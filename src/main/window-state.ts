import { closeSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, rmSync, writeSync } from 'node:fs'
import { basename, dirname, join } from 'node:path'

// 窗口状态文件名，位于应用配置目录（userData），与 Go 版 internal/windowstate 相同。
export const WINDOW_STATE_FILE_NAME = 'window-state.json'

// 主窗口首次启动与越界回退时使用的尺寸（window-state 主规格 no-saved-state）。
export const DEFAULT_WINDOW_WIDTH = 1000
export const DEFAULT_WINDOW_HEIGHT = 618

// 接受的最大窗口尺寸，超过即视为状态无效，避免异常数值撑坏窗口。
const MAX_DIMENSION = 32768

// 保存的窗口状态，位置与尺寸均为显示坐标（DIP）；JSON 字段名与 Go 版一致。
export interface WindowState {
  x: number
  y: number
  width: number
  height: number
  maximised: boolean
}

// 屏幕坐标系中的矩形。
export interface Rect {
  x: number
  y: number
  width: number
  height: number
}

// 恢复所需的显示器信息，由装配层从 Electron screen API 适配而来。
export interface Screen {
  bounds: Rect
  workArea: Rect
  isPrimary: boolean
}

// 把保存状态换算成的一次恢复动作；move 为 false 时不设置窗口位置。
export interface Placement {
  x: number
  y: number
  width: number
  height: number
  move: boolean
  maximised: boolean
}

function isValid(state: WindowState): boolean {
  return (
    state.width > 0 && state.height > 0 && state.width <= MAX_DIMENSION && state.height <= MAX_DIMENSION
  )
}

// resolve 返回 saved 在当前显示器布局下的落点。saved 为空、尺寸无效或与任何显示器都
// 不相交时，改用默认尺寸在主显示器工作区居中；显示器列表为空时只决定尺寸、不移动
// 窗口，因此在无法设置位置的会话（Wayland）下退化为只恢复尺寸与最大化状态。
export function resolve(saved: WindowState | null, screens: Screen[]): Placement {
  let placement: Placement = {
    x: 0,
    y: 0,
    width: DEFAULT_WINDOW_WIDTH,
    height: DEFAULT_WINDOW_HEIGHT,
    move: false,
    maximised: false
  }
  if (saved !== null && isValid(saved)) {
    placement = { ...placement, x: saved.x, y: saved.y, width: saved.width, height: saved.height, maximised: saved.maximised }
  }
  if (screens.length === 0) {
    // 没有显示器信息时不移动窗口，位置字段因此不适用。
    return { ...placement, x: 0, y: 0 }
  }
  if (saved !== null && isValid(saved) && onScreen(saved, screens)) {
    return { ...placement, move: true }
  }

  const area = workArea(primary(screens))
  return {
    x: area.x + Math.trunc((area.width - DEFAULT_WINDOW_WIDTH) / 2),
    y: area.y + Math.trunc((area.height - DEFAULT_WINDOW_HEIGHT) / 2),
    width: DEFAULT_WINDOW_WIDTH,
    height: DEFAULT_WINDOW_HEIGHT,
    move: true,
    maximised: placement.maximised
  }
}

// primary 返回主显示器；没有任何显示器标记为主屏时使用第一个。
function primary(screens: Screen[]): Screen {
  for (const screen of screens) {
    if (screen.isPrimary) {
      return screen
    }
  }
  return screens[0]
}

// workArea 返回显示器的工作区；工作区缺失时退回显示器边界。
function workArea(screen: Screen): Rect {
  if (screen.workArea.width > 0 && screen.workArea.height > 0) {
    return screen.workArea
  }
  return screen.bounds
}

// screenBounds 返回显示器的边界；边界缺失时退回工作区。
function screenBounds(screen: Screen): Rect {
  if (screen.bounds.width > 0 && screen.bounds.height > 0) {
    return screen.bounds
  }
  return screen.workArea
}

// overlaps 报告两个矩形是否有正面积重叠（仅边缘相接不算）。
function overlaps(a: Rect, b: Rect): boolean {
  return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
}

function onScreen(rect: Rect, screens: Screen[]): boolean {
  for (const screen of screens) {
    if (overlaps(rect, screenBounds(screen))) {
      return true
    }
  }
  return false
}

// 坐标字段缺失时按 0 处理（与 Go 版 json.Unmarshal 的零值语义一致），类型不符则无效。
function coordinate(value: unknown): number | null {
  if (value === undefined) {
    return 0
  }
  if (typeof value === 'number' && Number.isInteger(value)) {
    return value
  }
  return null
}

function dimension(value: unknown): number | null {
  if (typeof value === 'number' && Number.isInteger(value)) {
    return value
  }
  return null
}

function maximisedFlag(value: unknown): boolean | null {
  if (value === undefined) {
    return false
  }
  if (typeof value === 'boolean') {
    return value
  }
  return null
}

// 读取窗口状态：文件缺失、无法读取、内容损坏或数值无效时返回 null，调用方统一按
// “没有保存状态”处理，不得阻断启动或影响其他配置（技术标准 state-persistence）。
export function loadWindowState(path: string): WindowState | null {
  let raw: string
  try {
    raw = readFileSync(path, 'utf8')
  } catch {
    return null
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return null
  }
  const data = parsed as Record<string, unknown>
  const x = coordinate(data['x'])
  const y = coordinate(data['y'])
  const width = dimension(data['width'])
  const height = dimension(data['height'])
  const maximised = maximisedFlag(data['maximised'])
  if (x === null || y === null || width === null || height === null || maximised === null) {
    return null
  }
  const state: WindowState = { x, y, width, height, maximised }
  return isValid(state) ? state : null
}

// 原子地保存窗口状态：写同目录临时文件并重命名覆盖，写入中断不会留下半截 JSON，
// 也不会影响目录中的其他文件（如 config.json）。
export function saveWindowState(path: string, state: WindowState): void {
  if (!isValid(state)) {
    throw new Error(`窗口状态无效：尺寸 ${state.width}x${state.height}`)
  }
  const data =
    JSON.stringify(
      { x: state.x, y: state.y, width: state.width, height: state.height, maximised: state.maximised },
      null,
      2
    ) + '\n'
  const dir = dirname(path)
  mkdirSync(dir, { recursive: true })
  const temp = join(dir, `.${basename(path)}.${process.pid}-${Date.now()}.tmp`)
  const fd = openSync(temp, 'w')
  try {
    writeSync(fd, data)
    fsyncSync(fd)
  } finally {
    closeSync(fd)
  }
  try {
    renameSync(temp, path)
  } catch (error) {
    rmSync(temp, { force: true })
    throw error
  }
}
