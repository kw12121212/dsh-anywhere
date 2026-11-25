// mock-dsh-server --manage 的用量（dsbot-usage 模拟器验收）：按 ds-bot 0.3.10 usage.js（usageView、hourKeys）与
// mobile.js（usageSummary、totals）源码核对的形态，从一份确定性的调用记录（覆盖约 13 个月）按请求的 timeZone 聚合。
// 调用记录只记端点与参数键，不记 Bot id 或时区。
const SLOT_MS = 15 * 60_000

// 与 usage.js hourKeys 同法：按时区取 'YYYY-MM-DD HH'，非法时区回退到本机时区
function hourKeys(timeZone) {
  let format
  try {
    format = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', hourCycle: 'h23' })
  } catch {
    format = new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', hourCycle: 'h23' })
  }
  return (time) => {
    const part = Object.fromEntries(format.formatToParts(new Date(Math.floor(time / SLOT_MS) * SLOT_MS)).map(({ type, value }) => [type, value]))
    return `${part.year}-${part.month}-${part.day} ${part.hour}`
  }
}

const MODELS = [
  { key: 'deepseek/v4', provider: 'deepseek', model: 'v4', name: 'DeepSeek V4', providerName: 'DeepSeek' },
  { key: 'deepseek/v4-flash', provider: 'deepseek', model: 'v4-flash', name: 'DeepSeek V4 Flash', providerName: 'DeepSeek' },
  { key: 'glm/glm-5.3', provider: 'glm', model: 'glm-5.3', name: 'GLM 5.3', providerName: 'GLM Coding Plan' },
  { key: 'local-claude-code/default', provider: 'local-claude-code', model: 'default', name: 'Claude Code', providerName: 'Claude Code' },
  // 不在模型目录中的模型：上游 name 退回模型 id、无 providerName
  { key: 'openrouter/qwen-4', provider: 'openrouter', model: 'qwen-4', name: 'qwen-4' },
  { key: 'openrouter/kimi-k3', provider: 'openrouter', model: 'kimi-k3', name: 'kimi-k3' },
  { key: 'moonshot/k3-long', provider: 'moonshot', model: 'k3-long', name: 'k3-long' },
  { key: 'zhipu/glm-air', provider: 'zhipu', model: 'glm-air', name: 'glm-air' },
  { key: 'minimax/m3', provider: 'minimax', model: 'm3', name: 'm3' },
]
const KINDS = ['chat', 'group', 'compaction', 'image', 'memory', 'session-title', 'side', 'other']

