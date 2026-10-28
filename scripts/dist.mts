// 打包入口（npm run dist / dist:win / dist:mac / dist:linux）。
// 版本号单一来源（设计决策 version-single-source-via-ci）：git describe --tags
// --exact-match 精确匹配 v 前缀标签（去前缀，未命中为 dev），同一 VERSION 先经
// APP_VERSION 环境变量注入 electron-vite 构建（__APP_VERSION__ define），再经
// extraMetadata.version 注入 electron-builder 包元数据，保证关于页、安装包元数据
// 与文件名三者同源（技术标准 packaging/package-version-from-tag、
// build-identity/electron-identity-single-source）。
import { spawnSync } from 'node:child_process'
import { pathToFileURL } from 'node:url'

// 与 workflow 结构断言测试锁定的版本派生公式保持字符级一致。
export const DESCRIBE_ARGS = ['describe', '--tags', '--match', 'v*', '--exact-match']

export function versionFromDescribe(status: number | null, stdout: string): string {
  const tag = status === 0 ? stdout.trim() : ''
  const version = tag.startsWith('v') ? tag.slice(1) : ''
  return version !== '' ? version : 'dev'
}

export function resolveVersion(): string {
  const result = spawnSync('git', DESCRIBE_ARGS, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore']
  })
  return versionFromDescribe(result.status, result.stdout ?? '')
}

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
  const version = resolveVersion()
  // electron-builder 要求包元数据版本为合法 semver：未打标签的本地/分支构建
  // 回退 0.0.0（产物只作 CI 工件，发布产物只来自 tag 构建）；应用内关于页
  // 仍显示 APP_VERSION 注入的 dev（app-identity/about-shows-dev-for-untagged-build）。
  const packageVersion = version === 'dev' ? '0.0.0' : version
  const targets = process.argv.slice(2)
  run('npm', ['run', 'build'], { APP_VERSION: version })
  run(
    'npx',
    ['electron-builder', ...targets, '--publish', 'never', `--config.extraMetadata.version=${packageVersion}`],
    {}
  )
}

// 仅作为 node 直运行的入口脚本时执行打包；被测试导入时不触发。
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main()
}
