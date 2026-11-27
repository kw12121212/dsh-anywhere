import DshCore
import Foundation
import Observation

@MainActor
@Observable
final class AppSessionModel {
    var snapshot = MacosSessionSnapshot()
    var urlInput: String = ""

    private var controller: MacosSessionController!
    private var lifecycleMonitor: AppLifecycleMonitor?
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
        Task {
            await controller.start()
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

    private func applySnapshot(_ next: MacosSessionSnapshot) {
        guard next != snapshot else { return }
        let previousScreen = snapshot.screen
        snapshot = next

        if next.screen == .authExpired && previousScreen != .authExpired {
            urlInput = ""
        } else if next.screen == .connected && (HarnessUrl.hasToken(urlInput) || urlInput.isEmpty) {
            urlInput = next.cleanUrl ?? ""
        }
    }
}
