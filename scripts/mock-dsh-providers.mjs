// mock-dsh-server --manage 的模型服务商（dsbot-models-providers 模拟器验收）：按 ds-bot 0.3.10 providers.js
//（providersView、checkKey、addProvider、customProfile、discoverModels、saveProviderModels、removeProvider）源码核对的形态，
// 用内存数据模拟，并作为 state.models 目录的来源。调用记录只记端点与参数键，不记 key。
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const PROTOCOLS = ['openai-completions', 'openai-responses', 'anthropic-messages']
const ROUTE_ID = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/
const KNOWN = {
  zai: { name: 'Z.ai', models: [{ id: 'glm-4.6', name: 'GLM 4.6' }] },
  groq: { name: 'Groq', models: [{ id: 'llama-3.3-70b', name: 'Llama 3.3 70B' }] },
  openrouter: { name: 'OpenRouter', models: [{ id: 'auto', name: 'Auto' }] },
}
const invalid = (message) => Object.assign(new Error(message), { code: 'bot/invalid' })

function checkKey(value) {
  const key = typeof value === 'string' ? value.trim() : ''
  if (key === '') throw invalid('Enter the API key')
  if (/^[A-Za-z_][A-Za-z0-9_]*=[^=]/.test(key)) throw invalid('Paste only the key, not a NAME=value line')
  if (/^(["']).*\1$/.test(key)) throw invalid('Paste the key without quotes')
  if (!/^[\x21-\x7E]+$/.test(key)) throw invalid('An API key has no spaces or special characters')
  return key
}
function checkEndpoint(value) {
  let url
  try { url = new URL(String(value ?? '').trim()) } catch { throw invalid('Enter the endpoint as an http or https address') }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw invalid('Enter the endpoint as an http or https address')
  return String(value).trim()
}

export function createProviders() {
  let enabled = true
  let available = true
  let revision = 1
  const calls = []
  let providers = []
  let addable = []
  function reset() {
    providers = [
      { id: 'deepseek', name: 'DeepSeek', models: [{ id: 'v4', name: 'DeepSeek V4' }, { id: 'v4-flash', name: 'DeepSeek V4 Flash' }], key: { configured: true, writable: true }, refreshable: false, removable: false, custom: false },
      { id: 'glm', name: 'GLM Coding Plan', models: [{ id: 'glm-5.3', name: 'GLM 5.3' }], key: { configured: false, writable: true }, refreshable: false, removable: false, custom: false },
      { id: 'env-openai', name: 'OpenAI (env)', models: [{ id: 'gpt-5', name: 'GPT-5' }], key: { configured: true, writable: false }, refreshable: false, removable: false, custom: false },
      { id: 'my-gw', name: 'My Gateway', models: [{ id: 'gw-a', name: 'Gateway A' }, { id: 'gw-b', name: 'Gateway B' }], declared: true, key: { configured: true, writable: true }, refreshable: true, removable: true, custom: true },
    ]
    addable = Object.entries(KNOWN).map(([id, k]) => ({ id, name: k.name }))
    revision += 1
  }
  reset()

  const refs = (p) => p.models.map((m) => ({ id: m.id, ref: `${p.id}/${m.id}`, name: m.name ?? m.id }))
  const view = () => (available ? {
    available: true,
    providers: [...providers].sort((a, b) => Number(a.removable) - Number(b.removable)).map((p) => ({
      id: p.id, name: p.name, models: refs(p), declaredModels: p.declared ? refs(p) : [], refreshable: p.refreshable,
      key: p.key, removable: p.removable, custom: p.custom,
    })),
    addable, protocols: PROTOCOLS, revision,
  } : { available: false })
  const find = (id) => providers.find((p) => p.id === String(id ?? '').trim())

  // state.models：服务商模型展开为目录（缺 key 的标 needsKey），另附本地 Agent 路由
  const catalog = () => [
    ...providers.flatMap((p) => p.models.map((m) => ({ ref: `${p.id}/${m.id}`, provider: p.id, providerName: p.name, id: m.id, name: m.name ?? m.id, input: ['text'], ...(p.key && !p.key.configured ? { needsKey: true } : {}) }))),
    { ref: 'local-claude-code/default', provider: 'local-claude-code', providerName: 'Claude Code', id: 'default', name: 'Claude Code' },
  ]

  const endpoints = {
    async providers() { return view() },
    async models() { await sleep(400); return { modelsKey: 'mk-' + revision } },
    async 'provider-key'(p) {
      if (p.value !== null) checkKey(p.value) // 上游 setProviderKey 先 checkKey 再找路由
      const pr = find(p.provider)
      if (!pr || !pr.key) throw invalid(`${p.provider} takes no API key here`)
      if (!pr.key.writable) throw invalid(`The API key for ${pr.id} is set outside DS Bot and cannot be changed here`)
      pr.key = { ...pr.key, configured: p.value !== null }
      return view() // 凭据写入不改设置 revision
    },
    async 'provider-add'(p) {
      await sleep(600)
      const id = String(p.provider ?? '').trim()
      if (find(id)) throw invalid(`${id} is already set up`)
      const key = typeof p.key === 'string' && p.key.trim() !== '' ? checkKey(p.key) : undefined
      if (p.custom === true) {
        if (!ROUTE_ID.test(id)) throw invalid('A provider id is lowercase letters, digits and dashes, starting with a letter')
        if (KNOWN[id]) throw invalid(`DSH already offers ${id}; add it from its list`)
        if (!PROTOCOLS.includes(p.api)) throw invalid(`The protocol must be one of ${PROTOCOLS.join(', ')}`)
        checkEndpoint(p.baseURL)
        const models = (Array.isArray(p.models) ? p.models : []).map((m) => ({ id: String(m?.id ?? m).trim(), ...(m?.name ? { name: m.name } : {}) }))
        if (models.length === 0) throw invalid('Add at least one model')
        if (models.some((m) => m.id === '')) throw invalid('Every model needs an id')
        if (new Set(models.map((m) => m.id)).size !== models.length) throw invalid('Two models have the same id')
        providers.push({ id, name: String(p.name ?? '').trim().slice(0, 80) || id, models, declared: true, key: { configured: key !== undefined, writable: true }, refreshable: true, removable: true, custom: true })
      } else {
        const k = KNOWN[id]
        if (!k || !addable.some((a) => a.id === id)) throw invalid(`DSH offers no provider ${id}`)
        providers.push({ id, name: k.name, models: k.models.map((m) => ({ ...m })), key: { configured: key !== undefined, writable: true }, refreshable: false, removable: true, custom: false })
        addable = addable.filter((a) => a.id !== id)
      }
      revision += 1
      return view()
    },
    async 'provider-models'(p) {
      await sleep(900)
      const pr = p.provider ? find(p.provider) : undefined
      if (pr) {
        if (pr.id === 'my-gw') return [{ id: 'gw-a', name: 'Gateway A' }, { id: 'gw-c', name: 'Gateway C', contextWindow: 128000 }, { id: 'gw-d' }]
        return pr.models.map((m) => ({ ...m }))
      }
      checkEndpoint(p.baseURL)
      if (typeof p.key === 'string' && p.key.trim() !== '') checkKey(p.key)
      return [{ id: 'm-large', name: 'Large' }, { id: 'm-small', name: 'Small' }, { id: 'm-vision', inputModalities: ['text', 'image'] }]
    },
    async 'provider-models-save'(p) {
      await sleep(500)
      const pr = find(p.provider)
      if (!pr || !pr.removable) throw invalid(`No editable provider ${p.provider}`)
      if (!pr.refreshable) throw invalid(`${pr.id} has no model list to refresh here`)
      if (p.revision !== undefined && p.revision !== revision) throw Object.assign(new Error('The provider settings changed in another window; refresh the models and try again'), { code: 'bot/stale' })
      const list = Array.isArray(p.models) ? p.models : []
      if (list.length === 0) throw invalid('Select at least one model')
      pr.models = list.map((m) => ({ id: String(m?.id ?? m).trim(), ...(m?.name ? { name: m.name } : {}) }))
      revision += 1
      return view()
    },
    async 'provider-remove'(p) {
      const pr = find(p.provider)
      if (!pr) throw invalid(`${p.provider} is not set up`)
      if (!pr.removable) throw invalid(`${pr.id} comes with this setup and cannot be removed`)
      providers = providers.filter((x) => x !== pr)
      if (KNOWN[pr.id]) addable.push({ id: pr.id, name: KNOWN[pr.id].name })
      revision += 1
      return view()
    },
  }

  /** 本模块负责的端点：返回 Promise；其他端点返回 null。enabled=false 模拟旧版远端（bot/not-found）。 */
  function handle(endpoint, p, ok, fail) {
    const fn = endpoints[endpoint]
    if (!fn) return null
    calls.push({ endpoint, keys: Object.keys(p ?? {}).sort(), at: Date.now() })
    if (!enabled) return Promise.resolve({ ok: false, error: { code: 'bot/not-found', message: `Unknown endpoint ${endpoint}`, details: {} } })
    // 上游服务商写入只 dropCatalog、不推进团队 revision：客户端靠 state 请求里的 models（modelsKey）发现目录变化
    return fn(p ?? {}).then((value) => ok(value),
      (error) => (error.code ? { ok: false, error: { code: error.code, message: error.message, details: {} } } : fail(error.message)))
  }

  // /mock/providers：?enabled=0|1；?available=0|1；?bumpRevision=1（模拟别处改了设置）；?reset=1；?resetCalls=1
  function set(params) {
    const en = params.get('enabled')
    if (en === '0' || en === '1') enabled = en === '1'
    const av = params.get('available')
    if (av === '0' || av === '1') available = av === '1'
    if (params.get('bumpRevision') === '1') revision += 1
    if (params.get('reset') === '1') reset()
    if (params.get('resetCalls') === '1') calls.length = 0
    return { enabled, available, revision, providers, calls }
  }

  // 同上游 channel.js modelsKey：目录 ref（缺 key 加 !）与默认模型
  const modelsKey = () => `${catalog().map((m) => `${m.ref}${m.needsKey ? '!' : ''}`).join(',')}|deepseek/v4`
  return { handle, set, catalog, modelsKey }
}
