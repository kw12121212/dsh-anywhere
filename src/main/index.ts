import { join } from 'node:path'
import { BrowserWindow, app, net, protocol } from 'electron'
import { APP_SCHEME, createAppProtocolHandler } from './app-protocol'
import { createMainWindowOptions, rendererEntryURL } from './window'

protocol.registerSchemesAsPrivileged([
  { scheme: APP_SCHEME, privileges: { standard: true, secure: true, supportFetchAPI: true } }
])

function createMainWindow(): void {
  const window = new BrowserWindow(createMainWindowOptions(join(__dirname, '../preload/index.js')))
  window.loadURL(rendererEntryURL(app.isPackaged, process.env['ELECTRON_RENDERER_URL'])).catch(() => {
    console.error('failed to load local page')
  })
}

void app.whenReady().then(() => {
  protocol.handle(APP_SCHEME, createAppProtocolHandler(join(__dirname, '../renderer'), (url) => net.fetch(url)))
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
