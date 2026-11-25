// mock-dsh-server --manage 的密钥设置与落盘加密（dsbot-secrets-settings 模拟器验收）：
// 按 DS-Bot 0.3.10 src/host/secrets.js 与 secrets-crypto.js 源码核对的形态模拟。
// 调用记录与控制接口绝不返回或记录任何密钥值与口令，只记端点名与参数键。
const refused = (message) => Object.assign(new Error(message), { code: 'bot/refused' })

const SECRET_NAME = /^[A-Z][A-Z0-9_]{0,63}$/
const MIN_VALUE = 4
const MAX_VALUE = 16384
const MIN_PASSPHRASE = 8
const MAX_PASSPHRASE = 1024
const LOCKED_MSG = 'The secrets store is locked. Unlock it in Settings → Secrets, or start DS Bot with DS_BOT_SECRETS_PASSPHRASE_FILE set to a file that holds the passphrase.'

const normalizeName = (raw) =>
  String(raw ?? '').trim().replace(/^\$(env:)?/i, '').replace(/^DSH_SECRET_/i, '').replace(/[\s-]+/g, '_').toUpperCase()

const cleanValue = (raw) => {
  if (typeof raw !== 'string') throw refused('Type the key first')
  const value = raw.trim()
  if (value.length < MIN_VALUE) throw refused(`A key must have at least ${MIN_VALUE} characters`)
  if (value.length > MAX_VALUE) throw refused(`A key can have at most ${MAX_VALUE} characters`)
  return value
}

const cleanPassphrase = (raw) => {
  if (typeof raw !== 'string') throw refused('Type the passphrase first.')
  if (raw.length < MIN_PASSPHRASE) throw refused(`A passphrase has at least ${MIN_PASSPHRASE} characters.`)
  if (raw.length > MAX_PASSPHRASE) throw refused(`A passphrase has at most ${MAX_PASSPHRASE} characters.`)
  return raw
}

