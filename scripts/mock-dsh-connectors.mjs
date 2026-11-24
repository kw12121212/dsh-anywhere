// mock-dsh-server --manage 的连接器与邮箱（dsbot-connectors-mail 模拟器验收）：按 ds-bot 0.3.10
// connectors/index.js（rowFor、endpoints）与 connectors/mail/index.js（mailView、endpoints、applyRules）源码核对的形态，
// 用内存数据模拟各鉴权类型的连接与登录流程。调用记录只记端点与参数键，不记 key、token、密码等值。
import { randomBytes } from 'node:crypto'

const REDIRECT = 'http://127.0.0.1:53682/callback'
const CATALOG = [
  { id: 'github', name: 'GitHub', category: 'dev', about: 'Repositories, issues, and pull requests', auth: { kind: 'oauth', scopes: ['repo', 'read:org'], client: { id: 'Ov23mock', deviceFlow: true }, token: true } },
  { id: 'notion', name: 'Notion', category: 'docs', about: 'Pages, databases, and wiki content', auth: { kind: 'oauth' } },
  { id: 'linear', name: 'Linear', category: 'work', about: 'Issues, projects, and cycles', auth: { kind: 'oauth' } },
  { id: 'slack', name: 'Slack', category: 'work', about: 'Channels and messages', auth: { kind: 'oauth', clientSetup: { kind: 'slack', appUrl: 'https://api.slack.com/apps' } } },
  { id: 'sentry', name: 'Sentry', category: 'dev', about: 'Errors, traces, and releases', auth: { kind: 'oauth' } },
  { id: 'amap', name: 'Amap', category: 'search', about: 'Maps, places, and routes in China', auth: { kind: 'key', query: 'key', keyUrl: 'https://lbs.amap.com/api/mcp-server/create' } },
  { id: 'exa', name: 'Exa', category: 'search', about: 'Web search for agents', auth: { kind: 'none' } },
  { id: 'deepwiki', name: 'DeepWiki', category: 'docs', about: 'Documentation for public code repositories', auth: { kind: 'none' } },
]
const PRESETS = ['qq', '163', '126', 'gmail', 'icloud']
const ADDRESS = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

