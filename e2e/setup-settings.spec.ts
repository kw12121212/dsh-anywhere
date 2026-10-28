import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { _electron as electron, expect, test } from '@playwright/test'
import type { ElectronApplication, Page } from '@playwright/test'
import { FakeDsh, VALID_TOKEN } from './fake-dsh'

// 每个测试使用独立的临时 userData（技术标准 e2e-isolated-user-data），不读写真实配置目录。
let userDataDir: string
let fake: FakeDsh
let app: ElectronApplication | null = null

test.beforeEach(() => {
  userDataDir = mkdtempSync(join(tmpdir(), 'dsh-anywhere-e2e-'))
  fake = new FakeDsh()
  return fake.start()
})

test.afterEach(async () => {
  try {
    await app?.close()
  } catch {
    // 应用可能已在测试中退出。
  }
  app = null
  await fake.stop()
  rmSync(userDataDir, { recursive: true, force: true })
})

function configPath(): string {
  return join(userDataDir, 'config.json')
}

function saveConfig(harnessURL: string): void {
  writeFileSync(configPath(), JSON.stringify({ harnessURL }))
}

function readSavedURL(): string {
  return (JSON.parse(readFileSync(configPath(), 'utf8')) as { harnessURL: string }).harnessURL
}

// 把 config.json 占位为同名目录，使保存时的原子重命名失败（模拟配置目录不可写）。
function blockConfigWrite(): void {
  rmSync(configPath(), { force: true })
  mkdirSync(configPath())
}

function launchEnv(): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [key, value] of Object.entries(process.env)) {
    if (value !== undefined && key !== 'ELECTRON_RENDERER_URL') {
      env[key] = value
    }
  }
  return env
}

// 与主窗口 spec 相同的启动方式：去掉开发服务器地址，加载 app:// 构建产物。
async function launch(): Promise<Page> {
  const launched = await electron.launch({ args: ['.', `--user-data-dir=${userDataDir}`], env: launchEnv() })
  app = launched
  // macOS 会把 /var/folders 规范化成 /private/var/folders，两侧都按真实路径比较。
  expect(realpathSync(await launched.evaluate(({ app }) => app.getPath('userData')))).toBe(realpathSync(userDataDir))
  return findPage((url) => url === 'app://local/index.html')
}

async function findPage(match: (url: string) => boolean): Promise<Page> {
  if (app === null) {
    throw new Error('app not launched')
  }
  let found: Page | undefined
  await expect
    .poll(() => {
      found = app?.windows().find((p) => match(p.url()))
      return found !== undefined
    })
    .toBe(true)
  return found as Page
}

function running(): ElectronApplication {
  if (app === null) {
    throw new Error('app not launched')
  }
  return app
}

async function windowCount(): Promise<number> {
  return running().evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().length)
}

async function remoteShown(): Promise<boolean> {
  return running().evaluate(({ BrowserWindow }) => (BrowserWindow.getAllWindows()[0]?.contentView.children.length ?? 0) > 0)
}

// 通过应用菜单点击“设置…”，与真实菜单入口走同一条 openSettings 路径。
async function menuOpenSettings(): Promise<void> {
  await running().evaluate(({ Menu }) => {
    for (const top of Menu.getApplicationMenu()?.items ?? []) {
      for (const item of top.submenu?.items ?? []) {
        if (item.label === '设置…') {
          item.click()
        }
      }
    }
  })
}

async function menuReload(): Promise<void> {
  await running().evaluate(({ Menu }) => {
    for (const top of Menu.getApplicationMenu()?.items ?? []) {
      for (const item of top.submenu?.items ?? []) {
        if (item.label === '重新加载') {
          item.click()
        }
      }
    }
  })
}

function settingsPage(): Promise<Page> {
  return findPage((url) => url.includes('view=settings'))
}

function settingsGone(): boolean {
  return running().windows().every((p) => !p.url().includes('view=settings'))
}

async function requestCount(): Promise<number> {
  return fake.requests.length
}

// 等待一小段窗口期，确认没有新的 harness 请求（保存未触发重新加载）。
async function assertNoNewRequest(baseline: number): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 800))
  expect(await requestCount()).toBe(baseline)
}

// 通过初始化界面建立 dsh 会话并进入 harness（first-session-immediate 场景）。
async function enterHarnessViaSetup(): Promise<Page> {
  const local = await launch()
  await local.locator('#harness-url').fill(`${fake.baseURL}?token=${VALID_TOKEN}`)
  await local.locator('#start').click()
  const remote = await findPage((url) => url.startsWith(fake.baseURL))
  await expect(remote.locator('#harness')).toHaveText('harness /')
  await expect.poll(remoteShown).toBe(true)
  return local
}

