// 激活已运行实例主窗口所需的最小操作集合，由装配层用 BrowserWindow 方法提供，
// 单元测试用记录器替换（语义与 Go 版 internal/singleinstance 一致）。
export interface ActivationWindow {
  isDestroyed(): boolean
  isMinimised(): boolean
  restore(): void
  show(): void
  focus(): void
}

// SingleInstanceActivator 把第二个实例的启动通知转换为对已运行实例主窗口的激活。
//
// requestSingleInstanceLock 在 app ready 之前调用，second-instance 可能早于主窗口
// 创建到达；窗口未就绪时的请求只记录为待处理，由 start 在窗口就绪后补做一次，
// 对应 single-instance 主规格的 second-launch-during-startup 场景。
export class SingleInstanceActivator {
  private window: ActivationWindow | null = null
  private started = false
  private pending = false

  // bind 记录主窗口的操作集合，必须在 start 之前调用。
  bind(window: ActivationWindow): void {
    this.window = window
  }

  // start 表示主窗口已就绪，可以安全调用窗口方法；有提前到达的请求时立即补做。
  start(): void {
    this.started = true
    if (this.pending) {
      this.pending = false
      this.activate()
    }
  }

  // request 处理一次第二个实例的启动通知：窗口就绪后立即激活，否则等待 start。
  request(): void {
    if (!this.started) {
      this.pending = true
      return
    }
    this.activate()
  }

  // activate 顺序固定：最小化窗口先恢复，再显示，最后聚焦。窗口已销毁时不做任何事。
  private activate(): void {
    const window = this.window
    if (!window || window.isDestroyed()) {
      return
    }
    if (window.isMinimised()) {
      window.restore()
    }
    window.show()
    window.focus()
  }
}