export function createConnectors({ bump, botExists, botIds }) {
  let enabled = true
  let google = false
  let seq = 0
  const calls = []
  let saved = {}
  let customs = {}
  let mail = {}
  let connectorRequests = {}
  let mailRequests = {}

  function reset() {
    // exa 已连接（3 个工具，运维值班关闭）；sentry 出错；团队 Wiki 为自加的远程服务器（只读开关可用）
    saved = {
      exa: { state: 'ready', tools: 3, offFor: ['bot-2'] },
      sentry: { state: 'error', error: 'fetch failed', account: 'ops@example.com', readOnly: false, tools: 0 },
      'team-wiki': { state: 'ready', tools: 4, readOnly: false },
    }
    customs = { 'team-wiki': { name: '团队 Wiki', url: 'https://wiki.example.com/mcp', headers: {} } }
    mail = {
      accounts: { m1: { id: 'm1', address: 'ops@qq.com', provider: 'qq', auth: 'password' } },
      allowedRecipients: ['boss@example.com'],
      noDraftFor: [],
      pending: [],
    }
    connectorRequests = {
      'bot-1': [{ id: 'creq-1', callId: 'call-c1', sessionId: 's-bot-1', connectorId: 'notion', name: 'Notion', purpose: '读取产品文档', status: 'pending', askedAt: Date.now() }],
    }
    mailRequests = {
      'bot-1': [mailCard()],
    }
  }
  function mailCard() {
    seq += 1
    return {
      id: 'mreq-' + seq, callId: 'call-m' + seq, sessionId: 's-bot-1', account: 'm1',
      to: 'team@example.com', cc: '', bcc: '', subject: '本周巡检报告' + (seq > 1 ? ` #${seq}` : ''), body: '各位好：\n本周巡检已完成，详见附件。',
      attachments: ['report.pdf'], dir: '/work/ops', attachmentDetails: [{ name: 'report.pdf', size: 245760 }], status: 'pending', askedAt: Date.now(),
    }
  }
  reset()

  const entryOf = (id) => {
    const cat = CATALOG.find((e) => e.id === id)
    if (cat) return cat
    const custom = customs[id]
    if (!custom) return undefined
    const base = { id, name: custom.name, category: 'custom', about: custom.url ?? custom.command ?? '', auth: { kind: 'custom' }, custom: true }
    return custom.oauth === true ? { ...base, auth: { kind: 'oauth' } } : base
  }
  const known = (id) => {
    const entry = entryOf(String(id ?? ''))
    if (!entry) throw new Error('No such connector')
    return entry
  }
  const rowFor = (entry) => {
    const s = saved[entry.id] ?? {}
    const state = s.state ?? (entry.custom ? 'ready' : 'off')
    const connected = state === 'ready' || (state === 'error' && s.account) || state === 'needs-sign-in'
    return {
      id: entry.id, name: entry.name, category: entry.category, about: entry.about, auth: entry.auth, state,
      ...(s.error ? { error: s.error } : {}),
      account: s.account ?? null,
      ...(connected && (entry.auth.kind === 'oauth' || entry.auth.kind === 'key' || entry.custom) && s.readOnly !== undefined ? { readOnly: s.readOnly === true } : {}),
      offFor: s.offFor ?? [], tools: s.tools ?? 0,
      ...(entry.custom ? { custom: true } : {}),
      ...(s.pending ? { pending: s.pending } : {}),
      ...(s.redirectUri ? { redirectUri: s.redirectUri } : {}),
    }
  }
  const mailView = () => ({
    accounts: Object.values(mail.accounts).map((a) => ({ id: a.id, address: a.address, provider: a.provider, auth: a.auth, state: a.error ? 'error' : 'ready', ...(a.error ? { error: a.error } : {}) })),
    google,
    allowedRecipients: [...mail.allowedRecipients],
    noDraftFor: [...mail.noDraftFor],
    ...(mail.pending.length > 0 ? { pending: mail.pending.map((p) => ({ kind: 'browser', url: p.url, address: p.address })) } : {}),
  })
  const connectorsView = () => ({
    connectors: [...CATALOG, ...Object.keys(customs).map(entryOf)].map(rowFor),
    mail: mailView(),
  })
  const view = () => (enabled ? { connectors: connectorsView(), connectorRequests, mailRequests } : {})

  const patch = (id, p) => { saved[id] = { ...(saved[id] ?? {}), ...p } }
  const settle = (id) => {
    for (const list of Object.values(connectorRequests)) {
      for (const r of list) if (r.status === 'pending' && r.connectorId === id) { r.status = 'connected'; r.settledAt = Date.now() }
    }
  }
  const ready = (id, extra = {}) => {
    patch(id, { state: 'ready', error: undefined, pending: undefined, redirectUri: undefined, tools: 5, readOnly: saved[id]?.readOnly ?? false, ...extra })
    settle(id)
  }
  const beginBrowser = (id) => {
    const state = randomBytes(6).toString('hex')
    patch(id, { state: 'connecting', error: undefined, expectedState: state, redirectUri: REDIRECT, pending: { kind: 'browser', url: `https://auth.example.com/authorize?client_id=mock&response_type=code&state=${state}&redirect_uri=${encodeURIComponent(REDIRECT)}` } })
  }
  function applyRules(p) {
    const parse = (raw) => String(Array.isArray(raw) ? raw.join(', ') : raw ?? '').split(/[,;]+/).map((s) => s.trim().toLowerCase()).filter((a) => ADDRESS.test(a))
    const add = (raw) => { for (const a of parse(raw)) if (!mail.allowedRecipients.includes(a)) mail.allowedRecipients.push(a) }
    const drop = (raw) => { const d = new Set(parse(raw)); mail.allowedRecipients = mail.allowedRecipients.filter((a) => !d.has(a)) }
    if (p.mode === 'allow-recipient') { add(p.recipients ?? p.address); if (typeof p.botId === 'string') mail.noDraftFor = mail.noDraftFor.filter((b) => b !== p.botId) }
    else if (p.mode === 'no-draft') { if (typeof p.botId === 'string' && botExists(p.botId) && !mail.noDraftFor.includes(p.botId)) mail.noDraftFor.push(p.botId) }
    else if (p.mode === 'require-approval') { if (typeof p.botId === 'string') mail.noDraftFor = mail.noDraftFor.filter((b) => b !== p.botId); if (p.recipients || p.address) drop(p.recipients ?? p.address) }
    if (p.addRecipient) add(p.addRecipient)
    if (p.removeRecipient) drop(p.removeRecipient)
    if (typeof p.botId === 'string' && typeof p.noDraft === 'boolean') {
      mail.noDraftFor = p.noDraft ? (botExists(p.botId) && !mail.noDraftFor.includes(p.botId) ? [...mail.noDraftFor, p.botId] : mail.noDraftFor) : mail.noDraftFor.filter((b) => b !== p.botId)
    }
  }
  const customId = (name) => {
    let base = String(name).toLowerCase().trim().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 28) || 'custom'
    if (CATALOG.some((e) => e.id === base) || base === 'mail') base += '-x'
    let id = base
    while (customs[id]) id = `${base.slice(0, 28)}-${randomBytes(2).toString('hex')}`
    return id
  }
  const IMPORTS = [
    { source: 'claude', name: 'filesystem', command: 'npx', args: ['-y', '@modelcontextprotocol/server-filesystem', '/home/user'], envNames: [], headersNames: [] },
    { source: 'cursor', name: 'docs-search', url: 'https://docs-search.example.com/mcp', envNames: [], headersNames: ['Authorization'] },
  ]

  const endpoints = {
    async 'connector-connect'(p) {
      const entry = known(p.id)
      if (entry.auth.kind === 'none') { ready(entry.id, { tools: 2 }); return connectorsView() }
      if (entry.auth.kind === 'key') {
        const key = typeof p.key === 'string' ? p.key.trim() : ''
        if (key === '') throw new Error('Paste a key first')
        ready(entry.id, { tools: 6 })
        return connectorsView()
      }
      const token = typeof p.token === 'string' ? p.token.trim() : ''
      if (token !== '') {
        await sleep(800)
        if (token === 'bad') throw new Error('GitHub did not accept this token')
        ready(entry.id, { account: 'mock-user', tools: 8 })
        return connectorsView()
      }
      await sleep(600)
      if (entry.auth.client?.deviceFlow) {
        patch(entry.id, { state: 'connecting', error: undefined, pending: { kind: 'device', userCode: 'MOCK-' + randomBytes(2).toString('hex').toUpperCase(), verificationUri: 'https://github.com/login/device', expiresAt: Date.now() + 900_000 } })
        return connectorsView()
      }
      if (entry.auth.clientSetup && !saved[entry.id]?.clientId) {
        patch(entry.id, { state: 'needs-client', error: undefined, redirectUri: REDIRECT, pending: undefined })
        return connectorsView()
      }
      beginBrowser(entry.id)
      return connectorsView()
    },
    async 'connector-sign-in-code'(p) {
      const entry = known(p.id)
      const s = saved[entry.id]
      if (s?.pending?.kind !== 'browser') throw new Error('No sign-in is waiting')
      const input = String(p.input ?? p.code ?? '').trim()
      if (input === '') throw new Error('Paste the address or the code')
      let code = input
      if (/^https?:\/\//i.test(input)) {
        const url = new URL(input)
        code = url.searchParams.get('code') ?? ''
        if (url.searchParams.get('state') !== s.expectedState) throw new Error('This address is not from this sign-in attempt (state mismatch). Copy the whole address again.')
      }
      if (code === '') throw new Error('The address has no code')
      await sleep(500)
      ready(entry.id, { account: 'me@example.com', expectedState: undefined })
      return connectorsView()
    },
    async 'connector-client'(p) {
      const entry = known(p.id)
      const clientId = String(p.clientId ?? '').trim()
      if (clientId === '') throw new Error('Enter the client ID')
      patch(entry.id, { clientId })
      beginBrowser(entry.id)
      return connectorsView()
    },
    async 'connector-cancel'(p) {
      const entry = known(p.id)
      const connected = saved[entry.id]?.account !== undefined && saved[entry.id]?.state !== 'off'
      patch(entry.id, { pending: undefined, redirectUri: undefined, expectedState: undefined, state: connected ? 'ready' : 'off' })
      return connectorsView()
    },
    async 'connector-retry'(p) {
      const entry = known(p.id)
      await sleep(400)
      ready(entry.id)
      return connectorsView()
    },
    async 'connector-disconnect'(p) {
      const entry = known(p.id)
      delete saved[entry.id]
      if (entry.custom) delete customs[entry.id]
      return connectorsView()
    },
    async 'connector-remove'(p) {
      const entry = known(p.id)
      if (!entry.custom) throw new Error('Only added connectors can be removed')
      delete saved[entry.id]
      delete customs[entry.id]
      return connectorsView()
    },
    async 'connector-set'(p) {
      const entry = known(p.id)
      if (p.readOnly !== undefined) patch(entry.id, { readOnly: p.readOnly === true })
      if (Array.isArray(p.offFor)) patch(entry.id, { offFor: [...new Set(p.offFor.filter(botExists))] })
      return connectorsView()
    },
    async 'connector-add'(p) {
      const name = String(p.name ?? '').trim()
      if (name === '') throw new Error('Name the connector')
      const url = typeof p.url === 'string' ? p.url.trim() : undefined
      const command = typeof p.command === 'string' ? p.command.trim() : undefined
      if (url !== undefined && !/^https:\/\//i.test(url) && !/^http:\/\/(localhost|127\.)/i.test(url)) throw new Error('Connector addresses must start with https://')
      if (url === undefined && command === undefined) throw new Error('Enter an address or a command')
      if (p.oauth === true && url === undefined) throw new Error('OAuth sign-in needs an address')
      const id = customId(name)
      customs[id] = url !== undefined ? { name, url, headers: {}, ...(p.oauth === true ? { oauth: true } : {}) } : { name, command, args: [], env: {} }
      if (p.oauth === true) patch(id, { state: 'off' })
      else patch(id, { state: 'ready', tools: 1 })
      return connectorsView()
    },
    async 'connector-import-scan'() {
      await sleep(300)
      return IMPORTS.filter((i) => !Object.values(customs).some((c) => c.name === i.name))
    },
    async 'connector-import'(p) {
      const wanted = new Set((Array.isArray(p.items) ? p.items : []).map((i) => `${i?.source}:${i?.name}`))
      const created = []
      for (const item of IMPORTS) {
        if (!wanted.has(`${item.source}:${item.name}`)) continue
        const id = customId(item.name)
        customs[id] = item.url ? { name: item.name, url: item.url, headers: {} } : { name: item.name, command: item.command, args: item.args, env: {} }
        patch(id, { state: 'ready', tools: 2 })
        created.push(id)
      }
      return { view: connectorsView(), created }
    },
    async 'connector-request-cancel'(p) {
      for (const list of Object.values(connectorRequests)) {
        const r = list.find((e) => e.id === String(p.requestId ?? ''))
        if (!r) continue
        if (r.status !== 'pending') throw new Error('This request was already answered')
        r.status = 'canceled'; r.settledAt = Date.now()
        return true
      }
      throw new Error('This request is gone')
    },
    async 'mail-account-add'(p) {
      const address = String(p.address ?? '').trim().toLowerCase()
      if (p.google === true) {
        if (!google) throw new Error('Google sign-in is not available in this build')
        const state = randomBytes(6).toString('hex')
        mail.pending = [...mail.pending.filter((x) => x.address !== address), { address: address || undefined, state, url: `https://accounts.google.com/o/oauth2/v2/auth?client_id=mock&state=${state}&redirect_uri=${encodeURIComponent(REDIRECT)}` }]
        return mailView()
      }
      if (!ADDRESS.test(address)) throw new Error('Enter an email address')
      const password = String(p.password ?? '').trim()
      if (password === '') throw new Error('Enter the authorization code or app password')
      const provider = typeof p.provider === 'string' ? p.provider : 'other'
      if (provider === 'other' && (String(p.imapHost ?? '').trim() === '' || String(p.smtpHost ?? '').trim() === '')) throw new Error('Enter the IMAP and SMTP hosts')
      await sleep(1500) // 上游先登录 IMAP 验证再保存
      if (password === 'wrong') throw new Error('The password was refused. Use the mailbox app password or authorization code, not the account password.')
      const existing = Object.values(mail.accounts).find((a) => a.address === address)
      const id = existing?.id ?? 'm' + randomBytes(4).toString('hex')
      mail.accounts[id] = { id, address, provider: PRESETS.includes(provider) ? provider : 'other', auth: 'password' }
      return mailView()
    },
    async 'mail-account-remove'(p) {
      const account = Object.values(mail.accounts).find((a) => a.id === p.id || a.address === p.address)
      if (!account) throw new Error('No such account')
      delete mail.accounts[account.id]
      return mailView()
    },
    async 'mail-rule-set'(p) {
      applyRules(p ?? {})
      return mailView()
    },
    async 'mail-send-answer'(p) {
      let found
      for (const [botId, list] of Object.entries(mailRequests)) {
        const r = list.find((e) => e.id === String(p.requestId ?? ''))
        if (r) found = { botId, r }
      }
      if (!found || found.r.status !== 'pending') throw new Error('This card was already answered')
      const { botId, r } = found
      const f = p.fields
      if (f && typeof f === 'object') {
        if (typeof f.to === 'string' && f.to.trim() !== '') r.to = f.to.trim()
        for (const k of ['cc', 'bcc', 'subject']) if (typeof f[k] === 'string') r[k] = f[k].trim()
        if (typeof f.body === 'string') r.body = f.body
      }
      if (typeof p.rule === 'string') applyRules({ mode: p.rule, botId, recipients: [r.to, r.cc, r.bcc].filter(Boolean).join(', ') })
      if (p.choice !== 'send') { r.status = 'canceled'; r.settledAt = Date.now(); return true }
      await sleep(1200) // SMTP 发送
      r.status = 'sent'; r.settledAt = Date.now()
      return true
    },
    async 'mail-sign-in-code'(p) {
      const pending = mail.pending.find((x) => x.address === String(p.address ?? '').toLowerCase()) ?? mail.pending[0]
      if (!pending) throw new Error('No Google sign-in is waiting')
      const input = String(p.input ?? '').trim()
      const url = /^https?:/i.test(input) ? new URL(input) : undefined
      const code = url ? url.searchParams.get('code') : input
      if (!code) throw new Error('The address has no code')
      if (url && url.searchParams.get('state') !== pending.state) throw new Error('The sign-in answer is not for this attempt')
      mail.pending = mail.pending.filter((x) => x !== pending)
      const id = 'g' + randomBytes(4).toString('hex')
      mail.accounts[id] = { id, address: pending.address || 'me@gmail.com', provider: 'gmail', auth: 'google' }
      return mailView()
    },
  }

  /** 本模块负责的端点：返回 Promise<{ok,value}|{ok:false,error}>；其他端点返回 null。 */
  function handle(endpoint, p, ok, fail) {
    const fn = endpoints[endpoint]
    if (!fn || !enabled) return null
    calls.push({ endpoint, keys: Object.keys(p ?? {}).sort(), at: Date.now() })
    return fn(p ?? {}).then((value) => { bump(); return ok(value) }, (error) => fail(error.message))
  }

  // /mock/connectors：?enabled=0|1 切换 state 是否带连接器（旧版远端）；?complete=<id> 完成设备码登录；?google=0|1；
  // ?mailCard=1 新增一张待发邮件卡；?connectorCard=<id> 新增一张连接器请求卡；?reset=1 复原；?resetCalls=1 清空调用记录
  function set(params) {
    const en = params.get('enabled')
    if (en === '0' || en === '1') enabled = en === '1'
    const g = params.get('google')
    if (g === '0' || g === '1') google = g === '1'
    const done = params.get('complete')
    if (done && saved[done]?.pending?.kind === 'device') ready(done, { account: 'mock-user', tools: 8 })
    if (params.get('mailCard') === '1') (mailRequests['bot-1'] ??= []).push(mailCard())
    const cc = params.get('connectorCard')
    if (cc) {
      const entry = entryOf(cc) ?? { id: cc, name: cc }
      ;(connectorRequests['bot-1'] ??= []).push({ id: 'creq-' + ++seq, callId: 'call-c' + seq, sessionId: 's-bot-1', connectorId: entry.id, name: entry.name, purpose: '验收用请求', status: 'pending', askedAt: Date.now() })
    }
    if (params.get('reset') === '1') reset()
    if (params.get('resetCalls') === '1') calls.length = 0
    bump()
    return { enabled, google, view: connectorsView(), connectorRequests, mailRequests, calls }
  }

  return { view, handle, set, liveKey: () => JSON.stringify(enabled ? connectorsView() : null), botIds }
}
