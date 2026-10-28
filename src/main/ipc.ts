import { APP_SCHEME, APP_HOST } from './app-protocol'
import { IPC_CHANNELS } from './view-state'
import type { LocalState } from './view-state'

export const PACKAGED_ORIGIN = `${APP_SCHEME}://${APP_HOST}`

// allowedLocalOrigins 返回可调用 IPC 的本地页面来源：打包后只接受 app://local，
// 未打包且设置了开发服务器地址时额外接受该来源（技术标准 electron-layout-security/ipc-sender-validation）。
export function allowedLocalOrigins(isPackaged: boolean, devServerURL: string | undefined): string[] {
  const origins = [PACKAGED_ORIGIN]
  if (!isPackaged && devServerURL) {
    const dev = originOf(devServerURL)
    if (dev !== null) {
      origins.push(dev)
    }
  }
  return origins
}

// originOf 按“协议//主机[:端口]”计算来源；WHATWG URL 对 app: 等非特殊协议的 origin 为 "null"，不能直接使用。
function originOf(raw: string): string | null {
  try {
    const url = new URL(raw)
    return url.host === '' ? null : `${url.protocol}//${url.host}`
  } catch {
    return null
  }
}

export interface SenderFrame {
  url: string
  parent: unknown
}

export interface IpcEvent {
  senderFrame: SenderFrame | null
}

// isTrustedSender 只接受来自允许来源的主 frame。
export function isTrustedSender(event: IpcEvent, allowed: readonly string[]): boolean {
  const frame = event.senderFrame
  if (frame === null || frame.parent !== null) {
    return false
  }
  const origin = originOf(frame.url)
  return origin !== null && allowed.includes(origin)
}

export interface IpcRegistrar {
  handle(channel: string, listener: (event: IpcEvent, ...args: unknown[]) => unknown): void
}

export interface MainWindowIpcDeps {
  allowedOrigins: readonly string[]
  getState(): LocalState
  retry(): void
}

// registerMainWindowIpc 注册本地页面的 IPC：两个通道都不接受参数，发送方或参数不符即拒绝，日志不记录参数。
export function registerMainWindowIpc(ipc: IpcRegistrar, deps: MainWindowIpcDeps): void {
  const guard = (event: IpcEvent, args: unknown[]): void => {
    if (!isTrustedSender(event, deps.allowedOrigins) || args.length !== 0) {
      throw new Error('拒绝来自非本地页面或参数无效的请求')
    }
  }
  ipc.handle(IPC_CHANNELS.getState, (event, ...args) => {
    guard(event, args)
    return deps.getState()
  })
  ipc.handle(IPC_CHANNELS.retry, (event, ...args) => {
    guard(event, args)
    deps.retry()
  })
}
