// 纯 URL 校验与脱敏：不依赖 node 内置模块，主进程与渲染进程共用同一实现，
// 保证输入框实时校验与主进程保存校验的规则完全一致。

const TOKEN_PARAM = 'token'

// 校验错误只含固定文本，不回显输入，避免 token 经由错误信息泄露。
export const URL_ERRORS = {
  empty: '地址不能为空',
  malformed: '地址格式无效',
  notAbsolute: '必须是包含协议和主机名的完整地址，例如 https://example.com',
  unsupportedScheme: '只支持 http 或 https 地址'
} as const

export class URLValidationError extends Error {}

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
