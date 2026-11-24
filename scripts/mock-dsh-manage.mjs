// mock-dsh-server 的 --manage 模式（dsbot-native-manage 里程碑模拟器验收）：可变的内存团队，
// 按 DS-Bot 0.3.10 源码核对的形态实现管理端点（channel.js / roster.js / privacy.js）。每次写入 revision+1
// 并经 feed 推 revision 事件；拒绝时回 {ok:false, error:{code:'bot/refused', message}}（上游 channel.js errorCode 对普通 Error 的映射）。
// 日志只记端点与参数键，不打印正文。
import { createHash } from 'node:crypto'
import { deflateSync } from 'node:zlib'
import { createConnectors } from './mock-dsh-connectors.mjs'
import { createIm } from './mock-dsh-im.mjs'
import { createLocalAgents } from './mock-dsh-local-agents.mjs'
import { createProviders } from './mock-dsh-providers.mjs'

const MODELS = [
  { ref: 'deepseek/v4', provider: 'deepseek', providerName: 'DeepSeek', id: 'v4', name: 'DeepSeek V4', input: ['text'], reasoning: { efforts: [{ id: 'low', name: 'Low' }, { id: 'high', name: 'High' }, { id: 'max', name: 'Max' }] } },
  { ref: 'deepseek/v4-flash', provider: 'deepseek', providerName: 'DeepSeek', id: 'v4-flash', name: 'DeepSeek V4 Flash', input: ['text'] },
  { ref: 'glm/glm-5.3', provider: 'glm', providerName: 'GLM Coding Plan', id: 'glm-5.3', name: 'GLM 5.3', input: ['text'], needsKey: true },
  { ref: 'local-claude-code/default', provider: 'local-claude-code', providerName: 'Claude Code', id: 'default', name: 'Claude Code' },
]
const EFFORTS = { 'deepseek/v4': { efforts: MODELS[0].reasoning.efforts } }

