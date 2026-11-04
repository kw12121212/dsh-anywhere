// 模拟 dsh 服务端：仅本地验收用（android-main-webview 与 android-session-auth 模拟器验收）。
// 监听 127.0.0.1:13080，模拟器经 10.0.2.2:13080 访问宿主。
// 页面包含：流式 chunked 输出、同源链接、外部链接、window.open（同源/外部）、
// file: 打开按钮、跨主机 http 子资源引用。
// 用法：node scripts/mock-dsh-server.mjs [--port 13080] [--status <code>] [--delay <ms>]
import { createServer } from 'node:http'

function argValue(name, fallback) {
  const i = process.argv.indexOf(`--${name}`)
  const raw = i >= 0 ? process.argv[i + 1] : undefined
  const num = Number(raw)
  return raw !== undefined && Number.isFinite(num) ? num : fallback
}

const port = argValue('port', 13080)
const status = argValue('status', 200)
const delayMs = argValue('delay', 0)

const crossOriginPort = port + 1
const page = () => `<!doctype html>
<html><head><meta charset="utf-8"><title>mock dsh harness</title>
<!-- 跨源（不同端口）明文子资源：真实存在；被 App 明文策略拦截时不会执行，
     未被拦截时会执行并把 state 改为 state:blocked-loaded（server 日志也会记录请求） -->
<script src="http://10.0.2.2:${crossOriginPort}/tracker2.js"></script>
<style>
  body { font-family: sans-serif; margin: 16px; }
  .block { margin: 32px 0; padding: 20px; }
  #stream { white-space: pre-wrap; min-height: 3em; border: 1px solid #ccc; padding: 8px; }
  a, button { font-size: 22px; padding: 14px; }
</style>
</head><body>
<h1 id="title">mock dsh harness</h1>
<div class="block" id="stream"></div>
<div class="block"><a id="same-origin-link" href="/sessions/1">同源链接：/sessions/1</a></div>
<div class="block"><a id="external-link" href="https://example.org/docs">外部链接：example.org</a></div>
<div class="block"><button id="open-same-origin" onclick="window.open('/sessions/2')">window.open 同源</button></div>
<div class="block"><button id="open-external" onclick="window.open('https://example.org/popup')">window.open 外部</button></div>
<div class="block"><button id="open-file" onclick="window.open('file:///etc/passwd')">window.open file:</button></div>
<div class="block" id="state">state:loaded</div>
<script>
  // 流式接收模拟（chunked 注入页面 DOM，验收断言最终行数）。
  const stream = document.getElementById('stream')
  let n = 0
  const timer = setInterval(() => {
    n += 1
    stream.textContent += \`chunk \${n}\\n\`
    if (n >= 5) { clearInterval(timer); document.getElementById('state').textContent = 'state:stream-done' }
  }, 300)
  window.blockedLoaded = false
  // 验收辅助：把可点元素的物理像素边界上报给 mock server（uiautomator 看不到 WebView DOM）。
  const dpr = window.devicePixelRatio || 1
  for (const id of ['same-origin-link', 'external-link', 'open-same-origin', 'open-external', 'open-file', 'state']) {
    const el = document.getElementById(id)
    if (!el) continue
    const r = el.getBoundingClientRect()
    fetch('/rect/' + id + '?l=' + Math.round(r.left * dpr) + '&t=' + Math.round((r.top + window.scrollY) * dpr) +
      '&r=' + Math.round(r.right * dpr) + '&b=' + Math.round((r.bottom + window.scrollY) * dpr) + '&dpr=' + dpr)
  }
</script>
</body></html>`

const sessionPage = `<!doctype html><html><head><meta charset="utf-8"></head>
<body><h1 id="session-page">session page</h1><a id="back-home" href="/">返回首页</a></body></html>`

