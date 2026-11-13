// android-release — 本地发布脚本（android-release 变更，milestone intake 问题
// distribution 已确认：APK 在本地构建签名后上传到同一版本的 GitHub Release）。
//
// 步骤序列（任一前置不满足即失败退出且不上传）：
//   1. 前置校验：工作区干净、HEAD 正好位于 v<package.json version> tag 提交、
//      对应 GitHub Release 已存在（由现有 Release 工作流创建）、keystore 可用；
//   2. 构建：node scripts/build-android-tstunnel.mts（stamp 幂等）+ assembleRelease；
//   3. 产物：复制为 DSH-Anywhere-<version>.apk 并生成同名 .sha256
//      （版本唯一来源 package.json；APK 的 sha256 是独立文件，不进桌面 SHA256SUMS）；
//   4. 签名核验：apksigner verify --print-certs 与 keystore 侧证书 DN 比对；
//   5. 上传：gh release upload v<version> <apk> <sha256>；同名资产默认拒绝，
//      显式 --overwrite 才 --clobber。
//
// 密钥纪律：不回显 keystore.properties 内容，错误信息不含任何口令。
// 外部命令全部经可注入 Runner（默认 spawnSync），Vitest 以假 Runner 锁定序列。
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { copyFileSync, existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { readPackageVersion } from './package-version.mts'

const repoRoot = fileURLToPath(new URL('..', import.meta.url))

export interface RunnerResult {
  status: number | null
  stdout: string
  stderr: string
  /** spawnSync 自身失败（如 win32 上对无扩展名脚本 ENOENT）时的 error.message，供 fail 文案附加以免掩盖真实原因。 */
  error?: string
}

export interface RunnerOptions {
  cwd?: string
  /** 追加合并进进程环境的变量（如 -storepass:env 的密码），不落在命令行。 */
  env?: NodeJS.ProcessEnv
  /** win32 上执行 .bat / 脚本包装器必须经 shell（node 的 shell 即 cmd /d /s /c 并处理引用）。 */
  shell?: boolean
}

export type Runner = (command: string, args: string[], opts?: RunnerOptions) => RunnerResult

export const spawnRunner: Runner = (command, args, opts) => {
  const base = { cwd: opts?.cwd ?? repoRoot, encoding: 'utf8' as const }
  const env = opts?.env ? { ...process.env, ...opts.env } : undefined
  // DEP0190：node 对 shell+args 只做拼接不做转义——shell 分支在此合成单个显式引用的命令串执行。
  const r =
    opts?.shell === true
      ? spawnSync(shellCommandLine(command, args), env ? { ...base, shell: true, env } : { ...base, shell: true })
      : spawnSync(command, args, env ? { ...base, env } : base)
  return { status: r.status, stdout: r.stdout ?? '', stderr: r.stderr ?? '', ...(r.error ? { error: r.error.message } : {}) }
}

function fail(message: string): never {
  console.error(`android-release: ${message}`)
  process.exit(1)
}

export function keystorePropsPath(env: NodeJS.ProcessEnv = process.env): string {
  return env.DSH_KEYSTORE_PROPS ?? join(env.USERPROFILE ?? env.HOME ?? '.', '.config', 'dsh-anywhere', 'keys', 'keystore.properties')
}

export function apkArtifactName(version: string): string {
  return `DSH-Anywhere-${version}.apk`
}

/** 前置校验（纯判定，外部命令经 Runner，文件存在性可注入）。全部通过返回 null。 */
export function preflight(
  runner: Runner,
  version: string,
  env: NodeJS.ProcessEnv = process.env,
  fileExists: (path: string) => boolean = existsSync,
): string | null {
  // 1. 工作区干净。
  const status = runner('git', ['status', '--porcelain'])
  if (status.status !== 0) return `git status 失败：${status.stderr.trim().slice(0, 120)}`
  if (status.stdout.trim() !== '') return '工作区不干净：先提交或暂存全部改动再发布'

  // 2. HEAD 正好位于 v<version> tag 提交。
  const head = runner('git', ['rev-parse', 'HEAD'])
  const tagged = runner('git', ['rev-parse', `v${version}^{commit}`])
  if (tagged.status !== 0) return `tag v${version} 不存在（升版后先打 tag）`
  if (head.stdout.trim() !== tagged.stdout.trim()) {
    return `HEAD 不在 tag v${version} 指向的提交上（发布必须在 tag 提交执行）`
  }

  // 3. 对应 GitHub Release 已存在。
  const release = runner('gh', ['release', 'view', `v${version}`])
  if (release.status !== 0) {
    return `GitHub Release v${version} 不存在或 gh 未登录（先等 Release 工作流完成；gh auth status 排查）`
  }

  // 4. keystore 可用。
  if (!fileExists(keystorePropsPath(env))) {
    return `签名 keystore 配置缺失：先运行 node scripts/android-keystore.mts 或设置 DSH_KEYSTORE_PROPS`
  }
  return null
}

/** 同名资产是否已存在于 Release（避免误覆盖）。 */
export function assetExists(runner: Runner, tag: string, name: string): boolean {
  const view = runner('gh', ['release', 'view', tag, '--json', 'assets'])
  if (view.status !== 0) return false
  try {
    const assets = JSON.parse(view.stdout) as { assets?: Array<{ name?: string }> }
    return (assets.assets ?? []).some((a) => a.name === name)
  } catch {
    return false
  }
}

/** 从 apksigner 与 keystore 侧输出提取证书 DN 并比对。 */
export function certificatesMatch(apksignerOut: string, keytoolOut: string): boolean {
  const dn = (s: string): string | null => {
    const m = /CN=DSH Anywhere,\s*O=Keepwriting/.exec(s)
    return m ? m[0].replace(/\s+/g, '') : null
  }
  const a = dn(apksignerOut)
  const k = dn(keytoolOut)
  return a !== null && a === k
}

/** gradle 调用按平台选入口：win32 的 CreateProcess 不能执行无扩展名 POSIX 脚本，走 gradlew.bat 且经 shell。 */
export function gradleInvocation(): { command: string; args: string[]; shell: boolean } {
  const win32 = process.platform === 'win32'
  return { command: win32 ? 'android\\gradlew.bat' : 'android/gradlew', args: ['-p', 'android', 'assembleRelease'], shell: win32 }
}

/** build-tools 最新版内的 apksigner 入口：win32 侧只有 apksigner.bat。 */
export function apksignerPath(androidHome: string): string {
  const base = join(androidHome, 'build-tools', readdirSync(join(androidHome, 'build-tools')).sort(compareVersionDirs).at(-1) ?? '', 'apksigner')
  return process.platform === 'win32' ? `${base}.bat` : base
}

/** 点分版本目录的数值比较：字典序会把 9.0.0 排在 34.0.0 之后，必须逐段按数字比。 */
export function compareVersionDirs(a: string, b: string): number {
  const pa = a.split('.').map((x) => Number.parseInt(x, 10) || 0)
  const pb = b.split('.').map((x) => Number.parseInt(x, 10) || 0)
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0)
    if (d !== 0) return d
  }
  return 0
}