test.describe('首次设置（first-run-setup）', () => {
  test('未配置启动显示初始化界面：空输入、按钮禁用、无无效提示', async () => {
    const local = await launch()
    await expect(local.locator('#unconfigured')).toBeVisible()
    await expect(local.locator('#invalid-config')).toBeHidden()
    await expect(local.locator('#harness-url')).toHaveValue('')
    await expect(local.locator('#start')).toBeDisabled()
  })

  test('配置损坏或地址无效时显示初始化界面与已有配置无效提示', async () => {
    writeFileSync(configPath(), '{not json')
    const local = await launch()
    await expect(local.locator('#unconfigured')).toBeVisible()
    await expect(local.locator('#invalid-config')).toBeVisible()
    await local.close()

    rmSync(configPath())
    saveConfig('ftp://dsh.example.test/')
    const again = await launch()
    await expect(again.locator('#invalid-config')).toBeVisible()
  })

  test('实时校验：非法地址禁用并显示原因，合法地址启用且无错误', async () => {
    const local = await launch()
    const input = local.locator('#harness-url')
    const start = local.locator('#start')
    const error = local.locator('#url-error')

    await input.fill('ftp://dsh.example.test/')
    await expect(start).toBeDisabled()
    await expect(error).toHaveText('只支持 http 或 https 地址')

    await input.fill('http://127.0.0.1:13080/?token=abc123')
    await expect(start).toBeEnabled()
    await expect(error).toHaveText('')

    await input.fill('')
    await expect(start).toBeDisabled()
    await expect(error).toHaveText('')
  })

  test('开始使用保存干净地址并用原始地址打开主窗口一次', async () => {
    await enterHarnessViaSetup()
    expect(readSavedURL()).toBe(fake.baseURL)
    expect(readFileSync(configPath(), 'utf8')).not.toContain(VALID_TOKEN)
    // 原始（带 token）地址确实被请求过一次。
    expect(fake.requests.some((r) => r.path.includes('token=*'))).toBe(true)
  })

  test('保存失败时显示不含 token 的错误并停留在初始化界面', async () => {
    blockConfigWrite()
    const local = await launch()
    await expect(local.locator('#invalid-config')).toBeVisible()
    await local.locator('#harness-url').fill(`${fake.baseURL}?token=${VALID_TOKEN}`)
    await local.locator('#start').click()
    await expect(local.locator('#url-error')).toContainText('保存失败')
    await expect(local.locator('#url-error')).not.toContainText(VALID_TOKEN)
    await expect(local.locator('#unconfigured')).toBeVisible()
    await expect(local.locator('#harness-url')).toBeEnabled()
  })
})

