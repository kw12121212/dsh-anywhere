// 版本号单一来源（技术标准 packaging/package-version-single-source）：package.json
// 的 version 字段。electron.vite.config.ts 的 __APP_VERSION__ define 与 scripts/dist.mts
// 的 electron-builder extraMetadata.version 注入都经本模块读取同一文件；版本非法时
// 在构建入口直接报错，不静默回退（历史上的 0.0.0 安装包与 dev 关于页即回退造成）。
import { readFileSync } from 'node:fs'

// 与 workflows.test.mts 的统一性断言共享同一公式：只接受 x.y.z，不接受预发布号。
export const VERSION_PATTERN = /^\d+\.\d+\.\d+$/

export function versionFromManifest(raw: string): string {
  let manifest: unknown
  try {
    manifest = JSON.parse(raw)
  } catch {
    throw new Error('package.json 不是合法 JSON，无法确定版本号')
  }
  const version = (manifest as { version?: unknown }).version
  if (typeof version !== 'string' || !VERSION_PATTERN.test(version)) {
    throw new Error(`package.json 的 version 必须是 x.y.z 形式（当前：${String(version)}）`)
  }
  return version
}

export function readPackageVersion(packageJsonPath: string): string {
  return versionFromManifest(readFileSync(packageJsonPath, 'utf8'))
}