/** cmd 引用：含空白的实参加双引号（内嵌引号加倍）。cmd 元字符不在此覆盖，实参现实形态为路径与固定 flag。 */
export function quoteForCmd(arg: string): string {
  return /[\s"]/.test(arg) ? `"${arg.replace(/"/g, '""')}"` : arg
}

/** shell 分支的单命令串：command 与 args 逐个引用后拼接（规避 DEP0190 的 shell+args 仅拼接语义）。 */
export function shellCommandLine(command: string, args: string[]): string {
  return [command, ...args].map(quoteForCmd).join(' ')
}

/** keytool 证书 DN 查询：-v 才输出 DN；密码经 -storepass:env 注入环境，不进命令行。 */
export function keytoolInvocation(storeFile: string, keyAlias: string, storePassword: string): { command: 'keytool'; args: string[]; env: Record<string, string> } {
  return {
    command: 'keytool',
    args: ['-list', '-v', '-keystore', storeFile, '-alias', keyAlias, '-storepass:env', 'DSH_KEYSTORE_STOREPASS'],
    env: { DSH_KEYSTORE_STOREPASS: storePassword },
  }
}

/** 失败文案附加真实原因：spawn error 优先，其次截断的 stderr。 */
function describeFailure(r: RunnerResult): string {
  if (r.error) return `：${r.error}`
  const s = r.stderr.trim()
  return s ? `：${s.slice(0, 120)}` : ''
}

export function main(argv: string[] = process.argv.slice(2), runner: Runner = spawnRunner, env: NodeJS.ProcessEnv = process.env, fileExists: (path: string) => boolean = existsSync): void {
  const overwrite = argv.includes('--overwrite')
  const version = readPackageVersion(join(repoRoot, 'package.json'))
  console.log(`==> android-release v${version}${overwrite ? '（--overwrite 覆盖同名资产）' : ''}`)

  const problem = preflight(runner, version, env, fileExists)
  if (problem) fail(problem)

  const apkName = apkArtifactName(version)
  const tag = `v${version}`
  if (!overwrite && assetExists(runner, tag, apkName)) {
    fail(`Release ${tag} 已有同名资产 ${apkName}：确认后用 --overwrite 覆盖`)
  }

  // 构建：AAR（stamp 幂等）+ assembleRelease。
  const aar = runner('node', ['scripts/build-android-tstunnel.mts'])
  if (aar.status !== 0) fail('AAR 构建失败（scripts/build-android-tstunnel.mts）')
  const gradleCmd = gradleInvocation()
  const gradle = runner(gradleCmd.command, gradleCmd.args, gradleCmd.shell ? { shell: true } : undefined)
  if (gradle.status !== 0) fail(`assembleRelease 失败${describeFailure(gradle)}`)

  // 产物命名 + sha256。
  const outDir = join(repoRoot, 'android', 'app', 'build', 'outputs', 'apk', 'release')
  const built = join(outDir, 'app-release.apk')
  if (!fileExists(built)) fail(`未找到构建产物：${built}`)
  const apk = join(outDir, apkName)
  copyFileSync(built, apk)
  const sha256 = createHash('sha256').update(readFileSync(apk)).digest('hex')
  const shaFile = `${apk}.sha256`
  // 格式与 sha256sum 兼容：<hash>  <filename>（两个空格）。
  writeFileSync(shaFile, `${sha256}  ${apkName}\n`)

  // 签名核验：apksigner 证书 DN 与 keystore 侧一致（Android SDK build-tools）。
  const androidHome = env.ANDROID_HOME ?? join(env.LOCALAPPDATA ?? '', 'Android', 'Sdk')
  const verify = runner(apksignerPath(androidHome), ['verify', '--print-certs', apk], process.platform === 'win32' ? { shell: true } : undefined)
  if (verify.status !== 0) fail(`apksigner verify 失败${describeFailure(verify)}`)
  const props = readFileSync(keystorePropsPath(env), 'utf8')
  const storeFile = /^storeFile=(.+)$/m.exec(props)?.[1]?.trim() ?? fail('keystore.properties 缺少 storeFile')
  const keyAlias = /^keyAlias=(.+)$/m.exec(props)?.[1]?.trim() ?? 'dsh'
  const storePassword = /^storePassword=(.+)$/m.exec(props)?.[1]?.trim()
  if (!storePassword) fail('keystore.properties 缺少 storePassword')
  const kt = keytoolInvocation(storeFile, keyAlias, storePassword)
  const keytool = runner(kt.command, kt.args, { env: kt.env })
  if (keytool.status !== 0) fail(`keytool 读取 keystore 失败${describeFailure(keytool)}`)
  if (!certificatesMatch(verify.stdout, keytool.stdout)) {
    fail('APK 签名证书与本地 keystore 不一致：拒绝上传（android-uniform-signing）')
  }

  // 上传。
  const upload = runner('gh', ['release', 'upload', tag, apk, shaFile, ...(overwrite ? ['--clobber'] : [])])
  if (upload.status !== 0) fail(`gh release upload 失败：${upload.stderr.trim().slice(0, 160)}`)
  console.log(`==> 完成：${apkName} 与 .sha256 已上传到 Release ${tag}`)

  // 自托管更新目录（可选）：DSH_UPDATES_DIR 指向 pca 的 dsh_updates_dir（Windows 侧
  // 经 \\wsl.localhost\... 路径可达）。写入 APK、.sha256 与 latest.json（version_code
  // 按 MAJOR*10000+MINOR*100+PATCH 派生，与 App 侧 VersionSpec 同一公式）。未设置时跳过。
  const updatesDir = env.DSH_UPDATES_DIR
  if (updatesDir) {
    const [major, minor, patch] = version.split('.').map(Number)
    const manifest = {
      version_name: version,
      version_code: major * 10000 + minor * 100 + patch,
      sha256,
      size: statSync(apk).size,
    }
    copyFileSync(apk, join(updatesDir, apkName))
    copyFileSync(shaFile, join(updatesDir, shaFile))
    writeFileSync(join(updatesDir, 'latest.json'), `${JSON.stringify(manifest, null, 2)}\n`)
    console.log(`==> 自托管更新目录已更新：${updatesDir}（latest.json → ${version}）`)
  }
}

// 仅作为 node 直运行的入口脚本时执行；被测试导入时不触发。
if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) {
  main()
}
