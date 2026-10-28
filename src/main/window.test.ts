import { describe, expect, it } from 'vitest'
import { APP_ENTRY_URL } from './app-protocol'
import { SESSION_PARTITION } from './session'
import { MAIN_WINDOW_TITLE, createMainWindowOptions, createRemoteViewOptions, rendererEntryURL } from './window'

const secure = {
  contextIsolation: true,
  sandbox: true,
  nodeIntegration: false,
  nodeIntegrationInSubFrames: false,
  nodeIntegrationInWorker: false,
  webSecurity: true,
  allowRunningInsecureContent: false,
  experimentalFeatures: false
}

describe('createMainWindowOptions', () => {
  it('标题为 DSH Anywhere，启用上下文隔离与沙箱并关闭 Node 集成', () => {
    const options = createMainWindowOptions('/app/out/preload/index.js')
    expect(options.title).toBe(MAIN_WINDOW_TITLE)
    expect(MAIN_WINDOW_TITLE).toBe('DSH Anywhere')
    expect(options.webPreferences).toEqual({ ...secure, preload: '/app/out/preload/index.js' })
  })
})

describe('createRemoteViewOptions', () => {
  it('远程视图使用持久化分区与安全设置，且不挂 preload', () => {
    const prefs = createRemoteViewOptions().webPreferences
    expect(prefs).toEqual({ ...secure, partition: SESSION_PARTITION })
    expect(prefs).not.toHaveProperty('preload')
    expect(SESSION_PARTITION.startsWith('persist:')).toBe(true)
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
