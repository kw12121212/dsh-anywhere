package work.keepwriting.core

/**
 * Side chat 旁路提问构造与解码（dsbot-side-chat）：side 会话的建立/提问/取消/关闭
 * 与 btw 卡删除。wire 形态 0.3.5 实测（tech/dsbot-api endpoint-inventory-source）：
 * side-new {sessionId}→{chatId}；side-ask {sessionId,text}；side-cancel/close {sideId}；
 * btw-delete {id}。side 会话条目在 state.sideChats 的 entries[]（kind 同聊天条目）。
 */
object SideChat {

    /** side-new / side-ask / side-cancel / side-close / btw-delete payload。 */
    fun newSide(sessionId: String): String = "{\"sessionId\":${JsonString.of(sessionId)}}"

    fun ask(sessionId: String, text: String, snapshotJson: String? = null): String =
        if (snapshotJson == null) {
            "{\"sessionId\":${JsonString.of(sessionId)},\"text\":${JsonString.of(text)}}"
        } else {
            "{\"sessionId\":${JsonString.of(sessionId)},\"text\":${JsonString.of(text)},\"snapshot\":$snapshotJson}"
        }

    fun cancel(sideId: String): String = "{\"sideId\":${JsonString.of(sideId)}}"

    fun close(sideId: String): String = "{\"sideId\":${JsonString.of(sideId)}}"

    fun btwDelete(id: String): String = "{\"id\":${JsonString.of(id)}}"

    /** side-new 应答的 chatId。 */
    fun decodeChatId(value: Json.Value): String? =
        (value as? Json.Value.Object)?.let { (it.members["chatId"] as? Json.Value.StringValue)?.value }

    /** state.sideChats → 每个旁路会话（id 与 entries 渲染条目）。 */
    data class SideSession(val id: String, val entries: List<DsbotModels.ChatItem>)

    fun decodeSideSessions(state: Json.Value.Object): List<SideSession> {
        val sideChats = state.members["sideChats"] as? Json.Value.Object ?: return emptyList()
        val out = mutableListOf<SideSession>()
        for ((_, arr) in sideChats.members) {
            val sessions = arr as? Json.Value.Array ?: continue
            for (s in sessions.items) {
                val o = s as? Json.Value.Object ?: continue
                val id = (o.members["id"] as? Json.Value.StringValue)?.value ?: continue
                val entries = (o.members["entries"] as? Json.Value.Array)?.items?.map { item ->
                    val io = item as? Json.Value.Object
                    DsbotModels.ChatItem(
                        kind = io?.members?.get("kind")?.let { (it as? Json.Value.StringValue)?.value },
                        text = io?.members?.get("text")?.let { (it as? Json.Value.StringValue)?.value },
                        time = (io?.members?.get("time") as? Json.Value.NumberValue)?.raw?.toLongOrNull(),
                        seq = (io?.members?.get("seq") as? Json.Value.NumberValue)?.raw?.toLongOrNull(),
                        turn = (io?.members?.get("turn") as? Json.Value.NumberValue)?.raw?.toLongOrNull(),
                        raw = item,
                    )
                } ?: emptyList()
                out.add(SideSession(id, entries))
            }
        }
        return out
    }

    /** 主聊天快照（side-ask 附最近消息）：主 rows 尾部 N 条文本条目 → snapshot 数组 JSON。 */
    fun snapshotOf(messages: List<Pair<Boolean, String>>, max: Int = 5): String =
        messages.takeLast(max).joinToString(",", "[", "]") { (fromUser, text) ->
            "{\"role\":${JsonString.of(if (fromUser) "user" else "assistant")},\"text\":${JsonString.of(text)}}"
        }
}
