import AppKit
import Foundation

private final class ObserverTokenBox: @unchecked Sendable {
    private let lock = NSLock()
    private var defaultTokens: [NSObjectProtocol] = []
    private var workspaceTokens: [NSObjectProtocol] = []

    func addDefault(_ token: NSObjectProtocol) {
        lock.withLock { defaultTokens.append(token) }
    }

    func addWorkspace(_ token: NSObjectProtocol) {
        lock.withLock { workspaceTokens.append(token) }
    }

    func removeAll() {
        let (d, w) = lock.withLock {
            let pair = (defaultTokens, workspaceTokens)
            defaultTokens.removeAll()
            workspaceTokens.removeAll()
            return pair
        }
        let defaultCenter = NotificationCenter.default
        for token in d {
            defaultCenter.removeObserver(token)
        }
        let workspaceCenter = NSWorkspace.shared.notificationCenter
        for token in w {
            workspaceCenter.removeObserver(token)
        }
    }
}

/// 监听 macOS 系统休眠/唤醒与窗口隐藏/最小化状态，驱动连接控制器断开或保游标补齐。
@MainActor
final class AppLifecycleMonitor {
    private let tokenBox = ObserverTokenBox()
    private let onActiveChanged: @MainActor (Bool) -> Void
    private var isSleeping = false
    private var isHidden = false
    private var lastActive = true

    init(onActiveChanged: @escaping @MainActor (Bool) -> Void) {
        self.onActiveChanged = onActiveChanged
        registerObservers()
    }

    deinit {
        tokenBox.removeAll()
    }

    private func registerObservers() {
        let wsCenter = NSWorkspace.shared.notificationCenter
        tokenBox.addWorkspace(wsCenter.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.isSleeping = true
                self.evaluate(closingWindow: nil)
            }
        })
        tokenBox.addWorkspace(wsCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.isSleeping = false
                self.evaluate(closingWindow: nil)
            }
        })

        let center = NotificationCenter.default
        let appNotifications: [Notification.Name] = [
            NSApplication.didHideNotification,
            NSApplication.didUnhideNotification,
            NSApplication.didBecomeActiveNotification,
        ]
        for name in appNotifications {
            tokenBox.addDefault(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] note in
                let noteName = note.name
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    if noteName == NSApplication.didHideNotification {
                        self.isHidden = true
                    } else if noteName == NSApplication.didUnhideNotification {
                        self.isHidden = false
                    }
                    self.evaluate(closingWindow: nil)
                }
            })
        }

        let windowNotifications: [Notification.Name] = [
            NSWindow.didMiniaturizeNotification,
            NSWindow.didDeminiaturizeNotification,
            NSWindow.didBecomeKeyNotification,
            NSWindow.didChangeOcclusionStateNotification,
            NSWindow.willCloseNotification,
        ]
        for name in windowNotifications {
            tokenBox.addDefault(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] note in
                let closing = note.name == NSWindow.willCloseNotification ? (note.object as? NSWindow) : nil
                Task { @MainActor [weak self] in
                    self?.evaluate(closingWindow: closing)
                }
            })
        }
    }

    private func evaluate(closingWindow: NSWindow?) {
        let hasVisibleWindow = NSApplication.shared.windows.contains { window in
            if let closingWindow, window === closingWindow { return false }
            return window.isVisible && !window.isMiniaturized && window.occlusionState.contains(.visible)
        }
        let nextActive = !isSleeping && !isHidden && hasVisibleWindow
        if nextActive != lastActive {
            lastActive = nextActive
            onActiveChanged(nextActive)
        }
    }
}
