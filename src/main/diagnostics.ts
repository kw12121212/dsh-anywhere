import { redactURL } from './url-validation'

// 诊断上报的纯逻辑（diagnostics-reporting 规格），与 android/core 的 DiagReport/DiagStore
// 同语义：报告模型、脱敏、截断、编解码、端点校验、批量组包与同会话去重。本模块不得
// 引入 node 专属 API（设置页的本地脚本也复用端点校验）；文件持久化在 diagnostics-store.ts。
// 上报平台为 pca 侧专用枚举 dsh_desktop；token 查询参数的值绝不进入任何输出
// （技术标准 security-secrets/no-input-echo-errors）。

export const DIAG_PLATFORM_DSH_DESKTOP = 'dsh_desktop'

export type DiagKind = 'crash' | 'error'

export interface DiagReport {
  id: string
  kind: DiagKind
  occurredAt: string
  appVersionName: string
  device: string
  osVersion: string
  thread: string
  message: string
  stackTrace: string
  breadcrumbs: string[]
}

export const DIAG_LIMITS = {
  MAX_MESSAGE_BYTES: 4 << 10,
  MAX_STACK_BYTES: 64 << 10,
  MAX_BREADCRUMB_LINES: 200,
  MAX_BREADCRUMB_BYTES: 1 << 10,
  MAX_BATCH_REPORTS: 20,
  MAX_BATCH_BYTES: 1 << 20,
  DEFAULT_QUEUE_CAPACITY: 50
} as const

const utf8 = new TextEncoder()

export function byteLength(text: string): number {
  return utf8.encode(text).length
}

// sanitizeDiagText 报告文本脱敏：URL 形态片段经 redactURL（剥 token、隐藏密码、无法解析
// 不回显原文），残余 token=值 一律替换为 ***——两条都做，token 值绝不离开设备。
const URL_TOKEN = /https?:\/\/[^\s"'<>]+/g
const TOKEN_VALUE = /token=[^&\s"'<>]*/g

export function sanitizeDiagText(text: string): string {
  return text.replace(URL_TOKEN, (m) => redactURL(m)).replace(TOKEN_VALUE, 'token=***')
}

// truncateBytes 按字节截断（UTF-8），回退到字符边界，不产生半个字符：
// 截断点是边界当且仅当其后首个字节（若存在）是 ASCII 或多字节字符的首字节。
export function truncateBytes(text: string, maxBytes: number): string {
  const bytes = utf8.encode(text)
  if (bytes.length <= maxBytes) {
    return text
  }
  let end = maxBytes
  while (end > 0 && end < bytes.length && (bytes[end]! & 0xc0) === 0x80) {
    end--
  }
  return new TextDecoder().decode(bytes.subarray(0, end))
}

// validateDiagnosticsEndpoint 仅接受带主机名的 https 绝对地址；返回规范化端点，非法返回 null。
// 规范化：小写主机、去默认端口 443、空路径补 /；拒绝带 query 的端点——拼 API 路径会畸形
// （与 core DiagEndpoint 语义一致）。
export function validateDiagnosticsEndpoint(raw: string): string | null {
  let url: URL
  try {
    url = new URL(raw.trim())
  } catch {
    return null
  }
  if (url.protocol !== 'https:' || url.hostname === '' || url.search !== '') {
    return null
  }
  const port = url.port === '' || url.port === '443' ? '' : `:${url.port}`
  return `https://${url.hostname.toLowerCase()}${port}${url.pathname === '' ? '/' : url.pathname}`
}

export function encodeReport(r: DiagReport): string {
  return JSON.stringify({
    id: r.id,
    kind: r.kind,
    platform: DIAG_PLATFORM_DSH_DESKTOP,
    occurred_at: r.occurredAt,
    app_version_name: r.appVersionName,
    app_version_code: 0,
    device: r.device,
    os_version: r.osVersion,
    thread: r.thread,
    message: r.message,
    stack_trace: r.stackTrace,
    breadcrumbs: r.breadcrumbs
  })
}

export function encodeBatch(reports: DiagReport[]): string {
  return `{"reports":[${reports.map((r) => encodeReport(r)).join(',')}]}`
}

// isDiagReport 判定解析出的对象是否为完整报告（队列读取用；字段缺失或类型不符即丢弃）。
export function isDiagReport(value: unknown): value is DiagReport {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const v = value as Record<string, unknown>
  return (
    typeof v.id === 'string' &&
    (v.kind === 'crash' || v.kind === 'error') &&
    typeof v.occurredAt === 'string' &&
    typeof v.appVersionName === 'string' &&
    typeof v.device === 'string' &&
    typeof v.osVersion === 'string' &&
    typeof v.thread === 'string' &&
    typeof v.message === 'string' &&
    typeof v.stackTrace === 'string' &&
    Array.isArray(v.breadcrumbs) &&
    v.breadcrumbs.every((b) => typeof b === 'string')
  )
}

export interface QueuedReport {
  name: string
  report: DiagReport
}

// buildBatches 批量组包：每批 ≤20 份且编码后 ≤1 MiB，保持时间序。
export function buildBatches(items: QueuedReport[]): QueuedReport[][] {
  const out: QueuedReport[][] = []
  let current: QueuedReport[] = []
  let currentBytes = 14 // {"reports":[]} 骨架
  for (const item of items) {
    const size = byteLength(encodeReport(item.report)) + 1
    const overflowBytes = currentBytes + size > DIAG_LIMITS.MAX_BATCH_BYTES
    if (current.length > 0 && (overflowBytes || current.length >= DIAG_LIMITS.MAX_BATCH_REPORTS)) {
      out.push(current)
      current = []
      currentBytes = 14
    }
    current.push(item)
    currentBytes += size
  }
  if (current.length > 0) {
    out.push(current)
  }
  return out
}

// DiagErrorDedup 同会话错误态去重：同类错误（分类键）至多上报一次。
export class DiagErrorDedup {
  private readonly seen = new Set<string>()

  seenBefore(key: string): boolean {
    if (this.seen.has(key)) {
      return true
    }
    this.seen.add(key)
    return false
  }
}

// diagTimestamp epoch 毫秒 → RFC3339 UTC（与 pca occurred_at 要求一致）。
export function diagTimestamp(epochMillis: number): string {
  return new Date(epochMillis).toISOString()
}
