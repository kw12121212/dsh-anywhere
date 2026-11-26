import Foundation

/// 交互卡片解码、上限校验、只读降级与作答请求体构造（与 `Cards.kt` 对齐）。
public enum Cards: Sendable {
    public static let maxDepth = 16
    public static let maxItems = 64
    public static let maxChars = 64_000

    public static var readOnlyHint: String {
        I18n.tr("待处理项请在输入框上方的待处理区或网页端处理", "Handle pending items in the panel above the input box or in the web app")
    }

    public enum Card: Equatable, Sendable {
        public struct QuestionOption: Equatable, Sendable {
            public let label: String
            public let description: String?

            public init(label: String, description: String? = nil) {
                self.label = label
                self.description = description
            }
        }

        public enum ViewSection: Equatable, Sendable {
            case text(String)
            case metric(label: String, value: String)
            case table(header: [String], rows: [[String]])
            case formula(name: String, placeholder: String?)
            case progress(label: String?, percent: Int)
            case choice(name: String, options: [String])
            case confirm(String)
        }

        case readOnly(title: String?, body: String)
        case approval(callId: String, title: String?, detail: String?)
        case question(callId: String, title: String?, options: [QuestionOption])
        case view(callId: String, title: String?, sections: [ViewSection])
        case secretRequest(callId: String, title: String?, hint: String?)
        case learning(callId: String, title: String?, options: [String])
        case browserHandoff(callId: String, title: String?)
        case sandboxHtml(callId: String, title: String?, html: String)
    }

    public static func fromChatItem(_ raw: DsbotModels.ChatItem) -> Card? {
        guard let obj = raw.raw?.objectValue,
              let kind = raw.kind,
              kind == "answer" || kind == "closed"
        else {
            return nil
        }
        if kind == "answer" && (obj.contains("answers") || obj.contains("handoff") || obj.contains("views")) {
            return answeredRecord(obj)
        }
        let nested = obj.object("question")
            ?? obj.array("questions")?.first?.objectValue
            ?? obj.object("view")
            ?? obj.object("secret")
        if kind == "closed" {
            return nested.map {
                .readOnly(title: $0.nonEmptyString("question") ?? $0.nonEmptyString("title"), body: I18n.tr("已处理", "Handled"))
            }
        }
        if let nested {
            let card = parse(nested)
            let extra = (obj.array("questions")?.count ?? 0) - 1
            if extra > 0, card != nil {
                return .readOnly(
                    title: I18n.tr("本条含 \(extra + 1) 个待办问题", "This entry has \(extra + 1) pending questions"),
                    body: I18n.tr("请在网页端逐个处理（原生端暂不支持多问题条目作答）", "Answer them one by one in the web app (multi-question entries are not supported here yet)")
                )
            }
            return card
        }
        if obj.contains("callId") {
            return parse(obj)
        }
        return nil
    }

    public static func asReadOnly(_ card: Card) -> Card {
        switch card {
        case .readOnly, .sandboxHtml:
            return card
        case .approval(_, let title, _):
            return .readOnly(title: title ?? I18n.tr("审批", "Approval"), body: readOnlyHint)
        case .question(_, let title, _):
            return .readOnly(title: title ?? I18n.tr("提问", "Question"), body: readOnlyHint)
        case .view(_, let title, _):
            return .readOnly(title: title ?? I18n.tr("卡片", "Card"), body: readOnlyHint)
        case .secretRequest(_, let title, _):
            return .readOnly(title: title ?? I18n.tr("密钥请求", "Key request"), body: readOnlyHint)
        case .learning(_, let title, _):
            return .readOnly(title: title ?? I18n.tr("学习审批", "Learning approval"), body: readOnlyHint)
        case .browserHandoff(_, let title):
            return .readOnly(title: title ?? I18n.tr("浏览器交接", "Browser handoff"), body: readOnlyHint)
        }
    }

