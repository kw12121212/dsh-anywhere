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
const forbidden = process.argv.includes('--forbidden')

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
let stateLiveProbeSent = false
let cardAnswered = false
let btwDeleted = new Set()
let lastSideId = ''
const sideSessions = new Map()
const uploads = new Map()
// 预置 1x1 PNG（dsbot-attachments 验收：聊天附件引用条目 → mux 读取字节）
const MOCK_PNG = Buffer.from(
  '89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c63f8cfc0f01f00' +
  '050001ff89993d1d0000000049454e44ae426082', 'hex')
const MOCK_PNG_SHA = createHash('sha256').update(MOCK_PNG).digest('hex')
uploads.set('s-bot-1:' + MOCK_PNG_SHA, { data: MOCK_PNG, name: 'mock.png' })
const MOCK_TXT = Buffer.from('mock file attachment content', 'utf8')
const MOCK_TXT_SHA = createHash('sha256').update(MOCK_TXT).digest('hex')
uploads.set('s-bot-1:' + MOCK_TXT_SHA, { data: MOCK_TXT, name: 'note.txt' })

const feedClients = new Set() // 首次增量应答带 live 变化档，之后回最小 unchanged
function sessionCookie(req) {
  const header = req.headers.cookie ?? ''
  const m = /(?:^|;\s*)dsh-auth-emu1=([^;]+)/.exec(header)
  return m ? m[1] : null
}

// 最小 WebSocket 服务端（/api/remote.mux）：SHA1 握手 + 掩码帧解析 + mux RPC。
// 调用前 cookie 已由 server.on("upgrade") 校验。
function handleMuxUpgrade(req, socket) {
    const key = req.headers['sec-websocket-key']
    const accept = createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64')
    const sock = socket
    const CRLF = String.fromCharCode(13, 10)
    sock.write(
      'HTTP/1.1 101 Switching Protocols' + CRLF +
      'Upgrade: websocket' + CRLF +
      'Connection: Upgrade' + CRLF +
      'Sec-WebSocket-Accept: ' + accept + CRLF + CRLF,
    )
    sock.setNoDelay(true)
    const sendText = (text) => {
      const payload = Buffer.from(text, 'utf8')
      let header
      if (payload.length < 126) header = Buffer.from([0x81, payload.length])
      else if (payload.length < 65536) {
        header = Buffer.alloc(4)
        header[0] = 0x81; header[1] = 126; header.writeUInt16BE(payload.length, 2)
      } else {
        header = Buffer.alloc(10)
        header[0] = 0x81; header[1] = 127; header.writeBigUInt64BE(BigInt(payload.length), 2)
      }
      sock.write(Buffer.concat([header, payload]))
    }
    let buf = Buffer.alloc(0)
    let closed = false
    const handleMessage = (text) => {
      let msg
      try { msg = JSON.parse(text) } catch { return }
      if (msg.type !== 'open') return
      const id = msg.streamId
      const endpoint = msg.endpoint
      const request = msg.payload?.args?.request
      setTimeout(() => {
        if (closed) return
        if (endpoint === 'session/prompt') {
          const blocks = Array.isArray(request?.content) ? request.content : []
          const files = blocks.filter((b) => b.type === 'file').map((b) => b.receiptId)
          if (!request?.sessionId || files.length === 0) {
            sendText(JSON.stringify({ type: 'error', streamId: id, error: { code: 'gateway/input-invalid', message: 'mock: sessionId 与至少一个 file receipt 必填' } }))
            return
          }
          sendText(JSON.stringify({ type: 'item', streamId: id, value: { ok: true, value: { accepted: true, receipts: files } } }))
          sendText(JSON.stringify({ type: 'end', streamId: id }))
          // 回复经 feed 推送：图片附件由 Bot "看到"并发一段文本
          feedClients.forEach((write) => {
            write('event: stream\ndata: {"botId":"bot-1","sessionId":"s-bot-1","attemptId":"a2","phase":"start"}\n\n')
            write('event: stream\ndata: {"botId":"bot-1","sessionId":"s-bot-1","attemptId":"a2","text":"收到附件"}\n\n')
            write('event: stream\ndata: {"botId":"bot-1","sessionId":"s-bot-1","attemptId":"a2","phase":"end"}\n\n')
            write('event: message\ndata: {"botId":"bot-1","sessionId":"s-bot-1","item":{"kind":"bot","text":"收到附件（' + files.length + ' 个）","seq":100}}\n\n')
          })
          return
        }
        if (endpoint === 'session/attachment') {
          const ref = request?.attachment
          const stored = ref ? uploads.get(request.sessionId + ':' + String(ref.attachmentId ?? '').replace('sha256:', '')) : null
          if (!stored) {
            sendText(JSON.stringify({ type: 'error', streamId: id, error: { code: 'session/attachment-invalid', message: 'mock: attachment not found' } }))
            return
          }
          sendText(JSON.stringify({ type: 'item', streamId: id, value: { ok: true, value: { attachment: ref, data: stored.data.toString('base64') } } }))
          sendText(JSON.stringify({ type: 'end', streamId: id }))
          return
        }
        sendText(JSON.stringify({ type: 'error', streamId: id, error: { code: 'gateway/internal', message: 'mock: unknown endpoint ' + endpoint } }))
      }, 60)
    }
    sock.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk])
      while (true) {
        if (buf.length < 2) break
        const opcode = buf[0] & 0x0f
        const masked = (buf[1] & 0x80) !== 0
        let len = buf[1] & 0x7f
        let off = 2
        if (len === 126) { if (buf.length < 4) break; len = buf.readUInt16BE(2); off = 4 }
        else if (len === 127) { if (buf.length < 10) break; len = Number(buf.readBigUInt64BE(2)); off = 10 }
        const maskLen = masked ? 4 : 0
        if (buf.length < off + maskLen + len) break
        const mask = masked ? buf.subarray(off, off + 4) : null
        const payload = Buffer.from(buf.subarray(off + maskLen, off + maskLen + len))
        if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3]
        buf = buf.subarray(off + maskLen + len)
        if (opcode === 8) { closed = true; socket.end(); return }
        if (opcode === 1) handleMessage(payload.toString('utf8'))
      }
    })
    sock.on('close', () => { closed = true })
    sock.on('error', () => { closed = true; try { socket.destroy() } catch {} })
    return
}

