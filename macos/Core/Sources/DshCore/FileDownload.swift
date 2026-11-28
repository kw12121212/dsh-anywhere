import Foundation

/// 文件分块下载（bot-chat 文件链接打开，与 `FileDownload.kt` 对齐）：DS-Bot 0.3.10
/// `file-download` 的请求体、分块解码与拼接校验。实测（FINDINGS 第 17 节，夹具
/// file-download-chunk-*.json）：Bot 交付的文件以 bot 条目 files[] 自带的 {sessionId, seq,
/// index} 定位，每块最多 512 KiB，应答 {name, mime, bytes(base64), size, version, nextOffset,
/// done}；续块带上首块的 version，文件变了或已不可用回 bot/not-found。
public enum FileDownload: Sendable {
    public static let chunkBytes = 512 * 1024

    /// Bot 交付文件（deliverables/presented）的分块请求。
    public static func presented(sessionId: String, seq: Int64, index: Int, offset: Int64, version: String?) -> String {
        var out = "{\"sessionId\":\(JsonString.of(sessionId))"
        out += ",\"seq\":\(seq),\"index\":\(index),\"offset\":\(offset)"
        if let version {
            out += ",\"version\":\(JsonString.of(version))"
        }
        return out + "}"
    }

    /// 文件附件（attachmentId）的分块请求；附件块以 attachmentId 为版本，无需回传。
    public static func attachment(sessionId: String, attachmentId: String, offset: Int64) -> String {
        "{\"sessionId\":\(JsonString.of(sessionId)),\"attachmentId\":\(JsonString.of(attachmentId)),\"offset\":\(offset)}"
    }

    public struct Chunk: Equatable, Sendable {
        public let name: String
        public let mime: String?
        public let bytes: Data
        public let size: Int64
        public let version: String?
        public let nextOffset: Int64
        public let done: Bool
    }

    /// 解码一块；字段缺失或 base64 非法为 nil。
    public static func decode(_ value: JsonValue?) -> Chunk? {
        guard let o = value?.objectValue,
              let bytesRaw = o["bytes"]?.stringValue,
              let bytes = Data(base64Encoded: bytesRaw),
              let size = long(o, "size"),
              let nextOffset = long(o, "nextOffset")
        else {
            return nil
        }
        return Chunk(
            name: o.string("name") ?? "file",
            mime: o.string("mime"),
            bytes: bytes,
            size: size,
            version: o.string("version"),
            nextOffset: nextOffset,
            done: o.bool("done") ?? false
        )
    }

    /// 拼接校验：从 offset 0 起逐块接收，每块必须紧接上一块（解码长度 = nextOffset - 期望
    /// offset）、总量与首块一致、version 与首块一致；超过 maxBytes 中止。通过的块由调用方
    /// 写盘（不在内存里累积）。
    public final class Assembler {
        public enum Failure: Equatable, Sendable {
            case versionChanged
            case outOfOrder
            case tooLarge
            case malformed
        }

        public enum Step: Equatable {
            /// 继续请求下一块。
            case next(offset: Int64, version: String?)
            case done
            case fail(Failure)
        }

        public private(set) var offset: Int64 = 0
        public private(set) var size: Int64 = -1
        public private(set) var version: String?
        public private(set) var name: String?
        private var failed = false
        private let maxBytes: Int64

        public init(maxBytes: Int64 = .max) {
            self.maxBytes = maxBytes
        }

        public func accept(_ chunk: Chunk) -> Step {
            if failed { return .fail(.malformed) }
            let first = size < 0
            let bad: Failure?
            if chunk.size < 0 || chunk.nextOffset < offset {
                bad = .malformed
            } else if !first && chunk.size != size {
                bad = .versionChanged
            } else if !first && chunk.version != version {
                bad = .versionChanged
            } else if Int64(chunk.bytes.count) != chunk.nextOffset - offset {
                bad = .outOfOrder
            } else if chunk.size > maxBytes {
                bad = .tooLarge
            } else if chunk.nextOffset > chunk.size {
                bad = .malformed
            } else if chunk.bytes.isEmpty && chunk.nextOffset < chunk.size {
                bad = .malformed
            } else if chunk.done && chunk.nextOffset != chunk.size {
                // 声称完结却没到总量：不把不完整的文件当作完成交出去
                bad = .malformed
            } else {
                bad = nil
            }
            if let bad {
                failed = true
                return .fail(bad)
            }
            if first {
                size = chunk.size
                version = chunk.version
                name = chunk.name
            }
            offset = chunk.nextOffset
            return offset == size ? .done : .next(offset: offset, version: version)
        }
    }

    /// 缓存文件名：只取最后一段（去掉 / 与 \ 前的路径）、去控制字符与 Windows 保留字符、
    /// 去掉 ".." 片段，以 "." 开头的加前缀，限 120 字符且保留扩展名；空回落 "file"。
    public static func safeName(_ name: String?) -> String {
        let last = (name ?? "")
            .components(separatedBy: "/").last ?? ""
            .components(separatedBy: "\\").last ?? ""
        let bidi = Set<Character>("\u{200E}\u{200F}\u{202A}\u{202B}\u{202C}\u{202D}\u{202E}\u{2066}\u{2067}\u{2068}\u{2069}")
        var filtered = String(last.filter { ch in
            guard let scalar = ch.unicodeScalars.first else { return false }
            return scalar.value >= 0x20
                && scalar.value != 0x7F
                && !"<>:\"|?*".contains(ch)
                && !bidi.contains(ch)
        })
        while filtered.contains("..") {
            filtered = filtered.replacingOccurrences(of: "..", with: "_")
        }
        var s = filtered.trimmingCharacters(in: .whitespaces)
        if s.isEmpty || s == "." || s == "_" { return "file" }
        if s.hasPrefix(".") { s = "file" + s }
        if s.count > 120 {
            let parts = s.components(separatedBy: ".")
            let ext = (parts.count > 1 && !(parts.last ?? "").isEmpty && (parts.last ?? "").count <= 10) ? parts.last : nil
            if let ext {
                s = String(s.prefix(120 - ext.count - 1)) + "." + ext
            } else {
                s = String(s.prefix(120))
            }
        }
        return s
    }

    private static let textExts: Set<String> = ["md", "markdown", "txt", "log", "csv", "tsv", "json", "yaml", "yml", "xml", "html", "htm"]

    /// 是否可作为文本显示（「阅读全文」入口）。
    public static func isText(name: String?, mime: String? = nil) -> Bool {
        if let mime, mime.hasPrefix("text/") { return true }
        guard let name else { return false }
        let ext = (name.components(separatedBy: ".").last ?? "").lowercased()
        return textExts.contains(ext)
    }

    private static func long(_ o: JsonObject, _ key: String) -> Int64? {
        guard let raw = o[key]?.numberRaw else { return nil }
        return Int64(raw) ?? Double(raw).map(Int64.init)
    }
}
