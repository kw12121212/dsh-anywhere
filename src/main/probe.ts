// 导航前对 harness 地址做带超时的可达性探测；任何 HTTP 响应（含 401、5xx 与重定向）都算可达。

export const DEFAULT_PROBE_TIMEOUT_MS = 10_000

export type FailureReason = 'dns' | 'refused' | 'timeout' | 'tls' | 'other'

export type ProbeResult = { ok: true } | { ok: false; reason: FailureReason }

// ProbeRequest 是 Electron net.ClientRequest 中探测用到的最小子集。
export interface ProbeRequest {
  on(event: 'response' | 'redirect' | 'error', listener: (arg: Error) => void): unknown
  abort(): void
  end(): void
}

export type RequestFactory = (url: string) => ProbeRequest

// classifyNetError 把 Chromium 网络错误（如 net::ERR_CONNECTION_REFUSED）归类为失败原因。
export function classifyNetError(message: string): FailureReason {
  if (/ERR_NAME_NOT_RESOLVED|ERR_NAME_RESOLUTION_FAILED/.test(message)) {
    return 'dns'
  }
  if (/ERR_CONNECTION_REFUSED/.test(message)) {
    return 'refused'
  }
  if (/ERR_(CONNECTION_)?TIMED_OUT/.test(message)) {
    return 'timeout'
  }
  if (/ERR_CERT_|ERR_SSL_|_SSL_|ERR_BAD_SSL/.test(message)) {
    return 'tls'
  }
  return 'other'
}

// probe 发出一次不跟随重定向的请求；收到响应头或重定向即视为可达，不读取正文。
export function probe(url: string, createRequest: RequestFactory, timeoutMs = DEFAULT_PROBE_TIMEOUT_MS): Promise<ProbeResult> {
  return new Promise((resolve) => {
    let settled = false
    const request = createRequest(url)
    const finish = (result: ProbeResult): void => {
      if (settled) {
        return
      }
      settled = true
      clearTimeout(timer)
      request.abort()
      resolve(result)
    }
    const timer = setTimeout(() => finish({ ok: false, reason: 'timeout' }), timeoutMs)
    request.on('response', () => finish({ ok: true }))
    request.on('redirect', () => finish({ ok: true }))
    request.on('error', (error) => finish({ ok: false, reason: classifyNetError(error.message) }))
    request.end()
  })
}
