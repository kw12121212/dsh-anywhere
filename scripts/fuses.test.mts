import { copyFileSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { basename, join } from 'node:path'
import { FuseState, FuseV1Options, FuseVersion, getCurrentFuseWire } from '@electron/fuses'
import { describe, expect, it } from 'vitest'
import { FUSE_CONFIG, applyFuses } from './fuses.mts'

// 在 Node 中 require('electron') 返回 Electron 可执行文件路径（类型声明描述的是主进程 API）。
const electronPath = createRequire(import.meta.url)('electron') as string

describe('FUSE_CONFIG', () => {
  it('关闭 RunAsNode、NODE_OPTIONS 与调试参数', () => {
    expect(FUSE_CONFIG).toEqual({
      version: FuseVersion.V1,
      resetAdHocDarwinSignature: true,
      [FuseV1Options.RunAsNode]: false,
      [FuseV1Options.EnableNodeOptionsEnvironmentVariable]: false,
      [FuseV1Options.EnableNodeCliInspectArguments]: false
    })
  })
})

describe('applyFuses', () => {
  // macOS 的 fuse 位于 .app 内的 Electron Framework，单独复制可执行文件无法翻转，交给打包阶段验证。
  it.skipIf(process.platform === 'darwin')(
    '翻转 Electron 二进制副本中的三项 fuse，且不改动原文件',
    async () => {
      const dir = mkdtempSync(join(tmpdir(), 'fuses-'))
      try {
        const copy = join(dir, basename(electronPath))
        copyFileSync(electronPath, copy)
        const before = readFileSync(electronPath)
        await applyFuses(copy)
        const wire = await getCurrentFuseWire(copy)
        expect(wire[FuseV1Options.RunAsNode]).toBe(FuseState.DISABLE)
        expect(wire[FuseV1Options.EnableNodeOptionsEnvironmentVariable]).toBe(FuseState.DISABLE)
        expect(wire[FuseV1Options.EnableNodeCliInspectArguments]).toBe(FuseState.DISABLE)
        const original = await getCurrentFuseWire(electronPath)
        expect(original[FuseV1Options.RunAsNode]).toBe(FuseState.ENABLE)
        expect(readFileSync(electronPath).equals(before)).toBe(true)
      } finally {
        rmSync(dir, { recursive: true, force: true })
      }
    },
    60_000
  )

  it('目标不是 Electron 二进制时失败', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'fuses-'))
    try {
      const fake = join(dir, 'electron')
      writeFileSync(fake, 'not an electron binary')
      await expect(applyFuses(fake)).rejects.toThrow()
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })
})
