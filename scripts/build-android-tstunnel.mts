// android/tstunnel Go 模块（自写薄包装，包 tailscale.com/tsnet，App 进程内
// userspace tailnet 客户端）经 gomobile 构建出 android/app/libs/tstunnel.aar，
// 供 :app 模块链接。
//
// 为什么不进完整回归命令（tech/android-client 的 android-build-outside-gate）：
// 依赖 Go 工具链 + Android SDK/NDK（真实外部环境），进门禁会让门禁结果取决于
// 开发机当下的状态。它是构建入口不是取证脚本：不做行为断言，只校验环境。
//
// AAR 不入库（.gitignore 覆盖 android/app/libs/）：gomobile/gobind 与
// tailscale.com 的版本钉在 android/tstunnel/go.mod 的 tool 指令与 require 里。
//
// 用法：node scripts/build-android-tstunnel.mts [NDK 版本，缺省自动探测最新]
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = fileURLToPath(new URL('..', import.meta.url))
const goDir = resolve(repoRoot, 'android', 'tstunnel')
const appLibs = resolve(repoRoot, 'android', 'app', 'libs')
const aar = join(appLibs, 'tstunnel.aar')
const stamp = join(appLibs, 'aar.stamp')

function fail(message: string): never {
  console.error(`build-android-tstunnel: ${message}`)
  process.exit(1)
}

function run(command: string, args: string[], env: NodeJS.ProcessEnv = {}, cwd?: string): void {
  // gomobile/go 是真实可执行文件：不经 shell，带空格的 -ldflags 才不会被拆散。
  const result = spawnSync(command, args, {
    stdio: 'inherit',
    cwd: cwd ?? repoRoot,
    env: { ...process.env, ...env },
  })
  if (result.status !== 0) {
    fail(`${command} ${args.join(' ')} 失败（退出码 ${result.status}）`)
  }
}

// ── 环境校验 ──
if (spawnSync('go', ['version'], { shell: process.platform === 'win32' }).status !== 0) {
  fail('未找到 go（模块要求 go ≥1.26）')
}
const androidHome = process.env.ANDROID_HOME ?? join(process.env.LOCALAPPDATA ?? '', 'Android', 'Sdk')
if (!existsSync(join(androidHome, 'platforms'))) {
  fail(`ANDROID_HOME=${androidHome} 下没有 platforms/，先装 Android SDK`)
}
const ndkDir = join(androidHome, 'ndk')
if (!existsSync(ndkDir)) fail(`ANDROID_HOME=${androidHome} 下没有 ndk/，先用 sdkmanager 安装 NDK`)
const ndkVersion = process.argv[2] ?? readdirSync(ndkDir).sort().at(-1)
const ndkHome = join(androidHome, 'ndk', ndkVersion ?? '')
if (!existsSync(ndkHome)) {
  fail(`NDK 不存在：${ndkHome}（sdkmanager --install "ndk;${ndkVersion}"）`)
}
console.log(`==> ANDROID_HOME=${androidHome} NDK=${ndkVersion}`)

// ── stamp 缓存：源未变且 AAR 存在时整脚本早退（gomobile bind 5-8 分钟）──
function tstunnelHash(): string {
  const files = readdirSync(goDir)
    .filter((f) => f === 'go.mod' || f === 'go.sum' || f.endsWith('.go'))
    .sort()
  const h = createHash('sha256')
  for (const f of files) h.update(readFileSync(join(goDir, f)))
  return h.digest('hex')
}
if (existsSync(aar) && existsSync(stamp) && readFileSync(stamp, 'utf8') === tstunnelHash()) {
  console.log(`==> tstunnel 源未变且 AAR 存在（stamp 命中），跳过重建：${aar}`)
  process.exit(0)
}

// ── 安装 go.mod tool 指令钉住的 gomobile/gobind 到模块本地 ──
const gobin = join(goDir, '.gobin')
mkdirSync(gobin, { recursive: true })
run('go', ['install', 'golang.org/x/mobile/cmd/gomobile', 'golang.org/x/mobile/cmd/gobind'], { GOBIN: gobin }, goDir)
const pathSep = process.platform === 'win32' ? ';' : ':'
const gobinPath = `${gobin}${pathSep}${process.env.PATH ?? ''}`

// ── gomobile bind（与 tailscale-android 上游同参：api 26 + 16KB page-size 对齐）──
mkdirSync(appLibs, { recursive: true })
run(
  process.platform === 'win32' ? join(gobin, 'gomobile.exe') : join(gobin, 'gomobile'),
  [
    'bind', '-target', 'android', '-androidapi', '26',
    '-ldflags', '-linkmode=external -extldflags=-Wl,-z,max-page-size=16384',
    '-o', aar, '.',
  ],
  { ANDROID_HOME: androidHome, ANDROID_NDK_HOME: ndkHome, PATH: gobinPath },
  goDir,
)
if (!existsSync(aar) || !statSync(aar).isFile()) fail(`AAR 未生成：${aar}`)
console.log(`==> 完成：${aar}（${(statSync(aar).size / 1024 / 1024).toFixed(1)} MB）`)
writeFileSync(stamp, tstunnelHash())
