// android-icons 的行为测试：用 sharp 现造一个测试源图，向临时目录生成各密度图标，
// 断言文件存在、像素尺寸与 ICON_DENSITIES 一致；并验证参数解析的缺省与拒绝。
import { mkdtempSync, rmSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterAll, describe, expect, it } from 'vitest'
import { ICON_DENSITIES, generateIcons, parseArgs } from './android-icons.mts'

const repoRoot = fileURLToPath(new URL('..', import.meta.url))
const tmp = mkdtempSync(join(tmpdir(), 'dsh-icons-test-'))
afterAll(() => rmSync(tmp, { recursive: true, force: true }))

async function pngMetadata(file: string): Promise<{ width: number; height: number }> {
  const sharp = (await import('sharp')).default
  return sharp(await readFile(file)).metadata() as Promise<{ width: number; height: number }>
}

describe('parseArgs', () => {
  it('缺省指向仓库内图标源与 res 目录，参数可覆盖', () => {
    const defaults = parseArgs([])
    expect(defaults.source).toBe(resolve(repoRoot, 'build', 'appicon.png'))
    expect(defaults.out).toBe(resolve(repoRoot, 'android', 'app', 'src', 'main', 'res'))

    const overridden = parseArgs(['--source', join(tmp, 'src.png'), '--out', join(tmp, 'out')])
    expect(overridden.source).toBe(join(tmp, 'src.png'))
    expect(overridden.out).toBe(join(tmp, 'out'))
  })

  it('未知参数或缺失值即抛错', () => {
    expect(() => parseArgs(['--nope', 'x'])).toThrow(/用法/)
    expect(() => parseArgs(['--source'])).toThrow(/用法/)
  })
})

describe('generateIcons', () => {
  it('生成全部密度的 ic_launcher.png 且像素尺寸与密度表一致', async () => {
    const sharp = (await import('sharp')).default
    // 非正方形源图（240x160）验证 contain 不裁切不变形：输出仍应为正方形目标尺寸。
    const source = join(tmp, 'source.png')
    await sharp({ create: { width: 240, height: 160, channels: 4, background: { r: 80, g: 120, b: 200, alpha: 1 } } })
      .png()
      .toFile(source)

    const outDir = join(tmp, 'res')
    const written = await generateIcons(source, outDir)

    expect(written).toHaveLength(Object.keys(ICON_DENSITIES).length)
    for (const [density, size] of Object.entries(ICON_DENSITIES)) {
      const file = join(outDir, density, 'ic_launcher.png')
      expect(written).toContain(resolve(file))
      const meta = await pngMetadata(file)
      expect(meta.width).toBe(size)
      expect(meta.height).toBe(size)
    }
  })

  it('源文件缺失时报错（不静默产出空图标）', async () => {
    await expect(generateIcons(join(tmp, 'absent.png'), join(tmp, 'res2'))).rejects.toThrow()
  })
})
