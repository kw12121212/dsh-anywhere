import AppKit
import DshCore
import SwiftUI

@main
struct DshAnywhereApp: App {
    @State private var model = AppSessionModel()
    @State private var chrome: WindowChromeModel

    init() {
        let store = WindowStateStore(directory: NativeDataDirectory.url)
        let screens = NSScreen.screens.map { screen in
            let frame = screen.visibleFrame
            return MacosScreenState(
                x: frame.origin.x, y: frame.origin.y, width: frame.width, height: frame.height
            )
        }
        _chrome = State(initialValue: WindowChromeModel(store: store, screens: screens))
    }

    var body: some Scene {
        // 单主窗口（macos-shell-window）：真机实测 `Window` 场景关闭窗口即退出应用
        //（单窗口工具语义，违背「关窗常驻」规格），故按 design 登记的回退方案用
        // WindowGroup 保证启动显示与关窗常驻、Dock 点击重开，并移除「新建窗口」
        // 入口维持单主窗口；单实例由 LaunchServices 对同 bundle id 的默认行为承担。
        WindowGroup(AppIdentity.displayName) {
            ContentView(model: model, chrome: chrome)
        }
        .defaultSize(width: MacosWindowState.defaultWidth, height: MacosWindowState.defaultHeight)
        .commands {
            // 单主窗口：移除「文件 → 新建窗口」（⌘N）入口。
            CommandGroup(replacing: .newItem) {}
            // 应用菜单：固定中文标签的关于入口（标准关于面板，版本来自 Info.plist）。
            CommandGroup(replacing: .appInfo) {
                Button("关于 \(AppIdentity.displayName)") {
                    NSApplication.shared.orderFrontStandardAboutPanel(nil)
                }
            }
            // 视图菜单（macos-app 规格 macos-app-menus-and-shortcuts）：重新加载复用
            // 既有重连编排（重新探测、重取 state 并重订阅 feed）；侧栏与检查器开合与
            // 界面共用同一绑定，菜单项状态始终与界面一致。
            CommandMenu("视图") {
                Button("重新加载") {
                    model.retry()
                }
                .keyboardShortcut("r", modifiers: .command)
                .disabled(model.snapshot.isSubmitting)

                Divider()

                // 团队侧栏搜索（macos-team-sidebar）：⌘K 聚焦侧栏搜索框。
                Button("搜索聊天") {
                    model.requestSidebarSearchFocus()
                }
                .keyboardShortcut("k", modifiers: .command)

                Button(chrome.sidebarVisibility == .detailOnly ? "显示侧栏" : "隐藏侧栏") {
                    chrome.sidebarVisibility = chrome.sidebarVisibility == .detailOnly ? .all : .detailOnly
                }
                Button(chrome.inspectorVisible ? "隐藏检查器" : "显示检查器") {
                    chrome.inspectorVisible.toggle()
                }
            }
        }

        Settings {
            SettingsView(model: model)
        }
    }
}
