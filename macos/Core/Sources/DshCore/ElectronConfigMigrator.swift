import Foundation

/// 从原版 Electron `DSH Anywhere` 配置目录只读迁移 `harnessURL` 与合法 `https` 诊断端点（绝不修改源目录、不迁移 cookie）。
public enum ElectronConfigMigrator: Sendable {
    public struct MigrationResult: Equatable, Sendable {
        public let migratedHarnessUrl: String?
        public let migratedDiagnosticsEndpoint: String?

        public var didMigrateHarnessUrl: Bool {
            migratedHarnessUrl != nil
        }

        public var didMigrateAnything: Bool {
            migratedHarnessUrl != nil || migratedDiagnosticsEndpoint != nil
        }

        public init(migratedHarnessUrl: String? = nil, migratedDiagnosticsEndpoint: String? = nil) {
            self.migratedHarnessUrl = migratedHarnessUrl
            self.migratedDiagnosticsEndpoint = migratedDiagnosticsEndpoint
        }
    }

    /// 默认 Electron 候选目录：优先打包产物目录 `DSH Anywhere`，兼容开发/package name 目录 `dsh-anywhere`。
    public static func defaultElectronDirectories(applicationSupportDirectory: URL) -> [URL] {
        [
            applicationSupportDirectory.appendingPathComponent("DSH Anywhere", isDirectory: true),
            applicationSupportDirectory.appendingPathComponent("dsh-anywhere", isDirectory: true),
        ]
    }

    /// 仅当原生 `config.json` 为 `.missing` 时执行一次性只读迁移；已有有效或损坏原生配置时跳过。
    @discardableResult
    public static func migrateIfNeeded(
        nativeDirectory: URL,
        electronDirectories: [URL]
    ) -> MigrationResult {
        let nativeHarnessStore = HarnessConfigStore(directory: nativeDirectory)
        guard case .missing = nativeHarnessStore.load() else {
            return MigrationResult()
        }

        var matchedDir: URL?
        var candidateHarnessUrl: String?
        for dir in electronDirectories {
            if case .ok(let cleanUrl) = HarnessConfigStore(directory: dir).load() {
                matchedDir = dir
                candidateHarnessUrl = cleanUrl
                break
            }
        }

        var searchOrder: [URL] = []
        var seenPaths: Set<String> = []
        if let matchedDir {
            let path = matchedDir.standardizedFileURL.path
            seenPaths.insert(path)
            searchOrder.append(matchedDir)
        }
        for dir in electronDirectories {
            let path = dir.standardizedFileURL.path
            if seenPaths.insert(path).inserted {
                searchOrder.append(dir)
            }
        }

        var savedHarness: String?
        if let candidateHarnessUrl, (try? nativeHarnessStore.save(candidateHarnessUrl)) != nil {
            savedHarness = candidateHarnessUrl
        }

        var savedDiagnostics: String?
        let nativeDiagStore = DiagnosticsEndpointStore(directory: nativeDirectory)
        if !nativeDiagStore.exists() {
            for dir in searchOrder {
                if case .ok(let endpoint) = DiagnosticsEndpointStore(directory: dir).load() {
                    if (try? nativeDiagStore.save(endpoint)) != nil {
                        savedDiagnostics = endpoint
                    }
                    break
                }
            }
        }

        return MigrationResult(
            migratedHarnessUrl: savedHarness,
            migratedDiagnosticsEndpoint: savedDiagnostics
        )
    }
}
