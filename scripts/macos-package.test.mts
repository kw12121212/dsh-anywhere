import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { DISPLAY_NAME } from '../src/main/app-info'
import {
  APP_BUNDLE_ID,
  APP_COPYRIGHT,
  APP_DISPLAY_NAME,
  APP_EXECUTABLE_NAME,
  MIN_MACOS_VERSION,
  SWIFT_PRODUCT_NAME,
  buildMacosApp,
  escapeXml,
  parseArgs,
  renderInfoPlist,
  type CommandRunner
} from './macos-package.mts'

const tempDirs: string[] = []

function makeTempDir(prefix = 'dsh-macos-pkg-'): string {
  const dir = mkdtempSync(join(tmpdir(), prefix))
  tempDirs.push(dir)
  return dir
}

function setupFakeRepo(options: { version?: string; withIcon?: boolean; withBinary?: boolean } = {}): {
  repoRoot: string
  binDir: string
  outDir: string
} {
  const root = makeTempDir()
  const repoRoot = join(root, 'repo')
  const outDir = join(root, 'out')
  const binDir = join(root, 'swift-bin')

  mkdirSync(join(repoRoot, 'build'), { recursive: true })
  mkdirSync(join(repoRoot, 'macos', 'App'), { recursive: true })
  mkdirSync(binDir, { recursive: true })

  if (options.version !== undefined) {
    writeFileSync(join(repoRoot, 'package.json'), JSON.stringify({ name: 'dsh-anywhere', version: options.version }), 'utf8')
  }
  if (options.withIcon !== false) {
    writeFileSync(join(repoRoot, 'build', 'icon.icns'), Buffer.from('icns-dummy-bytes'))
  }
  if (options.withBinary !== false) {
    writeFileSync(join(binDir, SWIFT_PRODUCT_NAME), Buffer.from('#!/bin/sh\necho fake\n'))
  }

  return { repoRoot, binDir, outDir }
}

afterEach(() => {
  while (tempDirs.length > 0) {
    const dir = tempDirs.pop()
    if (dir) rmSync(dir, { recursive: true, force: true })
  }
})

