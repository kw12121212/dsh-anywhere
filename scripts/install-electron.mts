// electron npm 包自 44 起发布时不再携带 postinstall 脚本（发布包的 package.json
// 没有 scripts 字段），npm ci / npm install 后不会自动下载平台二进制；dev 模式与
// e2e（Playwright 启动 Electron）都依赖 node_modules/electron/dist。本脚本挂为根包
// postinstall：dist 已存在时跳过（幂等），否则执行 electron 自带的 install.js 补装，
// 失败即失败，让完整回归命令（npm ci 起）在任何机器端到端可跑。
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { join } from 'node:path'

const installScript = join('node_modules', 'electron', 'install.js')
const distDir = join('node_modules', 'electron', 'dist')

if (!existsSync(installScript)) {
  // electron 不在依赖里（或包布局再变）时不阻断安装，留给后续步骤按需报错。
  console.log(`install-electron: ${installScript} 不存在，跳过二进制补装`)
} else if (!existsSync(distDir)) {
  const result = spawnSync(process.execPath, [installScript], { stdio: 'inherit' })
  if (result.status !== 0) {
    process.exit(result.status ?? 1)
  }
}
