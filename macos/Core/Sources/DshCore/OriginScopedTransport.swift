import Foundation

/// 源隔离与请求头安全策略异常。
public enum OriginScopeError: Error, Equatable, Sendable {
    case invalidUrl
    case crossOriginBlocked
}

/// 请求头策略（遵循 `tech/dsbot-api` `auth-cookie-and-host`）：限定同源、注入 `Host`/`Cookie`、剥离跨站标记头。
public enum OriginHeaderPolicy: Sendable {
    private static let forbiddenExact: Set<String> = ["origin", "referer", "host", "cookie"]

    public static func sanitize(
        url: String,
        allowedBaseUrl: String,
        cookieHeader: String?,
        headers: [(name: String, value: String)] = []
    ) throws -> [(name: String, value: String)] {
        guard let targetOrigin = HarnessUrl.canonicalOrigin(url),
              let allowedOrigin = HarnessUrl.canonicalOrigin(allowedBaseUrl),
              let host = HarnessUrl.hostHeader(allowedBaseUrl)
        else {
            throw OriginScopeError.invalidUrl
        }
        guard targetOrigin == allowedOrigin else {
            throw OriginScopeError.crossOriginBlocked
        }

        var out: [(name: String, value: String)] = [("Host", host)]
        if let cookieHeader, !cookieHeader.isEmpty {
            out.append(("Cookie", cookieHeader))
        }
        for header in headers {
            let lower = header.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if forbiddenExact.contains(lower) || lower.hasPrefix("sec-fetch-") {
                continue
            }
            out.append(header)
        }
        return out
    }
}

/// 绑定到已保存 harness canonical origin 的 `HttpTransport` 装饰器。
public final class OriginScopedHttpTransport: HttpTransport, Sendable {
    public let baseUrl: String
    public let transport: any HttpTransport
    public let cookieStore: SessionCookieStore

    public init(baseUrl: String, transport: any HttpTransport, cookieStore: SessionCookieStore) {
        self.baseUrl = baseUrl
        self.transport = transport
        self.cookieStore = cookieStore
    }

    public func send(_ request: HttpRequest) async throws -> HttpResponse {
        let sanitized = try OriginHeaderPolicy.sanitize(
            url: request.url,
            allowedBaseUrl: baseUrl,
            cookieHeader: cookieStore.cookieHeader(forUrl: baseUrl),
            headers: request.headers
        )
        return try await transport.send(
            HttpRequest(
                method: request.method,
                url: request.url,
                headers: sanitized,
                body: request.body
            )
        )
    }
}
