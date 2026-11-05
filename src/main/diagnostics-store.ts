import { readFileSync, readdirSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { writeFileAtomic } from './config'
import { DIAG_LIMITS, isDiagReport, validateDiagnosticsEndpoint, type DiagReport, type QueuedReport } from './diagnostics'

// 诊断上报的文件持久化（diagnostics-reporting 规格）：端点配置（userData 下独立文件、
// 原子写、三态读取）与有界待发队列（每份一个文件、原子落盘、损坏丢弃、超限丢最旧）。
// 只在主进程使用（node:fs）；设置页只经 IPC 间接读写。

export const DIAGNOSTICS_CONFIG_FILE_NAME = 'diagnostics.json'

export type DiagEndpointLoad = { status: 'missing' } | { status: 'invalid' } | { status: 'ok'; endpoint: string }

export function loadDiagEndpoint(path: string): DiagEndpointLoad {
  let data: string
  try {
    data = readFileSync(path, 'utf8')
  } catch {
    return { status: 'missing' }
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(data)
  } catch {
    return { status: 'invalid' }
  }
  const raw = (parsed as { diagnosticsEndpoint?: unknown } | null)?.diagnosticsEndpoint
  if (typeof raw !== 'string') {
    return { status: 'invalid' }
  }
  const endpoint = validateDiagnosticsEndpoint(raw)
  return endpoint === null ? { status: 'invalid' } : { status: 'ok', endpoint }
}

export function saveDiagEndpoint(path: string, raw: string): string {
  const endpoint = validateDiagnosticsEndpoint(raw)
  if (endpoint === null) {
    throw new Error('诊断端点仅支持 https 地址')
  }
  writeFileAtomic(path, `${JSON.stringify({ diagnosticsEndpoint: endpoint }, null, 2)}\n`)
  return endpoint
}

export function clearDiagEndpoint(path: string): void {
  rmSync(path, { force: true })
}

export class DiagnosticsQueue {
  constructor(
    private readonly dir: string,
    private readonly capacity: number = DIAG_LIMITS.DEFAULT_QUEUE_CAPACITY
  ) {}

  add(report: DiagReport): void {
    const name = `${String(Date.now()).padStart(19, '0')}-${report.id}.json`
    // 队列文件存内部模型（camelCase）；提交时的线上格式转换在 encodeBatch。
    writeFileAtomic(join(this.dir, name), JSON.stringify(report))
    this.enforceCapacity()
  }

  load(): QueuedReport[] {
    let names: string[]
    try {
      names = readdirSync(this.dir)
        .filter((n) => n.endsWith('.json'))
        .sort()
    } catch {
      return []
    }
    const out: QueuedReport[] = []
    for (const name of names) {
      let report: unknown
      try {
        report = JSON.parse(readFileSync(join(this.dir, name), 'utf8'))
      } catch {
        rmSync(join(this.dir, name), { force: true })
        continue
      }
      if (!isDiagReport(report)) {
        rmSync(join(this.dir, name), { force: true })
        continue
      }
      out.push({ name, report })
    }
    return out
  }

  remove(names: string[]): void {
    for (const name of names) {
      rmSync(join(this.dir, name), { force: true })
    }
  }

  clear(): void {
    for (const name of this.listNames()) {
      rmSync(join(this.dir, name), { force: true })
    }
  }

  private listNames(): string[] {
    try {
      return readdirSync(this.dir).filter((n) => n.endsWith('.json')).sort()
    } catch {
      return []
    }
  }

  private enforceCapacity(): void {
    const names = this.listNames()
    const excess = names.length - this.capacity
    for (let i = 0; i < excess; i++) {
      rmSync(join(this.dir, names[i]!), { force: true })
    }
  }
}

export function newDiagId(): string {
  return randomUUID()
}