export function createUsage({ team }) {
  let enabled = true
  let summary = true
  let scanning = false
  let empty = false
  const calls = []
  let events = []

  // owner 键：Bot 为 id，群聊为 room:<id>，Agent 为 agent:<id>，其余 other；已删除对象保留记录时的名称
  const OWNERS = [
    { key: 'bot-1' }, { key: 'bot-2' }, { key: 'bot-3' }, { key: 'bot-4' }, { key: 'bot-5' }, { key: 'bot-6' },
    { key: 'bot-old', stored: '旧助理' },
    { key: 'agent:agent-gone', stored: 'DSH Agent' },
    { key: 'room:room-1' }, { key: 'room:room-2' },
    { key: 'other' },
  ]

  function reset() {
    let seed = 20261125
    const rnd = (n) => { seed = (seed * 1103515245 + 12345) % 2147483648; return Math.floor(seed / 65536) % n }
    const now = Date.now()
    const start = now - 400 * 24 * 3600_000
    events = []
    for (let t = start; t <= now; t += (60 + rnd(240)) * 60_000) {
      // 较早的记录稀疏一些，越近越密；bot-1 与 DeepSeek V4 用得最多
      if (now - t > 120 * 24 * 3600_000 && rnd(3) > 0) continue
      const owner = rnd(4) === 0 ? 0 : rnd(OWNERS.length)
      const model = rnd(3) === 0 ? 0 : rnd(MODELS.length)
      const kind = rnd(2) === 0 ? 0 : rnd(KINDS.length)
      const scale = 1 + rnd(40)
      events.push({ time: t, owner, model, kind, input: scale * 900 + rnd(500), cacheRead: scale * 4200 + rnd(900), cacheWrite: rnd(5) === 0 ? scale * 60 : 0, output: scale * 150 + rnd(80), calls: 1 + rnd(3) })
    }
  }
  reset()

  function describe(key, stored) {
    if (key === 'other') return { key, kind: 'other' }
    if (key.startsWith('room:')) {
      const id = key.slice(5)
      const room = team.rooms[id]
      return { key, kind: 'room', id, name: room?.name ?? stored ?? '', ...(room ? {} : { deleted: true }) }
    }
    if (key.startsWith('agent:')) {
      const id = key.slice(6)
      const agent = team.agents[id]
      return { key, kind: 'agent', id, name: agent?.name ?? stored ?? '', ...(agent ? {} : { deleted: true }) }
    }
    const bot = team.bots[key]
    return { key, kind: 'bot', id: key, name: bot?.name ?? stored ?? '', ...(bot ? {} : { deleted: true }) }
  }

  function view(timeZone) {
    const hourOf = hourKeys(typeof timeZone === 'string' ? timeZone.slice(0, 64) : undefined)
    const owners = []
    const ownerIndex = new Map()
    const models = []
    const modelIndex = new Map()
    const kinds = []
    const kindIndex = new Map()
    const at = (map, list, key, make) => {
      if (!map.has(key)) { map.set(key, list.length); list.push(make()) }
      return map.get(key)
    }
    const buckets = new Map()
    // scanning：模拟仍在读旧会话（只给最近 20 天），总数偏小
    const cutoff = scanning ? Date.now() - 20 * 24 * 3600_000 : -Infinity
    for (const e of empty ? [] : events) {
      if (e.time < cutoff) continue
      const o = OWNERS[e.owner]
      const row = [hourOf(e.time), at(ownerIndex, owners, o.key, () => describe(o.key, o.stored)), at(modelIndex, models, MODELS[e.model].key, () => ({ ...MODELS[e.model] })), at(kindIndex, kinds, KINDS[e.kind], () => KINDS[e.kind])]
      const id = row.join('\n')
      const bucket = buckets.get(id) ?? [...row, 0, 0, 0, 0, 0]
      bucket[4] += e.input
      bucket[5] += e.cacheRead
      bucket[6] += e.cacheWrite
      bucket[7] += e.output
      bucket[8] += e.calls
      buckets.set(id, bucket)
    }
    return { owners, models, kinds, rows: [...buckets.values()].sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)), now: hourOf(Date.now()), scanning }
  }

  const totals = (rows) => rows.reduce((sum, row) => ({
    input: sum.input + row[4], cacheRead: sum.cacheRead + row[5], cacheWrite: sum.cacheWrite + row[6], output: sum.output + row[7], calls: sum.calls + row[8],
  }), { input: 0, cacheRead: 0, cacheWrite: 0, output: 0, calls: 0 })

  function usageSummary(p) {
    const botId = String(p.botId ?? '')
    if (!team.bots[botId]) throw Object.assign(new Error('Unknown Bot'), { code: 'bot/not-found' })
    const timeZone = typeof p.timeZone === 'string' ? p.timeZone.slice(0, 64) : undefined
    const v = view(timeZone)
    const owner = v.owners.findIndex((entry) => entry.kind === 'bot' && entry.id === botId)
    const rows = owner < 0 ? [] : v.rows.filter((row) => row[1] === owner)
    const hourOf = hourKeys(timeZone)
    const since = (hours) => hourOf(Date.now() - (hours - 1) * 3_600_000)
    const within = (hours) => rows.filter((row) => row[0] >= since(hours))
    const month = within(24 * 30)
    const models = v.models.map((model, index) => ({ key: model.key, name: model.name, ...totals(month.filter((row) => row[2] === index)) }))
      .filter((model) => model.calls > 0 || model.input > 0 || model.output > 0)
    return { botId, day: totals(within(24)), week: totals(within(24 * 7)), month: totals(month), models, scanning }
  }

  /** 本模块负责的端点；其他端点返回 null。enabled=false 模拟不支持 usage 的旧版远端；summary=false 时 usage-summary 同样未知。 */
  function handle(endpoint, p, ok) {
    if (endpoint !== 'usage' && endpoint !== 'usage-summary') return null
    calls.push({ endpoint, keys: Object.keys(p ?? {}).sort(), at: Date.now() })
    const unknown = { ok: false, error: { code: 'bot/not-found', message: `Unknown endpoint ${endpoint}`, details: {} } }
    if (endpoint === 'usage') return enabled ? ok(view(p?.timeZone)) : unknown
    if (!summary) return unknown
    try {
      return ok(usageSummary(p ?? {}))
    } catch (error) {
      return { ok: false, error: { code: error.code ?? 'bot/refused', message: error.message, details: {} } }
    }
  }

  // /mock/usage：?enabled=0|1；?summary=0|1（同时控制 hello 是否声明 usage-summary）；?scanning=0|1；?empty=0|1；?reset=1；?resetCalls=1
  function set(params) {
    for (const [name, apply] of [['enabled', (v) => { enabled = v }], ['summary', (v) => { summary = v }], ['scanning', (v) => { scanning = v }], ['empty', (v) => { empty = v }]]) {
      const v = params.get(name)
      if (v === '0' || v === '1') apply(v === '1')
    }
    if (params.get('reset') === '1') reset()
    if (params.get('resetCalls') === '1') calls.length = 0
    return { enabled, summary, scanning, empty, events: events.length, calls }
  }

  return { handle, set, summaryOn: () => summary }
}
