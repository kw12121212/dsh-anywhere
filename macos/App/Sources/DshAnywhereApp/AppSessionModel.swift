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

    private var controller: MacosSessionController!
    private var lifecycleMonitor: AppLifecycleMonitor?
    private var diagnosticsRuntime: DiagnosticsRuntime?
    private var diagnosticsStore: DiagnosticsEndpointStore?
    private var diagnosticsQueue: DiagnosticsQueue?
    private var started = false

    init() {
        let baseSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support", isDirectory: true)
        let dataDir = baseSupport.appendingPathComponent("DSH Anywhere Native", isDirectory: true)
        let electronDirs = ElectronConfigMigrator.defaultElectronDirectories(applicationSupportDirectory: baseSupport)
        let configStore = HarnessConfigStore(directory: dataDir)
        let cookieStore = SessionCookieStore(directory: dataDir)
        let probeTransport = UrlSessionHttpTransport(requestTimeout: 10, resourceTimeout: 10)
        let apiTransport = UrlSessionHttpTransport(requestTimeout: 30, resourceTimeout: 60)
        let sseStream = UrlSessionSseStream()

        self.controller = MacosSessionController(
            configStore: configStore,
            cookieStore: cookieStore,
            probeTransport: probeTransport,
            apiTransport: apiTransport,
            sseStream: sseStream,
            electronConfigDirectories: electronDirs,
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
        snapshot = next

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
