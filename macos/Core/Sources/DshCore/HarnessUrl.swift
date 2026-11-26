import Foundation

/// harness URL 校验、token 剥离、脱敏与 tailnet 主机判定（与 `HarnessUrl.kt`、`TailnetRouting.kt` 与 `testdata/harness-url-vectors.json` 对齐）。
public enum HarnessUrl: Sendable {
    public enum ErrorCategory: String, Equatable, Sendable {
        case empty
        case malformed
        case notAbsolute
        case unsupportedScheme
    }

    /// 错误值不包含原始输入或 token（满足 `security-secrets` 的 `no-input-echo-errors`）。
    public struct ValidationError: Error, Equatable, Sendable, CustomStringConvertible {
        public let category: ErrorCategory

        public init(_ category: ErrorCategory) {
            self.category = category
        }

        public var description: String { category.rawValue }
    }

    private static let tokenParam = "token"

    /// 校验用户输入并返回去掉 `token` 参数的干净地址；只接受带主机名的 `http`/`https` 绝对地址。
    public static func normalize(_ raw: String) throws -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            throw ValidationError(.empty)
        }
        guard let scheme = leadingScheme(in: trimmed)?.lowercased() else {
            throw ValidationError(.notAbsolute)
        }
        if scheme != "http" && scheme != "https" {
            throw ValidationError(.unsupportedScheme)
        }
        if hasInvalidPercentEscape(trimmed) {
            throw ValidationError(.malformed)
        }
        guard let parts = parseHttpParts(trimmed, expectedScheme: scheme) else {
            throw ValidationError(.malformed)
        }
        let authority = buildAuthority(
            scheme: parts.scheme,
            userInfo: parts.userInfo,
            host: parts.host,
            port: parts.port
        )
        let path = parts.path.isEmpty ? "/" : parts.path
        let query = stripToken(parts.query)
        var out = "\(parts.scheme)://\(authority)\(path)"
        if let query {
            out += "?\(query)"
        }
        if let fragment = parts.fragment {
            out += "#\(fragment)"
        }
        return out
    }

    /// 校验并返回错误类别；合法返回 `nil`。
    public static func errorCategory(_ raw: String) -> ErrorCategory? {
        do {
            _ = try normalize(raw)
            return nil
        } catch let err as ValidationError {
            return err.category
        } catch {
            return .malformed
        }
    }

    /// 判断地址的查询参数中是否含有 `token`。
    public static func hasToken(_ raw: String) -> Bool {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let scheme = leadingScheme(in: trimmed)?.lowercased(),
              scheme == "http" || scheme == "https",
              !hasInvalidPercentEscape(trimmed),
              let parts = parseHttpParts(trimmed, expectedScheme: scheme),
              let query = parts.query
        else {
            return false
        }
        return query.split(separator: "&", omittingEmptySubsequences: false)
            .contains { !$0.isEmpty && isTokenPart(String($0)) }
    }

    /// 日志脱敏：去掉 `token` 参数并隐藏密码；无法解析时不回显原文。
    public static func redact(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let scheme = leadingScheme(in: trimmed)?.lowercased(),
              scheme == "http" || scheme == "https",
              !hasInvalidPercentEscape(trimmed),
              let parts = parseHttpParts(trimmed, expectedScheme: scheme)
        else {
            return "<无法解析的地址>"
        }
        let redactedUserInfo: String? = parts.userInfo.map { ui in
            if let colon = ui.firstIndex(of: ":") {
                let user = String(ui[..<colon])
                return "\(user):xxxxx"
            }
            return ui
        }
        let authority = buildAuthority(
            scheme: parts.scheme,
            userInfo: redactedUserInfo,
            host: parts.host,
            port: parts.port
        )
        let path = parts.path.isEmpty ? "/" : parts.path
        let query = stripToken(parts.query)
        var out = "\(parts.scheme)://\(authority)\(path)"
        if let query {
            out += "?\(query)"
        }
        if let fragment = parts.fragment {
            out += "#\(fragment)"
        }
        return out
    }

    /// 干净地址或 host 字符串是否属于 tailnet 目标（字面判定，不做 DNS 解析）。
    public static func isTailnetHost(_ host: String) -> Bool {
        var h = host.trimmingCharacters(in: .whitespacesAndNewlines)
        while h.hasSuffix(".") { h.removeLast() }
        if h.hasPrefix("[") && h.hasSuffix("]") && h.count >= 2 {
            h = String(h.dropFirst().dropLast())
        }
        h = h.lowercased()
        if h.isEmpty { return false }
        if isLoopbackHost(h) { return false }
        if let ipv4 = parseIPv4(h) {
            return ipv4[0] == 100 && (64...127).contains(ipv4[1])
        }
        if h.contains(":") {
            return h.hasPrefix("fd7a:115c:a1e0:")
        }
        if h.hasSuffix(".ts.net") {
            return true
        }
        return !h.contains(".")
    }

    /// 目标主机是否为本机回环（localhost / 127.0.0.0/8 / ::1 / 0.0.0.0 / ::）。
    public static func isLoopbackHost(_ host: String) -> Bool {
        var h = host.trimmingCharacters(in: .whitespacesAndNewlines)
        while h.hasSuffix(".") { h.removeLast() }
        if h.hasPrefix("[") && h.hasSuffix("]") && h.count >= 2 {
            h = String(h.dropFirst().dropLast())
        }
        h = h.lowercased()
        if h == "localhost" || h.hasSuffix(".localhost") { return true }
        if h == "::1" || h == "0:0:0:0:0:0:0:1" || h == "0.0.0.0" || h == "::" { return true }
        if let ipv4 = parseIPv4(h) {
            return ipv4[0] == 127
        }
        return false
    }

    /// 提取已规范化 URL 的 host（失败返回 `nil`）。
    public static func extractHost(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let scheme = leadingScheme(in: trimmed)?.lowercased(),
              scheme == "http" || scheme == "https",
              let parts = parseHttpParts(trimmed, expectedScheme: scheme)
        else {
            return nil
        }
        return parts.host
    }

    /// 提取规范化源 `scheme://host:effectivePort`（http 默认 80、https 默认 443；非法返回 `nil`）。
    public static func canonicalOrigin(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let scheme = leadingScheme(in: trimmed)?.lowercased(),
              scheme == "http" || scheme == "https",
              !hasInvalidPercentEscape(trimmed),
              let parts = parseHttpParts(trimmed, expectedScheme: scheme)
        else {
            return nil
        }
        let effectivePort = parts.port ?? (scheme == "https" ? 443 : 80)
        return "\(parts.scheme)://\(parts.host):\(effectivePort)"
    }

    /// 提取与请求目标一致的 `Host` 请求头（默认端口省略，非默认端口附带 `:port`）。
    public static func hostHeader(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let scheme = leadingScheme(in: trimmed)?.lowercased(),
              scheme == "http" || scheme == "https",
              !hasInvalidPercentEscape(trimmed),
              let parts = parseHttpParts(trimmed, expectedScheme: scheme)
        else {
            return nil
        }
        if let port = parts.port {
            let isDefault = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
            return isDefault ? parts.host : "\(parts.host):\(port)"
        }
        return parts.host
    }

    // MARK: - 内部解析辅助

    private struct ParsedHttpParts {
        let scheme: String
        let userInfo: String?
        let host: String
        let port: Int?
        let path: String
        let query: String?
        let fragment: String?
    }

    private static func leadingScheme(in text: String) -> String? {
        guard let first = text.unicodeScalars.first, isAlpha(first) else { return nil }
        var idx = text.unicodeScalars.startIndex
        while idx < text.unicodeScalars.endIndex {
            let scalar = text.unicodeScalars[idx]
            if scalar == ":" {
                return String(text.unicodeScalars[text.unicodeScalars.startIndex..<idx])
            }
            if isAlpha(scalar) || isDigit(scalar) || scalar == "+" || scalar == "." || scalar == "-" {
                idx = text.unicodeScalars.index(after: idx)
            } else {
                return nil
            }
        }
        return nil
    }

    private static func hasInvalidPercentEscape(_ text: String) -> Bool {
        let bytes = Array(text.utf8)
        var i = 0
        while i < bytes.count {
            if bytes[i] == 0x25 { // '%'
                guard i + 2 < bytes.count, isHex(bytes[i + 1]), isHex(bytes[i + 2]) else {
                    return true
                }
                i += 3
            } else {
                i += 1
            }
        }
        return false
    }

    private static func parseHttpParts(_ raw: String, expectedScheme: String) -> ParsedHttpParts? {
        // 拒绝空白与控制字符
        for scalar in raw.unicodeScalars {
            if scalar.value <= 0x20 || scalar.value == 0x7F {
                return nil
            }
        }
        guard let colonIdx = raw.firstIndex(of: ":") else { return nil }
        let afterColon = raw[raw.index(after: colonIdx)...]
        guard afterColon.hasPrefix("//") else { return nil }
        let rest = afterColon.dropFirst(2)

        // 先切出 fragment (#...)
        let beforeFragment: Substring
        let fragment: String?
        if let hashIdx = rest.firstIndex(of: "#") {
            beforeFragment = rest[..<hashIdx]
            fragment = String(rest[rest.index(after: hashIdx)...])
        } else {
            beforeFragment = rest
            fragment = nil
        }

        // 再切出 query (?...)
        let beforeQuery: Substring
        let query: String?
        if let qIdx = beforeFragment.firstIndex(of: "?") {
            beforeQuery = beforeFragment[..<qIdx]
            query = String(beforeFragment[beforeFragment.index(after: qIdx)...])
        } else {
            beforeQuery = beforeFragment
            query = nil
        }

        // 切出 authority 与 path
        let authoritySub: Substring
        let path: String
        if let slashIdx = beforeQuery.firstIndex(of: "/") {
            authoritySub = beforeQuery[..<slashIdx]
            path = String(beforeQuery[slashIdx...])
        } else {
            authoritySub = beforeQuery
            path = ""
        }

        guard !authoritySub.isEmpty else { return nil }

        let userInfo: String?
        let hostPortSub: Substring
        if let atIdx = authoritySub.lastIndex(of: "@") {
            userInfo = String(authoritySub[..<atIdx])
            hostPortSub = authoritySub[authoritySub.index(after: atIdx)...]
        } else {
            userInfo = nil
            hostPortSub = authoritySub
        }

        guard !hostPortSub.isEmpty else { return nil }

        let host: String
        let port: Int?
        if hostPortSub.hasPrefix("[") {
            guard let closeBracket = hostPortSub.firstIndex(of: "]") else { return nil }
            let inner = String(hostPortSub[hostPortSub.index(after: hostPortSub.startIndex)..<closeBracket])
            guard !inner.isEmpty, isValidIPv6HostChars(inner) else { return nil }
            host = "[\(inner.lowercased())]"
            let afterBracket = hostPortSub[hostPortSub.index(after: closeBracket)...]
            if afterBracket.isEmpty {
                port = nil
            } else if afterBracket.hasPrefix(":") {
                let portStr = String(afterBracket.dropFirst())
                guard !portStr.isEmpty, let p = Int(portStr), (0...65535).contains(p),
                      portStr.unicodeScalars.allSatisfy(isDigit) else { return nil }
                port = p
            } else {
                return nil
            }
        } else if let colon = hostPortSub.lastIndex(of: ":") {
            let h = String(hostPortSub[..<colon])
            let portStr = String(hostPortSub[hostPortSub.index(after: colon)...])
            guard !h.isEmpty, isValidHost(h) else { return nil }
            if portStr.isEmpty {
                port = nil
            } else {
                guard let p = Int(portStr), (0...65535).contains(p),
                      portStr.unicodeScalars.allSatisfy(isDigit) else { return nil }
                port = p
            }
            host = h.lowercased()
        } else {
            let h = String(hostPortSub)
            guard !h.isEmpty, isValidHost(h) else { return nil }
            host = h.lowercased()
            port = nil
        }

        return ParsedHttpParts(
            scheme: expectedScheme,
            userInfo: userInfo,
            host: host,
            port: port,
            path: path,
            query: query,
            fragment: fragment
        )
    }

    private static func buildAuthority(scheme: String, userInfo: String?, host: String, port: Int?) -> String {
        var out = ""
        if let userInfo {
            out += "\(userInfo)@"
        }
        out += host
        if let port {
            let isDefault = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
            if !isDefault {
                out += ":\(port)"
            }
        }
        return out
    }

    private static func stripToken(_ rawQuery: String?) -> String? {
        guard let rawQuery else { return nil }
        let kept = rawQuery.split(separator: "&", omittingEmptySubsequences: false)
            .map(String.init)
            .filter { !$0.isEmpty && !isTokenPart($0) }
        return kept.isEmpty ? nil : kept.joined(separator: "&")
    }

    private static func isTokenPart(_ part: String) -> Bool {
        let rawKey: String
        if let eq = part.firstIndex(of: "=") {
            rawKey = String(part[..<eq])
        } else {
            rawKey = part
        }
        let plusReplaced = rawKey.replacingOccurrences(of: "+", with: " ")
        guard let decoded = decodePercentUTF8(plusReplaced) else {
            return false
        }
        return decoded == tokenParam
    }

    private static func decodePercentUTF8(_ input: String) -> String? {
        var outBytes: [UInt8] = []
        let bytes = Array(input.utf8)
        var i = 0
        while i < bytes.count {
            if bytes[i] == 0x25 { // '%'
                guard i + 2 < bytes.count,
                      let hi = hexValue(bytes[i + 1]),
                      let lo = hexValue(bytes[i + 2])
                else {
                    return nil
                }
                outBytes.append((hi << 4) | lo)
                i += 3
            } else {
                outBytes.append(bytes[i])
                i += 1
            }
        }
        return String(bytes: outBytes, encoding: .utf8)
    }

    private static func isValidHost(_ host: String) -> Bool {
        for scalar in host.unicodeScalars {
            // 禁止常见保留分隔符
            if scalar == "[" || scalar == "]" || scalar == ":" || scalar == "/" ||
                scalar == "?" || scalar == "#" || scalar == "@" || scalar == "%" {
                return false
            }
        }
        return true
    }

    private static func isValidIPv6HostChars(_ inner: String) -> Bool {
        inner.unicodeScalars.allSatisfy { s in
            isHexScalar(s) || s == ":" || s == "."
        }
    }

    private static func parseIPv4(_ s: String) -> [Int]? {
        let parts = s.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }
        var out: [Int] = []
        out.reserveCapacity(4)
        for p in parts {
            guard !p.isEmpty, p.unicodeScalars.allSatisfy(isDigit), let v = Int(p), (0...255).contains(v) else {
                return nil
            }
            out.append(v)
        }
        return out
    }

    private static func isAlpha(_ s: UnicodeScalar) -> Bool {
        (s >= "a" && s <= "z") || (s >= "A" && s <= "Z")
    }

    private static func isDigit(_ s: UnicodeScalar) -> Bool {
        s >= "0" && s <= "9"
    }

    private static func isHexScalar(_ s: UnicodeScalar) -> Bool {
        isDigit(s) || (s >= "a" && s <= "f") || (s >= "A" && s <= "F")
    }

    private static func isHex(_ b: UInt8) -> Bool {
        (0x30...0x39).contains(b) || (0x61...0x66).contains(b) || (0x41...0x46).contains(b)
    }

    private static func hexValue(_ b: UInt8) -> UInt8? {
        switch b {
        case 0x30...0x39: return b - 0x30
        case 0x61...0x66: return b - 0x61 + 10
        case 0x41...0x46: return b - 0x41 + 10
        default: return nil
        }
    }
}
