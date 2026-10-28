// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LocalState, SaveResult } from '../../main/view-state'

// src/renderer/index.html 正文的主干结构：页面脚本按 id 查找这些元素。
const PAGE_BODY = `
<main class="container">
  <section id="connecting">
    <h1 id="app-title">DSH Anywhere</h1>
    <p id="status" class="intro">正在连接 harness…</p>
  </section>
  <section id="unconfigured" hidden>
    <h1>欢迎使用 dsh-anywhere</h1>
    <p class="intro">请先填写 harness 地址。</p>
    <p id="invalid-config" role="alert" hidden>已有配置无效，请重新填写 harness 地址。</p>
    <form id="setup-form" novalidate>
      <label for="harness-url">harness 地址</label>
      <input id="harness-url" type="url" autocomplete="off" spellcheck="false" />
      <p id="url-error" aria-live="polite"></p>
      <button id="start" type="submit" disabled>开始使用</button>
    </form>
  </section>
  <section id="reauth" hidden>
    <h1>需要重新认证</h1>
    <p class="intro">会话已失效。</p>
    <p class="intro">请运行 dsh-url 获取新地址。</p>
    <p id="reauth-error" aria-live="polite"></p>
    <div class="actions"><button id="open-settings" type="button">打开设置</button></div>
  </section>
  <section id="load-failed" hidden>
    <h1>无法连接 harness</h1>
    <p class="intro">地址：<code id="failed-url"></code></p>
    <p id="failed-reason" class="notice"></p>
    <p id="failed-error" class="error" aria-live="polite"></p>
    <div class="actions">
      <button id="retry" type="button">重试</button>
      <button id="change-url" type="button">修改地址</button>
    </div>
  </section>
  <section id="settings" hidden>
    <h1>设置</h1>
    <form id="settings-form" novalidate>
      <label for="settings-url">harness 地址</label>
      <input id="settings-url" type="url" autocomplete="off" spellcheck="false" />
      <p id="settings-error" aria-live="polite"></p>
      <div class="actions">
        <button id="save" type="submit" disabled>保存</button>
        <button id="cancel" type="button">取消</button>
      </div>
    </form>
  </section>
</main>`

interface FakeDsh {
  getState: ReturnType<typeof vi.fn>
  onState: ReturnType<typeof vi.fn>
  retry: ReturnType<typeof vi.fn>
  openSettings: ReturnType<typeof vi.fn>
  completeSetup: (raw: string) => Promise<SaveResult>
  getSettingsURL: ReturnType<typeof vi.fn>
  saveSettings: (raw: string) => Promise<SaveResult>
  listeners: ((state: LocalState) => void)[]
}

function fakeDsh(overrides: Partial<FakeDsh> = {}): FakeDsh {
  const listeners: ((state: LocalState) => void)[] = []
  return {
    getState: vi.fn(async () => ({ view: 'connecting' }) as LocalState),
    onState: vi.fn((listener: (state: LocalState) => void) => listeners.push(listener)),
    retry: vi.fn(async () => {}),
    openSettings: vi.fn(async () => {}),
    completeSetup: vi.fn(async (_raw: string): Promise<SaveResult> => ({ ok: true })),
    getSettingsURL: vi.fn(async () => 'https://a.example.test/'),
    saveSettings: vi.fn(async (_raw: string): Promise<SaveResult> => ({ ok: true })),
    listeners,
    ...overrides
  }
}

function el(id: string): HTMLElement {
  const found = document.getElementById(id)
  if (found === null) {
    throw new Error(`missing #${id}`)
  }
  return found
}

async function importPage(view: '' | 'settings', dsh: FakeDsh): Promise<void> {
  vi.resetModules()
  ;(window as unknown as { dsh: unknown }).dsh = dsh
  document.body.innerHTML = PAGE_BODY
  if (view === 'settings') {
    ;(window as unknown as { happyDOM: { setURL(url: string): void } }).happyDOM.setURL('http://local/index.html?view=settings')
  } else {
    ;(window as unknown as { happyDOM: { setURL(url: string): void } }).happyDOM.setURL('http://local/index.html')
  }
  await import('./main')
  // 等待页面模块的初始 getState 微任务完成。
  await new Promise((resolve) => setTimeout(resolve, 0))
}

