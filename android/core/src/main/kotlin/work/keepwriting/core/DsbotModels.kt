package work.keepwriting.core

/**
 * DS-Bot state/chat 响应的可降级解码模型（dsbot-api-client）：强类型字段只覆盖
 * 已测基准（DS-Bot 0.3.2）确认存在的部分，未知字段与类型不匹配字段保留在 raw
 *（原始 Json.Value 节点）里——/api/bot 是无稳定承诺的内部接口，升级后新增或变形
 * 的字段必须能降级显示而不是抛错（tech/dsbot-api 的 version-baseline-and-degrade）。
 */
object DsbotModels {
    /** state 全量视图。revision 是同步的锚点；raw 保留完整原始对象。 */
    data class State(
        val revision: String,
        val liveKey: String?,
        val mainBotIds: List<String>,
        val bots: List<Bot>,
        val rooms: List<Room>,
        val raw: Json.Value.Object,
    )

    data class Bot(
        val id: String,
        val sessionId: String?,
        val parts: List<String>,
        val waiting: Boolean,
        val raw: Json.Value.Object,
    )

    data class Room(
        val id: String,
        val sessionId: String?,
        val members: List<String>,
        val raw: Json.Value.Object,
    )

    /** state 增量应答：unchanged=true 时可能只有三键，也可能重发 live 字段集。 */
    data class StateDelta(
        val revision: String?,
        val unchanged: Boolean,
        val liveKey: String?,
        /** 应答里是否带 live 字段集（activity/work 等）——带则需要合并到本地。 */
        val hasLiveFields: Boolean,
        val raw: Json.Value.Object,
    )

    data class ChatPage(val sessionId: String?, val items: List<ChatItem>, val cursor: Json.Value?)

    data class ChatItem(
        val kind: String?,
        val text: String?,
        val time: Long?,
        val seq: Long?,
        val turn: Long?,
        val raw: Json.Value?,
    )

    fun decodeState(value: Json.Value): State? {
        val obj = value as? Json.Value.Object ?: return null
        // 全量要求 bots 键存在：增量应答也总带 revision，但不含 bots——判别依据是
        // 键形态而非 revision（评审修复：仅凭 revision 会把增量当空全量清空团队页）。
        if (!obj.members.containsKey("bots")) return null
        val revision = obj.scalarOf("revision") ?: return null
        return State(
            revision = revision,
            liveKey = obj.stringOf("liveKey"),
            mainBotIds = obj.stringList("mainBotIds"),
            bots = (obj.members["bots"] as? Json.Value.Array)?.items?.mapNotNull(::decodeBot) ?: emptyList(),
            rooms = (obj.members["rooms"] as? Json.Value.Array)?.items?.mapNotNull(::decodeRoom) ?: emptyList(),
            raw = obj,
        )
    }

    private fun decodeBot(value: Json.Value): Bot? {
        val obj = value as? Json.Value.Object ?: return null
        val id = obj.stringOf("id") ?: return null
        return Bot(
            id = id,
            sessionId = obj.stringOf("sessionId"),
            parts = obj.stringList("parts"),
            waiting = (obj.members["waiting"] as? Json.Value.BoolValue)?.value ?: false,
            raw = obj,
        )
    }

    private fun decodeRoom(value: Json.Value): Room? {
        val obj = value as? Json.Value.Object ?: return null
        val id = obj.stringOf("id") ?: return null
        return Room(
            id = id,
            sessionId = obj.stringOf("sessionId"),
            members = obj.stringList("members"),
            raw = obj,
        )
    }

    /** state 增量应答解码：结构不完整时返回 null（调用方按全量刷新降级）。 */
    fun decodeStateDelta(value: Json.Value): StateDelta? {
        val obj = value as? Json.Value.Object ?: return null
        val unchanged = (obj.members["unchanged"] as? Json.Value.BoolValue)?.value ?: return null
        return StateDelta(
            revision = obj.scalarOf("revision"),
            unchanged = unchanged,
            liveKey = obj.stringOf("liveKey"),
            hasLiveFields = obj.members.keys.any { it !in setOf("revision", "unchanged", "liveKey") },
            raw = obj,
        )
    }

    fun decodeChatPage(value: Json.Value): ChatPage {
        val obj = value as? Json.Value.Object
        val items = (obj?.members?.get("items") as? Json.Value.Array)?.items ?: emptyList()
        return ChatPage(
            sessionId = obj?.stringOf("sessionId"),
            items = items.map { item ->
                val o = item as? Json.Value.Object
                ChatItem(
                    kind = o?.stringOf("kind"),
                    text = o?.stringOf("text"),
                    time = o?.longOf("time"),
                    seq = o?.longOf("seq"),
                    turn = o?.longOf("turn"),
                    raw = item,
                )
            },
            cursor = obj?.cursorOf(),
        )
    }

    /** chat/earlier 应答的下一页 cursor：保留原始值形态透传（0.3.x 实测为对象
     *  {"part","beforeSeq"}，夹具同构；字符串/数字形态也放行），无则视为到最早。 */
    private fun Json.Value.Object.cursorOf(): Json.Value? = members["cursor"]

    /**
     * 把增量应答的 live 字段集（hasLiveFields 时服务端重发的 work/questions/views 等）
     * 合并进当前全量 state：条目结构不变、live 键整体覆盖；无 live 字段时原样返回。
     */
    fun mergeDelta(state: State, delta: StateDelta): State {
        if (!delta.hasLiveFields) return state
        val merged = LinkedHashMap<String, Json.Value>(state.raw.members)
        for ((k, v) in delta.raw.members) {
            if (k != "revision" && k != "unchanged" && k != "liveKey") merged[k] = v
        }
        val mergedObj = Json.Value.Object(merged)
        return State(
            revision = delta.revision ?: state.revision,
            liveKey = delta.liveKey ?: state.liveKey,
            mainBotIds = state.mainBotIds,
            bots = state.bots,
            rooms = state.rooms,
            raw = mergedObj,
        )
    }

    private fun Json.Value.Object.stringOf(key: String): String? =
        (members[key] as? Json.Value.StringValue)?.value

    /** revision 在 0.3.2 里是 JSON 数字（1694），未来可能变字符串——两种都收。 */
    private fun Json.Value.Object.scalarOf(key: String): String? =
        (members[key] as? Json.Value.NumberValue)?.raw ?: (members[key] as? Json.Value.StringValue)?.value

    private fun Json.Value.Object.longOf(key: String): Long? =
        (members[key] as? Json.Value.NumberValue)?.raw?.toLongOrNull()

    private fun Json.Value.Object.stringList(key: String): List<String> =
        (members[key] as? Json.Value.Array)?.items?.mapNotNull { (it as? Json.Value.StringValue)?.value } ?: emptyList()
}
