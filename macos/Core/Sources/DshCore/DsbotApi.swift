import Foundation

/// DS-Bot API 信封构造、路径拼接与结果解码（与 `DsbotApi.kt` 和 `tech/dsbot-api` 对齐）。
public enum DsbotApi: Sendable {
    public enum Reason: Equatable, Sendable {
        case badInput
        case refused
        case stale
        case notFound
        case readOnly
        case featureOff
        case serverError
        case network
        case authExpired
        case forbiddenHost
    }

    public enum Result: Equatable, Sendable {
        case ok(JsonValue)
        case botError(code: String, message: String)
        case failure(status: Int?, reason: Reason)
    }

    /// 构造 POST `/api/bot` 请求信封 JSON（`payloadJson` 为空串时传 `null`）。
    public static func buildRequest(endpoint: String, payloadJson: String = "{}") -> String {
        let payload = payloadJson.isEmpty ? "null" : payloadJson
        return "{\"endpoint\":\(JsonString.of(endpoint)),\"payload\":\(payload)}"
    }

    /// 基准地址 + 子路径统一拼接：先去除基准地址尾部斜杠，避免 `//api/bot` 触发 SPA 静态兜底（POST → 405）。
    public static func url(base: String, path: String) -> String {
        var trimmed = base
        while trimmed.hasSuffix("/") {
            trimmed.removeLast()
        }
        return trimmed + path
    }

    /// 检测 405 SPA 静态兜底缺陷（POST 请求未命中 `/api/` 前缀路由）。
    public static func isSpaFallback(status: Int, allowHeader: String?) -> Bool {
        guard status == 405, let allowHeader else { return false }
        let methods = allowHeader.split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
        return methods.contains("GET") && methods.contains("HEAD")
    }

    /// 解码 HTTP 响应：401 → `.authExpired`、403 → `.forbiddenHost`、200 合法 JSON → `.ok`/`.botError`。
    public static func parseResponse(status: Int, bodyText: String) -> Result {
        if status == 401 { return .failure(status: 401, reason: .authExpired) }
        if status == 403 { return .failure(status: 403, reason: .forbiddenHost) }
        if status != 200 { return .failure(status: status, reason: .network) }
        guard let root = Json.parse(bodyText), let obj = root.objectValue else {
            return .failure(status: 200, reason: .serverError)
        }
        if obj.bool("ok") == true {
            return .ok(obj["value"] ?? .null)
        }
        if let err = obj.object("error") {
            let code = err.string("code") ?? "bot/error"
            let message = err.string("message") ?? ""
            return .botError(code: code, message: message)
        }
        return .failure(status: 200, reason: .serverError)
    }

    /// `bot/` 前缀错误码 → 用户可读原因；未登记错误码缺省归 `.refused`。
    public static func reason(of code: String) -> Reason {
        switch code {
        case "bot/invalid": return .badInput
        case "bot/stale": return .stale
        case "bot/not-found": return .notFound
        case "bot/read-only": return .readOnly
        case "bot/browser-off": return .featureOff
        case "bot/internal": return .serverError
        default: return .refused
        }
    }
}

/// 基于注入 `HttpTransport` 的无 IO DS-Bot API 客户端（供 `macos/App` 注入 `URLSession`）。
public struct DsbotTransportClient: Sendable {
    public let baseUrl: String
    public let transport: any HttpTransport
    public let defaultHeaders: [(name: String, value: String)]

    public init(
        baseUrl: String,
        transport: any HttpTransport,
        defaultHeaders: [(name: String, value: String)] = []
    ) {
        self.baseUrl = baseUrl
        self.transport = transport
        self.defaultHeaders = defaultHeaders
    }

    public func postBot(
        endpoint: String,
        payloadJson: String = "{}",
        extraHeaders: [(name: String, value: String)] = []
    ) async -> DsbotApi.Result {
        let bodyText = DsbotApi.buildRequest(endpoint: endpoint, payloadJson: payloadJson)
        let headers = defaultHeaders + extraHeaders + [
            ("Content-Type", "application/json"),
            ("Accept", "application/json"),
        ]
        let req = HttpRequest(
            method: "POST",
            url: DsbotApi.url(base: baseUrl, path: "/api/bot"),
            headers: headers,
            body: Data(bodyText.utf8)
        )
        do {
            let resp = try await transport.send(req)
            if DsbotApi.isSpaFallback(status: resp.statusCode, allowHeader: resp.header("Allow")) {
                return .failure(status: resp.statusCode, reason: .serverError)
            }
            return DsbotApi.parseResponse(status: resp.statusCode, bodyText: resp.bodyText)
        } catch {
            return .failure(status: nil, reason: .network)
        }
    }
}
