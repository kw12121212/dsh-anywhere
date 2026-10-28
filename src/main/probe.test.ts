import { EventEmitter } from 'node:events'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { classifyNetError, probe } from './probe'
import type { ProbeRequest } from './probe'

class FakeRequest extends EventEmitter implements ProbeRequest {
  aborted = 0
  ended = false
  abort(): void {
    this.aborted++
  }
  end(): void {
    this.ended = true
  }
}

function factory(onEnd: (request: FakeRequest) => void): { requests: string[]; create: (url: string) => FakeRequest } {
  const requests: string[] = []
  return {
    requests,
    create: (url) => {
      requests.push(url)
      const request = new FakeRequest()
      queueMicrotask(() => onEnd(request))
      return request
    }
  }
}

afterEach(() => {
  vi.useRealTimers()
})

describe('classifyNetError', () => {
  it('按 Chromium 网络错误码分类', () => {
    expect(classifyNetError('net::ERR_NAME_NOT_RESOLVED')).toBe('dns')
    expect(classifyNetError('net::ERR_CONNECTION_REFUSED')).toBe('refused')
    expect(classifyNetError('net::ERR_CONNECTION_TIMED_OUT')).toBe('timeout')
    expect(classifyNetError('net::ERR_TIMED_OUT')).toBe('timeout')
    expect(classifyNetError('net::ERR_CERT_AUTHORITY_INVALID')).toBe('tls')
    expect(classifyNetError('net::ERR_CERT_DATE_INVALID')).toBe('tls')
    expect(classifyNetError('net::ERR_SSL_PROTOCOL_ERROR')).toBe('tls')
    expect(classifyNetError('net::ERR_FAILED')).toBe('other')
    // 与 Go 版一致：地址不可达（如 Tailscale 未连接时没有路由）不是主机名解析失败。
    expect(classifyNetError('net::ERR_ADDRESS_UNREACHABLE')).toBe('other')
  })
})

describe('probe', () => {
  it('收到任何响应即可达，并中止请求不读正文', async () => {
    const f = factory((r) => r.emit('response', {}))
    const result = await probe('https://dsh.example.test/', f.create)
    expect(result).toEqual({ ok: true })
    expect(f.requests).toEqual(['https://dsh.example.test/'])
  })

  it('重定向视为可达', async () => {
    let request: FakeRequest | undefined
    const f = factory((r) => {
      request = r
      r.emit('redirect', 302)
    })
    expect(await probe('https://dsh.example.test/', f.create)).toEqual({ ok: true })
    expect(request?.ended).toBe(true)
    expect(request?.aborted).toBe(1)
  })

  it('网络错误按原因返回不可达', async () => {
    const f = factory((r) => r.emit('error', new Error('net::ERR_CONNECTION_REFUSED')))
    expect(await probe('http://127.0.0.1:13080/', f.create)).toEqual({ ok: false, reason: 'refused' })
  })

  it('超时后返回 timeout，之后的事件不再改变结果', async () => {
    vi.useFakeTimers()
    let request: FakeRequest | undefined
    const f = factory((r) => {
      request = r
    })
    const pending = probe('https://slow.example.test/', f.create, 10_000)
    await vi.advanceTimersByTimeAsync(9_999)
    let settled = false
    void pending.then(() => {
      settled = true
    })
    await Promise.resolve()
    expect(settled).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    expect(await pending).toEqual({ ok: false, reason: 'timeout' })
    request?.emit('response', {})
    expect(request?.aborted).toBe(1)
  })
})
