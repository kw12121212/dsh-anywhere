import Foundation

/// 待处理停靠区（dsbot-pending-dock，与 `PendingDock.kt` 对齐）：把当前聊天等待用户处理的事项
/// 汇集成一个有序列表，供输入框上方的手风琴使用。DS-Bot 0.3.10 的待处理项都在 state 里
///（源码核对，tech/dsbot-api 的 dock-fields-0310）：questions/views 按会话 id、live work.approvals、
/// secretRequests/connectorRequests 按 botId、live learning、live browserHolds。聊天条目里的 kind=answer
/// 是用户已经给出的回复记录，不是待处理项。macOS 范围不含邮件发送卡（dsbot-connectors-mail 未移植）。
public enum PendingDock: Sendable {
    /// 标题截断上限（与 Android ChatBars.TITLE_MAX 对齐）。
    public static let titleMax = 80
    /// 列表条数上限（与 Android ChatBars.LIST_MAX 对齐）。
    public static let listMax = 20

    /// 优先级：学习/审批 → 提问 → 密钥/连接器 → 交接 → 卡片。
    public enum Kind: Equatable, Sendable {
        case learning
        case approval
        case question
        case secret
        case connector
        case handoff
        case view

        public var rank: Int {
            switch self {
            case .learning, .approval: return 0
            case .question: return 1
            case .secret, .connector: return 2
            case .handoff: return 3
            case .view: return 4
            }
        }

        /// 类型标签（按当前界面语言）。
        public var label: String {
            switch self {
            case .learning: return I18n.tr("学习审批", "Learning")
            case .approval: return I18n.tr("审批", "Approval")
            case .question: return I18n.tr("提问", "Question")
            case .secret: return I18n.tr("密钥请求", "Key request")
            case .connector: return I18n.tr("连接器请求", "Connector")
            case .handoff: return I18n.tr("浏览器交接", "Browser handoff")
            case .view: return I18n.tr("卡片", "Card")
            }
        }
    }

    /// 停靠区一项的数据（按类型）。
    public indirect enum Payload: Equatable, Sendable {
        public struct QuestionOption: Equatable, Sendable {
            public let label: String
            public let description: String?

            public init(label: String, description: String?) {
                self.label = label
                self.description = description
            }
        }

        public struct Question: Equatable, Sendable {
            public let sessionId: String
            public let id: String
            public let question: String
            public let detail: String?
            public let options: [QuestionOption]
            public let multiSelect: Bool
            public let allowCustom: Bool
        }

        public struct ApprovalEntry: Equatable, Sendable {
            public let id: String
            public let tool: String
            public let reason: String?
            public let taskTitle: String?
        }

        public struct SecretEntry: Equatable, Sendable {
            public let id: String
            public let name: String
            public let purpose: String?
            public let kind: String?
        }

        /// show_view 卡片；raw 是 state.views 的原始项（dsbot-rich-render 原生绘制用 ViewCard.decode）。
        public struct ViewEntry: Equatable, Sendable {
            public let sessionId: String
            public let id: String
            public let title: String?
            public let raw: JsonObject?
        }

        public struct HandoffEntry: Equatable, Sendable {
            public let botId: String
            public let callId: String
            public let note: String?
        }

        case question(Question)
        case approval(ApprovalEntry)
        case secret(SecretEntry)
        case view(ViewEntry)
        case handoff(HandoffEntry)
        case learning(proposal: LearningProposal)
        case connector(request: ConnectorRequest)
    }

    public struct Item: Equatable, Sendable {
        public let id: String
        public let kind: Kind
        public let title: String
        public let payload: Payload

        public init(id: String, kind: Kind, title: String, payload: Payload) {
            self.id = id
            self.kind = kind
            self.title = title
            self.payload = payload
        }
    }

