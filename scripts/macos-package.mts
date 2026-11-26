// macOS 原生通用二进制（arm64 + x86_64）未签名 .app 组装与归档脚本。
// 版本号单一来源（技术标准 packaging/package-version-single-source）：经 scripts/package-version.mts
// 从仓库根 package.json 读取 version 写入 Info.plist 的 CFBundleShortVersionString 与 CFBundleVersion；
// CI 由 .github/workflows/package.yml 的 package-macos-native job 调用（仅构建 artifact，不进 Release）。
import { spawnSync } from 'node:child_process'
import { chmodSync, copyFileSync, existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { readPackageVersion } from './package-version.mts'

const defaultRepoRoot = fileURLToPath(new URL('..', import.meta.url))

export const APP_DISPLAY_NAME = 'DSH Anywhere'
export const APP_BUNDLE_ID = 'work.keepwriting.dsh-anywhere'
export const APP_COPYRIGHT = '© 2026 Keepwriting'
export const APP_EXECUTABLE_NAME = 'DSH Anywhere'
export const SWIFT_PRODUCT_NAME = 'DshAnywhereApp'
export const MIN_MACOS_VERSION = '14.0'

export interface CommandResult {
  status: number | null
  stdout: string
  stderr: string
}

export type CommandRunner = (command: string, args: string[], cwd: string) => CommandResult

export interface InfoPlistOptions {
  version: string
  displayName?: string
  bundleId?: string
  executableName?: string
  copyright?: string
  minSystemVersion?: string
}

export interface BuildMacosAppOptions {
  repoRoot?: string
  outDir?: string
  platform?: NodeJS.Platform
  runner?: CommandRunner
}

export interface BuildMacosAppResult {
  version: string
  appPath: string
  zipPath: string
}

export function escapeXml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;')
}