    private static func answeredRecord(_ obj: JsonObject) -> Card {
        let questions = obj.array("questions") ?? []
        let firstQuestion = questions.first?.objectValue?.nonEmptyString("question").map { q in
            questions.count > 1 ? I18n.tr("\(q)（共 \(questions.count) 个问题）", "\(q) (\(questions.count) questions)") : q
        }
        let answer = obj.array("answers")?.first?.objectValue
        var picked = (answer?.array("selected")?.compactMap(\.stringValue)) ?? []
        if let custom = answer?.nonEmptyString("custom") {
            picked.append(custom)
        }
        let title = firstQuestion.map { I18n.tr("已回答：\($0)", "Answered: \($0)") }
            ?? obj.nonEmptyString("text").map { _ in I18n.tr("已回复", "Replied") }
            ?? I18n.tr("已回复", "Replied")
        let joined = picked.joined(separator: "、")
        let body = !joined.isEmpty ? joined : (obj.nonEmptyString("text") ?? I18n.tr("已处理", "Handled"))
        return .readOnly(title: title, body: String(body.prefix(maxChars)))
    }

    public static func fromStateEntry(_ value: JsonValue) -> Card? {
        value.objectValue.flatMap(parse)
    }

    public static func parse(_ obj: JsonObject) -> Card? {
        if Json.encode(.object(obj)).count > maxChars {
            return .readOnly(title: nil, body: I18n.tr("卡片内容过大，已折叠", "Card is too large and was collapsed"))
        }
        guard let callId = obj.nonEmptyString("callId") ?? obj.nonEmptyString("id") else {
            return nil
        }
        let outerWrapperKinds: Set<String> = ["answer", "closed", "event"]
        let rawKind = obj.nonEmptyString("kind") ?? obj.nonEmptyString("type")
        let innerType = obj.nonEmptyString("type")
        let kind: String?
        if let innerType, !outerWrapperKinds.contains(innerType) {
            kind = innerType
        } else if let rawKind, outerWrapperKinds.contains(rawKind) {
            kind = (obj.contains("options") || obj.contains("question")) ? "question" : nil
        } else {
            kind = rawKind ?? ((obj.contains("options") || obj.contains("question")) ? "question" : nil)
        }

        switch kind {
        case "approval":
            return .approval(callId: callId, title: obj.nonEmptyString("title"), detail: obj.nonEmptyString("detail") ?? obj.nonEmptyString("why"))
        case "question", "ask_user":
            return parseQuestion(callId: callId, obj: obj)
        case "view", "show_view":
            return parseView(callId: callId, obj: obj)
        case "secret", "secret-request":
            return .secretRequest(callId: callId, title: obj.nonEmptyString("title"), hint: obj.nonEmptyString("hint"))
        case "learning":
            let options = nonEmptyStrList(obj, "options")
            if options.isEmpty {
                return .readOnly(title: obj.nonEmptyString("title"), body: String(Json.encode(.object(obj)).prefix(400)))
            }
            return .learning(callId: callId, title: obj.nonEmptyString("title"), options: Array(options.prefix(maxItems)))
        case "browser-handoff", "browser":
            return .browserHandoff(callId: callId, title: obj.nonEmptyString("title"))
        case "html":
            guard let html = obj.nonEmptyString("html") else {
                return .readOnly(title: obj.nonEmptyString("title"), body: I18n.tr("空 HTML 卡", "Empty HTML card"))
            }
            return .sandboxHtml(callId: callId, title: obj.nonEmptyString("title"), html: html)
        default:
            return nil
        }
    }

    private static func parseQuestion(callId: String, obj: JsonObject) -> Card {
        let title = obj.nonEmptyString("question") ?? obj.nonEmptyString("title")
        let options = (obj.array("options") ?? []).compactMap { item -> Card.QuestionOption? in
            guard let o = item.objectValue, let label = o.nonEmptyString("label") else { return nil }
            return Card.QuestionOption(label: label, description: o.nonEmptyString("description"))
        }
        if options.isEmpty {
            return .readOnly(title: title, body: String(Json.encode(.object(obj)).prefix(400)))
        }
        return .question(callId: callId, title: title, options: Array(options.prefix(maxItems)))
    }

