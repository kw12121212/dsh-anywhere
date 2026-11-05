// android-release 的判定与序列测试：假 Runner 注入，覆盖四类前置失败、
// 资产冲突、覆盖语义与成功路径命令序列（不真跑 git/gradle/gh）。
import { describe, expect, it } from 'vitest'
import { assetExists, certificatesMatch, apkArtifactName, main, preflight } from './android-release.mts'
import type { Runner } from './android-release.mts'
import { readPackageVersion } from './package-version.mts'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { existsSync, mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { apksignerPath, gradleInvocation, keytoolInvocation } from './android-release.mts'

const repoRoot = fileURLToPath(new URL('..', import.meta.url))

const OK = { status: 0, stdout: '', stderr: '' }

function fakeRunner(overrides: Array<{ match: (cmd: string, args: string[]) => boolean; result: { status: number; stdout: string; stderr: string } }>): Runner & { calls: Array<[string, string[]]>; callOpts: Array<{ shell?: boolean; env?: Record<string, string> } | undefined> } {
  const calls: Array<[string, string[]]> = []
  const callOpts: Array<{ shell?: boolean; env?: Record<string, string> } | undefined> = []
  const runner = ((cmd: string, args: string[], opts?: { shell?: boolean; env?: Record<string, string> }) => {
    calls.push([cmd, args])
    callOpts.push(opts)
    for (const o of overrides) {
      if (o.match(cmd, args)) return o.result
    }
    return { status: 0, stdout: '', stderr: '' }
  }) as Runner & { calls: Array<[string, string[]]>; callOpts: Array<{ shell?: boolean; env?: Record<string, string> } | undefined> }
  runner.calls = calls
  runner.callOpts = callOpts
  return runner
}

const goodGit = [
  { match: (c: string, a: string[]) => c === 'git' && a[0] === 'status', result: { status: 0, stdout: '', stderr: '' } },
  { match: (c: string, a: string[]) => c === 'git' && a[0] === 'rev-parse' && a[1] === 'HEAD', result: { status: 0, stdout: 'abc123\n', stderr: '' } },
  { match: (c: string, a: string[]) => c === 'git' && a[0] === 'rev-parse' && a[1]?.startsWith('v1.'), result: { status: 0, stdout: 'abc123\n', stderr: '' } },
]

describe('preflight', () => {
  const env = { DSH_KEYSTORE_PROPS: 'C:/fake/keystore.properties' }

  it('工作区脏即拒绝', () => {
    const r = fakeRunner([{ match: (c, a) => c === 'git' && a[0] === 'status', result: { status: 0, stdout: ' M x.ts\n', stderr: '' } }])
    expect(preflight(r, '1.0.1', env, () => true)).toMatch(/工作区不干净/)
  })

  it('tag 不存在即拒绝', () => {
    const r = fakeRunner([
      { match: (c, a) => c === 'git' && a[0] === 'status', result: OK },
      { match: (c, a) => c === 'git' && a[0] === 'rev-parse' && a[1]?.startsWith('v'), result: { status: 128, stdout: '', stderr: 'fatal' } },
    ])
    expect(preflight(r, '1.0.1', env, () => true)).toMatch(/tag v1\.0\.1 不存在/)
  })

  it('HEAD 与 tag 提交不符即拒绝', () => {
    const r = fakeRunner([
      { match: (c, a) => c === 'git' && a[0] === 'status', result: OK },
      { match: (c, a) => c === 'git' && a[0] === 'rev-parse' && a[1] === 'HEAD', result: { status: 0, stdout: 'def999\n', stderr: '' } },
      { match: (c, a) => c === 'git' && a[0] === 'rev-parse' && a[1]?.startsWith('v'), result: { status: 0, stdout: 'abc123\n', stderr: '' } },
    ])
    expect(preflight(r, '1.0.1', env, () => true)).toMatch(/HEAD 不在 tag/)
  })

  it('Release 不存在即拒绝', () => {
    const r = fakeRunner([
      ...goodGit,
      { match: (c) => c === 'gh', result: { status: 1, stdout: '', stderr: 'release not found' } },
    ])
    expect(preflight(r, '1.0.1', env, () => true)).toMatch(/Release v1\.0\.1 不存在/)
  })

  it('keystore 缺失即拒绝（路径不存在的环境）', () => {
    const r = fakeRunner([...goodGit, { match: (c) => c === 'gh', result: OK }])
    expect(preflight(r, '1.0.1', { DSH_KEYSTORE_PROPS: 'C:/x' }, () => false)).toMatch(/keystore/)
  })

  it('全部满足则通过', () => {
    const r = fakeRunner([...goodGit, { match: (c) => c === 'gh', result: OK }])
    expect(preflight(r, '1.0.1', env, () => true)).toBeNull()
  })
})

describe('assetExists / certificatesMatch / 命名', () => {
  it('按资产名判定存在', () => {
    const r = fakeRunner([
      { match: (c, a) => c === 'gh' && a[1] === 'view', result: { status: 0, stdout: '{"assets":[{"name":"DSH-Anywhere-1.0.1.apk"},{"name":"other"}]}', stderr: '' } },
    ])
    expect(assetExists(r, 'v1.0.1', 'DSH-Anywhere-1.0.1.apk')).toBe(true)
    expect(assetExists(r, 'v1.0.1', 'DSH-Anywhere-1.0.2.apk')).toBe(false)
  })

  it('证书 DN 一致才通过（大小写空白无关）', () => {
    expect(certificatesMatch('V2 Signer: certificate DN: CN=DSH Anywhere, O=Keepwriting', 'CN=DSH Anywhere,  O=Keepwriting')).toBe(true)
    expect(certificatesMatch('CN=Someone Else', 'CN=DSH Anywhere, O=Keepwriting')).toBe(false)
    expect(certificatesMatch('no cert here', 'CN=DSH Anywhere, O=Keepwriting')).toBe(false)
  })

  it('APK 命名带版本', () => {
    expect(apkArtifactName('1.2.3')).toBe('DSH-Anywhere-1.2.3.apk')
  })
})

describe('平台调用形态（回归：v1.1.0 在 Windows 上 spawn ENOENT）', () => {
  it('gradle 入口按平台选择：win32 为 gradlew.bat 且经 shell，非 win32 为无扩展名脚本', () => {
    const g = gradleInvocation()
    if (process.platform === 'win32') {
      expect(g.command.replace(/\\/g, '/')).toBe('android/gradlew.bat')
      expect(g.shell).toBe(true)
    } else {
      expect(g.command).toBe('android/gradlew')
      expect(g.shell).toBe(false)
    }
    expect(g.args).toEqual(['-p', 'android', 'assembleRelease'])
  })

  it('apksigner 路径按平台带扩展名：win32 取 build-tools 内 apksigner.bat，非 win32 取 apksigner', () => {
    const home = mkdtempSync(join(tmpdir(), 'dsh-android-home-'))
    try {
      const btDir = join(home, 'build-tools', '99.0.0')
      mkdirSync(btDir, { recursive: true })
      writeFileSync(join(btDir, process.platform === 'win32' ? 'apksigner.bat' : 'apksigner'), '')
      const p = apksignerPath(home).replace(/\\/g, '/')
      const expected = `/build-tools/99.0.0/${process.platform === 'win32' ? 'apksigner.bat' : 'apksigner'}`
      expect(p.endsWith(expected)).toBe(true)
    } finally {
      rmSync(home, { recursive: true, force: true })
    }
  })

  it('keytool 带 -v 与 -storepass:env，密码只进环境变量、不进命令行', () => {
    const k = keytoolInvocation('C:/keys/release.keystore', 'dsh', 'top-secret-pass')
    expect(k.args).toEqual(['-list', '-v', '-keystore', 'C:/keys/release.keystore', '-alias', 'dsh', '-storepass:env', 'DSH_KEYSTORE_STOREPASS'])
    expect(k.env).toEqual({ DSH_KEYSTORE_STOREPASS: 'top-secret-pass' })
    expect(k.args.join(' ')).not.toContain('top-secret-pass')
  })
})

describe('main 序列（假 Runner，process.exit 打桩）', () => {
  function withExitStubbed<T>(fn: () => T): { result: T; exits: number[]; messages: string[] } {
    const exits: number[] = []
    const messages: string[] = []
    const origExit = process.exit
    const origErr = console.error
    ;(process as { exit?: unknown }).exit = (code?: number): never => {
      exits.push(code ?? 1)
      throw new Error('exit')
    }
    console.error = (msg: string) => { messages.push(String(msg)) }
    try {
      const result = fn()
      return { result, exits, messages }
    } finally {
      process.exit = origExit
      console.error = origErr
    }
  }

  it('同名资产已存在且无 --overwrite 时拒绝（不出现 upload）', () => {
    // 资产名按当前版本派生，避免升版后与 package.json 脱节误走后续构建。
    const r = fakeRunner([
      ...goodGit,
      { match: (c) => c === 'gh' && c !== undefined, result: { status: 0, stdout: `{"assets":[{"name":"${apkArtifactName(readPackageVersion(join(repoRoot, 'package.json')))}"}]}`, stderr: '' } },
    ])
    const { exits } = withExitStubbed(() => {
      expect(() => main([], r, { DSH_KEYSTORE_PROPS: 'C:/fake/keystore.properties' }, () => true)).toThrow(/exit/)
    })
    expect(exits).toEqual([1])
    const uploaded = r.calls.filter(([c, a]) => c === 'gh' && a[0] === 'release' && a[1] === 'upload')
    expect(uploaded).toHaveLength(0)
  })

  it('构建产物缺失即失败（到达 gradle 之后、upload 之前）', () => {
    const r = fakeRunner([...goodGit, { match: (c) => c === 'gh', result: OK }])
    const { exits, messages } = withExitStubbed(() => {
      // fileExists：keystore true、构建产物 false → 走「未找到构建产物」fail。
      expect(() => main([], r, { DSH_KEYSTORE_PROPS: 'C:/fake/keystore.properties' }, (p) => p.includes('keystore.properties'))).toThrow(/exit/)
    })
    expect(exits).toEqual([1])
    expect(messages.join('\n')).toMatch(/未找到构建产物/)
    // 命令序列：AAR 构建、assembleRelease 都已发出；从未 upload。
    const commands = r.calls.map(([c, a]) => `${c} ${(a ?? []).join(' ')}`)
    expect(commands.some((s) => s.includes('build-android-tstunnel.mts'))).toBe(true)
    expect(commands.some((s) => s.includes('assembleRelease'))).toBe(true)
    expect(commands.some((s) => s.includes('release upload'))).toBe(false)
  })

  it('win32 上 gradle 调用为 gradlew.bat 且经 shell（修复前记录的是无扩展名 android/gradlew）', () => {
    const r = fakeRunner([...goodGit, { match: (c) => c === 'gh', result: OK }])
    withExitStubbed(() => {
      expect(() => main([], r, { DSH_KEYSTORE_PROPS: 'C:/fake/keystore.properties' }, (p) => p.includes('keystore.properties'))).toThrow(/exit/)
    })
    const idx = r.calls.findIndex(([, a]) => a.includes('assembleRelease'))
    expect(idx).toBeGreaterThanOrEqual(0)
    const [cmd] = r.calls[idx]
    if (process.platform === 'win32') {
      expect(cmd.replace(/\\/g, '/')).toBe('android/gradlew.bat')
      expect(r.callOpts[idx]?.shell).toBe(true)
    } else {
      expect(cmd).toBe('android/gradlew')
      expect(r.callOpts[idx]?.shell).toBeUndefined()
    }
  })

  it('成功路径：apksigner 按平台入口与 shell、keytool 透传 env 且带 -v/-storepass:env，密码不进命令行与输出，upload 携带双资产', () => {
    const version = readPackageVersion(join(repoRoot, 'package.json'))
    const apkName = apkArtifactName(version)
    const storePass = 'unit-test-store-pass'
    // 真实文件三件：keystore.properties、假 androidHome（含 build-tools/99.0.0）、构建产物 app-release.apk（缺则造哑文件，测后清理）。
    const tmpProps = mkdtempSync(join(tmpdir(), 'dsh-props-'))
    const tmpHome = mkdtempSync(join(tmpdir(), 'dsh-android-home-'))
    const outDir = join(repoRoot, 'android', 'app', 'build', 'outputs', 'apk', 'release')
    const built = join(outDir, 'app-release.apk')
    const madeDummy = !existsSync(built)
    try {
      writeFileSync(join(tmpProps, 'keystore.properties'), `storeFile=C:/fake/release.keystore\nstorePassword=${storePass}\nkeyAlias=dsh\n`)
      mkdirSync(join(tmpHome, 'build-tools', '99.0.0'), { recursive: true })
      writeFileSync(join(tmpHome, 'build-tools', '99.0.0', process.platform === 'win32' ? 'apksigner.bat' : 'apksigner'), '')
      if (madeDummy) {
        mkdirSync(outDir, { recursive: true })
        writeFileSync(built, 'dummy-apk')
      }
      const dn = 'CN=DSH Anywhere, O=Keepwriting'
      const r = fakeRunner([
        ...goodGit,
        { match: (c, a) => c === 'gh' && a[0] === 'release' && a[1] === 'view', result: { status: 0, stdout: '{"assets":[]}', stderr: '' } },
        { match: (c, a) => (c.endsWith('apksigner') || c.endsWith('apksigner.bat')) && a.includes('verify'), result: { status: 0, stdout: `Signer #1 certificate DN: ${dn}`, stderr: '' } },
        { match: (c) => c === 'keytool', result: { status: 0, stdout: `Owner: ${dn}`, stderr: '' } },
      ])
      main([], r, { DSH_KEYSTORE_PROPS: join(tmpProps, 'keystore.properties'), ANDROID_HOME: tmpHome }, (p) => p.includes('keystore.properties') || p.endsWith('app-release.apk'))
      // apksigner：平台入口 + win32 shell。
      const vi = r.calls.findIndex(([c, a]) => (c.endsWith('apksigner') || c.endsWith('apksigner.bat')) && a.includes('verify'))
      expect(vi).toBeGreaterThanOrEqual(0)
      expect(process.platform === 'win32' ? r.calls[vi][0].endsWith('apksigner.bat') : r.calls[vi][0].endsWith('apksigner')).toBe(true)
      expect(r.callOpts[vi]?.shell).toBe(process.platform === 'win32' ? true : undefined)
      // keytool：-v + -storepass:env，密码经 env 透传、不出现在任何命令行参数或 stub 输出。
      const ki = r.calls.findIndex(([c]) => c === 'keytool')
      expect(ki).toBeGreaterThanOrEqual(0)
      expect(r.calls[ki][1]).toContain('-v')
      expect(r.calls[ki][1]).toContain('-storepass:env')
      expect(r.callOpts[ki]?.env).toEqual({ DSH_KEYSTORE_STOREPASS: storePass })
      const allArgs = r.calls.map(([, a]) => a.join(' ')).join(' ')
      expect(allArgs).not.toContain(storePass)
      // upload：APK 与 .sha256 双资产。
      const upload = r.calls.filter(([c, a]) => c === 'gh' && a[0] === 'release' && a[1] === 'upload')
      expect(upload).toHaveLength(1)
      expect(upload[0][1].join(' ')).toContain(apkName)
      expect(upload[0][1].join(' ')).toContain(`${apkName}.sha256`)
    } finally {
      rmSync(tmpProps, { recursive: true, force: true })
      rmSync(tmpHome, { recursive: true, force: true })
      if (madeDummy) rmSync(built, { force: true })
      rmSync(join(outDir, apkName), { force: true })
      rmSync(join(outDir, `${apkName}.sha256`), { force: true })
    }
  })


})