    /// 从 state 汇集。sessionIds：当前聊天的会话 id（私聊为 Bot 当前分段；群聊为群会话与成员的
    /// 群内会话，即 rooms[].sessionId 与 parts）；botId 非空时为私聊（另汇集该 Bot 的密钥/连接器
    /// 请求、学习审批与浏览器交接），roomId 非空时为群聊（审批按 roomId 归属）。answered 中的 id
    /// 一律排除（作答成功、等待 state 刷新的过渡期）。
    public static func collect(
        _ state: JsonObject?,
        sessionIds: Set<String>,
        botId: String?,
        roomId: String?,
        answered: Set<String>
    ) -> [Item] {
        guard let state else { return [] }
        var out: [Item] = []
        if let botId {
            for p in learningOf(state, botId) {
                out.append(Item(
                    id: p.id,
                    kind: .learning,
                    title: p.name ?? p.path ?? I18n.tr("学习审批", "Learning approval"),
                    payload: .learning(proposal: p)
                ))
            }
        }
        for a in approvalsOf(state, botId: botId, roomId: roomId) {
            let title = a.taskTitle.map { "\($0) · \(a.tool)" } ?? (a.tool.isEmpty ? I18n.tr("工具审批", "Tool approval") : a.tool)
            out.append(Item(id: a.id, kind: .approval, title: title, payload: .approval(a)))
        }
        for q in questionsOf(state, sessionIds: sessionIds) {
            out.append(Item(id: q.id, kind: .question, title: q.question.isEmpty ? I18n.tr("提问", "Question") : q.question, payload: .question(q)))
        }
        if let botId {
            for r in secretsOf(state, botId: botId, sessionIds: sessionIds) {
                out.append(Item(id: r.id, kind: .secret, title: r.name, payload: .secret(r)))
            }
            for c in connectorsOf(state, botId: botId, sessionIds: sessionIds) {
                out.append(Item(id: c.id, kind: .connector, title: c.name ?? c.connectorId ?? I18n.tr("连接器", "Connector"), payload: .connector(request: c)))
            }
            // 同一聊天可先后发起多次交接：id 含 callId，作答过的那次不会挡住下一次
            if let h = handoffOf(state, botId: botId) {
                out.append(Item(id: "handoff:\(botId):\(h.callId)", kind: .handoff, title: h.note ?? I18n.tr("浏览器交接", "Browser handoff"), payload: .handoff(h)))
            }
        }
        for v in viewsOf(state, sessionIds: sessionIds) {
            out.append(Item(id: v.id, kind: .view, title: v.title ?? I18n.tr("卡片", "Card"), payload: .view(v)))
        }
        return out
            .enumerated()
            .filter { !answered.contains($1.id) }
            .sorted { a, b in
                // 同档保持来源顺序（序号 tiebreaker 固化稳定性语义，不依赖 sorted 实现细节）
                if a.element.kind.rank != b.element.kind.rank {
                    return a.element.kind.rank < b.element.kind.rank
                }
                return a.offset < b.offset
            }
            .map(\.element)
    }

    /// 默认展开项：第一项；空列表为 -1。
    public static func expandedIndex(_ items: [Item]) -> Int {
        items.isEmpty ? -1 : 0
    }

    /// live learning[botId] 的待审提案。
    public static func learningOf(_ state: JsonObject?, _ botId: String) -> [LearningProposal] {
        let raw = state?.object("learning")?[botId]?.arrayValue ?? []
        return raw.compactMap(LearningProposal.decode).prefix(listMax).map { $0 }
    }

    /// state connectorRequests[botId] 中待处理、且属于当前会话（有 sessionId 时）的请求。
    public static func connectorsOf(_ state: JsonObject?, botId: String, sessionIds: Set<String>) -> [ConnectorRequest] {
        let raw = state?.object("connectorRequests")?[botId]?.arrayValue ?? []
        return raw.compactMap(ConnectorRequest.decode)
            .filter { $0.status == "pending" && ($0.sessionId == nil || sessionIds.contains($0.sessionId!)) }
            .prefix(listMax).map { $0 }
    }

    /// live work.approvals：私聊取该 Bot 不在群里的、群聊取 roomId 匹配的；answerable=false 的不计
    ///（无法在此作答）。
    public static func approvalsOf(_ state: JsonObject?, botId: String?, roomId: String?) -> [Payload.ApprovalEntry] {
        let raw = state?.object("work")?["approvals"]?.arrayValue ?? []
        return raw.compactMap { v -> Payload.ApprovalEntry? in
            guard let o = v.objectValue, let id = o.nonEmptyString("id") else { return nil }
            if o["answerable"]?.boolValue == false { return nil }
            let belongs: Bool
            if let roomId {
                belongs = o.string("roomId") == roomId
            } else {
                belongs = botId != nil && o.string("botId") == botId && o.string("roomId") == nil
            }
            guard belongs else { return nil }
            return Payload.ApprovalEntry(id: id, tool: o.string("tool") ?? "", reason: nonBlank(o.string("reason")), taskTitle: o.string("taskTitle"))
        }
        .prefix(listMax).map { $0 }
    }

