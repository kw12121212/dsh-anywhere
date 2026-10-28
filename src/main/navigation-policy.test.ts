import { describe, expect, it } from 'vitest'
import { classifyOpen, sameOrigin } from './navigation-policy'

const harness = 'https://dsh.example.test/'

describe('sameOrigin', () => {
  it('协议、主机、端口都相同才同源，默认端口与显式端口等价', () => {
    expect(sameOrigin(harness, 'https://dsh.example.test/sessions/1')).toBe(true)
    expect(sameOrigin(harness, 'https://dsh.example.test:443/x')).toBe(true)
    expect(sameOrigin(harness, 'https://dsh.example.test:8443/')).toBe(false)
    expect(sameOrigin(harness, 'http://dsh.example.test/')).toBe(false)
    expect(sameOrigin(harness, 'https://other.example.test/')).toBe(false)
  })

  it('无法解析或非 http/https 地址不同源', () => {
    expect(sameOrigin(harness, 'not a url')).toBe(false)
    expect(sameOrigin('file:///a', 'file:///a')).toBe(false)
  })
})

describe('classifyOpen', () => {
  it('同源链接与 window.open 留在主窗口', () => {
    expect(classifyOpen(harness, 'https://dsh.example.test/sessions/2')).toBe('stay')
  })

  it('非同源 http/https 交给系统浏览器，包括不同端口', () => {
    expect(classifyOpen(harness, 'https://example.org/docs')).toBe('external')
    expect(classifyOpen(harness, 'http://example.org/popup')).toBe('external')
    expect(classifyOpen(harness, 'https://dsh.example.test:8443/')).toBe('external')
  })

  it('其他协议被忽略', () => {
    expect(classifyOpen(harness, 'file:///etc/passwd')).toBe('ignore')
    expect(classifyOpen(harness, 'javascript:alert(1)')).toBe('ignore')
    expect(classifyOpen(harness, 'mailto:a@example.org')).toBe('ignore')
    expect(classifyOpen(harness, '')).toBe('ignore')
  })
})