describe('macos-package', () => {
  it('应用身份常量与 Electron app-info 同源', () => {
    expect(APP_DISPLAY_NAME).toBe(DISPLAY_NAME)
    expect(APP_EXECUTABLE_NAME).toBe(DISPLAY_NAME)
    expect(APP_BUNDLE_ID).toBe('work.keepwriting.dsh-anywhere')
    expect(APP_COPYRIGHT).toBe('© 2026 Keepwriting')
    expect(MIN_MACOS_VERSION).toBe('14.0')
  })

  it('escapeXml 转义 XML 特殊字符', () => {
    expect(escapeXml('A & B <C> "D" \'E\'')).toBe('A &amp; B &lt;C&gt; &quot;D&quot; &apos;E&apos;')
  })

  it('renderInfoPlist 写入单一来源版本号与规范身份字段', () => {
    const plist = renderInfoPlist({ version: '1.9.0' })
    expect(plist).toContain('<key>CFBundleDisplayName</key>\n  <string>DSH Anywhere</string>')
    expect(plist).toContain('<key>CFBundleIdentifier</key>\n  <string>work.keepwriting.dsh-anywhere</string>')
    expect(plist).toContain('<key>CFBundleExecutable</key>\n  <string>DSH Anywhere</string>')
    expect(plist).toContain('<key>CFBundleIconFile</key>\n  <string>AppIcon</string>')
    expect(plist).toContain('<key>CFBundleShortVersionString</key>\n  <string>1.9.0</string>')
    expect(plist).toContain('<key>CFBundleVersion</key>\n  <string>1.9.0</string>')
    expect(plist).toContain('<key>LSMinimumSystemVersion</key>\n  <string>14.0</string>')
    expect(plist).toContain('<key>NSHumanReadableCopyright</key>\n  <string>© 2026 Keepwriting</string>')
  })

  it('parseArgs 支持默认输出路径、--out 并拒绝非法参数', () => {
    const fallback = resolve('/tmp/default-out')
    expect(parseArgs([], fallback)).toEqual({ outDir: fallback })
    expect(parseArgs(['--out', '/tmp/custom-out'], fallback)).toEqual({ outDir: resolve('/tmp/custom-out') })
    expect(() => parseArgs(['--unknown', '1'], fallback)).toThrow(/无法识别/)
    expect(() => parseArgs(['--out'], fallback)).toThrow(/无法识别/)
  })

  it('非 macOS 平台拒绝执行', () => {
    const { repoRoot, outDir } = setupFakeRepo({ version: '1.9.0' })
    expect(() =>
      buildMacosApp({
        repoRoot,
        outDir,
        platform: 'linux',
        runner: () => ({ status: 0, stdout: '', stderr: '' })
      })
    ).toThrow(/darwin/)
  })

  it('package.json 版本缺失或非 x.y.z 时报错退出', () => {
    const missing = setupFakeRepo()
    expect(() => buildMacosApp({ repoRoot: missing.repoRoot, outDir: missing.outDir, platform: 'darwin' })).toThrow()

    const invalid = setupFakeRepo({ version: 'dev' })
    expect(() => buildMacosApp({ repoRoot: invalid.repoRoot, outDir: invalid.outDir, platform: 'darwin' })).toThrow(/x\.y\.z/)
  })

  it('缺少 build/icon.icns 时报错退出', () => {
    const { repoRoot, outDir } = setupFakeRepo({ version: '1.9.0', withIcon: false })
    expect(() => buildMacosApp({ repoRoot, outDir, platform: 'darwin' })).toThrow(/icon\.icns/)
  })

  it('swift build 失败、缺少产物二进制、--show-bin-path 为空时报错退出', () => {
    const { repoRoot, binDir, outDir } = setupFakeRepo({ version: '1.9.0' })
    expect(() =>
      buildMacosApp({
        repoRoot,
        outDir,
        platform: 'darwin',
        runner: () => ({ status: 1, stdout: '', stderr: 'compile failed' })
      })
    ).toThrow(/compile failed/)

    expect(() =>
      buildMacosApp({
        repoRoot,
        outDir,
        platform: 'darwin',
        runner: (_cmd, args) => {
          if (args.includes('--show-bin-path')) return { status: 0, stdout: '   \n', stderr: '' }
          return { status: 0, stdout: '', stderr: '' }
        }
      })
    ).toThrow(/未返回构建输出目录/)

    const missingBin = setupFakeRepo({ version: '1.9.0', withBinary: false })
    expect(() =>
      buildMacosApp({
        repoRoot: missingBin.repoRoot,
        outDir: missingBin.outDir,
        platform: 'darwin',
        runner: (_cmd, args) => {
          if (args.includes('--show-bin-path')) return { status: 0, stdout: `${missingBin.binDir}\n`, stderr: '' }
          return { status: 0, stdout: '', stderr: '' }
        }
      })
    ).toThrow(/未找到编译产物可执行文件/)

    // 单架构输出必须被通用二进制守卫拒绝
    expect(() =>
      buildMacosApp({
        repoRoot,
        outDir,
        platform: 'darwin',
        runner: (cmd, args) => {
          if (cmd === 'swift' && args.includes('--show-bin-path')) return { status: 0, stdout: `${binDir}\n`, stderr: '' }
          if (cmd === 'lipo') return { status: 0, stdout: 'arm64\n', stderr: '' }
          return { status: 0, stdout: '', stderr: '' }
        }
      })
    ).toThrow(/通用二进制架构不完整/)
  })

  it('成功组装 .app 包结构、注入版本与图标并按序调用校验/签名/打包', () => {
    const { repoRoot, binDir, outDir } = setupFakeRepo({ version: '1.9.0' })
    const calls: Array<{ command: string; args: string[] }> = []

    const runner: CommandRunner = (command, args) => {
      calls.push({ command, args })
      if (command === 'swift' && args.includes('--show-bin-path')) {
        return { status: 0, stdout: `${binDir}\n`, stderr: '' }
      }
      if (command === 'lipo') {
        return { status: 0, stdout: 'x86_64 arm64 \n', stderr: '' }
      }
      if (command === 'ditto') {
        const zipTarget = args[args.length - 1]
        writeFileSync(zipTarget, Buffer.from('PK-zip-placeholder'))
      }
      return { status: 0, stdout: '', stderr: '' }
    }

    const result = buildMacosApp({ repoRoot, outDir, platform: 'darwin', runner })

    expect(result.version).toBe('1.9.0')
    expect(result.appPath).toBe(join(outDir, 'DSH Anywhere.app'))
    expect(result.zipPath).toBe(join(outDir, 'dsh-anywhere_1.9.0_macos_native_app.zip'))

    expect(calls.map((c) => c.command)).toEqual(['swift', 'swift', 'lipo', 'plutil', 'codesign', 'ditto'])
    expect(calls[0].args).toContain('arm64')
    expect(calls[0].args).toContain('x86_64')
    expect(calls[4].args).toEqual(['--force', '--deep', '--sign', '-', result.appPath])
    expect(calls[5].args).toEqual(['-c', '-k', '--sequesterRsrc', '--keepParent', result.appPath, result.zipPath])

    const exePath = join(result.appPath, 'Contents', 'MacOS', 'DSH Anywhere')
    const iconTarget = join(result.appPath, 'Contents', 'Resources', 'AppIcon.icns')
    const pkgInfoPath = join(result.appPath, 'Contents', 'PkgInfo')
    const infoPlistPath = join(result.appPath, 'Contents', 'Info.plist')

    expect(existsSync(exePath)).toBe(true)
    expect(statSync(exePath).mode & 0o111).toBe(0o111)
    expect(readFileSync(iconTarget)).toEqual(Buffer.from('icns-dummy-bytes'))
    expect(readFileSync(pkgInfoPath, 'utf8')).toBe('APPL????')
    expect(readFileSync(infoPlistPath, 'utf8')).toContain('<string>1.9.0</string>')
    expect(existsSync(result.zipPath)).toBe(true)
  })
})
