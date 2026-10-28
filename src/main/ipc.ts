import { APP_SCHEME, APP_HOST } from './app-protocol'
import { IPC_CHANNELS } from './view-state'
import type { LocalState, SaveResult } from './view-state'

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

// 单个地址参数的长度上限：harness 地址远短于此，超长输入直接拒绝。
const MAX_URL_ARGUMENT_LENGTH = 8192

export interface LocalPageIpcDeps {
  allowedOrigins: readonly string[]
  getState(): LocalState
  retry(): void
  // completeSetup 校验并保存地址，成功后让主窗口用原始地址打开一次（first-run-setup 主规格 complete-setup）。
  completeSetup(raw: string): SaveResult
  // openSettings 打开或聚焦设置窗口（错误页“修改地址”与重新认证页“打开设置”按钮）。
  openSettings(): void
  // getSettingsURL 返回当前已保存的干净地址；未配置或配置无效时为空字符串。
  getSettingsURL(): string
  // saveSettings 校验并保存地址，按 settings-policy 决定主窗口是否用原始地址打开一次；
  // 成功后设置窗口由其页面自行 window.close() 关闭。
  saveSettings(raw: string): SaveResult
}

// registerLocalPageIpc 注册本地页面（主窗口与设置窗口）的全部 IPC：每个处理函数先校验
// 发送方是允许来源的主 frame，参数按白名单校验——无参通道不接受任何参数，地址通道只接受
// 一个非超长字符串；不符即拒绝，日志不记录参数（技术标准 electron-layout-security/ipc-sender-validation）。
export function registerLocalPageIpc(ipc: IpcRegistrar, deps: LocalPageIpcDeps): void {
  const guardNoArgs = (event: IpcEvent, args: unknown[]): void => {
    if (!isTrustedSender(event, deps.allowedOrigins) || args.length !== 0) {
      throw new Error('拒绝来自非本地页面或参数无效的请求')
    }
  }
  const guardUrlArg = (event: IpcEvent, args: unknown[]): string => {
    if (!isTrustedSender(event, deps.allowedOrigins) || args.length !== 1) {
      throw new Error('拒绝来自非本地页面或参数无效的请求')
    }
    const raw = args[0]
    if (typeof raw !== 'string' || raw.length > MAX_URL_ARGUMENT_LENGTH) {
      throw new Error('拒绝来自非本地页面或参数无效的请求')
    }
    return raw
  }
  ipc.handle(IPC_CHANNELS.getState, (event, ...args) => {
    guardNoArgs(event, args)
    return deps.getState()
  })
  ipc.handle(IPC_CHANNELS.retry, (event, ...args) => {
    guardNoArgs(event, args)
    deps.retry()
  })
  ipc.handle(IPC_CHANNELS.openSettings, (event, ...args) => {
    guardNoArgs(event, args)
    deps.openSettings()
  })
  ipc.handle(IPC_CHANNELS.completeSetup, (event, ...args) => deps.completeSetup(guardUrlArg(event, args)))
  ipc.handle(IPC_CHANNELS.getSettingsURL, (event, ...args) => {
    guardNoArgs(event, args)
    return deps.getSettingsURL()
  })
  ipc.handle(IPC_CHANNELS.saveSettings, (event, ...args) => deps.saveSettings(guardUrlArg(event, args)))
}
