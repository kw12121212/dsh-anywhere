import Foundation

/// dsh 会话 cookie 的解析、筛选与序列化（与 `SessionCookies.kt` 对齐；cookie 值不写入任何日志）。
public enum SessionCookies: Sendable {
    public struct Cookie: Equatable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
        public let name: String
        public let value: String
        public let attrs: [String: String]

        public init(name: String, value: String, attrs: [String: String] = [:]) {
            self.name = name
            self.value = value
            self.attrs = attrs
        }

        public var description: String { "Cookie(\(name)=***)" }
        public var debugDescription: String { description }
    }

    public static func isAuthCookie(_ name: String) -> Bool {
        name.hasPrefix("dsh-auth-")
    }

    /// 拆分被 HTTP 响应合并成单个逗号分隔字符串的 `Set-Cookie` 头，保留 `Expires=Wed, 21 Oct ...` 中的日期逗号。
    public static func splitSetCookieHeader(_ combined: String) -> [String] {
        let attrNames: Set<String> = [
            "expires", "max-age", "domain", "path", "samesite", "secure", "httponly", "partitioned",
        ]
        var result: [String] = []
        var start = combined.startIndex
        var idx = combined.startIndex

        while idx < combined.endIndex {
            if combined[idx] == "," {
                let afterComma = combined.index(after: idx)
                let rest = combined[afterComma...]
                let trimmedStart = rest.drop { $0 == " " || $0 == "\t" }
                if let candidateName = leadingTokenBeforeEquals(in: trimmedStart),
                   !attrNames.contains(candidateName.lowercased()) {
                    let piece = String(combined[start..<idx]).trimmingCharacters(in: .whitespacesAndNewlines)
                    if !piece.isEmpty {
                        result.append(piece)
                    }
                    start = trimmedStart.startIndex
                    idx = start
                    continue
                }
            }
            idx = combined.index(after: idx)
        }

        let tail = String(combined[start...]).trimmingCharacters(in: .whitespacesAndNewlines)
        if !tail.isEmpty {
            result.append(tail)
        }
        return result
    }

    private static func leadingTokenBeforeEquals(in text: Substring) -> String? {
        var idx = text.startIndex
        while idx < text.endIndex {
            let scalar = text.unicodeScalars[idx]
            if scalar == "=" {
                return idx > text.startIndex ? String(text[..<idx]) : nil
            }
            if isCookieTokenScalar(scalar) {
                idx = text.index(after: idx)
            } else {
                return nil
            }
        }
        return nil
    }

    private static func isCookieTokenScalar(_ s: UnicodeScalar) -> Bool {
        let v = s.value
        guard v > 0x20 && v < 0x7F else { return false }
        let separators = "()<>@,;:\\\"/[]?={}"
        return !separators.unicodeScalars.contains(s)
    }

    public static func parseSetCookie(_ line: String) -> Cookie? {
        let parts = line.split(separator: ";", omittingEmptySubsequences: false)
        guard let firstRaw = parts.first else { return nil }
        let first = firstRaw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let eq = first.firstIndex(of: "="), eq > first.startIndex else { return nil }
        let name = String(first[..<eq]).trimmingCharacters(in: .whitespacesAndNewlines)
        let value = String(first[first.index(after: eq)...]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, !value.isEmpty else { return nil }

        var attrs: [String: String] = [:]
        for rawPart in parts.dropFirst() {
            let part = rawPart.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !part.isEmpty else { continue }
            if let attrEq = part.firstIndex(of: "=") {
                let k = String(part[..<attrEq]).trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                let v = String(part[part.index(after: attrEq)...]).trimmingCharacters(in: .whitespacesAndNewlines)
                if !k.isEmpty { attrs[k] = v }
            } else {
                attrs[part.lowercased()] = ""
            }
        }
        return Cookie(name: name, value: value, attrs: attrs)
    }

    public static func parseHeader(_ header: String) -> [Cookie] {
        header.split(separator: ";", omittingEmptySubsequences: false).compactMap { rawPart in
            guard let eq = rawPart.firstIndex(of: "="), eq > rawPart.startIndex else { return nil }
            let name = String(rawPart[..<eq]).trimmingCharacters(in: .whitespacesAndNewlines)
            let value = String(rawPart[rawPart.index(after: eq)...]).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty, !value.isEmpty else { return nil }
            return Cookie(name: name, value: value)
        }
    }

    public static func authCookies(in cookies: [Cookie]) -> [Cookie] {
        cookies.filter { isAuthCookie($0.name) }
    }

    public static func header(for cookies: [Cookie]) -> String {
        cookies.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
    }

    public static func serialize(_ cookies: [Cookie]) -> String {
        authCookies(in: cookies).map { "\($0.name)\t\($0.value)" }.joined(separator: "\n")
    }

    public static func deserialize(_ text: String) -> [Cookie] {
        text.split(whereSeparator: \.isNewline).compactMap { rawLine in
            let line = String(rawLine)
            guard let tab = line.firstIndex(of: "\t"), tab > line.startIndex else { return nil }
            let name = String(line[..<tab])
            let value = String(line[line.index(after: tab)...])
            guard isAuthCookie(name), !value.isEmpty else { return nil }
            return Cookie(name: name, value: value)
        }
    }
}
