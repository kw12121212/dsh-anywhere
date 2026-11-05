import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { DiagnosticsQueue } from './diagnostics-store'
import { DiagnosticsRuntime, type DiagSendOutcome } from './diagnostics-runtime'

// 运行时编排测试：未配置零行为、崩溃同步落盘、错误态同会话去重、
// 发送成功删除/失败保留/4xx 丢弃。发送经注入的假实现，不触网络。

let dir: string | undefined

afterEach(() => {
  if (dir !== undefined) {
    rmSync(dir, { recursive: true, force: true })
    dir = undefined
  }
})

interface Harness {
  runtime: DiagnosticsRuntime
  queue: DiagnosticsQueue
  endpoint: string | null
  sent: string[]
  outcome: DiagSendOutcome | 'throw'
}

function setup(endpoint: string | null = 'https://gamepc.example.ts.net:18790/'): Harness {
  dir = mkdtempSync(join(tmpdir(), 'diag-runtime-'))
  const queue = new DiagnosticsQueue(join(dir, 'queue'))
  const h: Harness = {
    queue,
    endpoint,
    sent: [],
    outcome: 'delivered',
    runtime: undefined as unknown as DiagnosticsRuntime
  }
  const runtime = new DiagnosticsRuntime({
    queue,
    loadEndpoint: () => h.endpoint,
    send: async (ep, body) => {
      h.sent.push(`${ep} ${body}`)
      if (h.outcome === 'throw') {
        throw new Error('network down')
      }
      return h.outcome
    },
    appVersion: '1.2.0',
    device: 'win32/x64',
    osVersion: '10.0'
  })
  h.runtime = runtime
  return h
}

describe('DiagnosticsRuntime', () => {
  it('未配置端点时零行为：不落盘、不发送', () => {
    const h = setup(null)
    h.runtime.reportCrashSync('main', new Error('boom'))
    h.runtime.reportRenderCrash({ type: 'remote', reason: 'oom', exitCode: 5 })
    h.runtime.recordLoadError('refused', 'https://a.example.test/')
    expect(h.queue.load()).toHaveLength(0)
    return h.runtime.flush().then(() => {
      expect(h.sent).toHaveLength(0)
    })
  })

  it('崩溃类同步落盘且不发网络，flush 后送达并清空队列', async () => {
    const h = setup()
    h.runtime.reportCrashSync('main', new Error('boom https://h.example.test/x?token=secret'))
    expect(h.queue.load()).toHaveLength(1)
    expect(h.sent).toHaveLength(0)
    const queued = h.queue.load()[0]!
    expect(queued.report.kind).toBe('crash')
    expect(queued.report.message).not.toContain('secret')
    expect(queued.report.message).toContain('https://h.example.test/')
    await h.runtime.flush()
    expect(h.sent).toHaveLength(1)
    expect(h.sent[0]).toContain('/api/diagnostics/reports')
    expect(h.queue.load()).toHaveLength(0)
  })

  it('错误态同会话同原因只落盘一份；渲染崩溃独立成报', async () => {
    const h = setup()
    h.runtime.recordLoadError('refused', 'https://a.example.test/')
    h.runtime.recordLoadError('refused', 'https://a.example.test/')
    h.runtime.recordLoadError('timeout', 'https://a.example.test/')
    h.runtime.reportRenderCrash({ type: 'remote', reason: 'oom', exitCode: 5 })
    await h.runtime.flush()
    // 同因只一份：refused 一份 + timeout 一份 + 渲染崩溃一份；可能分多批送达。
    const bodies = h.sent.join('')
    expect((bodies.match(/"kind":"error"/g) ?? []).length).toBe(2)
    expect(bodies).toContain('"kind":"crash"')
    expect(h.queue.load()).toHaveLength(0)
  })

  it('网络失败保留队列；4xx 丢弃该批', async () => {
    const h = setup()
    h.runtime.reportCrashSync('main', new Error('a'))
    h.outcome = 'failed'
    await h.runtime.flush()
    expect(h.queue.load()).toHaveLength(1)
    h.outcome = 'throw'
    await h.runtime.flush()
    expect(h.queue.load()).toHaveLength(1)
    h.outcome = 'rejected'
    await h.runtime.flush()
    expect(h.queue.load()).toHaveLength(0)
    expect(h.sent).toHaveLength(3)
  })
})
