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
import { copyFileSync, existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { readPackageVersion } from './package-version.mts'

const repoRoot = fileURLToPath(new URL('..', import.meta.url))

export interface RunnerResult {
  status: number | null
  stdout: string
  stderr: string
}

export type Runner = (command: string, args: string[], cwd?: string) => RunnerResult

export const spawnRunner: Runner = (command, args, cwd) => {
  const r = spawnSync(command, args, { cwd: cwd ?? repoRoot, encoding: 'utf8' })
  return { status: r.status, stdout: r.stdout ?? '', stderr: r.stderr ?? '' }
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
  const gradle = runner('android/gradlew', ['-p', 'android', 'assembleRelease'])
  if (gradle.status !== 0) fail('assembleRelease 失败')

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
  const apksigner = join(androidHome, 'build-tools', readdirSync(join(androidHome, 'build-tools')).sort().at(-1) ?? '', 'apksigner')
  const verify = runner(apksigner, ['verify', '--print-certs', apk])
  const props = readFileSync(keystorePropsPath(env), 'utf8')
  const storeFile = /^storeFile=(.+)$/m.exec(props)?.[1]?.trim() ?? fail('keystore.properties 缺少 storeFile')
  const keytool = runner('keytool', ['-list', '-keystore', storeFile, '-alias', /^keyAlias=(.+)$/m.exec(props)?.[1]?.trim() ?? 'dsh'])
  if (!certificatesMatch(verify.stdout, keytool.stdout)) {
    fail('APK 签名证书与本地 keystore 不一致：拒绝上传（android-uniform-signing）')
  }

  // 上传。
  const upload = runner('gh', ['release', 'upload', tag, apk, shaFile, ...(overwrite ? ['--clobber'] : [])])
  if (upload.status !== 0) fail(`gh release upload 失败：${upload.stderr.trim().slice(0, 160)}`)
  console.log(`==> 完成：${apkName} 与 .sha256 已上传到 Release ${tag}`)
}

// 仅作为 node 直运行的入口脚本时执行；被测试导入时不触发。
if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) {
  main()
}
