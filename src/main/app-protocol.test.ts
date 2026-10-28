import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  APP_ENTRY_URL,
  LOCAL_PAGE_CSP,
  LOCAL_PAGE_CSP_HEADER,
  createAppProtocolHandler,
  resolveAppPath
} from './app-protocol'
import type { FileFetcher } from './app-protocol'

let root: string

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), 'app-protocol-'))
  mkdirSync(join(root, 'assets'))
  writeFileSync(join(root, 'index.html'), '<h1>local</h1>')
  writeFileSync(join(root, 'assets', 'app.js'), 'console.log(1)')
})

afterEach(() => {
  rmSync(root, { recursive: true, force: true })
})

// 运行时由 net.fetch 读取 file:// 地址；测试用真实文件系统读取实现同样的约定。
const readFromDisk: FileFetcher = async (fileURL) => new Response(readFileSync(fileURLToPath(fileURL)), { status: 200 })

describe('resolveAppPath', () => {
  it('把入口地址映射到根目录下的 index.html', () => {
    expect(resolveAppPath(root, APP_ENTRY_URL)).toBe(join(resolve(root), 'index.html'))
  })

  it('根路径回退到 index.html', () => {
    expect(resolveAppPath(root, 'app://local/')).toBe(join(resolve(root), 'index.html'))
  })

  it('映射子目录中的文件，并解码百分号编码', () => {
    expect(resolveAppPath(root, 'app://local/assets/app.js')).toBe(join(resolve(root), 'assets', 'app.js'))
    expect(resolveAppPath(root, 'app://local/assets/a%20b.js')).toBe(join(resolve(root), 'assets', 'a b.js'))
  })

  it('URL 解析器规范化掉的点段仍落在根目录内', () => {
    expect(resolveAppPath(root, 'app://local/../secret.txt')).toBe(join(resolve(root), 'secret.txt'))
    expect(resolveAppPath(root, 'app://local/%2e%2e/secret.txt')).toBe(join(resolve(root), 'secret.txt'))
  })

  it.each([
    ['编码的斜杠', 'app://local/..%2F..%2Fsecret.txt'],
    ['子目录中跳出', 'app://local/assets/..%2F..%2Fsecret.txt'],
    ['跳到同前缀的兄弟目录', 'app://local/..%2Fsibling%2Fsecret.txt']
  ])('解码后解析到根目录之外时返回 null（%s）', (_name, url) => {
    expect(resolveAppPath(root, url)).toBeNull()
  })

  it('编码的反斜杠只在 Windows 上作为分隔符', () => {
    const resolved = resolveAppPath(root, 'app://local/..%5C..%5Csecret.txt')
    if (process.platform === 'win32') {
      expect(resolved).toBeNull()
    } else {
      expect(resolved).toBe(join(resolve(root), '..\\..\\secret.txt'))
    }
  })

  it('允许以两个点开头的普通文件名', () => {
    expect(resolveAppPath(root, 'app://local/..foo.js')).toBe(join(resolve(root), '..foo.js'))
  })

  it('拒绝其他主机、其他协议与非法地址', () => {
    expect(resolveAppPath(root, 'app://other/index.html')).toBeNull()
    expect(resolveAppPath(root, 'https://local/index.html')).toBeNull()
    expect(resolveAppPath(root, 'file:///etc/passwd')).toBeNull()
    expect(resolveAppPath(root, 'not a url')).toBeNull()
  })

  it('拒绝非法的百分号编码', () => {
    expect(resolveAppPath(root, 'app://local/%E0%A4%A')).toBeNull()
  })
})

describe('createAppProtocolHandler', () => {
  it('返回文件内容并附加 CSP 响应头', async () => {
    const handler = createAppProtocolHandler(root, readFromDisk)
    const response = await handler(new Request(APP_ENTRY_URL))
    expect(response.status).toBe(200)
    expect(await response.text()).toBe('<h1>local</h1>')
    expect(response.headers.get('content-security-policy')).toBe(LOCAL_PAGE_CSP_HEADER)
  })

  it('保留原响应头', async () => {
    const fetcher: FileFetcher = async () => new Response('x', { headers: { 'content-type': 'text/javascript' } })
    const response = await createAppProtocolHandler(root, fetcher)(new Request('app://local/assets/app.js'))
    expect(response.headers.get('content-type')).toBe('text/javascript')
    expect(response.headers.get('content-security-policy')).toBe(LOCAL_PAGE_CSP_HEADER)
  })

  it('越界路径返回 404 且不读取文件', async () => {
    let fetched = false
    const fetcher: FileFetcher = async () => {
      fetched = true
      return new Response('secret')
    }
    const response = await createAppProtocolHandler(root, fetcher)(new Request('app://local/..%2F..%2Fsecret.txt'))
    expect(response.status).toBe(404)
    expect(fetched).toBe(false)
    expect(response.headers.get('content-security-policy')).toBe(LOCAL_PAGE_CSP_HEADER)
  })

  it('文件不存在时返回 404', async () => {
    const response = await createAppProtocolHandler(root, readFromDisk)(new Request('app://local/missing.js'))
    expect(response.status).toBe(404)
  })

  it('读取结果非成功状态时返回 404', async () => {
    const fetcher: FileFetcher = async () => new Response('', { status: 500 })
    const response = await createAppProtocolHandler(root, fetcher)(new Request(APP_ENTRY_URL))
    expect(response.status).toBe(404)
  })
})

describe('LOCAL_PAGE_CSP', () => {
  it('不允许 unsafe-eval 与 unsafe-inline', () => {
    expect(LOCAL_PAGE_CSP_HEADER).not.toContain('unsafe-eval')
    expect(LOCAL_PAGE_CSP_HEADER).not.toContain('unsafe-inline')
    expect(LOCAL_PAGE_CSP).toContain("script-src 'self'")
    expect(LOCAL_PAGE_CSP_HEADER).toBe(`${LOCAL_PAGE_CSP}; frame-ancestors 'none'`)
  })

  it('与本地页面 meta 标签中的 CSP 一致', () => {
    const html = readFileSync(resolve(__dirname, '../renderer/index.html'), 'utf8')
    const match = html.match(/http-equiv="Content-Security-Policy"\s+content="([^"]+)"/)
    expect(match?.[1]).toBe(LOCAL_PAGE_CSP)
  })
})
