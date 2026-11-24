// mock-dsh-server --manage 的 IM 接入（dsbot-im-bridges 模拟器验收）：按 ds-bot 0.3.10 im/index.js（viewOf、imView、
// imStatusView、endpoints）与 im/platforms/weixin.js（loginStart/loginWait）源码核对的形态，用内存数据模拟。
// 调用记录只记端点与参数键，不记 token、Secret 等值。
import { randomBytes } from 'node:crypto'

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const PLATFORMS = ['telegram', 'weixin', 'feishu', 'dingtalk', 'wecom', 'qq', 'discord', 'slack']
const FIELDS = {
  telegram: [{ key: 'token', secret: true }],
  feishu: [{ key: 'domain', options: ['feishu', 'lark'] }, { key: 'appId' }, { key: 'appSecret', secret: true }],
  dingtalk: [{ key: 'clientId' }, { key: 'clientSecret', secret: true }],
  wecom: [{ key: 'botId' }, { key: 'secret', secret: true }],
  qq: [{ key: 'appId' }, { key: 'appSecret', secret: true }],
  discord: [{ key: 'token', secret: true }],
  slack: [{ key: 'appToken', secret: true }, { key: 'botToken', secret: true }],
}

export function createIm({ bump, botExists, roomExists }) {
  let enabled = true
  const calls = []
  let connections = {}
  let statuses = {}
  const logins = new Map() // loginId → { polls, expired }

  function reset() {
    // Telegram：已连接，一个待允许请求、一个已配对账号、私聊与绑定到项目群的群聊、一个未绑定群聊；
    // 飞书：App Secret 无效（出错，可重试成功）
    connections = {
      im_tg01: {
        id: 'im_tg01', platform: 'telegram', account: { id: '1001', name: '@ds_mock_bot' }, botId: 'bot-1', settings: {}, secrets: ['token'], enabled: true,
        paired: [{ id: 'u-alice', name: 'Alice', at: 1 }],
        requests: [{ id: 'u-bob', name: 'Bob', chatKey: 'u-bob', chatKind: 'direct', at: 2 }],
        chats: { 'u-alice': { kind: 'direct', title: 'Alice', at: 1 }, 'g-ops': { kind: 'group', title: '运维值班群', target: { roomId: 'room-1' }, at: 3 }, 'g-new': { kind: 'group', title: '新建测试群', at: 4 } },
        createdAt: 1,
      },
      im_fs02: {
        id: 'im_fs02', platform: 'feishu', account: null, botId: 'bot-2', settings: { domain: 'feishu', appId: 'cli_mock' }, secrets: ['appSecret'], enabled: true,
        paired: [], requests: [], chats: {}, createdAt: 2,
      },
    }
    statuses = { im_tg01: { state: 'ready', at: Date.now() }, im_fs02: { state: 'error', error: 'app secret invalid (99991663)', at: Date.now() } }
  }
  reset()

  const view = () => (enabled ? { im: { connections: Object.values(connections), platforms: PLATFORMS }, imStatus: statuses } : {})
  const conn = (id) => {
    const c = connections[String(id ?? '')]
    if (!c) throw new Error('No such IM bot')
    return c
  }

  const endpoints = {
    async 'im-add'(p) {
      if (!PLATFORMS.includes(p.platform)) throw new Error('This platform is not available yet')
      if (!botExists(p.botId)) throw new Error('Choose a Bot first')
      if (p.platform === 'weixin') throw new Error('This platform signs in by scanning a code; not available yet')
      const fields = p.fields ?? {}
      for (const f of FIELDS[p.platform]) if (!f.options && String(fields[f.key] ?? '').trim() === '') throw new Error(`${f.key} is required`)
      await sleep(1200) // 上游先向平台校验账号
      if (Object.values(fields).some((v) => String(v).trim() === 'bad')) throw new Error('Telegram did not accept this token (401 Unauthorized)')
      const id = 'im_' + randomBytes(4).toString('hex')
      const settings = Object.fromEntries(FIELDS[p.platform].filter((f) => !f.secret).map((f) => [f.key, f.options ? (f.options.includes(fields[f.key]) ? fields[f.key] : f.options[0]) : String(fields[f.key]).trim()]))
      connections[id] = { id, platform: p.platform, account: { id: '9', name: p.platform === 'telegram' ? '@new_mock_bot' : `${p.platform}-bot` }, botId: p.botId, settings, secrets: FIELDS[p.platform].filter((f) => f.secret).map((f) => f.key), enabled: true, paired: [], requests: [], chats: {}, createdAt: Date.now() }
      statuses[id] = { state: 'ready', at: Date.now() }
      return connections[id]
    },
    async 'im-update'(p) {
      const c = conn(p.id)
      if (p.botId !== undefined) { if (!botExists(p.botId)) throw new Error('Choose a Bot first'); c.botId = p.botId }
      if (p.enabled !== undefined) c.enabled = p.enabled === true
      return c
    },
    async 'im-remove'(p) {
      conn(p.id)
      delete connections[p.id]
      delete statuses[p.id]
      return true
    },
    async 'im-pair'(p) {
      const c = conn(p.id)
      const at = c.requests.findIndex((r) => r.id === String(p.senderId ?? ''))
      if (at === -1) throw new Error('This request is gone')
      const [r] = c.requests.splice(at, 1)
      if (p.allow === true) {
        c.paired.push({ id: r.id, name: r.name, at: Date.now() })
        c.chats[r.chatKey] ??= { kind: r.chatKind, title: r.name, at: Date.now() }
      }
      return true
    },
    async 'im-unpair'(p) {
      const c = conn(p.id)
      c.paired = c.paired.filter((e) => e.id !== String(p.senderId ?? ''))
      return true
    },
    async 'im-chat'(p) {
      const c = conn(p.id)
      const chat = c.chats[String(p.chatKey ?? '')]
      if (!chat) throw new Error('No such chat')
      if (p.remove === true) delete c.chats[p.chatKey]
      else if (p.target === null) delete chat.target
      else if (p.target !== undefined) {
        if (p.target.botId !== undefined && !botExists(p.target.botId)) throw new Error('Choose a Bot or a group')
        if (p.target.roomId !== undefined && !roomExists(p.target.roomId)) throw new Error('Choose a Bot or a group')
        chat.target = p.target.botId !== undefined ? { botId: String(p.target.botId) } : { roomId: String(p.target.roomId) }
      }
      return true
    },
    async 'im-retry'(p) {
      const c = conn(p.id)
      await sleep(600)
      statuses[c.id] = { state: 'ready', at: Date.now() }
      return true
    },
    async 'im-login-start'(p) {
      if (p.platform !== 'weixin') throw new Error('This platform has no QR login')
      if (!botExists(p.botId)) throw new Error('Choose a Bot first')
      await sleep(500)
      const loginId = randomBytes(6).toString('hex')
      logins.set(loginId, { polls: 0, expired: false })
      return { loginId, qr: `https://liteapp.weixin.qq.com/q/mock${loginId}?qrcode=${loginId}&bot_type=3`, expiresAt: Date.now() + 300_000, botId: p.botId }
    },
    async 'im-login-wait'(p) {
      if (p.platform !== 'weixin') throw new Error('This platform has no QR login')
      if (!botExists(p.botId)) throw new Error('Choose a Bot first')
      const held = logins.get(String(p.loginId ?? ''))
      if (!held || held.expired) return { status: 'expired' }
      held.polls += 1
      await sleep(held.polls === 1 ? 3000 : 1500) // 上游长轮询（最长约 35 秒）
      if (held.expired) return { status: 'expired' }
      if (held.confirm) {
        logins.delete(String(p.loginId))
        const id = 'im_' + randomBytes(4).toString('hex')
        connections[id] = { id, platform: 'weixin', account: { id: 'wx1', name: 'WeChat' }, botId: p.botId, settings: {}, secrets: ['token'], enabled: true, paired: [{ id: 'wx-owner', name: '我的微信', at: Date.now() }], requests: [], chats: { 'wx-owner': { kind: 'direct', title: '我的微信', at: Date.now() } }, createdAt: Date.now() }
        statuses[id] = { state: 'ready', at: Date.now() }
        return { status: 'done', id }
      }
      return { status: held.scanned ? 'scanned' : 'waiting' }
    },
  }

  /** 本模块负责的端点：返回 Promise；其他端点返回 null。 */
  function handle(endpoint, p, ok, fail) {
    const fn = endpoints[endpoint]
    if (!fn || !enabled) return null
    calls.push({ endpoint, keys: Object.keys(p ?? {}).sort(), fieldKeys: Object.keys(p?.fields ?? {}).sort(), at: Date.now() })
    return fn(p ?? {}).then((value) => { bump(); return ok(value) }, (error) => fail(error.message))
  }

  // /mock/im：?enabled=0|1 切换 state 是否带 IM；?scan=1 模拟另一台设备已扫码；?confirm=1 确认；?expire=1 让当前二维码过期；
  // ?request=<name> 给 Telegram 机器人新增一条待允许请求；?reset=1 复原；?resetCalls=1 清空调用记录
  function set(params) {
    const en = params.get('enabled')
    if (en === '0' || en === '1') enabled = en === '1'
    for (const held of logins.values()) {
      if (params.get('scan') === '1') held.scanned = true
      if (params.get('confirm') === '1') held.confirm = true
      if (params.get('expire') === '1') held.expired = true
    }
    const req = params.get('request')
    if (req && connections.im_tg01) connections.im_tg01.requests.push({ id: 'u-' + randomBytes(2).toString('hex'), name: req, chatKey: 'u-' + req, chatKind: 'direct', at: Date.now() })
    if (params.get('reset') === '1') reset()
    if (params.get('resetCalls') === '1') calls.length = 0
    bump()
    return { enabled, connections, statuses, logins: [...logins.keys()], calls }
  }

  return { view, handle, set, liveKey: () => JSON.stringify(enabled ? statuses : null) }
}
