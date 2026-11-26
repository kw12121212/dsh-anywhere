import Foundation

/// 按 canonical origin 隔离的 `dsh-auth-*` 会话 cookie 私有存储（`0600` 原子落盘，不入日志）。
public final class SessionCookieStore: Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    public static let fileName = "session-cookies"
    public static let tmpPrefix = ".session-cookies."

    public let directory: URL
    private let lock = NSLock()
    // 通过 NSLock 同步访问；在 Swift 6 Sendable 检查下以 nonisolated(unsafe) 标明锁保护字段。
    private nonisolated(unsafe) var buckets: [String: [SessionCookies.Cookie]] = [:]

    public init(directory: URL) {
        self.directory = directory
    }

    private var fileURL: URL {
        directory.appendingPathComponent(Self.fileName)
    }

    public var description: String {
        let count = lock.withLock { buckets.count }
        return "SessionCookieStore(origins: \(count))"
    }

    public var debugDescription: String {
        description
    }

    public func load() {
        let fm = FileManager.default
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: fileURL.path, isDirectory: &isDir), !isDir.boolValue,
              let data = try? Data(contentsOf: fileURL),
              let text = String(data: data, encoding: .utf8)
        else {
            lock.withLock { buckets = [:] }
            return
        }

        var loaded: [String: [SessionCookies.Cookie]] = [:]
        for rawLine in text.split(whereSeparator: \.isNewline) {
            let parts = rawLine.split(separator: "\t", omittingEmptySubsequences: false)
            guard parts.count == 3 else { continue }
            let originRaw = String(parts[0])
            let name = String(parts[1])
            let value = String(parts[2])
            guard let origin = HarnessUrl.canonicalOrigin(originRaw),
                  SessionCookies.isAuthCookie(name),
                  !value.isEmpty
            else {
                continue
            }
            var list = loaded[origin] ?? []
            list.removeAll { $0.name == name }
            list.append(SessionCookies.Cookie(name: name, value: value))
            loaded[origin] = list
        }
        lock.withLock {
            buckets = loaded
        }
    }

    public func cookies(forUrl url: String) -> [SessionCookies.Cookie] {
        guard let origin = HarnessUrl.canonicalOrigin(url) else { return [] }
        return lock.withLock { buckets[origin] ?? [] }
    }

    public func hasAuthCookies(forUrl url: String) -> Bool {
        !cookies(forUrl: url).isEmpty
    }

    public func cookieHeader(forUrl url: String) -> String? {
        let list = cookies(forUrl: url)
        guard !list.isEmpty else { return nil }
        return SessionCookies.header(for: list)
    }

    public func replaceCookies(_ newCookies: [SessionCookies.Cookie], forUrl url: String) throws {
        guard let origin = HarnessUrl.canonicalOrigin(url) else { return }
        var deduplicated: [SessionCookies.Cookie] = []
        for c in SessionCookies.authCookies(in: newCookies) where !c.value.isEmpty {
            deduplicated.removeAll { $0.name == c.name }
            deduplicated.append(SessionCookies.Cookie(name: c.name, value: c.value))
        }
        let snapshot: [String: [SessionCookies.Cookie]] = lock.withLock {
            if deduplicated.isEmpty {
                buckets.removeValue(forKey: origin)
            } else {
                buckets[origin] = deduplicated
            }
            return buckets
        }
        try persist(snapshot)
    }

    public func clear(forUrl url: String) throws {
        guard let origin = HarnessUrl.canonicalOrigin(url) else { return }
        let snapshot: [String: [SessionCookies.Cookie]] = lock.withLock {
            buckets.removeValue(forKey: origin)
            return buckets
        }
        try persist(snapshot)
    }

    private func persist(_ snapshot: [String: [SessionCookies.Cookie]]) throws {
        let fm = FileManager.default
        try fm.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: NSNumber(value: 0o700)]
        )
        let suffix = String(UInt64.random(in: 0...UInt64.max), radix: 16)
        let tmpURL = directory.appendingPathComponent("\(Self.tmpPrefix)\(suffix).tmp")
        defer {
            try? fm.removeItem(at: tmpURL)
        }

        var lines: [String] = []
        for origin in snapshot.keys.sorted() {
            for cookie in snapshot[origin] ?? [] where SessionCookies.isAuthCookie(cookie.name) && !cookie.value.isEmpty {
                lines.append("\(origin)\t\(cookie.name)\t\(cookie.value)")
            }
        }
        let payload = Data(lines.joined(separator: "\n").utf8)
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
    }
}
