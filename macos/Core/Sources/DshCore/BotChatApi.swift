import Foundation

/// 私聊端点族（bot-chat，形态以 tech/dsbot-api FINDINGS 登记与 Android 实现为准）：
/// 请求体构造与轻量结果。应答条目分页复用 `DsbotModels.decodeChatPage`。
public enum BotChatApi: Sendable {
    /// chat {botId, cursor?}（首页与预取；cursor 透传上一页原始值）。
    public static func chatPayload(botId: String, cursor: JsonValue? = nil) -> String {
        var out = "{\"botId\":\(JsonString.of(botId))"
        if let cursor {
            out += ",\"cursor\":\(Json.encode(cursor))"
        }
        return out + "}"
    }

    /// earlier {sessionId, cursor}（向上翻页；cursor 为上一页返回的原始对象）。
    public static func earlierPayload(sessionId: String, cursor: JsonValue) -> String {
        "{\"sessionId\":\(JsonString.of(sessionId)),\"cursor\":\(Json.encode(cursor))}"
    }

    /// send {sessionId, text}（纯文本）。
    public static func sendPayload(sessionId: String, text: String) -> String {
        "{\"sessionId\":\(JsonString.of(sessionId)),\"text\":\(JsonString.of(text))}"
    }

    /// stop-member-work {roomId:"", botId}（私聊停止；roomId 空串为私聊形态）。
    public static func stopPayload(botId: String) -> String {
        "{\"roomId\":\"\",\"botId\":\(JsonString.of(botId))}"
    }

    /// resume {botId}。
    public static func resumePayload(botId: String) -> String {
        "{\"botId\":\(JsonString.of(botId))}"
    }

    /// bot-new-part {botId}。
    public static func newPartPayload(botId: String) -> String {
        "{\"botId\":\(JsonString.of(botId))}"
    }

    /// bot-open-part {botId, part}。
    public static func openPartPayload(botId: String, part: String) -> String {
        "{\"botId\":\(JsonString.of(botId)),\"part\":\(JsonString.of(part))}"
    }

    /// run-stats {botId}。
    public static func runStatsPayload(botId: String) -> String {
        "{\"botId\":\(JsonString.of(botId))}"
    }

    /// exchange {}（团队级 Bot 间记录）。
    public static let exchangePayload = "{}"
}
