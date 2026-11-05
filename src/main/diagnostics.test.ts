import { describe, expect, it } from 'vitest'
import {
  DIAG_PLATFORM_DSH_DESKTOP,
  buildBatches,
  diagTimestamp,
  encodeBatch,
  encodeReport,
  isDiagReport,
  sanitizeDiagText,
  truncateBytes,
  validateDiagnosticsEndpoint,
  type DiagReport,
  type QueuedReport
} from './diagnostics'

// 诊断纯逻辑测试（与 android/core 的 DiagReportingTest 同语义锁定）：
// 脱敏（token 绝不出现）、截断（多字节安全）、编解码、端点校验、组包上限。

function report(overrides: Partial<DiagReport> = {}): DiagReport {
  return {
    id: '7d2a9c1e-0b1c-4d2e-9f01-00000000d5a1',
    kind: 'crash',
    occurredAt: '2026-11-05T13:00:00.000Z',
    appVersionName: '1.2.0',
    device: 'win32/x64',
    osVersion: '10.0.26300',
    thread: 'main',
    message: 'boom',
    stackTrace: 'at Object.<anonymous> (main.ts:1)',
    breadcrumbs: ['a', 'b'],
    ...overrides
  }
}

describe('sanitizeDiagText', () => {
  it('URL 中的 token 与密码被剥离，值绝不出现', () => {
    const out = sanitizeDiagText('probe failed for https://h.example.test:18790/p?token=abc123&x=1 next')
    expect(out).toBe('probe failed for https://h.example.test:18790/p?x=1 next')
    expect(out).not.toContain('abc123')
  })

  it('无法解析的地址不回显原文', () => {
    const out = sanitizeDiagText('bad https://a b.example.test/x?token=zz end')
    expect(out).not.toContain('token=zz')
    expect(out).not.toContain('zz')
  })

  it('残余 token 键值对被替换', () => {
    expect(sanitizeDiagText('msg token=supersecret tail')).toBe('msg token=*** tail')
  })
})

describe('truncateBytes', () => {
  it('按字节截断且不切多字节字符', () => {
    const text = '密码密码密码'
    expect(Buffer.byteLength(truncateBytes(text, 6))).toBe(6)
    expect(truncateBytes(text, 7)).toBe('密码')
    expect(truncateBytes('密', 0)).toBe('')
  })
})

describe('encode / decode', () => {
  it('线上编码携带 dsh_desktop 平台与服务端封闭字段集', () => {
    const json = encodeReport(report())
    expect(json).toContain(`"platform":"${DIAG_PLATFORM_DSH_DESKTOP}"`)
    expect(json).toContain('"occurred_at"')
    expect(json).toContain('"app_version_code"')
    expect(JSON.parse(encodeBatch([report(), report({ id: 'x' })]))).toHaveProperty('reports.length', 2)
  })

  it('isDiagReport 校验内部模型（队列文件格式）', () => {
    expect(isDiagReport(JSON.parse(JSON.stringify(report())))).toBe(true)
    expect(isDiagReport(null)).toBe(false)
    expect(isDiagReport({ id: 'x' })).toBe(false)
    expect(isDiagReport({ ...report(), kind: 'anr' })).toBe(false)
    expect(isDiagReport({ ...report(), breadcrumbs: [1] })).toBe(false)
  })
})

describe('validateDiagnosticsEndpoint', () => {
  it('仅接受 https，规范化端口与路径，拒绝带 query 的端点', () => {
    expect(validateDiagnosticsEndpoint('https://gamepc.example.ts.net:18790')).toBe('https://gamepc.example.ts.net:18790/')
    expect(validateDiagnosticsEndpoint('https://a.example.test')).toBe('https://a.example.test/')
    expect(validateDiagnosticsEndpoint('https://a.example.test/p#frag')).toBe('https://a.example.test/p')
    expect(validateDiagnosticsEndpoint('https://a.example.test:443/')).toBe('https://a.example.test/')
    expect(validateDiagnosticsEndpoint('https://a.example.test/p?q=1')).toBeNull()
    expect(validateDiagnosticsEndpoint('http://a.example.test/')).toBeNull()
    expect(validateDiagnosticsEndpoint('not a url')).toBeNull()
    expect(validateDiagnosticsEndpoint('')).toBeNull()
  })
})

describe('buildBatches', () => {
  it('每批至多 20 份且保持时间序', () => {
    const items: QueuedReport[] = []
    for (let i = 1; i <= 45; i++) {
      items.push({ name: `${i}.json`, report: report({ id: String(i) }) })
    }
    const batches = buildBatches(items)
    expect(batches).toHaveLength(3)
    for (const batch of batches) {
      expect(batch.length).toBeLessThanOrEqual(20)
    }
    expect(batches.flat().map((i) => i.name)).toEqual(items.map((i) => i.name))
  })
})

describe('diagTimestamp', () => {
  it('RFC3339 UTC 毫秒', () => {
    expect(diagTimestamp(0)).toBe('1970-01-01T00:00:00.000Z')
    expect(diagTimestamp(1123)).toBe('1970-01-01T00:00:01.123Z')
  })
})
