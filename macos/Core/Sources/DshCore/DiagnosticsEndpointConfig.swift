import Foundation

/// 诊断上报端点的校验、解析与序列化（仅接受无 query 与 userInfo 的 `https` 地址，语义对齐 `validateDiagnosticsEndpoint`）。
public enum DiagnosticsEndpointConfig: Sendable {
    public enum Load: Equatable, Sendable {
        case missing
        case invalid
        case ok(String)
    }

    public struct ValidationError: Error, Equatable, Sendable {
        public init() {}
    }

    public static let fileName = "diagnostics.json"
    public static let tmpPrefix = ".diagnostics.json."

    /// 校验并规范化诊断上报端点；仅接受带主机名的 `https` 绝对地址且不含 query 与 userInfo，非法返回 `nil`。
    public static func validate(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        for scalar in trimmed.unicodeScalars {
            if scalar.value <= 0x20 || scalar.value == 0x7F {
                return nil
            }
        }

        guard let colonIdx = trimmed.firstIndex(of: ":") else { return nil }
        let scheme = String(trimmed[..<colonIdx]).lowercased()
        guard scheme == "https" else { return nil }

        let afterColon = trimmed[trimmed.index(after: colonIdx)...]
        guard afterColon.hasPrefix("//") else { return nil }
        guard !hasInvalidPercentEscape(trimmed) else { return nil }

        let rest = afterColon.dropFirst(2)
        // 剥离 fragment（#...）
        let beforeFragment: Substring
        if let hashIdx = rest.firstIndex(of: "#") {
            beforeFragment = rest[..<hashIdx]
        } else {
            beforeFragment = rest
        }

        // 拒绝任何 query 参数，避免后续拼路径畸形
        guard !beforeFragment.contains("?") else { return nil }

        let authoritySub: Substring
        let path: String
        if let slashIdx = beforeFragment.firstIndex(of: "/") {
            authoritySub = beforeFragment[..<slashIdx]
            let rawPath = String(beforeFragment[slashIdx...])
            path = rawPath.isEmpty ? "/" : rawPath
        } else {
            authoritySub = beforeFragment
            path = "/"
        }

        guard !authoritySub.isEmpty, !authoritySub.contains("@") else { return nil }

        let host: String
        let port: Int?
        if authoritySub.hasPrefix("[") {
            guard let closeBracket = authoritySub.firstIndex(of: "]") else { return nil }
            let inner = String(authoritySub[authoritySub.index(after: authoritySub.startIndex)..<closeBracket])
            guard !inner.isEmpty, isValidIPv6HostChars(inner) else { return nil }
            host = "[\(inner.lowercased())]"
            let afterBracket = authoritySub[authoritySub.index(after: closeBracket)...]
            if afterBracket.isEmpty {
                port = nil
            } else if afterBracket.hasPrefix(":") {
                guard let parsed = parsePort(String(afterBracket.dropFirst())) else { return nil }
                port = parsed
            } else {
                return nil
            }
        } else if let colon = authoritySub.lastIndex(of: ":") {
            let h = String(authoritySub[..<colon])
            let portStr = String(authoritySub[authoritySub.index(after: colon)...])
            guard !h.isEmpty, isValidHost(h) else { return nil }
            if portStr.isEmpty {
                port = nil
            } else {
                guard let parsed = parsePort(portStr) else { return nil }
                port = parsed
            }
            host = h.lowercased()
        } else {
            let h = String(authoritySub)
            guard !h.isEmpty, isValidHost(h) else { return nil }
            host = h.lowercased()
            port = nil
        }

        let portSuffix: String
        if let port, port != 443 {
            portSuffix = ":\(port)"
        } else {
            portSuffix = ""
        }
        return "https://\(host)\(portSuffix)\(path)"
    }

