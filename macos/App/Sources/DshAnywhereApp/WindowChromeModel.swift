import AppKit
import DshCore
import Observation
import SwiftUI

/// 主窗口外壳的窗口装配状态（macos-shell-window）：侧栏与检查器开合、侧栏宽度观测，
/// 以及经 `WindowStateStore` 的恢复与持久化。恢复值（初始侧栏宽度）在启动时一次性解析
/// 后固定，不随拖动回灌，避免与 NavigationSplitView 的列宽 ideal 互相拉扯；
/// `liveSidebarWidth` 仅由侧栏内容的几何观测更新并用于下次持久化。
@MainActor
@Observable
final class WindowChromeModel {
    var sidebarVisibility: NavigationSplitViewVisibility
    var inspectorVisible: Bool
    var liveSidebarWidth: Double

    /// 恢复/默认的侧栏初始列宽（固定作为列宽 ideal，不随拖动变化）。
    private(set) var restoredSidebarWidth: Double

    private let store: WindowStateStore
    private let coordinator: WindowPersistenceCoordinator

    init(store: WindowStateStore, screens: [MacosScreenState]) {
        self.store = store
        if let saved = store.load(), saved.intersectsAnyScreen(screens) {
            sidebarVisibility = saved.sidebarVisible ? .all : .detailOnly
            inspectorVisible = saved.inspectorVisible
            restoredSidebarWidth = saved.sidebarWidth
            liveSidebarWidth = saved.sidebarWidth
        } else {
            // 无状态、损坏或保存位置不在任何可见屏幕内：全部取默认值（几何由协调器居中）。
            sidebarVisibility = .all
            inspectorVisible = true
            restoredSidebarWidth = MacosWindowState.defaultSidebarWidth
            liveSidebarWidth = MacosWindowState.defaultSidebarWidth
        }
        // 全部存储属性初始化完成后再把自身交给协调器（Swift 不允许初始化期间部分
        // 初始化的 self 作为参数传递）。
        coordinator = WindowPersistenceCoordinator(store: store)
        coordinator.chrome = self
    }

    /// 主窗口挂载/重挂载（Dock 重开会创建新 NSWindow）时恢复几何或按默认尺寸居中。
    func windowDidChange(_ window: NSWindow?) {
        guard let window else { return }
        coordinator.attach(to: window)
    }

    /// 侧栏宽度观测入口：收起侧栏时列内容可能以接近 0 的宽度触发回调，忽略低于
    /// 最小列宽的观测值，避免用户的拖宽记忆被清零。
    func updateLiveSidebarWidth(_ raw: Double) {
        guard raw >= MacosWindowState.minSidebarWidth else { return }
        liveSidebarWidth = raw
    }
}

/// 主窗口几何的恢复与持久化协调器：窗口挂载时把保存几何应用到窗口（保存值在当前可见
/// 屏幕内时）或按默认尺寸居中主显示器；主窗口关闭与应用退出时把当前几何、侧栏与检查器
/// 开合及侧栏宽度经 `WindowStateStore` 原子写入（tech/state-persistence：persist-on-exit，
/// 不在每次拖动时写盘）。
@MainActor
final class WindowPersistenceCoordinator: NSObject {
    private weak var window: NSWindow?
    weak var chrome: WindowChromeModel?
    private let store: WindowStateStore
    private var didRegister = false

    init(store: WindowStateStore) {
        self.store = store
        super.init()
    }

    func attach(to window: NSWindow) {
        guard window !== self.window else { return }
        self.window = window
        restoreOrCenter(window: window)
        guard !didRegister else { return }
        didRegister = true
        let nc = NotificationCenter.default
        nc.addObserver(
            self, selector: #selector(windowWillClose(_:)),
            name: NSWindow.willCloseNotification, object: nil
        )
        nc.addObserver(
            self, selector: #selector(appWillTerminate(_:)),
            name: NSApplication.willTerminateNotification, object: nil
        )
    }

    @objc private func windowWillClose(_ note: Notification) {
        // 只处理主窗口的关闭（设置窗口等其他窗口的关闭不触发持久化）。
        guard let closing = note.object as? NSWindow, closing === window else { return }
        persistCurrentState()
    }

    @objc private func appWillTerminate(_ note: Notification) {
        persistCurrentState()
    }

    private func restoreOrCenter(window: NSWindow) {
        let screens = Self.currentScreens()
        if let saved = store.load(), saved.intersectsAnyScreen(screens) {
            // 保存几何与当前可见屏幕相交：按保存的位置与尺寸恢复。
            window.setFrame(
                CGRect(x: saved.x, y: saved.y, width: saved.width, height: saved.height),
                display: false
            )
        } else {
            // 无状态或保存位置在所有屏幕之外（如外接屏已拔掉）：默认尺寸居中主显示器。
            let visible = NSScreen.screens.first?.visibleFrame
                ?? CGRect(x: 0, y: 0, width: 1440, height: 900)
            let size = CGSize(width: MacosWindowState.defaultWidth, height: MacosWindowState.defaultHeight)
            window.setFrame(
                CGRect(
                    x: visible.midX - size.width / 2,
                    y: visible.midY - size.height / 2,
                    width: size.width,
                    height: size.height
                ),
                display: false
            )
        }
    }

    private func persistCurrentState() {
        guard let window, let chrome else { return }
        let frame = window.frame
        try? store.save(
            MacosWindowState(
                x: frame.origin.x,
                y: frame.origin.y,
                width: frame.width,
                height: frame.height,
                sidebarVisible: chrome.sidebarVisibility != .detailOnly,
                inspectorVisible: chrome.inspectorVisible,
                sidebarWidth: chrome.liveSidebarWidth
            )
        )
    }

    private static func currentScreens() -> [MacosScreenState] {
        NSScreen.screens.map { screen in
            let frame = screen.visibleFrame
            return MacosScreenState(
                x: frame.origin.x, y: frame.origin.y, width: frame.width, height: frame.height
            )
        }
    }
}
