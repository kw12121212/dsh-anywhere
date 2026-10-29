import { describe, expect, it } from 'vitest'
import { APP_VERSION } from './version'

describe('APP_VERSION', () => {
  it('define 未注入时兜底为 dev（防御路径；产物构建总会注入 package.json 版本号）', () => {
    // 单元测试不经过 electron-vite 的 define，验证的就是未注入路径。
    expect(APP_VERSION).toBe('dev')
  })
})
