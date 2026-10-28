import { isAbsolute, relative, resolve, sep } from 'node:path'
import { pathToFileURL } from 'node:url'

// 本地页面经 app://local/ 提供，取代 file://：来源稳定，可供后续 IPC 发送方校验。
export const APP_SCHEME = 'app'
export const APP_HOST = 'local'
export const APP_ENTRY_URL = `${APP_SCHEME}://${APP_HOST}/index.html`

// 与 src/renderer/index.html 中的 meta CSP 保持一致（由单元测试锁定）。
export const LOCAL_PAGE_CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data:",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'none'"
].join('; ')

// frame-ancestors 在 meta 中会被忽略，只能随响应头下发。
export const LOCAL_PAGE_CSP_HEADER = `${LOCAL_PAGE_CSP}; frame-ancestors 'none'`

// 把 app:// 请求地址映射到 rootDir 下的文件；主机不符、编码非法或解析到 rootDir 之外时返回 null。
export function resolveAppPath(rootDir: string, requestURL: string): string | null {
  let url: URL
  try {
    url = new URL(requestURL)
  } catch {
    return null
  }
  if (url.protocol !== `${APP_SCHEME}:` || url.host !== APP_HOST) {
    return null
  }

  let pathname: string
  try {
    pathname = decodeURIComponent(url.pathname)
  } catch {
    return null
  }
  if (pathname === '/' || pathname === '') {
    pathname = '/index.html'
  }

  const root = resolve(rootDir)
  const target = resolve(root, `.${pathname}`)
  const rel = relative(root, target)
  if (rel === '' || rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel)) {
    return null
  }
  return target
}

export type FileFetcher = (fileURL: string) => Promise<Response>

// 生成 protocol.handle 的处理函数；fetchFile 在运行时为 net.fetch。
export function createAppProtocolHandler(
  rootDir: string,
  fetchFile: FileFetcher
): (request: Request) => Promise<Response> {
  return async (request) => {
    const filePath = resolveAppPath(rootDir, request.url)
    if (filePath === null) {
      return withCSP(new Response('Not Found', { status: 404 }))
    }
    try {
      const response = await fetchFile(pathToFileURL(filePath).toString())
      if (!response.ok) {
        return withCSP(new Response('Not Found', { status: 404 }))
      }
      return withCSP(response)
    } catch {
      return withCSP(new Response('Not Found', { status: 404 }))
    }
  }
}

function withCSP(response: Response): Response {
  const headers = new Headers(response.headers)
  headers.set('Content-Security-Policy', LOCAL_PAGE_CSP_HEADER)
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers })
}
