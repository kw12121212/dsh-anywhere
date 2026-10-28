import { describe, expect, it } from 'vitest'
import { EXPIRED_MARKER, isSessionExpired, tokenEntryIndices } from './session'

describe('isSessionExpired', () => {
  const expiredDoc = { contentType: 'text/plain', text: `${EXPIRED_MARKER}\n` }

  it('401、text/plain 且正文为 dsh 固定文本时判定为失效', () => {
    expect(isSessionExpired(401, expiredDoc)).toBe(true)
  })

  it('状态码、类型或正文不符时不判定为失效', () => {
    expect(isSessionExpired(200, expiredDoc)).toBe(false)
    expect(isSessionExpired(403, expiredDoc)).toBe(false)
    expect(isSessionExpired(401, { contentType: 'text/html', text: EXPIRED_MARKER })).toBe(false)
    expect(isSessionExpired(401, { contentType: 'text/plain', text: 'Unauthorized' })).toBe(false)
    expect(isSessionExpired(401, { contentType: 'text/plain', text: null })).toBe(false)
    expect(isSessionExpired(401, null)).toBe(false)
  })
})

describe('tokenEntryIndices', () => {
  it('返回含 token 的非当前条目，从大到小排列', () => {
    const urls = [
      'https://dsh.example.test/?token=a',
      'https://dsh.example.test/',
      'https://dsh.example.test/?token=b',
      'https://dsh.example.test/x'
    ]
    expect(tokenEntryIndices(urls, 3)).toEqual([2, 0])
  })

  it('不返回当前条目', () => {
    expect(tokenEntryIndices(['https://dsh.example.test/?token=a'], 0)).toEqual([])
  })
})
