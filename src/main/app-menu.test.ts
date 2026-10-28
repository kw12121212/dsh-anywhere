import type { MenuItemConstructorOptions } from 'electron'
import { describe, expect, it } from 'vitest'
import { ABOUT_TITLE, DISPLAY_NAME, aboutMessage } from './app-info'
import { RELOAD_ACCELERATOR, RELOAD_LABEL, SETTINGS_ACCELERATOR, SETTINGS_LABEL, buildMenuTemplate } from './app-menu'

function findReload(template: MenuItemConstructorOptions[]): MenuItemConstructorOptions | undefined {
  for (const item of template) {
    if (Array.isArray(item.submenu)) {
      const found = item.submenu.find((sub) => sub.label === RELOAD_LABEL)
      if (found) {
        return found
      }
    }
  }
  return undefined
}

function findAbout(template: MenuItemConstructorOptions[]): MenuItemConstructorOptions | undefined {
  for (const item of template) {
    if (Array.isArray(item.submenu)) {
      const found = item.submenu.find((sub) => sub.label === ABOUT_TITLE)
      if (found) {
        return found
      }
    }
  }
  return undefined
}

function findSettings(template: MenuItemConstructorOptions[]): MenuItemConstructorOptions | undefined {
  for (const item of template) {
    if (Array.isArray(item.submenu)) {
      const found = item.submenu.find((sub) => sub.label === SETTINGS_LABEL)
      if (found) {
        return found
      }
    }
  }
  return undefined
}

function topLabels(template: MenuItemConstructorOptions[]): (string | undefined)[] {
  return template.map((item) => item.label ?? item.role)
}

// 与 Electron 默认 editMenu 的结构一致，只把标签改为中文。
const COMMON_EDIT_ITEMS = [
  { role: 'undo', label: '撤销' },
  { role: 'redo', label: '重做' },
  { type: 'separator' },
  { role: 'cut', label: '剪切' },
  { role: 'copy', label: '复制' },
  { role: 'paste', label: '粘贴' }
]
const WIN_LINUX_EDIT_ITEMS = [
  ...COMMON_EDIT_ITEMS,
  { role: 'delete', label: '删除' },
  { type: 'separator' },
  { role: 'selectAll', label: '全选' }
]
const MAC_EDIT_ITEMS = [
  ...COMMON_EDIT_ITEMS,
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

const ACTIONS = { reload: () => {}, openSettings: () => {}, showAbout: () => {} }

describe('buildMenuTemplate', () => {
  it('三平台都有“重新加载”（CmdOrCtrl+R），点击调用重新探测而不是 Chromium reload', () => {
    for (const platform of ['darwin', 'win32', 'linux'] as const) {
      let reloads = 0
      const reload = findReload(buildMenuTemplate(platform, { ...ACTIONS, reload: () => reloads++ }))
      expect(reload?.accelerator).toBe(RELOAD_ACCELERATOR)
      expect(reload?.role).toBeUndefined()
      ;(reload?.click as () => void)()
      expect(reloads).toBe(1)
    }
  })

  it('三平台都有“关于 DSH Anywhere”，点击调用 showAbout', () => {
    for (const platform of ['darwin', 'win32', 'linux'] as const) {
      let abouts = 0
      const about = findAbout(buildMenuTemplate(platform, { ...ACTIONS, showAbout: () => abouts++ }))
      expect(about?.label).toBe(ABOUT_TITLE)
      ;(about?.click as () => void)()
      expect(abouts).toBe(1)
    }
  })

  it('三平台都有“设置…”（CmdOrCtrl+,），点击调用 openSettings', () => {
    for (const platform of ['darwin', 'win32', 'linux'] as const) {
      let opens = 0
      const settings = findSettings(buildMenuTemplate(platform, { ...ACTIONS, openSettings: () => opens++ }))
      expect(settings?.label).toBe(SETTINGS_LABEL)
      expect(settings?.accelerator).toBe(SETTINGS_ACCELERATOR)
      expect(SETTINGS_ACCELERATOR).toBe('CmdOrCtrl+,')
      ;(settings?.click as () => void)()
      expect(opens).toBe(1)
    }
  })

  it('macOS 应用菜单自建：首项为关于，设置…紧随其后，其余逐项复刻默认 appMenu 角色，标签为中文', () => {
    const template = buildMenuTemplate('darwin', ACTIONS)
    expect(template[0]).toEqual({
      label: DISPLAY_NAME,
      submenu: [
        { label: ABOUT_TITLE, click: expect.any(Function) },
        { label: SETTINGS_LABEL, accelerator: SETTINGS_ACCELERATOR, click: expect.any(Function) },
        { type: 'separator' },
        { role: 'services', label: '服务' },
        { type: 'separator' },
        { role: 'hide', label: `隐藏 ${DISPLAY_NAME}` },
        { role: 'hideOthers', label: '隐藏其他' },
        { role: 'unhide', label: '显示全部' },
        { type: 'separator' },
        { role: 'quit', label: `退出 ${DISPLAY_NAME}` }
      ]
    })
    expect(topLabels(template.slice(1))).toEqual(['编辑', '视图', '窗口'])
    expect(template[3]).toEqual({
      role: 'windowMenu',
      label: '窗口',
      submenu: [
        { role: 'minimize', label: '最小化' },
        { role: 'zoom', label: '缩放' },
        { type: 'separator' },
        { role: 'front', label: '前置全部窗口' }
      ]
    })
  })

  it('Windows 与 Linux 的菜单栏为文件、编辑、视图、帮助，“文件”中提供设置…与退出，“帮助”中提供关于', () => {
    for (const platform of ['win32', 'linux'] as const) {
      const template = buildMenuTemplate(platform, ACTIONS)
      expect(topLabels(template)).toEqual(['文件', '编辑', '视图', '帮助'])
      expect(template[0]?.submenu).toEqual([
        { label: SETTINGS_LABEL, accelerator: SETTINGS_ACCELERATOR, click: expect.any(Function) },
        { role: 'quit', label: '退出' }
      ])
      expect(template[3]?.submenu).toEqual([{ label: ABOUT_TITLE, click: expect.any(Function) }])
    }
  })

  it('编辑菜单子项为中文，结构与 Electron 默认编辑菜单一致并保留系统编辑角色', () => {
    const expected = { darwin: MAC_EDIT_ITEMS, win32: WIN_LINUX_EDIT_ITEMS, linux: WIN_LINUX_EDIT_ITEMS }
    for (const platform of ['darwin', 'win32', 'linux'] as const) {
      const edit = buildMenuTemplate(platform, ACTIONS).find((item) => item.label === '编辑')
      expect(edit?.role).toBeUndefined()
      expect(edit?.submenu).toEqual(expected[platform])
    }
  })
})

describe('应用身份与关于文案', () => {
  it('显示名称与关于标题同源', () => {
    expect(DISPLAY_NAME).toBe('DSH Anywhere')
    expect(ABOUT_TITLE).toBe('关于 DSH Anywhere')
  })

  it('关于正文同时显示应用名称与版本号（标签构建与未打标签构建）', () => {
    expect(aboutMessage('0.1.0')).toBe('DSH Anywhere\n版本 0.1.0')
    expect(aboutMessage('dev')).toBe('DSH Anywhere\n版本 dev')
  })
})