    private static func parseView(callId: String, obj: JsonObject) -> Card {
        let title = obj.nonEmptyString("title") ?? obj.nonEmptyString("question")
        var sections: [Card.ViewSection] = []
        var depth = 0

        func walk(_ node: JsonObject) {
            depth += 1
            if depth > maxDepth || sections.count >= maxItems {
                depth -= 1
                return
            }
            for (key, value) in node.entries {
                if sections.count >= maxItems { break }
                switch key {
                case "text", "note":
                    if let s = value.stringValue {
                        sections.append(.text(s))
                    }
                case "metrics":
                    if let m = value.objectValue {
                        for (mk, mv) in m.entries {
                            if let s = mv.stringValue {
                                sections.append(.metric(label: mk, value: s))
                            }
                        }
                    }
                case "table":
                    if let t = value.objectValue {
                        let header = nonEmptyStrList(t, "header")
                        let rows = (t.array("rows") ?? []).compactMap { rowVal -> [String]? in
                            rowVal.arrayValue?.compactMap(\.stringValue)
                        }
                        if !header.isEmpty {
                            sections.append(.table(header: header, rows: Array(rows.prefix(maxItems))))
                        }
                    }
                case "formula", "input":
                    if let f = value.objectValue, let name = f.nonEmptyString("name") {
                        sections.append(.formula(name: name, placeholder: f.nonEmptyString("placeholder")))
                    }
                case "progress":
                    if let pr = value.objectValue {
                        let rawPct = pr.int("percent") ?? 0
                        let clamped = min(max(rawPct, 0), 100)
                        sections.append(.progress(label: pr.nonEmptyString("label"), percent: clamped))
                    }
                case "choice":
                    if let c = value.objectValue, let name = c.nonEmptyString("name") {
                        let opts = nonEmptyStrList(c, "options")
                        if !opts.isEmpty {
                            sections.append(.choice(name: name, options: Array(opts.prefix(maxItems))))
                        }
                    }
                case "confirm":
                    if let s = value.stringValue {
                        sections.append(.confirm(s))
                    }
                case "sections":
                    if let arr = value.arrayValue {
                        for sub in arr {
                            if let subObj = sub.objectValue {
                                walk(subObj)
                            }
                        }
                    }
                default:
                    break
                }
            }
            depth -= 1
        }

        walk(obj)
        if sections.isEmpty {
            return .readOnly(title: title, body: String(Json.encode(.object(obj)).prefix(400)))
        }
        return .view(callId: callId, title: title, sections: sections)
    }

    // MARK: - 作答 payload 构造

    public static func approvalAnswer(callId: String, approved: Bool) -> String {
        "{\"callId\":\(JsonString.of(callId)),\"approved\":\(approved)}"
    }

    public static func answerQuestion(callId: String, choice: Int) -> String {
        "{\"callId\":\(JsonString.of(callId)),\"choice\":\(choice)}"
    }

    public static func dismiss(callId: String) -> String {
        "{\"callId\":\(JsonString.of(callId))}"
    }

    public static func viewReply(callId: String, valuesJson: String) -> String {
        "{\"callId\":\(JsonString.of(callId)),\"values\":\(valuesJson)}"
    }

    public static func secretSet(callId: String, value: String) -> String {
        "{\"callId\":\(JsonString.of(callId)),\"value\":\(JsonString.of(value))}"
    }

    public static func learningAnswer(callId: String, choice: Int) -> String {
        "{\"callId\":\(JsonString.of(callId)),\"choice\":\(choice)}"
    }

    public static func browserGiveBack(callId: String, result: String) -> String {
        "{\"callId\":\(JsonString.of(callId)),\"result\":\(JsonString.of(result))}"
    }

    private static func nonEmptyStrList(_ obj: JsonObject, _ key: String) -> [String] {
        (obj.array(key) ?? []).compactMap { val in
            guard let s = val.stringValue, !s.isEmpty else { return nil }
            return s
        }
    }
}
