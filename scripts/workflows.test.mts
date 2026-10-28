// CI/发布工作流与打包配置的结构断言（技术标准 packaging/workflow-structure-assertion-tests）。
// 迁移自 internal/appinfo 下的 Go 断言测试：关键片段正向断言、负向断言，并锁定
// 被复用上游的关键前提（package.yml 的 workflow_call 触发器）。
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
import { FuseV1Options } from '@electron/fuses'
import { describe, expect, it } from 'vitest'
import { DISPLAY_NAME } from '../src/main/app-info'
import { FUSE_CONFIG } from './fuses.mts'

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

describe('regression.yml', () => {
  it('三平台矩阵逐字执行 config.yaml 的完整回归命令', () => {
    const workflow = repoFile('.github', 'workflows', 'regression.yml')
    const command = regressionCommand()
    expect(workflow).toContain(`\n          ${command}\n`)
    // 回归命令必须保留完整的 Node 工具链步骤，缺一步即口径漂移。
    for (const fragment of [
      'npm run typecheck',
      'npm run build',
      'npm run test:coverage',
      'npm run test:e2e'
    ]) {
      expect(command).toContain(fragment)
    }
    for (const fragment of [
      '- ubuntu-latest',
      '- macos-latest',
      '- windows-latest',
      'shell: bash',
      'package-lock.json'
    ]) {
      expect(workflow).toContain(fragment)
    }
  })

  it('回归前为 Linux 准备显示服务器与 Electron 沙箱，且不含 Go/GTK 步骤', () => {
    const workflow = repoFile('.github', 'workflows', 'regression.yml')
    const regression = workflow.indexOf('- name: Full project regression')
    expect(regression).toBeGreaterThanOrEqual(0)
    for (const fragment of [
      'Xvfb :99',
      'echo "DISPLAY=:99" >> "$GITHUB_ENV"',
      'kernel.apparmor_restrict_unprivileged_userns=0'
    ]) {
      const at = workflow.indexOf(fragment)
      expect(at).toBeGreaterThanOrEqual(0)
      // 显示服务器与沙箱必须先于回归步骤就绪。
      expect(at).toBeLessThan(regression)
    }
    // 负向断言：环境由前置步骤提供，命令保持三平台一致，不以关闭沙箱绕过安全基线；
    // Go/Wails 工具链与 GTK 开发库不得回流。
    for (const forbidden of [
      'xvfb-run',
      '--no-sandbox',
      'setup-go',
      'go test',
      'go vet',
      'libgtk-4-dev',
      'libwebkitgtk-6.0-dev'
    ]) {
      expect(workflow).not.toContain(forbidden)
    }
  })
})

describe('package.yml', () => {
  it('只经 npm 打包 script 调用 electron-builder，不复制打包命令', () => {
    const workflow = repoFile('.github', 'workflows', 'package.yml')
    for (const fragment of [
      'workflow_call',
      'fetch-depth: 0',
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
    // 负向断言：Go/Wails/GTK 与 NSIS choco 步骤不得回流，也不得直接调 electron-builder。
    for (const forbidden of [
      'go build',
      'wails3',
      'setup-go',
      'choco install nsis',
      'libgtk-4-dev',
      'libwebkitgtk-6.0-dev',
      'npx electron-builder'
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
    // 唯一允许的外部 action 是官方 download-artifact；发布步骤只用 gh CLI。
    for (const line of workflow.split('\n')) {
      if (
        line.includes('uses:') &&
        !line.includes('actions/download-artifact') &&
        !line.includes('uses: ./.github/workflows/package.yml')
      ) {
        throw new Error(`release.yml uses a third-party action instead of gh CLI: ${line.trim()}`)
      }
    }
  })

  it('先草稿、校验四类产物、再发布，失败只删草稿', () => {
    const workflow = repoFile('.github', 'workflows', 'release.yml')
    for (const fragment of [
      'contents: write',
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

describe('README.md', () => {
  it('记录未签名安装包首次启动的绕过步骤', () => {
    const readme = repoFile('README.md')
    for (const fragment of ['Gatekeeper', 'SmartScreen', '仍要运行', '仍要打开']) {
      expect(readme).toContain(fragment)
    }
  })
})
