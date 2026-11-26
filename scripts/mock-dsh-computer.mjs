// mock-dsh-server --manage 的电脑操控设置（dsbot-computer-settings 模拟器验收）：
// 按 ds-bot 0.3.10 src/host/computer/index.js（computerView/computerEndpoint/setEnabled/setLevel/revokeApp/ensureProvider）
// 与 channel.js（view().computer、READS、只读宿主 bot/read-only）源码核对的形态模拟。
// 上游的写入都会 rt.save() 推进 revision（provider 状态变化同样保存），这里照搬：每次状态变化调用 bump()。
// 调用记录只记端点与参数键。
const LEVELS = ['off', 'view', 'background', 'foreground']
const INSTALL = {
  packages: ['@deepseek-ai/dsh-computer-use', '@deepseek-ai/dsh-experimental-computer-use-cua-driver-native'],
  command: 'pnpm add @deepseek-ai/dsh-computer-use @deepseek-ai/dsh-experimental-computer-use-cua-driver-native',
}
// 宿主形态：开启后 ensureProvider 得到的最终 provider 状态与 macos 标记
const HOSTS = {
  macos: { macos: true, outcome: () => ({ state: 'ready', name: 'dsh-cua-macos' }) },
  windows: { macos: false, outcome: () => ({ state: 'ready', name: 'dsh-cua' }) },
  missing: { macos: false, outcome: () => ({ state: 'missing', message: '@deepseek-ai/dsh-computer-use is not installed in this DSH.', install: INSTALL }) },
  // 一般加载失败（可重试的组件错误）
  error: { macos: false, outcome: () => ({ state: 'error', message: 'Could not prepare the computer-use provider: spawn EACCES' }) },
  // 宿主无包加载器（上游 importPackage 的 bot/no-loader 落为 error + 固定文案）→ 客户端判为此宿主不支持
  noloader: { macos: false, outcome: () => ({ state: 'error', message: 'This DSH cannot load packages.' }) },
  occupied: { macos: false, outcome: () => ({ state: 'occupied', name: 'other-provider' }) },
  unsupported: { macos: false, outcome: () => ({ state: 'unsupported' }) },
  // computer 模块缺失：channel.js 回落 { enabled: false }，computer-* 端点回 bot/not-found
  minimal: { macos: false, outcome: () => ({ state: 'unsupported' }) },
}
const refused = (message) => Object.assign(new Error(message), { code: 'bot/refused' })
const notFound = (message) => Object.assign(new Error(message), { code: 'bot/not-found' })

