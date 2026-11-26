import Foundation

/// 统计与 Bot 间记录人话格式化（与 `BotStatsText.kt` 对齐）。
public enum BotStatsText: Sendable {
    private static let posixLocale = Locale(identifier: "en_US_POSIX")

    public static func runStats(_ value: JsonValue) -> String {
        guard let obj = value.objectValue else { return "" }
        var lines: [String] = []

        if let session = obj.object("sessionStats") {
            let turns = session.int64("turns")
            let steps = session.int64("steps")
            if turns != nil || steps != nil {
                var line = I18n.tr("对话 ", "Session ")
                if let turns { line += I18n.tr("\(turns) 轮", "\(turns) turns") }
                if turns != nil && steps != nil { line += " · " }
                if let steps { line += I18n.tr("\(steps) 步", "\(steps) steps") }
                lines.append(line)
            }
            let llmMs = session.int64("llmMs")
            let toolMs = session.int64("toolMs")
            if llmMs != nil || toolMs != nil {
                var line = I18n.tr("耗时 ", "Time ")
                if let llmMs { line += I18n.tr("模型 \(duration(llmMs))", "model \(duration(llmMs))") }
                if llmMs != nil && toolMs != nil { line += " · " }
                if let toolMs { line += I18n.tr("工具 \(duration(toolMs))", "tools \(duration(toolMs))") }
                lines.append(line)
            }
        }

        if let usage = obj.object("tokenUsage") {
            let uncached = usage.int64("uncachedInputTokens")
            let output = usage.int64("outputTokens")
            let cacheRead = usage.int64("cacheReadTokens")
            if uncached != nil || output != nil || cacheRead != nil {
                var parts: [String] = []
                if let uncached { parts.append(I18n.tr("输入 \(count(uncached))", "input \(count(uncached))")) }
                if let output { parts.append(I18n.tr("输出 \(count(output))", "output \(count(output))")) }
                if let cacheRead { parts.append(I18n.tr("缓存读取 \(count(cacheRead))", "cache read \(count(cacheRead))")) }
                lines.append(I18n.tr("Token：", "Tokens: ") + parts.joined(separator: " · "))
            }
        }

        if let pressure = obj.object("contextPressure"),
           let used = pressure.int64("pressureTokens"),
           let window = pressure.int64("contextWindow"),
           window > 0
        {
            let pct = (used * 100) / window
            lines.append(I18n.tr(
                "上下文 \(count(used)) / \(count(window))（\(pct)%）",
                "Context \(count(used)) / \(count(window)) (\(pct)%)"
            ))
        }

        return lines.joined(separator: "\n")
    }

    public static func exchanges(_ value: JsonValue) -> String {
        guard let arr = value.arrayValue else { return "" }
        if arr.isEmpty {
            return I18n.tr("暂无 Bot 间记录", "No messages between Bots yet")
        }
        return arr.map { item -> String in
            if let obj = item.objectValue, let text = firstText(in: obj) {
                return "· \(text)"
            }
            return "· \(Json.encode(item))"
        }.joined(separator: "\n")
    }

    private static func firstText(in obj: JsonObject) -> String? {
        for key in ["text", "content", "summary", "title", "question", "answer", "name"] {
            if let s = obj.nonBlankString(key) {
                return String(s.prefix(200))
            }
        }
        return nil
    }

    public static func duration(_ ms: Int64) -> String {
        let s = ms / 1000
        if s >= 3600 {
            let val = String(format: "%.1f", locale: posixLocale, Double(s) / 3600.0)
            return I18n.tr("\(val) 小时", "\(val) h")
        }
        if s >= 60 {
            let val = String(format: "%.1f", locale: posixLocale, Double(s) / 60.0)
            return I18n.tr("\(val) 分钟", "\(val) min")
        }
        return I18n.tr("\(s) 秒", "\(s) s")
    }

    public static func count(_ n: Int64) -> String {
        if n >= 1_000_000 {
            return String(format: "%.2fM", locale: posixLocale, Double(n) / 1_000_000.0)
        }
        if n >= 10_000 {
            return "\(n / 1000)K"
        }
        return "\(n)"
    }
}
