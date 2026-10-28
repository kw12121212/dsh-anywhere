import { describe, expect, it } from 'vitest'
import { DESCRIBE_ARGS, versionFromDescribe } from './dist.mts'

describe('versionFromDescribe', () => {
  it('v 前缀标签去掉前缀', () => {
    expect(versionFromDescribe(0, 'v0.1.0\n')).toBe('0.1.0')
    expect(versionFromDescribe(0, 'v1.2.3')).toBe('1.2.3')
  })

  it('git describe 未命中标签（非零退出）时回退 dev', () => {
    expect(versionFromDescribe(1, '')).toBe('dev')
    expect(versionFromDescribe(128, '')).toBe('dev')
  })

  it('spawn 失败（status 为 null）时回退 dev', () => {
    expect(versionFromDescribe(null, '')).toBe('dev')
  })

  it('输出不带 v 前缀或为空时按未命中处理，不产出空版本号', () => {
    expect(versionFromDescribe(0, '0.1.0')).toBe('dev')
    expect(versionFromDescribe(0, '')).toBe('dev')
    expect(versionFromDescribe(0, 'v')).toBe('dev')
  })
})

describe('DESCRIBE_ARGS', () => {
  it('锁定 git 精确匹配 v 前缀标签的派生公式', () => {
    expect(DESCRIBE_ARGS).toEqual(['describe', '--tags', '--match', 'v*', '--exact-match'])
  })
})