export function createSecrets({ bump, pushLive, botExists }) {
  let enabled = true
  let storeEnabled = true
  let readOnly = null
  const calls = []
  let entries = new Map()
  let requests = {}
  let store = { mode: 'plaintext', locked: false, source: null, reason: null, passphrase: null }

  function reset() {
    const now = Date.now()
    enabled = true
    storeEnabled = true
    readOnly = null
    store = { mode: 'plaintext', locked: false, source: null, reason: null, passphrase: null }
    entries = new Map([
      ['GITHUB_TOKEN', { name: 'GITHUB_TOKEN', value: 'mock-ghp-1234', purpose: 'GitHub API 访问', scope: 'all', requestedBy: null, createdAt: now - 7_200_000, updatedAt: now - 7_200_000, lastUsedAt: now - 600_000 }],
      ['STRIPE_KEY', { name: 'STRIPE_KEY', value: 'mock-sk-5678', purpose: '账单与订阅查询', scope: ['bot-1'], requestedBy: 'bot-1', createdAt: now - 86_400_000, updatedAt: now - 3_600_000 }],
    ])
    requests = {}
  }
  reset()

  const secretView = (s) => ({
    name: s.name,
    purpose: s.purpose,
    scope: s.scope,
    requestedBy: s.requestedBy,
    createdAt: s.createdAt,
    updatedAt: s.updatedAt,
    ...(s.lastUsedAt !== undefined ? { lastUsedAt: s.lastUsedAt } : {}),
  })

  const secretsView = () =>
    store.locked ? [] : [...entries.values()].sort((a, b) => a.name.localeCompare(b.name)).map(secretView)

  const storeStatus = () => ({
    mode: store.mode,
    locked: store.locked === true,
    source: store.locked ? null : store.source,
    ...(store.locked && store.reason ? { reason: store.reason } : {}),
  })

  const cleanScope = (raw) => {
    if (raw === 'all') return 'all'
    if (!Array.isArray(raw)) throw refused('Choose every Bot or a list of Bots')
    return [...new Set(raw.filter((id) => typeof id === 'string' && botExists(id)))]
  }

  const checkWritable = () => {
    if (readOnly !== null) throw refused(readOnly)
  }

  const checkUnlocked = () => {
    if (store.locked) throw refused(LOCKED_MSG)
  }

  function findPendingRequest(id) {
    for (const [botId, list] of Object.entries(requests)) {
      const req = list.find((r) => r.id === id)
      if (req) return { botId, req }
    }
    return null
  }

  const endpoints = {
    'secret-store'() {
      if (!storeEnabled) throw Object.assign(new Error('Unknown endpoint secret-store'), { code: 'bot/not-found' })
      return storeStatus()
    },
    'secret-unlock'(p) {
      if (!storeEnabled) throw Object.assign(new Error('Unknown endpoint secret-unlock'), { code: 'bot/not-found' })
      const pass = cleanPassphrase(p.passphrase)
      if (store.mode !== 'encrypted') throw refused('The secrets file is not encrypted.')
      if (pass !== store.passphrase) throw refused('The passphrase did not unlock the secrets file, or the file is damaged.')
      store.locked = false
      store.reason = null
      store.source = 'user'
      pushLive()
      return storeStatus()
    },
    'secret-encrypt'(p) {
      if (!storeEnabled) throw Object.assign(new Error('Unknown endpoint secret-encrypt'), { code: 'bot/not-found' })
      const pass = cleanPassphrase(p.passphrase)
      if (store.mode === 'encrypted') throw refused('Secrets are already encrypted.')
      checkWritable()
      store.mode = 'encrypted'
      store.locked = false
      store.reason = null
      store.source = 'user'
      store.passphrase = pass
      bump()
      return storeStatus()
    },
    'secret-decrypt'(p) {
      if (!storeEnabled) throw Object.assign(new Error('Unknown endpoint secret-decrypt'), { code: 'bot/not-found' })
      const pass = cleanPassphrase(p.passphrase)
      if (store.mode !== 'encrypted') throw refused('Secrets are not encrypted.')
      checkWritable()
      if (pass !== store.passphrase) throw refused('The passphrase did not unlock the secrets file, or the file is damaged.')
      store.mode = 'plaintext'
      store.locked = false
      store.reason = null
      store.source = null
      store.passphrase = null
      bump()
      return storeStatus()
    },
    'secret-set'(p) {
      checkUnlocked()
      checkWritable()
      const value = cleanValue(p.value)
      const now = Date.now()
      if (p.requestId !== undefined) {
        const found = findPendingRequest(String(p.requestId))
        if (!found) throw refused('This request is gone')
        if (found.req.status !== 'pending') throw refused('This request was already answered')
        const existing = entries.get(found.req.name)
        const scope = existing === undefined || found.req.kind === 'fill' ? (p.scope === 'bot' ? [found.botId] : 'all') : existing.scope
        entries.set(found.req.name, {
          name: found.req.name,
          value,
          purpose: existing?.purpose || found.req.purpose || '',
          scope,
          requestedBy: existing?.requestedBy ?? found.botId,
          createdAt: existing?.createdAt ?? now,
          updatedAt: now,
          ...(existing?.lastUsedAt !== undefined ? { lastUsedAt: existing.lastUsedAt } : {}),
        })
        found.req.status = 'saved'
        found.req.settledAt = now
        bump()
        return secretView(entries.get(found.req.name))
      }
      const name = normalizeName(p.name)
      if (!SECRET_NAME.test(name)) throw refused('A name uses capitals, digits, and underscores, and starts with a letter')
      const existing = entries.get(name)
      entries.set(name, {
        name,
        value,
        purpose: typeof p.purpose === 'string' && p.purpose.trim() !== '' ? p.purpose.trim().slice(0, 300) : existing?.purpose ?? '',
        scope: p.scope !== undefined ? cleanScope(p.scope) : existing?.scope ?? 'all',
        requestedBy: existing?.requestedBy ?? null,
        createdAt: existing?.createdAt ?? now,
        updatedAt: now,
        ...(existing?.lastUsedAt !== undefined ? { lastUsedAt: existing.lastUsedAt } : {}),
      })
      bump()
      return secretView(entries.get(name))
    },
    'secret-update'(p) {
      checkUnlocked()
      checkWritable()
      const name = normalizeName(p.name)
      const secret = entries.get(name)
      if (!secret) throw refused('No such key')
      if (p.scope !== undefined) secret.scope = cleanScope(p.scope)
      if (typeof p.purpose === 'string') secret.purpose = p.purpose.trim().slice(0, 300)
      secret.updatedAt = Date.now()
      bump()
      return secretView(secret)
    },
    'secret-delete'(p) {
      checkUnlocked()
      checkWritable()
      const name = normalizeName(p.name)
      if (!entries.delete(name)) throw refused('No such key')
      bump()
      return true
    },
    'secret-allow'(p) {
      checkUnlocked()
      checkWritable()
      const found = findPendingRequest(String(p.requestId ?? ''))
      if (!found) throw refused('This request is gone')
      const secret = entries.get(found.req.name)
      if (!secret) throw refused(`${found.req.name} is not saved yet`)
      if (Array.isArray(secret.scope) && !secret.scope.includes(found.botId)) secret.scope.push(found.botId)
      found.req.status = 'allowed'
      found.req.settledAt = Date.now()
      bump()
      return secretView(secret)
    },
    'secret-cancel'(p) {
      checkWritable()
      const found = findPendingRequest(String(p.requestId ?? ''))
      if (!found) throw refused('This request is gone')
      found.req.status = 'canceled'
      found.req.settledAt = Date.now()
      bump()
      return true
    },
  }

  function view() {
    if (!enabled) return { readOnly }
    return {
      secrets: secretsView(),
      ...(storeEnabled ? { secretStore: storeStatus() } : {}),
      secretRequests: requests,
      readOnly,
    }
  }

  function liveFields() {
    if (!enabled) return { readOnly }
    return {
      secrets: secretsView(),
      ...(storeEnabled ? { secretStore: storeStatus() } : {}),
      readOnly,
    }
  }

  function liveKey() {
    return `${enabled ? 1 : 0}:${storeEnabled ? 1 : 0}:${readOnly ?? ''}:${store.mode}:${store.locked ? 1 : 0}:${store.reason ?? ''}:${secretsView().map((s) => `${s.name}@${s.updatedAt}`).join(',')}`
  }

  function handle(endpoint, p, ok, fail) {
    const fn = endpoints[endpoint]
    if (!fn) return null
    calls.push({ endpoint, keys: Object.keys(p ?? {}).sort(), at: Date.now() })
    if (!enabled) return { ok: false, error: { code: 'bot/not-found', message: `Unknown endpoint ${endpoint}`, details: {} } }
    try {
      return ok(fn(p ?? {}))
    } catch (error) {
      return error.code ? { ok: false, error: { code: error.code, message: error.message, details: {} } } : fail(error.message)
    }
  }

  // /mock/secrets：?enabled=0|1；?storeEnabled=0|1；?readOnly=<文案或空>；?lock=1[&reason=...]；?request=0|1；?reset=1；?resetCalls=1
  function set(params) {
    const en = params.get('enabled')
    if (en === '0' || en === '1') enabled = en === '1'
    const se = params.get('storeEnabled')
    if (se === '0' || se === '1') storeEnabled = se === '1'
    if (params.has('readOnly')) {
      const r = params.get('readOnly')
      readOnly = r && r.trim() !== '' ? r : null
    }
    if (params.get('lock') === '1') {
      store.mode = 'encrypted'
      store.locked = true
      store.source = null
      store.reason = params.get('reason') || 'missing-passphrase'
      if (!store.passphrase) store.passphrase = 'test-pass-1234'
    }
    const reqParam = params.get('request')
    if (reqParam === '1') {
      requests = {
        'bot-1': [{ id: 'sreq-1', sessionId: 's-bot-1', name: 'DEPLOY_TOKEN', purpose: '发布脚本鉴权', kind: 'fill', status: 'pending', askedAt: Date.now() }],
      }
    } else if (reqParam === '0') {
      requests = {}
    }
    if (params.get('reset') === '1') reset()
    if (params.get('resetCalls') === '1') calls.length = 0
    bump()
    return { enabled, storeEnabled, readOnly, store: storeStatus(), secrets: secretsView(), pendingRequests: Object.values(requests).flat().filter((r) => r.status === 'pending').length, calls }
  }

  return { view, liveFields, liveKey, handle, set }
}
