import { describe, expect, it } from 'vitest'
import { warmUpWebGlobals } from './web-globals'

describe('warmUpWebGlobals', () => {
  it('重复调用不抛错，且 Web 全局仍可构造 Response', () => {
    expect(() => warmUpWebGlobals()).not.toThrow()
    expect(() => warmUpWebGlobals()).not.toThrow()
    expect(new Response('ok').status).toBe(200)
  })
})
