import AppKit
import DshCore
import Foundation
import Observation

@MainActor
@Observable
final class AppSessionModel {
    var snapshot = MacosSessionSnapshot()
    var urlInput: String = ""
    var diagnosticsConfigured = false
    var configuredDiagnosticsEndpoint: String?

    /// 团队侧栏装配状态（macos-team-sidebar）与 ⌘K 搜索聚焦请求计数。
    let sidebar: TeamSidebarModel
    private(set) var sidebarSearchFocusTick = 0

    private var controller: MacosSessionController!
    private var lifecycleMonitor: AppLifecycleMonitor?
    private var diagnosticsRuntime: DiagnosticsRuntime?
    private var diagnosticsStore: DiagnosticsEndpointStore?
    private var diagnosticsQueue: DiagnosticsQueue?
    private let prefsStore: NativePrefsStore
    private let noticePrefs: NoticePrefsBox
    private var started = false

    init() {
        let dataDir = NativeDataDirectory.url
        let electronDirs = ElectronConfigMigrator.defaultElectronDirectories(
            applicationSupportDirectory: dataDir.deletingLastPathComponent()
        )
        let configStore = HarnessConfigStore(directory: dataDir)
        let cookieStore = SessionCookieStore(directory: dataDir)
        let probeTransport = UrlSessionHttpTransport(requestTimeout: 10, resourceTimeout: 10)
        let apiTransport = UrlSessionHttpTransport(requestTimeout: 30, resourceTimeout: 60)
        let sseStream = UrlSessionSseStream()

        // 本机偏好（应用内提醒开关，默认开）：横幅门控经线程安全闭包注入控制器。
        let prefsStore = NativePrefsStore(directory: dataDir)
        self.prefsStore = prefsStore
        let noticePrefs = NoticePrefsBox((prefsStore.load() ?? NativePrefs()).noticeBannerEnabled)
        self.noticePrefs = noticePrefs
        self.sidebar = TeamSidebarModel(store: ReadStateStore(directory: dataDir))

        self.controller = MacosSessionController(
            configStore: configStore,
            cookieStore: cookieStore,
            probeTransport: probeTransport,
            apiTransport: apiTransport,
            sseStream: sseStream,
            electronConfigDirectories: electronDirs,
            noticesEnabled: { noticePrefs.value },
            onSnapshot: { [weak self] next in
                Task { @MainActor [weak self] in
                    self?.applySnapshot(next)
                }
            }
        )

        // 诊断上报装配（macos-diagnostics）：端点经 DiagnosticsEndpointStore、队列在数据目录
        // 独立子目录、发送经禁用系统 cookie 的 ephemeral URLSession 直连（2xx 送达、4xx 拒绝
        // 丢弃、其余失败保留）；未配置端点时全部采集路径零行为。
        let diagnosticsStore = DiagnosticsEndpointStore(directory: dataDir)
        let diagnosticsQueue = DiagnosticsQueue(
            directory: dataDir.appendingPathComponent("diagnostics-reports", isDirectory: true)
        )
        let diagSession = URLSession(
            configuration: makeEphemeralConfiguration(requestTimeout: 30, resourceTimeout: 60),
            delegate: NoRedirectDelegate(),
            delegateQueue: nil
        )
        let osVersion = ProcessInfo.processInfo.operatingSystemVersion
        let runtime = DiagnosticsRuntime(
            deps: DiagnosticsRuntimeDeps(
                queue: diagnosticsQueue,
                loadEndpoint: {
                    if case .ok(let endpoint) = diagnosticsStore.load() {
                        return endpoint
                    }
                    return nil
                },
                send: { target, body in
                    guard let url = URL(string: target) else { return .failed }
                    var request = URLRequest(url: url)
                    request.httpMethod = "POST"
                    request.httpBody = Data(body.utf8)
                    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                    request.httpShouldHandleCookies = false
                    guard let (data, rawResponse) = try? await diagSession.data(for: request) as (Data, URLResponse) else {
                        return .failed
                    }
                    _ = data
                    guard let http = rawResponse as? HTTPURLResponse else { return .failed }
                    switch http.statusCode {
                    case 200..<300:
                        return .delivered
                    case 400..<500:
                        return .rejected
                    default:
                        return .failed
                    }
                },
                appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev",
                device: Host.current().localizedName ?? "Mac",
                osVersion: "macOS \(osVersion.majorVersion).\(osVersion.minorVersion).\(osVersion.patchVersion)"
            )
        )
        self.diagnosticsStore = diagnosticsStore
        self.diagnosticsQueue = diagnosticsQueue
        self.diagnosticsRuntime = runtime
        if case .ok(let endpoint) = diagnosticsStore.load() {
            diagnosticsConfigured = true
            configuredDiagnosticsEndpoint = endpoint
        }
        CrashReporter.install(runtime: runtime)
    }

