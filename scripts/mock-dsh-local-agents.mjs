// mock-dsh-server --manage 的本地 Agent（dsbot-local-agents 模拟器验收）：按 ds-bot 0.3.10 local-agents/index.js
//（localAgentsEndpoint、detected、customView）与 engines.js validateCustom 源码核对的形态，用内存数据模拟。
// 调用记录只记端点与参数键。
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const KNOWN_PROGRAMS = new Set(['gemini', 'opencode', 'qwen'])

function validateCustom({ name, command, args }) {
  const clean = {
    name: String(name ?? '').trim().slice(0, 40),
    command: String(command ?? '').trim(),
    args: (Array.isArray(args) ? args : String(args ?? '').split(' ')).map((arg) => String(arg).trim()).filter(Boolean),
  }
  if (clean.name === '') throw new Error('Name is required')
  if (clean.command === '' || /[\s]/.test(clean.command) || !(clean.command.startsWith('/') || clean.command.startsWith('~/') || /^[A-Za-z]:[\\/]/.test(clean.command) || /^[\w.-]+$/.test(clean.command))) {
    throw new Error('Command must be a program name, an absolute path, or a ~/path')
  }
  if (clean.args.some((arg) => arg.includes('\0') || /[\r\n]/.test(arg))) throw new Error('Arguments must not contain newlines')
  return clean
}

export function createLocalAgents() {
  let enabled = true
  let available = true
  const calls = []
  let custom = []
  let seq = 0
  const reset = () => { custom = [{ id: 'agent-mock1', name: 'Gemini ACP', command: 'gemini', args: ['--experimental-acp'] }] }
  reset()
  const detected = () => [
    { route: 'local-claude-code', name: 'Claude Code', path: '/usr/local/bin/claude', version: '2.1.3 (Claude Code)', signIn: 'claude login', signedIn: true },
    { route: 'local-codex-cli', name: 'Codex', path: '/home/user/.local/bin/codex', version: 'codex-cli 0.48.0', signIn: 'codex login', signedIn: false },
  ]
  const customView = () => custom.map((a) => ({ ...a, route: `local-acp-${a.id}`, available }))  // 上游：路由注册与否（supported() 时自定义路由总会注册），与命令是否存在无关
  const list = () => ({ available, detected: available ? detected() : [], custom: customView() })
  const MODELS = {
    'local-claude-code': [{ provider: 'local-claude-code', id: 'sonnet', name: 'Claude Sonnet' }, { provider: 'local-claude-code', id: 'opus', name: 'Claude Opus' }],
    'local-codex-cli': [{ provider: 'local-codex-cli', id: 'gpt-5-codex', name: 'GPT-5 Codex' }],
  }

  const endpoints = {
    async 'local-agents'() { await sleep(400); return list() },
    async 'local-agent-refresh'() { await sleep(2000); return list() },
    async 'local-agent-models'(p) {
      await sleep(1200) // 首次会在电脑上启动 CLI
      const route = String(p.route ?? '')
      if (MODELS[route]) return MODELS[route]
      return route.startsWith('local-acp-') ? [{ provider: route, id: 'default', name: 'Default' }] : []
    },
    async 'local-agent-add'(p) {
      const clean = validateCustom(p)
      await sleep(500)
      if (!KNOWN_PROGRAMS.has(clean.command) && !clean.command.includes('/') && !clean.command.includes('\\')) throw new Error(`No program named ${clean.command} was found`)
      const entry = { id: `agent-m${++seq}`, ...clean }
      custom.push(entry)
      return { ...entry, route: `local-acp-${entry.id}` }
    },
    async 'local-agent-update'(p) {
      const entry = custom.find((a) => a.id === p.id)
      if (!entry) throw new Error('Unknown agent')
      Object.assign(entry, validateCustom({ name: p.name ?? entry.name, command: p.command ?? entry.command, args: p.args ?? entry.args }))
      return { ...entry, route: `local-acp-${entry.id}` }
    },
    async 'local-agent-remove'(p) {
      const at = custom.findIndex((a) => a.id === p.id)
      if (at === -1) throw new Error('Unknown agent')
      custom.splice(at, 1)
      return customView()
    },
  }

  /** 本模块负责的端点：返回 Promise；其他端点返回 null。enabled=false 时模拟旧版远端（未知端点 bot/not-found）。 */
  function handle(endpoint, p, ok, fail) {
    const fn = endpoints[endpoint]
    if (!fn) return null
    calls.push({ endpoint, keys: Object.keys(p ?? {}).sort(), at: Date.now() })
    if (!enabled) return Promise.resolve({ ok: false, error: { code: 'bot/not-found', message: `Unknown endpoint ${endpoint}`, details: {} } })
    return fn(p ?? {}).then((value) => ok(value), (error) => fail(error.message))
  }

  // /mock/local-agents：?enabled=0|1（0 = 旧版远端回 bot/not-found）；?available=0|1；?reset=1；?resetCalls=1
  function set(params) {
    const en = params.get('enabled')
    if (en === '0' || en === '1') enabled = en === '1'
    const av = params.get('available')
    if (av === '0' || av === '1') available = av === '1'
    if (params.get('reset') === '1') reset()
    if (params.get('resetCalls') === '1') calls.length = 0
    return { enabled, available, custom, calls }
  }

  return { handle, set }
}
