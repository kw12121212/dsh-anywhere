import type { LocalState, SaveResult } from '../../main/view-state'

// preload 经 contextBridge 暴露的本地页面 API（主窗口与设置窗口共用）。
declare global {
  interface Window {
    dsh: {
      getState(): Promise<LocalState>
      onState(listener: (state: LocalState) => void): void
      retry(): Promise<void>
      openSettings(): Promise<void>
      completeSetup(raw: string): Promise<SaveResult>
      getSettingsURL(): Promise<string>
      saveSettings(raw: string): Promise<SaveResult>
    }
  }
}
