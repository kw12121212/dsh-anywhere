import { describe, expect, it } from 'vitest'
import { PACKAGED_ORIGIN, allowedLocalOrigins, isTrustedSender, registerMainWindowIpc } from './ipc'
import type { IpcEvent } from './ipc'
import { IPC_CHANNELS } from './view-state'

const mainFrame = (url: string): IpcEvent => ({ senderFrame: { url, parent: null } })

describe('allowedLocalOrigins', () => {
  it('打包后只接受 app://local，即使设置了开发服务器地址', () => {
    expect(allowedLocalOrigins(true, 'http://localhost:5173/')).toEqual([PACKAGED_ORIGIN])
    expect(PACKAGED_ORIGIN).toBe('app://local')
  })

  it('未打包时额外接受开发服务器来源', () => {
    expect(allowedLocalOrigins(false, 'http://localhost:5173/')).toEqual(['app://local', 'http://localhost:5173'])
    expect(allowedLocalOrigins(false, undefined)).toEqual(['app://local'])
    expect(allowedLocalOrigins(false, 'not a url')).toEqual(['app://local'])
  })
})

describe('isTrustedSender', () => {
  const allowed = ['app://local']

  it('接受允许来源的主 frame', () => {
    expect(isTrustedSender(mainFrame('app://local/index.html'), allowed)).toBe(true)
  })

  it('拒绝其他来源、子 frame 与缺失的 frame', () => {
    expect(isTrustedSender(mainFrame('https://dsh.example.test/'), allowed)).toBe(false)
    expect(isTrustedSender(mainFrame('app://other/index.html'), allowed)).toBe(false)
    expect(isTrustedSender(mainFrame('about:blank'), allowed)).toBe(false)
    expect(isTrustedSender({ senderFrame: { url: 'app://local/index.html', parent: {} } }, allowed)).toBe(false)
    expect(isTrustedSender({ senderFrame: null }, allowed)).toBe(false)
  })
})

describe('registerMainWindowIpc', () => {
  function setup() {
    const handlers = new Map<string, (event: IpcEvent, ...args: unknown[]) => unknown>()
    let retries = 0
    registerMainWindowIpc(
      { handle: (channel, listener) => handlers.set(channel, listener) },
      { allowedOrigins: ['app://local'], getState: () => ({ view: 'reauth' }), retry: () => retries++ }
    )
    return { handlers, retries: () => retries }
  }

  it('本地页面可查询状态与重试', () => {
    const { handlers, retries } = setup()
    expect(handlers.get(IPC_CHANNELS.getState)?.(mainFrame('app://local/index.html'))).toEqual({ view: 'reauth' })
    handlers.get(IPC_CHANNELS.retry)?.(mainFrame('app://local/index.html'))
    expect(retries()).toBe(1)
  })

  it('非本地来源、子 frame 与带参数的请求被拒绝', () => {
    const { handlers, retries } = setup()
    const retry = handlers.get(IPC_CHANNELS.retry)
    expect(() => retry?.(mainFrame('https://dsh.example.test/'))).toThrow()
    expect(() => retry?.({ senderFrame: { url: 'app://local/index.html', parent: {} } })).toThrow()
    expect(() => retry?.(mainFrame('app://local/index.html'), 'http://evil.example.test/')).toThrow()
    expect(() => handlers.get(IPC_CHANNELS.getState)?.(mainFrame('https://dsh.example.test/'))).toThrow()
    expect(retries()).toBe(0)
  })
})
