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

  it('macOS 使用标准应用菜单与编辑菜单', () => {
    const roles = buildMenuTemplate('darwin', { reload: () => {} }).map((item) => item.role ?? item.label)
    expect(roles).toEqual(['appMenu', 'editMenu', '视图', 'windowMenu'])
  })

  it('Windows 与 Linux 在“文件”中提供退出', () => {
    for (const platform of ['win32', 'linux'] as const) {
      const template = buildMenuTemplate(platform, { reload: () => {} })
      expect(template.map((item) => item.role ?? item.label)).toEqual(['文件', 'editMenu', '视图'])
      expect(template[0]?.submenu).toEqual([{ role: 'quit', label: '退出' }])
    }
  })
})
