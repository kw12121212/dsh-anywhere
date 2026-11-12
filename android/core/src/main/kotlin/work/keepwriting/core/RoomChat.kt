package work.keepwriting.core

/**
 * 群聊构造与解码（dsbot-group-chat）：room-chat 应答（成员归属条目 + running/speaking）
 * 与群控制端点（stop-room-work / room-progress / goal-draft / goal-control）的 payload
 * 构造。wire 形态为 0.3.5 真实服务器实测（见 tech/dsbot-api endpoint-inventory-source）。
 */
object RoomChat {

    /** room-chat 应答：群历史一页。 */
    data class RoomPage(
        val roomId: String?,
        val items: List<DsbotModels.ChatItem>,
        val running: List<String>,
        val speaking: List<String>,
        val cursor: Json.Value?,
    )

    fun decodeRoomPage(value: Json.Value): RoomPage {
        val obj = value as? Json.Value.Object
        val items = (obj?.members?.get("items") as? Json.Value.Array)?.items ?: emptyList()
        return RoomPage(
            roomId = obj?.stringOr("roomId"),
            items = items.map { item ->
                val o = item as? Json.Value.Object
                DsbotModels.ChatItem(
                    kind = o?.stringOr("kind"),
                    text = o?.stringOr("text"),
                    time = o?.numberOr("time"),
                    seq = o?.numberOr("seq"),
                    turn = o?.numberOr("turn"),
                    raw = item,
                )
            },
            running = stringList(obj, "running"),
            speaking = stringList(obj, "speaking"),
            cursor = obj?.members?.get("cursor"),
        )
    }

    /** 群条目的成员归属（botId/name/color 在条目顶层；用户消息无）。 */
    data class Speaker(val botId: String?, val name: String?, val color: String?)

    /**
     * 群页渲染（逐原始条目）：跳过 [PASS]/checkpoint 等不可见条目的同时把 botId/name/
     * color 归属直接带上——render 后按索引回查会在中段不可见条目时错位，归属必须随
     * 条目本身走。
     */
    fun renderRoom(page: RoomPage): List<ChatRender.Item> {
        val out = mutableListOf<ChatRender.Item>()
        for (raw in page.items) {
            val item = ChatRender.renderItem(raw) ?: continue // 不可见 kind 与私聊同源跳过
            if (item is ChatRender.Item.Message && !item.fromUser) {
                val sp = speakerOf(raw)
                if (sp?.name != null) {
                    out.add(item.copy(speakerName = sp.name, speakerColor = sp.color))
                    continue
                }
            }
            out.add(item)
        }
        return out
    }

    fun speakerOf(item: DsbotModels.ChatItem): Speaker? {
        val obj = item.raw as? Json.Value.Object ?: return null
        val botId = obj.stringOr("botId") ?: return null
        return Speaker(botId = botId, name = obj.stringOr("name"), color = obj.stringOr("color"))
    }

    /** room-chat 请求。 */
    fun roomChatRequest(roomId: String, cursorJson: String? = null): String =
        if (cursorJson == null) "{\"roomId\":${JsonString.of(roomId)}}"
        else "{\"roomId\":${JsonString.of(roomId)},\"cursor\":$cursorJson}"

    /** stop-room-work / room-progress / goal-draft / goal-control 的 payload。 */
    fun stopRoomWork(roomId: String): String = "{\"roomId\":${JsonString.of(roomId)}}"

    fun roomProgress(roomId: String): String = "{\"roomId\":${JsonString.of(roomId)}}"

    fun goalDraft(roomId: String, text: String): String =
        "{\"roomId\":${JsonString.of(roomId)},\"text\":${JsonString.of(text)}}"

    fun goalControl(roomId: String, action: String): String =
        "{\"roomId\":${JsonString.of(roomId)},\"action\":${JsonString.of(action)}}"

    private fun stringList(obj: Json.Value.Object?, key: String): List<String> =
        (obj?.members?.get(key) as? Json.Value.Array)?.items?.mapNotNull {
            (it as? Json.Value.StringValue)?.value
        } ?: emptyList()

    private fun Json.Value.Object.stringOr(key: String): String? =
        (members[key] as? Json.Value.StringValue)?.value?.takeIf { it.isNotEmpty() }

    private fun Json.Value.Object.numberOr(key: String): Long? =
        (members[key] as? Json.Value.NumberValue)?.raw?.toLongOrNull()
}
