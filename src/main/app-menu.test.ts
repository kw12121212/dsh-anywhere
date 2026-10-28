import type { MenuItemConstructorOptions } from 'electron'
import { describe, expect, it } from 'vitest'
import { RELOAD_ACCELERATOR, RELOAD_LABEL, buildMenuTemplate } from './app-menu'

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

describe('buildMenuTemplate', () => {
  it('三平台都有“重新加载”（CmdOrCtrl+R），点击调用重新探测而不是 Chromium reload', () => {
    for (const platform of ['darwin', 'win32', 'linux'] as const) {
      let reloads = 0
      const reload = findReload(buildMenuTemplate(platform, { reload: () => reloads++ }))
      expect(reload?.accelerator).toBe(RELOAD_ACCELERATOR)
      expect(reload?.role).toBeUndefined()
      ;(reload?.click as () => void)()
      expect(reloads).toBe(1)
    }
  })

  it('macOS 使用标准应用菜单，其后的编辑、视图、窗口菜单为中文', () => {
    const template = buildMenuTemplate('darwin', { reload: () => {} })
    expect(template[0]).toEqual({ role: 'appMenu' })
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

  it('Windows 与 Linux 的菜单栏为文件、编辑、视图，“文件”中提供退出', () => {
    for (const platform of ['win32', 'linux'] as const) {
      const template = buildMenuTemplate(platform, { reload: () => {} })
      expect(topLabels(template)).toEqual(['文件', '编辑', '视图'])
      expect(template[0]?.submenu).toEqual([{ role: 'quit', label: '退出' }])
    }
  })

  it('编辑菜单子项为中文，结构与 Electron 默认编辑菜单一致并保留系统编辑角色', () => {
    const expected = { darwin: MAC_EDIT_ITEMS, win32: WIN_LINUX_EDIT_ITEMS, linux: WIN_LINUX_EDIT_ITEMS }
    for (const platform of ['darwin', 'win32', 'linux'] as const) {
      const edit = buildMenuTemplate(platform, { reload: () => {} }).find((item) => item.label === '编辑')
      expect(edit?.role).toBeUndefined()
      expect(edit?.submenu).toEqual(expected[platform])
    }
  })
})
