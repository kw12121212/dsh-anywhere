// 群内搜索模拟（dsbot-group-search）：group-search {roomId, query, before?, offset?}。
// 语义照搬 DS-Bot 0.3.10 src/host/group-search.js searchGroupBook 与 channel.js 的 group-search 分支：
// 未知群回 bot/not-found「Unknown group chat」；查询小写、截 200 字符、按空白切分去重、最多 8 词，全部词须命中；
// 按新到旧、每页 20 条；before（整数 seq）只取更旧记录，offset 跳过前 N 条命中；hasMore 仅在确有下一条命中时为 true；
// 无 text 的记录跳过；命中带前后邻居（邻居无 text 时不带）。用户自己的消息带 name 不带 botId（FINDINGS 第 42 节实测）。

const SEARCH_MAX = 20
const TERMS_MAX = 8
const QUERY_MAX = 200

const MEMBERS = [
  { botId: 'bot-1', name: '研究主管' },
  { botId: 'bot-2', name: '运维值班' },
]

// 群账本（旧到新）：54 条带「发布」/release 的消息（release 便于 adb 输入）足以触发两次加载更多；第 30 条为无 text 的暂停记录
function bookOf(now) {
  const records = []
  for (let seq = 1; seq <= 60; seq += 1) {
    const time = now - (61 - seq) * 3_600_000
    if (seq === 30) {
      records.push({ seq, time, kind: 'pause' })
      continue
    }
    const user = seq % 4 === 1
    const topic = seq % 12 === 0 ? 'Deploy window confirmed for Saturday' : `第 ${seq} 条：发布计划${seq % 3 === 0 ? '与回滚预案' : ''}（release plan ${seq}）`
    const long = seq === 58 ? '背景说明。'.repeat(40) + '这里提到发布窗口需要再确认一次。' + '补充细节。'.repeat(30) : ''
    const text = `${long}${topic}`
    records.push(user ? { seq, time, text, who: 'wx' } : { seq, time, text, botId: MEMBERS[seq % 2].botId, who: MEMBERS[seq % 2].name })
  }
  return records
}

function lineOf(record) {
  if (typeof record?.text !== 'string' || record.text.trim() === '') return undefined
  const line = { seq: record.seq, time: record.time, text: record.text }
  if (record.botId !== undefined) line.botId = record.botId
  if (record.who !== undefined) line.name = record.who
  return line
}

function searchGroupBook(records, query, page) {
  const terms = [...new Set(String(query ?? '').toLowerCase().slice(0, QUERY_MAX).split(/\s+/).filter(Boolean))].slice(0, TERMS_MAX)
  if (terms.length === 0) return { results: [], hasMore: false }
  const offset = Number.isInteger(page.offset) && page.offset > 0 ? page.offset : 0
  const before = Number.isInteger(page.before) ? page.before : undefined
  const results = []
  let skipped = 0
  let hasMore = false
  for (let at = records.length - 1; at >= 0; at -= 1) {
    const record = records[at]
    if (before !== undefined && !(Number.isInteger(record?.seq) && record.seq < before)) continue
    const text = typeof record?.text === 'string' ? record.text.toLowerCase() : ''
    if (text === '' || !terms.every(term => text.includes(term))) continue
    const hit = lineOf(record)
    if (hit === undefined) continue
    if (skipped < offset) {
      skipped += 1
      continue
    }
    if (results.length === SEARCH_MAX) {
      hasMore = true
      break
    }
    results.push({
      ...hit,
      before: at > 0 ? lineOf(records[at - 1]) : undefined,
      after: at + 1 < records.length ? lineOf(records[at + 1]) : undefined,
    })
  }
  return { results, hasMore }
}

const started = Date.now()

/** 处理 group-search；返回应答信封（ok/value 或 ok:false/error）。 */
export function handleGroupSearch(body, roomIds = ['room-1']) {
  if (!roomIds.includes(body?.roomId)) return { ok: false, error: { code: 'bot/not-found', message: 'Unknown group chat' } }
  const value = searchGroupBook(bookOf(started), body.query, { before: body.before, offset: body.offset })
  console.log(`  group-search query=${JSON.stringify(body.query)} before=${body.before ?? '-'} offset=${body.offset ?? '-'} → ${value.results.length} hits hasMore=${value.hasMore}`)
  return { ok: true, value }
}
