import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { DIAGNOSTICS_CONFIG_FILE_NAME, DiagnosticsQueue, clearDiagEndpoint, loadDiagEndpoint, saveDiagEndpoint } from './diagnostics-store'
import { encodeReport, type DiagReport } from './diagnostics'

// 端点配置三态 + 有界队列（原子写、损坏丢弃、超限丢最旧）的持久化测试。

let dir: string

afterEach(() => {
  if (dir !== undefined) {
    rmSync(dir, { recursive: true, force: true })
  }
})

function report(id: string): DiagReport {
  return {
    id,
    kind: 'error',
    occurredAt: '2026-11-05T13:00:00.000Z',
    appVersionName: '1.2.0',
    device: 'win32/x64',
    osVersion: '10.0',
    thread: 'main',
    message: 'boom',
    stackTrace: '',
    breadcrumbs: []
  }
}

describe('diag endpoint config', () => {
  it('三态读取、非法拒绝、清除与损坏容忍', () => {
    dir = mkdtempSync(join(tmpdir(), 'diag-store-'))
    const path = join(dir, DIAGNOSTICS_CONFIG_FILE_NAME)
    expect(loadDiagEndpoint(path)).toEqual({ status: 'missing' })
    expect(saveDiagEndpoint(path, 'https://gamepc.example.ts.net:18790')).toBe('https://gamepc.example.ts.net:18790/')
    expect(loadDiagEndpoint(path)).toEqual({ status: 'ok', endpoint: 'https://gamepc.example.ts.net:18790/' })
    expect(() => saveDiagEndpoint(path, 'http://bad/')).toThrow()
    expect(loadDiagEndpoint(path)).toEqual({ status: 'ok', endpoint: 'https://gamepc.example.ts.net:18790/' })
    clearDiagEndpoint(path)
    expect(loadDiagEndpoint(path)).toEqual({ status: 'missing' })
    writeFileSync(path, '{oops')
    expect(loadDiagEndpoint(path)).toEqual({ status: 'invalid' })
    writeFileSync(path, JSON.stringify({ diagnosticsEndpoint: 'http://x/' }))
    expect(loadDiagEndpoint(path)).toEqual({ status: 'invalid' })
  })
})

describe('DiagnosticsQueue', () => {
  it('按文件名时间序读取、删除已发送、清空', () => {
    dir = mkdtempSync(join(tmpdir(), 'diag-queue-'))
    const queue = new DiagnosticsQueue(dir, 5)
    queue.add(report('00000000-0000-4000-8000-000000000001'))
    queue.add(report('00000000-0000-4000-8000-000000000002'))
    const loaded = queue.load()
    expect(loaded).toHaveLength(2)
    expect(loaded[0]!.report.id).toBe('00000000-0000-4000-8000-000000000001')
    expect(loaded[0]!.name < loaded[1]!.name).toBe(true)
    queue.remove([loaded[0]!.name])
    expect(queue.load()).toHaveLength(1)
    queue.clear()
    expect(queue.load()).toHaveLength(0)
  })

  it('损坏文件被删除、超限丢最旧', () => {
    dir = mkdtempSync(join(tmpdir(), 'diag-queue-cap-'))
    const queue = new DiagnosticsQueue(dir, 3)
    for (let i = 1; i <= 3; i++) {
      queue.add(report(`00000000-0000-4000-8000-${String(i).padStart(12, '0')}`))
    }
    expect(queue.load()).toHaveLength(3)
    queue.add(report('00000000-0000-4000-8000-000000000004'))
    const loaded = queue.load()
    expect(loaded).toHaveLength(3)
    expect(loaded[0]!.report.id).toBe('00000000-0000-4000-8000-000000000002')
    writeFileSync(join(dir, loaded[0]!.name), 'garbage')
    expect(queue.load()).toHaveLength(2)
  })

  it('add 写入的内容可被 decode 回读（encodeReport 与队列读取闭环）', () => {
    dir = mkdtempSync(join(tmpdir(), 'diag-queue-enc-'))
    const queue = new DiagnosticsQueue(dir, 5)
    queue.add(report('00000000-0000-4000-8000-000000000009'))
    const loaded = queue.load()
    expect(loaded[0]!.report.message).toBe('boom')
    expect(encodeReport(loaded[0]!.report)).toContain('"platform":"dsh_desktop"')
  })
})