    /// state questions{sessionId → 提问}：只取当前聊天会话的。
    public static func questionsOf(_ state: JsonObject?, sessionIds: Set<String>) -> [Payload.Question] {
        guard let questions = state?.object("questions") else { return [] }
        var out: [Payload.Question] = []
        for (sid, v) in questions.entries {
            guard sessionIds.contains(sid), let o = v.objectValue, let id = o.nonEmptyString("id") else { continue }
            let options = (o.array("options") ?? []).compactMap { opt -> Payload.QuestionOption? in
                guard let oo = opt.objectValue, let label = oo.nonEmptyString("label") else { return nil }
                return Payload.QuestionOption(label: label, description: oo.string("description"))
            }
            out.append(Payload.Question(
                sessionId: sid,
                id: id,
                question: clip(o.string("question") ?? "", 300),
                detail: o.string("detail"),
                options: Array(options.prefix(listMax)),
                multiSelect: o.bool("multiSelect") ?? false,
                allowCustom: o.bool("allowCustom") ?? false
            ))
        }
        return out
    }

    /// state views{sessionId → 卡片}：只取当前聊天会话的。
    public static func viewsOf(_ state: JsonObject?, sessionIds: Set<String>) -> [Payload.ViewEntry] {
        guard let views = state?.object("views") else { return [] }
        var out: [Payload.ViewEntry] = []
        for (sid, v) in views.entries {
            guard sessionIds.contains(sid), let o = v.objectValue, let id = o.nonEmptyString("id") else { continue }
            let title = nonBlank(o.string("title")).map { clip($0, titleMax) }
            out.append(Payload.ViewEntry(sessionId: sid, id: id, title: title, raw: o))
        }
        return out
    }

    /// state secretRequests[botId] 中待处理、且属于当前会话（有 sessionId 时）的请求。
    public static func secretsOf(_ state: JsonObject?, botId: String, sessionIds: Set<String>) -> [Payload.SecretEntry] {
        let raw = state?.object("secretRequests")?[botId]?.arrayValue ?? []
        return raw.compactMap { v -> Payload.SecretEntry? in
            guard let o = v.objectValue, let id = o.nonEmptyString("id") else { return nil }
            guard o.string("status") == "pending" else { return nil }
            if let sid = o.string("sessionId"), !sessionIds.contains(sid) { return nil }
            return Payload.SecretEntry(
                id: id,
                name: o.string("name") ?? I18n.tr("密钥", "Key"),
                purpose: nonBlank(o.string("purpose")),
                kind: o.string("kind")
            )
        }
        .prefix(listMax).map { $0 }
    }

    /// live browserHolds[botId]：Bot 发起的交接（带 callId）才需要用户处理。
    public static func handoffOf(_ state: JsonObject?, botId: String) -> Payload.HandoffEntry? {
        guard let hold = state?.object("browserHolds")?.object(botId),
              let callId = hold.nonEmptyString("callId")
        else { return nil }
        let note = nonBlank(hold.string("note") ?? hold.string("reason")).map { clip($0, titleMax) }
        return Payload.HandoffEntry(botId: botId, callId: callId, note: note)
    }

    /// 作答请求体（DS-Bot 0.3.10 channel.js 的参数，源码核对）。
    public enum Requests {
        public static func approval(_ id: String, _ allow: Bool) -> String {
            "{\"id\":\(JsonString.of(id)),\"choice\":\"\(allow ? "allow" : "reject")\"}"
        }

        public static func answerQuestion(sessionId: String, questionId: String, selected: [String], custom: String? = nil) -> String {
            let sel = selected.map(JsonString.of).joined(separator: ",")
            var cus = ""
            if let trimmed = custom?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty {
                cus = ",\"custom\":\(JsonString.of(trimmed))"
            }
            return "{\"sessionId\":\(JsonString.of(sessionId)),\"questionId\":\(JsonString.of(questionId)),\"answer\":{\"selected\":[\(sel)]\(cus)}}"
        }

