import { describe, expect, it } from 'vitest'
import { REASON_TEXT } from './reasons'

describe('REASON_TEXT', () => {
  it('按主规格 load-failure 的场景说明失败原因', () => {
    expect(REASON_TEXT.refused).toContain('连接被拒绝')
    expect(REASON_TEXT.dns).toContain('无法解析主机名')
    expect(REASON_TEXT.tls).toContain('安全连接（TLS）失败')
    expect(REASON_TEXT.timeout).toContain('连接超时')
    expect(REASON_TEXT.other).toBe('无法连接到该地址。')
  })
})
