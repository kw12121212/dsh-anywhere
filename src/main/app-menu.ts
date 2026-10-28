import type { MenuItemConstructorOptions } from 'electron'
import { ABOUT_TITLE, DISPLAY_NAME } from './app-info'

export const RELOAD_LABEL = '重新加载'
// macOS 上为 Cmd+R，其他平台为 Ctrl+R。
export const RELOAD_ACCELERATOR = 'CmdOrCtrl+R'
export const SETTINGS_LABEL = '设置…'
// macOS 上为 Cmd+,，其他平台为 Ctrl+,（settings-window 主规格 open-settings）。
export const SETTINGS_ACCELERATOR = 'CmdOrCtrl+,'

export interface MenuActions {
  reload(): void
  openSettings(): void
  showAbout(): void
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

// macOS 应用菜单自建而非 role:'appMenu'：系统默认的 about 项无法显示版本号，
// “关于 DSH Anywhere”必须显示名称与版本（app-identity 主规格）；其余子项逐项
// 复刻 Electron 默认 appMenu 的角色与顺序（设计决策 macos-custom-app-menu）。
function macAppMenu(actions: MenuActions): MenuItemConstructorOptions {
  return {
    label: DISPLAY_NAME,
    submenu: [
      { label: ABOUT_TITLE, click: () => actions.showAbout() },
      // 设置放在应用菜单（macOS Preferences 惯例）：menu-labels 主规格枚举 macOS 菜单栏
      // 无“文件”菜单，本变更不得新增（设计决策 settings-menu-placement）。
      { label: SETTINGS_LABEL, accelerator: SETTINGS_ACCELERATOR, click: () => actions.openSettings() },
      { type: 'separator' },
      { role: 'services', label: '服务' },
      { type: 'separator' },
      { role: 'hide', label: `隐藏 ${DISPLAY_NAME}` },
      { role: 'hideOthers', label: '隐藏其他' },
      { role: 'unhide', label: '显示全部' },
      { type: 'separator' },
      { role: 'quit', label: `退出 ${DISPLAY_NAME}` }
    ]
  }
}

// buildMenuTemplate 生成应用菜单。“重新加载”重新探测并打开已保存地址，不使用 Chromium 的 reload 角色；
// macOS 使用自建应用菜单（含关于与退出），其他平台在“文件”中提供退出、“帮助”中提供关于。
export function buildMenuTemplate(platform: NodeJS.Platform, actions: MenuActions): MenuItemConstructorOptions[] {
  const view: MenuItemConstructorOptions = {
    label: '视图',
    submenu: [{ label: RELOAD_LABEL, accelerator: RELOAD_ACCELERATOR, click: () => actions.reload() }]
  }
  if (platform === 'darwin') {
    return [macAppMenu(actions), editMenu(platform), view, MAC_WINDOW_MENU]
  }
  return [
    {
      label: '文件',
      submenu: [
        { label: SETTINGS_LABEL, accelerator: SETTINGS_ACCELERATOR, click: () => actions.openSettings() },
        { role: 'quit', label: '退出' }
      ]
    },
    editMenu(platform),
    view,
    { label: '帮助', submenu: [{ label: ABOUT_TITLE, click: () => actions.showAbout() }] }
  ]
}
