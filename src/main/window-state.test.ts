import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import {
  DEFAULT_WINDOW_HEIGHT,
  DEFAULT_WINDOW_WIDTH,
  WINDOW_STATE_FILE_NAME,
  type Screen,
  type WindowState,
  loadWindowState,
  resolve,
  saveWindowState
} from './window-state'

const PRIMARY: Screen = {
  bounds: { x: 0, y: 0, width: 1920, height: 1080 },
  workArea: { x: 0, y: 0, width: 1920, height: 1040 },
  isPrimary: true
}
const SECONDARY: Screen = {
  bounds: { x: 1920, y: 0, width: 1280, height: 720 },
  workArea: { x: 1920, y: 0, width: 1280, height: 680 },
  isPrimary: false
}
// 主显示器工作区 1920x1040 下默认窗口的居中位置。
const CENTERED = { x: 460, y: 211 }

describe('resolve', () => {
  it('没有保存状态时用默认尺寸在主显示器工作区居中', () => {
    expect(resolve(null, [PRIMARY, SECONDARY])).toEqual({
      ...CENTERED,
      width: DEFAULT_WINDOW_WIDTH,
      height: DEFAULT_WINDOW_HEIGHT,
      move: true,
      maximised: false
    })
  })

  it('没有显示器信息时不移动窗口，只使用默认尺寸（无法设置位置的会话）', () => {
    expect(resolve(null, [])).toEqual({
      x: 0,
      y: 0,
      width: DEFAULT_WINDOW_WIDTH,
      height: DEFAULT_WINDOW_HEIGHT,
      move: false,
      maximised: false
    })
  })

  it('保存的矩形与显示器相交时保留保存的位置与尺寸', () => {
    const saved: WindowState = { x: 200, y: 120, width: 1200, height: 800, maximised: false }
    expect(resolve(saved, [PRIMARY])).toEqual({ x: 200, y: 120, width: 1200, height: 800, move: true, maximised: false })
  })

  it('与主显示器只有部分重叠（左上角在屏内）时保留保存的位置与尺寸', () => {
    const saved: WindowState = { x: -400, y: -300, width: 1200, height: 800, maximised: false }
    expect(resolve(saved, [PRIMARY]).move).toBe(true)
    expect(resolve(saved, [PRIMARY]).x).toBe(-400)
  })

  it('与副显示器相交时同样保留', () => {
    const saved: WindowState = { x: 2100, y: 100, width: 1000, height: 618, maximised: false }
    expect(resolve(saved, [PRIMARY, SECONDARY])).toEqual({
      x: 2100,
      y: 100,
      width: 1000,
      height: 618,
      move: true,
      maximised: false
    })
  })

  it('保存位置与所有显示器都不相交时改用默认尺寸居中主显示器', () => {
    const saved: WindowState = { x: 5000, y: 3000, width: 1100, height: 700, maximised: false }
    expect(resolve(saved, [PRIMARY, SECONDARY])).toEqual({
      ...CENTERED,
      width: DEFAULT_WINDOW_WIDTH,
      height: DEFAULT_WINDOW_HEIGHT,
      move: true,
      maximised: false
    })
  })

  it('仅边缘相接不算相交，回退默认居中', () => {
    const saved: WindowState = { x: 1920, y: 100, width: 1000, height: 618, maximised: false }
    const placement = resolve(saved, [PRIMARY])
    expect(placement.move).toBe(true)
    expect(placement.x).toBe(CENTERED.x)
    expect(placement.width).toBe(DEFAULT_WINDOW_WIDTH)
  })

  it('保存位置离屏但处于最大化时，以默认几何居中且仍标记最大化', () => {
    const saved: WindowState = { x: 5000, y: 3000, width: 1100, height: 700, maximised: true }
    const placement = resolve(saved, [PRIMARY])
    expect(placement).toEqual({ ...CENTERED, width: DEFAULT_WINDOW_WIDTH, height: DEFAULT_WINDOW_HEIGHT, move: true, maximised: true })
  })

  it('在屏内且最大化时保留保存的位置尺寸与最大化标志', () => {
    const saved: WindowState = { x: 10, y: 20, width: 1000, height: 618, maximised: true }
    expect(resolve(saved, [PRIMARY]).maximised).toBe(true)
  })

  it('尺寸非正或超大时按没有保存状态处理', () => {
    for (const [width, height] of [
      [0, 618],
      [1000, -1],
      [32769, 618],
      [1000, 40000]
    ]) {
      const saved: WindowState = { x: 100, y: 100, width, height, maximised: false }
      const placement = resolve(saved, [PRIMARY])
      expect(placement.width).toBe(DEFAULT_WINDOW_WIDTH)
      expect(placement.height).toBe(DEFAULT_WINDOW_HEIGHT)
      expect(placement.move).toBe(true)
      expect(placement.x).toBe(CENTERED.x)
    }
  })

  it('没有显示器信息时仍恢复保存的尺寸与最大化（Wayland 只恢复尺寸与最大化）', () => {
    const saved: WindowState = { x: 200, y: 120, width: 1200, height: 800, maximised: true }
    expect(resolve(saved, [])).toEqual({ x: 0, y: 0, width: 1200, height: 800, move: false, maximised: true })
  })

  it('没有显示器标记为主屏时使用第一个显示器的工作区居中', () => {
    const screens: Screen[] = [{ ...PRIMARY, isPrimary: false }, SECONDARY]
    expect(resolve(null, screens).x).toBe(CENTERED.x)
  })

  it('主显示器工作区缺失时退回显示器边界居中', () => {
    const screen: Screen = { ...PRIMARY, workArea: { x: 0, y: 0, width: 0, height: 0 } }
    const placement = resolve(null, [screen])
    expect(placement.x).toBe(Math.trunc((1920 - DEFAULT_WINDOW_WIDTH) / 2))
    expect(placement.y).toBe(Math.trunc((1080 - DEFAULT_WINDOW_HEIGHT) / 2))
  })
})

