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
        // 单主窗口场景（macos-shell-window）：无「新建窗口」入口，关闭最后一个窗口
        // 应用保持运行，Dock 点击重开主窗口；单实例由 LaunchServices 对同 bundle id
        // 的默认行为承担（重复打开只激活既有实例）。
        Window(AppIdentity.displayName, id: "main") {
            ContentView(model: model, chrome: chrome)
        }
        .defaultSize(width: MacosWindowState.defaultWidth, height: MacosWindowState.defaultHeight)
        .commands {
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
