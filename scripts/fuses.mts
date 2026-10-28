import { FuseV1Options, FuseVersion, flipFuses } from '@electron/fuses'
import type { FuseConfig } from '@electron/fuses'

// 打包产物必须关闭的能力（技术标准 electron-layout-security/electron-fuses）。
// 只写入打包后的 Electron 二进制：开发与 Playwright 端到端测试依赖调试参数启动 node_modules 中的 Electron。
export const FUSE_CONFIG: FuseConfig = {
  version: FuseVersion.V1,
  [FuseV1Options.RunAsNode]: false,
  [FuseV1Options.EnableNodeOptionsEnvironmentVariable]: false,
  [FuseV1Options.EnableNodeCliInspectArguments]: false
}

export async function applyFuses(electronPath: string): Promise<void> {
  await flipFuses(electronPath, FUSE_CONFIG)
}
