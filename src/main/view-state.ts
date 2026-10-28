import type { FailureReason } from './probe'

// 主窗口本地页面要显示的内容；由主进程下发，本地页面只负责渲染。
export type LocalState =
  | { view: 'connecting' }
  | { view: 'error'; url: string; reason: FailureReason }
  | { view: 'unconfigured'; invalidConfig: boolean }
  | { view: 'reauth' }

// 保存类 IPC（初始化完成、设置保存）的返回：失败时 error 为不含 token 的固定可读文本，
// 不经由抛错传递，避免 IPC 错误包装把通道名等细节暴露给页面。
export type SaveResult = { ok: true } | { ok: false; error: string }

// 本地页面与主进程之间的 IPC 通道；远程视图不挂 preload，无法使用任何通道。
export const IPC_CHANNELS = {
  getState: 'main-window:get-state',
  retry: 'main-window:retry',
  state: 'main-window:state',
  openSettings: 'main-window:open-settings',
  completeSetup: 'setup:complete',
  getSettingsURL: 'settings:get-url',
  saveSettings: 'settings:save'
} as const