    /// 纯文本解析（`nil` 表示文件缺失；使用内置 `Json` 解析而非 `JSONSerialization`）。
    public static func parseContent(_ content: String?) -> Load {
        guard let content else { return .missing }
        guard let parsed = Json.parse(content),
              let obj = parsed.objectValue,
              let raw = obj.string("diagnosticsEndpoint"),
              let endpoint = validate(raw)
        else {
            return .invalid
        }
        return .ok(endpoint)
    }

    /// 序列化为与桌面端 `diagnostics.json` 一致的字节形态。
    public static func serialize(endpoint: String) -> String {
        "{\n  \"diagnosticsEndpoint\": \(JsonString.of(endpoint))\n}\n"
    }

    // MARK: - 内部解析辅助

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

    private static func parsePort(_ portStr: String) -> Int? {
        guard !portStr.isEmpty,
              portStr.unicodeScalars.allSatisfy(isDigit),
              let value = Int(portStr),
              (0...65535).contains(value)
        else {
            return nil
        }
        return value
    }

    private static func isValidHost(_ host: String) -> Bool {
        for scalar in host.unicodeScalars {
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

    private static func isDigit(_ s: UnicodeScalar) -> Bool {
        s >= "0" && s <= "9"
    }

    private static func isHexScalar(_ s: UnicodeScalar) -> Bool {
        isDigit(s) || (s >= "a" && s <= "f") || (s >= "A" && s <= "F")
    }

    private static func isHex(_ b: UInt8) -> Bool {
        (0x30...0x39).contains(b) || (0x61...0x66).contains(b) || (0x41...0x46).contains(b)
    }
}

/// 基于指定目录的 `diagnostics.json` 读写与 `0600` 原子持久化（目录由 App 层注入）。
public final class DiagnosticsEndpointStore: Sendable {
    public let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    public var fileURL: URL {
        directory.appendingPathComponent(DiagnosticsEndpointConfig.fileName)
    }

    public func exists() -> Bool {
        var isDir: ObjCBool = false
        return FileManager.default.fileExists(atPath: fileURL.path, isDirectory: &isDir) && !isDir.boolValue
    }

    public func load() -> DiagnosticsEndpointConfig.Load {
        guard exists() else { return .missing }
        guard let data = try? Data(contentsOf: fileURL),
              let text = String(data: data, encoding: .utf8)
        else {
            return .invalid
        }
        return DiagnosticsEndpointConfig.parseContent(text)
    }

    @discardableResult
    public func save(_ rawEndpoint: String) throws -> String {
        guard let endpoint = DiagnosticsEndpointConfig.validate(rawEndpoint) else {
            throw DiagnosticsEndpointConfig.ValidationError()
        }
        let fm = FileManager.default
        try fm.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: NSNumber(value: 0o700)]
        )
        let suffix = String(UInt64.random(in: 0...UInt64.max), radix: 16)
        let tmpURL = directory.appendingPathComponent("\(DiagnosticsEndpointConfig.tmpPrefix)\(suffix).tmp")
        defer {
            try? fm.removeItem(at: tmpURL)
        }
        let payload = Data(DiagnosticsEndpointConfig.serialize(endpoint: endpoint).utf8)
        let created = fm.createFile(
            atPath: tmpURL.path,
            contents: payload,
            attributes: [.posixPermissions: NSNumber(value: 0o600)]
        )
        if !created {
            try payload.write(to: tmpURL, options: .atomic)
        }
        try? fm.setAttributes([.posixPermissions: NSNumber(value: 0o600)], ofItemAtPath: tmpURL.path)

        if fm.fileExists(atPath: fileURL.path) {
            _ = try fm.replaceItemAt(fileURL, withItemAt: tmpURL)
        } else {
            try fm.moveItem(at: tmpURL, to: fileURL)
        }
        try fm.setAttributes([.posixPermissions: NSNumber(value: 0o600)], ofItemAtPath: fileURL.path)
        return endpoint
    }

    public func clear() {
        try? FileManager.default.removeItem(at: fileURL)
    }
}
