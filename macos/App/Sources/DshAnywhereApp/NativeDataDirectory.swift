import Foundation

/// 原生客户端私有数据目录（tech/macos-client 的 macos-app-network-and-storage-boundary）：
/// `~/Library/Application Support/DSH Anywhere Native/`，与 Electron 的 `DSH Anywhere/`
/// 目录隔离；会话 cookie、配置、诊断与窗口状态等原生持久化文件都位于其下。
enum NativeDataDirectory {
    static var url: URL {
        let baseSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(
                "Library/Application Support", isDirectory: true
            )
        return baseSupport.appendingPathComponent("DSH Anywhere Native", isDirectory: true)
    }
}
