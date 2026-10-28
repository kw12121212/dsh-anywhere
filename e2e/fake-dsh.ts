import { randomBytes } from 'node:crypto'
import { createServer } from 'node:http'
import type { IncomingMessage, Server, ServerResponse } from 'node:http'
import type { AddressInfo } from 'node:net'

// 与 dsh web 会话失效时返回的正文一致。
export const EXPIRED_TEXT = 'dsh web authentication required; reopen the URL printed by dsh web.\n'
export const VALID_TOKEN = 'launch-token-abc123'

export interface RequestRecord {
  path: string
  cookie: 'valid' | 'invalid' | 'none'
}

// FakeDsh 模拟 dsh web 的启动令牌流程：带有效 token 的请求签发 HttpOnly、SameSite=Strict 会话 cookie
// 并 302 到去掉 token 的地址；没有有效会话时返回 401 与固定正文；/status/502 返回带正文的 502，/status/502-empty 返回正文为空的 502。
export class FakeDsh {
  readonly requests: RequestRecord[] = []
  private readonly sessions = new Set<string>()
  private server: Server | null = null
  private port = 0

  get baseURL(): string {
    return `http://127.0.0.1:${this.port}/`
  }

  async start(port = 0): Promise<void> {
    const server = createServer((req, res) => this.handle(req, res))
    await new Promise<void>((resolve, reject) => {
      server.once('error', reject)
      server.listen(port, '127.0.0.1', () => resolve())
    })
    this.server = server
    this.port = (server.address() as AddressInfo).port
  }

  async stop(): Promise<void> {
    const server = this.server
    this.server = null
    if (server !== null) {
      server.closeAllConnections()
      await new Promise<void>((resolve) => server.close(() => resolve()))
    }
  }

  // expireSessions 模拟 dsh web 重启：已签发的会话全部失效。
  expireSessions(): void {
    this.sessions.clear()
  }

  private handle(req: IncomingMessage, res: ServerResponse): void {
    const url = new URL(req.url ?? '/', this.baseURL)
    const session = /(?:^|;\s*)dsh_session=([^;]+)/.exec(req.headers.cookie ?? '')?.[1]
    const valid = session !== undefined && this.sessions.has(session)
    const token = url.searchParams.get('token')
    url.searchParams.delete('token')
    this.requests.push({
      path: `${url.pathname}${url.search}${token === null ? '' : `${url.search === '' ? '?' : '&'}token=*`}`,
      cookie: valid ? 'valid' : session === undefined ? 'none' : 'invalid'
    })

    if (token === VALID_TOKEN) {
      const id = randomBytes(8).toString('hex')
      this.sessions.add(id)
      res.writeHead(302, {
        'Set-Cookie': `dsh_session=${id}; HttpOnly; SameSite=Strict; Path=/; Max-Age=3600`,
        Location: `${url.pathname}${url.search}`
      })
      res.end()
      return
    }
    if (url.pathname === '/status/502-empty') {
      res.writeHead(502)
      res.end()
      return
    }
    if (url.pathname === '/status/502') {
      res.writeHead(502, { 'Content-Type': 'text/plain' })
      res.end('bad gateway from fake dsh')
      return
    }
    if (!valid) {
      res.writeHead(401, { 'Content-Type': 'text/plain' })
      res.end(EXPIRED_TEXT)
      return
    }
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    res.end(`<!doctype html><title>harness ${url.pathname}</title>
<h1 id="harness">harness ${url.pathname}</h1>
<a id="same" href="/sessions/1">same</a>
<a id="blank" href="/sessions/3" target="_blank">blank</a>
<a id="ext" href="https://example.org/docs">ext</a>
<a id="port" href="http://127.0.0.1:${this.port === 1 ? 2 : 1}/">port</a>
<a id="file" href="file:///etc/passwd">file</a>`)
  }
}
