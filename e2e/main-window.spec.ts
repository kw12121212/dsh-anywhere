import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import type { AddressInfo } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { _electron as electron, expect, test } from '@playwright/test'
import type { ElectronApplication, Page } from '@playwright/test'
import { FakeDsh, VALID_TOKEN } from './fake-dsh'

let userDataDir: string
let fake: FakeDsh
let app: ElectronApplication | null = null

test.beforeEach(async () => {
  // 每个测试使用独立的临时 userData，不读写真实用户配置目录。
  userDataDir = mkdtempSync(join(tmpdir(), 'dsh-anywhere-e2e-'))
  fake = new FakeDsh()
  await fake.start()
})

test.afterEach(async () => {
  await app?.close()
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

async function launch(): Promise<Page> {
  // 去掉开发服务器地址，确保加载的是 app:// 构建产物。
  const env: Record<string, string> = {}
  for (const [key, value] of Object.entries(process.env)) {
    if (value !== undefined && key !== 'ELECTRON_RENDERER_URL') {
      env[key] = value
    }
  }
  const launched = await electron.launch({ args: ['.', `--user-data-dir=${userDataDir}`], env })
  app = launched
  // 记录而不是真正打开系统浏览器。
  await launched.evaluate(({ shell }) => {
    const g = globalThis as unknown as { __opened: string[] }
    g.__opened = []
    shell.openExternal = async (url: string) => {
      g.__opened.push(url)
    }
  })
  expect(await launched.evaluate(({ app }) => app.getPath('userData'))).toBe(userDataDir)
  return findPage(launched, (url) => url.startsWith('app://local/'))
}

async function findPage(target: ElectronApplication, match: (url: string) => boolean): Promise<Page> {
  let found: Page | undefined
  await expect
    .poll(() => {
      found = target.windows().find((p) => match(p.url()))
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

async function remoteShown(): Promise<boolean> {
  return running().evaluate(({ BrowserWindow }) => (BrowserWindow.getAllWindows()[0]?.contentView.children.length ?? 0) > 0)
}

async function opened(): Promise<string[]> {
  return running().evaluate(() => (globalThis as unknown as { __opened: string[] }).__opened)
}

async function windowCount(): Promise<number> {
  return running().evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().length)
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

// 模拟后续设置界面打开 dsh-url 输出的地址：带 token 的地址只交给远程视图加载一次。
async function loadInRemote(url: string): Promise<void> {
  await running().evaluate(async ({ webContents }, target) => {
    const remote = webContents.getAllWebContents().find((w) => !w.getURL().startsWith('app://'))
    await remote?.loadURL(target)
  }, url)
}

async function remoteHistory(): Promise<string[]> {
  return running().evaluate(({ webContents }) => {
    const remote = webContents.getAllWebContents().find((w) => !w.getURL().startsWith('app://'))
    return remote?.navigationHistory.getAllEntries().map((e) => e.url) ?? []
  })
}

// 建立 dsh 会话并让主窗口显示 harness 页面。
async function enterHarness(): Promise<{ local: Page; remote: Page }> {
  saveConfig(fake.baseURL)
  const local = await launch()
  await expect(local.locator('#reauth')).toBeVisible()
  await loadInRemote(`${fake.baseURL}?token=${VALID_TOKEN}`)
  await menuReload()
  const remote = await findPage(running(), (url) => url.startsWith(fake.baseURL))
  await expect(remote.locator('#harness')).toHaveText('harness /')
  await expect.poll(remoteShown).toBe(true)
  return { local, remote }
}

type Point = { x: number; y: number }

// 一次取得页面中所有链接的中心坐标。被主进程阻止的导航会让 Playwright 一直认为该页面有导航未完成，
// 之后的 locator 操作都会等待超时，因此点击改用坐标上的真实鼠标事件（中键也必须是可信事件）。
async function linkCenters(page: Page): Promise<Record<string, Point>> {
  return page.evaluate(() => {
    const centers: Record<string, { x: number; y: number }> = {}
    for (const link of Array.from(document.querySelectorAll('a[id]'))) {
      const rect = link.getBoundingClientRect()
      centers[link.id] = { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 }
    }
    return centers
  })
}

async function clickAt(page: Page, point: Point | undefined, button: 'left' | 'middle' = 'left'): Promise<void> {
  if (point === undefined) {
    throw new Error('link not found')
  }
  await page.mouse.click(point.x, point.y, { button })
}

async function remoteState(): Promise<{ url: string; loading: boolean }> {
  return running().evaluate(({ webContents }) => {
    const remote = webContents.getAllWebContents().find((w) => !w.getURL().startsWith('app://'))
    return { url: remote?.getURL() ?? '', loading: remote?.isLoading() ?? true }
  })
}

async function freePort(): Promise<number> {
  const server = createServer()
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()))
  const port = (server.address() as AddressInfo).port
  await new Promise<void>((resolve) => server.close(() => resolve()))
  return port
}

test.describe('主窗口', () => {
  test('没有配置时显示未配置页，不加载远程页面', async () => {
    const local = await launch()
    await expect(local.locator('#unconfigured')).toBeVisible()
    await expect(local.locator('#invalid-config')).toBeHidden()
    expect(await remoteShown()).toBe(false)
    expect(await running().evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0]?.getTitle())).toBe('DSH Anywhere')
  })

  test('应用菜单使用中文标签，编辑菜单保留系统编辑角色', async () => {
    await launch()
    const menu = await running().evaluate(({ Menu }) =>
      (Menu.getApplicationMenu()?.items ?? []).map((item) => ({
        label: item.label,
        items: (item.submenu?.items ?? []).filter((sub) => sub.type !== 'separator').map((sub) => `${sub.role ?? ''}:${sub.label}`)
      }))
    )
    expect(menu.map((m) => m.label)).toEqual(['文件', '编辑', '视图', '帮助'])
    // “帮助”中提供“关于 DSH Anywhere”（app-identity 主规格，无系统角色）。
    expect(menu[3]?.items).toEqual([':关于 DSH Anywhere'])
    expect(menu[1]?.items).toEqual([
      'undo:撤销',
      'redo:重做',
      'cut:剪切',
      'copy:复制',
      'paste:粘贴',
      'delete:删除',
      'selectall:全选'
    ])
  })

  test('编辑菜单的“复制”“粘贴”执行系统编辑动作', async () => {
    const local = await launch()
    await local.evaluate(() => {
      for (const id of ['copy-source', 'paste-target']) {
        const input = document.createElement('input')
        input.id = id
        document.body.appendChild(input)
      }
      const source = document.getElementById('copy-source') as HTMLInputElement
      source.value = '菜单复制测试'
      source.focus()
      source.select()
    })
    const clickEditItem = (label: string) =>
      running().evaluate(({ BrowserWindow, Menu }, target) => {
        const window = BrowserWindow.getAllWindows()[0]
        const edit = Menu.getApplicationMenu()?.items.find((item) => item.label === '编辑')
        const item = edit?.submenu?.items.find((sub) => sub.label === target)
        item?.click(undefined, window, window?.webContents)
      }, label)

    // 系统剪贴板在 WSLg 下与宿主共享：测试结束后恢复原内容。
    const original = await running().evaluate(({ clipboard }) => clipboard.readText())
    try {
      await running().evaluate(({ clipboard }) => clipboard.writeText(''))
      await clickEditItem('复制')
      await expect.poll(() => running().evaluate(({ clipboard }) => clipboard.readText())).toBe('菜单复制测试')

      await local.evaluate(() => (document.getElementById('paste-target') as HTMLInputElement).focus())
      await clickEditItem('粘贴')
      await expect
        .poll(() => local.evaluate(() => (document.getElementById('paste-target') as HTMLInputElement).value))
        .toBe('菜单复制测试')
    } finally {
      await running().evaluate(({ clipboard }, text) => clipboard.writeText(text), original)
    }
  })

  test('配置无效时提示已有配置无效', async () => {
    writeFileSync(configPath(), '{not json')
    const local = await launch()
    await expect(local.locator('#invalid-config')).toBeVisible()
    expect(fake.requests).toHaveLength(0)
  })

  test('地址返回 502 时视为可达，主窗口显示远程返回的内容', async () => {
    saveConfig(`${fake.baseURL}status/502`)
    await launch()
    const remote = await findPage(running(), (url) => url.startsWith(fake.baseURL))
    await expect(remote.locator('body')).toContainText('bad gateway from fake dsh')
    await expect.poll(remoteShown).toBe(true)
  })

  test('地址返回正文为空的 502 时也视为可达，不显示本地错误页', async () => {
    saveConfig(`${fake.baseURL}status/502-empty`)
    const local = await launch()
    await expect.poll(() => fake.requests.filter((r) => r.path === '/status/502-empty').length).toBeGreaterThanOrEqual(2)
    await expect.poll(remoteShown).toBe(true)
    await new Promise((resolve) => setTimeout(resolve, 500))
    expect(await remoteShown()).toBe(true)
    await expect(local.locator('#load-failed')).toBeHidden()
  })

  test('主机名无法解析时显示说明无法解析主机名的错误页', async () => {
    saveConfig('https://dsh.example.invalid/')
    const local = await launch()
    await expect(local.locator('#load-failed')).toBeVisible({ timeout: 20_000 })
    await expect(local.locator('#failed-url')).toHaveText('https://dsh.example.invalid/')
    await expect(local.locator('#failed-reason')).toContainText('无法解析主机名')
  })

  test('连接被拒绝时显示错误页，服务恢复后重试打开 harness', async () => {
    const port = await freePort()
    const url = `http://127.0.0.1:${port}/status/502`
    saveConfig(url)
    const local = await launch()
    await expect(local.locator('#load-failed')).toBeVisible({ timeout: 20_000 })
    await expect(local.locator('#failed-url')).toHaveText(url)
    await expect(local.locator('#failed-reason')).toContainText('连接被拒绝')
    await expect(local.locator('#retry')).toBeVisible()
    await expect(local.locator('#change-url')).toHaveText('修改地址')
    expect(await remoteShown()).toBe(false)

    await local.locator('#retry').click()
    await expect(local.locator('#load-failed')).toBeVisible({ timeout: 20_000 })
    await expect(local.locator('#retry')).toBeEnabled({ timeout: 20_000 })

    const recovered = new FakeDsh()
    await recovered.start(port)
    try {
      await local.locator('#retry').click()
      await expect.poll(remoteShown, { timeout: 20_000 }).toBe(true)
      const remote = await findPage(running(), (u) => u.startsWith(`http://127.0.0.1:${port}/`))
      await expect(remote.locator('body')).toContainText('bad gateway from fake dsh')
    } finally {
      await recovered.stop()
    }
  })
})

test.describe('dsh 会话', () => {
  test('带 token 地址换取会话 cookie，会话跨重启保留，失效后显示重新认证页', async () => {
    await enterHarness()
    const tokenIndex = fake.requests.findIndex((r) => r.path.includes('token=*'))
    expect(fake.requests[tokenIndex]?.cookie).toBe('none')
    // 302 后的干净地址请求已带上 SameSite=Strict 会话 cookie。
    expect(fake.requests[tokenIndex + 1]).toEqual({ path: '/', cookie: 'valid' })
    expect((await remoteHistory()).some((u) => u.includes('token'))).toBe(false)
    expect(readFileSync(configPath(), 'utf8')).not.toContain(VALID_TOKEN)

    await running().close()
    app = null
    const before = fake.requests.length
    const local = await launch()
    const remote = await findPage(running(), (url) => url.startsWith(fake.baseURL))
    await expect(remote.locator('#harness')).toHaveText('harness /')
    await expect.poll(remoteShown).toBe(true)
    await expect(local.locator('#reauth')).toBeHidden()
    // 探测请求不带会话 cookie；远程页面加载时带上了重启前签发的有效 cookie。
    const afterRestart = fake.requests.slice(before)
    expect(afterRestart.some((r) => r.cookie === 'valid')).toBe(true)
    expect(afterRestart.some((r) => r.cookie === 'invalid')).toBe(false)

    fake.expireSessions()
    await menuReload()
    await expect(local.locator('#reauth')).toBeVisible()
    await expect.poll(remoteShown).toBe(false)
    await expect(local.locator('#reauth')).toContainText('dsh-url')
    await expect(local.locator('#open-settings')).toBeVisible()
    expect(await local.locator('body').innerText()).not.toContain(VALID_TOKEN)
  })
})

test.describe('导航范围', () => {
  test('同源链接留在主窗口，非同源链接交给系统浏览器，其他协议被忽略', async () => {
    const { remote } = await enterHarness()

    const links = await linkCenters(remote)

    await clickAt(remote, links['ext'])
    await expect.poll(opened).toEqual(['https://example.org/docs'])
    await clickAt(remote, links['ext'], 'middle')
    await expect.poll(opened).toEqual(['https://example.org/docs', 'https://example.org/docs'])
    await clickAt(remote, links['port'])
    await expect.poll(opened).toHaveLength(3)
    expect((await opened())[2]).toBe('http://127.0.0.1:1/')
    await clickAt(remote, links['file'])
    await new Promise((resolve) => setTimeout(resolve, 300))
    expect(await opened()).toHaveLength(3)
    // 主窗口仍停留在原页面，且没有卡在加载中。
    expect(await remoteState()).toEqual({ url: fake.baseURL, loading: false })

    await clickAt(remote, links['same'])
    await expect.poll(async () => (await remoteState()).url).toBe(`${fake.baseURL}sessions/1`)
    await running().evaluate(({ webContents }) => {
      webContents.getAllWebContents().find((w) => !w.getURL().startsWith('app://'))?.navigationHistory.goBack()
    })
    await expect.poll(async () => (await remoteState()).url).toBe(fake.baseURL)
    await clickAt(remote, links['blank'])
    await expect.poll(async () => (await remoteState()).url).toBe(`${fake.baseURL}sessions/3`)
    expect(await windowCount()).toBe(1)
  })

  test('window.open 不弹出新窗口：同源在主窗口打开，非同源交给系统浏览器', async () => {
    const { remote } = await enterHarness()
    await remote.evaluate(() => {
      window.open('https://example.org/popup')
    })
    await expect.poll(opened).toEqual(['https://example.org/popup'])
    expect(remote.url()).toBe(fake.baseURL)
    await remote.evaluate(() => {
      window.open('/sessions/2')
    })
    await expect(remote.locator('#harness')).toHaveText('harness /sessions/2')
    expect(await windowCount()).toBe(1)
    expect(running().windows()).toHaveLength(2)
  })
})

test.describe('远程页面隔离', () => {
  test('远程页面没有 Node 能力与本地 API，无法改写配置或把主窗口导航到本地页面', async () => {
    const url = `${fake.baseURL}status/502`
    saveConfig(url)
    await launch()
    const remote = await findPage(running(), (u) => u.startsWith(fake.baseURL))
    await expect(remote.locator('body')).toContainText('bad gateway')
    const exposed = await remote.evaluate(async () => {
      const g = globalThis as Record<string, unknown>
      const local = await fetch('app://local/index.html').then(
        () => 'loaded',
        () => 'blocked'
      )
      const notification = await Notification.requestPermission()
      return { dsh: typeof g['dsh'], require: typeof g['require'], process: typeof g['process'], local, notification }
    })
    expect(exposed).toEqual({
      dsh: 'undefined',
      require: 'undefined',
      process: 'undefined',
      local: 'blocked',
      notification: 'denied'
    })

    await remote.evaluate(() => {
      location.href = 'app://local/index.html'
    })
    await remote.waitForTimeout(300)
    expect(remote.url()).toBe(url)
    expect(JSON.parse(readFileSync(configPath(), 'utf8'))).toEqual({ harnessURL: url })
  })
})
