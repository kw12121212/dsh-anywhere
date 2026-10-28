import { spawn } from 'node:child_process'
import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
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

function statePath(): string {
  return join(userDataDir, 'window-state.json')
}

function saveConfig(harnessURL: string): void {
  writeFileSync(configPath(), JSON.stringify({ harnessURL }))
}

function saveState(state: object): void {
  writeFileSync(statePath(), JSON.stringify(state))
}

function readState(): string {
  return readFileSync(statePath(), 'utf8')
}

// 与主窗口 spec 相同的启动方式：去掉开发服务器地址，加载 app:// 构建产物。
async function launch(): Promise<Page> {
  const env: Record<string, string> = {}
  for (const [key, value] of Object.entries(process.env)) {
    if (value !== undefined && key !== 'ELECTRON_RENDERER_URL') {
      env[key] = value
    }
  }
  const launched = await electron.launch({ args: ['.', `--user-data-dir=${userDataDir}`], env })
  app = launched
  expect(await launched.evaluate(({ app }) => app.getPath('userData'))).toBe(userDataDir)
  let page: Page | undefined
  await expect
    .poll(() => {
      page = launched.windows().find((p) => p.url().startsWith('app://local/'))
      return page !== undefined
    })
    .toBe(true)
  return page as Page
}

// 再次启动同一 userData 的第二个实例，等待其退出并返回退出码。
// 通过 node_modules/electron/cli.js 启动，cli.js 会转发 Electron 进程的退出码。
async function launchSecondInstance(): Promise<number> {
  const env: Record<string, string> = {}
  for (const [key, value] of Object.entries(process.env)) {
    if (value !== undefined && key !== 'ELECTRON_RENDERER_URL') {
      env[key] = value
    }
  }
  const cli = join(__dirname, '..', 'node_modules', 'electron', 'cli.js')
  const child = spawn(process.execPath, [cli, '.', `--user-data-dir=${userDataDir}`], { env })
  return new Promise<number>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('second instance did not exit')), 15_000)
    child.on('exit', (code) => {
      clearTimeout(timer)
      resolve(code ?? -1)
    })
    child.on('error', (error) => {
      clearTimeout(timer)
      reject(error)
    })
  })
}

async function mainWindow() {
  return running().evaluate(({ BrowserWindow }) => {
    const win = BrowserWindow.getAllWindows()[0]
    return {
      count: BrowserWindow.getAllWindows().length,
      position: win?.getPosition() ?? null,
      size: win?.getSize() ?? null,
      minimised: win?.isMinimized() ?? null,
      maximised: win?.isMaximized() ?? null,
      visible: win?.isVisible() ?? null,
      focused: win?.isFocused() ?? null,
      localURL: win?.webContents.getURL() ?? null
    }
  })
}

function running(): ElectronApplication {
  if (app === null) {
    throw new Error('app not launched')
  }
  return app
}

// WSLg/Xvfb 等无完整窗口管理器的会话不支持最小化与前台焦点（lesson wslg-weston-7217b7），
// 相关断言只在真实桌面平台执行。
const canVerifyWindowManagement = process.platform !== 'linux'

test.describe('单实例（single-instance）', () => {
  test('再次启动不出现第二个主窗口，已有窗口被带到前台，新进程以成功状态退出', async () => {
    await launch()
    await expect.poll(async () => (await mainWindow()).visible).toBe(true)

    expect(await launchSecondInstance()).toBe(0)

    await expect.poll(async () => (await mainWindow()).count).toBe(1)
    if (canVerifyWindowManagement) {
      await expect.poll(async () => (await mainWindow()).focused).toBe(true)
    }
  })

  test('已最小化的主窗口在再次启动时取消最小化并前置', async () => {
    test.skip(process.platform === 'linux', '无窗口管理器的 Linux 会话不能验证最小化恢复')
    await launch()
    await running().evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0]?.minimize())
    await expect.poll(async () => (await mainWindow()).minimised).toBe(true)

    expect(await launchSecondInstance()).toBe(0)

    await expect.poll(async () => (await mainWindow()).minimised).toBe(false)
    await expect.poll(async () => (await mainWindow()).count).toBe(1)
    await expect.poll(async () => (await mainWindow()).focused).toBe(true)
  })

  test('再次启动后主窗口仍显示 harness 页面，配置与窗口状态不被改写', async () => {
    await fake.start()
    saveConfig(fake.baseURL)
    const page = await launch()
    // 等应用自身初始导航结束（无 cookie 时显示重新认证页），再建立 dsh 会话。
    await expect(page.locator('#reauth')).toBeVisible()
    await running().evaluate(async ({ webContents }, target) => {
      const remote = webContents.getAllWebContents().find((w) => !w.getURL().startsWith('app://'))
      await remote?.loadURL(target)
    }, `${fake.baseURL}?token=${VALID_TOKEN}`)
    const remoteURL = async (): Promise<string> =>
      running().evaluate(({ webContents }) => {
        const remote = webContents.getAllWebContents().find((w) => !w.getURL().startsWith('app://'))
        return remote?.getURL() ?? ''
      })
    await expect.poll(remoteURL).toBe(fake.baseURL)
    const configBefore = readFileSync(configPath(), 'utf8')

    expect(await launchSecondInstance()).toBe(0)

    await expect.poll(async () => (await mainWindow()).count).toBe(1)
    // 远程视图仍显示同一地址，没有重新加载或回到本地初始化界面。
    await expect.poll(remoteURL).toBe(fake.baseURL)
    expect(readFileSync(configPath(), 'utf8')).toBe(configBefore)
  })
})

