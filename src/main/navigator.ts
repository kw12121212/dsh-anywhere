import type { ConfigLoad } from './config'
import type { FailureReason, ProbeResult } from './probe'
import type { LocalState } from './view-state'
import { normalizeHarnessURL } from './url-validation'

export interface NavigatorDeps {
  loadConfig(): ConfigLoad
  probe(url: string): Promise<ProbeResult>
  showLocal(state: LocalState): void
  showRemote(url: string): void
}

// Navigator 决定主窗口显示本地页面还是远程 harness。每次导航递增代次，
// 异步探测完成时代次已变则丢弃结果（技术标准 electron-layout-security/serialize-navigation-effects）。
export class Navigator {
  private generation = 0
  private remote = false
  private local: LocalState = { view: 'connecting' }
  private harness: string | null = null
  private disposed = false

  constructor(private readonly deps: NavigatorDeps) {}

  // 最近一次显示的本地页面状态，供本地页面加载后查询。
  get localState(): LocalState {
    return this.local
  }

  // 当前已保存的干净 harness 地址；没有有效配置时为 null。
  get harnessURL(): string | null {
    return this.harness
  }

  // 当前导航代次；异步观察远程页面的调用方在开始观察时记下它，完成时随结果一起交回。
  get generationId(): number {
    return this.generation
  }

  // 主窗口当前是否正显示远程 harness 页面；设置保存后判断是否需要重新打开时使用。
  get showsHarness(): boolean {
    return this.remote && !this.disposed
  }

  // dispose 在窗口关闭后作废控制器：之后到达的异步结果与调用都不再触达已销毁的窗口。
  dispose(): void {
    this.disposed = true
    this.remote = false
    this.generation++
  }

  // open 用于启动、菜单“重新加载”与错误页“重试”：读取配置，探测已保存地址后打开它或显示错误页。
  async open(): Promise<void> {
    if (this.disposed) {
      return
    }
    const generation = ++this.generation
    const config = this.deps.loadConfig()
    if (config.status !== 'ok') {
      this.harness = null
      this.showLocal({ view: 'unconfigured', invalidConfig: config.status === 'invalid' })
      return
    }
    this.harness = config.url
    this.showLocal({ view: 'connecting' })
    const result = await this.deps.probe(config.url)
    if (generation !== this.generation) {
      return
    }
    if (result.ok) {
      this.remote = true
      this.deps.showRemote(config.url)
    } else {
      this.showLocal({ view: 'error', url: config.url, reason: result.reason })
    }
  }

  // openRemote 用用户输入的原始地址（可能带 token）打开远程视图一次，供初始化完成与
  // 设置保存后建立 dsh 会话：先经共享校验模块计算干净地址并更新 harness 状态（错误页
  // 与重新加载随后使用它），再显示远程视图。校验失败抛出 URLValidationError，不改变
  // 任何状态、不导航，由调用方把可读错误返回给本地页面。
  openRemote(raw: string): void {
    if (this.disposed) {
      return
    }
    const trimmed = raw.trim()
    const clean = normalizeHarnessURL(trimmed)
    this.harness = clean
    this.remote = true
    this.local = { view: 'connecting' }
    this.generation++
    this.deps.showRemote(trimmed)
  }

  // sessionExpired 在远程页面返回 dsh 会话失效时显示重新认证页；observedGeneration 是开始读取失效页时的代次，
  // 代次已变（期间发生了新的导航）或不在显示远程页面时忽略。
  sessionExpired(observedGeneration: number): void {
    if (this.remote && observedGeneration === this.generation) {
      this.generation++
      this.showLocal({ view: 'reauth' })
    }
  }

  // loadFailed 在远程页面加载因网络错误失败时显示错误页；不在显示远程页面时忽略。
  loadFailed(reason: FailureReason): void {
    if (this.remote && this.harness !== null) {
      this.generation++
      this.showLocal({ view: 'error', url: this.harness, reason })
    }
  }

  private showLocal(state: LocalState): void {
    this.remote = false
    this.local = state
    this.deps.showLocal(state)
  }
}
