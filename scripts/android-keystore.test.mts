// android-keystore 的行为测试：以注入 runner 代替真实 keytool，验证生成参数、
// properties 内容、口令形态与「已存在即拒绝」的负路径（不真实执行 keytool）。
import { mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterAll, describe, expect, it } from 'vitest'
import { generateKeystore, keystorePaths } from './android-keystore.mts'
import type { CommandRunner } from './android-keystore.mts'

const tmp = mkdtempSync(join(tmpdir(), 'dsh-keystore-test-'))
afterAll(() => rmSync(tmp, { recursive: true, force: true }))

// 假 keytool：记录收到的参数，并像真 keytool 一样落盘 keystore 文件。
function fakeKeytool(env: NodeJS.ProcessEnv, seen: string[][]): CommandRunner {
  return (_command, args) => {
    seen.push(args)
    const { keystore } = keystorePaths(env)
    writeFileSync(keystore, 'fake-keystore')
    return { status: 0 }
  }
}

function tempEnv(dir: string): NodeJS.ProcessEnv {
  return { ...process.env, DSH_KEYSTORE_DIR: dir }
}

describe('keystorePaths', () => {
  it('DSH_KEYSTORE_DIR 覆盖存放目录，缺省在 ~/.config/dsh-anywhere/keys', () => {
    const overridden = keystorePaths(tempEnv(join(tmp, 'custom')))
    expect(overridden.keystoreDir).toBe(join(tmp, 'custom'))
    expect(overridden.keystore).toBe(join(tmp, 'custom', 'release.keystore'))
    expect(overridden.props).toBe(join(tmp, 'custom', 'keystore.properties'))

    const paths = keystorePaths({})
    expect(paths.keystoreDir.startsWith(homedir())).toBe(true)
    expect(paths.keystoreDir.endsWith(join('.config', 'dsh-anywhere', 'keys'))).toBe(true)
  })
})

describe('generateKeystore', () => {
  it('成功路径：keytool 收到 RSA 4096 与别名 dsh，properties 四项齐全且口令一致', () => {
    const env = tempEnv(join(tmp, 'ok'))
    const seen: string[][] = []
    const content = generateKeystore(env, fakeKeytool(env, seen))

    expect(seen).toHaveLength(1)
    const args = seen[0]!
    for (const fragment of ['-genkeypair', '-keyalg', 'RSA', '-keysize', '4096', '-alias', 'dsh', '-validity', '10000']) {
      expect(args).toContain(fragment)
    }
    // storepass 与 keypass 同值（PKCS12 不支持两者不同）。
    expect(args[args.indexOf('-storepass') + 1]).toBe(args[args.indexOf('-keypass') + 1])
    expect(args).toContain('CN=DSH Anywhere, O=Keepwriting')

    const props = readFileSync(keystorePaths(env).props, 'utf8')
    expect(props).toBe(content)
    const storePass = /^storePassword=(.+)$/m.exec(props)![1]!
    expect(storePass).toMatch(/^[0-9a-f]{24}$/)
    // java.util.Properties 把反斜杠当转义符：Windows 上也必须写正斜杠路径。
    expect(props).toContain(`storeFile=${join(tmp, 'ok', 'release.keystore').replaceAll('\\', '/')}`)
    expect(props).toContain('keyAlias=dsh')
    expect(props).toContain(`keyPassword=${storePass}`)
  })

  it('负路径：keystore 已存在时拒绝执行且不调用 keytool', () => {
    const env = tempEnv(join(tmp, 'exists'))
    mkdirSync(join(tmp, 'exists'), { recursive: true })
    writeFileSync(keystorePaths(env).keystore, 'old')
    const seen: string[][] = []
    expect(() => generateKeystore(env, fakeKeytool(env, seen))).toThrow(/已存在/)
    expect(seen).toHaveLength(0)
  })

  it('负路径：keytool 缺失（ENOENT）与退出非零都报错', () => {
    const missing = tempEnv(join(tmp, 'missing'))
    expect(() => generateKeystore(missing, () => ({ status: null, error: new Error('ENOENT') }))).toThrow(/未找到 keytool/)
    const failed = tempEnv(join(tmp, 'failed'))
    expect(() => generateKeystore(failed, () => ({ status: 1 }))).toThrow(/keytool 生成失败/)
  })
})
