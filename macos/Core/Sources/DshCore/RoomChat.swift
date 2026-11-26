import Foundation

/// 群聊解码、成员名册归属与 0.3.10 轮次事件渲染（与 `RoomChat.kt` 对齐）。
public enum RoomChat: Sendable {
    public struct RoomPage: Equatable, Sendable {
        public let roomId: String?
        public let items: [DsbotModels.ChatItem]
        public let running: [String]
        public let speaking: [String]
        public let cursor: JsonValue?

        public init(
            roomId: String?,
            items: [DsbotModels.ChatItem],
            running: [String],
            speaking: [String],
            cursor: JsonValue?
        ) {
            self.roomId = roomId
            self.items = items
            self.running = running
            self.speaking = speaking
            self.cursor = cursor
        }
    }

    public struct Speaker: Equatable, Sendable {
        public let botId: String?
        public let name: String?
        public let color: String?

        public init(botId: String?, name: String?, color: String?) {
            self.botId = botId
            self.name = name
            self.color = color
        }
    }

    public static func decodeRoomPage(_ value: JsonValue) -> RoomPage {
        let obj = value.objectValue
        let rawItems = obj?.array("items") ?? []
        let items = rawItems.map { item in
            let o = item.objectValue
            return DsbotModels.ChatItem(
                kind: o?.nonEmptyString("kind"),
                text: o?.nonEmptyString("text"),
                time: o?.int64("time"),
                seq: o?.int64("seq"),
                turn: o?.int64("turn"),
                raw: item
            )
        }
        let cursor = obj?["cursor"].flatMap { $0 == .null ? nil : $0 }
        return RoomPage(
            roomId: obj?.nonEmptyString("roomId"),
            items: items,
            running: runningOf(obj),
            speaking: idsOf(obj?["speaking"]),
            cursor: cursor
        )
    }

    public static func renderRoom(_ page: RoomPage, roster: [String: Speaker] = [:]) -> [ChatRender.Item] {
        var out: [ChatRender.Item] = []
        for raw in page.items {
            if let round = roundEventLine(raw, roster: roster) {
                out.append(.systemLine(round))
                continue
            }
            guard let item = ChatRender.renderItem(raw) else {
                out.append(contentsOf: ChatRender.extras(raw))
                continue
            }
            if case .message(let msg) = item, !msg.fromUser, let sp = speakerOf(raw, roster: roster), sp.name != nil {
                out.append(.message(msg.withSpeaker(name: sp.name, color: sp.color)))
                out.append(contentsOf: ChatRender.extras(raw))
                continue
            }
            out.append(item)
            out.append(contentsOf: ChatRender.extras(raw))
        }
        return out
    }

    public static func speakerOf(_ item: DsbotModels.ChatItem, roster: [String: Speaker] = [:]) -> Speaker? {
        guard let obj = item.raw?.objectValue, let botId = obj.nonEmptyString("botId") else {
            return nil
        }
        let known = roster[botId]
        return Speaker(
            botId: botId,
            name: known?.name ?? obj.nonEmptyString("name"),
            color: known?.color ?? obj.nonEmptyString("color")
        )
    }

    public static func roster(from state: JsonValue?) -> [String: Speaker] {
        guard let bots = state?.objectValue?.array("bots") else { return [:] }
        var map: [String: Speaker] = [:]
        for b in bots {
            guard let o = b.objectValue, let id = o.nonEmptyString("id") else { continue }
            map[id] = Speaker(botId: id, name: o.nonEmptyString("name"), color: o.nonEmptyString("color"))
        }
        return map
    }

    // MARK: - 群端点请求构造

    public static func roomChatRequest(roomId: String, cursorJson: String? = nil) -> String {
        if let cursorJson {
            return "{\"roomId\":\(JsonString.of(roomId)),\"cursor\":\(cursorJson)}"
        }
        return "{\"roomId\":\(JsonString.of(roomId))}"
    }

    public static func stopRoomWork(roomId: String) -> String {
        "{\"roomId\":\(JsonString.of(roomId))}"
    }

    public static func roomProgress(roomId: String) -> String {
        "{\"roomId\":\(JsonString.of(roomId))}"
    }

    public static func goalDraft(roomId: String, text: String) -> String {
        "{\"roomId\":\(JsonString.of(roomId)),\"text\":\(JsonString.of(text))}"
    }

    public static func goalControl(roomId: String, action: String) -> String {
        "{\"roomId\":\(JsonString.of(roomId)),\"action\":\(JsonString.of(action))}"
    }

    // MARK: - 轮次事件渲染

    private static let roundEvents: Set<String> = [
        "done", "stopped", "working", "approval", "failed", "missed", "lookup", "resumed", "cut",
    ]

