// CI/发布工作流与打包配置的结构断言（技术标准 packaging/workflow-structure-assertion-tests）。
// 迁移自 internal/appinfo 下的 Go 断言测试：关键片段正向断言、负向断言，并锁定
// 被复用上游的关键前提（package.yml 的 workflow_call 触发器）。
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
import { FuseV1Options } from '@electron/fuses'
import { describe, expect, it } from 'vitest'
import { DISPLAY_NAME } from '../src/main/app-info'
import { FUSE_CONFIG } from './fuses.mts'
import { VERSION_PATTERN, readPackageVersion } from './package-version.mts'

const repoRoot = fileURLToPath(new URL('..', import.meta.url))

// Windows 检出可能带 CRLF，读入后统一换行符再比较。
function repoFile(...segments: string[]): string {
  return readFileSync(join(repoRoot, ...segments), 'utf8').replaceAll('\r\n', '\n')
}

function regressionCommand(): string {
  for (const line of repoFile('.strict-spec-driven', 'config.yaml').split('\n')) {
    if (line.startsWith('    command: ')) {
      return line.slice('    command: '.length).trimEnd()
    }
  }
  throw new Error('.strict-spec-driven/config.yaml has no review_evidence.project_regression.command')
}

describe('.github/workflows', () => {
  it('CI 只保留打包与发布工作流，回归不上 GitHub Actions', () => {
    // 回归测试只在本地执行（config.yaml 的完整回归命令），CI 上只放打包。
    expect(readdirSync(join(repoRoot, '.github', 'workflows')).sort()).toEqual([
      'package.yml',
      'release.yml'
    ])
  })

  it('打包工作流只由 main 分支 push 触发（手动与 tag 复用入口保留）', () => {
    const workflow = repoFile('.github', 'workflows', 'package.yml')
    expect(workflow).toContain('push:\n    branches: [main]\n')
    // 其他分支名不得回流到触发器。
    expect(workflow).not.toContain('branches: [main, dev]')
  })
})

describe('config.yaml 完整回归口径', () => {
  it('本地回归命令保留完整的 Node 工具链步骤，缺一步即口径漂移', () => {
    const command = regressionCommand()
    for (const fragment of [
      'npm ci',
      'npm run typecheck',
      'npm run build',
      'npm run test:coverage',
      'npm run test:e2e'
    ]) {
      expect(command).toContain(fragment)
    }
  })
})

describe('package.yml', () => {
  it('只经 npm 打包 script 调用 electron-builder，不复制打包命令', () => {
    const workflow = repoFile('.github', 'workflows', 'package.yml')
    for (const fragment of [
      'workflow_call',
      'node-version: 22',
      'npm ci',
      'npm run dist:mac',
      'npm run dist:linux',
      'npm run dist:win',
      'upload-artifact',
      'if-no-files-found: error',
      'dist/*.dmg',
      'dist/*.AppImage',
      'dist/*.deb',
      'dist/*_installer.exe'
    ]) {
      expect(workflow).toContain(fragment)
    }
    // 负向断言：Go/Wails/GTK 与 NSIS choco 步骤不得回流，也不得直接调 electron-builder；
    // 版本号来自 package.json，不再需要为 git describe 深拉取标签。
    for (const forbidden of [
      'go build',
      'wails3',
      'setup-go',
      'choco install nsis',
      'libgtk-4-dev',
      'libwebkitgtk-6.0-dev',
      'npx electron-builder',
      'fetch-depth: 0'
    ]) {
      expect(workflow).not.toContain(forbidden)
    }
  })
})

describe('release.yml', () => {
  it('复用 package.yml 打包，不复制打包命令', () => {
    const workflow = repoFile('.github', 'workflows', 'release.yml')
    for (const fragment of [
      "tags: ['v*']",
      'uses: ./.github/workflows/package.yml',
      'needs: package',
      'merge-multiple: true'
    ]) {
      expect(workflow).toContain(fragment)
    }
    // 发布工作流不得复制打包命令。
    for (const forbidden of ['wails3 task', 'go build', 'electron-builder', 'npm run dist']) {
      expect(workflow).not.toContain(forbidden)
    }
    // 复用的前提是 package.yml 保留 workflow_call 触发器，上游删掉它则发布断链。
    expect(repoFile('.github', 'workflows', 'package.yml')).toContain('workflow_call')
    // 允许的外部 action 只有官方 checkout（verify-tag 前置校验）与 download-artifact；
    // 发布步骤只用 gh CLI。
    for (const line of workflow.split('\n')) {
      if (
        line.includes('uses:') &&
        !line.includes('actions/download-artifact') &&
        !line.includes('actions/checkout') &&
        !line.includes('uses: ./.github/workflows/package.yml')
      ) {
        throw new Error(`release.yml uses a third-party action instead of gh CLI: ${line.trim()}`)
      }
    }
  })

  it('打包前校验发布 tag 等于 v + package.json version（release-tag-guard）', () => {
    const workflow = repoFile('.github', 'workflows', 'release.yml')
    for (const fragment of [
      'Verify tag matches package.json version',
      'needs: verify-tag',
      "node -p \"require('./package.json').version\"",
      'v${VERSION}" != "${GITHUB_REF_NAME}'
    ]) {
      expect(workflow).toContain(fragment)
    }
    // 打包 job 必须依赖校验 job：guard 失败时不得产出任何安装包。
    const packageJobNeeds = workflow.split('  package:')[1]?.split('  release:')[0] ?? ''
    expect(packageJobNeeds).toContain('needs: verify-tag')
  })

  it('先草稿、校验四类产物、再发布，失败只删草稿', () => {
    const workflow = repoFile('.github', 'workflows', 'release.yml')
    for (const fragment of [
      'contents: write',
      // 发布 job 不 checkout，必须以 GH_REPO 显式定位仓库，gh 才不回退 git 远端探测。
      'GH_REPO: ${{ github.repository }}',
      'dsh-anywhere_${VERSION}_windows_amd64_installer.exe',
      'dsh-anywhere_${VERSION}_macos_universal.dmg',
      'dsh-anywhere_${VERSION}_linux_amd64.AppImage',
      'dsh-anywhere_${VERSION}_linux_amd64.deb',
      'shasum -a 256',
      'SHA256SUMS',
      'gh release create "${GITHUB_REF_NAME}" --draft --verify-tag',
      'gh release edit "${GITHUB_REF_NAME}" --draft=false',
      'if: failure() || cancelled()',
      '--json isDraft --jq .isDraft',
      'gh release delete "${GITHUB_REF_NAME}" --yes'
    ]) {
      expect(workflow).toContain(fragment)
    }
  })

  it('发布说明包含未签名安装包的绕过步骤', () => {
    const workflow = repoFile('.github', 'workflows', 'release.yml')
    for (const fragment of ['Gatekeeper', 'SmartScreen', '仍要打开', '仍要运行']) {
      expect(workflow).toContain(fragment)
    }
  })
})

