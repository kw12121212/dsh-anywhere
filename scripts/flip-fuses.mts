// 用法：npm run flip-fuses -- <Electron 可执行文件或 .app 路径>
import { applyFuses } from './fuses.mts'

const target = process.argv[2]
if (!target) {
  console.error('usage: npm run flip-fuses -- <path-to-electron>')
  process.exit(2)
}
await applyFuses(target)
