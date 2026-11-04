// android-keystore — 生成 dsh-anywhere Android 专用 release keystore。
//
// 里程碑 android-app 的 intake 问题 android-identity 已确认：debug 与 release 构建一
// 律用同一份专用 release keystore 签名（APK 自更新只接受同签名升级包；tech/android-
// client 的 android-uniform-signing 规则）；keystore 存放在 ~/.config/dsh-anywhere/
// keys/，不进仓库，用户须自行备份（丢失后已发布 APK 无法再覆盖升级）。
//
// 本脚本做三件事：
//   1. keytool 生成 RSA 4096 keystore（release.keystore，别名 dsh）；
//   2. 生成随机口令并写入同目录 keystore.properties（0600，不入库）——
//      android/app/build.gradle.kts 从这里读取签名配置；
//   3. 打印备份提醒。
//
// 已存在 keystore 时拒绝执行（不覆盖：覆盖即让所有已装 APK 无法升级）。
// 环境变量：DSH_KEYSTORE_DIR 覆盖存放目录；DSH_KEYTOOL 覆盖 keytool 路径（测试注入
// 假 runner 用）。依赖 JDK 的 keytool；Vitest 不真实执行 keytool，由
// android-keystore.test 以注入 runner 验证行为。
import { spawnSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { chmodSync, existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

export interface KeystorePaths {
  keystoreDir: string
  keystore: string
  props: string
}

export interface CommandResult {
  status: number | null
  error?: Error
}

export type CommandRunner = (command: string, args: string[]) => CommandResult

/** 默认 runner：同步执行外部命令，失败信息直接透传。 */
export const spawnRunner: CommandRunner = (command, args) => spawnSync(command, args, { stdio: 'inherit' })

/** 解析 keystore 存放目录与两个产物路径（DSH_KEYSTORE_DIR 覆盖，测试用）。 */
export function keystorePaths(env: NodeJS.ProcessEnv = process.env): KeystorePaths {
  const keystoreDir = env.DSH_KEYSTORE_DIR ?? join(homedir(), '.config', 'dsh-anywhere', 'keys')
  return {
    keystoreDir,
    keystore: join(keystoreDir, 'release.keystore'),
    props: join(keystoreDir, 'keystore.properties')
  }
}

/**
 * 生成 keystore 与签名配置。返回写出的 keystore.properties 内容。
 * keystore 已存在、keytool 缺失或失败时抛错（不覆盖、不静默回退）。
 */
export function generateKeystore(env: NodeJS.ProcessEnv = process.env, runner: CommandRunner = spawnRunner): string {
  const { keystoreDir, keystore, props } = keystorePaths(env)
  const keytool = env.DSH_KEYTOOL ?? 'keytool'
  if (existsSync(keystore)) {
    throw new Error(
      `keystore 已存在：${keystore}\n不会覆盖——覆盖会让所有已装 APK 无法升级。若确要重来，先自行备份并删除旧文件。`
    )
  }
  // PKCS12 keystore 不支持 store/key 不同口令（keytool 会忽略 -keypass），统一同口令。
  const storePass = randomBytes(12).toString('hex')
  mkdirSync(keystoreDir, { recursive: true })
  const result = runner(keytool, [
    '-genkeypair',
    '-keystore',
    keystore,
    '-alias',
    'dsh',
    '-keyalg',
    'RSA',
    '-keysize',
    '4096',
    '-validity',
    '10000',
    '-storepass',
    storePass,
    '-keypass',
    storePass,
    '-dname',
    'CN=DSH Anywhere, O=Keepwriting'
  ])
  if (result.error) {
    throw new Error(`未找到 keytool（需要 JDK）：${result.error.message}`)
  }
  if (result.status !== 0) {
    throw new Error(`keytool 生成失败（退出码 ${result.status}）`)
  }
  const content = [
    '# 由 scripts/android-keystore.mts 生成；不进仓库，请随 keystore 一起备份。',
    // java.util.Properties 把反斜杠当转义符，Windows 路径必须写正斜杠（java.io.File 两者都认）。
    `storeFile=${keystore.replaceAll('\\', '/')}`,
    `storePassword=${storePass}`,
    'keyAlias=dsh',
    `keyPassword=${storePass}`,
    ''
  ].join('\n')
  // 初落盘即 0600（Windows 上 chmod 为建议性，仍尽量收紧），不给 0644 留窗口。
  writeFileSync(props, content, { mode: 0o600 })
  chmodSync(props, 0o600)
  return content
}

function main(): void {
  generateKeystore()
  const { keystore, props } = keystorePaths()
  console.log(`已生成 keystore：${keystore}`)
  console.log(`签名配置：${props}（android/app 构建从这里读取；口令只写入该文件，不打印到日志）`)
  console.error('提醒：请把 keys/ 目录（keystore 与 properties）自行备份——丢失后已发布 APK 无法再覆盖升级。')
}

// 仅作为 node 直运行的入口脚本时执行生成；被测试导入时不触发。
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main()
  } catch (e) {
    console.error(e instanceof Error ? e.message : String(e))
    process.exit(1)
  }
}
