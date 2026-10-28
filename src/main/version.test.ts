import { describe, expect, it } from 'vitest'
import { APP_VERSION } from './version'

describe('APP_VERSION', () => {
  it('未注入构建期版本时默认为 dev（与 Go 版 internal/appinfo.Version 同语义）', () => {
    // 单元测试不经过 electron-vite 的 define，验证的就是未注入路径。
    expect(APP_VERSION).toBe('dev')
  })
})
