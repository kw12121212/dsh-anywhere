// 群共享待办与任务看板模拟（dsbot-room-todos-tasks，--chat-bars 模式）：state.rooms[] 的 todos 与 tasks，
// 以及 room-todo / task-note / task-cancel。语义照搬 DS-Bot 0.3.10：
// - channel.js room-todo 分支：未知群 room/not-found「Unknown group chat」；action:clear 回 {removed(数量), todos}，
//   action:remove 与勾选回 {item, todos}；不存在的待办 id 回 bot/refused「That to-do is no longer on the list.」
//   （roster.js 抛错经统一失败分支映射，FINDINGS 第 44 节实测）；勾选不带 done 时翻转。
// - tasks.js taskNote/taskCancel：未知群「No such group.」、不存在「No such task.」、已结束「That task is already finished.」、
//   空备注「Write a note first.」，均为 bot/refused；备注 trim 后截 500 字符，成功后以用户身份向群发「@成员名 备注」；
//   成功应答为完整 view（此处回 state 视图）。
// 日志只记端点与参数键，不记录正文。

const OPEN = new Set(['active', 'review', 'blocked'])
const NOTE_MAX = 500

export function createRoomBoard({ bump, botName, pushRoomLine }) {
  const now = Date.now()
  const rooms = {
    'room-1': {
      todos: [
        { id: 'todo-1', text: '确认发布窗口', done: false, createdAt: now - 7_200_000, createdBy: 'bot-1' },
        { id: 'todo-2', text: '准备回滚预案', done: false, createdAt: now - 3_600_000, createdBy: 'bot-2' },
        { id: 'todo-3', text: '整理需求清单', done: true, createdAt: now - 9_000_000, createdBy: 'bot-1', doneAt: now - 8_000_000, doneBy: 'bot-1' },
      ],
      tasks: {
        t_0000aaaa: { id: 't_0000aaaa', botId: 'bot-1', goal: '撰写发布说明', status: 'active', startedAt: now - 3_000_000, updatedAt: now - 60_000, progress: '已完成新功能部分，正在写已知问题' },
        t_0000bbbb: { id: 't_0000bbbb', botId: 'bot-2', goal: '跑完整回归测试', status: 'blocked', startedAt: now - 2_400_000, updatedAt: now - 300_000, progress: '端到端测试进行到一半',
          blocked: { cause: 'model', code: 'QUOTA', status: 402, from: 'active' }, notes: [{ text: '先跑核心模块', by: 'user', at: now - 1_000_000 }] },
        t_0000cccc: { id: 't_0000cccc', botId: 'bot-1', goal: '核对变更清单', status: 'review', startedAt: now - 1_800_000, updatedAt: now - 120_000, progress: '清单已整理，等你确认' },
        t_0000dddd: { id: 't_0000dddd', botId: 'bot-2', goal: '巡检测试环境', status: 'done', startedAt: now - 8_000_000, updatedAt: now - 7_000_000, progress: '', result: '测试环境磁盘与服务均正常' },
        t_0000eeee: { id: 't_0000eeee', botId: 'bot-1', goal: '旧版文档迁移', status: 'cancelled', startedAt: now - 9_500_000, updatedAt: now - 9_000_000, progress: '', reason: '需求取消' },
      },
    },
  }
  const calls = []

  const fail = (message, code = 'bot/refused') => ({ ok: false, error: { code, message, details: {} } })
  const ok = (value) => ({ ok: true, value })

  function roomTodo(body) {
    const room = rooms[String(body.roomId ?? '')]
    if (room === undefined) return fail('Unknown group chat', 'room/not-found')
    if (body.action === 'clear') {
      const removed = room.todos.filter((item) => item.done === true)
      if (removed.length > 0) {
        room.todos = room.todos.filter((item) => item.done !== true)
        bump()
      }
      return ok({ removed: removed.length, todos: room.todos })
    }
    const index = room.todos.findIndex((item) => item.id === String(body.id ?? ''))
    if (index === -1) return fail('That to-do is no longer on the list.')
    if (body.action === 'remove') {
      const [item] = room.todos.splice(index, 1)
      bump()
      return ok({ item, todos: room.todos })
    }
    const item = room.todos[index]
    const next = typeof body.done === 'boolean' ? body.done : item.done !== true
    if (item.done !== next) {
      item.done = next
      if (next) item.doneAt = Date.now()
      else { delete item.doneAt; delete item.doneBy }
      bump()
    }
    return ok({ item, todos: room.todos })
  }

  function openTask(body) {
    const room = rooms[String(body.roomId ?? '')]
    if (room === undefined) return { error: 'No such group.' }
    const task = room.tasks[String(body.id ?? '')]
    if (task === undefined) return { error: 'No such task.' }
    if (!OPEN.has(task.status)) return { error: 'That task is already finished.' }
    return { task }
  }

  function taskNote(body, view) {
    const found = openTask(body)
    if (found.error !== undefined) return fail(found.error)
    const text = String(body.text ?? '').replace(/\s+/g, ' ').trim().slice(0, NOTE_MAX)
    if (text === '') return fail('Write a note first.')
    const task = found.task
    ;(task.notes ??= []).push({ text, by: 'user', at: Date.now() })
    task.updatedAt = Date.now()
    pushRoomLine(String(body.roomId), `@${botName(task.botId) ?? task.botId} ${text}`)
    bump()
    return ok(view())
  }

  function taskCancel(body, view) {
    const found = openTask(body)
    if (found.error !== undefined) return fail(found.error)
    found.task.status = 'cancelled'
    delete found.task.blocked
    found.task.updatedAt = Date.now()
    bump()
    return ok(view())
  }

  return {
    /** 给 state 视图里的群对象补上 todos 与 tasks（字段形态同上游 view() 的房间展开）。 */
    decorate(room) {
      const own = rooms[room.id]
      return own === undefined ? room : { ...room, todos: own.todos, tasks: own.tasks }
    },
    handle(endpoint, body, view) {
      calls.push({ endpoint, keys: Object.keys(body ?? {}).sort() })
      console.log('  room-board:', endpoint, Object.keys(body ?? {}).sort().join(','))
      if (endpoint === 'room-todo') return roomTodo(body ?? {})
      if (endpoint === 'task-note') return taskNote(body ?? {}, view)
      return taskCancel(body ?? {}, view)
    },
    calls: () => calls,
  }
}
