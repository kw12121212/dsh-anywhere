import Foundation

/// 纯 Foundation 传输请求描述（不含网络 IO；由 `macos/App` 注入 `URLSession` 实现）。
public struct HttpRequest: Equatable, Sendable {
    public let method: String
    public let url: String
    public let headers: [(name: String, value: String)]
    public let body: Data?

    public init(
        method: String,
        url: String,
        headers: [(name: String, value: String)] = [],
        body: Data? = nil
    ) {
        self.method = method
        self.url = url
        self.headers = headers
        self.body = body
    }

    public static func == (lhs: HttpRequest, rhs: HttpRequest) -> Bool {
        lhs.method == rhs.method &&
            lhs.url == rhs.url &&
            lhs.body == rhs.body &&
            lhs.headers.count == rhs.headers.count &&
            zip(lhs.headers, rhs.headers).allSatisfy { $0.name == $1.name && $0.value == $1.value }
    }
}

/// 纯 Foundation HTTP 响应描述。
public struct HttpResponse: Equatable, Sendable {
    public let statusCode: Int
    public let headers: [(name: String, value: String)]
    public let body: Data

    public init(statusCode: Int, headers: [(name: String, value: String)] = [], body: Data = Data()) {
        self.statusCode = statusCode
        self.headers = headers
        self.body = body
    }

    public var bodyText: String {
        String(decoding: body, as: UTF8.self)
    }

    public func header(_ name: String) -> String? {
        let needle = name.lowercased()
        return headers.first { $0.name.lowercased() == needle }?.value
    }

    public static func == (lhs: HttpResponse, rhs: HttpResponse) -> Bool {
        lhs.statusCode == rhs.statusCode &&
            lhs.body == rhs.body &&
            lhs.headers.count == rhs.headers.count &&
            zip(lhs.headers, rhs.headers).allSatisfy { $0.name == $1.name && $0.value == $1.value }
    }
}

/// HTTP 传输注入协议（`macos/App` 通过 `URLSession` 实现并注入）。
public protocol HttpTransport: Sendable {
    func send(_ request: HttpRequest) async throws -> HttpResponse
}

/// SSE 事件流注入协议（逐行或逐块输出原始 UTF-8 文本）。
public protocol SseStream: Sendable {
    func open(url: String, headers: [(name: String, value: String)]) async throws -> AsyncThrowingStream<String, Error>
}