import { createHash } from 'node:crypto'

const server = createServer((req, res) => {
  const url = req.url ?? '/'
  console.log(`${new Date().toISOString()} ${req.method} ${url} <- ${req.socket.remoteAddress}`)
  // /api/file?path=<服务器绝对路径>（带会话 cookie）：模拟真实 dsh 的版本重定向——
  // Location 只给查询串（"?path=…&v=…"），再请求一次才出字节（与 dsh-api-session-
  // controller 的 serveFile 一致，对话内文件路径链接的 App 端打开路径）。
  const fileUrl = url.startsWith('/api/file') ? new URL(url, 'http://x') : null
  if (fileUrl) {
    if (!sessionCookie(req) || !sessions.has(sessionCookie(req))) {
      res.writeHead(401, { 'Content-Type': 'text/plain' })
      res.end(EXPIRED_MARKER)
      return
    }
    const path = fileUrl.searchParams.get('path') ?? ''
    if (!path.startsWith('/home/')) {
      res.writeHead(404, { 'Content-Type': 'text/plain' })
      res.end('not found')
      return
    }
    if (!fileUrl.searchParams.has('v')) {
      res.writeHead(302, { Location: `?path=${encodeURIComponent(path)}&v=mocksig` })
      res.end()
      return
    }
    const name = path.split('/').pop() ?? 'file'
    const type = name.endsWith('.mp4') ? 'video/mp4' : name.endsWith('.png') ? 'image/png' : 'application/octet-stream'
    res.writeHead(200, { 'Content-Type': type })
    res.end(Buffer.from(`mock file bytes for ${name}`))
    return
  }
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
          'Set-Cookie': `dsh-auth-emu1=${sid}; Path=/; HttpOnly; SameSite=Strict`,
        })
        res.end()
        return
      }
      console.log('  -> invalid token, 401')
      res.writeHead(401, { 'Content-Type': 'text/plain' })
      res.end(EXPIRED_MARKER)
      return
    }
    if (forbidden) {
      console.log('  -> forbidden mode, 403')
      res.writeHead(403, { 'Content-Type': 'text/plain' })
      res.end('forbidden')
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
  // /api/bot 最小实现（dsbot-native-shell 验收）：update-check 返回与基准不一致的版本，
  // state 返回全量视图（revision 数字），feed 为 SSE（hello + keepalive）。
  if (url === '/api/bot' && req.method === 'POST') {
    let body = ''
    req.on('data', (c) => (body += c))
    req.on('end', () => {
      const sid = sessionCookie(req)
      if (!sid || !sessions.has(sid)) {
        res.writeHead(401, { 'Content-Type': 'text/plain' })
        res.end(EXPIRED_MARKER)
        return
      }
      const endpoint = /"endpoint":"([^"]+)"/.exec(body)?.[1] ?? ''
      if (endpoint === 'update-check') {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: { current: '9.9.9', dsh: { current: '9.9.9' } } }))
        return
      }
      if (endpoint === 'state') {
        // since+live 全命中：按契约回最小 unchanged 形态（第一次带 work 变化档、之后纯三键）
        const since = /"since":(\d+)/.exec(body)?.[1]
        const live = /"live":"([^"]*)"/.exec(body)?.[1]
        if (since === '7' && live) {
          // 作答后（revision 推送重取）：继续带 work 键反映 approvals 清空（增量推送必须含变更）
          const answer = stateLiveProbeSent && !cardAnswered
            ? { ok: true, value: { revision: 7, unchanged: true, liveKey: 'mock-live' } }
            : { ok: true, value: { revision: 7, unchanged: true, liveKey: 'mock-live', work: { approvals: cardAnswered ? [] : [{ sessionId: 's-bot-1', botId: 'bot-1', title: '部署审批' }], bots: { 'bot-1': { turn: 2 } } } } }
          stateLiveProbeSent = true
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify(answer))
          return
        }
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({
          ok: true,
          value: {
            revision: 7, liveKey: 'mock-live', mainBotIds: ['bot-1'],
            bots: [
              { id: 'bot-1', name: '研究主管', color: 'blue', role: '研究', sessionId: 's-bot-1', pinned: true, preview: '关于市场数据的最新分析', unread: 3, parts: ['part-0', 'part-1'] },
              { id: 'bot-2', name: '运维值班', color: 'green', role: '运维', sessionId: 's-bot-2', preview: '磁盘告警已处理', parts: ['part-0', 'part-1', 'part-2'] },
            ],
            rooms: [{ id: 'room-1', name: '项目群', color: 'violet', members: ['bot-1', 'bot-2'], sessionId: 'room-1', preview: '讨论发布计划' }],
            sections: [{ id: 'sec-1', name: '常用', itemIds: ['bot-1'] }],
            questions: cardAnswered ? {} : { 's-bot-2': { question: '选哪个维护窗口？' } },
            work: { approvals: cardAnswered ? [] : [{ sessionId: 's-bot-1', botId: 'bot-1', title: '部署审批' }], bots: { 'bot-2': { turn: 1 } } },
          },
        }))
        return
      }
      if (endpoint === 'room-chat') {
        // 分页：首屏带 cursor，带 cursor 请求回更早一页（无 cursor=到最早）
        const hasCursor = /"cursor"/.test(body)
        res.writeHead(200, { 'Content-Type': 'application/json' })
        if (hasCursor) {
          res.end(JSON.stringify({
            ok: true,
            value: {
              roomId: 'room-1',
              running: ['bot-2'],
              speaking: [],
              items: [
                { kind: 'user', text: '上一页的群消息', turn: 0, seq: 0 },
                { kind: 'bot', botId: 'bot-1', name: '研究主管', color: 'blue', text: '上一页的回复', turn: 0, seq: 1 },
              ],
            },
          }))
          return
        }
        res.end(JSON.stringify({
          ok: true,
          value: {
            roomId: 'room-1',
            announcement: '每周一篇公众号',
            cursor: { part: 0, beforeSeq: 1 },
            running: ['bot-2'],
            speaking: [],
            items: [
              { kind: 'user', text: '群消息探针', turn: 1, seq: 1 },
              { kind: 'bot', botId: 'bot-1', name: '研究主管', color: 'blue', text: '收到，安排分工', turn: 1, seq: 2 },
              { kind: 'bot', botId: 'bot-2', name: '运维值班', color: 'green', text: '巡检进行中', turn: 1, seq: 3 },
              { kind: 'bot', botId: 'bot-1', name: '研究主管', color: 'blue', text: '成片好了：[clean 版](/home/wx766/.dsh/bot/workspace/deliverables/demo.mp4)（对比度 9.5:1）', turn: 1, seq: 4 },
              { kind: 'bot', botId: 'bot-2', name: '运维值班', color: 'green', text: '[demo 视频文件](/home/wx766/.dsh/bot/workspace/deliverables/demo.mp4)', turn: 1, seq: 5 },
              { kind: 'pass', botId: 'bot-1' },
              { kind: 'checkpoint', turn: 1 },
            ],
          },
        }))
        return
      }
      if (['stop-room-work', 'room-progress', 'goal-draft', 'goal-control'].includes(endpoint)) {
        if (!/"roomId"/.test(body)) {
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, error: { code: 'bot/invalid', message: 'roomId required' } }))
          return
        }
        if (endpoint === 'goal-draft' && !/"text"/.test(body)) {
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, error: { code: 'bot/invalid', message: 'Write what this group should achieve, in at most 2000 characters.' } }))
          return
        }
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: endpoint === 'room-progress' ? [] : true }))
        return
      }
      if (endpoint === 'chat') {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({
          ok: true,
          value: {
            sessionId: 's-bot-1',
            cursor: { part: 0, beforeSeq: 1 }, // 真实 0.3.x 的对象形态 cursor（earlier 用）
            items: [
              { kind: 'user', text: '分析一下市场', turn: 1, seq: 1 },
              { kind: 'bot', text: '## 市场概览\n- 需求稳定\n- 供给收紧\n\n```\n指数 3.2%\n```', turn: 1, seq: 2 },
              { kind: 'tool', call: { name: 'web_search' }, label: '搜索市场数据' },
              { kind: 'answer', callId: 'card-q-1', type: 'question', question: '选择发布窗口？', options: [{ label: '周六', description: '低峰' }, { label: '周日' }] },
              (btwDeleted.has('btw-1') ? { kind: 'checkpoint', turn: 0 } : { kind: 'event', role: 'btw', id: 'btw-1', from: '研究主管', text: '插话：这个数据源过期了' }),
              { kind: 'answer', callId: 'card-secret-1', type: 'secret', title: 'API key', hint: '至少 4 字符' },
              { kind: 'answer', callId: 'card-html-1', type: 'html', html: '<h3>巡检小结</h3><p style="color:#333">一切正常</p>' },
              // 附件引用条目（dsbot-attachments）：attachmentId 指向预置 1x1 PNG（启动时写入 uploads）
              { kind: 'image', image: { attachmentId: 'sha256:' + MOCK_PNG_SHA, mediaType: 'image/png', bytes: MOCK_PNG.length, width: 1, height: 1, name: 'mock.png' } },
              { kind: 'file', file: { attachmentId: 'sha256:' + MOCK_TXT_SHA, name: 'note.txt', bytes: MOCK_TXT.length } },
              { kind: 'checkpoint', turn: 1 },
            ],
          },
        }))
        return
      }
      if (endpoint === 'earlier') {
        // 更早一页（无 cursor = 到最早）；分段边界不可见。cursor 校验对象形态——
        // 字符串/缺失都回 bot/invalid（掩盖形态错误等于掩盖客户端拼接 bug）
        const cursorRaw = /"cursor":\s*(\{[^}]*\}|\S+?)[,}]/.exec(body)?.[1]
        if (!cursorRaw || !cursorRaw.trim().startsWith('{')) {
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, error: { code: 'bot/invalid', message: 'cursor must be an object' } }))
          return
        }
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({
          ok: true,
          value: {
            sessionId: 's-bot-1',
            items: [
              { kind: 'part-boundary' },
              { kind: 'user', text: '上周的结论还成立吗', turn: 0, seq: 0 },
              { kind: 'bot', text: '成立，且更明显了', turn: 0, seq: 1 },
            ],
          },
        }))
        return
      }
      // Side chat 端点族（dsbot-side-chat；形态 0.3.5 探针实证）
      if (['side-new', 'side-ask', 'side-cancel', 'side-close', 'btw-delete'].includes(endpoint)) {
        const sid = sessionCookie(req)
        if (!sid || !sessions.has(sid)) {
          res.writeHead(401, { 'Content-Type': 'text/plain' })
          res.end(EXPIRED_MARKER)
          return
        }
        if (endpoint === 'side-new') {
          if (!/"sessionId"/.test(body)) {
            res.writeHead(200, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify({ ok: false, error: { code: 'bot/invalid', message: 'Nothing to open' } }))
            return
          }
          const sideId = 'side-' + Math.random().toString(36).slice(2, 10)
          lastSideId = sideId
          sideSessions.set(sideId, [])
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: true, value: { chatId: sideId } }))
          return
        }
        if (endpoint === 'side-ask') {
          if (!/"text":"....*"/.test(body)) {
            res.writeHead(200, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify({ ok: false, error: { code: 'bot/invalid', message: 'Write a question.' } }))
            return
          }
          // 回答经 feed 推流式+消息（side sessionId 归属）
          setTimeout(() => {
            feedClients.forEach((write) => {
              const N = 10
              write('event: stream' + String.fromCharCode(10) + 'data: {"botId":"bot-1","sessionId":"' + lastSideId + '","attemptId":"sa1","phase":"start"}' + String.fromCharCode(10, 10))
              write('event: stream' + String.fromCharCode(10) + 'data: {"botId":"bot-1","sessionId":"' + lastSideId + '","attemptId":"sa1","text":"旁路回答：不影响主工作"}' + String.fromCharCode(10, 10))
              write('event: stream' + String.fromCharCode(10) + 'data: {"botId":"bot-1","sessionId":"' + lastSideId + '","attemptId":"sa1","phase":"end"}' + String.fromCharCode(10, 10))
              write('event: message' + String.fromCharCode(10) + 'data: {"botId":"bot-1","sessionId":"' + lastSideId + '","item":{"kind":"bot","text":"旁路回答：不影响主工作","seq":200}}' + String.fromCharCode(10, 10))
            })
          }, 300)
          // 应答回新 chatId（与真实 0.3.5 形态对齐——App 据此归属 feed 事件与轮询）
          const askChatId = 'side-ask-' + Math.random().toString(36).slice(2, 10)
          lastSideId = askChatId
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: true, value: { chatId: askChatId } }))
          return
        }
        if (endpoint === 'side-cancel' || endpoint === 'side-close') {
          if (!/"sideId"/.test(body)) {
            res.writeHead(200, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify({ ok: false, error: { code: 'bot/invalid', message: 'Nothing to ' + endpoint.split('-')[1] } }))
            return
          }
          const m = /"sideId":"([^"]+)"/.exec(body)
          if (m) lastSideId = m[1] // close 后保留 id 供取消路径测试
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: true, value: true }))
          return
        }
        // btw-delete（持久：记录已删 id，chat 应答过滤）
        const btwId = /"id":"([^"]+)"/.exec(body)?.[1]
        if (!btwId) {
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, error: { code: 'bot/invalid', message: 'Nothing to delete' } }))
          return
        }
        btwDeleted.add(btwId)
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: true }))
        return
      }
      // 卡片作答端点族（dsbot-interactive-cards；形态 0.3.5 探针实证）
      if (['approval-answer', 'answer-question', 'dismiss-question', 'view-reply', 'dismiss-view',
           'secret-set', 'secret-allow', 'secret-cancel', 'learning-answer', 'browser-give-back'].includes(endpoint)) {
        if (!/"callId"/.test(body)) {
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, error: { code: 'bot/invalid', message: 'callId required' } }))
          return
        }
        if (endpoint === 'secret-set') {
          const parsedBody = JSON.parse(body)
          const value = String(parsedBody.payload?.value ?? '')
          if (value.length === 0) {
            res.writeHead(200, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify({ ok: false, error: { code: 'bot/refused', message: 'Type the key first' } }))
            return
          }
          if (value.length < 4) {
            res.writeHead(200, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify({ ok: false, error: { code: 'bot/refused', message: 'A key must have at least 4 characters' } }))
            return
          }
        }
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: true }))
        // 作答成功：经 feed 推 revision（团队页「需要你」经 state 重取同步——cards-answer-sync）
        cardAnswered = true
        setTimeout(() => {
          feedClients.forEach((write) => {
            write('event: revision' + String.fromCharCode(10) + 'data: {"revision":9}' + String.fromCharCode(10, 10))
          })
        }, 150)
        return
      }
      // 显式登记的写端点：返回真实形态的最小值（拼写/形态错误落到兜底 bot/not-found，
      // 不再恒 ok——那是 fetchState "{{}}" 被 endpoint 正则掩盖的同种病根）
      if (['stop-member-work', 'resume', 'bot-new-part', 'bot-open-part'].includes(endpoint)) {
        if (!/"botId"/.test(body)) {
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, error: { code: 'bot/invalid', message: 'botId required' } }))
          return
        }
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: true }))
        return
      }
      if (endpoint === 'run-stats' || endpoint === 'exchange' || endpoint === 'exchange-text' || endpoint === 'file-text') {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: { note: 'mock placeholder' } }))
        return
      }
      if (endpoint === 'send') {
        // 发送后 3s 发 stream（start/text/end）与 message 定格（经 feed 推送）
        setTimeout(() => {
          feedClients.forEach((write) => {
            write(`event: stream
data: {"botId":"bot-1","sessionId":"s-bot-1","attemptId":"a1","phase":"start"}

`)
            write(`event: stream
data: {"botId":"bot-1","sessionId":"s-bot-1","attemptId":"a1","text":"好的"}

`)
            write(`event: stream
data: {"botId":"bot-1","sessionId":"s-bot-1","attemptId":"a1","text":"，马上分析"}

`)
            write(`event: stream
data: {"botId":"bot-1","sessionId":"s-bot-1","attemptId":"a1","phase":"end"}

`)
            write(`event: message
data: {"botId":"bot-1","sessionId":"s-bot-1","item":{"kind":"bot","text":"好的，马上分析","seq":99}}

`)
          })
        }, 3000)
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: true }))
        return
      }
      // 兜底：未登记端点一律 bot/not-found（不掩盖客户端拼写/形态错误）
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ ok: false, error: { code: 'bot/not-found', message: `Unknown endpoint: ${endpoint || '(none)'}` } }))
    })
    return
  }
  if (url.startsWith('/api/session/uploadFileBinary')) {
    const sid = sessionCookie(req)
    if (!sid || !sessions.has(sid)) {
      res.writeHead(401, { 'Content-Type': 'text/plain' })
      res.end(EXPIRED_MARKER)
      return
    }
    const qs = new URL(url, 'http://x').searchParams
    const sessionId = qs.get('sessionId') ?? ''
    const name = qs.get('name') ?? 'file'
    const mediaType = qs.get('mediaType')
    const chunks = []
    let total = 0
    let tooLarge = false
    req.on('data', (c) => { total += c.length; if (total > 1024 * 1024) tooLarge = true; else chunks.push(c) })
    req.on('end', () => {
      if (tooLarge) {
        // 超限显式拒绝（不静默截断——那会掩盖客户端字节流缺陷）
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: false, error: { code: 'session/attachment-invalid', message: 'mock: file exceeds 1MB limit', details: { reason: 'TOO_LARGE' } } }))
        return
      }
      const buf = Buffer.concat(chunks)
      const sha = createHash('sha256').update(buf).digest('hex')
      uploads.set(sessionId + ':' + sha, { data: buf, name })
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({
        ok: true,
        value: {
          receiptId: 'mock-receipt-' + sha.slice(0, 8),
          file: {
            attachmentId: 'sha256:' + sha,
            name,
            bytes: buf.length,
            ...(mediaType ? { mediaType } : {}),
          },
        },
      }))
    })
    return
  }
  if ((url === '/api/session/prompt' || url === '/api/session/attachment') && req.method === 'POST') {
    // HTTP unary RPC（0.3.5 client-request 信封；与真实服务器同构）
    const sid = sessionCookie(req)
    if (!sid || !sessions.has(sid)) {
      res.writeHead(401, { 'Content-Type': 'text/plain' })
      res.end(EXPIRED_MARKER)
      return
    }
    let rpcBody = ''
    req.on('data', (c) => { rpcBody += c })
    req.on('end', () => {
      let envelope
      try { envelope = JSON.parse(rpcBody) } catch { envelope = null }
      if (!envelope || envelope.type !== 'client-request') {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ type: 'server-response', rpcId: envelope?.rpcId ?? '', result: { ok: false, error: { code: 'gateway/bad-request', message: 'invalid client-request message' } } }))
        return
      }
      const request = envelope.payload?.args?.request
      let result
      if (url === '/api/session/prompt') {
        const blocks = Array.isArray(request?.content) ? request.content : []
        const files = blocks.filter((b) => b.type === 'file').map((b) => b.receiptId)
        const hasText = blocks.some((b) => b.type === 'text' && b.text)
        if (!request?.sessionId || (files.length === 0 && !hasText)) {
          result = { ok: false, error: { code: 'gateway/input-invalid', message: 'mock: sessionId 与 text/file 内容必填' } }
        } else {
          result = { ok: true, value: { accepted: true, receipts: files } }
          setTimeout(() => {
            feedClients.forEach((write) => {
              const sid = request.sessionId
              write('event: stream\ndata: {"botId":"bot-1","sessionId":"' + sid + '","attemptId":"a3","phase":"start"}\n\n')
              write('event: stream\ndata: {"botId":"bot-1","sessionId":"' + sid + '","attemptId":"a3","text":"收到"}\n\n')
              write('event: stream\ndata: {"botId":"bot-1","sessionId":"' + sid + '","attemptId":"a3","phase":"end"}\n\n')
              write('event: message\ndata: {"botId":"bot-1","sessionId":"' + sid + '","item":{"kind":"bot","name":"研究主管","color":"blue","text":"收到","seq":101}}\n\n')
            })
          }, 300)
        }
      } else {
        const ref = request?.attachment
        const stored = ref ? uploads.get(request.sessionId + ':' + String(ref.attachmentId ?? '').replace('sha256:', '')) : null
        result = stored
          ? { ok: true, value: { attachment: ref, data: stored.data.toString('base64') } }
          : { ok: false, error: { code: 'session/attachment-invalid', message: 'mock: attachment not found' } }
      }
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ type: 'server-response', rpcId: envelope.rpcId, result }))
    })
    return
  }
  if (url === '/api/bot/feed') {
    const sid = sessionCookie(req)
    if (!sid || !sessions.has(sid)) {
      res.writeHead(401, { 'Content-Type': 'text/plain' })
      res.end(EXPIRED_MARKER)
      return
    }
    feedClients.add(res.write.bind(res))
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Transfer-Encoding': 'chunked' })
    res.write(`event: hello
data: {"revision":7}

`)
    // 5s 后发一次 live 事件（触发 since+live 增量取），10s 后发 revision（全量取）
    setTimeout(() => res.write(`event: live
data: {}

`), 5000)
    setTimeout(() => res.write(`event: revision
data: {"revision":8}

`), 10000)
    const keep = setInterval(() => res.write(`: keepalive

`), 5000)
    req.on('close', () => { clearInterval(keep); feedClients.delete(res.write.bind(res)) })
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

server.on('upgrade', (req, socket) => {
  const upUrl = (req.url ?? '').split('?')[0]
  if (upUrl === '/api/remote.mux') {
    const sid = sessionCookie(req)
    if (!sid || !sessions.has(sid)) {
      socket.write('HTTP/1.1 401 Unauthorized' + String.fromCharCode(13, 10) + 'Connection: close' + String.fromCharCode(13, 10, 13, 10))
      socket.destroy()
      return
    }
    handleMuxUpgrade(req, socket)
    return
  }
  socket.write('HTTP/1.1 404 Not Found' + String.fromCharCode(13, 10) + 'Connection: close' + String.fromCharCode(13, 10, 13, 10))
  socket.destroy()
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
