import { join } from 'node:path'
import { BrowserWindow, Menu, WebContentsView, app, dialog, ipcMain, net, protocol, screen, session, shell } from 'electron'
import { ABOUT_TITLE, aboutMessage } from './app-info'
import { buildMenuTemplate } from './app-menu'
import { APP_SCHEME, createAppProtocolHandler } from './app-protocol'
import { APP_VERSION } from './version'
import { CONFIG_FILE_NAME, ensurePrivateDir, loadConfig, redactURL } from './config'
import { allowedLocalOrigins, registerMainWindowIpc } from './ipc'
import { Navigator } from './navigator'
import { isRemotePermissionAllowed } from './permissions'
import { probe } from './probe'
import { attachRemoteView } from './remote-view'
import { SESSION_PARTITION } from './session'
import { SingleInstanceActivator } from './single-instance'
import { IPC_CHANNELS } from './view-state'
import { type Screen, WINDOW_STATE_FILE_NAME, loadWindowState, resolve, saveWindowState } from './window-state'
import { createMainWindowOptions, createRemoteViewOptions, rendererEntryURL } from './window'

let navigator: Navigator | null = null
let mainWindow: BrowserWindow | null = null
const activator = new SingleInstanceActivator()

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
    registerMainWindowIpc(ipcMain, {
      allowedOrigins: allowedLocalOrigins(app.isPackaged, process.env['ELECTRON_RENDERER_URL']),
      getState: () => navigator?.localState ?? { view: 'connecting' },
      retry: () => void navigator?.open()
    })
    Menu.setApplicationMenu(
      Menu.buildFromTemplate(buildMenuTemplate(process.platform, { reload: () => void navigator?.open(), showAbout }))
    )
    createMainWindow()

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        createMainWindow()
      }
    })
  })

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
      app.quit()
    }
  })
} else {
  app.quit()
}
