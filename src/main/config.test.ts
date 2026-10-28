import { chmodSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  CONFIG_FILE_NAME,
  URLValidationError,
  URL_ERRORS,
  ensurePrivateDir,
  hasToken,
  loadConfig,
  normalizeHarnessURL,
  redactURL,
  saveConfig
} from './config'

let dir: string
let path: string

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'config-'))
  path = join(dir, CONFIG_FILE_NAME)
})

afterEach(() => {
  rmSync(dir, { recursive: true, force: true })
})

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

describe('loadConfig / saveConfig', () => {
  it('从未保存过时为 missing', () => {
    expect(loadConfig(path)).toEqual({ status: 'missing' })
  })

  it('保存返回并持久化干净地址，重新读取得到同一地址', () => {
    expect(saveConfig(path, 'http://127.0.0.1:13080/?token=abc123')).toBe('http://127.0.0.1:13080/')
    expect(readFileSync(path, 'utf8')).not.toContain('abc123')
    expect(JSON.parse(readFileSync(path, 'utf8'))).toEqual({ harnessURL: 'http://127.0.0.1:13080/' })
    expect(loadConfig(path)).toEqual({ status: 'ok', url: 'http://127.0.0.1:13080/' })
  })

  it('覆盖保存后不残留临时文件', () => {
    saveConfig(path, 'https://a.example.test/')
    saveConfig(path, 'https://b.example.test/')
    expect(loadConfig(path)).toEqual({ status: 'ok', url: 'https://b.example.test/' })
    expect(readdirSync(dir)).toEqual([CONFIG_FILE_NAME])
  })

  it('配置文件只对当前用户可读写', () => {
    if (process.platform === 'win32') {
      return
    }
    saveConfig(path, 'https://a.example.test/')
    expect(statSync(path).mode & 0o777).toBe(0o600)
  })

  it('校验失败时不修改已有配置，也不创建配置文件', () => {
    expect(() => saveConfig(path, '/chat')).toThrow(URLValidationError)
    expect(loadConfig(path)).toEqual({ status: 'missing' })
    saveConfig(path, 'https://a.example.test/')
    expect(() => saveConfig(path, '  ')).toThrow(URL_ERRORS.empty)
    expect(loadConfig(path)).toEqual({ status: 'ok', url: 'https://a.example.test/' })
  })

  it('内容损坏、字段缺失或地址无效时为 invalid', () => {
    writeFileSync(path, '{not json')
    expect(loadConfig(path)).toEqual({ status: 'invalid' })
    writeFileSync(path, 'null')
    expect(loadConfig(path)).toEqual({ status: 'invalid' })
    writeFileSync(path, JSON.stringify({ harnessURL: 42 }))
    expect(loadConfig(path)).toEqual({ status: 'invalid' })
    writeFileSync(path, JSON.stringify({ harnessURL: 'ftp://dsh.example.test/' }))
    expect(loadConfig(path)).toEqual({ status: 'invalid' })
  })

  it('无法读取时为 invalid', () => {
    expect(loadConfig(dir)).toEqual({ status: 'invalid' })
  })

  it('手工写入的 token 在读取时被剥离', () => {
    writeFileSync(path, JSON.stringify({ harnessURL: 'https://dsh.example.test/?token=abc123' }))
    expect(loadConfig(path)).toEqual({ status: 'ok', url: 'https://dsh.example.test/' })
  })
})

describe('ensurePrivateDir', () => {
  it('创建缺失的目录并在 POSIX 上收紧已有目录的权限', () => {
    const nested = join(dir, 'a', 'b')
    ensurePrivateDir(nested)
    expect(statSync(nested).isDirectory()).toBe(true)
    if (process.platform === 'win32') {
      return
    }
    chmodSync(nested, 0o755)
    ensurePrivateDir(nested)
    expect(statSync(nested).mode & 0o777).toBe(0o700)
  })

  it('Windows 上不修改权限', () => {
    const nested = join(dir, 'win')
    ensurePrivateDir(nested, 'win32')
    expect(statSync(nested).isDirectory()).toBe(true)
  })
})
