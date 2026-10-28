import { join } from 'node:path'
import { BrowserWindow, Menu, WebContentsView, app, dialog, ipcMain, net, protocol, screen, session, shell } from 'electron'
import { ABOUT_TITLE, aboutMessage } from './app-info'
import { buildMenuTemplate } from './app-menu'
import { APP_SCHEME, createAppProtocolHandler } from './app-protocol'
import { APP_VERSION } from './version'
import { CONFIG_FILE_NAME, ensurePrivateDir, loadConfig, saveConfig } from './config'
import { URLValidationError, redactURL } from './url-validation'
import { allowedLocalOrigins, registerLocalPageIpc } from './ipc'
import { Navigator } from './navigator'
import { isRemotePermissionAllowed } from './permissions'
import { probe } from './probe'
import { attachRemoteView } from './remote-view'
import { SESSION_PARTITION } from './session'
import { shouldOpenRemoteAfterSettingsSave } from './settings-policy'
import { SettingsWindowManager } from './settings-window'
import { SingleInstanceActivator } from './single-instance'
import { IPC_CHANNELS } from './view-state'
import type { SaveResult } from './view-state'
import { type Screen, WINDOW_STATE_FILE_NAME, loadWindowState, resolve, saveWindowState } from './window-state'
import {
  createMainWindowOptions,
  createRemoteViewOptions,
  createSettingsWindowOptions,
  rendererEntryURL,
  settingsEntryURL
} from './window'

let navigator: Navigator | null = null
let mainWindow: BrowserWindow | null = null
const activator = new SingleInstanceActivator()
const settingsManager = new SettingsWindowManager()

// Wayland 会话下窗口位置由合成器决定，传入空显示器列表使 resolve 只恢复尺寸与最大化。
function currentScreens(): Screen[] {
  if (process.env['XDG_SESSION_TYPE'] === 'wayland') {
    return []
  }
  const primaryId = screen.getPrimaryDisplay().id
  return screen.getAllDisplays().map((display) => ({
    bounds: display.bounds,
    workArea: display.workArea,
    isPrimary: display.id === primaryId
  }))
}

function showAbout(): void {
  const options = {
    type: 'info' as const,
    title: ABOUT_TITLE,
    message: aboutMessage(APP_VERSION),
    buttons: ['确定']
  }
  const parent = mainWindow && !mainWindow.isDestroyed() ? mainWindow : undefined
  void (parent ? dialog.showMessageBox(parent, options) : dialog.showMessageBox(options))
}

// createSettingsWindow 新建设置窗口：与主窗口同为 app:// 本地页面，挂同一 preload；
// 本地页面不导航、不弹窗，标题固定为“设置”；应用正在退出（主窗口已销毁）时返回 null。
function createSettingsWindow(): BrowserWindow | null {
  if (mainWindow === null || mainWindow.isDestroyed()) {
    return null
  }
  const window = new BrowserWindow(createSettingsWindowOptions(join(__dirname, '../preload/index.js')))
  window.on('page-title-updated', (event) => event.preventDefault())
  const local = window.webContents
  local.on('will-navigate', (event) => event.preventDefault())
  local.setWindowOpenHandler(() => ({ action: 'deny' }))
  window.once('ready-to-show', () => {
    window.show()
    window.focus()
  })
  local.loadURL(settingsEntryURL(app.isPackaged, process.env['ELECTRON_RENDERER_URL'])).catch(() => {
    console.error('failed to load settings page')
  })
  return window
}

function openSettings(): void {
  settingsManager.open(createSettingsWindow)
}

// 保存失败转换为返回给本地页面的固定可读文本：校验错误本身即固定文本，
// 文件系统错误不回显路径或输入（技术标准 security-secrets/no-input-echo-errors）。
function saveFailure(err: unknown): SaveResult {
  if (err instanceof URLValidationError) {
    return { ok: false, error: err.message }
  }
  console.error(`failed to save config: ${String(err instanceof Error ? err.message : err)}`)
  return { ok: false, error: '保存失败：无法写入配置文件。' }
}

