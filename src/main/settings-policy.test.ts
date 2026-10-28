import { describe, expect, it } from 'vitest'
import { shouldOpenRemoteAfterSettingsSave } from './settings-policy'

const OK_A = { status: 'ok', url: 'https://a.example.test/' } as const
const CLEAN_A = 'https://a.example.test/'

function decide(overrides: Partial<Parameters<typeof shouldOpenRemoteAfterSettingsSave>[0]>): boolean {
  return shouldOpenRemoteAfterSettingsSave({
    previous: OK_A,
    clean: CLEAN_A,
    trimmedInput: CLEAN_A,
    mainShowsHarness: true,
    ...overrides
  })
}

describe('shouldOpenRemoteAfterSettingsSave', () => {
  it('地址未改动、输入不带 token 且主窗口正显示 harness 时不重新加载', () => {
    expect(decide({})).toBe(false)
  })

  it('前次配置缺失或无效时打开', () => {
    expect(decide({ previous: { status: 'missing' } })).toBe(true)
    expect(decide({ previous: { status: 'invalid' } })).toBe(true)
  })

  it('干净地址已变更时打开', () => {
    expect(decide({ clean: 'http://127.0.0.1:13080/', trimmedInput: 'http://127.0.0.1:13080/' })).toBe(true)
  })

  it('原始输入带 token 时即使地址不变也打开', () => {
    expect(decide({ trimmedInput: `${CLEAN_A}?token=def456` })).toBe(true)
  })

  it('主窗口不在 harness 页面（本地页面）时打开', () => {
    expect(decide({ mainShowsHarness: false })).toBe(true)
  })
})