export function renderInfoPlist(options: InfoPlistOptions): string {
  const displayName = options.displayName ?? APP_DISPLAY_NAME
  const bundleId = options.bundleId ?? APP_BUNDLE_ID
  const executableName = options.executableName ?? APP_EXECUTABLE_NAME
  const copyright = options.copyright ?? APP_COPYRIGHT
  const minSystemVersion = options.minSystemVersion ?? MIN_MACOS_VERSION
  const version = options.version

  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDevelopmentRegion</key>
  <string>zh_CN</string>
  <key>CFBundleDisplayName</key>
  <string>${escapeXml(displayName)}</string>
  <key>CFBundleExecutable</key>
  <string>${escapeXml(executableName)}</string>
  <key>CFBundleIconFile</key>
  <string>AppIcon</string>
  <key>CFBundleIdentifier</key>
  <string>${escapeXml(bundleId)}</string>
  <key>CFBundleInfoDictionaryVersion</key>
  <string>6.0</string>
  <key>CFBundleName</key>
  <string>${escapeXml(displayName)}</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>${escapeXml(version)}</string>
  <key>CFBundleVersion</key>
  <string>${escapeXml(version)}</string>
  <key>LSMinimumSystemVersion</key>
  <string>${escapeXml(minSystemVersion)}</string>
  <key>NSHighResolutionCapable</key>
  <true/>
  <key>NSHumanReadableCopyright</key>
  <string>${escapeXml(copyright)}</string>
  <key>NSPrincipalClass</key>
  <string>NSApplication</string>
</dict>
</plist>
`
}

export function parseArgs(argv: string[], defaultOutDir = resolve(defaultRepoRoot, 'dist', 'macos-native')): { outDir: string } {
  let outDir = defaultOutDir
  for (let i = 0; i < argv.length; i += 2) {
    const flag = argv[i]
    const value = argv[i + 1]
    if (flag === '--out' && value) {
      outDir = resolve(value)
    } else {
      throw new Error(`用法：node scripts/macos-package.mts [--out <dir>]（无法识别：${flag} ${value ?? ''}）`)
    }
  }
  return { outDir }
}

function defaultRunner(command: string, args: string[], cwd: string): CommandResult {
  const result = spawnSync(command, args, {
    cwd,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe']
  })
  return {
    status: result.status,
    stdout: result.stdout ?? '',
    stderr: result.stderr ?? ''
  }
}

function runOrThrow(runner: CommandRunner, command: string, args: string[], cwd: string): string {
  const result = runner(command, args, cwd)
  if (result.status !== 0) {
    const detail = (result.stderr || result.stdout).trim()
    throw new Error(`${command} ${args.join(' ')} 执行失败（exit ${String(result.status)}）${detail ? `: ${detail}` : ''}`)
  }
  return result.stdout
}

export function buildMacosApp(options: BuildMacosAppOptions = {}): BuildMacosAppResult {
  const platform = options.platform ?? process.platform
  if (platform !== 'darwin') {
    throw new Error(`macos-package 只能在 macOS (darwin) 上运行，当前平台为 ${platform}`)
  }

  const repoRoot = options.repoRoot ?? defaultRepoRoot
  const outDir = options.outDir ?? resolve(repoRoot, 'dist', 'macos-native')
  const runner = options.runner ?? defaultRunner

  const version = readPackageVersion(resolve(repoRoot, 'package.json'))
  const iconPath = resolve(repoRoot, 'build', 'icon.icns')
  if (!existsSync(iconPath)) {
    throw new Error(`缺少应用图标源文件：${iconPath}`)
  }

  const appPackageDir = resolve(repoRoot, 'macos', 'App')
  const swiftBuildArgs = ['build', '--package-path', appPackageDir, '-c', 'release', '--arch', 'arm64', '--arch', 'x86_64']
  runOrThrow(runner, 'swift', swiftBuildArgs, repoRoot)

  const binDir = runOrThrow(runner, 'swift', [...swiftBuildArgs, '--show-bin-path'], repoRoot).trim()
  if (!binDir) {
    throw new Error('swift build --show-bin-path 未返回构建输出目录')
  }
  const binaryPath = join(binDir, SWIFT_PRODUCT_NAME)
  if (!existsSync(binaryPath)) {
    throw new Error(`未找到编译产物可执行文件：${binaryPath}`)
  }

  const archs = runOrThrow(runner, 'lipo', ['-archs', binaryPath], repoRoot)
    .trim()
    .split(/\s+/)
    .filter(Boolean)
  if (!archs.includes('arm64') || !archs.includes('x86_64')) {
    throw new Error(`通用二进制架构不完整（期望同时包含 arm64 与 x86_64，实际：${archs.join(', ') || '无'}）`)
  }

  const appPath = join(outDir, `${APP_DISPLAY_NAME}.app`)
  const contentsDir = join(appPath, 'Contents')
  const macOsDir = join(contentsDir, 'MacOS')
  const resourcesDir = join(contentsDir, 'Resources')

  rmSync(appPath, { recursive: true, force: true })
  mkdirSync(macOsDir, { recursive: true })
  mkdirSync(resourcesDir, { recursive: true })

  const executablePath = join(macOsDir, APP_EXECUTABLE_NAME)
  copyFileSync(binaryPath, executablePath)
  chmodSync(executablePath, 0o755)

  copyFileSync(iconPath, join(resourcesDir, 'AppIcon.icns'))
  writeFileSync(join(contentsDir, 'PkgInfo'), 'APPL????', 'utf8')

  const infoPlistPath = join(contentsDir, 'Info.plist')
  writeFileSync(infoPlistPath, renderInfoPlist({ version }), 'utf8')

  runOrThrow(runner, 'plutil', ['-lint', infoPlistPath], repoRoot)
  runOrThrow(runner, 'codesign', ['--force', '--deep', '--sign', '-', appPath], repoRoot)

  const zipPath = join(outDir, `dsh-anywhere_${version}_macos_native_app.zip`)
  rmSync(zipPath, { force: true })
  runOrThrow(runner, 'ditto', ['-c', '-k', '--sequesterRsrc', '--keepParent', appPath, zipPath], repoRoot)

  return { version, appPath, zipPath }
}

function main(): void {
  const { outDir } = parseArgs(process.argv.slice(2))
  const result = buildMacosApp({ outDir })
  console.log(`macos-package：已构建 ${result.appPath}（${result.version}）并打包 ${result.zipPath}`)
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main()
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error))
    process.exit(1)
  }
}
