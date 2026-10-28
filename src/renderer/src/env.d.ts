import type { LocalState } from '../../main/view-state'

// preload 经 contextBridge 暴露的本地页面 API。
declare global {
  interface Window {
    dsh: {
      getState(): Promise<LocalState>
      onState(listener: (state: LocalState) => void): void
      retry(): Promise<void>
    }
  }
}
