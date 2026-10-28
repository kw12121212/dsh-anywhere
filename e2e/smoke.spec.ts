import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { _electron as electron, expect, test } from '@playwright/test'
import type { ElectronApplication, Page } from '@playwright/test'

let app: ElectronApplication
let page: Page
let userDataDir: string

test.beforeEach(async () => {
  // 去掉开发服务器地址，确保端到端测试加载的是 app:// 构建产物。
  const env: Record<string, string> = {}
  for (const [key, value] of Object.entries(process.env)) {
    if (value !== undefined && key !== 'ELECTRON_RENDERER_URL') {
      env[key] = value
    }
  }
  // 每个测试使用独立的临时 userData，不读写真实用户配置目录（与 Go 版配置目录重合）。
  userDataDir = mkdtempSync(join(tmpdir(), 'dsh-anywhere-e2e-'))
  app = await electron.launch({ args: ['.', `--user-data-dir=${userDataDir}`], env })
  page = await app.firstWindow()
  await page.waitForLoadState('domcontentloaded')
})

test.afterEach(async () => {
  await app.close()
  rmSync(userDataDir, { recursive: true, force: true })
})

test('启动后显示 app:// 本地页面，并使用临时 userData', async () => {
  await expect(page.locator('#app-title')).toHaveText('DSH Anywhere')
  expect(page.url()).toBe('app://local/index.html')
  expect(await app.evaluate(({ app }) => app.getPath('userData'))).toBe(userDataDir)
})

test('主窗口使用安全的 webPreferences', async () => {
  const prefs = await app.evaluate(({ BrowserWindow }) => {
    // getLastWebPreferences 未进入公开类型，但能读回窗口实际生效的设置；升级后消失时本测试会直接失败。
    type Prefs = Electron.WebPreferences | null
    const contents = BrowserWindow.getAllWindows()[0].webContents as unknown as { getLastWebPreferences(): Prefs }
    const p = contents.getLastWebPreferences()
    return {
      contextIsolation: p?.contextIsolation,
      sandbox: p?.sandbox,
      nodeIntegration: p?.nodeIntegration,
      webSecurity: p?.webSecurity
    }
  })
  expect(prefs).toEqual({ contextIsolation: true, sandbox: true, nodeIntegration: false, webSecurity: true })
})

test('渲染进程中没有 Node 能力', async () => {
  const exposed = await page.evaluate(() => {
    const g = globalThis as Record<string, unknown>
    return { require: typeof g['require'], process: typeof g['process'], module: typeof g['module'] }
  })
  expect(exposed).toEqual({ require: 'undefined', process: 'undefined', module: 'undefined' })
})

test('本地页面的 CSP 拒绝字符串求值与内联脚本', async () => {
  // page.evaluate 经 CDP 执行会绕过 eval 检查，因此用字符串定时器在页面自身的任务中触发求值。
  const result = await page.evaluate(async () => {
    const marker = globalThis as Record<string, unknown>
    const violations: string[] = []
    document.addEventListener('securitypolicyviolation', (event) => violations.push(event.violatedDirective))
    ;(setTimeout as unknown as (code: string, ms: number) => number)('globalThis.__timerRan = true', 0)
    const script = document.createElement('script')
    script.textContent = 'globalThis.__inlineRan = true'
    document.head.appendChild(script)
    await new Promise((resolve) => setTimeout(resolve, 200))
    return {
      timerRan: marker['__timerRan'] === true,
      inlineRan: marker['__inlineRan'] === true,
      scriptViolations: violations.filter((d) => d.startsWith('script-src')).length
    }
  })
  expect(result.timerRan).toBe(false)
  expect(result.inlineRan).toBe(false)
  expect(result.scriptViolations).toBeGreaterThanOrEqual(2)
})

test('app:// 响应头带 CSP，越界路径返回 404，其他来源被 CSP 拦截', async () => {
  const result = await page.evaluate(async () => {
    const ok = await fetch('app://local/index.html')
    const escaped = await fetch('app://local/%2e%2e%2F%2e%2e%2Fpackage.json')
    const otherHost = await fetch('app://other/index.html').then(
      () => 'loaded',
      () => 'blocked'
    )
    return {
      okStatus: ok.status,
      csp: ok.headers.get('content-security-policy'),
      escapedStatus: escaped.status,
      otherHost
    }
  })
  expect(result.okStatus).toBe(200)
  expect(result.csp).toContain("frame-ancestors 'none'")
  expect(result.csp).toContain("script-src 'self'")
  expect(result.escapedStatus).toBe(404)
  expect(result.otherHost).toBe('blocked')
})
