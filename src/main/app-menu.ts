import type { MenuItemConstructorOptions } from 'electron'

export const RELOAD_LABEL = '重新加载'
// macOS 上为 Cmd+R，其他平台为 Ctrl+R。
export const RELOAD_ACCELERATOR = 'CmdOrCtrl+R'

export interface MenuActions {
  reload(): void
}

// 编辑菜单：与 Electron 默认 editMenu 的结构一致，保留系统编辑角色（行为与快捷键由平台提供），只把标签改为中文。
// macOS 的 webview 依赖编辑菜单才能使用 Cmd+C/V。
function editMenu(platform: NodeJS.Platform): MenuItemConstructorOptions {
  const common: MenuItemConstructorOptions[] = [
    { role: 'undo', label: '撤销' },
    { role: 'redo', label: '重做' },
    { type: 'separator' },
    { role: 'cut', label: '剪切' },
    { role: 'copy', label: '复制' },
    { role: 'paste', label: '粘贴' }
  ]
  if (platform === 'darwin') {
    return {
      label: '编辑',
      submenu: [
        ...common,
        { role: 'pasteAndMatchStyle', label: '粘贴并匹配样式' },
        { role: 'delete', label: '删除' },
        { role: 'selectAll', label: '全选' },
        { type: 'separator' },
        {
          label: '语音',
          submenu: [
            { role: 'startSpeaking', label: '开始朗读' },
            { role: 'stopSpeaking', label: '停止朗读' }
          ]
        }
      ]
    }
  }
  return {
    label: '编辑',
    submenu: [...common, { role: 'delete', label: '删除' }, { type: 'separator' }, { role: 'selectAll', label: '全选' }]
  }
}

// macOS 窗口菜单：保留 windowMenu 角色（系统会追加窗口列表），子项与 Electron 默认一致，标签为中文。
const MAC_WINDOW_MENU: MenuItemConstructorOptions = {
  role: 'windowMenu',
  label: '窗口',
  submenu: [
    { role: 'minimize', label: '最小化' },
    { role: 'zoom', label: '缩放' },
    { type: 'separator' },
    { role: 'front', label: '前置全部窗口' }
  ]
}

// buildMenuTemplate 生成应用菜单。“重新加载”重新探测并打开已保存地址，不使用 Chromium 的 reload 角色；
// macOS 使用标准应用菜单（含退出），其他平台在“文件”中提供退出。
export function buildMenuTemplate(platform: NodeJS.Platform, actions: MenuActions): MenuItemConstructorOptions[] {
  const view: MenuItemConstructorOptions = {
    label: '视图',
    submenu: [{ label: RELOAD_LABEL, accelerator: RELOAD_ACCELERATOR, click: () => actions.reload() }]
  }
  if (platform === 'darwin') {
    return [{ role: 'appMenu' }, editMenu(platform), view, MAC_WINDOW_MENU]
  }
  return [{ label: '文件', submenu: [{ role: 'quit', label: '退出' }] }, editMenu(platform), view]
}
