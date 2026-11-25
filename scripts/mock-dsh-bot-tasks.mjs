// mock-dsh-server 的后台任务详情支持（dsbot-bot-task-detail 验收）：
// 供 --chat-bars 与 --manage 共用，对齐 DS-Bot 0.3.10 channel.js / bot-tasks.js / parts.js taskPage 形态。
// 日志与调用记录只记端点与参数键，不记录消息正文。

const ACTIVE = new Set(['running', 'waiting_work', 'waiting_approval'])

export function createBotTasks({ bump, botExists }) {
  let state = initState()

  function initState() {
    const now = Date.now()
    return {
      failSend: '', // '' | 'invalid' | 'readonly'
      seq: 20,
      calls: [],
      tasks: [
        {
          id: 'task-1',
          botId: 'bot-1',
          title: '整理本周市场数据',
          status: 'running',
          origin: 'fg-handoff',
          sessionId: 't-1',
          startedAt: now - 95_000,
          updatedAt: now - 5_000,
          running: true,
          earlier: [
            { kind: 'task-brief', text: '请整理本周各渠道市场数据，输出对比结论。', time: now - 94_000, seq: 1, turn: 1, sessionId: 't-1' },
            { kind: 'bot', text: '已拉取第一批渠道数据，正在交叉核对。', time: now - 80_000, seq: 2, turn: 1, sessionId: 't-1' },
          ],
          earlierSteps: [
            { kind: 'steps', turn: 1, ms: 1850, time: now - 80_000, tools: [
              { name: 'web_search', label: 'Searching online', ok: true },
              { name: 'read_file', label: 'Opening a file', ok: true },
            ] },
          ],
          latest: [
            { kind: 'bot', text: '## 本周数据初稿\n\n- 华东增长 **12%**\n- 华南增长 **8%**', time: now - 15_000, seq: 10, turn: 2, sessionId: 't-1' },
          ],
          latestSteps: [
            { kind: 'steps', turn: 2, ms: 920, time: now - 15_000, tools: [
              { name: 'bash', label: 'Running shell commands', ok: true },
            ] },
          ],
        },
        {
          id: 'task-2',
          botId: 'bot-1',
          title: '核对报价表',
          status: 'waiting_approval',
          origin: 'bot-handoff',
          sessionId: 't-2',
          startedAt: now - 40_000,
          updatedAt: now - 2_000,
          running: false,
          earlier: [],
          earlierSteps: [],
          latest: [
            { kind: 'task-brief', name: '采购助理', text: '请核对 Q4 报价表中的单价与折扣。', time: now - 39_000, seq: 1, turn: 1, sessionId: 't-2' },
            { kind: 'bot', text: '发现两项折扣差异，等待审批确认后继续。', time: now - 10_000, seq: 2, turn: 1, sessionId: 't-2' },
          ],
          latestSteps: [
            { kind: 'steps', turn: 1, ms: 1400, time: now - 10_000, tools: [
              { name: 'read_file', label: 'Opening a file', ok: true },
              { name: 'bash', label: 'Running shell commands', ok: false },
            ] },
          ],
        },
        {
          id: 'task-0',
          botId: 'bot-1',
          title: '已完成任务（不应显示）',
          status: 'completed',
          sessionId: 't-0',
          startedAt: now - 180_000,
          updatedAt: now - 60_000,
          endedAt: now - 60_000,
          summary: '历史任务已完成。',
          running: false,
          earlier: [],
          earlierSteps: [],
          latest: [
            { kind: 'bot', text: '历史任务已完成。', time: now - 60_000, seq: 1, turn: 1, sessionId: 't-0' },
          ],
          latestSteps: [],
        },
      ],
    }
  }

  function tasksViewOf(botId) {
    return state.tasks
      .filter((t) => t.botId === botId)
      .map((t) => ({
        id: t.id,
        title: t.title,
        status: t.status,
        sessionId: t.sessionId,
        ...(t.origin ? { origin: t.origin } : {}),
        startedAt: t.startedAt,
        updatedAt: t.updatedAt,
        ...(t.endedAt !== undefined ? { endedAt: t.endedAt } : {}),
        ...(t.summary ? { summary: t.summary } : {}),
        ...(t.error ? { error: t.error } : {}),
        running: t.running,
      }))
  }

  function findTask(botId, taskId) {
    return state.tasks.find((t) => t.botId === botId && t.id === taskId)
  }

  function handle(endpoint, p, ok, fail) {
    if (!['bot-task-read', 'bot-task-send', 'bot-stop-task'].includes(endpoint)) return null
    state.calls.push({ endpoint, keys: Object.keys(p ?? {}).sort() })
    const botId = String(p?.botId ?? '')
    if (!botExists(botId)) return fail('Unknown Bot', 'bot/not-found')
    const taskId = String(p?.taskId ?? '')
    const task = findTask(botId, taskId)
    if (!task) return fail('Unknown task', 'bot/not-found')

    if (endpoint === 'bot-task-read') {
      const steps = p?.steps === true
      const beforeSeq = p?.cursor?.beforeSeq
      if (Number.isInteger(beforeSeq)) {
        const items = [...task.earlier, ...(steps ? task.earlierSteps : [])]
        return ok({ sessionId: task.sessionId, items, cursor: null })
      }
      const items = [...task.latest, ...(steps ? task.latestSteps : [])]
      const cursor = task.earlier.length > 0 ? { part: 0, beforeSeq: task.latest[0]?.seq ?? 10 } : null
      return ok({ sessionId: task.sessionId, items, cursor })
    }

    if (endpoint === 'bot-task-send') {
      if (state.failSend === 'readonly') return fail('Team is read-only', 'bot/read-only')
      if (state.failSend === 'invalid') return fail('Simulated send error', 'bot/invalid')
      if (!ACTIVE.has(task.status)) {
        return fail(`Task "${task.title}" is ${task.status} and cannot receive messages.`, 'bot/invalid')
      }
      const text = String(p?.text ?? '').trim()
      if (text === '') return fail('Message text is required', 'bot/invalid')
      const now = Date.now()
      const nextSeq = ++state.seq
      const turn = (task.latest.at(-1)?.turn ?? 2) + 1
      task.latest.push({ kind: 'user', text, time: now, seq: nextSeq, turn, sessionId: task.sessionId })
      task.updatedAt = now
      bump()
      return ok({ botId, taskId: task.id, sessionId: task.sessionId })
    }

    if (endpoint === 'bot-stop-task') {
      if (ACTIVE.has(task.status)) {
        const now = Date.now()
        task.status = 'stopped'
        task.running = false
        task.updatedAt = now
        task.endedAt = now
        bump()
      }
      return ok(true)
    }
    return null
  }

  function set(params) {
    if (params.get('reset') === '1') {
      state = initState()
      bump()
    }
    if (params.get('resetCalls') === '1') state.calls = []
    const failSend = params.get('failSend')
    if (failSend !== null) state.failSend = failSend === '0' ? '' : failSend
    const id = params.get('id')
    const status = params.get('status')
    if (id && status) {
      const task = state.tasks.find((t) => t.id === id)
      if (task) {
        const now = Date.now()
        task.status = status
        task.running = status === 'running'
        task.updatedAt = now
        if (!ACTIVE.has(status)) task.endedAt = now
        else delete task.endedAt
        const summary = params.get('summary')
        if (summary !== null) task.summary = summary
        const err = params.get('error')
        if (err !== null) task.error = err
        bump()
      }
    }
    return {
      failSend: state.failSend,
      tasks: state.tasks.map((t) => ({ id: t.id, botId: t.botId, status: t.status, latestCount: t.latest.length })),
      calls: state.calls,
    }
  }

  return { tasksViewOf, handle, set }
}
