// 模拟 dsh 服务端：仅本地验收用（android-main-webview 与 android-session-auth 模拟器验收）。
// 监听 127.0.0.1:13080，模拟器经 10.0.2.2:13080 访问宿主。
// 页面包含：流式 chunked 输出、同源链接、外部链接、window.open（同源/外部）、
// file: 打开按钮、跨主机 http 子资源引用。
// 用法：node scripts/mock-dsh-server.mjs [--port 13080] [--status <code>] [--delay <ms>] [--chat-bars] [--chat-cache] [--notices] [--outbox] [--dock] [--read-state] [--reader] [--rich] [--manage]
import { createServer } from 'node:http'
import { writeFileSync } from 'node:fs'
import { deflateSync } from 'node:zlib'
import { createManageTeam } from './mock-dsh-manage.mjs'

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
// --chat-bars：state 额外带 0.3.10 聊天条字段（dsbot-chat-bars-0310 模拟器验收；字段形态为
// ds-bot 0.3.10 源码核对，见 tech/dsbot-api 的 chat-bars-fields-0310），并处理对应写端点。
const chatBars = process.argv.includes('--chat-bars')
// --chat-cache：每个 Bot 的私聊为 40 条长列表、chat 读取延迟 1.5s（区分缓存上屏与网络读取），
// GET /mock/push?bot=<id>[&feed=1] 追加一条 Bot 消息（feed=1 时同时推 feed message 事件，
// 否则模拟实时推送遗漏、由兜底轮询补齐）（dsbot-chat-cache 模拟器验收）。
const chatCacheMode = process.argv.includes('--chat-cache')
const cacheChats = {}
function cacheChatOf(botId) {
  if (!cacheChats[botId]) {
    cacheChats[botId] = Array.from({ length: 40 }, (_, i) => ({ kind: i % 2 ? 'bot' : 'user', text: `${botId} 第 ${i + 1} 条消息`, turn: Math.floor(i / 2) + 1, seq: i + 1 }))
  }
  return cacheChats[botId]
}
// --notices：hello 声明 notices 能力、feed 首帧带 boot/seq、notices {boot, since} 补齐；
// GET /mock/notice?bot=&kind=&text=[&room=][&feed=0] 追加一条提醒（默认同时推 feed notice 事件），
// GET /mock/restart-boot 换 boot 并断开 feed（模拟 dsh 重启）（dsbot-notices-banner 模拟器验收）。
const noticesMode = process.argv.includes('--notices')
const noticeState = { boot: 'boot-' + Date.now(), seq: 0, list: [] }
const feedResponses = new Set()
// --outbox：GET /mock/fail-sends?on=1|0 切换发送失败（直接断开连接，客户端得到网络错误）；send 成功时
// 计数并在 300ms 后经 feed 推送对应的用户消息（kind=user、seq 递增，模拟服务端确认），GET /mock/sends
// 返回各文本收到的次数；上传的附件另存到 /tmp/dsbot-probe/last-upload.bin（dsbot-outbox-retry 验收）。
const outboxMode = process.argv.includes('--outbox')
const outboxState = { fail: false, userSeq: 1000, sends: {}, roomLines: [] }
// --dock：聊天同时含审批、提问、卡片、密钥与插话，state 的 live learning 带一条技能更新提案、connectorRequests
// 带一条待处理请求；作答端点记录 id 并从后续应答中移除（dsbot-pending-dock 模拟器验收）。
const dockMode = process.argv.includes('--dock')
// --read-state：团队条目都带 lastAt，覆盖预览各优先级（Main Bot、待回答、工作中、群里工作、交接包装预览、仅角色）；
// GET /mock/bump?id=<bot|room id>[&message=1] 把该条目 lastAt 前移到现在、预览换成新消息并经 feed 推 revision；
// message=1 时同时推该 Bot 的实时 message 事件（带 time，验收「打开着的聊天收到实时消息随之已读」）（dsbot-read-state）。
const readStateMode = process.argv.includes('--read-state')
// --reader：聊天里 Bot 交付文件（files[]：小 md 与 1.3 MB 二进制，file-download 按 512 KiB 分块、带 version）、
// 用户消息 imageRefs（三张图，image 端点 preview/原图）、群长帖 whole{length,file}（file-text）；
// hello 声明 image-preview（dsbot-reader-files 模拟器验收）。GET /mock/touch-file 改动大文件的 version（模拟下载中途变更）。
const readerMode = process.argv.includes('--reader')
// --rich：state.views 带一张含五种图表、滑块与公式、表格、选项与表单的卡片（s-bot-1）和一张畸形卡片（s-bot-2），
// view-reply 校验 viewId/action 后移除该卡片并推 revision；聊天含宽表格、任务列表、本地路径与 data 图片，
// markdown-image 对 /work/ 下路径返回 PNG、其余 bot/forbidden（dsbot-rich-render 模拟器验收）。
const richMode = process.argv.includes('--rich')
// --manage：可变的内存团队与 Bot/群/团队管理端点（dsbot-native-manage 里程碑模拟器验收，见 mock-dsh-manage.mjs）；
// 写入后经 feed 推 revision，日志只记端点与参数键。
const manageMode = process.argv.includes('--manage')
const manage = manageMode ? createManageTeam((revision) => setTimeout(() => feedClients.forEach((write) => write(`event: revision\ndata: ${JSON.stringify({ revision })}\n\n`)), 50), () => setTimeout(() => feedClients.forEach((write) => write(`event: live\ndata: {}\n\n`)), 50)) : null
const richClosed = new Set()
const richRevision = { n: 30 }
function richViews() {
  const views = {}
  if (!richClosed.has('s-bot-1')) views['s-bot-1'] = { id: 'view-1', title: '季度经营看板', view: { type: 'stack', let: { total: 'base * (1 + growth / 100)' }, children: [
    { type: 'text', text: '拖动增长率，看合计与图表变化。' },
    { type: 'slider', id: 'growth', label: '增长率', min: 0, max: 50, step: 5, value: 10, unit: '%' },
    { type: 'number', id: 'base', label: '基数', min: 0, step: 10, value: 100 },
    { type: 'row', children: [
      { type: 'metric', label: '合计', value: { '=': 'round(total, 1)' }, tone: 'good' },
      { type: 'metric', label: '占比', value: { '=': "percent(growth / 100, 0)" } },
    ] },
    { type: 'chart', kind: 'bar', categories: ['一季', '二季', '三季', '四季'], series: [{ name: '收入', values: [80, 95, { '=': 'total' }, 120] }, { name: '成本', values: [60, 70, 75, 90] }], unit: '万' },
    { type: 'chart', kind: 'line', categories: ['周一', '周二', '周三', '周四', '周五'], series: [{ name: '访问', values: [12, 18, 15, 22, { '=': 'growth' }] }] },
    { type: 'chart', kind: 'area', categories: ['1', '2', '3', '4'], series: [{ values: [3, 5, 4, 6] }] },
    { type: 'chart', kind: 'hbar', categories: ['北京', '上海', '深圳'], series: [{ values: [30, 45, 25] }] },
    { type: 'chart', kind: 'donut', categories: ['线上', '线下', '渠道'], series: [{ values: [50, 30, 20] }], unit: '%' },
    { type: 'progress', label: '目标完成', value: { '=': 'min(total, 150)' }, max: 150 },
    { type: 'callout', text: '三季度数据含预估。', tone: 'warn' },
    { type: 'table', columns: [{ key: 'q', label: '季度' }, { key: 'v', label: '收入（万）' }], rows: [['一季', 80], ['三季', { '=': 'round(total, 1)' }]] },
    { type: 'sources', items: [{ label: '经营周报', url: 'https://example.com/report' }] },
    { type: 'options', id: 'plan', options: [{ label: '按计划推进', description: '保持当前节奏' }, { label: '加大投入' }] },
    { type: 'form', id: 'memo', fields: [{ id: 'note', label: '备注', placeholder: '补充说明', multiline: true }], submitLabel: '提交备注' },
  ] } }
  if (!richClosed.has('s-bot-2')) views['s-bot-2'] = { id: 'view-2', title: '坏掉的图表', view: { type: 'chart', categories: ['a', 'b'], series: [{ values: [1, 2, 3] }] } }
  return views
}
const readerFiles = {
  's-bot-1#41#0': { name: '周报.md', mime: 'text/markdown', data: Buffer.from('# 周报\n\n' + Array.from({ length: 200 }, (_, i) => `第 ${i + 1} 行：本周进展与风险说明。`).join('\n'), 'utf8') },
  's-bot-1#41#1': { name: 'dataset.bin', mime: 'application/octet-stream', data: Buffer.alloc(1300 * 1024, 7) },
}
let readerVersion = 1
function readerPng(w, h, rgb) {
  const crc = (buf) => { let c = ~0; for (const b of buf) { c ^= b; for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1)) } return ~c >>> 0 }
  const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([len, td, c]) }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2
  const row = Buffer.concat([Buffer.from([0]), Buffer.alloc(w * 3).map((_, i) => rgb[i % 3])])
  const raw = Buffer.concat(Array.from({ length: h }, () => row))
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))])
}
const readerImages = { 'sha256:red': [220, 40, 40], 'sha256:green': [40, 180, 60], 'sha256:blue': [40, 80, 220] }
const readStateClock = { base: Date.now() - 3600_000, bumped: {}, previews: {}, revision: 20 }
function readStateValue() {
  const at = (id, offset) => readStateClock.bumped[id] ?? readStateClock.base + offset
  const value = {
    revision: readStateClock.revision, liveKey: 'rs-' + readStateClock.revision, mainBotIds: ['bot-1'],
    bots: [
      { id: 'bot-1', name: '研究主管', color: 'blue', role: '研究', sessionId: 's-bot-1', parts: ['part-0'], preview: '[Message from 用户] 早上好', lastAt: at('bot-1', 1000) },
      { id: 'bot-2', name: '运维值班', color: 'green', role: '运维', sessionId: 's-bot-2', parts: ['part-0'], preview: '磁盘告警已处理', lastAt: at('bot-2', 5000) },
      { id: 'bot-3', name: '采购助理', color: 'orange', role: '采购', sessionId: 's-bot-3', parts: ['part-0'], preview: '[Browser handoff] 需要登录供应商后台 — 登录后点完成\nAnswer: done', lastAt: at('bot-3', 4000) },
      { id: 'bot-4', name: '写作搭档', color: 'red', role: '写作', sessionId: 's-bot-4', parts: ['part-0'], lastAt: at('bot-4', 2000) },
      { id: 'bot-5', name: '数据分析', color: 'teal', role: '分析', sessionId: 's-bot-5', parts: ['part-0'], preview: '[Answer to question card] 用哪个口径？\nAnswer: 自然月', lastAt: at('bot-5', 3000) },
    ],
    rooms: [{ id: 'room-1', name: '项目群', color: 'violet', members: ['bot-2', 'bot-4'], sessionId: 'room-1', parts: ['g-1'], preview: '运维值班：发布窗口定在周六', lastAt: at('room-1', 6000) }],
    sections: [],
    questions: { 's-bot-1': { id: 'q-1', question: '选哪个发布窗口？', options: [{ label: '周六' }] } },
    work: { approvals: [], bots: { 'bot-2': { since: 1, tool: 'bash', target: 'df -h' } }, rooms: { 'room-1': [{ botId: 'bot-4', since: 1, tool: 'web_search' }] } },
  }
  for (const entry of [...value.bots, ...value.rooms]) if (readStateClock.previews[entry.id]) entry.preview = readStateClock.previews[entry.id]
  return value
}
const dockAnswered = new Set()
const bars = { revision: 7, stopped: new Set(), answered: new Set(), computerStopped: false, resumed: false }
function chatBarsState() {
  const tasks = [
    { id: 'task-1', title: '整理本周市场数据', status: 'running', sessionId: 't-1', startedAt: 1, updatedAt: 2, running: true },
    { id: 'task-2', title: '核对报价表', status: 'waiting_approval', sessionId: 't-2', startedAt: 1, updatedAt: 2, running: false },
    { id: 'task-0', title: '已完成任务（不应显示）', status: 'completed', sessionId: 't-0', startedAt: 1, updatedAt: 2, running: false },
  ].filter(t => !bars.stopped.has(t.id))
  const asks = {}
  if (!bars.answered.has('ask-1')) asks['ask-1'] = { id: 'ask-1', botId: 'bot-1', sessionId: 's-bot-1', appName: 'Notes', at: 10 }
  if (!bars.answered.has('ask-2')) asks['ask-2'] = { id: 'ask-2', botId: 'bot-2', sessionId: 'room-1', roomId: 'room-1', appName: 'Calendar', at: 11 }
  return {
    revision: bars.revision, liveKey: 'bars-' + bars.revision, mainBotIds: ['bot-1'],
    bots: [
      { id: 'bot-1', name: '研究主管', color: 'blue', role: '研究', sessionId: 's-bot-1', parts: ['part-0'], tasks },
      { id: 'bot-2', name: '运维值班', color: 'green', role: '运维', sessionId: 's-bot-2', parts: ['part-0'],
        ...(bars.resumed ? {} : { blocked: { code: 'QUOTA', provider: 'deepseek-official', status: 402, at: 5 } }) },
    ],
    rooms: [{ id: 'room-1', name: '项目群', color: 'violet', members: ['bot-1', 'bot-2'], sessionId: 'room-1',
      goal: { id: 'goal-1', status: 'active', title: '发布 2.0 版本', current: 1, stages: [
        { id: 's1', title: '需求确认', status: 'passed' }, { id: 's2', title: '实现与联调', status: 'active' },
        { id: 's3', title: '测试', status: 'pending' }, { id: 's4', title: '发布', status: 'pending' }] } }],
    sections: [],
    work: { bots: { 'bot-1': { since: 1, tool: 'web_search', target: 'example.com' } }, agents: {},
      rooms: { 'room-1': [{ botId: 'bot-2', since: 1, tool: 'bash', said: '正在跑回归测试。' }] }, approvals: [] },
    computerLive: { bots: bars.computerStopped ? {} : { 'bot-1': { state: 'waiting', app: 'Notes', steps: 1 } }, leases: [], cursors: [], asks, holds: {} },
  }
}

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
    req.on('end', async () => {
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
      if (manageMode) {
        let payload = {}
        try { payload = JSON.parse(body).payload ?? {} } catch {}
        const out = await manage.handle(endpoint, payload)
        if (out) {
          if (endpoint !== 'state' && endpoint !== 'chat') console.log('  manage:', endpoint, Object.keys(payload).join(','), out.ok ? 'ok' : 'refused: ' + out.error.message)
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify(out))
          return
        }
      }
      if (outboxMode && endpoint === 'state') {
        // 发送箱验收：Bot 都空闲（输入区显示发送按钮而不是停止按钮）
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: {
          revision: 7, liveKey: 'outbox-live', mainBotIds: ['bot-1'],
          bots: [
            { id: 'bot-1', name: '研究主管', color: 'blue', role: '研究', sessionId: 's-bot-1', parts: ['part-0'] },
            { id: 'bot-2', name: '运维值班', color: 'green', role: '运维', sessionId: 's-bot-2', parts: ['part-0'] },
          ],
          rooms: [{ id: 'room-1', name: '项目群', color: 'violet', members: ['bot-1', 'bot-2'], sessionId: 'room-1' }],
          sections: [], work: { bots: {}, rooms: {}, approvals: [] },
        } }))
        return
      }
      if (readStateMode && endpoint === 'state') {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: readStateValue() }))
        return
      }
      if (readStateMode && endpoint === 'chat') {
        const botId = /"botId":"([^"]*)"/.exec(body)?.[1] ?? 'bot-1'
        const last = readStateValue().bots.find(b => b.id === botId)?.lastAt ?? Date.now()
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: { sessionId: 's-' + botId, items: [
          { kind: 'user', text: '最近怎么样？', turn: 1, seq: 1, time: last - 1000 },
          { kind: 'bot', text: '一切正常。', turn: 1, seq: 2, time: readStateClock.bumped[botId] ? last - 500 : last },
          // bump 过的条目：聊天里有对应的新消息（与 state 的 lastAt/preview 一致，重取时不丢实时消息）
          ...(readStateClock.bumped[botId] ? [{ kind: 'bot', text: readStateClock.previews[botId], turn: 2, seq: 1000 + readStateClock.revision, time: last }] : []),
        ] } }))
        return
      }
      if (dockMode && endpoint === 'state') {
        // 0.3.10 形态：待处理项都在 state（questions/views 按会话 id，work.approvals、secretRequests、connectorRequests、
        // learning、browserHolds），已作答的移除
        const has = (id) => !dockAnswered.has(id)
        const value = {
          revision: 7, liveKey: 'dock-' + dockAnswered.size, mainBotIds: ['bot-1'],
          bots: [{ id: 'bot-1', name: '研究主管', color: 'blue', sessionId: 's-bot-1', parts: ['part-0'] }, { id: 'bot-2', name: '运维值班', color: 'green', sessionId: 's-bot-2', parts: ['part-0'] }],
          rooms: [], sections: [],
          questions: has('q-1') ? { 's-bot-1': { id: 'q-1', question: '选择发布窗口？', options: [{ label: '周六', description: '低峰' }, { label: '周日' }], multiSelect: false, askedAt: 1 } } : {},
          views: has('v-1') ? { 's-bot-1': { id: 'v-1', title: '巡检表', view: { blocks: [] }, askedAt: 1 } } : {},
          work: { bots: {}, rooms: {}, approvals: has('ap-1') ? [{ id: 'ap-1', sessionId: 's-bot-1', botId: 'bot-1', tool: 'bash', reason: 'kubectl apply -f prod.yaml', at: 1, answerable: true }] : [] },
          secretRequests: { 'bot-1': has('sr-1') ? [{ id: 'sr-1', sessionId: 's-bot-1', name: 'OPENAI_KEY', purpose: '调用模型', kind: 'fill', status: 'pending', askedAt: 1 }] : [] },
          connectorRequests: { 'bot-1': has('cr-1') ? [{ id: 'cr-1', connectorId: 'gmail', name: 'Gmail', purpose: '读取客户来信', status: 'pending', sessionId: 's-bot-1', askedAt: 1 }] : [] },
          learning: { 'bot-1': has('lp-1') ? [{ id: 'lp-1', kind: 'skill', action: 'update', botId: 'bot-1', name: '周报整理', description: '整理每周数据', why: '最近三次都手动整理周报', content: '1. 拉取本周数据\n2. 按项目分组\n3. 生成图表\n4. 写结论\n5. 发群里', previous: { description: '整理每周数据', content: '1. 拉取本周数据\n2. 生成图表\n3. 写结论\n' } }] : [] },
          browserHolds: has('handoff:bot-1') ? { 'bot-1': { by: 'bot', reason: '需要登录', note: '登录供应商后台后点完成', callId: 'c-9', sessionId: 's-bot-1' } } : {},
        }
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value }))
        return
      }
      if (dockMode && endpoint === 'chat') {
        // 聊天里的 kind=answer 是用户已经给出的回复记录（只读）
        const items = [
          { kind: 'user', text: '帮我准备上线', turn: 1, seq: 1 },
          { kind: 'answer', questions: [{ id: 'q-0', question: '先做哪个环境？', options: [{ label: '测试' }, { label: '生产' }] }], answers: [{ id: 'q-0', selected: ['测试'] }], time: 2 },
          { kind: 'bot', text: '好的，测试环境已就绪。', turn: 2, seq: 3 },
        ]
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: { sessionId: 's-bot-1', items } }))
        return
      }
      if (dockMode && ['approval-answer', 'answer-question', 'dismiss-question', 'view-reply', 'dismiss-view', 'secret-set', 'secret-allow', 'secret-cancel', 'learning-answer', 'connector-request-cancel', 'browser-give-back'].includes(endpoint)) {
        const p = JSON.parse(body).payload ?? {}
        const id = endpoint === 'answer-question' ? p.questionId
          : endpoint === 'dismiss-question' ? 'q-1'
          : endpoint === 'dismiss-view' ? 'v-1'
          : endpoint === 'browser-give-back' ? 'handoff:' + p.botId
          : (p.requestId ?? p.id)
        dockAnswered.add(String(id))
        // 不打印密钥值等正文：只记端点、id 与参数键
        console.log('  dock answer:', endpoint, id, Object.keys(p).join(','), endpoint === 'approval-answer' ? p.choice : endpoint === 'answer-question' ? JSON.stringify(p.answer) : '')
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: true }))
        return
      }
      if (richMode && endpoint === 'state') {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: {
          revision: richRevision.n, liveKey: 'rich-' + richRevision.n, mainBotIds: ['bot-1'],
          bots: [{ id: 'bot-1', name: '研究主管', color: 'blue', sessionId: 's-bot-1', parts: ['part-0'] }, { id: 'bot-2', name: '运维值班', color: 'green', sessionId: 's-bot-2', parts: ['part-0'] }],
          rooms: [], sections: [], views: richViews(), work: { approvals: [], bots: {}, rooms: {} },
        } }))
        return
      }
      if (richMode && endpoint === 'view-reply') {
        const p = JSON.parse(body).payload
        const v = richViews()[p.sessionId]
        res.writeHead(200, { 'Content-Type': 'application/json' })
        if (!v || v.id !== p.viewId) { res.end(JSON.stringify({ ok: false, error: { code: 'bot/stale', message: 'This view is no longer open', details: {} } })); return }
        // 与 validateViewAnswer 同口径的最小校验：action 必须是作答组件 id，slider/number 输入在范围内
        const nodes = []
        const walk = (n) => { if (n && typeof n === 'object') { nodes.push(n); for (const c of n.children ?? []) walk(c) } }
        walk(v.view)
        const invalid = (message) => { console.log('  view-reply rejected:', message); res.end(JSON.stringify({ ok: false, error: { code: 'bot/invalid', message, details: {} } })) }
        if (!nodes.some(n => ['options', 'confirm', 'form', 'submit'].includes(n.type) && n.id === p.action)) return invalid('action: no interactive component with id ' + p.action)
        for (const n of nodes.filter(n => n.type === 'slider' || n.type === 'number')) {
          const val = p.values?.inputs?.[n.id]
          if (val === undefined) continue
          if (typeof val !== 'number' || (n.min !== undefined && val < n.min) || (n.max !== undefined && val > n.max)) return invalid(`values.inputs.${n.id}: expected a number from ${n.min ?? '-∞'} to ${n.max ?? '∞'}`)
        }
        console.log('  view-reply', p.sessionId, 'action', p.action, 'values', JSON.stringify(p.values))
        richClosed.add(p.sessionId); richRevision.n += 1
        res.end(JSON.stringify({ ok: true, value: true }))
        setTimeout(() => feedClients.forEach((write) => write(`event: revision\ndata: ${JSON.stringify({ revision: richRevision.n })}\n\n`)), 100)
        return
      }
      if (richMode && endpoint === 'dismiss-view') {
        const p = JSON.parse(body).payload
        console.log('  dismiss-view', p.sessionId)
        richClosed.add(p.sessionId); richRevision.n += 1
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: true }))
        setTimeout(() => feedClients.forEach((write) => write(`event: revision\ndata: ${JSON.stringify({ revision: richRevision.n })}\n\n`)), 100)
        return
      }
      if (richMode && endpoint === 'chat') {
        const dot = readerPng(32, 32, [240, 160, 0]).toString('base64')
        const wide = '| 项目 | 负责人 | 开始 | 结束 | 状态 | 预算 | 风险 | 备注 |\n|---|---|---|---|---|---|---|---|\n' +
          ['官网改版', '数据平台', '移动端'].map((n, i) => `| ${n} | 张${i} | 2026-0${i + 1}-01 | 2026-0${i + 4}-30 | 进行中 | ${(i + 1) * 120} 万 | 中 | 第 ${i + 1} 期说明较长需要横向滚动 |`).join('\n')
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: { sessionId: 's-' + (/"botId":"([^"]*)"/.exec(body)?.[1] ?? 'bot-1'), items: [
          { kind: 'bot', text: '项目总览：\n' + wide, time: 1, seq: 1, turn: 1 },
          { kind: 'bot', text: '本周任务：\n- [x] 完成需求评审\n- [ ] 上线灰度\n- [ ] 复盘', time: 2, seq: 2, turn: 1 },
          { kind: 'bot', text: '截图如下：\n![截图](/work/shot.png)\n![外部截图](/etc/secret.png)\n![内联](data:image/png;base64,' + dot + ')', time: 3, seq: 3, turn: 1 },
        ] } }))
        return
      }
      if (richMode && endpoint === 'markdown-image') {
        const p = JSON.parse(body).payload
        res.writeHead(200, { 'Content-Type': 'application/json' })
        if (!String(p.path).startsWith('/work/')) { res.end(JSON.stringify({ ok: false, error: { code: 'bot/forbidden', message: 'Image outside workspace', details: {} } })); return }
        const png = readerPng(320, 180, [30, 140, 200])
        const rev = `${png.length}:0123456789abcdef`
        console.log('  markdown-image', p.path, p.knownRev ? 'knownRev' : '')
        res.end(JSON.stringify({ ok: true, value: p.knownRev === rev ? { mime: 'image/png', unchanged: true, size: png.length, rev } : { mime: 'image/png', bytes: png.toString('base64'), size: png.length, rev } }))
        return
      }
      if (readerMode && endpoint === 'hello') {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: { api: 1, apiMin: 1, apiMax: 1, plugin: { name: 'ds-bot', version: '0.3.10' }, capabilities: ['state', 'chat', 'room-chat', 'feed', 'image-preview', 'file-download', 'file-download-chunks'], boot: 'reader', seq: 0, revision: 7 } }))
        return
      }
      if (readerMode && endpoint === 'chat') {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: { sessionId: 's-bot-1', items: [
          { kind: 'user', text: '看看这几张图', time: 1, seq: 30, turn: 1, images: 3, imageRefs: Object.keys(readerImages).map((id, i) => ({ attachmentId: id, mime: 'image/png', name: `图${i + 1}.png` })) },
          { kind: 'bot', text: '这是一条很长的回复。' + '每一段都在讨论细节，长按可以选择文字。'.repeat(30) + '\n\n参考 [官网](https://example.com)。', time: 2, seq: 40, turn: 1 },
          { kind: 'bot', text: '交付了两个文件。', time: 3, seq: 42, turn: 1, sessionId: 's-bot-1', files: [{ name: '周报.md', sessionId: 's-bot-1', seq: 41, index: 0 }, { name: 'dataset.bin', sessionId: 's-bot-1', seq: 41, index: 1 }] },
        ] } }))
        return
      }
      if (readerMode && endpoint === 'room-chat') {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: { roomId: 'room-1', running: [], speaking: [], items: [
          { id: '1', kind: 'user', text: '大家汇报一下', time: 1 },
          { id: '2', kind: 'bot', botId: 'bot-1', name: '研究主管', color: 'blue', text: '[A long message: 9000 characters. Its start is below; all of it is in the attached file.]\n本周完成了……', time: 2, whole: { length: 9000, file: { attachmentId: 'att-long-1', name: 'long-post.txt' } } },
        ] } }))
        return
      }
      if (readerMode && endpoint === 'file-text') {
        const ok = /"attachmentId":"att-long-1"/.test(body)
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify(ok ? { ok: true, value: { text: '本周完成了全部里程碑。\n' + '详细说明：每一项任务都按计划推进。\n'.repeat(300), whole: true } } : { ok: false, error: { code: 'bot/unavailable', message: 'This file cannot be read here', details: {} } }))
        return
      }
      if (readerMode && endpoint === 'file-download') {
        const p = JSON.parse(body).payload
        const f = readerFiles[`${p.sessionId}#${p.seq}#${p.index}`]
        const version = `1:2:${f?.data.length}:${readerVersion}`
        res.writeHead(200, { 'Content-Type': 'application/json' })
        if (!f || (p.version !== undefined && p.version !== version) || p.offset > f.data.length) {
          res.end(JSON.stringify({ ok: false, error: { code: 'bot/not-found', message: 'This file is no longer available', details: {} } }))
          return
        }
        const part = f.data.subarray(p.offset, p.offset + 512 * 1024)
        console.log('  file-download', p.seq, p.index, 'offset', p.offset, 'bytes', part.length)
        res.end(JSON.stringify({ ok: true, value: { name: f.name, mime: f.mime, bytes: part.toString('base64'), size: f.data.length, version, nextOffset: p.offset + part.length, done: p.offset + part.length === f.data.length } }))
        return
      }
      if (readerMode && endpoint === 'image') {
        const p = JSON.parse(body).payload
        const rgb = readerImages[p.attachmentId]
        res.writeHead(200, { 'Content-Type': 'application/json' })
        if (!rgb) { res.end(JSON.stringify({ ok: false, error: { code: 'bot/not-found', message: 'Unknown attachment', details: {} } })); return }
        const png = p.preview ? readerPng(24, 24, rgb.map(c => Math.floor(c / 2))) : readerPng(480, 360, rgb)
        console.log('  image', p.attachmentId, p.preview ? 'preview' : 'original')
        setTimeout(() => res.end(JSON.stringify({ ok: true, value: { mime: 'image/png', data: png.toString('base64'), ...(p.preview ? { preview: true } : {}) } })), p.preview ? 0 : 1200)
        return
      }
      if (noticesMode && endpoint === 'hello') {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: { api: 1, apiMin: 1, apiMax: 1, plugin: { name: 'ds-bot', version: '0.3.10' }, capabilities: ['state', 'chat', 'feed', 'notices'], boot: noticeState.boot, seq: noticeState.seq, revision: 7 } }))
        return
      }
      if (noticesMode && endpoint === 'notices') {
        const boot = /"boot":"([^"]*)"/.exec(body)?.[1]
        const since = Number(/"since":(\d+)/.exec(body)?.[1] ?? 0)
        const fresh = boot !== noticeState.boot
        console.log('  notices fill: since', since, fresh ? '(reset: foreign boot)' : '')
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: { boot: noticeState.boot, seq: noticeState.seq, reset: fresh, notices: noticeState.list.filter(n => n.seq > (fresh ? 0 : since)) } }))
        return
      }
      if (chatBars && endpoint === 'state') {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: chatBarsState() }))
        return
      }
      if (chatBars && ['bot-stop-task', 'computer-app-answer', 'computer-stop', 'resume'].includes(endpoint)) {
        console.log('  chat-bars write:', endpoint, body)
        if (endpoint === 'bot-stop-task') bars.stopped.add(/"taskId":"([^"]+)"/.exec(body)?.[1])
        if (endpoint === 'computer-app-answer') bars.answered.add(/"id":"([^"]+)"/.exec(body)?.[1])
        if (endpoint === 'computer-stop') bars.computerStopped = true
        if (endpoint === 'resume') bars.resumed = true
        bars.revision++
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: true }))
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
      if (outboxMode && endpoint === 'room-chat' && !/"cursor"/.test(body)) {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: { roomId: 'room-1', running: [], speaking: [], items: [
          { id: 'b1', kind: 'bot', botId: 'bot-1', name: '研究主管', color: 'blue', text: '收到，安排分工', time: 1700000000000 },
          { id: 'u-old', kind: 'user', text: 'kaigong', time: 1700000001000 },
          ...outboxState.roomLines,
        ] } }))
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
      if (chatCacheMode && endpoint === 'chat') {
        const botId = /"botId":"([^"]+)"/.exec(body)?.[1] ?? 'bot-1'
        setTimeout(() => {
          // 首页只给最新 20 条并带游标（新消息让窗口前移），更早的经 earlier 翻出
          const list = cacheChatOf(botId)
          const page = list.slice(-20)
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: true, value: { sessionId: 's-' + botId, items: page, cursor: { part: 0, beforeSeq: page[0].seq } } }))
        }, 1500)
        return
      }
      if (chatCacheMode && endpoint === 'earlier') {
        const botId = (/"sessionId":"s-([^"]+)"/.exec(body)?.[1]) ?? 'bot-1'
        const before = Number(/"beforeSeq":(\d+)/.exec(body)?.[1] ?? 0)
        const older = cacheChatOf(botId).filter(m => m.seq < before)
        const page = older.slice(-20)
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: { items: page, ...(older.length > 20 ? { cursor: { part: 0, beforeSeq: page[0].seq } } : {}) } }))
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
      if (outboxMode && endpoint === 'send') {
        if (outboxState.fail) {
          console.log('  outbox: send dropped (fail mode)')
          res.destroy()
          return
        }
        const text = JSON.parse(body).payload?.text ?? ''
        outboxState.sends[text] = (outboxState.sends[text] ?? 0) + 1
        console.log('  outbox: send accepted, count', outboxState.sends[text])
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, value: true }))
        const item = { kind: 'user', text, time: Date.now(), seq: ++outboxState.userSeq }
        setTimeout(() => feedClients.forEach((write) => write(`event: message\ndata: ${JSON.stringify({ botId: 'bot-1', sessionId: 's-bot-1', item })}\n\n`)), 300)
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
      if (outboxMode) {
        writeFileSync('/tmp/dsbot-probe/last-upload.bin', buf)
        console.log('  outbox: upload', name, buf.length, 'bytes')
      }
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
      if (outboxMode && url === '/api/session/prompt') {
        // 发送箱验收：同样响应失败开关；私聊推 feed 用户行（seq），群聊把用户行记入 room-chat（只带 time，与真实同构）
        if (outboxState.fail) {
          console.log('  outbox: prompt dropped (fail mode)')
          res.destroy()
          return
        }
        const text = (request?.content ?? []).filter((b) => b.type === 'text').map((b) => b.text).join('')
        const sid = request?.sessionId ?? ''
        outboxState.sends[text] = (outboxState.sends[text] ?? 0) + 1
        console.log('  outbox: prompt accepted', sid, 'count', outboxState.sends[text])
        if (sid.startsWith('room-')) {
          outboxState.roomLines.push({ id: 'u' + outboxState.roomLines.length, kind: 'user', text, time: Date.now() })
        } else {
          const item = { kind: 'user', text, time: Date.now(), seq: ++outboxState.userSeq }
          setTimeout(() => feedClients.forEach((write) => write(`event: message\ndata: ${JSON.stringify({ botId: 'bot-1', sessionId: sid, item })}\n\n`)), 300)
        }
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ type: 'server-response', rpcId: envelope.rpcId, result: { ok: true, value: { accepted: true } } }))
        return
      }
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
    const writeFeed = res.write.bind(res)
    feedClients.add(writeFeed)
    feedResponses.add(res)
    res.on('close', () => { feedClients.delete(writeFeed); feedResponses.delete(res) })
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Transfer-Encoding': 'chunked' })
    res.write(noticesMode
      ? `event: hello\ndata: ${JSON.stringify({ revision: 7, api: 1, boot: noticeState.boot, seq: noticeState.seq })}\n\n`
      : `event: hello
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
  if (readerMode && url === '/mock/touch-file') {
    readerVersion += 1
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    res.end('version ' + readerVersion)
    return
  }
  if (manage && url.startsWith('/mock/browser-stats')) {
    const b = manage.browser
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({
      mode: b.mode,
      openBots: Object.keys(b.openBots),
      holds: b.holds,
      frameCalls: b.frameCalls,
      frameSeq: b.frameSeq,
      stopCalls: b.stopCalls,
      lastGiveBack: b.lastGiveBack,
    }))
    return
  }
  if (manage && url.startsWith('/mock/capabilities')) {
    const q = new URLSearchParams(url.split('?')[1] ?? '')
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(manage.setCapabilities(q)))
    return
  }
  if (manage && url.startsWith('/mock/browser')) {
    const q = new URLSearchParams(url.split('?')[1] ?? '')
    const state = manage.setBrowser(q)
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(state))
    return
  }
  if (readStateMode && url.startsWith('/mock/bump')) {
    const q = new URLSearchParams(url.split('?')[1] ?? '')
    const id = q.get('id') ?? 'bot-4'
    const time = Date.now()
    readStateClock.bumped[id] = time
    readStateClock.previews[id] = '新消息 ' + new Date(time).toISOString().slice(11, 19)
    readStateClock.revision += 1
    if (q.get('message') === '1') {
      const item = { kind: 'bot', text: readStateClock.previews[id], seq: 1000 + readStateClock.revision, time }
      feedClients.forEach((write) => write(`event: message\ndata: ${JSON.stringify({ botId: id, sessionId: 's-' + id, item })}\n\n`))
    }
    feedClients.forEach((write) => write(`event: revision\ndata: ${JSON.stringify({ revision: readStateClock.revision })}\n\n`))
    console.log('  bump:', id, readStateClock.bumped[id])
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    res.end('bumped ' + id)
    return
  }
  if (outboxMode && url.startsWith('/mock/fail-sends')) {
    outboxState.fail = new URLSearchParams(url.split('?')[1] ?? '').get('on') === '1'
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    res.end('fail=' + outboxState.fail)
    return
  }
  if (outboxMode && url === '/mock/sends') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(outboxState.sends))
    return
  }
  if (noticesMode && url.startsWith('/mock/notice')) {
    const q = new URLSearchParams(url.split('?')[1] ?? '')
    const botId = q.get('bot') ?? 'bot-2'
    const notice = { seq: ++noticeState.seq, time: Date.now(), botId, name: botId === 'bot-1' ? '研究主管' : '运维值班', kind: q.get('kind') ?? 'question', sessionId: 's-' + botId, ...(q.get('room') ? { roomId: q.get('room'), title: '项目群' } : {}), text: q.get('text') ?? '要选哪个维护窗口？' }
    noticeState.list.push(notice)
    if (q.get('feed') !== '0') feedClients.forEach((write) => write(`event: notice\ndata: ${JSON.stringify(notice)}\n\n`))
    console.log('  notice:', notice.seq, notice.kind, botId, q.get('feed') === '0' ? '(no feed)' : '')
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    res.end('notice ' + notice.seq)
    return
  }
  if (noticesMode && url === '/mock/restart-boot') {
    noticeState.boot = 'boot-' + Date.now()
    noticeState.seq = 0
    noticeState.list = []
    for (const r of feedResponses) r.destroy()
    console.log('  restart-boot ->', noticeState.boot)
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    res.end('restarted')
    return
  }
  if (noticesMode && url === '/mock/drop-feed') {
    for (const r of feedResponses) r.destroy()
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    res.end('dropped')
    return
  }
  if (chatCacheMode && url.startsWith('/mock/push')) {
    const q = new URLSearchParams(url.split('?')[1] ?? '')
    const botId = q.get('bot') ?? 'bot-1'
    const list = cacheChatOf(botId)
    const item = { kind: 'bot', text: `${botId} 新消息 ${list.length + 1}`, turn: 99, seq: list.length + 1 }
    list.push(item)
    if (q.get('feed') === '1') {
      feedClients.forEach((write) => write(`event: message\ndata: ${JSON.stringify({ botId, sessionId: 's-' + botId, item })}\n\n`))
    }
    console.log('  chat-cache push:', botId, q.get('feed') === '1' ? 'with feed' : 'no feed')
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    res.end('pushed')
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
