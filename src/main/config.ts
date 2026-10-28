import { chmodSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { basename, dirname, join } from 'node:path'
import { randomBytes } from 'node:crypto'

// 配置文件名与格式与 Go 版 internal/config 一致：{"harnessURL": "..."}。
export const CONFIG_FILE_NAME = 'config.json'

const TOKEN_PARAM = 'token'

// 校验错误只含固定文本，不回显输入，避免 token 经由错误信息泄露。
export const URL_ERRORS = {
  empty: '地址不能为空',
  malformed: '地址格式无效',
  notAbsolute: '必须是包含协议和主机名的完整地址，例如 https://example.com',
  unsupportedScheme: '只支持 http 或 https 地址'
} as const

export class URLValidationError extends Error {}

export type ConfigLoad =
  | { status: 'ok'; url: string }
  | { status: 'missing' }
  | { status: 'invalid' }

// normalizeHarnessURL 校验用户输入并返回去掉 token 参数的干净地址；
// 首尾空白被忽略，只接受带主机名的 http/https 绝对地址。
export function normalizeHarnessURL(raw: string): string {
  const trimmed = raw.trim()
  if (trimmed === '') {
    throw new URLValidationError(URL_ERRORS.empty)
  }
  const scheme = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(trimmed)?.[1]?.toLowerCase()
  if (scheme === undefined) {
    throw new URLValidationError(URL_ERRORS.notAbsolute)
  }
  if (scheme !== 'http' && scheme !== 'https') {
    throw new URLValidationError(URL_ERRORS.unsupportedScheme)
  }
  // WHATWG URL 会接受非法的百分号转义（如 %zz），这里与 Go 版 url.Parse 一样拒绝。
  if (/%(?![0-9a-fA-F]{2})/.test(trimmed)) {
    throw new URLValidationError(URL_ERRORS.malformed)
  }
  let url: URL
  try {
    url = new URL(trimmed)
  } catch {
    throw new URLValidationError(URL_ERRORS.malformed)
  }
  if (url.hostname === '') {
    throw new URLValidationError(URL_ERRORS.notAbsolute)
  }
  stripToken(url)
  return url.href
}

// redactURL 返回可写入日志的地址文本：去掉 token 参数并隐藏密码；无法解析时不回显原文。
export function redactURL(raw: string): string {
  let url: URL
  try {
    url = new URL(raw.trim())
  } catch {
    return '<无法解析的地址>'
  }
  stripToken(url)
  if (url.password !== '') {
    url.password = 'xxxxx'
  }
  return url.href
}

// hasToken 判断地址的查询参数中是否含有 token。
export function hasToken(raw: string): boolean {
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return false
  }
  return queryParts(url).some(isTokenPart)
}

// stripToken 删除所有名为 token（区分大小写）的查询参数，其余参数保持原有顺序与编码，
// 空段一并去掉；只按 & 分隔，与 dsh web 生成的地址一致。
function stripToken(url: URL): void {
  const kept = queryParts(url).filter((part) => part !== '' && !isTokenPart(part))
  url.search = kept.join('&')
}

function queryParts(url: URL): string[] {
  return url.search === '' ? [] : url.search.slice(1).split('&')
}

function isTokenPart(part: string): boolean {
  const key = part.split('=', 1)[0] ?? ''
  try {
    return decodeURIComponent(key.replaceAll('+', ' ')) === TOKEN_PARAM
  } catch {
    return false
  }
}

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
