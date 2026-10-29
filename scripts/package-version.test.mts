import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { VERSION_PATTERN, readPackageVersion, versionFromManifest } from './package-version.mts'

const repoRoot = fileURLToPath(new URL('..', import.meta.url))

describe('versionFromManifest', () => {
  it('返回 package.json 中的 x.y.z 版本号', () => {
    expect(versionFromManifest('{"version": "1.0.0"}')).toBe('1.0.0')
    expect(versionFromManifest('{"name": "dsh-anywhere", "version": "1.2.3"}')).toBe('1.2.3')
  })

  it('非 x.y.z 版本直接抛错，不静默回退（不产出 dev/0.0.0 漂移版本）', () => {
    for (const raw of ['{"version": "1.0"}', '{"version": "0.0.0-dev"}', '{"version": "dev"}', '{"version": ""}', '{}']) {
      expect(() => versionFromManifest(raw)).toThrow(/必须是 x\.y\.z 形式/)
    }
  })

  it('version 缺失、类型错误或 JSON 损坏时抛出可读错误', () => {
    expect(() => versionFromManifest('{"version": 1}')).toThrow(/必须是 x\.y\.z 形式/)
    expect(() => versionFromManifest('{not json')).toThrow(/不是合法 JSON/)
  })
})

describe('readPackageVersion', () => {
  // 用临时 fixture 而不是仓库 package.json 的当前值，版本升级不需要改本测试；
  // 仓库版本本身的格式约束由 workflows.test.mts 的统一性断言锁定。
  it('读取指定 package.json 文件中的版本', () => {
    const dir = mkdtempSync(join(tmpdir(), 'dsh-anywhere-version-'))
    try {
      writeFileSync(join(dir, 'package.json'), '{"version": "2.3.4"}')
      expect(readPackageVersion(join(dir, 'package.json'))).toBe('2.3.4')
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })

  it('文件不存在时抛错而不是回退默认版本', () => {
    expect(() => readPackageVersion(join(repoRoot, 'no-such-package.json'))).toThrow()
  })
})

describe('VERSION_PATTERN', () => {
  it('锁定 x.y.z 判定公式（不允许预发布号与前后空白）', () => {
    expect(VERSION_PATTERN.test('1.0.0')).toBe(true)
    expect(VERSION_PATTERN.test('10.20.30')).toBe(true)
    expect(VERSION_PATTERN.test('1.0.0-beta.1')).toBe(false)
    expect(VERSION_PATTERN.test('v1.0.0')).toBe(false)
    expect(VERSION_PATTERN.test(' 1.0.0')).toBe(false)
  })
})
