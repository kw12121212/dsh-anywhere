// electron-builder afterPack 钩子：对解包产物翻转 electron fuses。
// fuse 配置集中在 scripts/fuses.mts（技术标准 electron-layout-security/electron-fuses），
// 安装包内二进制与开发期基线保持一致（设计决策 fuses-afterpack）。
import { join } from 'node:path'
import { applyFuses } from './fuses.mts'

// darwin 传 .app 路径（@electron/fuses 自行解析包内 Electron Framework），
// win32/linux 传可执行文件路径。
const EXECUTABLES: Record<string, string> = {
  win32: 'DSH Anywhere.exe',
  darwin: 'DSH Anywhere.app',
  linux: 'dsh-anywhere'
}

export function executableForPlatform(electronPlatformName: string): string {
  const executable = EXECUTABLES[electronPlatformName]
  if (!executable) {
    throw new Error(`unsupported platform for fuses: ${electronPlatformName}`)
  }
  return executable
}

export default async function afterPack(context: {
  appOutDir: string
  electronPlatformName: string
}): Promise<void> {
  await applyFuses(join(context.appOutDir, executableForPlatform(context.electronPlatformName)))
}