        public static func dismissQuestion(_ sessionId: String) -> String {
            "{\"sessionId\":\(JsonString.of(sessionId))}"
        }

        public static func dismissView(_ sessionId: String) -> String {
            "{\"sessionId\":\(JsonString.of(sessionId))}"
        }

        public static func secretSet(requestId: String, value: String) -> String {
            "{\"requestId\":\(JsonString.of(requestId)),\"value\":\(JsonString.of(value))}"
        }

        public static func secretAllow(_ requestId: String) -> String {
            "{\"requestId\":\(JsonString.of(requestId))}"
        }

        public static func secretCancel(_ requestId: String) -> String {
            "{\"requestId\":\(JsonString.of(requestId))}"
        }

        public static func learning(_ id: String, _ approve: Bool) -> String {
            "{\"id\":\(JsonString.of(id)),\"approve\":\(approve)}"
        }

        public static func connectorCancel(_ requestId: String) -> String {
            "{\"requestId\":\(JsonString.of(requestId))}"
        }

        /// 带 callId：上游据此判过期（交接已换成新的一次时回 bot/stale），不会误交还新的那次。
        public static func giveBack(botId: String, callId: String, done: Bool) -> String {
            "{\"botId\":\(JsonString.of(botId)),\"callId\":\(JsonString.of(callId)),\"outcome\":\"\(done ? "done" : "skip")\"}"
        }

        public static func btwDelete(sessionId: String, cardId: String) -> String {
            "{\"sessionId\":\(JsonString.of(sessionId)),\"cardId\":\(JsonString.of(cardId))}"
        }
    }

    /// 截断到 max（UTF-16 码元，与 Kotlin String.length 一致；超出以 … 结尾，不切断代理对）。
    static func clip(_ s: String, _ max: Int) -> String {
        I18n.clip(s, maxUTF16: max)
    }

    static func nonBlank(_ s: String?) -> String? {
        guard let s, !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return s
    }
}

/// 连接器请求（connectors/index.js request_connector，源码核对）。
public struct ConnectorRequest: Equatable, Sendable {
    public let id: String
    public let connectorId: String?
    public let name: String?
    public let purpose: String?
    public let status: String?
    public let sessionId: String?

    public static func decode(_ value: JsonValue?) -> ConnectorRequest? {
        guard let o = value?.objectValue, let id = o.nonEmptyString("id") else { return nil }
        return ConnectorRequest(
            id: id,
            connectorId: o.string("connectorId"),
            name: o.string("name"),
            purpose: o.string("purpose"),
            status: o.string("status"),
            sessionId: o.string("sessionId")
        )
    }
}

/// 学习提案（learning.js reviewSkill / reviewAgents，源码核对）：kind=skill 带 name/description/content，
/// 更新已有技能时另带 previous.content；kind=agents 带 path 与 edits[{op, old, text, why}]。
public struct LearningProposal: Equatable, Sendable {
    public struct Edit: Equatable, Sendable {
        public let op: String?
        public let old: String?
        public let text: String?
        public let why: String?

        public init(op: String?, old: String?, text: String?, why: String?) {
            self.op = op
            self.old = old
            self.text = text
            self.why = why
        }
    }

    public let id: String
    public let kind: String?
    public let action: String?
    public let name: String?
    public let description: String?
    public let why: String?
    public let content: String?
    public let previousContent: String?
    public let path: String?
    public let edits: [Edit]

    public init(
        id: String, kind: String?, action: String?, name: String?, description: String?, why: String?,
        content: String?, previousContent: String?, path: String?, edits: [Edit]
    ) {
        self.id = id
        self.kind = kind
        self.action = action
        self.name = name
        self.description = description
        self.why = why
        self.content = content
        self.previousContent = previousContent
        self.path = path
        self.edits = edits
    }

