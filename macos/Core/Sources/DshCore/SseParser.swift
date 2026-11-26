import Foundation

/// SSE 帧增量解析器（与 `SseParser.kt` 对齐）：支持任意切分喂入、`\r\n`/`\n`、`:` 注释保活与多行 `data:` 拼接。
public final class SseParser {
    public struct Frame: Equatable, Sendable {
        public let event: String
        public let data: String

        public init(event: String, data: String) {
            self.event = event
            self.data = data
        }
    }

    private var buffer: String = ""
    private var eventName: String?
    private var dataLines: [String] = []

    public init() {}

    public func feed(_ chunk: String) -> [Frame] {
        var out: [Frame] = []
        buffer += chunk
        while let nl = buffer.utf8.firstIndex(of: 0x0A) {
            var line = String(decoding: buffer.utf8[..<nl], as: UTF8.self)
            let afterNl = buffer.utf8.index(after: nl)
            buffer.removeSubrange(..<afterNl)
            if line.hasSuffix("\r") {
                line.removeLast()
            }
            if line.hasPrefix(":") {
                // 注释/保活行
            } else if line.hasPrefix("event:") {
                let after = line.dropFirst("event:".count)
                eventName = after.trimmingCharacters(in: .whitespacesAndNewlines)
            } else if line.hasPrefix("data:") {
                let after = line.dropFirst("data:".count)
                dataLines.append(after.trimmingCharacters(in: .whitespacesAndNewlines))
            } else if line.isEmpty {
                if !dataLines.isEmpty {
                    out.append(Frame(event: eventName ?? "message", data: dataLines.joined(separator: "\n")))
                }
                eventName = nil
                dataLines.removeAll(keepingCapacity: true)
            }
        }
        return out
    }

    /// 流结束时冲刷缓冲中残留的数据行。
    public func finish() -> [Frame] {
        var out: [Frame] = []
        if !buffer.isEmpty {
            out.append(contentsOf: feed("\n"))
        }
        if !dataLines.isEmpty {
            out.append(Frame(event: eventName ?? "message", data: dataLines.joined(separator: "\n")))
            eventName = nil
            dataLines.removeAll(keepingCapacity: true)
        }
        return out
    }
}
