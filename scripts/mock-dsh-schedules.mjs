// mock-dsh-server --manage 的定时任务（dsbot-schedules 模拟器验收）：按 ds-bot 0.3.10 schedules.js
//（schedulesView、selectorOf、endpoints）源码核对的形态，用内存任务列表模拟。
// 调用记录只记端点与参数键，不记标题或任务正文。
const invalid = (message) => Object.assign(new Error(message), { code: 'bot/invalid' })

export function createSchedules({ botExists, roomExists }) {
  let enabled = true
  let available = true
  let canPause = true
  let seq = 10
  const calls = []
  let tasks = []

  function reset() {
    const now = Date.now()
    const iso = (deltaMs) => new Date(now + deltaMs).toISOString()
    tasks = [
      {
        id: 'sc-1',
        title: '每日简报',
        prompt: '总结昨天以来的变化和今天需要处理的事。\n第二行：列出未关闭的高优先级事项、负责人与截止时间，便于晨会快速同步。',
        kind: 'daily',
        time: '09:00:00',
        timeZone: 'Asia/Shanghai',
        scheduledAt: iso(20 * 3600_000),
        status: 'active',
        state: 'scheduled',
        sessionId: 's-bot-1',
        botId: 'bot-1',
      },
      {
        id: 'sc-2',
        title: '纽约收市周报',
        prompt: '整理当日行情与要点。',
        kind: 'weekly',
        time: '18:30:00',
        timeZone: 'America/New_York',
        weekdays: [1, 2, 3, 4, 5],
        scheduledAt: iso(30 * 3600_000),
        status: 'paused',
        state: 'paused',
        sessionId: 's-bot-1',
        botId: 'bot-1',
        roomId: 'room-1',
      },
      {
        id: 'sc-3',
        title: '一次性过期提醒',
        prompt: '检查发布脚本的回滚演练是否完成。',
        kind: 'at',
        scheduledAt: iso(-3600_000),
        status: 'active',
        state: 'overdue',
        sessionId: 's-bot-2',
        botId: 'bot-2',
      },
      {
        id: 'sc-4',
        title: '集群健康巡检',
        prompt: '检查磁盘与服务状态并同步到群里。',
        kind: 'every',
        everySeconds: 7200,
        scheduledAt: iso(2 * 3600_000),
        status: 'active',
        state: 'scheduled',
        sessionId: 'room-1',
        roomId: 'room-1',
      },
      {
        id: 'sc-5',
        title: '历史归档任务',
        prompt: '导出季度归档清单。',
        kind: 'cron',
        expression: '0 9 * * 1',
        timeZone: 'Asia/Shanghai',
        scheduledAt: iso(-48 * 3600_000),
        status: 'ended',
        state: 'finished',
        sessionId: 's-bot-2',
        botId: 'bot-2',
      },
    ]
  }
  reset()

  const view = () => (available ? { available: true, canPause, tasks: tasks.map((t) => ({ ...t })) } : { available: false, tasks: [] })

  const lateOnce = (t) => (t.kind === 'at' || t.kind === 'after') && Date.parse(t.scheduledAt) <= Date.now()

  const endpoints = {
    schedules() {
      return view()
    },
    'schedule-create'(p) {
      if (!available) throw invalid('Automation tasks are not enabled in DSH Settings')
      const target = String(p.target ?? '').trim()
      const isBot = botExists(target)
      const isRoom = !isBot && roomExists(target)
      if (!isBot && !isRoom) throw invalid('No such Bot or group chat')
      const title = String(p.title ?? '').trim()
      if (!title) throw invalid('Give the task a name')
      if (title.length > 120) throw invalid('Keep the name under 120 characters')
      const prompt = String(p.prompt ?? '').trim()
      if (!prompt) throw invalid('Say what the Bot should do')
      const w = p.when
      if (!w || typeof w !== 'object') throw invalid('Choose when the task runs')
      const now = Date.now()
      const task = {
        id: 'sc-' + ++seq,
        title,
        prompt,
        kind: String(w.kind ?? ''),
        status: 'active',
        state: 'scheduled',
        sessionId: isBot ? 's-' + target : target,
        ...(isBot ? { botId: target } : { roomId: target }),
      }
      if (w.kind === 'at') {
        const ms = Date.parse(String(w.at ?? ''))
        if (!Number.isFinite(ms) || ms <= now) throw invalid('Pick a time in the future')
        task.scheduledAt = new Date(ms).toISOString()
      } else if (w.kind === 'daily') {
        if (!/^\d\d:\d\d(?::\d\d)?$/.test(String(w.time ?? ''))) throw invalid('Invalid time')
        task.time = String(w.time).length === 5 ? `${w.time}:00` : String(w.time)
        task.timeZone = String(w.timeZone || 'Asia/Shanghai')
        task.scheduledAt = new Date(now + 24 * 3600_000).toISOString()
      } else if (w.kind === 'weekly') {
        if (!/^\d\d:\d\d(?::\d\d)?$/.test(String(w.time ?? ''))) throw invalid('Invalid time')
        const days = Array.isArray(w.weekdays) ? [...new Set(w.weekdays.map(Number))].filter((d) => Number.isInteger(d) && d >= 1 && d <= 7).sort((a, b) => a - b) : []
        if (days.length === 0) throw invalid('Pick at least one day')
        task.time = String(w.time).length === 5 ? `${w.time}:00` : String(w.time)
        task.timeZone = String(w.timeZone || 'Asia/Shanghai')
        task.weekdays = days
        task.scheduledAt = new Date(now + 24 * 3600_000).toISOString()
      } else if (w.kind === 'every') {
        const minutes = Number(w.minutes)
        if (!Number.isInteger(minutes) || minutes < 1 || minutes > 7 * 24 * 60) throw invalid('Repeat every 1 minute to 7 days')
        task.everySeconds = minutes * 60
        task.scheduledAt = new Date(now + minutes * 60_000).toISOString()
      } else {
        throw invalid(`Unsupported schedule kind: ${w.kind}`)
      }
      tasks.push(task)
      return view()
    },
    'schedule-pause'(p) {
      if (!available) throw invalid('Automation tasks are not enabled in DSH Settings')
      if (!canPause) throw invalid('This server does not support pausing tasks')
      const t = tasks.find((x) => x.sessionId === p.sessionId && x.id === p.id)
      if (!t) throw invalid('No such task')
      if (t.status !== 'active' && t.status !== 'paused') throw invalid('Finished tasks cannot be paused')
      if (lateOnce(t)) throw invalid('This one-time task is already due and cannot be paused')
      const active = p.active === true
      t.status = active ? 'active' : 'paused'
      t.state = active ? 'scheduled' : 'paused'
      return view()
    },
    'schedule-delete'(p) {
      if (!available) throw invalid('Automation tasks are not enabled in DSH Settings')
      const idx = tasks.findIndex((x) => x.sessionId === p.sessionId && x.id === p.id)
      if (idx < 0) throw invalid('No such task')
      tasks.splice(idx, 1)
      return view()
    },
  }

  /** 本模块负责的端点；其他端点返回 null。enabled=false 模拟不支持日程的旧版远端（bot/not-found）。 */
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

  // /mock/schedules：?enabled=0|1；?available=0|1；?canPause=0|1；?reset=1；?resetCalls=1
  function set(params) {
    const en = params.get('enabled')
    if (en === '0' || en === '1') enabled = en === '1'
    const av = params.get('available')
    if (av === '0' || av === '1') available = av === '1'
    const cp = params.get('canPause')
    if (cp === '0' || cp === '1') canPause = cp === '1'
    if (params.get('reset') === '1') reset()
    if (params.get('resetCalls') === '1') calls.length = 0
    return { enabled, available, canPause, tasks, calls }
  }

  return { handle, set }
}
