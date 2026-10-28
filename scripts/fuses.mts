import { FuseV1Options, FuseVersion, flipFuses } from '@electron/fuses'
import type { FuseConfig } from '@electron/fuses'

// 打包产物必须关闭的能力（技术标准 electron-layout-security/electron-fuses）。
// 只写入打包后的 Electron 二进制：开发与 Playwright 端到端测试依赖调试参数启动 node_modules 中的 Electron。
export const FUSE_CONFIG: FuseConfig = {
  version: FuseVersion.V1,
  // 翻转 fuse 会让 macOS 的 ad-hoc 签名失效；本应用不做代码签名，
  // 不重置签名则 arm64/universal 包（含 dmg 内产物）启动即被拒。
  resetAdHocDarwinSignature: true,
  [FuseV1Options.RunAsNode]: false,
  [FuseV1Options.EnableNodeOptionsEnvironmentVariable]: false,
  [FuseV1Options.EnableNodeCliInspectArguments]: false
}

export async function applyFuses(electronPath: string): Promise<void> {
  await flipFuses(electronPath, FUSE_CONFIG)
}