test.describe('设置窗口（settings-window）', () => {
  test('菜单打开设置窗口显示当前地址，重复打开聚焦不新建', async () => {
    await enterHarnessViaSetup()
    await menuOpenSettings()
    const settings = await settingsPage()
    await expect(settings.locator('#settings-url')).toHaveValue(fake.baseURL)
    expect(await windowCount()).toBe(2)

    await menuOpenSettings()
    await settingsPage()
    expect(await windowCount()).toBe(2)
  })

  test('保存新地址：关闭设置窗口，主窗口打开原始地址，配置只存干净地址', async () => {
    await enterHarnessViaSetup()
    await menuOpenSettings()
    const settings = await settingsPage()
    const target = `${fake.baseURL}other/?token=${VALID_TOKEN}`
    await settings.locator('#settings-url').fill(target)
    await settings.locator('#save').click()

    await expect.poll(settingsGone).toBe(true)
    const remote = await findPage((url) => url.startsWith(fake.baseURL))
    await expect(remote.locator('#harness')).toHaveText('harness /other/')
    expect(readSavedURL()).toBe(`${fake.baseURL}other/`)
    expect(readFileSync(configPath(), 'utf8')).not.toContain(VALID_TOKEN)
  })

  test('地址未改动、不带 token 且主窗口正显示 harness 时保存不重新加载', async () => {
    await enterHarnessViaSetup()
    await menuOpenSettings()
    const settings = await settingsPage()
    const baseline = await requestCount()
    await settings.locator('#save').click()
    await expect.poll(settingsGone).toBe(true)
    await assertNoNewRequest(baseline)
    expect(readSavedURL()).toBe(fake.baseURL)
  })

  test('同一地址粘贴新 token 保存后主窗口仍打开原始地址', async () => {
    await enterHarnessViaSetup()
    await menuOpenSettings()
    const settings = await settingsPage()
    await settings.locator('#settings-url').fill(`${fake.baseURL}?token=${VALID_TOKEN}`)
    const baseline = await requestCount()
    await settings.locator('#save').click()
    await expect.poll(async () => (await requestCount()) > baseline).toBe(true)
    const remote = await findPage((url) => url.startsWith(fake.baseURL))
    await expect(remote.locator('#harness')).toHaveText('harness /')
    expect(readSavedURL()).toBe(fake.baseURL)
    expect(readFileSync(configPath(), 'utf8')).not.toContain(VALID_TOKEN)
  })

  // 每个应用实例只做一次设置窗口的创建/销毁循环：Playwright×Electron 45 下同一实例
  // 反复创建销毁第二个窗口会以一定概率让主进程 fast-fail（0xC0000409，与应用代码无关，
  // 已用无 Playwright 的自动化脚本验证应用自身稳定），因此把两个场景拆成独立测试。
  test('编辑后取消不改变配置与主窗口', async () => {
    await enterHarnessViaSetup()
    const baseline = await requestCount()
    await menuOpenSettings()
    const settings = await settingsPage()
    await settings.locator('#settings-url').fill('https://b.example.test/')
    await settings.locator('#cancel').click()
    await expect.poll(settingsGone).toBe(true)
    await assertNoNewRequest(baseline)
    expect(readSavedURL()).toBe(fake.baseURL)
  })

  test('直接关闭设置窗口不改变配置与主窗口', async () => {
    await enterHarnessViaSetup()
    const baseline = await requestCount()
    await menuOpenSettings()
    const settings = await settingsPage()
    await settings.close()
    await expect.poll(() => windowCount()).toBe(1)
    await assertNoNewRequest(baseline)
    expect(readSavedURL()).toBe(fake.baseURL)
  })

  test('保存失败时设置窗口保持打开、显示不含 token 的错误且主窗口不导航', async () => {
    await enterHarnessViaSetup()
    const baseline = await requestCount()
    blockConfigWrite()
    await menuOpenSettings()
    const settings = await settingsPage()
    await settings.locator('#settings-url').fill(`${fake.baseURL}?token=${VALID_TOKEN}`)
    await settings.locator('#save').click()
    await expect(settings.locator('#settings-error')).toContainText('保存失败')
    await expect(settings.locator('#settings-error')).not.toContainText(VALID_TOKEN)
    await expect(settings.locator('#settings')).toBeVisible()
    await assertNoNewRequest(baseline)
  })

  test('保存非法地址被实时校验拦截，保存按钮不可用', async () => {
    await enterHarnessViaSetup()
    await menuOpenSettings()
    const settings = await settingsPage()
    await settings.locator('#settings-url').fill('ftp://dsh.example.test/')
    await expect(settings.locator('#save')).toBeDisabled()
    await expect(settings.locator('#settings-error')).toHaveText('只支持 http 或 https 地址')
    await settings.locator('#settings-url').fill('')
    await expect(settings.locator('#save')).toBeDisabled()
  })
})

test.describe('从本地页面恢复会话（session-auth / load-failure）', () => {
  test('重新认证页打开设置，粘贴新地址保存后主窗口恢复 harness', async () => {
    const local = await enterHarnessViaSetup()
    fake.expireSessions()
    await menuReload()
    await expect(local.locator('#reauth')).toBeVisible()

    await local.locator('#open-settings').click()
    const settings = await settingsPage()
    await expect(settings.locator('#settings-url')).toHaveValue(fake.baseURL)
    await settings.locator('#settings-url').fill(`${fake.baseURL}?token=${VALID_TOKEN}`)
    await settings.locator('#save').click()

    await expect.poll(settingsGone).toBe(true)
    const remote = await findPage((url) => url.startsWith(fake.baseURL))
    await expect(remote.locator('#harness')).toHaveText('harness /')
    expect(readSavedURL()).toBe(fake.baseURL)
  })

  test('错误页“修改地址”打开设置窗口并显示当前地址', async () => {
    const local = await enterHarnessViaSetup()
    await fake.stop()
    await menuReload()
    await expect(local.locator('#load-failed')).toBeVisible()
    await local.locator('#change-url').click()
    const settings = await settingsPage()
    await expect(settings.locator('#settings-url')).toHaveValue(fake.baseURL)
  })

  test('关闭主窗口即退出应用，设置窗口不能让进程继续存活', async () => {
    await enterHarnessViaSetup()
    await menuOpenSettings()
    await settingsPage()
    expect(await windowCount()).toBe(2)

    await running().evaluate(({ BrowserWindow }) => {
      BrowserWindow.getAllWindows().find((w) => w.getTitle() === 'DSH Anywhere')?.close()
    })
    await running().waitForEvent('close')
    app = null
  })
})
