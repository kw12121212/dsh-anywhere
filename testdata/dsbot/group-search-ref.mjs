// 用上游网页端算法（ds-bot 0.3.10 client.js 的 src/client/group-search.js 原文：termsOf、snippetOf、marked）
// 对一组查询与文本计算查询词、片段与高亮区间，供 GroupSearchTest 全量比对（tech/dsbot-api upstream-client-algorithm-parity）。
// 用法：node group-search-ref.mjs <client.js 路径> > group-search-expected.json
import { readFileSync } from 'node:fs'

const client = readFileSync(process.argv[2], 'utf8')
const begin = client.indexOf('// src/client/group-search.js')
const end = client.indexOf('// src/client/paste-card.js')
if (begin < 0 || end < begin) throw new Error('group-search.js module boundary not found')
const src = client.slice(begin, end)
// marked 以 React 元素包裹命中片段：用最小替身把 <mark> 还原为可计算的结构
const fakeReact = { createElement: (tag, props, child) => ({ tag, child }) }
const require = (name) => {
  if (name !== 'react') throw new Error(`unexpected require ${name}`)
  return fakeReact
}
const { termsOf, snippetOf, marked } = new Function('require', src + '\nreturn { termsOf, snippetOf, marked }')(require)

// marked 的输出（字符串或字符串与 mark 元素交替的数组）→ 原文上的 [start, end) 区间
function rangesOf(text, terms) {
  const out = marked(text, terms)
  if (typeof out === 'string') return []
  const ranges = []
  let at = 0
  for (const part of out) {
    if (typeof part === 'string') {
      at += part.length
    } else {
      ranges.push([at, at + part.child.length])
      at += part.child.length
    }
  }
  if (at !== text.length) throw new Error('marked parts do not cover the text')
  return ranges
}

const long = (head, word, tail) => 'a'.repeat(head) + word + 'b'.repeat(tail)
const cases = [
  ['deploy', 'Deploy window confirmed for Saturday; DEPLOY again'],
  ['  Deploy　计划  deploy DEPLOY ', '计划：deploy 与 Deploy 计划'],
  ['a.b (x) [y]', 'a.b then axb (x) [y] \\d'],
  ['ab abc cd', 'abcd abc ab cd'],
  ['release', long(100, 'RELEASE', 300)],
  ['release', 'RELEASE' + 'b'.repeat(300)],
  ['release', 'c'.repeat(300) + 'release'],
  ['alpha beta', 'c'.repeat(200) + 'beta' + 'c'.repeat(50) + 'alpha' + 'c'.repeat(100)],
  ['none', 'x'.repeat(181)],
  ['short', 'x'.repeat(180)],
  ['发布', '背景说明。'.repeat(40) + '这里提到发布窗口需要再确认一次。' + '补充细节。'.repeat(30)],
  ['kelvin', 'temperature 5Kelvin and kelvin'],
  ['straße', 'STRASSE straße Straße'],
  ['ſun', 'SUN sun ſun'],
  ['i̇', 'İstanbul i̇stanbul'],
  ['😀 ok', 'hi 😀 there OK ok ' + 'z'.repeat(200) + '😀'],
  ['a b', 'a b a b'],
  ['﻿bom', 'bom ﻿bom'],
  ['ΣΑΣ', 'σας ΣΑΣ σαΣ'],
]

const out = cases.map(([query, text]) => {
  const terms = termsOf(query)
  const snippet = snippetOf(text, terms)
  return { query, text, terms, snippet, textRanges: rangesOf(text, terms), snippetRanges: rangesOf(snippet, terms) }
})
process.stdout.write(JSON.stringify(out, null, 1) + '\n')
