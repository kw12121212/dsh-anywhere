import type { BrowserWindowConstructorOptions } from 'electron'
import { APP_ENTRY_URL } from './app-protocol'

// 所有窗口共用的安全 webPreferences（技术标准 electron-layout-security/secure-webpreferences）。
export function createMainWindowOptions(preloadPath: string): BrowserWindowConstructorOptions {
  return {
    width: 1024,
    height: 768,
    title: 'dsh-anywhere',
    webPreferences: {
      preload: preloadPath,
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      nodeIntegrationInSubFrames: false,
      nodeIntegrationInWorker: false,
      webSecurity: true,
      allowRunningInsecureContent: false,
      experimentalFeatures: false
    }
  }
}

// 开发模式加载 electron-vite 开发服务器，其余情况加载 app:// 本地页面。
export function rendererEntryURL(isPackaged: boolean, devServerURL: string | undefined): string {
  if (!isPackaged && devServerURL) {
    return devServerURL
  }
  return APP_ENTRY_URL
}
