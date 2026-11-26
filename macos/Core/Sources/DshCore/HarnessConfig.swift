import Foundation

/// harness 配置的读取、校验与原子持久化（与桌面 `config.json` 与 `HarnessConfig.kt` 对齐）。
public enum HarnessConfig: Sendable {
    public enum Load: Equatable, Sendable {
        case missing
        case invalid
        case ok(String)
    }

    /// 目录中不应残留的临时文件前缀。
    public static let tmpPrefix = ".config.json."
    public static let fileName = "config.json"

    /// 纯文本解析（`nil` 表示文件缺失）。
    public static func parseContent(_ content: String?) -> Load {
        guard let content else { return .missing }
        guard let parsed = Json.parse(content),
              let obj = parsed.objectValue,
              let rawUrl = obj.string("harnessURL")
        else {
            return .invalid
        }
        do {
            return .ok(try HarnessUrl.normalize(rawUrl))
        } catch {
            return .invalid
        }
    }

    /// 序列化为与桌面端 `JSON.stringify(obj, null, 2) + "\n"` 一致的字节形态。
    public static func serialize(cleanUrl: String) -> String {
        "{\n  \"harnessURL\": \(JsonString.of(cleanUrl))\n}\n"
    }
}

/// 基于指定目录的 `config.json` 读写与原子保存（目录由 App 层注入）。
public final class HarnessConfigStore: Sendable {
    public let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    private var fileURL: URL {
        directory.appendingPathComponent(HarnessConfig.fileName)
    }

    public func load() -> HarnessConfig.Load {
        let fm = FileManager.default
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: fileURL.path, isDirectory: &isDir), !isDir.boolValue else {
            return .missing
        }
        guard let data = try? Data(contentsOf: fileURL),
              let text = String(data: data, encoding: .utf8)
        else {
            return .invalid
        }
        return HarnessConfig.parseContent(text)
    }

    @discardableResult
    public func save(_ rawUrl: String) throws -> String {
        let clean = try HarnessUrl.normalize(rawUrl)
        let fm = FileManager.default
        try fm.createDirectory(at: directory, withIntermediateDirectories: true)
        let suffix = String(UInt64.random(in: 0...UInt64.max), radix: 16)
        let tmpURL = directory.appendingPathComponent("\(HarnessConfig.tmpPrefix)\(suffix).tmp")
        defer {
            try? fm.removeItem(at: tmpURL)
        }
        let payload = HarnessConfig.serialize(cleanUrl: clean)
        try Data(payload.utf8).write(to: tmpURL, options: .atomic)
        if fm.fileExists(atPath: fileURL.path) {
            _ = try fm.replaceItemAt(fileURL, withItemAt: tmpURL)
        } else {
            try fm.moveItem(at: tmpURL, to: fileURL)
        }
        return clean
    }
}