describe('electron-builder.yml', () => {
  const config = repoFile('electron-builder.yml')
  const release = repoFile('.github', 'workflows', 'release.yml')

  it('打包身份与应用显示名称同源（app-identity / build-identity）', () => {
    expect(config).toContain('appId: work.keepwriting.dsh-anywhere')
    expect(config).toContain(`productName: ${DISPLAY_NAME}`)
    expect(config).not.toContain('com.example')
    expect(config).not.toContain('My Product')
  })

  it('electronFuses 配置与 fuses.mts 的 FUSE_CONFIG 同源（electron-fuses）', () => {
    for (const [yamlKey, value] of [
      ['runAsNode', FUSE_CONFIG[FuseV1Options.RunAsNode]],
      ['enableNodeOptionsEnvironmentVariable', FUSE_CONFIG[FuseV1Options.EnableNodeOptionsEnvironmentVariable]],
      ['enableNodeCliInspectArguments', FUSE_CONFIG[FuseV1Options.EnableNodeCliInspectArguments]],
      ['resetAdHocDarwinSignature', FUSE_CONFIG.resetAdHocDarwinSignature]
    ] as const) {
      expect(config).toContain(`${yamlKey}: ${String(value)}`)
    }
    // 自定义 afterPack 钩子会在 universal 每架构构建时重签名导致合并失败，不得回流。
    expect(config).not.toContain('afterPack:')
  })

  it('四类产物命名模板与 release.yml 的断言一一对应', () => {
    for (const name of [
      'windows_amd64_installer.exe',
      'macos_universal.dmg',
      'linux_amd64.AppImage',
      'linux_amd64.deb'
    ]) {
      // electron-builder 模板用 ${version}，release.yml 断言用 ${VERSION}，其余字符必须一致。
      expect(config).toContain(`dsh-anywhere_\${version}_${name}`)
      expect(release).toContain(`dsh-anywhere_\${VERSION}_${name}`)
    }
  })
})

describe('版本号单一来源（package.json）', () => {
  it('version 是 x.y.z 且 major ≥ 1（自 1.0.0 起步，不回落 0.x 线）', () => {
    const version = readPackageVersion(join(repoRoot, 'package.json'))
    expect(version).toMatch(VERSION_PATTERN)
    expect(Number(version.split('.')[0])).toBeGreaterThanOrEqual(1)
  })

  it('package-lock.json 根版本与 package.json 同步（npm ci 逐字校验该同步）', () => {
    const version = readPackageVersion(join(repoRoot, 'package.json'))
    const lock = JSON.parse(repoFile('package-lock.json')) as {
      version?: string
      packages?: Record<string, { version?: string }>
    }
    expect(lock.version).toBe(version)
    expect(lock.packages?.['']?.version).toBe(version)
  })

  it('构建入口版本注入同源：vite define 与 dist.mts 都经 package-version.mts 读取 package.json', () => {
    expect(repoFile('electron.vite.config.ts')).toContain("readPackageVersion(resolve(__dirname, 'package.json'))")
    expect(repoFile('scripts', 'dist.mts')).toContain("readPackageVersion(resolve(repoRoot, 'package.json'))")
    // extraMetadata 注入参数与 artifactName 的 ${version} 用同一版本值（tag guard 之外的双保险）。
    expect(repoFile('scripts', 'dist.mts')).toContain('--config.extraMetadata.version=${version}')
    // git describe 派生链与 APP_VERSION 环境变量注入不得回流（第二版本来源）。
    for (const segments of [['electron.vite.config.ts'], ['scripts', 'dist.mts']] as const) {
      const content = repoFile(...segments)
      expect(content).not.toContain('DESCRIBE_ARGS')
      expect(content).not.toContain("spawnSync('git'")
      expect(content).not.toContain("process.env['APP_VERSION']")
    }
  })

  it('electron npm 包 ≥44 不自带 postinstall，根包 postinstall 幂等补装平台二进制', () => {
    // 没有该钩子，npm ci 后 e2e 与 dev 模式无 Electron 二进制，完整回归命令无法端到端通过。
    const pkg = repoFile('package.json')
    expect(pkg).toContain('"postinstall": "node scripts/install-electron.mts"')
    expect(repoFile('scripts', 'install-electron.mts')).toContain('install.js')
  })
})

describe('README.md', () => {
  it('记录未签名安装包首次启动的绕过步骤', () => {
    const readme = repoFile('README.md')
    for (const fragment of ['Gatekeeper', 'SmartScreen', '仍要运行', '仍要打开']) {
      expect(readme).toContain(fragment)
    }
  })
})