// ── 会话语义（android-session-auth 验收）──
// 有效 token 列表：/?token=<t> 命中即签发 HttpOnly SameSite=Strict cookie 并 302 到 /；
// / 依 cookie 有效性返回 200 harness 页或 401 text/plain 固定正文（与 dsh 一致）；
// /invalidate 清空 token 与会话（模拟 dsh web 重启使既有会话失效）。
const EXPIRED_MARKER = 'dsh web authentication required; reopen the URL printed by dsh web.'
// token 轮次：/invalidate 模拟 dsh web 重启——旧轮 token 与会话全部失效，
// 下一轮 token 视为重启后 dsh-url 新输出的地址。
const tokenRounds = [['emu-token-1'], ['emu-token-2'], ['emu-token-3']]
let tokenRound = 0
const validTokens = new Set(tokenRounds[0])
const sessions = new Map() // cookie 值 -> 会话 id
function sessionCookie(req) {
  const header = req.headers.cookie ?? ''
  const m = /(?:^|;\s*)session=([^;]+)/.exec(header)
  return m ? m[1] : null
}

const server = createServer((req, res) => {
  const url = req.url ?? '/'
  console.log(`${new Date().toISOString()} ${req.method} ${url} <- ${req.socket.remoteAddress}`)
  if (url === '/' || url.startsWith('/?')) {
    const qs = new URL(url, 'http://x').searchParams
    const presentedToken = qs.get('token')
    if (presentedToken !== null) {
      if (validTokens.has(presentedToken)) {
        const sid = 's' + (sessions.size + 1)
        sessions.set(sid, sid)
        console.log(`  -> issue session ${sid}, 302 /`)
        res.writeHead(302, {
          Location: '/',
          'Set-Cookie': `session=${sid}; Path=/; HttpOnly; SameSite=Strict`,
        })
        res.end()
        return
      }
      console.log('  -> invalid token, 401')
      res.writeHead(401, { 'Content-Type': 'text/plain' })
      res.end(EXPIRED_MARKER)
      return
    }
    const sid = sessionCookie(req)
    if (sid && sessions.has(sid)) {
      // 会话有效：harness 页（--delay 让探测可见地停留）。
      setTimeout(() => {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
        res.end(page())
      }, delayMs)
      return
    }
    console.log('  -> no/invalid session, 401')
    res.writeHead(401, { 'Content-Type': 'text/plain' })
    res.end(EXPIRED_MARKER)
    return
  }
  if (url === '/whoami') {
    const sid = sessionCookie(req)
    res.writeHead(sid && sessions.has(sid) ? 200 : 401, { 'Content-Type': 'text/plain' })
    res.end(sid && sessions.has(sid) ? sid : 'no-session')
    return
  }
  if (url === '/invalidate') {
    tokenRound = Math.min(tokenRound + 1, tokenRounds.length - 1)
    validTokens.clear()
    for (const t of tokenRounds[tokenRound]) validTokens.add(t)
    sessions.clear()
    console.log(`  -> sessions invalidated (dsh restart); current round tokens: ${[...validTokens].join(',')}`)
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    res.end('invalidated')
    return
  }
  if (url.startsWith('/sessions/')) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    res.end(sessionPage)
    return
  }
  if (url.startsWith('/slow')) {
    // 慢端点：验证「正在连接」提示与超时分类（--status 控制主页面响应码）。
    setTimeout(() => {
      res.writeHead(200, { 'Content-Type': 'text/plain' })
      res.end('slow ok')
    }, 12_000)
    return
  }
  res.writeHead(404, { 'Content-Type': 'text/plain' })
  res.end('not found: ' + url)
})

server.listen(port, '127.0.0.1', () => {
  console.log(`mock dsh server on http://127.0.0.1:${port}/ (status=${status})`)
  // 跨源（不同端口）资源服务：真实可服务，验证 App 的明文拦截把它挡在客户端。
  const crossServer = createServer((req, res) => {
    console.log(`${new Date().toISOString()} CROSS-ORIGIN ${req.method} ${req.url}`)
    res.writeHead(200, { 'Content-Type': 'application/javascript' })
    res.end("document.getElementById('state').textContent = 'state:blocked-loaded'")
  })
  crossServer.listen(crossOriginPort, '127.0.0.1', () => {
    console.log(`cross-origin resource server on http://127.0.0.1:${crossOriginPort}/`)
  })
})
