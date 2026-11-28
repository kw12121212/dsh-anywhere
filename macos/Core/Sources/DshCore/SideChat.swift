import Foundation

/// Side chat 旁路提问请求构造与 `state.sideChats` 解码（与 `SideChat.kt` 对齐）。
public enum SideChat: Sendable {
    public struct SideSession: Equatable, Sendable {
        public let id: String
        public let entries: [DsbotModels.ChatItem]

        public init(id: String, entries: [DsbotModels.ChatItem]) {
            self.id = id
            self.entries = entries
        }
    }

    public static func newSide(sessionId: String) -> String {
        "{\"sessionId\":\(JsonString.of(sessionId))}"
    }

    public static func ask(sessionId: String, text: String, snapshotJson: String? = nil) -> String {
        if let snapshotJson {
            return "{\"sessionId\":\(JsonString.of(sessionId)),\"text\":\(JsonString.of(text)),\"snapshot\":\(snapshotJson)}"
        }
        return "{\"sessionId\":\(JsonString.of(sessionId)),\"text\":\(JsonString.of(text))}"
    }

    public static func cancel(sideId: String) -> String {
        "{\"sideId\":\(JsonString.of(sideId))}"
    }

    public static func close(sideId: String) -> String {
        "{\"sideId\":\(JsonString.of(sideId))}"
    }

    public static func btwDelete(id: String) -> String {
        "{\"id\":\(JsonString.of(id))}"
    }

    public static func decodeChatId(_ value: JsonValue) -> String? {
        value.objectValue?.string("chatId")
    }

    public static func decodeSideSessions(_ state: JsonObject) -> [SideSession] {
        guard let sideChats = state.object("sideChats") else { return [] }
        var out: [SideSession] = []
        for (_, arrVal) in sideChats.entries {
            guard let sessions = arrVal.arrayValue else { continue }
            for s in sessions {
                guard let o = s.objectValue, let id = o.string("id") else { continue }
                let entries = (o.array("entries") ?? []).map { item -> DsbotModels.ChatItem in
                    let io = item.objectValue
                    return DsbotModels.ChatItem(
                        kind: io?.string("kind"),
                        text: io?.string("text"),
                        time: io?.int64("time"),
                        seq: io?.int64("seq"),
                        turn: io?.int64("turn"),
                        raw: item
                    )
                }
                out.append(SideSession(id: id, entries: entries))
            }
        }
        return out
    }

    /// 旁路问答对（tech dsbot-api 实证形态：条目级 {id, question, answer, status: done|working}；
    /// 旧形态 kind/text 降级回退——0.3.6- 与既有夹具）。
    public struct QA: Equatable, Sendable {
        public let id: String
        public let question: String
        public let answer: String?
        public let working: Bool

        public init(id: String, question: String, answer: String?, working: Bool) {
            self.id = id
            self.question = question
            self.answer = answer
            self.working = working
        }
    }

    /// state.sideChats[botId] 的条目级问答解码（每条目一问一答；question/answer 缺失时回退
    /// entries[].kind/text 的 user/bot 文本；两形态都无的条目跳过）。
    public static func decodeQA(_ sessions: [JsonValue]) -> [QA] {
        var out: [QA] = []
        for s in sessions {
            guard let session = s.objectValue else { continue }
            let sessionEntries = session.array("entries") ?? []
            // 实证形态：会话对象本身即一个问答条目（0.3.5 探针：side-ask 每问新建会话）。
            if let question = session.nonEmptyString("question") {
                out.append(QA(
                    id: session.nonEmptyString("id") ?? question,
                    question: question,
                    answer: session.nonEmptyString("answer"),
                    working: session.string("status") == "working"
                ))
                continue
            }
            // 降级形态：entries[] 为聊天条目（kind/text）。
            let questionText = sessionEntries.first { $0.objectValue?.string("kind") == "user" }?.objectValue?.nonEmptyString("text")
            let answerText = sessionEntries.first { $0.objectValue?.string("kind") == "bot" }?.objectValue?.nonEmptyString("text")
            if let questionText {
                out.append(QA(
                    id: session.nonEmptyString("id") ?? questionText,
                    question: questionText,
                    answer: answerText,
                    working: session.string("status") == "working"
                ))
            }
        }
        return out
    }

    public static func snapshotOf(_ messages: [(fromUser: Bool, text: String)], max: Int = 5) -> String {
        let tail = max > 0 ? Array(messages.suffix(max)) : []
        let serialized = tail.map { item in
            let role = item.fromUser ? "user" : "assistant"
            return "{\"role\":\(JsonString.of(role)),\"text\":\(JsonString.of(item.text))}"
        }
        return "[\(serialized.joined(separator: ","))]"
    }
}
