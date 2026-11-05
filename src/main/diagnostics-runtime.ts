import {
  DIAG_LIMITS,
  DiagErrorDedup,
  buildBatches,
  diagTimestamp,
  encodeBatch,
  sanitizeDiagText,
  truncateBytes,
  type DiagReport
} from './diagnostics'
import { DiagnosticsQueue, newDiagId } from './diagnostics-store'

// 诊断上报运行时（diagnostics-reporting 规格）：崩溃类捕获在进程终止前同步落盘、
// 下次启动补报；错误态同会话去重后即时尝试发送；发送失败静默保留在队列，
// 4xx 丢弃该批；发送与重试绝不阻塞主流程。网络发送经注入的 send 依赖（index.ts 用
// Electron net.request 直连），测试注入假实现。

export type DiagSendOutcome = 'delivered' | 'rejected' | 'failed'

export interface DiagRuntimeDeps {
  queue: DiagnosticsQueue
  // 读取已配置端点；未配置返回 null（全部采集路径据此保持零行为）。
  loadEndpoint(): string | null
  // POST 一批报告；网络错误抛出或返回 failed。
  send(endpoint: string, body: string): Promise<DiagSendOutcome>
  appVersion: string
  device: string
  osVersion: string
}

export class DiagnosticsRuntime {
  private readonly dedup = new DiagErrorDedup()
  private flushing = false

  constructor(private readonly deps: DiagRuntimeDeps) {}

  // reportCrashSync 崩溃类报告：同步原子落盘（进程即将终止，不做网络发送）。
  reportCrashSync(thread: string, err: unknown): void {
    if (this.deps.loadEndpoint() === null) {
      return
    }
    const message = err instanceof Error ? `${err.name}: ${err.message}` : String(err)
    const stack = err instanceof Error && err.stack !== undefined ? err.stack : ''
    this.deps.queue.add(this.build('crash', thread, sanitizeDiagText(message), sanitizeDiagText(stack ?? '')))
  }

  // reportRenderCrash 渲染进程崩溃：进程仍存活，落盘后触发一轮发送。
  reportRenderCrash(detail: { type: string; reason: string; exitCode: number }): void {
    if (this.deps.loadEndpoint() === null) {
      return
    }
    const message = `渲染进程崩溃（${detail.type}）：${detail.reason}（exitCode=${detail.exitCode}）`
    this.deps.queue.add(this.build('crash', 'renderer', sanitizeDiagText(message), ''))
    void this.flush()
  }

  // recordLoadError 加载失败错误态（同会话同原因一次）。
  recordLoadError(reason: string, redactedUrl: string): void {
    if (this.deps.loadEndpoint() === null) {
      return
    }
    if (this.dedup.seenBefore(`load:${reason}`)) {
      return
    }
    this.deps.queue.add(this.build('error', 'main', `harness 加载失败（${reason}）：${redactedUrl}`, ''))
    void this.flush()
  }

  // flush 一轮发送：分批发送待发队列直到排空或失败；网络失败保留剩余并结束本轮，
  // 4xx 丢弃该批；发送中途新入队的报告由排空循环补发。已在役的一轮直接复用其 Promise。
  async flush(): Promise<void> {
    if (this.flushing) {
      return this.pending
    }
    if (this.deps.loadEndpoint() === null) {
      return
    }
    this.flushing = true
    const round = this.runRound().finally(() => {
      this.flushing = false
    })
    this.pending = round
    return round
  }

  private pending: Promise<void> = Promise.resolve()

  private async runRound(): Promise<void> {
    for (;;) {
      const endpoint = this.deps.loadEndpoint()
      if (endpoint === null) {
        return
      }
      const batches = buildBatches(this.deps.queue.load())
      if (batches.length === 0) {
        return
      }
      const target = `${endpoint.replace(/\/+$/, '')}/api/diagnostics/reports`
      for (const batch of batches) {
        let outcome: DiagSendOutcome
        try {
          outcome = await this.deps.send(target, encodeBatch(batch.map((i) => i.report)))
        } catch {
          return
        }
        if (outcome === 'delivered' || outcome === 'rejected') {
          this.deps.queue.remove(batch.map((i) => i.name))
        } else {
          return
        }
      }
    }
  }

  private build(kind: 'crash' | 'error', thread: string, message: string, stackTrace: string): DiagReport {
    return {
      id: newDiagId(),
      kind,
      occurredAt: diagTimestamp(Date.now()),
      appVersionName: this.deps.appVersion,
      device: this.deps.device,
      osVersion: this.deps.osVersion,
      thread,
      message: truncateBytes(message, DIAG_LIMITS.MAX_MESSAGE_BYTES),
      stackTrace: truncateBytes(stackTrace, DIAG_LIMITS.MAX_STACK_BYTES),
      breadcrumbs: []
    }
  }
}
