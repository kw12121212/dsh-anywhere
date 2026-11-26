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

    public static func snapshotOf(_ messages: [(fromUser: Bool, text: String)], max: Int = 5) -> String {
        let tail = max > 0 ? Array(messages.suffix(max)) : []
        let serialized = tail.map { item in
            let role = item.fromUser ? "user" : "assistant"
            return "{\"role\":\(JsonString.of(role)),\"text\":\(JsonString.of(item.text))}"
        }
        return "[\(serialized.joined(separator: ","))]"
    }
}