    private static let failures: [String: (zh: String, en: String)] = [
        "MISSING_CREDENTIAL": ("模型还没有 API Key", "The model has no API key"),
        "INVALID_CREDENTIAL": ("API Key 格式不对", "The API key is not in the right format"),
        "AUTH": ("API 密钥无效", "The API key was rejected"),
        "CONTENT_POLICY": ("模型服务的内容审核拦截了这条内容", "The model service blocked the content"),
        "QUOTA": ("账户额度已用尽", "The account is out of quota"),
        "ACCOUNT_QUOTA": ("账户额度已用尽", "The account is out of quota"),
        "ACCOUNT_SIGN_IN_REQUIRED": ("DeepSeek 账号需要登录", "The DeepSeek account needs a sign-in"),
        "ACCOUNT_SIGNED_OUT": ("DeepSeek 账号已退出登录", "The DeepSeek account is signed out"),
        "ACCOUNT_TOKEN_INVALID": ("DeepSeek 账号的登录失效了", "The DeepSeek account sign-in has expired"),
        "RATE_LIMIT": ("模型请求太频繁", "Too many requests to the model"),
        "CONTEXT_WINDOW_EXCEEDED": ("对话太长，超出模型上限", "The conversation is too long for the model"),
        "INVALID_REQUEST": ("模型服务拒绝了请求", "The model service refused the request"),
        "SERVER": ("模型服务出错", "The model service failed"),
        "TIMEOUT": ("模型响应超时", "The model took too long to answer"),
        "TRANSPORT": ("连不上模型服务", "Could not reach the model service"),
        "MODEL_SWITCH_COMPACTION_FAILED": ("切换模型时无法压缩对话", "The conversation could not be compacted for the model switch"),
        "SESSION": ("它的群聊会话打不开", "Its group conversation could not open"),
        "SESSION_RESET": ("它的群聊会话打不开，下次会换一个新的", "Its group conversation could not open, so it starts a new one next time"),
    ]

    public static func roundEventLine(_ raw: DsbotModels.ChatItem, roster: [String: Speaker] = [:]) -> String? {
        guard let obj = raw.raw?.objectValue,
              raw.kind == "event",
              let event = obj.nonEmptyString("event"),
              roundEvents.contains(event)
        else {
            return nil
        }
        let name = obj.nonEmptyString("botId").flatMap { roster[$0]?.name }
            ?? obj.nonEmptyString("name")
            ?? I18n.tr("某个 Bot", "a Bot")
        let text = obj.nonEmptyString("text")

        switch event {
        case "working":
            return I18n.tr("\(name) 开始做了", "\(name) started on it")
        case "approval":
            return I18n.tr("\(name) 停下来等你批准", "\(name) paused for your approval")
        case "done":
            return I18n.tr("\(name) 做完了", "\(name) finished its work")
        case "stopped":
            return I18n.tr("你停下了 \(name) 的工作", "You stopped \(name)'s work")
        case "missed":
            return I18n.tr("\(name) 没有及时回应", "\(name) didn't answer in time")
        case "lookup":
            if let text {
                return I18n.tr("\(name) 在自己的对话里查找「\(text)」", "\(name) looked up its own chat for “\(text)”")
            }
            return I18n.tr("\(name) 查看了自己的对话", "\(name) looked up its own chat")
        case "resumed":
            return I18n.tr("\(name) 的工作被重启打断，已经接着做", "\(name) was cut off by a restart and picked its work back up")
        case "cut":
            return I18n.tr("\(name) 的工作又被重启打断，已停下", "\(name) was cut off by a restart again and stopped")
        default: // "failed"
            let code = obj.nonEmptyString("code")
            let label = code.flatMap { failures[$0] }.map { I18n.tr($0.zh, $0.en) }
            let reason = label ?? text ?? code ?? I18n.tr("未知错误", "Unknown error")
            return I18n.tr(
                "\(name) 停下了：\(reason)。换个模型，或让它继续。",
                "\(name) stopped: \(reason). Switch its model or ask it to go on."
            )
        }
    }

    private static func runningOf(_ obj: JsonObject?) -> [String] {
        guard let obj, let running = obj["running"] else { return [] }
        switch running {
        case .array:
            return idsOf(running)
        case .bool(let active):
            return active ? idsOf(obj["speaking"]) : []
        default:
            return []
        }
    }

    private static func idsOf(_ value: JsonValue?) -> [String] {
        guard let value else { return [] }
        switch value {
        case .array(let items):
            return items.compactMap { item in
                guard let s = item.stringValue, !s.isEmpty else { return nil }
                return s
            }
        case .string(let s):
            return s.isEmpty ? [] : [s]
        default:
            return []
        }
    }
}
