import Foundation

/// 图片压缩尺寸换算（photo-compress，与 Kotlin `Outbox.kt` 的 ImageSizing 对齐）：
/// 长边超过上限时等比缩小，否则不处理（不放大）；采样率为解码时降内存用。
public enum ImageSizing: Sendable {
    public static let maxEdge = 2048
    /// 压缩目标 JPEG 质量（与 Android 当时实现对齐）。
    public static let jpegQuality = 0.82

    /// 缩小后的 (宽, 高)；不需要缩小或尺寸无效时返回 nil。
    public static func target(width: Int, height: Int, maxEdge: Int = ImageSizing.maxEdge) -> (width: Int, height: Int)? {
        guard width > 0, height > 0, maxEdge > 0 else { return nil }
        let long = max(width, height)
        guard long > maxEdge else { return nil }
        let scale = Double(maxEdge) / Double(long)
        let w = max(1, Int((Double(width) * scale).rounded()))
        let h = max(1, Int((Double(height) * scale).rounded()))
        return width >= height ? (maxEdge, h) : (w, maxEdge)
    }
}

/// 附件上传（attachments-send）：uploadFileBinary 的 URL 查询参数与应答 receipt
/// （应答形态解码复用 `AttachmentMessages.decodeUpload`）。
public enum AttachmentUpload: Sendable {
    /// POST /api/session/uploadFileBinary?sessionId&name[&mediaType]（octet-stream 正文）。
    public static func urlQuery(baseUrl: String, sessionId: String, name: String, mediaType: String?) -> String {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&=?")
        func enc(_ s: String) -> String {
            s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
        }
        var out = baseUrl.trimmingCharacters(in: .whitespaces)
        if !out.hasSuffix("/") { out += "/" }
        out += "api/session/uploadFileBinary?sessionId=\(enc(sessionId))&name=\(enc(name))"
        if let mediaType {
            out += "&mediaType=\(enc(mediaType))"
        }
        return out
    }
}

/// 「阅读全文」（file-text）应答：{text, whole}（0.3.10 源码核对形态，全量取回后显示）。
public enum FileText: Sendable {
    public struct Result: Equatable, Sendable {
        public let text: String
        public let whole: Bool

        public init(text: String, whole: Bool) {
            self.text = text
            self.whole = whole
        }
    }

    public static func request(fileJson: String) -> String {
        "{\"file\":\(fileJson)}"
    }

    public static func decode(_ value: JsonValue?) -> Result? {
        guard let o = value?.objectValue,
              let text = o.string("text")
        else {
            return nil
        }
        return Result(text: text, whole: o.bool("whole") ?? true)
    }
}
