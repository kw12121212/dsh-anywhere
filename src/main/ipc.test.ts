import { describe, expect, it } from 'vitest'
import { PACKAGED_ORIGIN, allowedLocalOrigins, isTrustedSender, registerLocalPageIpc } from './ipc'
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
    expect(isTrustedSender(mainFrame('app://local/index.html?view=settings'), allowed)).toBe(true)
  })

  it('拒绝其他来源、子 frame 与缺失的 frame', () => {
    expect(isTrustedSender(mainFrame('https://dsh.example.test/'), allowed)).toBe(false)
    expect(isTrustedSender(mainFrame('app://other/index.html'), allowed)).toBe(false)
    expect(isTrustedSender(mainFrame('about:blank'), allowed)).toBe(false)
    expect(isTrustedSender({ senderFrame: { url: 'app://local/index.html', parent: {} } }, allowed)).toBe(false)
    expect(isTrustedSender({ senderFrame: null }, allowed)).toBe(false)
  })
})

describe('registerLocalPageIpc', () => {
  function setup() {
    const handlers = new Map<string, (event: IpcEvent, ...args: unknown[]) => unknown>()
    const calls = { retries: 0, completeSetup: [] as string[], saveSettings: [] as string[], openSettings: 0 }
    registerLocalPageIpc(
      { handle: (channel, listener) => handlers.set(channel, listener) },
      {
        allowedOrigins: ['app://local'],
        getState: () => ({ view: 'reauth' }),
        retry: () => calls.retries++,
        completeSetup: (raw) => {
          calls.completeSetup.push(raw)
          return { ok: true }
        },
        openSettings: () => calls.openSettings++,
        getSettingsURL: () => 'https://dsh.example.test/',
        saveSettings: (raw) => {
          calls.saveSettings.push(raw)
          return { ok: true }
        }
      }
    )
    return { handlers, calls }
  }

  const local = mainFrame('app://local/index.html')

  it('本地页面可查询状态、重试、打开设置与读写设置', () => {
    const { handlers, calls } = setup()
    expect(handlers.get(IPC_CHANNELS.getState)?.(local)).toEqual({ view: 'reauth' })
    handlers.get(IPC_CHANNELS.retry)?.(local)
    handlers.get(IPC_CHANNELS.openSettings)?.(local)
    expect(handlers.get(IPC_CHANNELS.getSettingsURL)?.(local)).toBe('https://dsh.example.test/')
    expect(handlers.get(IPC_CHANNELS.completeSetup)?.(local, 'http://127.0.0.1:13080/?token=abc')).toEqual({ ok: true })
    expect(handlers.get(IPC_CHANNELS.saveSettings)?.(local, 'https://b.example.test/')).toEqual({ ok: true })
    expect(calls).toEqual({
      retries: 1,
      completeSetup: ['http://127.0.0.1:13080/?token=abc'],
      saveSettings: ['https://b.example.test/'],
      openSettings: 1
    })
  })

  it('非本地来源、子 frame 的请求全部被拒绝', () => {
    const { handlers, calls } = setup()
    const remote = mainFrame('https://dsh.example.test/')
    const subframe: IpcEvent = { senderFrame: { url: 'app://local/index.html', parent: {} } }
    expect(() => handlers.get(IPC_CHANNELS.retry)?.(remote)).toThrow()
    expect(() => handlers.get(IPC_CHANNELS.openSettings)?.(remote)).toThrow()
    expect(() => handlers.get(IPC_CHANNELS.getSettingsURL)?.(remote)).toThrow()
    expect(() => handlers.get(IPC_CHANNELS.completeSetup)?.(subframe, 'https://b.example.test/')).toThrow()
    expect(() => handlers.get(IPC_CHANNELS.saveSettings)?.(subframe, 'https://b.example.test/')).toThrow()
    expect(calls.retries).toBe(0)
    expect(calls.completeSetup).toHaveLength(0)
    expect(calls.saveSettings).toHaveLength(0)
    expect(calls.openSettings).toBe(0)
  })

  it('地址通道只接受单个字符串，参数缺失、多余、类型不符或超长即拒绝', () => {
    const { handlers, calls } = setup()
    const complete = handlers.get(IPC_CHANNELS.completeSetup)
    const save = handlers.get(IPC_CHANNELS.saveSettings)
    expect(() => complete?.(local)).toThrow()
    expect(() => complete?.(local, 'a', 'b')).toThrow()
    expect(() => complete?.(local, 42)).toThrow()
    expect(() => complete?.(local, null)).toThrow()
    expect(() => save?.(local, 'x'.repeat(8193))).toThrow()
    expect(() => save?.(local, {} as unknown as string)).toThrow()
    expect(calls.completeSetup).toHaveLength(0)
    expect(calls.saveSettings).toHaveLength(0)
    expect(complete?.(local, 'x'.repeat(8192))).toEqual({ ok: true })
  })

  it('无参通道不接受任何参数', () => {
    const { handlers } = setup()
    expect(() => handlers.get(IPC_CHANNELS.getState)?.(local, 'extra')).toThrow()
    expect(() => handlers.get(IPC_CHANNELS.openSettings)?.(local, 'extra')).toThrow()
    expect(() => handlers.get(IPC_CHANNELS.getSettingsURL)?.(local, 'extra')).toThrow()
  })
})
