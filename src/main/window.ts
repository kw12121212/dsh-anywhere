import type { BrowserWindowConstructorOptions, WebContentsViewConstructorOptions, WebPreferences } from 'electron'
import { DISPLAY_NAME } from './app-info'
import { APP_ENTRY_URL } from './app-protocol'
import { SESSION_PARTITION } from './session'
import { DEFAULT_WINDOW_HEIGHT, DEFAULT_WINDOW_WIDTH } from './window-state'

export const MAIN_WINDOW_TITLE = DISPLAY_NAME

// 所有窗口与视图共用的安全 webPreferences（技术标准 electron-layout-security/secure-webpreferences）。
const SECURE_WEB_PREFERENCES = {
  contextIsolation: true,
  sandbox: true,
  nodeIntegration: false,
  nodeIntegrationInSubFrames: false,
  nodeIntegrationInWorker: false,
  webSecurity: true,
  allowRunningInsecureContent: false,
  experimentalFeatures: false
} as const satisfies WebPreferences

// 主窗口自身的 webContents 只显示 app:// 本地页面，挂 preload 以调用主进程。
// 默认几何为 1000x618（window-state 主规格 no-saved-state），先隐藏创建，几何恢复
// 完成后再显示以避免闪烁（见 index.ts 的恢复接线）。
export function createMainWindowOptions(preloadPath: string): BrowserWindowConstructorOptions {
  return {
    width: DEFAULT_WINDOW_WIDTH,
    height: DEFAULT_WINDOW_HEIGHT,
    show: false,
    title: MAIN_WINDOW_TITLE,
    webPreferences: { ...SECURE_WEB_PREFERENCES, preload: preloadPath }
  }
}

// 远程 harness 视图：持久化分区保存会话 cookie，不挂 preload（技术标准 remote-content-no-preload）。
export function createRemoteViewOptions(): WebContentsViewConstructorOptions {
  return {
    webPreferences: { ...SECURE_WEB_PREFERENCES, partition: SESSION_PARTITION }
  }
}

// 开发模式加载 electron-vite 开发服务器，其余情况加载 app:// 本地页面。
export function rendererEntryURL(isPackaged: boolean, devServerURL: string | undefined): string {
  if (!isPackaged && devServerURL) {
    return devServerURL
  }
  return APP_ENTRY_URL
}