    /// 要显示的差异行：技能更新对比原内容、新建技能全部新增、AGENTS.md 逐条删除/新增。
    public func lines() -> [LineDiff.Line] {
        if kind == "agents" {
            return edits.flatMap { e in
                LineDiff.split(e.old).map { LineDiff.Line(op: .del, text: $0) }
                    + LineDiff.split(e.text).map { LineDiff.Line(op: .add, text: $0) }
            }
        }
        if let previousContent {
            return LineDiff.diff(previousContent, content ?? "")
        }
        return LineDiff.split(content).map { LineDiff.Line(op: .add, text: $0) }
    }

    public static func decode(_ value: JsonValue?) -> LearningProposal? {
        guard let o = value?.objectValue, let id = o.nonEmptyString("id") else { return nil }
        let edits = (o.array("edits") ?? []).compactMap { e -> Edit? in
            guard let eo = e.objectValue else { return nil }
            return Edit(op: eo.string("op"), old: eo.string("old"), text: eo.string("text"), why: eo.string("why"))
        }
        return LearningProposal(
            id: id,
            kind: o.string("kind"),
            action: o.string("action"),
            name: o.string("name"),
            description: o.string("description"),
            why: o.string("why"),
            content: o.string("content"),
            previousContent: o.object("previous")?.string("content"),
            path: o.string("path"),
            edits: Array(edits.prefix(PendingDock.listMax))
        )
    }
}

/// 逐行差异（dsbot-pending-dock 学习审批）：LCS 求最长公共行序列，其余为删除/新增。
public enum LineDiff {
    public enum Op: Equatable, Sendable {
        case same
        case add
        case del
    }

    public struct Line: Equatable, Sendable {
        public let op: Op
        public let text: String

        public init(op: Op, text: String) {
            self.op = op
            self.text = text
        }
    }

    /// 折叠结果：shown 为显示的前若干行，hidden 为折叠掉的行数。
    public struct Folded: Equatable, Sendable {
        public let shown: [Line]
        public let hidden: Int

        public init(shown: [Line], hidden: Int) {
            self.shown = shown
            self.hidden = hidden
        }
    }

    /// 超过该行数时折叠。
    public static let foldAt = 8

    /// 规模上限：超过时按「全部删除 + 全部新增」降级，避免 O(n·m) 爆内存。
    public static let maxLines = 400

    /// 按行切分；结尾换行不产生空行（previous.content 常以换行结尾而新内容被上游 trim）。
    public static func split(_ text: String?) -> [String] {
        guard let text, !text.isEmpty else { return [] }
        var s = text.replacingOccurrences(of: "\r\n", with: "\n")
        if s.hasSuffix("\n") { s.removeLast() }
        if s.isEmpty { return [] }
        return s.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
    }

    public static func diff(_ old: String, _ new: String) -> [Line] {
        let a = split(old)
        let b = split(new)
        if a.count > maxLines || b.count > maxLines {
            return a.map { Line(op: .del, text: $0) } + b.map { Line(op: .add, text: $0) }
        }
        // lcs[i][j] = a[i..] 与 b[j..] 的最长公共子序列长度
        var lcs = [[Int]](repeating: [Int](repeating: 0, count: b.count + 1), count: a.count + 1)
        for i in stride(from: a.count - 1, through: 0, by: -1) {
            for j in stride(from: b.count - 1, through: 0, by: -1) {
                lcs[i][j] = a[i] == b[j] ? lcs[i + 1][j + 1] + 1 : max(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        var out: [Line] = []
        var i = 0
        var j = 0
        while i < a.count && j < b.count {
            if a[i] == b[j] {
                out.append(Line(op: .same, text: a[i]))
                i += 1
                j += 1
            } else if lcs[i + 1][j] >= lcs[i][j + 1] {
                out.append(Line(op: .del, text: a[i]))
                i += 1
            } else {
                out.append(Line(op: .add, text: b[j]))
                j += 1
            }
        }
        while i < a.count {
            out.append(Line(op: .del, text: a[i]))
            i += 1
        }
        while j < b.count {
            out.append(Line(op: .add, text: b[j]))
            j += 1
        }
        return out
    }

    public static func fold(_ lines: [Line], max: Int = foldAt) -> Folded {
        if lines.count <= max { return Folded(shown: lines, hidden: 0) }
        return Folded(shown: Array(lines.prefix(max)), hidden: lines.count - max)
    }
}