describe('window-state 文件读写', () => {
  let dir: string

  afterEach(() => {
    if (dir) {
      rmSync(dir, { recursive: true, force: true })
      dir = undefined as unknown as string
    }
  })

  function statePath(): string {
    return join(dir, WINDOW_STATE_FILE_NAME)
  }

  it('保存后能读回相同状态，文件格式与 Go 版一致', () => {
    dir = mkdtempSync(join(tmpdir(), 'dsh-window-state-'))
    const state: WindowState = { x: 300, y: 200, width: 1100, height: 700, maximised: false }
    saveWindowState(statePath(), state)
    expect(readFileSync(statePath(), 'utf8')).toBe(
      `{\n  "x": 300,\n  "y": 200,\n  "width": 1100,\n  "height": 700,\n  "maximised": false\n}\n`
    )
    expect(loadWindowState(statePath())).toEqual(state)
  })

  it('保存覆盖上一次状态且目录中没有残留临时文件', () => {
    dir = mkdtempSync(join(tmpdir(), 'dsh-window-state-'))
    saveWindowState(statePath(), { x: 0, y: 0, width: 1200, height: 800, maximised: false })
    saveWindowState(statePath(), { x: 30, y: 40, width: 900, height: 600, maximised: true })
    expect(loadWindowState(statePath())).toEqual({ x: 30, y: 40, width: 900, height: 600, maximised: true })
    expect(readdirSync(dir).filter((name) => name.includes('.tmp'))).toEqual([])
  })

  it('保存窗口状态不影响同目录的 harness 配置文件', () => {
    dir = mkdtempSync(join(tmpdir(), 'dsh-window-state-'))
    const config = join(dir, 'config.json')
    writeFileSync(config, '{"harnessURL":"https://dsh.example/"}')
    saveWindowState(statePath(), { x: 1, y: 2, width: 1000, height: 618, maximised: false })
    expect(readFileSync(config, 'utf8')).toBe('{"harnessURL":"https://dsh.example/"}')
  })

  it('文件缺失、内容损坏、非对象内容都按没有状态处理', () => {
    dir = mkdtempSync(join(tmpdir(), 'dsh-window-state-'))
    expect(loadWindowState(statePath())).toBeNull()
    writeFileSync(statePath(), 'not json {{')
    expect(loadWindowState(statePath())).toBeNull()
    writeFileSync(statePath(), '[]')
    expect(loadWindowState(statePath())).toBeNull()
    writeFileSync(statePath(), '"hello"')
    expect(loadWindowState(statePath())).toBeNull()
    writeFileSync(statePath(), 'null')
    expect(loadWindowState(statePath())).toBeNull()
  })

  it('宽度为 0、负数、非整数或超大时按没有状态处理', () => {
    dir = mkdtempSync(join(tmpdir(), 'dsh-window-state-'))
    for (const width of [0, -5, 32769]) {
      writeFileSync(statePath(), JSON.stringify({ x: 0, y: 0, width, height: 618, maximised: false }))
      expect(loadWindowState(statePath())).toBeNull()
    }
    writeFileSync(statePath(), JSON.stringify({ x: 0, y: 0, width: 1000.5, height: 618, maximised: false }))
    expect(loadWindowState(statePath())).toBeNull()
  })

  it('字段类型不符按没有状态处理，缺失字段按零值处理', () => {
    dir = mkdtempSync(join(tmpdir(), 'dsh-window-state-'))
    writeFileSync(statePath(), JSON.stringify({ x: '10', y: 0, width: 1000, height: 618, maximised: false }))
    expect(loadWindowState(statePath())).toBeNull()
    writeFileSync(statePath(), JSON.stringify({ x: 0, y: 0, width: 1000, height: 618, maximised: 'yes' }))
    expect(loadWindowState(statePath())).toBeNull()
    // maximised 缺省为 false，未知字段忽略，负坐标合法（副显示器在主显示器左侧）。
    writeFileSync(statePath(), JSON.stringify({ y: -100, width: 1000, height: 618, extra: 1 }))
    expect(loadWindowState(statePath())).toEqual({ x: 0, y: -100, width: 1000, height: 618, maximised: false })
  })

  it('保存尺寸无效时拒绝写入而不是写坏状态文件', () => {
    dir = mkdtempSync(join(tmpdir(), 'dsh-window-state-'))
    expect(() => saveWindowState(statePath(), { x: 0, y: 0, width: 0, height: 618, maximised: false })).toThrow()
    expect(loadWindowState(statePath())).toBeNull()
  })
})
