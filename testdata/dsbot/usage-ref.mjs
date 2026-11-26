// 用上游网页端算法（ds-bot 0.3.10 client.js 的 src/client/usage-data.js 原文）对用量夹具计算期望值，供 UsageTest 全量比对。
// 用法：node usage-ref.mjs <client.js 路径> <usage 夹具> > 期望.json
import { readFileSync } from 'node:fs'
const client = readFileSync(process.argv[2], 'utf8')
const src = client.slice(client.indexOf('// src/client/usage-data.js'), client.indexOf('// src/client/usage-page.js'))
const { RANGES, spanRange, summarize, compact, percent2, OWNER, MODEL } = new Function(src + '\nreturn { RANGES, spanRange, summarize, compact, percent2, OWNER, MODEL }')()
const view = JSON.parse(readFileSync(process.argv[3], 'utf8')).value
const isBot = (at) => ['bot', 'agent'].includes(view.owners[at]?.kind)
const ranked = (map) => [...map].filter(([, s]) => s.total > 0).sort((a, b) => b[1].total - a[1].total).map(([at, s]) => [at, s.total])
const customs = [['2026-11-24', '2026-11-25'], ['2026-11-25', '2026-11-25'], ['2026-10-01', '2026-11-25'], ['2026-11-25', '2026-10-01'], ['2026-08-01', '2026-11-25'], ['2025-12-01', '2026-11-25'], ['2025-11-15', '2026-02-10']]
const ranges = [...RANGES.map((r) => ({ ...r, name: r.id })), ...customs.map(([a, b]) => ({ ...spanRange(a, b), name: `${a}~${b}`, a, b }))]
const shape = (s) => ({
  keys: s.buckets.map((b) => b.key),
  sums: s.buckets.map((b) => [b.sum.input, b.sum.cacheRead, b.sum.output, b.sum.calls, b.sum.total]),
  stacks: s.buckets.map((b) => [...b.stacks]),
  total: [s.total.input, s.total.cacheRead, s.total.output, s.total.calls, s.total.total],
  previous: s.previous,
  owners: ranked(s.owners), models: ranked(s.models), kinds: ranked(s.kinds),
})
const out = { ranges: [] }
for (const range of ranges) {
  out.ranges.push({
    name: range.name, from: range.a ?? null, to: range.b ?? null, unit: range.unit, count: range.count, stride: range.stride, days: range.days ?? 0,
    byBot: shape(summarize(view, range, undefined, (row) => (isBot(row[OWNER]) ? `o${row[OWNER]}` : 'rest'))),
    byModel: shape(summarize(view, range, undefined, (row) => `m${row[MODEL]}`)),
    owner1: shape(summarize(view, range, (row) => row[OWNER] === 1)),
    model2: shape(summarize(view, range, (row) => row[MODEL] === 2)),
  })
}
out.compact = [0, 999, 1000, 1234, 9995, 99949, 999499, 999500, 1000000, 1234567, 12345678, 123456789, 999500000, 2500000000].map((n) => [n, compact(n)])
out.percent = [[0, 10], [5, 1000], [1, 3], [2, 3], [1, 1], [3, 0], [0.5, 1], [0.004, 1]].map(([a, b]) => [a, b, percent2(a, b)])
process.stdout.write(JSON.stringify(out))
