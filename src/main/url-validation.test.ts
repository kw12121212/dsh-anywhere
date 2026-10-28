import { describe, expect, it } from 'vitest'
import { URLValidationError, URL_ERRORS, hasToken, normalizeHarnessURL, redactURL } from './url-validation'

function validationMessage(raw: string): string {
  try {
    normalizeHarnessURL(raw)
  } catch (err) {
    expect(err).toBeInstanceOf(URLValidationError)
    return (err as Error).message
  }
  throw new Error('expected validation error')
}

describe('normalizeHarnessURL', () => {
  it('忽略首尾空白并接受 https 地址', () => {
    expect(normalizeHarnessURL('  https://dsh.example.test:13080/  ')).toBe('https://dsh.example.test:13080/')
  })

  it('拒绝空值、相对路径与其他协议', () => {
    expect(validationMessage('   ')).toBe(URL_ERRORS.empty)
    expect(validationMessage('/chat')).toBe(URL_ERRORS.notAbsolute)
    expect(validationMessage('dsh.example.test/chat')).toBe(URL_ERRORS.notAbsolute)
    expect(validationMessage('ftp://dsh.example.test/')).toBe(URL_ERRORS.unsupportedScheme)
    expect(validationMessage('file:///tmp/x')).toBe(URL_ERRORS.unsupportedScheme)
    expect(validationMessage('javascript:alert(1)')).toBe(URL_ERRORS.unsupportedScheme)
  })

  it('拒绝无法解析或没有主机名的地址，错误信息不含 token', () => {
    const message = validationMessage('http://dsh.example.test/%zz?token=abc123')
    expect(message).toBe(URL_ERRORS.malformed)
    expect(message).not.toContain('abc123')
    expect(validationMessage('http://')).toBe(URL_ERRORS.malformed)
  })

  it('去掉所有 token 参数，保留其他参数、路径与片段', () => {
    expect(normalizeHarnessURL('http://127.0.0.1:13080/?token=abc123')).toBe('http://127.0.0.1:13080/')
    expect(normalizeHarnessURL('https://dsh.example.test/base/?lang=zh&token=abc123&token=def456#top')).toBe(
      'https://dsh.example.test/base/?lang=zh#top'
    )
    expect(normalizeHarnessURL('https://dsh.example.test/base/?lang=zh')).toBe('https://dsh.example.test/base/?lang=zh')
  })

  it('按解码后的参数名匹配 token，并保留其他参数的原有编码', () => {
    expect(normalizeHarnessURL('https://dsh.example.test/?%74oken=abc&q=a%20b&&tokens=1')).toBe(
      'https://dsh.example.test/?q=a%20b&tokens=1'
    )
    expect(normalizeHarnessURL('https://dsh.example.test/?%E0=1&Token=2')).toBe('https://dsh.example.test/?%E0=1&Token=2')
  })
})

describe('redactURL', () => {
  it('日志文本去掉 token 并隐藏密码', () => {
    expect(redactURL('https://dsh.example.test/?token=abc123')).toBe('https://dsh.example.test/')
    expect(redactURL('https://user:secret@dsh.example.test/')).toBe('https://user:xxxxx@dsh.example.test/')
  })

  it('无法解析时不回显原文', () => {
    expect(redactURL('not a url token=abc123')).toBe('<无法解析的地址>')
  })
})

describe('hasToken', () => {
  it('识别带 token 的地址', () => {
    expect(hasToken('https://dsh.example.test/?token=abc')).toBe(true)
    expect(hasToken('https://dsh.example.test/?lang=zh')).toBe(false)
    expect(hasToken('https://dsh.example.test/')).toBe(false)
    expect(hasToken('::')).toBe(false)
  })
})
