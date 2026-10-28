import { contextBridge, ipcRenderer } from 'electron'
import { IPC_CHANNELS } from '../main/view-state'
import type { LocalState } from '../main/view-state'

// 只挂在 app:// 本地页面上；远程 harness 视图不挂 preload。
contextBridge.exposeInMainWorld('dsh', {
  getState: (): Promise<LocalState> => ipcRenderer.invoke(IPC_CHANNELS.getState),
  onState: (listener: (state: LocalState) => void): void => {
    ipcRenderer.on(IPC_CHANNELS.state, (_event, state: LocalState) => listener(state))
  },
  retry: (): Promise<void> => ipcRenderer.invoke(IPC_CHANNELS.retry)
})
