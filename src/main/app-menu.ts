import type { MenuItemConstructorOptions } from 'electron'

export const RELOAD_LABEL = '重新加载'
// macOS 上为 Cmd+R，其他平台为 Ctrl+R。
export const RELOAD_ACCELERATOR = 'CmdOrCtrl+R'

export interface MenuActions {
  reload(): void
}

// buildMenuTemplate 生成应用菜单。“重新加载”重新探测并打开已保存地址，不使用 Chromium 的 reload 角色；
// macOS 使用标准应用菜单（含退出），其他平台在“文件”中提供退出。
export function buildMenuTemplate(platform: NodeJS.Platform, actions: MenuActions): MenuItemConstructorOptions[] {
  const view: MenuItemConstructorOptions = {
    label: '视图',
    submenu: [{ label: RELOAD_LABEL, accelerator: RELOAD_ACCELERATOR, click: () => actions.reload() }]
  }
  if (platform === 'darwin') {
    return [{ role: 'appMenu' }, { role: 'editMenu' }, view, { role: 'windowMenu' }]
  }
  return [{ label: '文件', submenu: [{ role: 'quit', label: '退出' }] }, { role: 'editMenu' }, view]
}
