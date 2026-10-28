// 设置窗口管理器：跟踪唯一的设置窗口实例，已打开则聚焦。
// 保存与取消由设置页面自行 window.close() 关窗，主进程不销毁该窗口。
// 窗口副作用以最小接口注入，单元测试无需真实 BrowserWindow。

export interface SettingsWindowLike {
  isDestroyed(): boolean
  show(): void
  focus(): void
}

export interface CreateSettingsWindow {
  // 新建设置窗口；无法创建（如应用正在退出）时返回 null。
  (): SettingsWindowLike | null
}

export class SettingsWindowManager {
  private window: SettingsWindowLike | null = null

  // open 打开设置窗口：已存在且未销毁时显示并聚焦它，否则经 create 新建
  // （settings-window 主规格 open-twice-focuses）。
  open(create: CreateSettingsWindow): void {
    if (this.window !== null && !this.window.isDestroyed()) {
      this.window.show()
      this.window.focus()
      return
    }
    const created = create()
    if (created !== null) {
      this.window = created
    }
  }

}
