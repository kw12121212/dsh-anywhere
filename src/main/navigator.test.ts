import { describe, expect, it } from 'vitest'
import type { ConfigLoad } from './config'
import { Navigator } from './navigator'
import type { ProbeResult } from './probe'
import type { LocalState } from './view-state'

type Shown = { local: LocalState } | { remote: string }

// 可控的假探测：每次调用返回一个由测试决定何时完成的 Promise。
function harness(config: ConfigLoad) {
  const shown: Shown[] = []
  const probes: { url: string; resolve: (r: ProbeResult) => void }[] = []
  const deps = {
    config,
    loadConfig: () => deps.config,
    probe: (url: string) => new Promise<ProbeResult>((resolve) => probes.push({ url, resolve })),
    showLocal: (state: LocalState) => shown.push({ local: state }),
    showRemote: (url: string) => shown.push({ remote: url })
  }
  return { deps, shown, probes, navigator: new Navigator(deps) }
}

const saved: ConfigLoad = { status: 'ok', url: 'https://dsh.example.test/' }

// 已打开远程 harness 的导航控制器。
async function opened() {
  const h = harness(saved)
  const opening = h.navigator.open()
  h.probes[0]?.resolve({ ok: true })
  await opening
  return h
}

describe('Navigator.open', () => {
  it('已保存可达地址时先显示正在连接，再打开干净地址', async () => {
    const h = harness(saved)
    const opening = h.navigator.open()
    expect(h.shown).toEqual([{ local: { view: 'connecting' } }])
    expect(h.probes[0]?.url).toBe('https://dsh.example.test/')
    h.probes[0]?.resolve({ ok: true })
    await opening
    expect(h.shown.at(-1)).toEqual({ remote: 'https://dsh.example.test/' })
    expect(h.navigator.harnessURL).toBe('https://dsh.example.test/')
  })

  it('不可达时显示带地址与原因的错误页', async () => {
    const h = harness(saved)
    const opening = h.navigator.open()
    h.probes[0]?.resolve({ ok: false, reason: 'refused' })
    await opening
    const errorState = { view: 'error', url: 'https://dsh.example.test/', reason: 'refused' }
    expect(h.shown.at(-1)).toEqual({ local: errorState })
    expect(h.navigator.localState).toEqual(errorState)
  })

  it('没有配置或配置无效时显示未配置页且不探测', async () => {
    const missing = harness({ status: 'missing' })
    await missing.navigator.open()
    expect(missing.shown).toEqual([{ local: { view: 'unconfigured', invalidConfig: false } }])
    expect(missing.probes).toHaveLength(0)
    expect(missing.navigator.harnessURL).toBeNull()

    const invalid = harness({ status: 'invalid' })
    await invalid.navigator.open()
    expect(invalid.shown).toEqual([{ local: { view: 'unconfigured', invalidConfig: true } }])
  })

  it('重新加载时配置已损坏则回到未配置页并提示配置无效', async () => {
    const h = harness(saved)
    const opening = h.navigator.open()
    h.probes[0]?.resolve({ ok: true })
    await opening
    h.deps.config = { status: 'invalid' }
    await h.navigator.open()
    expect(h.shown.at(-1)).toEqual({ local: { view: 'unconfigured', invalidConfig: true } })
    expect(h.navigator.harnessURL).toBeNull()
  })

  it('并发导航时只有最新一次的探测结果生效', async () => {
    const h = harness(saved)
    const first = h.navigator.open()
    const second = h.navigator.open()
    h.probes[1]?.resolve({ ok: false, reason: 'timeout' })
    await second
    h.probes[0]?.resolve({ ok: true })
    await first
    expect(h.shown.filter((s) => 'remote' in s)).toHaveLength(0)
    expect(h.shown.at(-1)).toEqual({ local: { view: 'error', url: 'https://dsh.example.test/', reason: 'timeout' } })
  })
})

describe('Navigator 远程页面事件', () => {
  it('会话失效时显示重新认证页，之后重新加载可回到 harness', async () => {
    const h = await opened()
    h.navigator.sessionExpired(h.navigator.generationId)
    expect(h.shown.at(-1)).toEqual({ local: { view: 'reauth' } })
    const reloading = h.navigator.open()
    h.probes[1]?.resolve({ ok: true })
    await reloading
    expect(h.shown.at(-1)).toEqual({ remote: 'https://dsh.example.test/' })
  })

  it('远程加载失败时显示错误页', async () => {
    const h = await opened()
    h.navigator.loadFailed('dns')
    expect(h.shown.at(-1)).toEqual({ local: { view: 'error', url: 'https://dsh.example.test/', reason: 'dns' } })
  })

  it('不在显示远程页面时忽略失效与加载失败事件', async () => {
    const h = harness(saved)
    const opening = h.navigator.open()
    h.navigator.sessionExpired(h.navigator.generationId)
    h.navigator.loadFailed('dns')
    expect(h.shown).toEqual([{ local: { view: 'connecting' } }])
    h.probes[0]?.resolve({ ok: true })
    await opening
    expect(h.shown.at(-1)).toEqual({ remote: 'https://dsh.example.test/' })
  })

  it('重新加载期间旧页面迟到的失效事件不影响重新加载的结果', async () => {
    const h = await opened()
    const reloading = h.navigator.open()
    h.navigator.sessionExpired(h.navigator.generationId)
    h.probes[1]?.resolve({ ok: true })
    await reloading
    expect(h.shown.slice(-2)).toEqual([{ local: { view: 'connecting' } }, { remote: 'https://dsh.example.test/' }])
  })

  it('读取失效页期间发生了新的导航时，迟到的失效判定被丢弃', async () => {
    const h = await opened()
    const observed = h.navigator.generationId
    const reloading = h.navigator.open()
    h.probes[1]?.resolve({ ok: true })
    await reloading
    h.navigator.sessionExpired(observed)
    expect(h.shown.at(-1)).toEqual({ remote: 'https://dsh.example.test/' })
  })
})

describe('Navigator.dispose', () => {
  it('窗口关闭后迟到的探测结果、远程事件与新的导航都不再产生副作用', async () => {
    const h = harness(saved)
    const opening = h.navigator.open()
    h.navigator.dispose()
    h.probes[0]?.resolve({ ok: true })
    await opening
    const observed = h.navigator.generationId
    h.navigator.sessionExpired(observed)
    h.navigator.loadFailed('dns')
    await h.navigator.open()
    expect(h.shown).toEqual([{ local: { view: 'connecting' } }])
    expect(h.probes).toHaveLength(1)
  })

  it('显示远程页面时关闭窗口，之后的失效与加载失败事件被忽略', async () => {
    const h = await opened()
    const observed = h.navigator.generationId
    h.navigator.dispose()
    h.navigator.sessionExpired(observed)
    h.navigator.loadFailed('refused')
    expect(h.shown.at(-1)).toEqual({ remote: 'https://dsh.example.test/' })
  })
})
