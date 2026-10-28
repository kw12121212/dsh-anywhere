// 远程 harness 会话的权限策略：Electron 默认批准所有权限请求，这里改为默认拒绝，只放行白名单。

// clipboard-sanitized-write 让 harness 的“复制”按钮可写剪贴板；fullscreen 让页面内容可全屏显示。
export const ALLOWED_REMOTE_PERMISSIONS: readonly string[] = ['clipboard-sanitized-write', 'fullscreen']

export function isRemotePermissionAllowed(permission: string): boolean {
  return ALLOWED_REMOTE_PERMISSIONS.includes(permission)
}
