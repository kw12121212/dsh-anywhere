import { EventEmitter } from 'node:events'
import type { WebContents } from 'electron'
import { describe, expect, it } from 'vitest'
import { attachRemoteView } from './remote-view'
import type { RemoteViewDeps } from './remote-view'
import { EXPIRED_MARKER } from './session'

type OpenHandler = (details: { url: string }) => { action: string }

// 只实现 attachRemoteView 用到的 WebContents 成员。
class FakeContents extends EventEmitter {
  openHandler: OpenHandler | undefined
  loaded: string[] = []
  history = ['https://dsh.example.test/']
  active = 0
  document: unknown = null
  readError = false

  setWindowOpenHandler(handler: OpenHandler): void {
    this.openHandler = handler
  }
  loadURL(url: string): Promise<void> {
    this.loaded.push(url)
    return Promise.reject(new Error('net::ERR_ABORTED'))
  }
  executeJavaScriptInIsolatedWorld(): Promise<unknown> {
    return this.readError ? Promise.reject(new Error('gone')) : Promise.resolve(this.document)
  }
  navigationHistory = {
    getAllEntries: () => this.history.map((url) => ({ url, title: '' })),
    getActiveIndex: () => this.active,
    removeEntryAtIndex: (index: number) => {
      this.history.splice(index, 1)
      if (index < this.active) {
        this.active--
      }
      return true
    }
  }
}

function setup(harness: string | null = 'https://dsh.example.test/') {
  const contents = new FakeContents()
  const calls = { external: [] as string[], expired: [] as number[], failed: [] as string[], generation: 7 }
  const deps: RemoteViewDeps = {
    harnessURL: () => harness,
    openExternal: (url) => calls.external.push(url),
    generation: () => calls.generation,
    sessionExpired: (observed) => calls.expired.push(observed),
    loadFailed: (reason) => calls.failed.push(reason)
  }
  attachRemoteView(contents as unknown as WebContents, deps)
  return { contents, calls }
}

function navigate(contents: FakeContents, url: string): boolean {
  let prevented = false
  contents.emit('will-navigate', { url, preventDefault: () => (prevented = true) })
  return prevented
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('attachRemoteView 导航范围', () => {
  it('同源链接留在主窗口', () => {
    const { contents, calls } = setup()
    expect(navigate(contents, 'https://dsh.example.test/sessions/1')).toBe(false)
    expect(calls.external).toEqual([])
  })

  it('非同源 http/https 链接交给系统浏览器，主窗口不导航', () => {
    const { contents, calls } = setup()
    expect(navigate(contents, 'https://example.org/docs')).toBe(true)
    expect(navigate(contents, 'https://dsh.example.test:8443/')).toBe(true)
    expect(calls.external).toEqual(['https://example.org/docs', 'https://dsh.example.test:8443/'])
  })

  it('其他协议被忽略', () => {
    const { contents, calls } = setup()
    expect(navigate(contents, 'file:///etc/passwd')).toBe(true)
    expect(calls.external).toEqual([])
  })

  it('没有已保存地址时拦截所有导航', () => {
    const { contents, calls } = setup(null)
    expect(navigate(contents, 'https://dsh.example.test/')).toBe(true)
    expect(calls.external).toEqual([])
  })

  it('window.open 一律不创建新窗口：同源在主窗口打开，非同源交给系统浏览器', () => {
    const { contents, calls } = setup()
    expect(contents.openHandler?.({ url: 'https://dsh.example.test/sessions/2' })).toEqual({ action: 'deny' })
    expect(contents.loaded).toEqual(['https://dsh.example.test/sessions/2'])
    expect(contents.openHandler?.({ url: 'https://example.org/popup' })).toEqual({ action: 'deny' })
    expect(contents.openHandler?.({ url: 'file:///etc/passwd' })).toEqual({ action: 'deny' })
    expect(calls.external).toEqual(['https://example.org/popup'])
    expect(contents.loaded).toHaveLength(1)
  })
})

describe('attachRemoteView 会话失效', () => {
  it('主框架 401 且正文为 dsh 固定文本时报告失效', async () => {
    const { contents, calls } = setup()
    contents.document = { contentType: 'text/plain', text: EXPIRED_MARKER }
    contents.emit('did-navigate', {}, 'https://dsh.example.test/', 401)
    contents.emit('did-finish-load')
    // 读取进行中发生新的导航：交回的仍是开始读取时的代次，由导航控制器判定为过时。
    calls.generation = 8
    await flush()
    expect(calls.expired).toEqual([7])
  })

  it('非 401 不读取正文，正文不符或读取失败时不报告', async () => {
    const { contents, calls } = setup()
    contents.document = { contentType: 'text/plain', text: EXPIRED_MARKER }
    contents.emit('did-navigate', {}, 'https://dsh.example.test/', 200)
    contents.emit('did-finish-load')
    contents.document = { contentType: 'text/html', text: 'Unauthorized' }
    contents.emit('did-navigate', {}, 'https://dsh.example.test/', 401)
    contents.emit('did-finish-load')
    contents.readError = true
    contents.emit('did-finish-load')
    await flush()
    expect(calls.expired).toEqual([])
  })
})

describe('attachRemoteView 加载失败与历史', () => {
  it('主框架网络错误报告失败原因，中止与子 frame 失败被忽略', () => {
    const { contents, calls } = setup()
    contents.emit('did-fail-load', {}, -102, 'ERR_CONNECTION_REFUSED', 'https://dsh.example.test/', true)
    contents.emit('did-fail-load', {}, -3, 'ERR_ABORTED', 'https://dsh.example.test/', true)
    contents.emit('did-fail-load', {}, -105, 'ERR_NAME_NOT_RESOLVED', 'https://ads.example.test/', false)
    expect(calls.failed).toEqual(['refused'])
  })

  it('导航后从历史中删除带 token 的非当前条目', () => {
    const { contents } = setup()
    contents.history = ['https://dsh.example.test/?token=abc', 'https://dsh.example.test/']
    contents.active = 1
    contents.emit('did-navigate', {}, 'https://dsh.example.test/', 200)
    expect(contents.history).toEqual(['https://dsh.example.test/'])

    contents.history = ['https://dsh.example.test/?token=abc', 'https://dsh.example.test/#a']
    contents.active = 1
    contents.emit('did-navigate-in-page', {}, 'https://dsh.example.test/#a', false)
    expect(contents.history).toHaveLength(2)
    contents.emit('did-navigate-in-page', {}, 'https://dsh.example.test/#a', true)
    expect(contents.history).toEqual(['https://dsh.example.test/#a'])
  })
})
