import Foundation

/// 带 token 地址一次性建立会话结果。
public enum SessionEstablishResult: Equatable, Sendable {
    case ok
    case rejected(status: Int)
    case networkError(ProbeFailureReason)
}

/// 原生建立 dsh 会话（与 `NativeSession.establish` 对齐）：
/// 只对带 token 地址发起一次请求（跟随最多 5 次同源重定向），按当次签发的 `dsh-auth-*` 判定成功并落盘。
public struct SessionEstablisher: Sendable {
    private static let redirectStatusCodes: Set<Int> = [301, 302, 303, 307, 308]

    public let transport: any HttpTransport
    public let cookieStore: SessionCookieStore
    public let maxRedirects: Int

    public init(transport: any HttpTransport, cookieStore: SessionCookieStore, maxRedirects: Int = 5) {
        self.transport = transport
        self.cookieStore = cookieStore
        self.maxRedirects = maxRedirects
    }

    public func establish(tokenUrl: String) async -> SessionEstablishResult {
        let trimmed = tokenUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let expectedOrigin = HarnessUrl.canonicalOrigin(trimmed),
              let hostHeader = HarnessUrl.hostHeader(trimmed)
        else {
            return .networkError(.other)
        }

        // 每次尝试独立收集新签发的 dsh-auth-* cookie，防止陈旧持久化 cookie 掩盖无效 token。
        var collected: [SessionCookies.Cookie] = []
        var currentUrl = trimmed
        var redirects = 0

        while true {
            var headers: [(name: String, value: String)] = [("Host", hostHeader)]
            if !collected.isEmpty {
                headers.append(("Cookie", SessionCookies.header(for: collected)))
            }
            let response: HttpResponse
            do {
                response = try await transport.send(
                    HttpRequest(method: "GET", url: currentUrl, headers: headers)
                )
            } catch {
                return .networkError(NetErrorClassifier.classify(error))
            }

            for header in response.headers where header.name.lowercased() == "set-cookie" {
                for single in SessionCookies.splitSetCookieHeader(header.value) {
                    if let parsed = SessionCookies.parseSetCookie(single),
                       SessionCookies.isAuthCookie(parsed.name),
                       !parsed.value.isEmpty {
                        collected.removeAll { $0.name == parsed.name }
                        collected.append(parsed)
                    }
                }
            }

            if Self.redirectStatusCodes.contains(response.statusCode),
               redirects < maxRedirects,
               let location = response.header("Location"),
               !location.isEmpty,
               let nextUrl = HttpLocation.resolve(base: currentUrl, location: location),
               HarnessUrl.canonicalOrigin(nextUrl) == expectedOrigin {
                currentUrl = nextUrl
                redirects += 1
                continue
            }

            if !collected.isEmpty {
                do {
                    try cookieStore.replaceCookies(collected, forUrl: trimmed)
                } catch {
                    return .networkError(.other)
                }
                return .ok
            }
            return .rejected(status: response.statusCode)
        }
    }
}
