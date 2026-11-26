// mock-dsh-server --manage 的 MCP 访问（dsbot-mcp-access 模拟器验收）：
// 按 ds-bot 0.3.10 src/host/mcp-server.js（mcpView/mcpEndpoint/scope/clientOf）与 channel.js（live().mcp、
// mcp-* 不在 READS、只读宿主 bot/read-only）源码核对的形态模拟。上游写的是 mcp.json、不调用 rt.save()，
// 视图走 live()，这里同样不推进 revision，而是变更 liveKey 并 pushLive()。
// 令牌只出现在 mcp-client-add 的应答中；模块只保存令牌摘要，调用记录只记端点与参数键，不记令牌。
import { createHash, randomBytes, randomUUID } from 'node:crypto'

export const DEFAULT_PORT = 8797
const invalid = (message) => Object.assign(new Error(message), { code: 'bot/invalid' })
const ids = (list) => (Array.isArray(list) ? [...new Set(list.map(String).filter(Boolean))] : [])

export function createMcp({ pushLive, bots, roomExists }) {
  let on = false
  let port = DEFAULT_PORT
  let busyPort = null
  let readOnly = null
  let clients = []
  const calls = []

  function reset() {
    on = false
    port = DEFAULT_PORT
    busyPort = null
    readOnly = null
    const now = Date.now()
    clients = [
      { id: 'mcp-c1', name: 'Claude Desktop', hash: 'x'.repeat(64), bots: ['bot-1'], rooms: [], createdAt: now - 86_400_000, lastUsedAt: now - 3_600_000 },
    ]
  }
  reset()

  // 上游 settle()：只读宿主或关闭 → off（开启时 error 为只读原因）；端口被占用 → off + Port N is in use；否则 on
  function view() {
    let status = 'off'
    let error = null
    if (on && readOnly !== null) error = readOnly
    else if (on && busyPort === port) error = `Port ${port} is in use`
    else if (on) status = 'on'
    return {
      on,
      port,
      status,
      error,
      url: `http://127.0.0.1:${port}/mcp`,
      clients: clients.map(({ id, name, bots: b, rooms, createdAt, lastUsedAt }) => ({ id, name, bots: b, rooms, createdAt, lastUsedAt: lastUsedAt ?? null })),
    }
  }

  const liveKey = () => createHash('sha256').update(JSON.stringify(view())).digest('hex').slice(0, 12)
  const liveFields = () => ({ mcp: view() })

  // mcp-server.js scope()：bots 过滤掉不存在与私有 Bot，rooms 过滤掉不存在的群；未给的键保持 fallback
  const scope = (p, fallback) => {
    const known = bots()
    return {
      bots: p.bots === undefined ? fallback.bots : ids(p.bots).filter((id) => known[id] !== undefined && known[id].private !== true),
      rooms: p.rooms === undefined ? fallback.rooms : ids(p.rooms).filter((id) => roomExists(id)),
    }
  }
  const clientOf = (id) => {
    const client = clients.find((entry) => entry.id === String(id ?? ''))
    if (client === undefined) throw invalid('This MCP client does not exist')
    return client
  }

  const endpoints = {
    set(p) {
      if (p.port !== undefined) {
        const next = Number(p.port)
        if (!Number.isInteger(next) || next < 1024 || next > 65535) throw invalid('The port must be a number from 1024 to 65535')
        port = next
      }
      if (p.on !== undefined) on = p.on === true
      return view()
    },
    'client-add'(p) {
      const name = String(p.name ?? '').trim().slice(0, 60)
      if (name === '') throw invalid('Give the client a name')
      const all = bots()
      const mains = Object.values(all).filter((b) => b.private !== true && b.main === true).map((b) => b.id)
      const token = `dsb_${randomBytes(32).toString('base64url')}`
      clients.push({ id: randomUUID(), name, hash: createHash('sha256').update(token).digest('hex'), ...scope(p, { bots: mains, rooms: [] }), createdAt: Date.now() })
      return { ...view(), token }
    },
    'client-update'(p) {
      const client = clientOf(p.id)
      if (p.name !== undefined) {
        const name = String(p.name).trim().slice(0, 60)
        if (name === '') throw invalid('Give the client a name')
        client.name = name
      }
      Object.assign(client, scope(p, client))
      return view()
    },
    'client-remove'(p) {
      const client = clientOf(p.id)
      clients = clients.filter((entry) => entry !== client)
      return view()
    },
  }

  function handle(endpoint, p, ok, fail) {
    if (!endpoint.startsWith('mcp-')) return null
    const sub = endpoint.slice('mcp-'.length)
    if (!(sub in endpoints)) return null
    calls.push({ endpoint, keys: Object.keys(p ?? {}).sort(), at: Date.now() })
    try {
      // 四个写端点都不在上游 READS 内
      if (readOnly !== null) throw Object.assign(new Error(readOnly), { code: 'bot/read-only' })
      const value = endpoints[sub](p ?? {})
      pushLive()
      return ok(value)
    } catch (error) {
      return error.code ? { ok: false, error: { code: error.code, message: error.message, details: {} } } : fail(error.message)
    }
  }

  // /mock/mcp：?on=0|1；?port=<端口>（直接置位，不校验）；?busy=<端口|空>（模拟该端口被占用）；?readOnly=<文案或空>；
  // ?used=<clientId>（标记刚刚使用）；?reset=1；?resetCalls=1
  function set(params) {
    if (params.get('reset') === '1') reset()
    if (params.get('resetCalls') === '1') calls.length = 0
    const o = params.get('on')
    if (o === '0' || o === '1') on = o === '1'
    const pt = Number(params.get('port'))
    if (params.has('port') && Number.isInteger(pt)) port = pt
    if (params.has('busy')) {
      const b = Number(params.get('busy'))
      busyPort = params.get('busy') && Number.isInteger(b) ? b : null
    }
    if (params.has('readOnly')) {
      const r = params.get('readOnly')
      readOnly = r && r.trim() !== '' ? r : null
    }
    const used = params.get('used')
    const client = used ? clients.find((c) => c.id === used) : undefined
    if (client) client.lastUsedAt = Date.now()
    pushLive()
    return { view: view(), readOnly, busyPort, calls }
  }

  return { handle, view, set, liveKey, liveFields, readOnly: () => readOnly }
}
