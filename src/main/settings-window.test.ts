import { describe, expect, it } from 'vitest'
import { SettingsWindowManager, type SettingsWindowLike } from './settings-window'

class FakeWindow implements SettingsWindowLike {
  shown = 0
  focused = 0
  destroyed = false

  isDestroyed(): boolean {
    return this.destroyed
  }

  show(): void {
    this.shown++
  }

  focus(): void {
    this.focused++
  }
}

describe('SettingsWindowManager', () => {
  it('首次 open 创建窗口，已打开时再次 open 聚焦已有窗口不新建', () => {
    const manager = new SettingsWindowManager()
    const first = new FakeWindow()
    let created = 0
    manager.open(() => {
      created++
      return first
    })
    manager.open(() => {
      throw new Error('不应再次创建')
    })
    expect(created).toBe(1)
    expect(first.shown).toBe(1)
    expect(first.focused).toBe(1)
  })

  it('用户直接关闭窗口（已销毁）后再次 open 新建', () => {
    const manager = new SettingsWindowManager()
    const first = new FakeWindow()
    let created = 0
    const create = (): FakeWindow => {
      created++
      return new FakeWindow()
    }
    manager.open(() => {
      created++
      return first
    })
    first.destroyed = true
    const second = new FakeWindow()
    manager.open(() => {
      created++
      return second
    })
    expect(created).toBe(2)
  })

  it('create 返回 null 时不记住窗口也不抛出', () => {
    const manager = new SettingsWindowManager()
    expect(() => manager.open(() => null)).not.toThrow()
    const first = new FakeWindow()
    manager.open(() => first)
    expect(first.shown).toBe(0)
  })
})
