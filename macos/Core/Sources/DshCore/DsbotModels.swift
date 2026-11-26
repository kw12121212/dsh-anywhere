import Foundation

/// DS-Bot state/chat 响应的可降级解码模型（与 `DsbotModels.kt` 对齐，未知与变形字段保留在 `raw`）。
public enum DsbotModels: Sendable {
    public static func modelsKey(_ raw: JsonObject?) -> String? {
        raw?.string("modelsKey")
    }

    /// 构造 `state` 请求参数 JSON；没有任何参数时返回 `"{}"`。
    public static func statePayload(since: String?, live: String?, models: String?) -> String {
        var parts: [String] = []
        if let since {
            let isIntToken = !since.isEmpty && since.allSatisfy { ($0 >= "0" && $0 <= "9") || $0 == "-" }
            parts.append("\"since\":" + (isIntToken ? since : JsonString.of(since)))
        }
        if let live {
            parts.append("\"live\":" + JsonString.of(live))
        }
        if since != nil, let models {
            parts.append("\"models\":" + JsonString.of(models))
        }
        return "{\(parts.joined(separator: ","))}"
    }

    public struct State: Equatable, Sendable {
        public let revision: String
        public let liveKey: String?
        public let mainBotIds: [String]
        public let bots: [Bot]
        public let rooms: [Room]
        public let raw: JsonObject

        public init(
            revision: String,
            liveKey: String?,
            mainBotIds: [String],
            bots: [Bot],
            rooms: [Room],
            raw: JsonObject
        ) {
            self.revision = revision
            self.liveKey = liveKey
            self.mainBotIds = mainBotIds
            self.bots = bots
            self.rooms = rooms
            self.raw = raw
        }
    }

    public struct Bot: Equatable, Sendable {
        public let id: String
        public let sessionId: String?
        public let parts: [String]
        public let waiting: Bool
        public let raw: JsonObject

        public init(id: String, sessionId: String?, parts: [String], waiting: Bool, raw: JsonObject) {
            self.id = id
            self.sessionId = sessionId
            self.parts = parts
            self.waiting = waiting
            self.raw = raw
        }
    }

    public struct Room: Equatable, Sendable {
        public let id: String
        public let sessionId: String?
        public let members: [String]
        public let raw: JsonObject

        public init(id: String, sessionId: String?, members: [String], raw: JsonObject) {
            self.id = id
            self.sessionId = sessionId
            self.members = members
            self.raw = raw
        }
    }

    public struct StateDelta: Equatable, Sendable {
        public let revision: String?
        public let unchanged: Bool
        public let liveKey: String?
        public let hasLiveFields: Bool
        public let raw: JsonObject

        public init(
            revision: String?,
            unchanged: Bool,
            liveKey: String?,
            hasLiveFields: Bool,
            raw: JsonObject
        ) {
            self.revision = revision
            self.unchanged = unchanged
            self.liveKey = liveKey
            self.hasLiveFields = hasLiveFields
            self.raw = raw
        }
    }

    public struct ChatPage: Equatable, Sendable {
        public let sessionId: String?
        public let items: [ChatItem]
        public let cursor: JsonValue?

        public init(sessionId: String?, items: [ChatItem], cursor: JsonValue?) {
            self.sessionId = sessionId
            self.items = items
            self.cursor = cursor
        }
    }

    public struct ChatItem: Equatable, Sendable {
        public let kind: String?
        public let text: String?
        public let time: Int64?
        public let seq: Int64?
        public let turn: Int64?
        public let raw: JsonValue?

        public init(
            kind: String?,
            text: String?,
            time: Int64?,
            seq: Int64?,
            turn: Int64?,
            raw: JsonValue?
        ) {
            self.kind = kind
            self.text = text
            self.time = time
            self.seq = seq
            self.turn = turn
            self.raw = raw
        }
    }

    /// 全量视图解码：必须含 `bots` 键与 `revision`（避免把 `unchanged` 增量当空全量）。
    public static func decodeState(_ value: JsonValue) -> State? {
        guard let obj = value.objectValue,
              obj.contains("bots"),
              let revision = obj.scalarString("revision")
        else {
            return nil
        }
        return State(
            revision: revision,
            liveKey: obj.string("liveKey"),
            mainBotIds: obj.stringList("mainBotIds"),
            bots: obj.array("bots")?.compactMap(decodeBot) ?? [],
            rooms: obj.array("rooms")?.compactMap(decodeRoom) ?? [],
            raw: obj
        )
    }

    private static func decodeBot(_ value: JsonValue) -> Bot? {
        guard let obj = value.objectValue, let id = obj.string("id") else { return nil }
        return Bot(
            id: id,
            sessionId: obj.string("sessionId"),
            parts: obj.stringList("parts"),
            waiting: obj.bool("waiting") ?? false,
            raw: obj
        )
    }

    private static func decodeRoom(_ value: JsonValue) -> Room? {
        guard let obj = value.objectValue, let id = obj.string("id") else { return nil }
        return Room(
            id: id,
            sessionId: obj.string("sessionId"),
            members: obj.stringList("members"),
            raw: obj
        )
    }

    /// 增量应答解码：缺失 `unchanged` 布尔键返回 `nil`。
    public static func decodeStateDelta(_ value: JsonValue) -> StateDelta? {
        guard let obj = value.objectValue, let unchanged = obj.bool("unchanged") else {
            return nil
        }
        let metaKeys: Set<String> = ["revision", "unchanged", "liveKey"]
        let hasLive = obj.keys.contains { !metaKeys.contains($0) }
        return StateDelta(
            revision: obj.scalarString("revision"),
            unchanged: unchanged,
            liveKey: obj.string("liveKey"),
            hasLiveFields: hasLive,
            raw: obj
        )
    }

    public static func decodeChatPage(_ value: JsonValue) -> ChatPage {
        let obj = value.objectValue
        let rawItems = obj?.array("items") ?? []
        let items = rawItems.map { item in
            let o = item.objectValue
            return ChatItem(
                kind: o?.string("kind"),
                text: o?.string("text"),
                time: o?.int64("time"),
                seq: o?.int64("seq"),
                turn: o?.int64("turn"),
                raw: item
            )
        }
        let cursor = obj?["cursor"].flatMap { $0 == .null ? nil : $0 }
        return ChatPage(
            sessionId: obj?.string("sessionId"),
            items: items,
            cursor: cursor
        )
    }

    /// 将增量应答中的 live 字段合并进全量 `State`；无 live 字段时原样返回。
    public static func mergeDelta(state: State, delta: StateDelta) -> State {
        guard delta.hasLiveFields else { return state }
        var merged = state.raw
        for (k, v) in delta.raw.entries where k != "revision" && k != "unchanged" && k != "liveKey" {
            merged.set(k, v)
        }
        return State(
            revision: delta.revision ?? state.revision,
            liveKey: delta.liveKey ?? state.liveKey,
            mainBotIds: state.mainBotIds,
            bots: state.bots,
            rooms: state.rooms,
            raw: merged
        )
    }
}