test.describe('窗口状态（window-state）', () => {
  test('保存的位置与尺寸在重启后恢复', async () => {
    saveState({ x: 200, y: 120, width: 1200, height: 800, maximised: false })
    await launch()
    await expect.poll(async () => (await mainWindow()).position).toEqual([200, 120])
    await expect.poll(async () => (await mainWindow()).size).toEqual([1200, 800])
  })

  test('保存的最大化状态在重启后恢复', async () => {
    test.skip(process.platform === 'linux', '无窗口管理器的 Linux 会话不能验证最大化状态')
    saveState({ x: 10, y: 10, width: 1000, height: 618, maximised: true })
    await launch()
    await expect.poll(async () => (await mainWindow()).maximised).toBe(true)
  })

  test('状态文件损坏时按默认尺寸在主显示器工作区居中启动', async () => {
    writeFileSync(statePath(), 'not json {{')
    await launch()
    const [size, workArea] = await running().evaluate(({ BrowserWindow, screen }) => [
      BrowserWindow.getAllWindows()[0]?.getSize(),
      screen.getPrimaryDisplay().workArea
    ])
    expect(size).toEqual([1000, 618])
    const placement = await mainWindow()
    expect(placement.position).toEqual([
      workArea.x + Math.trunc((workArea.width - 1000) / 2),
      workArea.y + Math.trunc((workArea.height - 618) / 2)
    ])
  })

  test('关闭应用时保存当前几何，覆盖旧状态且不影响 harness 配置', async () => {
    await fake.start()
    saveConfig(fake.baseURL)
    saveState({ x: 0, y: 0, width: 1200, height: 800, maximised: false })
    const configBefore = readFileSync(configPath(), 'utf8')
    await launch()

    await running().evaluate(({ BrowserWindow }) => {
      const win = BrowserWindow.getAllWindows()[0]
      win?.setPosition(300, 200)
      win?.setSize(1100, 700)
    })
    await expect.poll(async () => (await mainWindow()).position).toEqual([300, 200])
    await expect.poll(async () => (await mainWindow()).size).toEqual([1100, 700])

    await app?.close()
    app = null

    expect(JSON.parse(readState())).toEqual({ x: 300, y: 200, width: 1100, height: 700, maximised: false })
    expect(readFileSync(configPath(), 'utf8')).toBe(configBefore)
    expect(readdirSync(userDataDir).filter((name) => name.includes('.tmp'))).toEqual([])

    // 重启后恢复的是新保存的几何（覆盖上一次状态）。
    await launch()
    await expect.poll(async () => (await mainWindow()).position).toEqual([300, 200])
    await expect.poll(async () => (await mainWindow()).size).toEqual([1100, 700])

    // 最大化后退出：保存的状态标记为最大化（无窗口管理器的 Linux 会话跳过）。
    if (process.platform !== 'linux') {
      await running().evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0]?.maximize())
      await expect.poll(async () => (await mainWindow()).maximised).toBe(true)
      await running().close()
      app = null
      expect(JSON.parse(readState()).maximised).toBe(true)
    }
  })
})

test.describe('关于菜单（app-identity）', () => {
  test('“关于 DSH Anywhere”显示应用名称与版本号，不影响当前页面', async () => {
    const page = await launch()
    const urlBefore = page.url()
    // 替换 dialog.showMessageBox 记录参数而不是真的弹出对话框。
    await running().evaluate(({ dialog }) => {
      const g = globalThis as unknown as { __about: { title: string; message: string }[] }
      g.__about = []
      dialog.showMessageBox = (async (arg1: unknown, arg2?: unknown) => {
        const options = (arg2 ?? arg1) as { title: string; message: string }
        g.__about.push({ title: options.title, message: options.message })
        return { response: 0, checkboxChecked: false }
      }) as typeof dialog.showMessageBox
    })
    await running().evaluate(({ Menu }) => {
      for (const top of Menu.getApplicationMenu()?.items ?? []) {
        for (const item of top.submenu?.items ?? []) {
          if (item.label === '关于 DSH Anywhere') {
            item.click()
          }
        }
      }
    })
    const about = await running().evaluate(() => (globalThis as unknown as { __about: { title: string; message: string }[] }).__about)
    expect(about).toEqual([{ title: '关于 DSH Anywhere', message: 'DSH Anywhere\n版本 dev' }])
    expect(page.url()).toBe(urlBefore)
    expect((await mainWindow()).count).toBe(1)
  })
})
