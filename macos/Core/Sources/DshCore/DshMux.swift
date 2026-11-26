import Foundation

/// 抽象 WebSocket 文本会话接口（由 `macos/App` 注入传输实现；`DshCore` 不含 IO）。
public protocol MuxTextSession {
    func sendText(_ text: String) throws
    func receiveText() throws -> String?
    func close()
}

/// dsh `/api/remote.mux` 流多路复用 RPC 封装（与 `DshMux.kt` 对齐）。
public final class DshMux {
    public enum Result: Equatable, Sendable {
        case ok(String?)
        case err(code: String, message: String)
    }

    public enum DownlinkFrame: Equatable, Sendable {
        case item(streamId: String, valueJson: String?)
        case end(streamId: String)
        case error(streamId: String, code: String, message: String)
        case ignored
    }

    private let session: any MuxTextSession
    private var nextStreamId: Int = 1

    public init(session: any MuxTextSession) {
        self.session = session
    }

    public static func encodeOpen(streamId: String, endpoint: String, requestJson: String) -> String {
        "{\"type\":\"open\",\"streamId\":\(JsonString.of(streamId))," +
            "\"endpoint\":\(JsonString.of(endpoint)),\"payload\":{\"args\":{\"request\":\(requestJson)}}}"
    }

    public static func encodeCancel(streamId: String) -> String {
        "{\"type\":\"cancel\",\"streamId\":\(JsonString.of(streamId))}"
    }

    public static func decodeFrame(_ text: String) -> DownlinkFrame? {
        guard let obj = Json.parse(text)?.objectValue else { return nil }
        guard let type = obj.string("type") else { return .ignored }
        guard let streamId = obj.string("streamId") else { return .ignored }
        switch type {
        case "item":
            let valueJson = obj["value"].map(Json.encode)
            return .item(streamId: streamId, valueJson: valueJson)
        case "end":
            return .end(streamId: streamId)
        case "error":
            let err = obj.object("error")
            let code = err?.string("code") ?? "gateway/internal"
            let message = err?.string("message") ?? ""
            return .error(streamId: streamId, code: code, message: message)
        default:
            return .ignored
        }
    }

    public func call(endpoint: String, requestJson: String) -> Result {
        let streamId = "a\(nextStreamId)"
        nextStreamId += 1
        do {
            try session.sendText(Self.encodeOpen(streamId: streamId, endpoint: endpoint, requestJson: requestJson))
        } catch {
            return .err(code: "gateway/closed", message: "websocket send failed")
        }
        var lastValue: String?
        while true {
            let text: String?
            do {
                text = try session.receiveText()
            } catch {
                return .err(code: "gateway/closed", message: "websocket closed before reply")
            }
            guard let text else {
                return .err(code: "gateway/closed", message: "websocket closed before reply")
            }
            guard let frame = Self.decodeFrame(text) else {
                return .err(code: "gateway/protocol", message: "non-object frame on mux")
            }
            switch frame {
            case .item(let sid, let valueJson) where sid == streamId:
                lastValue = valueJson
            case .end(let sid) where sid == streamId:
                return .ok(lastValue)
            case .error(let sid, let code, let message) where sid == streamId:
                return .err(code: code, message: message)
            default:
                continue
            }
        }
    }

    public func close() {
        session.close()
    }
}

/// 附件链路的纯构造与解码（与 `AttachmentMessages` in `DshMux.kt` 对齐）。
public enum AttachmentMessages: Sendable {
    public struct UploadResult: Equatable, Sendable {
        public let receiptId: String
        public let attachmentId: String
        public let name: String?
        public let bytes: Int64?

        public init(receiptId: String, attachmentId: String, name: String?, bytes: Int64?) {
            self.receiptId = receiptId
            self.attachmentId = attachmentId
            self.name = name
            self.bytes = bytes
        }
    }

    /// HTTP unary RPC `client-request` 信封（`POST /api/<channel>/<method>`）。
    public static func clientRequest(rpcId: String, method: String, requestJson: String) -> String {
        "{\"type\":\"client-request\",\"rpcId\":\(JsonString.of(rpcId)),\"method\":\(JsonString.of(method))," +
            "\"payload\":{\"args\":{\"request\":\(requestJson)}}}"
    }

    /// 解析 `server-response` 信封。
    public static func parseServerResponse(_ bodyText: String) -> DshMux.Result? {
        guard let root = Json.parse(bodyText)?.objectValue,
              root.string("type") == "server-response",
              let result = root.object("result")
        else {
            return nil
        }
        if result.bool("ok") == true {
            return .ok(result["value"].map(Json.encode))
        }
        let err = result.object("error")
        return .err(
            code: err?.string("code") ?? "gateway/internal",
            message: err?.string("message") ?? ""
        )
    }

    /// 解析 `uploadFileBinary` 应答（兼容普通文件 `file` 键与标准化图片 `image` 键）。
    public static func decodeUpload(_ value: JsonValue) -> UploadResult? {
        guard let obj = value.objectValue,
              let receiptId = obj.string("receiptId"),
              let file = (obj.object("file") ?? obj.object("image")),
              let attachmentId = file.string("attachmentId")
        else {
            return nil
        }
        return UploadResult(
            receiptId: receiptId,
            attachmentId: attachmentId,
            name: file.string("name"),
            bytes: file.int64("bytes")
        )
    }

    public static func promptContent(receiptIds: [String], text: String) -> String {
        if receiptIds.isEmpty && text.isEmpty { return "" }
        var blocks = receiptIds.map { "{\"type\":\"file\",\"receiptId\":\(JsonString.of($0))}" }
        if !text.isEmpty {
            blocks.append("{\"type\":\"text\",\"text\":\(JsonString.of(text))}")
        }
        return blocks.joined(separator: ",")
    }

    public static func promptRequest(
        requestId: String,
        sessionId: String,
        receiptIds: [String],
        text: String
    ) -> String {
        "{\"requestId\":\(JsonString.of(requestId)),\"sessionId\":\(JsonString.of(sessionId))," +
            "\"mode\":\"queue\",\"content\":[\(promptContent(receiptIds: receiptIds, text: text))]}"
    }

    public static func attachmentRequest(sessionId: String, attachmentJson: String) -> String {
        "{\"sessionId\":\(JsonString.of(sessionId)),\"attachment\":\(attachmentJson)}"
    }
}
