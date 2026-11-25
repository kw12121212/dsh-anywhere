// mock-dsh-server --manage 的关于 DS Bot 与诊断（dsbot-system-about 模拟器验收）：
// 按 DS-Bot 0.3.10 src/host/update.js、dsh-update.js、diagnostics.js 与 channel.js 源码核对的形态模拟。
// update-install / dsh-update-install 立即返回 phase=installing，约 installMs 毫秒后转为 installed（或 ?fail=1 时 failed），
// 结果只经后续 update-check 读回（同上游）。调用记录只记端点名与参数键。
const refused = (message) => Object.assign(new Error(message), { code: 'bot/refused' })

const BASE_BOT = '0.3.10'
const BASE_DSH = '0.2.2-rc.3'
const REPO = 'https://github.com/Fei2411/DS-Bot'

export function createSystem() {
  let enabled = true
  let starsEnabled = true
  let installMs = 6000
  let failNext = false
  const calls = []
  let bot
  let dsh
  let entries
  let stars

  function reset() {
    enabled = true
    starsEnabled = true
    installMs = 6000
    failNext = false
    const now = Date.now()
    bot = { name: 'ds-bot', current: BASE_BOT, latest: '0.3.11', source: 'registry', notesUrl: `${REPO}/releases`, repoUrl: REPO, enabled: true, checkedAt: now - 120_000, phase: 'idle', installed: null, error: null }
    dsh = { current: BASE_DSH, verified: '0.2.3', kind: 'npm', blockedBy: null, checkedAt: now - 120_000, phase: 'idle', installed: null, error: null }
    stars = 1234
    entries = [
      { time: now - 60_000, level: 'error', source: 'turn', text: '429 Too Many Requests: rate limit reached for requests', bot: '研究主管', where: '私聊', code: 'RATE_LIMIT', status: 429 },
      { time: now - 600_000, level: 'warn', source: 'host', text: 'update check failed: registry.npmjs.org answered 503' },
      { time: now - 3_600_000, level: 'error', source: 'api', text: 'schedule-create: Pick a time in the future', code: 'Error' },
    ].sort((a, b) => a.time - b.time) // 上游按记录顺序（旧到新）保存，diagnostics 再取最后 50 条倒序
  }
  reset()

  const newer = (a, b) => {
    const parse = (v) => {
      const [core, pre] = String(v ?? '0.0.0').split(/-(.*)/s)
      return { nums: core.split('.').map(Number), pre: pre ?? null }
    }
    const x = parse(a)
    const y = parse(b)
    for (let i = 0; i < 3; i++) if (x.nums[i] !== y.nums[i]) return x.nums[i] > y.nums[i]
    if (x.pre === y.pre) return false
    if (x.pre === null) return true
    if (y.pre === null) return false
    return x.pre.localeCompare(y.pre, 'en', { numeric: true }) > 0
  }

  const botView = () => ({
    ...bot,
    available: bot.phase !== 'installed' && bot.phase !== 'updated' && bot.latest !== null && newer(bot.latest, bot.current),
  })
  const dshView = () => (dsh === null ? null : {
    ...dsh,
    available: dsh.phase !== 'installed' && dsh.current !== null && dsh.verified !== null && dsh.verified !== dsh.current
      && !(dsh.blockedBy !== null && newer(dsh.blockedBy, bot.current)),
  })

  const finishLater = (target, version) => {
    const fail = failNext
    failNext = false
    setTimeout(() => {
      if (target.phase !== 'installing') return
      if (fail) Object.assign(target, { phase: 'failed', error: 'npm exited 1 — npm ERR! network request failed' })
      else Object.assign(target, { phase: 'installed', installed: version })
    }, installMs).unref?.()
  }

  const endpoints = {
    'update-check': (p) => {
      if (p.fresh === true) {
        bot.checkedAt = Date.now()
        if (dsh) dsh.checkedAt = Date.now()
      }
      return { ...botView(), dsh: dshView() }
    },
    'update-install': () => {
      if (bot.phase === 'installing') return botView()
      if (!botView().available) throw refused('No DS Bot update is available')
      Object.assign(bot, { phase: 'installing', error: null })
      finishLater(bot, bot.latest)
      return botView()
    },
    'dsh-update-install': () => {
      if (dsh === null) return undefined
      if (dsh.phase === 'installing') return dshView()
      if (!dshView().available) throw refused('No DSH update is available')
      if (dsh.kind !== 'npm') throw refused('This DSH updates with its app')
      Object.assign(dsh, { phase: 'installing', error: null })
      finishLater(dsh, dsh.verified)
      return dshView()
    },
    'repo-stars': () => {
      if (!starsEnabled) throw Object.assign(new Error('Unknown endpoint repo-stars'), { code: 'bot/not-found' })
      return stars
    },
    diagnostics: () => ({
      facts: { botVersion: bot.current, dshVersion: dsh?.current ?? undefined, os: 'linux 6.6.87 x64', node: 'v24.22.3', team: '3 Bots, 1 Main, 1 group chat', models: 'deepseek/deepseek-chat' },
      entries: entries.slice(-50).reverse(),
      total: entries.length,
      markdown: '| | |\n|---|---|',
      issuesUrl: `${REPO}/issues/new`,
    }),
    'diagnostics-log': () => entries
      .map((e) => `${new Date(e.time).toISOString()} ${e.level.toUpperCase()} ${e.source}${e.bot ? ` (${e.bot}${e.where ? ` · ${e.where}` : ''})` : ''}${e.code ? ` [${e.code}${e.status ? ` ${e.status}` : ''}]` : ''} ${e.text}${e.stack ? `\n${e.stack.replace(/^/gm, '    ')}` : ''}`)
      .join('\n'),
    'diagnostics-clear': () => {
      entries = []
      return true
    },
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

  // /mock/system：?enabled=0|1（全部系统端点不支持）；?stars=0|1；?installMs=<毫秒>；?fail=1（下一次安装失败）；
  // ?source=registry|git|path|app|tarball；?checks=0|1（更新检查开关）；?latest=<版本|none>；
  // ?dshKind=npm|app；?dshVerified=<版本|none>；?blockedBy=<版本|none>；?noDsh=1；
  // ?restart=1（模拟重启 DSH：已安装的版本成为当前版本、phase 回到 idle/updated）；?reset=1；?resetCalls=1
  function set(params) {
    const flag = (k, fn) => { const v = params.get(k); if (v === '0' || v === '1') fn(v === '1') }
    const text = (k) => { const v = params.get(k); return v === null ? undefined : v === 'none' ? null : v }
    if (params.get('reset') === '1') reset()
    flag('enabled', (v) => { enabled = v })
    flag('stars', (v) => { starsEnabled = v })
    // 上游关闭更新检查时从不检查：latest 保持 null、checkedAt 为 0
    flag('checks', (v) => { bot.enabled = v; if (!v) Object.assign(bot, { latest: null, checkedAt: 0 }) })
    if (params.get('fail') === '1') failNext = true
    // ?bulk=<n>：追加 n 条带栈帧的告警（约同真实服务 200 条上限、80 KB 日志的体量，验证完整日志渲染）
    const bulk = Number(params.get('bulk'))
    if (Number.isInteger(bulk) && bulk > 0) {
      const now = Date.now()
      for (let i = 0; i < bulk; i++) entries.push({ time: now - (bulk - i) * 1000, level: 'warn', source: 'host', text: `bulk warning ${i}: provider answered 502 Bad Gateway while refreshing the model catalog for route deepseek/deepseek-chat`, stack: 'at refresh (file:///~/.dsh/profiles/web/node_modules/ds-bot/src/host/models.js:120:11)\nat async load (file:///~/.dsh/profiles/web/node_modules/ds-bot/src/host/models.js:88:5)' })
      entries = entries.slice(-200)
    }
    const ms = Number(params.get('installMs'))
    if (params.has('installMs') && Number.isFinite(ms) && ms >= 0) installMs = ms
    if (params.get('source')) bot.source = params.get('source')
    if (text('latest') !== undefined) bot.latest = text('latest')
    if (params.get('noDsh') === '1') dsh = null
    if (dsh) {
      if (params.get('dshKind')) dsh.kind = params.get('dshKind')
      if (text('dshVerified') !== undefined) dsh.verified = text('dshVerified')
      if (text('blockedBy') !== undefined) dsh.blockedBy = text('blockedBy')
    }
    if (params.get('restart') === '1') {
      if (bot.phase === 'installed') Object.assign(bot, { current: bot.installed, phase: 'updated' })
      if (dsh && dsh.phase === 'installed') Object.assign(dsh, { current: dsh.installed, phase: 'idle', installed: null })
    }
    if (params.get('resetCalls') === '1') calls.length = 0
    return { enabled, starsEnabled, installMs, failNext, bot: botView(), dsh: dshView(), entries: entries.length, calls }
  }

  return { handle, set }
}
