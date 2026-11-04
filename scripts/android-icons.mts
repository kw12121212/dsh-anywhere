// android-icons — 从桌面图标源生成 Android 各密度启动图标。
//
// 源是仓库根 build/appicon.png（electron-builder 同款图标源，app-identity 主规格
// app-icons-from-project-source 场景的 Android 对应物）；输出 android/app/src/main/res/
// mipmap-{mdpi..xxxhdpi}/ic_launcher.png（48/72/96/144/192）并提交入库。构建期不动态
// 生成（保持 assemble 离线自包含），图标源变化时重新运行本脚本。
//
// 用法：node scripts/android-icons.mts [--source <png>] [--out <dir>]
// 测试向临时目录生成并核对文件与像素尺寸（android-icons.test.mts）。
import { mkdir } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const repoRoot = fileURLToPath(new URL('..', import.meta.url))

/** 密度 → 图标边长（px），与 Android 各 mipmap 目录的标准尺寸一致。 */
export const ICON_DENSITIES: Readonly<Record<string, number>> = Object.freeze({
  'mipmap-mdpi': 48,
  'mipmap-hdpi': 72,
  'mipmap-xhdpi': 96,
  'mipmap-xxhdpi': 144,
  'mipmap-xxxhdpi': 192
})

/** 解析 CLI 参数（--source/--out，等号或空格分隔都接受）。 */
export function parseArgs(argv: string[]): { source: string; out: string } {
  const defaults = {
    source: resolve(repoRoot, 'build', 'appicon.png'),
    out: resolve(repoRoot, 'android', 'app', 'src', 'main', 'res')
  }
  const parsed = { ...defaults }
  for (let i = 0; i < argv.length; i += 2) {
    const flag = argv[i]
    const value = argv[i + 1]
    if (flag === '--source' && value) parsed.source = resolve(value)
    else if (flag === '--out' && value) parsed.out = resolve(value)
    else throw new Error(`用法：node scripts/android-icons.mts [--source <png>] [--out <dir>]（无法识别：${flag} ${value ?? ''}）`)
  }
  return parsed
}

/** 生成全部密度的 ic_launcher.png，返回写出的文件路径列表。 */
export async function generateIcons(source: string, outDir: string): Promise<string[]> {
  const sharp = (await import('sharp')).default
  const written: string[] = []
  for (const [density, size] of Object.entries(ICON_DENSITIES)) {
    const target = join(outDir, density, 'ic_launcher.png')
    await mkdir(dirname(target), { recursive: true })
    // fit: contain + 透明背景：非正方形源不裁切不变形，居中留边。
    await sharp(source).resize(size, size, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } }).png().toFile(target)
    written.push(target)
  }
  return written
}

async function main(): Promise<void> {
  const { source, out } = parseArgs(process.argv.slice(2))
  const written = await generateIcons(source, out)
  console.log(`android-icons：从 ${source} 生成 ${written.length} 个密度图标：`)
  for (const file of written) console.log(`  ${file}`)
}

// 仅作为 node 直运行的入口脚本时执行生成；被测试导入时不触发。
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    await main()
  } catch (e) {
    console.error(e instanceof Error ? e.message : String(e))
    process.exit(1)
  }
}
