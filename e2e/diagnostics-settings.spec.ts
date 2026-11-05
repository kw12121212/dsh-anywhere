import { mkdtempSync, readFileSync, realpathSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { _electron as electron, expect, test } from '@playwright/test'
import type { ElectronApplication, Page } from '@playwright/test'

// 设置窗口「诊断上报」条目（diagnostics-reporting 规格）：默认未配置、非法地址不可保存、
// 合法地址保存后持久化、清除回到未配置。每个测试使用独立的临时 userData
// （技术标准 e2e-isolated-user-data）。

let userDataDir: string
let app: ElectronApplication | null = null

test.beforeEach(() => {
  userDataDir = mkdtempSync(join(tmpdir(), 'dsh-anywhere-e2e-'))
})

test.afterEach(async () => {
  try {
    await app?.close()
  } catch {
    // 应用可能已在测试中退出。
  }
  app = null
  rmSync(userDataDir, { recursive: true, force: true })
})

function launchEnv(): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [key, value] of Object.entries(process.env)) {
    if (value !== undefined && key !== 'ELECTRON_RENDERER_URL') {
      env[key] = value
    }
  }
  return env
}

async function launch(): Promise<Page> {
  const launched = await electron.launch({ args: ['.', `--user-data-dir=${userDataDir}`], env: launchEnv() })
  app = launched
  expect(realpathSync(await launched.evaluate(({ app }) => app.getPath('userData')))).toBe(realpathSync(userDataDir))
  return findPage((url) => url === 'app://local/index.html')
}

async function findPage(match: (url: string) => boolean): Promise<Page> {
  let found: Page | undefined
  await expect
    .poll(() => {
      found = app?.windows().find((p) => match(p.url()))
      return found !== undefined
    })
    .toBe(true)
  return found as Page
}

async function openSettings(): Promise<Page> {
  const running = app
  expect(running).not.toBeNull()
  await running!.evaluate(({ Menu }) => {
    for (const top of Menu.getApplicationMenu()?.items ?? []) {
      for (const item of top.submenu?.items ?? []) {
        if (item.label === '设置…') {
          item.click()
        }
      }
    }
  })
  return findPage((url) => url.includes('view=settings'))
}

function diagConfigPath(): string {
  return join(userDataDir, 'diagnostics.json')
}

test('默认未配置：输入为空、保存禁用', async () => {
  await launch()
  const settings = await openSettings()
  const input = settings.locator('#diag-endpoint')
  await expect(input).toHaveValue('')
  await expect(settings.locator('#diag-save')).toBeDisabled()
  await expect(settings.locator('#diag-clear')).toBeEnabled()
})

test('非法地址不可保存，合法 https 可保存并持久化，重启后回显', async () => {
  await launch()
  let settings = await openSettings()
  const input = settings.locator('#diag-endpoint')
  await input.fill('http://clear.example/')
  await expect(settings.locator('#diag-save')).toBeDisabled()
  await expect(settings.locator('#diag-error')).toHaveText(/https/)
  await input.fill('https://gamepc.example.ts.net:18790')
  await expect(settings.locator('#diag-save')).toBeEnabled()
  await settings.locator('#diag-save').click()
  await expect(input).toHaveValue('https://gamepc.example.ts.net:18790/')
  const saved = JSON.parse(readFileSync(diagConfigPath(), 'utf8')) as { diagnosticsEndpoint: string }
  expect(saved.diagnosticsEndpoint).toBe('https://gamepc.example.ts.net:18790/')

  // 重启后仍显示已配置端点（持久化）。
  await app?.close()
  app = null
  await launch()
  settings = await openSettings()
  await expect(settings.locator('#diag-endpoint')).toHaveValue('https://gamepc.example.ts.net:18790/')
})

test('清除回到未配置并删除配置文件', async () => {
  await launch()
  const settings = await openSettings()
  await settings.locator('#diag-endpoint').fill('https://gamepc.example.ts.net:18790')
  await settings.locator('#diag-save').click()
  await expect(settings.locator('#diag-endpoint')).toHaveValue('https://gamepc.example.ts.net:18790/')
  await settings.locator('#diag-clear').click()
  await expect(settings.locator('#diag-endpoint')).toHaveValue('')
  await expect(settings.locator('#diag-save')).toBeDisabled()
  expect(() => readFileSync(diagConfigPath(), 'utf8')).toThrow()
})
