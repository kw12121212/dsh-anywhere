import { contextBridge, ipcRenderer } from 'electron'
import { IPC_CHANNELS } from '../main/view-state'
import type { LocalState, SaveResult } from '../main/view-state'

// 只挂在 app:// 本地页面（主窗口与设置窗口）上；远程 harness 视图不挂 preload。
contextBridge.exposeInMainWorld('dsh', {
  getState: (): Promise<LocalState> => ipcRenderer.invoke(IPC_CHANNELS.getState),
  onState: (listener: (state: LocalState) => void): void => {
    ipcRenderer.on(IPC_CHANNELS.state, (_event, state: LocalState) => listener(state))
  },
  retry: (): Promise<void> => ipcRenderer.invoke(IPC_CHANNELS.retry),
  openSettings: (): Promise<void> => ipcRenderer.invoke(IPC_CHANNELS.openSettings),
  completeSetup: (raw: string): Promise<SaveResult> => ipcRenderer.invoke(IPC_CHANNELS.completeSetup, raw),
  getSettingsURL: (): Promise<string> => ipcRenderer.invoke(IPC_CHANNELS.getSettingsURL),
  saveSettings: (raw: string): Promise<SaveResult> => ipcRenderer.invoke(IPC_CHANNELS.saveSettings, raw)
})
