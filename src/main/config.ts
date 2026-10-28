import { chmodSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { basename, dirname, join } from 'node:path'
import { randomBytes } from 'node:crypto'
import { normalizeHarnessURL } from './url-validation'

// 配置文件名与格式与 Go 版 internal/config 一致：{"harnessURL": "..."}。
export const CONFIG_FILE_NAME = 'config.json'

export type ConfigLoad =
  | { status: 'ok'; url: string }
  | { status: 'missing' }
  | { status: 'invalid' }

// loadConfig 读取配置文件：从未保存过为 missing；无法读取、内容损坏或地址无效为 invalid。
export function loadConfig(path: string): ConfigLoad {
  let data: string
  try {
    data = readFileSync(path, 'utf8')
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code === 'ENOENT') {
      return { status: 'missing' }
    }
    return { status: 'invalid' }
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(data)
  } catch {
    return { status: 'invalid' }
  }
  const raw = (parsed as { harnessURL?: unknown } | null)?.harnessURL
  if (typeof raw !== 'string') {
    return { status: 'invalid' }
  }
  try {
    return { status: 'ok', url: normalizeHarnessURL(raw) }
  } catch {
    return { status: 'invalid' }
  }
}

// saveConfig 校验 raw，原子地保存去掉 token 的干净地址并返回它；校验失败时不修改已有配置。
export function saveConfig(path: string, raw: string): string {
  const clean = normalizeHarnessURL(raw)
  writeFileAtomic(path, `${JSON.stringify({ harnessURL: clean }, null, 2)}\n`)
  return clean
}

// writeFileAtomic 在目标目录中写临时文件并重命名覆盖，写入中断不会留下半截文件。
export function writeFileAtomic(path: string, data: string): void {
  const dir = dirname(path)
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  const tmp = join(dir, `.${basename(path)}.${randomBytes(6).toString('hex')}.tmp`)
  try {
    writeFileSync(tmp, data, { mode: 0o600, flush: true })
    renameSync(tmp, path)
  } finally {
    rmSync(tmp, { force: true })
  }
}

// ensurePrivateDir 确保目录存在，并在 POSIX 上把权限收紧为仅当前用户可访问（会话 cookie 也存放其中）。
export function ensurePrivateDir(dir: string, platform: NodeJS.Platform = process.platform): void {
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  if (platform !== 'win32') {
    chmodSync(dir, 0o700)
  }
}
