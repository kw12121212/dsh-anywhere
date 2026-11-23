// mock-dsh-server 的 --manage 模式（dsbot-native-manage 里程碑模拟器验收）：可变的内存团队，
// 按 DS-Bot 0.3.10 源码核对的形态实现管理端点（channel.js / roster.js / privacy.js）。每次写入 revision+1
// 并经 feed 推 revision 事件；拒绝时回 {ok:false, error:{code:'bot/refused', message}}（上游 channel.js errorCode 对普通 Error 的映射）。
// 日志只记端点与参数键，不打印正文。
import { createHash } from 'node:crypto'

const MODELS = [
  { ref: 'deepseek/v4', provider: 'deepseek', providerName: 'DeepSeek', id: 'v4', name: 'DeepSeek V4', input: ['text'], reasoning: { efforts: [{ id: 'low', name: 'Low' }, { id: 'high', name: 'High' }, { id: 'max', name: 'Max' }] } },
  { ref: 'deepseek/v4-flash', provider: 'deepseek', providerName: 'DeepSeek', id: 'v4-flash', name: 'DeepSeek V4 Flash', input: ['text'] },
  { ref: 'glm/glm-5.3', provider: 'glm', providerName: 'GLM Coding Plan', id: 'glm-5.3', name: 'GLM 5.3', input: ['text'], needsKey: true },
  { ref: 'local-claude-code/default', provider: 'local-claude-code', providerName: 'Claude Code', id: 'default', name: 'Claude Code' },
]
const EFFORTS = { 'deepseek/v4': { efforts: MODELS[0].reasoning.efforts } }

export function createManageTeam(pushRevision) {
  const team = {
    revision: 100,
    mainBotIds: ['bot-1'],
    bots: {
      'bot-1': { id: 'bot-1', name: '研究主管', role: '研究', instructions: '负责团队研究计划。', color: 'deepseek', avatar: { shape: 'whale' }, model: 'deepseek/v4', appliedModel: 'deepseek/v4', effort: 'high' },
      'bot-2': { id: 'bot-2', name: '运维值班', role: '运维', instructions: '处理告警。', model: 'deepseek/v4-flash', appliedModel: 'deepseek/v4-flash', workDir: '/work/ops' },
      'bot-3': { id: 'bot-3', name: '写作搭档', role: '写作', instructions: '', model: 'deepseek/v4', appliedModel: 'deepseek/v4', workDir: '/work/writing', permission: 'workspace-write' },
      'bot-4': { id: 'bot-4', name: '采购助理', role: '', instructions: '', model: 'deepseek/v4-flash', appliedModel: 'deepseek/v4-flash' },
    },
    rooms: { 'room-1': { id: 'room-1', name: '项目群', color: 'violet', members: ['bot-1', 'bot-2'], admin: 'bot-1', mode: 'auto', notice: '' } },
    sections: [],
    privateArchives: [{ id: 'old-1', name: '旧私有助理', workDir: '/work/old-private' }],
    seq: 10,
  }
  const library = []
  const fail = (message) => ({ ok: false, error: { code: 'bot/refused', message, details: {} } })
  const ok = (value) => ({ ok: true, value })
  const norm = (s) => String(s ?? '').trim().toLowerCase()
  const engine = (ref) => (!ref ? 'model' : String(ref).split('/')[0].startsWith('local-') ? String(ref).split('/')[0] : 'model')
  const inGroup = (id) => Object.values(team.rooms).some((r) => r.members.includes(id))
  // 以 held 开头的名称模拟上游「正在另一处创建中、被保留」的名称（roster.js isHeld）：客户端看不到，只能由服务端拒绝
  const nameTaken = (name, self) => /^held/i.test(String(name).trim()) || Object.values(team.bots).some((b) => b.id !== self && norm(b.name) === norm(name))
  const bump = () => { team.revision += 1; pushRevision(team.revision) }

  function view() {
    return {
      revision: team.revision,
      liveKey: 'manage-' + team.revision,
      mainBotId: team.mainBotIds[0] ?? null,
      mainBotIds: team.mainBotIds,
      bots: Object.values(team.bots).map((b) => ({ ...b, sessionId: 's-' + b.id, parts: [] })),
      rooms: Object.values(team.rooms).map((r) => ({ ...r, sessionId: r.id, parts: [] })),
      sections: team.sections,
      privateArchives: team.privateArchives,
      models: MODELS,
      defaultModel: 'deepseek/v4',
      permissions: { options: [{ value: 'read-only', name: '只读' }, { value: 'workspace-write', name: '工作区可写' }, { value: 'danger-full-access', name: '完全访问' }], defaultPreset: 'danger-full-access' },
      colors: ['deepseek', 'blue', 'violet', 'orange', 'green', 'magenta', 'cyan', 'red', 'yellow', 'brown', 'gray', 'black', 'aurora'],
      prefs: {},
      work: { bots: {}, rooms: {}, approvals: [] },
    }
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
    if (p.workDir !== undefined) { if (p.workDir) bot.workDir = p.workDir; else delete bot.workDir }
    return bot
  }

  function handle(endpoint, p) {
    try {
      switch (endpoint) {
        case 'state':
          if (p.since === team.revision) return ok({ revision: team.revision, unchanged: true, liveKey: 'manage-' + team.revision })
          return ok(view())
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
          bump()
          return ok(view())
        }
        case 'set-flags': {
          const entry = team.bots[p.id] ?? team.rooms[p.id]
          if (!entry) throw new Error('Unknown conversation')
          if (typeof p.pinned === 'boolean') entry.pinned = p.pinned
          if (typeof p.hidden === 'boolean') entry.hidden = p.hidden
          bump()
          return ok(entry)
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
        default:
          return null
      }
    } catch (error) {
      return fail(error.message)
    }
  }

  return { handle, team }
}
