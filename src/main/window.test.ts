import { describe, expect, it } from 'vitest'
import { APP_ENTRY_URL } from './app-protocol'
import { createMainWindowOptions, rendererEntryURL } from './window'

describe('createMainWindowOptions', () => {
  it('启用上下文隔离与沙箱并关闭 Node 集成', () => {
    const prefs = createMainWindowOptions('/app/out/preload/index.js').webPreferences
    expect(prefs).toMatchObject({
      preload: '/app/out/preload/index.js',
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      nodeIntegrationInSubFrames: false,
      nodeIntegrationInWorker: false,
      webSecurity: true,
      allowRunningInsecureContent: false,
      experimentalFeatures: false
    })
  })
})

describe('rendererEntryURL', () => {
  it('未打包且有开发服务器地址时加载开发服务器', () => {
    expect(rendererEntryURL(false, 'http://localhost:5173')).toBe('http://localhost:5173')
  })

  it('未打包但没有开发服务器地址时加载 app:// 本地页面', () => {
    expect(rendererEntryURL(false, undefined)).toBe(APP_ENTRY_URL)
    expect(rendererEntryURL(false, '')).toBe(APP_ENTRY_URL)
  })

  it('打包后忽略开发服务器地址', () => {
    expect(rendererEntryURL(true, 'http://localhost:5173')).toBe(APP_ENTRY_URL)
  })
})