export function createManageTeam(pushRevision, pushLive = () => {}) {
  const team = {
    revision: 100,
    mainBotIds: ['bot-1'],
    bots: {
      'bot-1': { id: 'bot-1', name: '研究主管', role: '研究', instructions: '负责团队研究计划。', color: 'deepseek', avatar: { shape: 'whale' }, model: 'deepseek/v4', appliedModel: 'deepseek/v4', effort: 'high', capabilities: ['notes', 'web-pack'], packs: [] },
      'bot-2': { id: 'bot-2', name: '运维值班', role: '运维', instructions: '处理告警。', model: 'deepseek/v4-flash', appliedModel: 'deepseek/v4-flash', workDir: '/work/ops', sectionId: 'sec-1', capabilities: [], packs: ['local-fs'] },
      'bot-3': { id: 'bot-3', name: '写作搭档', role: '写作', instructions: '', model: 'deepseek/v4', appliedModel: 'deepseek/v4', workDir: '/work/writing', permission: 'workspace-write' },
      'bot-4': { id: 'bot-4', name: '采购助理', role: '', instructions: '', model: 'deepseek/v4-flash', appliedModel: 'deepseek/v4-flash' },
      // 工作文件夹已在电脑上被删除（不在目录树里）：选择页起点读不到时应退回主目录（dsbot-folders）
      // 私有 Bot：不能进群（dsbot-group-manage 候选列表不显示）
      'bot-6': { id: 'bot-6', name: '私密助理', role: '', instructions: '', model: 'deepseek/v4-flash', appliedModel: 'deepseek/v4-flash', workDir: '/home/user/notes', private: true },
      'bot-5': { id: 'bot-5', name: '归档助理', role: '归档', instructions: '', model: 'deepseek/v4-flash', appliedModel: 'deepseek/v4-flash', workDir: '/work/gone' },
    },
    rooms: {
      'room-1': { id: 'room-1', name: '项目群', named: true, color: 'violet', members: ['bot-1', 'bot-2'], admin: 'bot-1', mode: 'admin', notice: '' },
      'room-2': { id: 'room-2', name: '闲聊群', named: true, members: ['bot-3', 'bot-4'], admin: null, mode: 'everyone', notice: '周五下午茶' },
    },
    sections: [{ id: 'sec-1', name: '常用' }],
    privateArchives: [{ id: 'old-1', name: '旧私有助理', workDir: '/work/old-private' }],
    seq: 10,
    // 团队的 DSH Agent（dsbot-member-agents）：至多一个；chats 按会话存条目，titles 存会话标题，running 表示工作中
    agents: {},
    agentChats: {},
    agentTitles: {},
    agentRunning: {},
    // 团队偏好（dsbot-prefs-theme）：初始主题 Paper、网页端设过消息宽度 90%（手机改其他项时必须保留）
    prefs: { theme: 'paper', width: 90 },
  }
  const library = []
  // 能力库（dsbot-capabilities，ds-bot 0.3.10 capabilities.js 形态）：web-pack 含 search 与 notes；local-fs 为运维值班提议、未批准的 stdio MCP；
  // search 的 mockError 模拟连接失败（live capabilityStatuses 为 error）。enabled=false 时 state 不含能力库字段（旧版远端）
  const caps = {
    enabled: true,
    items: {
      'notes': { name: 'notes', kind: 'skill', description: '记笔记的约定', createdBy: null, createdAt: 1, updatedAt: 1, content: '# 笔记\n把要点写进 notes.md。' },
      'search': { name: 'search', kind: 'mcp', description: '网页搜索', createdBy: null, createdAt: 1, updatedAt: 1, transport: 'streamable-http', url: 'https://mcp.example.com/search', headers: { Authorization: 'Bearer $DSH_SECRET_SEARCH_KEY' }, approved: true, mockError: 'HTTP 401 Unauthorized' },
      'web-pack': { name: 'web-pack', kind: 'pack', description: '上网三件套', createdBy: null, createdAt: 1, updatedAt: 1, items: ['search', 'notes'] },
      'local-fs': { name: 'local-fs', kind: 'mcp', description: '', createdBy: 'bot-2', createdAt: 2, updatedAt: 2, transport: 'stdio', command: 'npx', args: ['-y', '@modelcontextprotocol/server-filesystem', '/work/ops'], env: {}, approved: false },
    },
    calls: [],
  }
  const CAP_NAME = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/
  const capLeafStatus = (name) => {
    const item = caps.items[name]
    if (!item) return undefined
    if (item.kind === 'mcp' && item.transport === 'stdio' && item.approved !== true) return { state: 'needs-approval' }
    if (item.mockError) return { state: 'error', error: item.mockError }
    return { state: 'ready' }
  }
  const capSeverity = { error: 3, 'needs-approval': 2, connecting: 1, ready: 0 }
  // 上游 capabilityStatusesView：只为常驻挂载生成；能力包取子项最严重者
  function capabilityStatuses() {
    const out = {}
    for (const bot of Object.values(team.bots)) {
      const statuses = {}
      for (const name of bot.capabilities ?? []) {
        const item = caps.items[name]
        if (!item) continue
        const children = item.kind === 'pack' ? (item.items ?? []).map(capLeafStatus).filter(Boolean) : [capLeafStatus(name)].filter(Boolean)
        const worst = children.sort((a, b) => capSeverity[b.state] - capSeverity[a.state])[0]
        if (worst) statuses[name] = worst
      }
      out[bot.id] = statuses
    }
    return out
  }
  const capLibraryView = () => Object.values(caps.items).map(({ mockError, ...item }) => ({ ...item }))
  const agentTimers = {}
  // Bot 浏览器（dsbot-bot-browser，ds-bot 0.3.10 channel.js / browser.js / browser/session.js 形态）
  const browser = {
    mode: 'desktop', // 'off' | 'web' | 'desktop'
    openBots: {
      'bot-1': { url: 'https://example.com/dashboard', title: 'Example Dashboard', rgb: [40, 120, 220] },
    },
    holds: {},
    frameSeq: 0,
    frameCalls: 0,
    stopCalls: 0,
    lastGiveBack: null,
  }
  function makeMockPng(w, h, rgb) {
    const crc = (buf) => { let c = ~0; for (const b of buf) { c ^= b; for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1)) } return ~c >>> 0 }
    const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([len, td, c]) }
    const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2
    const row = Buffer.concat([Buffer.from([0]), Buffer.alloc(w * 3).map((_, i) => rgb[i % 3])])
    const raw = Buffer.concat(Array.from({ length: h }, () => row))
    return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))])
  }
  // live 键随 Agent 工作状态与浏览器持有状态变化（上游 liveAnswer 按 live 内容生成键）
  const liveKey = () => 'manage-' + team.revision + '-' + Object.keys(team.agentRunning).sort().join(',') + '-' + JSON.stringify(browser.holds) + '-' + JSON.stringify(caps.enabled ? capabilityStatuses() : {}) + '-' + conn.liveKey() + '-' + im.liveKey()
  // 工作文件夹（dsbot-folders，ds-bot 0.3.10 folders.js 形态）：内存目录树；/home/user/.dsh 是数据目录（guarded）
  const DIRS = new Set(['/', '/home', '/home/user', '/home/user/projects', '/home/user/projects/alpha', '/home/user/projects/beta',
    '/home/user/notes', '/home/user/.config', '/home/user/.dsh', '/home/user/.dsh/ds-bot', '/home/user/.dsh/ds-bot/workspace',
    '/home/user/big', ...Array.from({ length: 6 }, (_, i) => `/home/user/big/d${i + 1}`),
    '/work', '/work/ops', '/work/writing', '/work/old-private'])
  const DATA = '/home/user/.dsh'
  let teamDir = '/home/user/.dsh/ds-bot/workspace'
  const within = (inner, outer) => inner === outer || inner.startsWith(outer === '/' ? '/' : outer + '/')
  const parentOf = (p) => (p === '/' ? '/' : p.slice(0, p.lastIndexOf('/')) || '/')
  const nameOf = (p) => (p === '/' ? '/' : p.slice(p.lastIndexOf('/') + 1))
  const expand = (t) => (t === '~' ? '/home/user' : t.startsWith('~/') ? '/home/user' + t.slice(1) : t)
  // 上游 cleanWorkDir：完整路径、存在、是文件夹、不含也不在数据目录内（团队工作区在数据目录内是例外）；等于团队工作区时视为未设
  function cleanWorkDir(input) {
    const text = String(input ?? '').trim()
    if (text === '') return undefined
    const path = expand(text).replace(/\/+$/, '') || '/'
    if (!path.startsWith('/')) throw new Error(`Give the full path of the folder, not ${text}`)
    if (!DIRS.has(path)) throw new Error(`There is no folder at ${text}`)
    if (within(DATA, path) || (within(path, DATA) && !within(path, teamDir))) throw new Error(`${path} holds DeepSeek Harness data, so Bots cannot work there`)
    return path === teamDir ? undefined : path
  }
  const ROOM_MODES = ['everyone', 'admin']
  const PRESETS = ['read-only', 'workspace-write', 'danger-full-access']
  function checkMembers(members) {
    if (members.length < 2) throw new Error('A group chat needs at least two Bots')
    if (members.length > 12) throw new Error('A group chat holds at most 12 Bots')
    const priv = members.map((m) => team.bots[m]).find((b) => b?.private)
    if (priv) throw new Error(`${priv.name} is a private Bot and cannot be in a group chat: what is said in a group is read by every member`)
  }
  function cleanNotice(text) {
    const notice = String(text ?? '').trim()
    if (notice.length > 2000) throw new Error(`A group's notice can hold at most 2000 characters; this one has ${notice.length}`)
    return notice
  }
  function cleanRoomName(text, selfId) {
    const next = String(text ?? '').trim().replace(/\s+/g, ' ').slice(0, 60)
    if (next !== '' && Object.values(team.rooms).some((r) => r.id !== selfId && norm(r.name) === norm(next))) throw new Error(`Another group chat is already called "${next}"`)
    return next
  }
  const autoRoomName = (members) => ['You', ...members.map((m) => team.bots[m]?.name).filter(Boolean)].join(', ')
  // 记忆：按记忆键存条目（bot:<id> / group:<id> / team），变更日志与最近一次整理
  const memories = {
    'bot:bot-1': [
      { topic: null, text: '用户偏好用中文回复', id: 'm-1', added: '2026-11-20', by: null, source: 'conversation', until: null, pinned: false },
      { topic: 'work', text: '周报每周五下午发到项目群', id: 'm-2', added: '2026-11-21', by: 'user', source: 'memory panel', until: '2026-12-31', pinned: true },
      { topic: null, text: '这是一条很长的记忆，用来验证长文本可以展开与收起：' + '项目计划、风险清单、里程碑安排与每周例会的记录要点都写在共享文件夹里，需要时先查最新版本再回答。'.repeat(3), id: 'm-5', added: '2026-11-22', by: null, source: 'conversation', until: null, pinned: false },
    ],
    team: [
      { topic: null, text: '团队仓库在 /work/repo', id: 'm-3', added: '2026-11-01', by: null, source: 'review', until: '2026-11-10', pinned: false },
      { topic: 'shared', text: '发布前要跑完整回归', id: 'm-4', added: '2026-11-02', by: null, source: 'review', until: null, pinned: false },
    ],
    'group:room-1': [{ topic: null, text: '项目群每周一早上同步进度', id: 'm-6', added: '2026-11-15', by: null, source: 'conversation', until: null, pinned: false }],
  }
  const memJournal = [{ id: 'h-0', key: 'bot:bot-1', scope: 'own', op: 'remove', text: '旧的会议时间是周三', time: '2026-11-19T08:00:00.000Z', entry: { topic: null, text: '旧的会议时间是周三', id: 'm-0', added: '2026-11-10', by: null, source: 'conversation', until: null, pinned: false } }]
  let memTidy = { key: 'bot:bot-1', scope: 'own', batch: 'tidy-1', at: '2026-11-23T02:00:00.000Z', merged: 0, moved: 0, retired: 1, items: [{ op: 'retired', text: '被淘汰的旧会议时间' }] }
  const memStore = (key) => (memories[key] ??= [])
  const memPanel = (p) => {
    if (p.roomId !== undefined && p.roomId !== null) {
      if (!team.rooms[p.roomId]) throw new Error('Unknown group chat')
      return { body: { roomId: p.roomId }, scopes: { group: 'group:' + p.roomId } }
    }
    if (!team.bots[p.botId]) throw new Error('Unknown Bot')
    return { body: { botId: p.botId }, scopes: { own: 'bot:' + p.botId, team: 'team' } }
  }
  const memText = (raw) => {
    const text = String(raw ?? '').trim()
    if (!text) throw new Error('A memory entry needs some text')
    if (text.length > 300) throw new Error('A memory entry holds at most 300 characters')
    return text
  }
  const memFind = (p) => {
    const key = memPanel(p).scopes[p.scope]
    const e = memStore(key ?? '').find((x) => x.text === p.text && (x.topic ?? null) === (p.topic ?? null))
    if (!e) throw new Error('This entry changed or moved. Reload the memory and try again.')
    return e
  }
  const memLog = (op, p, text, before, entry) => {
    const key = memPanel(p).scopes[p.scope]
    memJournal.push({ id: 'h-' + ++team.seq, key, scope: p.scope, op, text, ...(before !== undefined ? { before } : {}), ...(entry ? { entry: { ...entry } } : {}), time: new Date().toISOString() })
  }
  function memoryView(p) {
    const panel = memPanel(p)
    const entries = Object.entries(panel.scopes).flatMap(([scope, key]) => memStore(key).map((e) => ({ scope, ...e, expired: e.until !== null && e.until < '2026-11-24' })))
    const tidy = memTidy && Object.values(panel.scopes).includes(memTidy.key) ? { scope: memTidy.scope, batch: memTidy.batch, at: memTidy.at, merged: memTidy.merged, moved: memTidy.moved, retired: memTidy.retired, items: memTidy.items } : null
    return {
      ...panel.body, entries, sizes: {}, limits: { entry: 300, group: 12000, bot: 8000, team: 4000, topic: 16000, topics: 40 },
      summary: null, fresh: true, summarizing: false, tidy, keys: panel.scopes,
      places: [...Object.values(team.bots).map((b) => ({ key: 'bot:' + b.id, kind: 'bot', name: b.name })), ...Object.values(team.rooms).map((r) => ({ key: 'group:' + r.id, kind: 'group', name: r.name }))],
      error: null,
    }
  }
  const fail = (message) => ({ ok: false, error: { code: 'bot/refused', message, details: {} } })
  const ok = (value) => ({ ok: true, value })
  // 上游 invalid()（参数不合法，如 set-prefs 的取值校验）
  const invalid = (message) => ({ ok: false, error: { code: 'bot/invalid', message, details: {} } })
  const norm = (s) => String(s ?? '').trim().toLowerCase()
  const engine = (ref) => (!ref ? 'model' : String(ref).split('/')[0].startsWith('local-') ? String(ref).split('/')[0] : 'model')
  const inGroup = (id) => Object.values(team.rooms).some((r) => r.members.includes(id))
  // 以 held 开头的名称模拟上游「正在另一处创建中、被保留」的名称（roster.js isHeld）：客户端看不到，只能由服务端拒绝
  const nameTaken = (name, self) => /^held/i.test(String(name).trim()) || Object.values(team.bots).some((b) => b.id !== self && norm(b.name) === norm(name))
  const bump = () => { team.revision += 1; pushRevision(team.revision) }
  // 连接器与邮箱（dsbot-connectors-mail，见 mock-dsh-connectors.mjs）
  const conn = createConnectors({ bump, botExists: (id) => Boolean(team.bots[id]) })
  // 本地 Agent（dsbot-local-agents，见 mock-dsh-local-agents.mjs）：不在 state 中
  const localAgents = createLocalAgents()
  // 模型服务商（dsbot-models-providers，见 mock-dsh-providers.mjs）：也是 state.models 目录的来源
  const providers = createProviders()
  // IM 接入（dsbot-im-bridges，见 mock-dsh-im.mjs）
  const im = createIm({ bump, botExists: (id) => Boolean(team.bots[id]), roomExists: (id) => Boolean(team.rooms[id]) })

  function view() {
    return {
      revision: team.revision,
      liveKey: liveKey(),
      mainBotId: team.mainBotIds[0] ?? null,
      mainBotIds: team.mainBotIds,
      bots: Object.values(team.bots).map((b) => ({ ...b, sessionId: 's-' + b.id, parts: [] })),
      rooms: Object.values(team.rooms).map((r) => ({ ...r, sessionId: r.id, parts: [] })),
      sections: team.sections,
      privateArchives: team.privateArchives,
      models: providers.catalog(),
      modelsKey: providers.modelsKey(),
      defaultModel: 'deepseek/v4',
      permissions: { options: [{ value: 'read-only', name: '只读' }, { value: 'workspace-write', name: '工作区可写' }, { value: 'danger-full-access', name: '完全访问' }], defaultPreset: 'danger-full-access' },
      colors: ['deepseek', 'blue', 'violet', 'orange', 'green', 'magenta', 'cyan', 'red', 'yellow', 'brown', 'gray', 'black', 'aurora'],
      prefs: { ...team.prefs },
      browser: browser.mode === 'desktop',
      browserHolds: { ...browser.holds },
      memory: true,
      agents: Object.values(team.agents),
      work: { bots: {}, rooms: {}, approvals: [], agents: Object.fromEntries(Object.keys(team.agentRunning).map((id) => [id, { since: team.agentRunning[id] }])) },
      ...(caps.enabled ? { capabilities: capLibraryView(), capabilityStatuses: capabilityStatuses() } : {}),
      ...conn.view(),
      ...im.view(),
    }
  }

  const SECTION_COLORS = ['blue', 'orange', 'green', 'violet', 'rose']
  const SECTION_NAME_MAX = 32
  const unquote = (s) => String(s ?? '').trim().replace(/^["'“”「『《]+|["'“”」』》]+$/g, '').trim()
  function cleanSectionName(text, selfId) {
    const next = unquote(text).replace(/\s+/g, ' ').slice(0, SECTION_NAME_MAX)
    if (next === '') throw new Error('Enter a section name')
    if (team.sections.some((sec) => sec.id !== selfId && norm(sec.name) === norm(next))) {
      throw new Error(`Another section is already called "${next}"`)
    }
    return next
  }
  function freshSectionName(base = 'New section') {
    const stem = unquote(base).replace(/\s+/g, ' ').slice(0, SECTION_NAME_MAX - 4) || 'New section'
    const taken = (n) => team.sections.some((sec) => norm(sec.name) === norm(n))
    if (!taken(stem)) return stem
    let index = 2
    while (taken(`${stem} ${index}`)) index += 1
    return `${stem} ${index}`
  }

  function createBot(p) {
    const name = String(p.name ?? '').trim().slice(0, 40)
    if (!name) throw new Error('A Bot needs a name')
    if (nameTaken(name)) throw new Error(`A Bot named ${name} already exists`)
    if (p.effort && !p.model) throw new Error('Pick a model before choosing a thinking level')
    const id = 'bot-' + ++team.seq
    team.bots[id] = {
      id, name, role: String(p.role ?? '').trim().slice(0, 24), instructions: String(p.instructions ?? '').trim(),
      ...(p.color ? { color: p.color } : {}), ...(p.avatar ? { avatar: p.avatar } : {}),
      model: p.model ?? 'deepseek/v4', appliedModel: p.model ?? 'deepseek/v4', ...(p.effort ? { effort: p.effort } : {}),
      ...(p.workDir ? { workDir: p.workDir } : {}),
    }
    return team.bots[id]
  }

  function updateBot(p) {
    const bot = team.bots[p.id]
    if (!bot) throw new Error('Unknown Bot')
    // 文件夹先校验（上游先检查，失败时其余字段也不改）
    const moving = p.workDir !== undefined
    const workDir = moving ? cleanWorkDir(p.workDir) : undefined
    if (p.private === true) {
      if (team.mainBotIds.includes(bot.id)) throw new Error(`${bot.name} is a Main Bot and cannot be private`)
      if (inGroup(bot.id)) throw new Error(`${bot.name} is in a group chat and cannot be private`)
      if (!bot.workDir) throw new Error(`${bot.name} needs a working folder of its own to be private`)
    }
    if (p.model !== undefined && p.model !== null && engine(p.model) !== engine(bot.model ?? bot.appliedModel) && p.changeEngine !== true) {
      throw new Error(`Changing what ${bot.name} runs on needs a confirmation in the Bot's settings`)
    }
    if (p.name !== undefined) {
      const next = String(p.name).trim().slice(0, 40)
      if (!next || nameTaken(next, bot.id)) throw new Error(`Name ${next} is not available`)
      bot.name = next
    }
    if (p.role !== undefined) bot.role = String(p.role).trim().slice(0, 24)
    if (p.instructions !== undefined) bot.instructions = String(p.instructions).trim()
    if (p.color !== undefined) { if (p.color === null) delete bot.color; else bot.color = p.color }
    if (p.avatar !== undefined) { if (p.avatar === null) delete bot.avatar; else bot.avatar = p.avatar }
    // 上游换模型保留 effort（新模型不支持时按模型默认）
    if (p.model !== undefined) { bot.model = p.model; bot.appliedModel = p.model }
    if (p.effort !== undefined) { if (p.effort === null || p.effort === '') delete bot.effort; else bot.effort = p.effort }
    if (p.permission !== undefined) bot.permission = p.permission
    if (p.private !== undefined) { if (p.private) bot.private = true; else delete bot.private }
    if (moving) { if (workDir) bot.workDir = workDir; else delete bot.workDir }
    return bot
  }

  function handle(endpoint, p) {
    const connected = conn.handle(endpoint, p, ok, fail) ?? im.handle(endpoint, p, ok, fail) ?? localAgents.handle(endpoint, p, ok, fail) ?? providers.handle(endpoint, p, ok, fail)
    if (connected) return connected
    try {
      switch (endpoint) {
        case 'state':
          // 上游 channel.js：since 命中且 live 键相同只回三键；live 键变了附带 live 字段（work / browserHolds 等）
          // 上游：since 命中且 models 未给或与当前目录签名相同才回 unchanged
          if (p.since === team.revision && (p.models === undefined || p.models === providers.modelsKey())) return ok(p.live === liveKey() ? { revision: team.revision, unchanged: true, liveKey: liveKey() } : { revision: team.revision, unchanged: true, liveKey: liveKey(), work: view().work, browserHolds: view().browserHolds, ...(caps.enabled ? { capabilityStatuses: capabilityStatuses() } : {}) })
          return ok(view())
        // DSH Agent（dsbot-member-agents，ds-bot 0.3.10 agents.js / parts.js agentPage / turns.js stopAgent 形态）
        case 'hello':
          return ok({ api: 1, apiMin: 1, apiMax: 1, plugin: { name: 'ds-bot', version: '0.3.10' }, capabilities: ['state', 'chat', 'room-chat', 'agents', 'send', 'feed', 'stop-agent', 'stop-chat'], boot: 'manage', seq: 0, revision: team.revision })
        case 'add-agent': {
          if (Object.keys(team.agents).length > 0) throw new Error('The team already has a DSH Agent')
          const id = 'agent-' + ++team.seq
          const sid = 'as-' + ++team.seq
          team.agents[id] = { id, name: 'DSH Agent', sessions: [sid], createdAt: Date.now() }
          team.agentChats[sid] = []
          bump()
          return ok(team.agents[id])
        }
        case 'agent-chat': {
          const agent = team.agents[p.agentId]
          if (!agent) return { ok: false, error: { code: 'bot/not-found', message: 'Unknown DSH Agent', details: {} } }
          const sid = agent.sessions.at(-1)
          if (sid === undefined) return ok({ items: [], cursor: null })
          return ok({ sessionId: sid, items: team.agentChats[sid] ?? [], cursor: null })
        }
        case 'agent-session': {
          const agent = team.agents[p.agentId]
          if (!agent) throw new Error('Unknown DSH Agent')
          const sid = 'as-' + ++team.seq
          agent.sessions.push(sid)
          team.agentChats[sid] = []
          bump()
          return ok({ sessionId: sid })
        }
        case 'agent-archive': {
          const agent = team.agents[p.agentId]
          if (!agent) throw new Error('Unknown DSH Agent')
          if (!agent.sessions.includes(p.sessionId)) throw new Error('Session does not belong to the DSH Agent')
          agent.sessions = agent.sessions.filter((s) => s !== p.sessionId)
          bump()
          return ok(true)
        }
        case 'agent-rename': {
          const agent = team.agents[p.agentId]
          if (!agent) throw new Error('Unknown DSH Agent')
          if (!agent.sessions.includes(p.sessionId)) throw new Error('Session does not belong to the DSH Agent')
          const title = String(p.title ?? '').trim()
          if (title === '') throw new Error('A session needs a title')
          team.agentTitles[p.sessionId] = title.slice(0, 80)
          return ok(true)
        }
        case 'remove-agent': {
          if (!team.agents[p.agentId]) throw new Error('Unknown DSH Agent')
          delete team.agents[p.agentId]
          delete team.agentRunning[p.agentId]
          bump()
          return ok(view())
        }
        case 'stop-agent': {
          const was = team.agentRunning[p.agentId] !== undefined
          delete team.agentRunning[p.agentId]
          clearTimeout(agentTimers[p.agentId])
          if (was) pushLive()
          return ok(was)
        }
        case 'send': {
          // 只接管发往 Agent 会话的消息；其余交回 mock 主体（Bot 私聊）
          const agent = Object.values(team.agents).find((a) => a.sessions.includes(p.sessionId))
          if (!agent) return null
          const chat = team.agentChats[p.sessionId]
          const seq = chat.length + 1
          chat.push({ kind: 'user', text: String(p.text ?? ''), turn: seq, seq, time: Date.now() })
          team.agentRunning[agent.id] = Date.now()
          pushLive()
          // 12 秒后回复（留出经 adb 点停止的时间）；回复后推 live（Agent 会话没有 stream/message）
          agentTimers[agent.id] = setTimeout(() => {
            if (team.agentRunning[agent.id] === undefined) return
            chat.push({ kind: 'bot', text: `收到：${p.text}`, turn: seq, seq: seq + 1, time: Date.now() })
            delete team.agentRunning[agent.id]
            pushLive()
          }, 12000)
          return ok(true)
        }
        // 记忆（dsbot-memory，ds-bot 0.3.10 memory.js 形态）：条目以 (scope, topic, text) 定位；写后返回最新视图
        case 'memory-view':
          return ok(memoryView(p))
        case 'memory-history': {
          const panel = memPanel(p)
          const keys = new Set(Object.values(panel.scopes))
          const rows = memJournal.filter((r) => keys.has(r.key)).slice().reverse()
          const at = p.before ? rows.findIndex((r) => r.id === p.before) : -1
          const slice = p.before ? (at === -1 ? [] : rows.slice(at + 1)) : rows
          const page = slice.slice(0, 50)
          const undone = new Set(memJournal.map((r) => r.restored).filter(Boolean))
          return ok({ rows: page.map((r) => ({ id: r.id, time: r.time, op: r.op, scope: r.scope, text: r.text, by: 'user', restored: undone.has(r.id) })), more: slice.length > 50 ? page.at(-1).id : null })
        }
        case 'memory-pin': {
          const e = memFind(p)
          e.pinned = p.pin === true
          return ok(memoryView(p))
        }
        case 'memory-edit': {
          const e = memFind(p)
          const next = memText(p.next)
          memLog('replace', p, next, e.text)
          e.text = next
          return ok(memoryView(p))
        }
        case 'memory-add': {
          const panel = memPanel(p)
          const scope = panel.scopes[p.scope] ? p.scope : Object.keys(panel.scopes)[0]
          const text = memText(p.text)
          memStore(panel.scopes[scope]).push({ topic: p.topic ?? null, text, id: 'm-' + ++team.seq, added: '2026-11-24', by: 'user', source: 'memory panel', until: null, pinned: false })
          memLog('add', { ...p, scope }, text)
          return ok(memoryView(p))
        }
        case 'memory-forget': {
          const e = memFind(p)
          const list = memStore(memPanel(p).scopes[p.scope])
          list.splice(list.indexOf(e), 1)
          memLog('remove', p, e.text, undefined, e)
          return ok(memoryView(p))
        }
        case 'memory-move': {
          const e = memFind(p)
          const from = memStore(memPanel(p).scopes[p.scope])
          if (!p.to) throw new Error('Choose where to move the entry')
          from.splice(from.indexOf(e), 1)
          memStore(p.to).push({ ...e, topic: p.to === 'team' ? 'shared' : null })
          return ok(memoryView(p))
        }
        case 'memory-layer': {
          const e = memFind({ ...p, scope: 'team' })
          e.topic = p.always === true ? null : 'shared'
          return ok(memoryView(p))
        }
        case 'memory-restore': {
          const row = memJournal.find((r) => r.id === p.id)
          if (!row) throw new Error('That change is not in the change log of this memory.')
          if (memJournal.some((r) => r.restored === p.id)) throw new Error('That change is already restored.')
          const list = memStore(row.key)
          if (row.op === 'remove') list.push({ ...row.entry })
          else if (row.op === 'add') { const at = list.findIndex((e) => e.text === row.text); if (at >= 0) list.splice(at, 1) }
          else if (row.op === 'replace') { const e = list.find((x) => x.text === row.text); if (e) e.text = row.before }
          memJournal.push({ id: 'h-' + ++team.seq, key: row.key, scope: row.scope, op: row.op === 'add' ? 'remove' : row.op === 'remove' ? 'add' : 'replace', text: row.before ?? row.text, time: new Date().toISOString(), restored: row.id })
          return ok(memoryView(p))
        }
        case 'memory-tidy-undo': {
          if (!memTidy || memTidy.batch !== p.batch) throw new Error('This tidy can no longer be undone')
          memStore(memTidy.key).push({ topic: null, text: '被淘汰的旧会议时间', id: 'm-' + ++team.seq, added: '2026-11-01', by: null, source: 'conversation', until: null, pinned: false })
          memTidy = null
          return ok(memoryView(p))
        }
        case 'memory-ask': {
          // 模拟模型耗时 3 秒；回答并新增一条（与上游一样返回 {reply, changes, view}）
          const text = String(p.text ?? '').trim()
          if (!text) throw new Error('Type a question or a change first')
          return new Promise((resolve) => setTimeout(() => {
            try {
              const panel = memPanel(p)
              const scope = Object.keys(panel.scopes)[0]
              const added = `记住：${text}`.slice(0, 300)
              memStore(panel.scopes[scope]).push({ topic: null, text: added, id: 'm-' + ++team.seq, added: '2026-11-24', by: 'user', source: 'memory panel', until: null, pinned: false })
              memLog('add', { ...p, scope }, added)
              resolve(ok({ reply: `好的，我已经记下了：${text}`, changes: [{ op: 'add', scope, text: added, ok: true }], view: memoryView(p) }))
            } catch (error) {
              resolve(fail(error.message))
            }
          }, 3000))
        }
        case 'set-prefs': {
          // 按上游 channel.js setPrefs 校验（只实现手机会发的项）；收到的参数打印出来供验收核对「只含改动项」
          console.log('[mock] set-prefs ' + JSON.stringify(p))
          const pr = team.prefs
          if (p.theme !== undefined) { if (typeof p.theme !== 'string' || !/^[a-z][\w-]{0,31}$/.test(p.theme)) return invalid('Unknown theme'); pr.theme = p.theme }
          if (p.accent !== undefined) { if (p.accent === null || p.accent === '') delete pr.accent; else if (/^#[0-9a-f]{6}$/i.test(p.accent)) pr.accent = p.accent; else return invalid('Accent must be a #rrggbb color') }
          if (p.motion !== undefined) { if (p.motion === null || p.motion === 'normal') delete pr.motion; else if (p.motion === 'quiet' || p.motion === 'lively') pr.motion = p.motion; else return invalid('Motion must be quiet, normal or lively') }
          if (p.layout !== undefined) { if (p.layout === null || p.layout === 'bubble') delete pr.layout; else if (p.layout === 'flat') pr.layout = p.layout; else return invalid('Layout must be bubble or flat') }
          if (p.width !== undefined) { if (p.width === null || p.width === 80) delete pr.width; else if (Number.isInteger(p.width) && p.width >= 50 && p.width <= 100) pr.width = p.width; else return invalid('Width must be a whole percent from 50 to 100') }
          if (p.defaultBotModel !== undefined) {
            if (p.defaultBotModel === null || p.defaultBotModel === '') delete pr.defaultBotModel
            else if (engine(p.defaultBotModel) !== 'model') return invalid('A local agent cannot be the default model: a new Bot chooses to run on one when it is created')
            else if (!providers.catalog().some((m) => m.ref === p.defaultBotModel)) return invalid('Unknown model')
            else pr.defaultBotModel = p.defaultBotModel
          }
          if (p.enterToSend !== undefined) { if (p.enterToSend === false) pr.enterToSend = false; else if (p.enterToSend === true || p.enterToSend === null) delete pr.enterToSend }
          bump()
          return ok({ ...pr })
        }
        case 'efforts':
          return ok(EFFORTS[p.ref] ?? { efforts: [] })
        case 'chat':
          return ok({ sessionId: 's-' + (p.botId ?? ''), items: [{ kind: 'user', text: '你好', turn: 1, seq: 1 }, { kind: 'bot', text: `我是 ${team.bots[p.botId]?.name ?? 'Bot'}。`, turn: 1, seq: 2 }] })
        case 'create-bot': { const b = createBot(p); bump(); return ok(b) }
        case 'update-bot': { const b = updateBot(p); bump(); return ok(b) }
        case 'duplicate-bot': {
          const src = team.bots[p.id]
          if (!src) throw new Error('Unknown Bot')
          if (src.private) throw new Error(`${src.name} is private, so it cannot be copied`)
          let name = `${src.name} copy`
          for (let i = 2; nameTaken(name); i++) name = `${src.name} copy ${i}`
          const b = createBot({ ...src, name })
          bump()
          return ok(b)
        }
        case 'delete-bot': {
          const bot = team.bots[p.id]
          if (!bot) throw new Error('Unknown Bot')
          if (team.mainBotIds.includes(bot.id)) throw new Error('A Main Bot cannot be deleted; remove its Main Bot role first')
          if (bot.private) team.privateArchives.push({ id: bot.id, name: bot.name, ...(bot.workDir ? { workDir: bot.workDir } : {}) })
          delete team.bots[bot.id]
          for (const r of Object.values(team.rooms)) r.members = r.members.filter((m) => m !== bot.id)
          bump()
          return ok(view())
        }
        case 'private-release': {
          if (!team.privateArchives.some((a) => a.id === p.id)) throw new Error('Nothing kept closed for this Bot')
          team.privateArchives = team.privateArchives.filter((a) => a.id !== p.id)
          bump()
          return ok(view())
        }
        case 'set-main': {
          const bot = team.bots[p.id]
          if (!bot) throw new Error('Unknown Bot')
          const others = team.mainBotIds.filter((x) => x !== bot.id)
          if (p.main !== false) {
            if (bot.private) throw new Error(`${bot.name} is private, so it cannot be a Main Bot`)
            team.mainBotIds = team.mainBotIds.includes(bot.id) ? team.mainBotIds : [...others, bot.id]
            delete bot.hidden
          } else if (team.mainBotIds.includes(bot.id)) {
            if (others.length === 0) throw new Error('The team needs at least one Main Bot')
            team.mainBotIds = others
          }
          if (team.mainBotIds[0] && team.bots[team.mainBotIds[0]]) delete team.bots[team.mainBotIds[0]].sectionId
          bump()
          return ok(view())
        }
        case 'set-flags': {
          const entry = team.bots[p.id] ?? team.rooms[p.id] ?? team.agents[p.id]
          if (!entry) throw new Error('Unknown conversation')
          if (typeof p.pinned === 'boolean') entry.pinned = p.pinned
          if (typeof p.hidden === 'boolean') entry.hidden = p.hidden
          if (p.sectionId !== undefined) {
            if (p.sectionId === null || p.sectionId === '' || p.id === team.mainBotIds[0]) delete entry.sectionId
            else if (typeof p.sectionId === 'string' && team.sections.some((s) => s.id === p.sectionId)) entry.sectionId = p.sectionId
            else throw new Error('Unknown section')
          }
          bump()
          return ok(entry)
        }
        // 团队侧栏分组（dsbot-sidebar-sections，ds-bot 0.3.10 roster.js saveSection/reorderSections/deleteSection 形态）
        case 'section-save': {
          const color = p.color
          if (color !== undefined && color !== null && color !== '' && !SECTION_COLORS.includes(color)) {
            throw new Error('Unknown section color')
          }
          let sec
          if (p.id !== undefined && p.id !== null && p.id !== '') {
            sec = team.sections.find((entry) => entry.id === p.id)
            if (!sec) throw new Error('Unknown section')
            if (p.name !== undefined) sec.name = cleanSectionName(p.name, sec.id)
            if (typeof p.collapsed === 'boolean') {
              if (p.collapsed) sec.collapsed = true
              else delete sec.collapsed
            }
            if (color !== undefined) {
              if (color === null || color === '') delete sec.color
              else sec.color = color
            }
          } else {
            const clean = p.name !== undefined && String(p.name).trim() !== '' ? cleanSectionName(p.name) : freshSectionName()
            sec = {
              id: 'sec-' + ++team.seq,
              name: clean,
              ...(p.collapsed === true ? { collapsed: true } : {}),
              ...(SECTION_COLORS.includes(color) ? { color } : {}),
            }
            team.sections.push(sec)
          }
          const entryOf = (entryId) => (entryId === team.mainBotIds[0] ? undefined : team.bots[entryId] ?? team.rooms[entryId] ?? team.agents[entryId])
          const allEntries = [...Object.values(team.bots), ...Object.values(team.rooms), ...Object.values(team.agents)]
          if (Array.isArray(p.items)) {
            const keep = new Set(p.items)
            for (const entry of allEntries) {
              if (entry.sectionId === sec.id && !keep.has(entry.id)) delete entry.sectionId
            }
            for (const itemId of keep) {
              const entry = entryOf(itemId)
              if (entry !== undefined) entry.sectionId = sec.id
            }
          }
          for (const itemId of p.add ?? []) {
            const entry = entryOf(itemId)
            if (entry !== undefined) entry.sectionId = sec.id
          }
          for (const itemId of p.remove ?? []) {
            const entry = entryOf(itemId)
            if (entry !== undefined && entry.sectionId === sec.id) delete entry.sectionId
          }
          bump()
          return ok(sec)
        }
        case 'section-order': {
          if (!Array.isArray(p.ids)) throw new Error('Section order must be an array of section ids')
          const byId = new Map(team.sections.map((sec) => [sec.id, sec]))
          const next = []
          for (const id of p.ids) {
            const sec = byId.get(id)
            if (sec !== undefined) {
              next.push(sec)
              byId.delete(id)
            }
          }
          for (const sec of byId.values()) next.push(sec)
          team.sections = next
          bump()
          return ok(view())
        }
        case 'section-delete': {
          if (!team.sections.some((sec) => sec.id === p.id)) throw new Error('Unknown section')
          team.sections = team.sections.filter((sec) => sec.id !== p.id)
          for (const entry of [...Object.values(team.bots), ...Object.values(team.rooms), ...Object.values(team.agents)]) {
            if (entry.sectionId === p.id) delete entry.sectionId
          }
          bump()
          return ok(view())
        }
        case 'folders':
          return ok({ team: teamDir, picker: 'browse', folders: [...new Set([...Object.values(team.bots), ...Object.values(team.rooms)].map((e) => e.workDir).filter((p) => p && DIRS.has(p)))].map((path) => ({ path, title: nameOf(path) })) })
        case 'folder-list': {
          const path = (typeof p.path === 'string' && p.path !== '' ? expand(p.path) : '/home/user').replace(/\/+$/, '') || '/'
          if (!DIRS.has(path)) throw new Error(`cannot list ${path}: ENOENT: no such file or directory, opendir '${path}'`)
          const crumbs = []
          for (let at = path; ; at = parentOf(at)) { crumbs.unshift({ name: nameOf(at), path: at, hidden: nameOf(at).startsWith('.') }); if (at === '/') break }
          const entries = [...DIRS].filter((d) => d !== path && parentOf(d) === path).sort().map((d) => ({ name: nameOf(d), path: d, hidden: nameOf(d).startsWith('.') }))
          // /home/user/big 模拟目录太多被截断
          return ok({ path, home: '/home/user', crumbs, entries: path === '/home/user/big' ? entries.slice(0, 3) : entries, truncated: path === '/home/user/big' })
        }
        case 'team-workspace': {
          const text = String(p.path ?? '').trim()
          const path = expand(text).replace(/\/+$/, '') || '/'
          if (!path.startsWith('/')) throw new Error(`Give the full path of the folder, not ${text}`)
          if (within(DATA, path) || (within(path, DATA) && path !== '/home/user/.dsh/ds-bot/workspace')) throw new Error(`${text} holds DeepSeek Harness data, so it cannot be the team workspace`)
          DIRS.add(path)
          teamDir = path
          bump()
          return ok({ team: path })
        }
        // 群管理（dsbot-group-manage，ds-bot 0.3.10 roster.js planRoomChange/updateRoom/createRoom/deleteRoom 形态）
        case 'update-room': {
          const room = team.rooms[p.id]
          if (!room) throw new Error('Unknown group chat')
          // 先完整校验（上游 planRoomChange），失败时什么都不改
          let members = Array.isArray(p.members) ? [...new Set(p.members)] : [...room.members]
          for (const m of p.add ?? []) if (!members.includes(m)) members.push(m)
          members = members.filter((m) => !(p.remove ?? []).includes(m) && team.bots[m])
          // 与仍存在的成员比较（上游 room.members.filter(liveBot)）：被删 Bot 留下的单人群仍可改其他设置
          const current = room.members.filter((m) => team.bots[m])
          if (members.length !== current.length || members.some((m) => !current.includes(m))) checkMembers(members)
          let admin = room.admin ?? null
          if (p.admin !== undefined) {
            admin = p.admin || null
            if (admin !== null && !members.includes(admin)) throw new Error(`${team.bots[admin]?.name ?? 'That Bot'} must be a member to be the admin`)
          }
          if (admin !== null && !members.includes(admin)) admin = null
          if (p.mode !== undefined && !ROOM_MODES.includes(p.mode)) throw new Error(`Reply mode must be one of ${ROOM_MODES.join(', ')}`)
          const notice = p.notice === undefined ? undefined : cleanNotice(p.notice)
          const name = p.name === undefined ? undefined : cleanRoomName(p.name, room.id)
          if (p.permission !== undefined && p.permission !== null && p.permission !== '' && !PRESETS.includes(p.permission)) throw new Error(`Unknown permission preset ${p.permission}`)
          const wd = p.workDir === undefined ? undefined : cleanWorkDir(p.workDir)
          room.members = members
          room.admin = admin
          if (p.mode !== undefined) room.mode = p.mode
          if (notice !== undefined) room.notice = notice
          if (name !== undefined) { if (name) { room.name = name; room.named = true } else delete room.named }
          if (!room.named) room.name = autoRoomName(members)
          if (p.permission !== undefined) { if (p.permission) room.permission = p.permission; else delete room.permission }
          if (p.workDir !== undefined) { if (wd) room.workDir = wd; else delete room.workDir }
          bump()
          return ok({ room: view().rooms.find((r) => r.id === room.id), changes: [] })
        }
        case 'create-room': {
          const members = [...new Set(Array.isArray(p.members) ? p.members : [])].filter((m) => team.bots[m])
          checkMembers(members)
          const custom = cleanRoomName(p.name ?? '')
          // 上游 channel 只在 admin 为字符串时转发；mode 与 notice 不转发（新群为 admin 模式、空公告）
          const admin = typeof p.admin === 'string' ? p.admin : members.find((m) => team.mainBotIds.includes(m)) ?? null
          if (admin !== null && !members.includes(admin)) throw new Error('The admin must be a member of the group')
          const id = 'room-' + ++team.seq
          team.rooms[id] = { id, name: custom || autoRoomName(members), ...(custom ? { named: true } : {}), members, admin, mode: 'admin', notice: '', greeted: p.greet === true }
          bump()
          return ok(team.rooms[id])
        }
        case 'delete-room': {
          if (!team.rooms[p.id]) throw new Error('Unknown group chat')
          delete team.rooms[p.id]
          bump()
          return ok(view())
        }
        // 头像库（dsbot-avatars，ds-bot 0.3.10 avatar-library.js / avatar.js 形态）：按字节 sha256 去重，单张上限 119982 字节
        case 'avatar-library': {
          const ordered = [...library].sort((a, b) => b.createdAt - a.createdAt)
          const start = Math.max(0, Math.floor(Number(p.offset)) || 0)
          const count = Math.min(48, Math.max(1, Math.floor(Number(p.limit)) || 48))
          const page = ordered.slice(start, start + count)
          return ok({ items: page, total: ordered.length, next: start + page.length })
        }
        case 'avatar-import': {
          const result = { imported: [], duplicates: [], failed: [] }
          for (const item of Array.isArray(p.items) ? p.items : []) {
            const name = String(item?.name ?? '').trim().slice(0, 120)
            const m = /^data:(image\/(png|jpeg|webp|gif));base64,([A-Za-z0-9+/=]+)$/.exec(String(item?.image ?? ''))
            if (!m) { result.failed.push({ name, message: 'The avatar data URL must hold a PNG, JPEG, WebP, or GIF image.' }); continue }
            const bytes = Buffer.from(m[3], 'base64')
            if (bytes.length > 119982) { result.failed.push({ name, message: `The avatar image is too large: ${bytes.length} bytes, and the limit is 119982 bytes (about 120 KB).` }); continue }
            const id = createHash('sha256').update(bytes).digest('hex')
            const known = library.find((e) => e.id === id)
            if (known) { result.duplicates.push({ id, name: known.name }); continue }
            const entry = { id, name: name || 'photo', mime: m[1], bytes: bytes.length, createdAt: Date.now() + library.length, image: item.image }
            library.push(entry)
            result.imported.push({ id, name: entry.name, mime: entry.mime, bytes: entry.bytes, createdAt: entry.createdAt })
          }
          return ok(result)
        }
        case 'avatar-delete': {
          const at = library.findIndex((e) => e.id === p.id)
          if (at === -1) return ok(false)
          library.splice(at, 1)
          return ok(true)
        }
        // Bot 浏览器（dsbot-bot-browser，ds-bot 0.3.10 channel.js / browser.js / browser/session.js 形态）
        case 'browser-frame': {
          browser.frameCalls += 1
          if (browser.mode === 'off') return { ok: false, error: { code: 'bot/browser-off', message: 'Bot browsers are off on this host', details: {} } }
          const page = browser.openBots[String(p.botId ?? '')]
          if (!page) return { ok: false, error: { code: 'bot/not-found', message: 'This Bot has no open browser', details: {} } }
          browser.frameSeq += 1
          const rgb = [(page.rgb[0] + browser.frameSeq * 17) % 256, page.rgb[1], page.rgb[2]]
          const png = makeMockPng(160, 100, rgb)
          return ok({ mime: 'image/png', data: png.toString('base64'), url: page.url, title: `${page.title} #${browser.frameSeq}` })
        }
        case 'browser-takeover': {
          if (browser.mode !== 'desktop') return { ok: false, error: { code: 'bot/browser-off', message: 'Bot browsers are off on this host', details: {} } }
          const botId = String(p.botId ?? '')
          if (!team.bots[botId]) return { ok: false, error: { code: 'bot/not-found', message: 'Unknown Bot', details: {} } }
          const asked = browser.holds[botId]
          browser.holds[botId] = asked?.callId !== undefined
            ? { by: 'user', reason: asked.reason, note: asked.note, callId: asked.callId, ...(asked.sessionId !== undefined ? { sessionId: asked.sessionId } : {}) }
            : { by: 'user', reason: 'takeover', note: 'The user is controlling this browser.' }
          pushLive()
          return ok(true)
        }
        case 'browser-give-back': {
          if (browser.mode !== 'desktop') return { ok: false, error: { code: 'bot/browser-off', message: 'Bot browsers are off on this host', details: {} } }
          const botId = String(p.botId ?? '')
          if (!team.bots[botId]) return { ok: false, error: { code: 'bot/not-found', message: 'Unknown Bot', details: {} } }
          if (p.outcome !== undefined && !['done', 'skip', 'returned'].includes(p.outcome)) return invalid('Nothing to send')
          const hold = browser.holds[botId] ?? null
          if (hold === null) return { ok: false, error: { code: 'bot/forbidden', message: 'This browser is not held', details: {} } }
          if (p.callId !== undefined && p.callId !== hold.callId) return { ok: false, error: { code: 'bot/stale', message: 'This handoff is no longer open', details: {} } }
          browser.lastGiveBack = { botId, hasOutcome: p.outcome !== undefined, outcome: p.outcome ?? null, hasCallId: p.callId !== undefined }
          delete browser.holds[botId]
          pushLive()
          return ok(true)
        }
        case 'browser-stop': {
          const botId = String(p.botId ?? '')
          if (!team.bots[botId]) return { ok: false, error: { code: 'bot/not-found', message: 'Unknown Bot', details: {} } }
          browser.stopCalls += 1
          if (browser.mode === 'desktop') {
            browser.holds[botId] = { by: 'user', reason: 'stopped', note: 'The user is controlling this browser.' }
          }
          pushLive()
          return ok(true)
        }
        // 能力（dsbot-capabilities）：上游 capabilities.js capabilityEndpoint（界面调用 ui:true）
        case 'capability-save': {
          caps.calls.push({ endpoint, name: p.name, kind: p.kind })
          const name = String(p.name ?? '').trim()
          if (name.length > 32 || !CAP_NAME.test(name) || name === 'github' || name === 'mail') throw new Error('Use a capability name with lowercase letters, digits, and single hyphens; connector names are reserved.')
          if (!['mcp', 'skill', 'pack'].includes(p.kind)) throw new Error('Choose an MCP server, skill, or pack.')
          const previous = caps.items[name]
          if (previous && previous.kind !== p.kind) throw new Error('A capability cannot change its kind. Delete it and save a new one.')
          const now = Date.now()
          const item = { name, kind: p.kind, description: String(p.description ?? '').trim().slice(0, 2000), createdBy: previous?.createdBy ?? null, createdAt: previous?.createdAt ?? now, updatedAt: now }
          const strings = (v) => { if (v === undefined) return []; if (!Array.isArray(v) || v.some((x) => typeof x !== 'string')) throw new Error('Arguments must be a list of text values.'); return [...v] }
          const map = (v, label) => { if (v === undefined) return {}; if (v === null || typeof v !== 'object' || Array.isArray(v) || Object.values(v).some((x) => typeof x !== 'string')) throw new Error(`${label} must contain text values.`); return { ...v } }
          if (p.kind === 'mcp') {
            if (p.transport === 'streamable-http') {
              const url = String(p.url ?? '').trim()
              try { const u = new URL(url); if (u.protocol !== 'https:' && u.protocol !== 'http:') throw new Error() } catch { throw new Error('Enter a valid HTTP or HTTPS URL.') }
              Object.assign(item, { transport: 'streamable-http', url, headers: map(p.headers, 'Headers'), approved: true })
            } else {
              const command = String(p.command ?? '').trim()
              if (command === '') throw new Error('Enter a command for the MCP server.')
              Object.assign(item, { transport: 'stdio', command, args: strings(p.args), env: map(p.env, 'Environment variables'), approved: true })
            }
          } else if (p.kind === 'skill') {
            item.content = String(p.content ?? '').trim()
          } else {
            const names = [...new Set(strings(p.items))]
            for (const child of names) if (!caps.items[child] || !['mcp', 'skill'].includes(caps.items[child].kind)) throw new Error(`Packs can include only saved MCP servers and skills. ${child} is not one.`)
            item.items = names
          }
          caps.items[name] = item
          bump()
          return ok(item)
        }
        case 'capability-delete': {
          caps.calls.push({ endpoint, name: p.name })
          const item = caps.items[String(p.name ?? '')]
          if (!item) throw new Error(`No capability named ${p.name}.`)
          delete caps.items[item.name]
          for (const pack of Object.values(caps.items)) if (pack.kind === 'pack') pack.items = (pack.items ?? []).filter((c) => c !== item.name)
          for (const bot of Object.values(team.bots)) {
            bot.capabilities = (bot.capabilities ?? []).filter((n) => n !== item.name)
            bot.packs = (bot.packs ?? []).filter((n) => n !== item.name)
          }
          bump()
          return ok(true)
        }
        case 'capability-approve': {
          caps.calls.push({ endpoint, name: p.name })
          const item = caps.items[String(p.name ?? '')]
          if (!item || item.kind !== 'mcp' || item.transport !== 'stdio') throw new Error(`No unapproved stdio MCP named ${p.name}.`)
          item.approved = true
          item.updatedAt = Date.now()
          bump()
          return ok(item)
        }
        case 'bot-capability': {
          caps.calls.push({ endpoint, botId: p.botId, name: p.name, attached: p.attached, mode: p.mode })
          const bot = team.bots[String(p.botId ?? '')]
          if (!bot) throw new Error('Unknown Bot')
          const name = String(p.name ?? '')
          if (!caps.items[name]) throw new Error(`No capability named ${name}.`)
          const mode = p.mode ?? 'always'
          if (!['always', 'catalog'].includes(mode)) throw new Error('Capability mode must be always or catalog.')
          bot.capabilities = (bot.capabilities ?? []).filter((n) => n !== name)
          bot.packs = (bot.packs ?? []).filter((n) => n !== name)
          if (p.attached === true) bot[mode === 'catalog' ? 'packs' : 'capabilities'].push(name)
          bump()
          return ok(bot.capabilities)
        }
        default:
          return null
      }
    } catch (error) {
      return fail(error.message)
    }
  }

  // /mock/capabilities：?library=0|1 切换 state 是否带能力库（旧版远端）；?resetCalls=1 清空调用记录；返回能力库与调用记录
  function setCapabilities(params) {
    const lib = params.get('library')
    if (lib === '0' || lib === '1') { caps.enabled = lib === '1'; bump(); pushLive() }
    if (params.get('resetCalls') === '1') caps.calls = []
    return { enabled: caps.enabled, items: capLibraryView(), bots: Object.fromEntries(Object.values(team.bots).map((b) => [b.id, { capabilities: b.capabilities ?? [], packs: b.packs ?? [] }])), statuses: capabilityStatuses(), calls: caps.calls }
  }

  function setBrowser(params) {
    const mode = params.get('mode')
    if (mode === 'off' || mode === 'web' || mode === 'desktop') {
      browser.mode = mode
      if (mode !== 'desktop') browser.holds = {}
    }
    const botId = params.get('bot') ?? 'bot-1'
    const open = params.get('open')
    if (open === '1') {
      browser.openBots[botId] = {
        url: params.get('url') ?? 'https://example.com/dashboard',
        title: params.get('title') ?? 'Example Dashboard',
        rgb: [40, 120, 220],
      }
    } else if (open === '0') {
      delete browser.openBots[botId]
    }
    const handoff = params.get('handoff')
    if (handoff === '1') {
      browser.holds[botId] = { by: 'bot', reason: 'login', note: '请登录测试后台', callId: 'handoff-1', sessionId: 's-' + botId }
    } else if (handoff === '0') {
      delete browser.holds[botId]
    }
    if (params.get('resetStats') === '1') {
      browser.frameCalls = 0
      browser.stopCalls = 0
      browser.lastGiveBack = null
    }
    bump()
    pushLive()
    return {
      mode: browser.mode,
      openBots: Object.keys(browser.openBots),
      holds: browser.holds,
      frameCalls: browser.frameCalls,
      frameSeq: browser.frameSeq,
      stopCalls: browser.stopCalls,
      lastGiveBack: browser.lastGiveBack,
    }
  }

  return { handle, team, browser, setBrowser, setCapabilities, setConnectors: (params) => conn.set(params), setIm: (params) => im.set(params), setLocalAgents: (params) => localAgents.set(params), setProviders: (params) => {
      const out = providers.set(params)
      // ?block=<botId>&code=<失败代码>&provider=<id>：让该 Bot 处于模型失败阻塞（修复卡验收）；?unblock=<botId> 清除
      const block = params.get('block')
      if (block && team.bots[block]) team.bots[block].blocked = { code: params.get('code') ?? 'MISSING_CREDENTIAL', provider: params.get('provider') ?? 'glm', at: Date.now() }
      const unblock = params.get('unblock')
      if (unblock && team.bots[unblock]) delete team.bots[unblock].blocked
      bump()
      return out
    } }
}