function createMainWindow(): void {
  const window = new BrowserWindow(createMainWindowOptions(join(__dirname, '../preload/index.js')))
  mainWindow = window
  // 窗口标题固定为应用名，不随本地页面标题变化。
  window.on('page-title-updated', (event) => event.preventDefault())

  // 窗口几何恢复（window-state 主规格）：隐藏创建后按解析结果应用尺寸、显示、位置、
  // 最大化，避免先按默认几何出现再跳动（顺序与 Go 版 Restore 一致）。
  const statePath = join(app.getPath('userData'), WINDOW_STATE_FILE_NAME)
  const placement = resolve(loadWindowState(statePath), currentScreens())
  window.setSize(placement.width, placement.height)
  window.show()
  if (placement.move) {
    window.setPosition(placement.x, placement.y)
  }
  if (placement.maximised) {
    window.maximize()
  }

  activator.bind({
    isDestroyed: () => window.isDestroyed(),
    isMinimised: () => window.isMinimized(),
    restore: () => window.restore(),
    show: () => window.show(),
    focus: () => window.focus()
  })
  activator.start()

  // 关闭主窗口或退出应用时保存当前几何；窗口已销毁或几何非正时跳过，
  // 宁可保留旧状态（跳过语义与 Go 版 Manager.Save 一致）。
  window.on('close', () => {
    if (window.isDestroyed()) {
      return
    }
    const [width, height] = window.getSize()
    if (width <= 0 || height <= 0) {
      return
    }
    const [x, y] = window.getPosition()
    try {
      saveWindowState(statePath, { x, y, width, height, maximised: window.isMaximized() })
    } catch (error) {
      console.error(`failed to save window state: ${String(error)}`)
    }
  })

  const local = window.webContents
  // 本地页面不导航、不弹窗。
  local.on('will-navigate', (event) => event.preventDefault())
  local.setWindowOpenHandler(() => ({ action: 'deny' }))

  const remote = new WebContentsView(createRemoteViewOptions())
  let remoteShown = false
  const fitRemote = (): void => {
    const [width = 0, height = 0] = window.getContentSize()
    remote.setBounds({ x: 0, y: 0, width, height })
  }
  window.on('resize', fitRemote)

  const configPath = join(app.getPath('userData'), CONFIG_FILE_NAME)
  const remoteSession = session.fromPartition(SESSION_PARTITION)
  remoteSession.setPermissionRequestHandler((_contents, permission, callback) => callback(isRemotePermissionAllowed(permission)))
  remoteSession.setPermissionCheckHandler((_contents, permission) => isRemotePermissionAllowed(permission))
  const nav = new Navigator({
    loadConfig: () => loadConfig(configPath),
    probe: (url) => probe(url, (target) => net.request({ url: target, session: remoteSession, redirect: 'manual', useSessionCookies: false })),
    showLocal: (state) => {
      if (remoteShown) {
        window.contentView.removeChildView(remote)
        remoteShown = false
      }
      local.send(IPC_CHANNELS.state, state)
      local.focus()
    },
    showRemote: (url) => {
      if (!remoteShown) {
        window.contentView.addChildView(remote)
        remoteShown = true
        fitRemote()
      }
      remote.webContents.loadURL(url).catch(() => {
        // 失败由 did-fail-load 处理。
      })
      remote.webContents.focus()
    }
  })
  navigator = nav

  attachRemoteView(remote.webContents, {
    harnessURL: () => nav.harnessURL,
    openExternal: (url) => {
      shell.openExternal(url).catch(() => console.error(`failed to open external url ${redactURL(url)}`))
    },
    generation: () => nav.generationId,
    sessionExpired: (observed) => nav.sessionExpired(observed),
    loadFailed: (reason) => nav.loadFailed(reason)
  })

  window.on('closed', () => {
    nav.dispose()
    remote.webContents.close()
    if (navigator === nav) {
      navigator = null
    }
    if (mainWindow === window) {
      mainWindow = null
    }
    // 关闭主窗口即退出应用（Go 版 main.go 语义）：设置窗口等辅助窗口不能让进程继续存活。
    app.quit()
  })

  local.loadURL(rendererEntryURL(app.isPackaged, process.env['ELECTRON_RENDERER_URL'])).catch(() => {
    console.error('failed to load local page')
  })
  void nav.open()
}

// requestSingleInstanceLock 必须在 app ready 之前调用；未获锁的进程随即以成功状态退出，
// 只有获锁的实例继续初始化（single-instance 主规格）。
if (app.requestSingleInstanceLock()) {
  app.on('second-instance', () => activator.request())

  protocol.registerSchemesAsPrivileged([
    { scheme: APP_SCHEME, privileges: { standard: true, secure: true, supportFetchAPI: true } }
  ])

  void app.whenReady().then(() => {
    ensurePrivateDir(app.getPath('userData'))
    protocol.handle(APP_SCHEME, createAppProtocolHandler(join(__dirname, '../renderer'), (url) => net.fetch(url)))
    const configPath = join(app.getPath('userData'), CONFIG_FILE_NAME)
    registerLocalPageIpc(ipcMain, {
      allowedOrigins: allowedLocalOrigins(app.isPackaged, process.env['ELECTRON_RENDERER_URL']),
      getState: () => navigator?.localState ?? { view: 'connecting' },
      retry: () => void navigator?.open(),
      openSettings,
      completeSetup: (raw) => {
        // 初始化完成：保存干净地址后用原始地址（可能带 token）打开主窗口一次以建立会话
        // （first-run-setup 主规格 complete-setup）；失败时不导航，页面留在初始化界面。
        try {
          saveConfig(configPath, raw)
        } catch (err) {
          return saveFailure(err)
        }
        navigator?.openRemote(raw)
        return { ok: true }
      },
      getSettingsURL: () => {
        // 与 Go 版 CurrentURL 一致：未配置或配置无效时为空字符串。
        const config = loadConfig(configPath)
        return config.status === 'ok' ? config.url : ''
      },
      saveSettings: (raw) => {
        // 设置保存：保存干净地址，按 settings-policy 判定主窗口是否用原始地址打开一次。
        // 成功后设置窗口由其页面自行 window.close() 关闭；主进程不在该窗口自己的 IPC
        // 处理函数里同步关窗，销毁竞态曾导致主进程在部分环境下崩溃。
        const previous = loadConfig(configPath)
        let clean: string
        try {
          clean = saveConfig(configPath, raw)
        } catch (err) {
          return saveFailure(err)
        }
        if (
          shouldOpenRemoteAfterSettingsSave({
            previous,
            clean,
            trimmedInput: raw.trim(),
            mainShowsHarness: navigator?.showsHarness ?? false
          })
        ) {
          navigator?.openRemote(raw)
        }
        return { ok: true }
      }
    })
    Menu.setApplicationMenu(
      Menu.buildFromTemplate(
        buildMenuTemplate(process.platform, { reload: () => void navigator?.open(), openSettings, showAbout })
      )
    )
    createMainWindow()
  })
} else {
  app.quit()
}
