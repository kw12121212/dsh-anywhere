import { join } from 'node:path'
import { BrowserWindow, Menu, WebContentsView, app, ipcMain, net, protocol, session, shell } from 'electron'
import { buildMenuTemplate } from './app-menu'
import { APP_SCHEME, createAppProtocolHandler } from './app-protocol'
import { CONFIG_FILE_NAME, ensurePrivateDir, loadConfig, redactURL } from './config'
import { allowedLocalOrigins, registerMainWindowIpc } from './ipc'
import { Navigator } from './navigator'
import { isRemotePermissionAllowed } from './permissions'
import { probe } from './probe'
import { attachRemoteView } from './remote-view'
import { SESSION_PARTITION } from './session'
import { IPC_CHANNELS } from './view-state'
import { createMainWindowOptions, createRemoteViewOptions, rendererEntryURL } from './window'

protocol.registerSchemesAsPrivileged([
  { scheme: APP_SCHEME, privileges: { standard: true, secure: true, supportFetchAPI: true } }
])

let navigator: Navigator | null = null

function createMainWindow(): void {
  const window = new BrowserWindow(createMainWindowOptions(join(__dirname, '../preload/index.js')))
  // 窗口标题固定为应用名，不随本地页面标题变化。
  window.on('page-title-updated', (event) => event.preventDefault())

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
  })

  local.loadURL(rendererEntryURL(app.isPackaged, process.env['ELECTRON_RENDERER_URL'])).catch(() => {
    console.error('failed to load local page')
  })
  void nav.open()
}

void app.whenReady().then(() => {
  ensurePrivateDir(app.getPath('userData'))
  protocol.handle(APP_SCHEME, createAppProtocolHandler(join(__dirname, '../renderer'), (url) => net.fetch(url)))
  registerMainWindowIpc(ipcMain, {
    allowedOrigins: allowedLocalOrigins(app.isPackaged, process.env['ELECTRON_RENDERER_URL']),
    getState: () => navigator?.localState ?? { view: 'connecting' },
    retry: () => void navigator?.open()
  })
  Menu.setApplicationMenu(
    Menu.buildFromTemplate(buildMenuTemplate(process.platform, { reload: () => void navigator?.open() }))
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
