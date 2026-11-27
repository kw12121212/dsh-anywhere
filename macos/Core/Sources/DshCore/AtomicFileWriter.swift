import Foundation

/// 目录内单文件的 `0600` 原子写（tech/state-persistence）：先建 `0700` 目录，写临时文件
/// （`0600`）后经 replaceItemAt/moveItem 原子落位，任何路径下不留临时残留。供
/// `WindowStateStore`、`ReadStateStore` 与本机偏好存储共用同一持久化模式。
enum AtomicFileWriter {
    static func write(data: Data, to fileURL: URL, in directory: URL, tmpPrefix: String) throws {
        let fm = FileManager.default
        try fm.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: NSNumber(value: 0o700)]
        )
        let suffix = String(UInt64.random(in: 0...UInt64.max), radix: 16)
        let tmpURL = directory.appendingPathComponent("\(tmpPrefix)\(suffix).tmp")
        defer {
            try? fm.removeItem(at: tmpURL)
        }
        let created = fm.createFile(
            atPath: tmpURL.path,
            contents: data,
            attributes: [.posixPermissions: NSNumber(value: 0o600)]
        )
        if !created {
            try data.write(to: tmpURL, options: .atomic)
        }
        try? fm.setAttributes([.posixPermissions: NSNumber(value: 0o600)], ofItemAtPath: tmpURL.path)

        if fm.fileExists(atPath: fileURL.path) {
            _ = try fm.replaceItemAt(fileURL, withItemAt: tmpURL)
        } else {
            try fm.moveItem(at: tmpURL, to: fileURL)
        }
        try fm.setAttributes([.posixPermissions: NSNumber(value: 0o600)], ofItemAtPath: fileURL.path)
    }
}
