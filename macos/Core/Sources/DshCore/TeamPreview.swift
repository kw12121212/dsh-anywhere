import Foundation

/// 团队页条目预览组装与摘要清洗（与 `TeamPreview.kt` 对齐）。
public enum TeamPreview: Sendable {
    public enum Kind: Equatable, Sendable {
        case answer
        case approval
        case work
        case groupWork
        case summary
        case role
    }

    public struct Preview: Equatable, Sendable {
        public let kind: Kind
        public let text: String

        public init(kind: Kind, text: String) {
            self.kind = kind
            self.text = text
        }

        public var waiting: Bool {
            kind == .answer || kind == .approval
        }
    }

    public static func waitingAnswer() -> String {
        I18n.tr("等你回答", "Waiting for your answer")
    }

    public static func waitingApproval() -> String {
        I18n.tr("等你审批", "Waiting for your approval")
    }

    public static func ofBot(_ bot: DsbotModels.Bot, state: DsbotModels.State) -> Preview? {
        let raw = state.raw
        let part = bot.sessionId ?? bot.id
        if has(raw, key: "questions", sessionId: part) || has(raw, key: "views", sessionId: part) {
            return Preview(kind: .answer, text: waitingAnswer())
        }
        let hasApproval = approvals(raw).contains { $0.nonBlankString("botId") == bot.id && $0.nonBlankString("roomId") == nil } ||
            pendingRequest(raw, key: "secretRequests", botId: bot.id) ||
            pendingRequest(raw, key: "connectorRequests", botId: bot.id) ||
            !(raw.object("learning")?.array(bot.id)?.isEmpty ?? true)
        if hasApproval {
            return Preview(kind: .approval, text: waitingApproval())
        }
        let work = raw.object("work")
        if let doing = work?.object("bots")?.object(bot.id) {
            return Preview(kind: .work, text: WorkPhrase.phrase(doing))
        }
        var groups: [(roomId: String, doing: JsonObject)] = []
        if let roomsWork = work?.object("rooms") {
            for (roomId, val) in roomsWork.entries {
                guard let arr = val.arrayValue else { continue }
                for item in arr {
                    guard let obj = item.objectValue, obj.nonBlankString("botId") == bot.id else { continue }
                    groups.append((roomId, obj))
                }
            }
        }
        if let first = groups.first {
            let name = state.rooms.first(where: { $0.id == first.roomId })?.raw.nonBlankString("name")
                ?? I18n.tr("群聊", "a group chat")
            return Preview(
                kind: .groupWork,
                text: more("\(name) · \(WorkPhrase.phrase(first.doing))", extra: groups.count - 1)
            )
        }
        if let cleaned = clean(bot.raw.nonBlankString("preview")) {
            return Preview(kind: .summary, text: cleaned)
        }
        return bot.raw.nonBlankString("role").map { Preview(kind: .role, text: $0) }
    }

    public static func ofRoom(_ room: DsbotModels.Room, state: DsbotModels.State) -> Preview? {
        let raw = state.raw
        var sessions: [String] = [room.sessionId ?? room.id]
        if let parts = room.raw.array("parts") {
            for item in parts {
                if let s = item.stringValue, !sessions.contains(s) {
                    sessions.append(s)
                }
            }
        }
        if sessions.contains(where: { has(raw, key: "questions", sessionId: $0) || has(raw, key: "views", sessionId: $0) }) {
            return Preview(kind: .answer, text: waitingAnswer())
        }
        if approvals(raw).contains(where: { $0.nonBlankString("roomId") == room.id }) {
            return Preview(kind: .approval, text: waitingApproval())
        }
        let entries = raw.object("work")?.object("rooms")?.array(room.id)?.compactMap(\.objectValue) ?? []
        if let first = entries.first {
            let who = first.nonBlankString("botId").map { id in
                state.bots.first(where: { $0.id == id })?.raw.nonBlankString("name") ?? id
            }
            let phrase = WorkPhrase.phrase(first)
            return Preview(
                kind: .groupWork,
                text: more(who.map { "\($0) · \(phrase)" } ?? phrase, extra: entries.count - 1)
            )
        }
        return clean(room.raw.nonBlankString("preview")).map { Preview(kind: .summary, text: $0) }
    }