    func startIfNeeded() {
        guard !started else { return }
        started = true
        lifecycleMonitor = AppLifecycleMonitor { [weak self] active in
            guard let self else { return }
            Task {
                await self.controller.setActive(active)
            }
        }
        // 启动时冲刷待发队列（崩溃报告补报与上次失败重试）；未配置端点时零行为。
        let runtime = diagnosticsRuntime
        Task {
            await controller.start()
            await runtime?.flush()
        }
    }

    func submitUrl() {
        let raw = urlInput
        Task {
            await controller.submitUrl(raw)
        }
    }

    func retry() {
        Task {
            await controller.retry()
        }
    }

    func dismissVersionBanner() {
        Task {
            await controller.dismissVersionBanner()
        }
    }

    /// 「应用内提醒」开关（notice-banner-toggle）：本机持久化，关闭后控制器不再发布横幅。
    var noticeBannerEnabled: Bool {
        get { noticePrefs.value }
        set {
            noticePrefs.set(newValue)
            try? prefsStore.save(NativePrefs(noticeBannerEnabled: newValue))
        }
    }

    /// 视图菜单「搜索聊天」（⌘K）→ 聚焦侧栏搜索框。
    func requestSidebarSearchFocus() {
        sidebarSearchFocusTick &+= 1
    }

    /// 选中某聊天（侧栏点击或横幅点按）：标记已读并联动提醒抑制的当前聊天。
    func selectChatById(_ id: String) {
        sidebar.select(id: id, team: snapshot.team)
        let selection = sidebar.selection
        Task {
            await controller.setCurrentTarget(selection)
        }
    }

    /// 收起提醒横幅（8 秒自动收起或点按后调用）。
    func dismissNotice(id: UInt64) {
        Task {
            await controller.dismissNotice(id: id)
        }
    }

    func saveSettingsUrl(_ raw: String) async -> SettingsSaveOutcome {
        await controller.saveSettingsUrl(raw)
    }

    /// 保存诊断上报端点（校验 + 原子持久化）；返回规范化端点，非法返回 nil 且不改变现状。
    func saveDiagnosticsEndpoint(_ raw: String) -> String? {
        guard let store = diagnosticsStore, let saved = try? store.save(raw) else {
            return nil
        }
        diagnosticsConfigured = true
        configuredDiagnosticsEndpoint = saved
        return saved
    }

    /// 清除诊断上报配置并删除本地待发队列，回到未配置。
    func clearDiagnosticsEndpoint() {
        diagnosticsStore?.clear()
        diagnosticsQueue?.clear()
        diagnosticsConfigured = false
        configuredDiagnosticsEndpoint = nil
    }

    private func applySnapshot(_ next: MacosSessionSnapshot) {
        guard next != snapshot else { return }
        let previousScreen = snapshot.screen
        let previousTeam = snapshot.team
        snapshot = next

        // 侧栏联动（macos-team-sidebar）：主机标识跟随连接地址（换主机清空选中并解除
        // 提醒抑制的当前聊天）；首次见到该主机的团队时打底已读，避免整页未读点。
        let hostMayChange = snapshot.cleanUrl != nil || previousTeam != next.team
        if hostMayChange, sidebar.updateHost(snapshot.cleanUrl) {
            Task {
                await controller.setCurrentTarget(nil)
            }
        }
        if next.team != previousTeam, let team = next.team {
            sidebar.teamDidUpdate(team)
        }

        if next.screen == .authExpired && previousScreen != .authExpired {
            urlInput = ""
        } else if next.screen == .connected && (HarnessUrl.hasToken(urlInput) || urlInput.isEmpty) {
            urlInput = next.cleanUrl ?? ""
        }

        // 错误页出现（含原因变化）时产生按原因分类的错误态报告；同会话同类由运行时去重，
        // 401/403 属会话与配置状态不在此上报。地址已是干净地址，运行时仍会再脱敏一次。
        if case .error(let reason) = next.screen {
            let reasonText = ConnectionCopy.probeReasonTitle(reason)
            let cleanUrl = next.cleanUrl ?? ""
            let runtime = diagnosticsRuntime
            Task {
                await runtime?.recordLoadError(reason: reasonText, redactedUrl: cleanUrl)
            }
        }
    }
}

/// 线程安全的布尔盒（应用内提醒开关）：控制器 actor 经闭包读取，设置窗口在主线程写入。
final class NoticePrefsBox: @unchecked Sendable {
    private let lock = NSLock()
    private var enabled: Bool

    init(_ enabled: Bool) {
        self.enabled = enabled
    }

    var value: Bool {
        lock.withLock { enabled }
    }

    func set(_ newValue: Bool) {
        lock.withLock { enabled = newValue }
    }
}