function pushState(dsh: FakeDsh, state: LocalState): void {
  for (const listener of dsh.listeners) {
    listener(state)
  }
}

function setValue(id: string, value: string): void {
  const input = el(id) as HTMLInputElement
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
}

function submit(id: string): void {
  el(id).dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
}

const flush = async (): Promise<void> => {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('主窗口页面渲染', () => {
  it('按状态切换四个区块', async () => {
    const dsh = fakeDsh()
    await importPage('', dsh)
    expect(el('connecting').hidden).toBe(false)

    pushState(dsh, { view: 'unconfigured', invalidConfig: false })
    expect(el('unconfigured').hidden).toBe(false)
    expect(el('connecting').hidden).toBe(true)
    expect(el('invalid-config').hidden).toBe(true)

    pushState(dsh, { view: 'unconfigured', invalidConfig: true })
    expect(el('invalid-config').hidden).toBe(false)

    pushState(dsh, { view: 'reauth' })
    expect(el('reauth').hidden).toBe(false)
    expect(el('unconfigured').hidden).toBe(true)

    pushState(dsh, { view: 'error', url: 'http://127.0.0.1:13080/', reason: 'refused' })
    expect(el('load-failed').hidden).toBe(false)
    expect(el('failed-url').textContent).toBe('http://127.0.0.1:13080/')
    expect(el('failed-reason').textContent).toContain('连接被拒绝')
    expect((el('retry') as HTMLButtonElement).disabled).toBe(false)
  })

  it('重试失败时显示错误并恢复按钮', async () => {
    const dsh = fakeDsh({ retry: vi.fn(async () => Promise.reject(new Error('ipc down'))) })
    await importPage('', dsh)
    pushState(dsh, { view: 'error', url: 'http://127.0.0.1:13080/', reason: 'refused' })
    el('retry').click()
    await flush()
    expect(el('failed-error').textContent).toContain('重试失败')
    expect((el('retry') as HTMLButtonElement).disabled).toBe(false)
  })

  it('错误页“修改地址”与重新认证页“打开设置”调用 openSettings，失败时显示错误', async () => {
    const dsh = fakeDsh({ openSettings: vi.fn(async () => Promise.reject(new Error('no'))) })
    await importPage('', dsh)
    pushState(dsh, { view: 'error', url: 'http://127.0.0.1:13080/', reason: 'refused' })
    el('change-url').click()
    await flush()
    expect(el('failed-error').textContent).toContain('无法打开设置窗口')

    pushState(dsh, { view: 'reauth' })
    el('open-settings').click()
    await flush()
    expect(el('reauth-error').textContent).toContain('无法打开设置窗口')
    expect(dsh.openSettings).toHaveBeenCalledTimes(2)
  })
})

describe('初始化表单', () => {
  it('实时校验：空输入禁用不显错，非法地址显示原因，合法地址启用', async () => {
    const dsh = fakeDsh()
    await importPage('', dsh)
    pushState(dsh, { view: 'unconfigured', invalidConfig: false })
    const start = el('start') as HTMLButtonElement
    expect(start.disabled).toBe(true)
    expect(el('url-error').textContent).toBe('')

    setValue('harness-url', 'ftp://dsh.example.test/')
    expect(start.disabled).toBe(true)
    expect(el('url-error').textContent).toBe('只支持 http 或 https 地址')

    setValue('harness-url', 'http://127.0.0.1:13080/?token=abc123')
    expect(start.disabled).toBe(false)
    expect(el('url-error').textContent).toBe('')

    setValue('harness-url', '')
    expect(start.disabled).toBe(true)
    expect(el('url-error').textContent).toBe('')
  })

  it('提交成功后转入正在打开提示', async () => {
    const completeSetup = vi.fn(async (_raw: string): Promise<SaveResult> => ({ ok: true }))
    const dsh = fakeDsh({ completeSetup } as Partial<FakeDsh>)
    await importPage('', dsh)
    pushState(dsh, { view: 'unconfigured', invalidConfig: false })
    setValue('harness-url', 'http://127.0.0.1:13080/?token=abc123')
    submit('setup-form')
    await flush()
    expect(completeSetup).toHaveBeenCalledWith('http://127.0.0.1:13080/?token=abc123')
    expect(el('unconfigured').hidden).toBe(true)
    expect(el('connecting').hidden).toBe(false)
    expect(el('status').textContent).toBe('正在打开 harness…')
  })

  it('提交失败显示不含 token 的错误并恢复输入', async () => {
    const completeSetup = vi.fn(async (_raw: string): Promise<SaveResult> => ({ ok: false, error: '保存失败：无法写入配置文件。' }))
    const dsh = fakeDsh({ completeSetup } as Partial<FakeDsh>)
    await importPage('', dsh)
    pushState(dsh, { view: 'unconfigured', invalidConfig: true })
    setValue('harness-url', 'http://127.0.0.1:13080/?token=abc123')
    submit('setup-form')
    await flush()
    expect(el('unconfigured').hidden).toBe(false)
    expect(el('url-error').textContent).toContain('保存失败')
    expect(el('url-error').textContent).not.toContain('abc123')
    expect((el('harness-url') as HTMLInputElement).disabled).toBe(false)
    // 恢复后按钮按当前输入重新可用。
    expect((el('start') as HTMLButtonElement).disabled).toBe(false)
  })
})

describe('设置页面', () => {
  it('读取当前地址填入并实时校验', async () => {
    const dsh = fakeDsh({ getSettingsURL: vi.fn(async () => 'https://a.example.test/') })
    await importPage('settings', dsh)
    expect(dsh.getSettingsURL).toHaveBeenCalled()
    expect((el('settings-url') as HTMLInputElement).value).toBe('https://a.example.test/')
    expect((el('save') as HTMLButtonElement).disabled).toBe(false)

    setValue('settings-url', 'ftp://dsh.example.test/')
    expect((el('save') as HTMLButtonElement).disabled).toBe(true)
    expect(el('settings-error').textContent).toBe('只支持 http 或 https 地址')

    setValue('settings-url', '')
    expect((el('save') as HTMLButtonElement).disabled).toBe(true)
  })

  it('未配置或配置无效时输入为空且保存不可用', async () => {
    const dsh = fakeDsh({ getSettingsURL: vi.fn(async () => '') })
    await importPage('settings', dsh)
    expect((el('settings-url') as HTMLInputElement).value).toBe('')
    expect((el('save') as HTMLButtonElement).disabled).toBe(true)
  })

  it('保存成功后页面自行关窗', async () => {
    const saveSettings = vi.fn(async (_raw: string): Promise<SaveResult> => ({ ok: true }))
    const dsh = fakeDsh({ saveSettings } as Partial<FakeDsh>)
    await importPage('settings', dsh)
    const close = vi.spyOn(window, 'close')
    setValue('settings-url', 'http://127.0.0.1:13080/?token=def456')
    submit('settings-form')
    await flush()
    expect(saveSettings).toHaveBeenCalledWith('http://127.0.0.1:13080/?token=def456')
    expect(close).toHaveBeenCalled()
  })

  it('保存失败显示不含 token 的错误且窗口保持打开', async () => {
    const saveSettings = vi.fn(async (_raw: string): Promise<SaveResult> => ({ ok: false, error: '保存失败：无法写入配置文件。' }))
    const dsh = fakeDsh({ saveSettings } as Partial<FakeDsh>)
    await importPage('settings', dsh)
    const close = vi.spyOn(window, 'close')
    setValue('settings-url', 'http://127.0.0.1:13080/?token=def456')
    submit('settings-form')
    await flush()
    expect(el('settings-error').textContent).toContain('保存失败')
    expect(el('settings-error').textContent).not.toContain('def456')
    expect(close).not.toHaveBeenCalled()
    expect(el('settings').hidden).toBe(false)
    expect((el('settings-url') as HTMLInputElement).disabled).toBe(false)
  })

  it('取消直接关窗且不保存', async () => {
    const dsh = fakeDsh()
    await importPage('settings', dsh)
    const close = vi.spyOn(window, 'close')
    setValue('settings-url', 'https://b.example.test/')
    el('cancel').click()
    await flush()
    expect(close).toHaveBeenCalled()
    expect(dsh.saveSettings).not.toHaveBeenCalled()
  })
})
