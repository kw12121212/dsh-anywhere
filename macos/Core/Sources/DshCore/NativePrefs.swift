import Foundation

/// macos/App 的本机界面偏好（目录注入的 prefs.json）：目前只含「应用内提醒」开关
///（notice-banner-toggle，默认开启）。解码端把文件缺失、损坏与未知键一律按默认处理。
public struct NativePrefs: Equatable, Sendable {
    public var noticeBannerEnabled: Bool
    /// 上次打开的聊天标识（last-chat-prefetch，只记 id 不存内容；nil 为未记）。
    public var lastChatId: String?

    public init(noticeBannerEnabled: Bool = true, lastChatId: String? = nil) {
        self.noticeBannerEnabled = noticeBannerEnabled
        self.lastChatId = lastChatId
    }

    static let fileName = "prefs.json"
    static let tmpPrefix = ".prefs.json."

    /// 解析 prefs.json：语法错误或键类型不符取默认；缺键取该键默认。
    public static func decode(_ text: String?) -> NativePrefs {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let root = Json.parse(text)?.objectValue
        else {
            return NativePrefs()
        }
        return NativePrefs(
            noticeBannerEnabled: root.bool("noticeBannerEnabled") ?? true,
            lastChatId: root.nonEmptyString("lastChatId")
        )
    }

    public func encode() -> String {
        var out = "{\n  \"noticeBannerEnabled\": \(noticeBannerEnabled)"
        if let lastChatId {
            out += ",\n  \"lastChatId\": \(JsonString.of(lastChatId))"
        }
        return out + "\n}\n"
    }
}

/// 基于指定目录的 prefs.json 读写与 `0600` 原子持久化（目录由 App 层注入）。
public final class NativePrefsStore: Sendable {
    public let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    public var fileURL: URL {
        directory.appendingPathComponent(NativePrefs.fileName)
    }

    public func load() -> NativePrefs? {
        guard let data = try? Data(contentsOf: fileURL),
              let text = String(data: data, encoding: .utf8)
        else {
            return nil
        }
        return NativePrefs.decode(text)
    }

    public func save(_ prefs: NativePrefs) throws {
        try AtomicFileWriter.write(
            data: Data(prefs.encode().utf8),
            to: fileURL,
            in: directory,
            tmpPrefix: NativePrefs.tmpPrefix
        )
    }
}
