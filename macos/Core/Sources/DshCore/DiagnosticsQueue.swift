import Foundation

/// 诊断上报的持久化与组包（diagnostics-reporting 规格）：有界待发队列（每份一个文件、
/// 原子落盘、超限丢最旧、损坏丢弃）与批量组包（每批 ≤20 份且编码后 ≤1 MiB）。
/// 语义与 android/core 的 DiagStore 对齐；端点配置复用 `DiagnosticsEndpointStore`，
/// 与 harness 配置同目录不同文件（separate-state-files）。目录 URL 由调用方注入。
public final class DiagnosticsQueue: Sendable {
    /// 队列中的一份：文件名（删除凭据）与报告。
    public struct Named: Sendable {
        public let name: String
        public let report: DiagReport

        public init(name: String, report: DiagReport) {
            self.name = name
            self.report = report
        }
    }

    public let directory: URL
    public let capacity: Int
    private let nowMillis: @Sendable () -> Int64

    /// - Parameters:
    ///   - directory: 队列目录（应用私有数据目录下的独立子目录）。
    ///   - capacity: 队列上限，超出丢最旧。
    ///   - nowMillis: 可注入的时间源（测试用）。
    public init(
        directory: URL,
        capacity: Int = DiagLimits.defaultQueueCapacity,
        nowMillis: @escaping @Sendable () -> Int64 = { Int64(Date.now.timeIntervalSince1970 * 1000) }
    ) {
        self.directory = directory
        self.capacity = capacity
        self.nowMillis = nowMillis
    }

    /// 原子写入一份报告；超过上限时删最旧的文件。
    public func add(_ report: DiagReport) {
        let fm = FileManager.default
        try? fm.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: NSNumber(value: 0o700)]
        )
        let name = fileName(millis: nowMillis(), id: report.id)
        let tmp = directory.appendingPathComponent(".\(name).\(String(UInt64.random(in: 0...UInt64.max), radix: 16)).tmp")
        defer { try? fm.removeItem(at: tmp) }
        let payload = Data(DiagJson.encodeReport(report).utf8)
        let created = fm.createFile(
            atPath: tmp.path,
            contents: payload,
            attributes: [.posixPermissions: NSNumber(value: 0o600)]
        )
        if !created {
            try? payload.write(to: tmp, options: .atomic)
            try? fm.setAttributes([.posixPermissions: NSNumber(value: 0o600)], ofItemAtPath: tmp.path)
        }
        // 目标名含报告 id 与毫秒时间戳，天然唯一：直接 move 即为原子落盘。
        try? fm.moveItem(at: tmp, to: directory.appendingPathComponent(name))
        enforceCapacity()
    }

    /// 读取全部待发报告（文件名字典序即时间序）；损坏/无法解析的文件删除并跳过，不阻断后续。
    public func load() -> [Named] {
        let fm = FileManager.default
        guard let names = listNames() else { return [] }
        var out: [Named] = []
        for name in names {
            let file = directory.appendingPathComponent(name)
            guard let data = try? Data(contentsOf: file),
                  let text = String(data: data, encoding: .utf8),
                  let report = DiagJson.decodeReport(text)
            else {
                try? fm.removeItem(at: file)
                continue
            }
            out.append(Named(name: name, report: report))
        }
        return out
    }

    /// 删除已发送（或按 4xx 丢弃）的文件；不存在的名字忽略。
    public func remove(_ names: [String]) {
        let fm = FileManager.default
        for name in names {
            try? fm.removeItem(at: directory.appendingPathComponent(name))
        }
    }

    /// 清空队列（设置清除端点时调用）。
    public func clear() {
        guard let names = listNames() else { return }
        remove(names)
    }

    private func listNames() -> [String]? {
        let fm = FileManager.default
        guard let all = try? fm.contentsOfDirectory(atPath: directory.path) else { return nil }
        return all.filter { $0.hasSuffix(".json") }.sorted()
    }

    private func enforceCapacity() {
        guard let names = listNames() else { return }
        let excess = names.count - capacity
        guard excess > 0 else { return }
        remove(Array(names[0..<excess]))
    }

    private func fileName(millis: Int64, id: String) -> String {
        let padded = String(repeating: "0", count: max(0, 19 - String(millis).count)) + String(millis)
        return "\(padded)-\(id).json"
    }
}

/// 批量组包：每批 ≤20 份且编码后 ≤1 MiB，保持时间序；单份超限独占一批（由截断上限保证不会出现）。
public enum DiagBatching: Sendable {
    public static func batches(_ items: [DiagnosticsQueue.Named]) -> [[DiagnosticsQueue.Named]] {
        var out: [[DiagnosticsQueue.Named]] = []
        var current: [DiagnosticsQueue.Named] = []
        var currentBytes = 14 // {"reports":[]} 骨架
        for item in items {
            let size = DiagJson.encodedSize(item.report) + 1
            let wouldOverflowBytes = currentBytes + size > DiagLimits.maxBatchBytes
            if !current.isEmpty && (wouldOverflowBytes || current.count >= DiagLimits.maxBatchReports) {
                out.append(current)
                current = []
                currentBytes = 14
            }
            current.append(item)
            currentBytes += size
        }
        if !current.isEmpty {
            out.append(current)
        }
        return out
    }
}

/// 同会话错误态去重：同类错误（按分类键）至多上报一次。
/// 通过 NSLock 同步访问；在 Swift 6 Sendable 检查下以 nonisolated(unsafe) 标明锁保护字段。
public final class DiagErrorDedup: Sendable {
    private let lock = NSLock()
    private nonisolated(unsafe) var seen: Set<String> = []

    public init() {}

    /// 记录分类键；此前已见过返回 true（不再上报）。
    public func seenBefore(_ key: String) -> Bool {
        lock.withLock {
            if seen.contains(key) { return true }
            seen.insert(key)
            return false
        }
    }

    public func reset() {
        lock.withLock { seen.removeAll() }
    }
}