export function createComputer({ bump, botIds }) {
  let host = 'macos'
  let readOnly = null
  let enabled = false
  let provider = { state: 'off' }
  let delayMs = 1500
  let levels = {}
  let apps = {}
  let perms = {}
  let timer = null
  const calls = []

  function reset() {
    host = 'macos'
    readOnly = null
    enabled = false
    provider = { state: 'off' }
    delayMs = 1500
    levels = {}
    const now = Date.now()
    apps = {
      'bot-1': {
        'com.apple.Safari': { name: 'Safari', id: 'com.apple.Safari', grantedAt: now - 3_600_000 },
        'com.microsoft.VSCode': { name: 'Visual Studio Code', id: 'com.microsoft.VSCode', grantedAt: now - 600_000 },
      },
    }
    perms = { accessibility: { state: 'granted' }, screenRecording: { state: 'denied' } }
    if (timer) clearTimeout(timer)
    timer = null
  }
  reset()

  const levelOf = (id) => (LEVELS.includes(levels[id]) ? levels[id] : 'background')
  const setProvider = (next) => {
    if (next.state === provider.state && next.name === provider.name && next.message === provider.message) return
    provider = next
    bump()
  }
  const settle = () => {
    if (!enabled) return setProvider({ state: 'off' })
    setProvider(HOSTS[host].outcome())
  }

  function view() {
    if (host === 'minimal') return { enabled: false }
    return {
      enabled,
      provider,
      macos: HOSTS[host].macos,
      bots: Object.fromEntries(botIds().map((id) => [id, { level: levelOf(id), apps: { ...(apps[id] ?? {}) } }])),
    }
  }

  const checkWritable = () => {
    if (readOnly !== null) throw Object.assign(new Error(readOnly), { code: 'bot/read-only' })
  }
  const checkBot = (id) => {
    if (!botIds().includes(id)) throw refused('Unknown Bot')
  }
  const access = () => (host === 'macos' && enabled && provider.state === 'ready')

  const endpoints = {
    'set-enabled'(p) {
      checkWritable()
      const next = p.enabled === true
      if (enabled !== next) {
        enabled = next
        bump()
      }
      if (timer) clearTimeout(timer)
      timer = null
      if (next) {
        setProvider(provider.state === 'off' ? { state: 'loading' } : provider)
        timer = setTimeout(() => { timer = null; settle() }, delayMs)
      } else {
        setProvider({ state: 'off' })
      }
      return view()
    },
    'set-level'(p) {
      checkWritable()
      const botId = String(p.botId ?? '')
      checkBot(botId)
      const level = String(p.level ?? '')
      if (!LEVELS.includes(level)) throw refused(`Level must be one of ${LEVELS.join(', ')}`)
      if (level === 'background') delete levels[botId]
      else levels[botId] = level
      bump()
      return true
    },
    'revoke-app'(p) {
      checkWritable()
      const botId = String(p.botId ?? '')
      checkBot(botId)
      const key = String(p.appKey ?? '')
      if (apps[botId]?.[key] !== undefined) {
        delete apps[botId][key]
        if (Object.keys(apps[botId]).length === 0) delete apps[botId]
        bump()
      }
      return true
    },
    'provider-retry'() {
      checkWritable()
      setProvider({ state: 'loading' })
      settle()
      return view()
    },
    'system-status'() {
      if (!access()) return null
      return { accessibility: { ...perms.accessibility }, screenRecording: { ...perms.screenRecording } }
    },
    'permission'(p) {
      checkWritable()
      if (!access()) throw refused('System permissions are not available here')
      const permission = ['accessibility', 'screenRecording'].includes(p.permission) ? p.permission : 'accessibility'
      // 模拟用户在电脑上完成授权：下一次 system-status 读到 granted
      perms[permission] = { state: 'granted' }
      return { state: 'prompted' }
    },
  }
  // 上游 channel.js READS 只含 computer-system-status 与 computer-shot
  const READS = new Set(['system-status'])

  function handle(endpoint, p, ok, fail) {
    if (!endpoint.startsWith('computer-')) return null
    const sub = endpoint.slice('computer-'.length)
    // 聊天条端点（app-answer/stop 等）不归本模块
    if (!(sub in endpoints)) return null
    calls.push({ endpoint, keys: Object.keys(p ?? {}).sort(), at: Date.now() })
    try {
      if (host === 'minimal') throw notFound(`Unknown endpoint ${endpoint}`)
      if (readOnly !== null && !READS.has(sub)) throw Object.assign(new Error(readOnly), { code: 'bot/read-only' })
      return ok(endpoints[sub](p ?? {}))
    } catch (error) {
      return error.code ? { ok: false, error: { code: error.code, message: error.message, details: {} } } : fail(error.message)
    }
  }

  // /mock/computer：?host=macos|windows|missing|error|noloader|occupied|unsupported|minimal；?enabled=0|1（直接置位并立即落定 provider）；
  // ?settle=0（与 host 同用：不立即落定 provider）；?readOnly=<文案或空>；?delay=<毫秒>；?grant=<appKey>&bot=<botId>[&name=<应用名>]；?perm=<key>&state=granted|denied；?reset=1；?resetCalls=1
  function set(params) {
    if (params.get('reset') === '1') reset()
    if (params.get('resetCalls') === '1') calls.length = 0
    const h = params.get('host')
    if (h && h in HOSTS) host = h
    if (params.has('readOnly')) {
      const r = params.get('readOnly')
      readOnly = r && r.trim() !== '' ? r : null
    }
    const d = Number(params.get('delay'))
    if (params.has('delay') && Number.isFinite(d) && d >= 0) delayMs = d
    const en = params.get('enabled')
    if (en === '0' || en === '1') {
      enabled = en === '1'
      provider = enabled ? HOSTS[host].outcome() : { state: 'off' }
    } else if (h && enabled && params.get('settle') !== '0') {
      // ?settle=0：只换宿主形态、保留当前 provider 状态（验收「重试」时由 computer-provider-retry 落定）
      provider = HOSTS[host].outcome()
    }
    const grant = params.get('grant')
    const bot = params.get('bot')
    if (grant && bot && botIds().includes(bot)) {
      apps[bot] ??= {}
      apps[bot][grant] = { name: params.get('name') || grant, grantedAt: Date.now() }
    }
    const perm = params.get('perm')
    if (perm === 'accessibility' || perm === 'screenRecording') perms[perm] = { state: params.get('state') === 'granted' ? 'granted' : 'denied' }
    bump()
    return { host, readOnly, view: view(), levels: { ...levels }, perms, calls }
  }

  // 只读宿主文案：manage view 同步到 state.readOnly（页面只读横幅），与上游 channel.js 同源
  return { handle, view, set, readOnly: () => readOnly }
}