    private static let multiSpaceRegex = try! NSRegularExpression(pattern: #"\s+"#)
    private static let headerRegex = try! NSRegularExpression(
        pattern: #"^\[(Message from|Reply from|Group chat|Group post from|Bot team setup|Handoff · part|Secret card)[^\]]*\]\s*"#
    )
    private static let handoffRegex = try! NSRegularExpression(pattern: #"^\[Browser handoff\]\s*(.*?)\s*Answer:\s*(\S+)\s*$"#)
    private static let questionRegex = try! NSRegularExpression(pattern: #"^\[Answer to question card\]\s*(.*?)\s*Answer:\s*(.*)$"#)
    private static let viewRegex = try! NSRegularExpression(pattern: #"^\[Answer to view card\]\s*(.*?)\s*Answer:\s*(.*)$"#)
    private static let groupLineRegex = try! NSRegularExpression(pattern: #"^([^：\[\]]{1,40})：(.*)$"#)
    private static let recordMarkerRegex = try! NSRegularExpression(
        pattern: #"\[(?:Browser handoff|Answer to question card|Answer to view card)\]"#
    )

    public static func clean(_ text: String?) -> String? {
        guard let text else { return nil }
        let nsRaw = text as NSString
        let flat = multiSpaceRegex.stringByReplacingMatches(
            in: text,
            range: NSRange(location: 0, length: nsRaw.length),
            withTemplate: " "
        ).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !flat.isEmpty else { return nil }

        let nsFlat = flat as NSString
        if let m = groupLineRegex.firstMatch(in: flat, range: NSRange(location: 0, length: nsFlat.length)) {
            let speaker = nsFlat.substring(with: m.range(at: 1))
            let rest = nsFlat.substring(with: m.range(at: 2))
            guard let body = cleanRecords(rest) else { return flat }
            return "\(speaker)：\(body)"
        }
        return cleanRecords(flat) ?? flat
    }

    private static func cleanRecords(_ flat: String) -> String? {
        let ns = flat as NSString
        let stripped: String
        if let firstHeader = headerRegex.firstMatch(in: flat, range: NSRange(location: 0, length: ns.length)) {
            stripped = ns.substring(from: firstHeader.range.length).trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            stripped = flat
        }

        let nsStripped = stripped as NSString
        let matches = recordMarkerRegex.matches(in: stripped, range: NSRange(location: 0, length: nsStripped.length))
        var records: [String] = []
        if matches.isEmpty {
            records = [stripped]
        } else {
            var last = 0
            for m in matches {
                if m.range.location > last {
                    let seg = nsStripped.substring(with: NSRange(location: last, length: m.range.location - last))
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    if !seg.isEmpty { records.append(seg) }
                }
                last = m.range.location
            }
            if last < nsStripped.length {
                let tail = nsStripped.substring(from: last).trimmingCharacters(in: .whitespacesAndNewlines)
                if !tail.isEmpty { records.append(tail) }
            }
        }
        records = records.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        if records.count <= 1 {
            return cleanBody(records.first ?? stripped)
        }
        let cleaned = records.compactMap(cleanBody).joined(separator: I18n.tr("；", "; "))
        return cleaned.isEmpty ? nil : cleaned
    }

    private static func cleanBody(_ text: String) -> String? {
        let ns = text as NSString
        let full = NSRange(location: 0, length: ns.length)
        if let m = handoffRegex.firstMatch(in: text, range: full) {
            let label = ns.substring(with: m.range(at: 1))
            let rawOutcome = ns.substring(with: m.range(at: 2)).lowercased()
            let outcome: String
            switch rawOutcome {
            case "done": outcome = I18n.tr("（已完成）", " (done)")
            case "skip", "skipped": outcome = I18n.tr("（已跳过）", " (skipped)")
            default: outcome = ""
            }
            return I18n.tr("浏览器交接：", "Browser handoff: ") + label + outcome
        }
        if let m = questionRegex.firstMatch(in: text, range: full) {
            let q = ns.substring(with: m.range(at: 1))
            let rawAns = ns.substring(with: m.range(at: 2))
            let ans = rawAns == "(skipped)" ? I18n.tr("已跳过", "skipped") : rawAns
            return I18n.tr("已回答「\(q)」：\(ans)", "Answered \"\(q)\": \(ans)")
        }
        if let m = viewRegex.firstMatch(in: text, range: full) {
            let title = ns.substring(with: m.range(at: 1))
            if title.isEmpty {
                return I18n.tr("已回复卡片", "Replied to a card")
            }
            return I18n.tr("已回复卡片「\(title)」", "Replied to card \"\(title)\"")
        }
        return text.isEmpty ? nil : text
    }

    private static func more(_ text: String, extra: Int) -> String {
        extra > 0 ? "\(text) +\(extra)" : text
    }

    private static func has(_ raw: JsonObject, key: String, sessionId: String) -> Bool {
        raw.object(key)?.object(sessionId) != nil
    }

    private static func approvals(_ raw: JsonObject) -> [JsonObject] {
        (raw.object("work")?.array("approvals") ?? [])
            .compactMap(\.objectValue)
            .filter { $0.bool("answerable") != false }
    }

    private static func pendingRequest(_ raw: JsonObject, key: String, botId: String) -> Bool {
        (raw.object(key)?.array(botId) ?? []).contains { item in
            guard let o = item.objectValue else { return false }
            return (o.nonBlankString("status") ?? "pending") == "pending"
        }
    }
}
