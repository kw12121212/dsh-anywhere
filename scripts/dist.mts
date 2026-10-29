// 打包入口（npm run dist / dist:win / dist:mac / dist:linux）。
// 版本号单一来源（技术标准 packaging/package-version-single-source）：package.json
// 的 version 字段。同一 VERSION 经 electron-vite 构建配置的 __APP_VERSION__ define
// 注入关于页（两者都读同一 package.json），再经 extraMetadata.version 注入
// electron-builder 包元数据与 artifactName 文件名；发布 tag 必须等于 v + 版本号，
// 由 release.yml 的 verify-tag job 在打包前校验。
import { spawnSync } from 'node:child_process'
import { resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { readPackageVersion } from './package-version.mts'

const repoRoot = fileURLToPath(new URL('..', import.meta.url))

function run(command: string, args: string[], env: NodeJS.ProcessEnv): void {
  // Windows 上 npm/npx 是 .cmd 脚本，必须经 shell 调用。
  const result = spawnSync(command, args, {
    stdio: 'inherit',
    env: { ...process.env, ...env },
    shell: process.platform === 'win32'
  })
  if (result.status !== 0) {
    process.exit(result.status ?? 1)
  }
}

function main(): void {
  // 版本非法（非 x.y.z、package.json 损坏）时 readPackageVersion 直接抛错退出，
  // 不再有 dev/0.0.0 静默回退：回退正是版本漂移的来源。
  const version = readPackageVersion(resolve(repoRoot, 'package.json'))
  const targets = process.argv.slice(2)
  run('npm', ['run', 'build'], {})
  run(
    'npx',
    ['electron-builder', ...targets, '--publish', 'never', `--config.extraMetadata.version=${version}`],
    {}
  )
}

// 仅作为 node 直运行的入口脚本时执行打包；被测试导入时不触发。
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main()
}
