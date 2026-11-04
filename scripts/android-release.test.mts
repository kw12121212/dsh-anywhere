// android-release 的判定与序列测试：假 Runner 注入，覆盖四类前置失败、
// 资产冲突、覆盖语义与成功路径命令序列（不真跑 git/gradle/gh）。
import { describe, expect, it } from 'vitest'
import { assetExists, certificatesMatch, apkArtifactName, main, preflight } from './android-release.mts'
import type { Runner } from './android-release.mts'

const OK = { status: 0, stdout: '', stderr: '' }

function fakeRunner(overrides: Array<{ match: (cmd: string, args: string[]) => boolean; result: { status: number; stdout: string; stderr: string } }>): Runner & { calls: Array<[string, string[]]> } {
  const calls: Array<[string, string[]]> = []
  const runner = ((cmd: string, args: string[]) => {
    calls.push([cmd, args])
    for (const o of overrides) {
      if (o.match(cmd, args)) return o.result
    }
    return { status: 0, stdout: '', stderr: '' }
  }) as Runner & { calls: Array<[string, string[]]> }
  runner.calls = calls
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
    const r = fakeRunner([
      ...goodGit,
      { match: (c) => c === 'gh' && c !== undefined, result: { status: 0, stdout: '{"assets":[{"name":"DSH-Anywhere-1.0.1.apk"}]}', stderr: '' } },
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


})
