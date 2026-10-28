import type { FailureReason } from './probe'

// 主窗口本地页面要显示的内容；由主进程下发，本地页面只负责渲染。
export type LocalState =
  | { view: 'connecting' }
  | { view: 'error'; url: string; reason: FailureReason }
  | { view: 'unconfigured'; invalidConfig: boolean }
  | { view: 'reauth' }

// 本地页面与主进程之间的 IPC 通道；远程视图不挂 preload，无法使用任何通道。
export const IPC_CHANNELS = {
  getState: 'main-window:get-state',
  retry: 'main-window:retry',
  state: 'main-window:state'
} as const
