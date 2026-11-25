package work.keepwriting.core

/**
 * 群聊构造与解码（dsbot-group-chat）：room-chat 应答（成员归属条目 + running/speaking）
 * 与群控制端点（stop-room-work / room-progress / goal-draft / goal-control）的 payload
 * 构造。wire 形态为 0.3.5 真实服务器实测（见 tech/dsbot-api endpoint-inventory-source）；
 * 0.3.10 的 running（布尔）/speaking（单个 botId 或 null）与群轮次事件行按 dsbot-group-rounds
 * 实测兼容（见 tech/dsbot-api group-rounds-endpoints-0310）。
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
            running = runningOf(obj),
            speaking = idsOf(obj?.members?.get("speaking")),
            // 上游到最早时回 cursor:null：与缺键同样视为到最早，否则「更早消息」会重取最新一页并前插造成重复
            cursor = obj?.members?.get("cursor")?.takeUnless { it is Json.Value.NullValue },
        )
    }

    /** 群条目的成员归属（botId/name/color 在条目顶层；用户消息无）。 */
    data class Speaker(val botId: String?, val name: String?, val color: String?)

    /**
     * 群页渲染（逐原始条目）：跳过 [PASS]/checkpoint 等不可见条目的同时把 botId/name/
     * color 归属直接带上——render 后按索引回查会在中段不可见条目时错位，归属必须随
     * 条目本身走。成员名称与颜色以团队名册 [roster]（state.bots，按 botId）为先、条目自带值为后备，
     * 与网页端 roster.byId[botId] ?? 条目值 一致（dsbot-group-rounds）。
     */
    fun renderRoom(page: RoomPage, roster: Map<String, Speaker> = emptyMap()): List<ChatRender.Item> {
        val out = mutableListOf<ChatRender.Item>()
        for (raw in page.items) {
            val round = roundEventLine(raw, roster)
            if (round != null) { // 群轮次事件行（working/done/failed…）：网页端 RelayEvent 同款文案
                out.add(ChatRender.Item.SystemLine(round))
                continue
            }
            val item = ChatRender.renderItem(raw)
            if (item == null) { // 不可见 kind 与私聊同源跳过（附带的交付文件/图片仍显示）
                out.addAll(ChatRender.extras(raw))
                continue
            }
            if (item is ChatRender.Item.Message && !item.fromUser) {
                val sp = speakerOf(raw, roster)
                if (sp?.name != null) {
                    out.add(item.copy(speakerName = sp.name, speakerColor = sp.color))
                    out.addAll(ChatRender.extras(raw))
                    continue
                }
            }
            out.add(item)
            out.addAll(ChatRender.extras(raw))
        }
        return out
    }

    fun speakerOf(item: DsbotModels.ChatItem, roster: Map<String, Speaker> = emptyMap()): Speaker? {
        val obj = item.raw as? Json.Value.Object ?: return null
        val botId = obj.stringOr("botId") ?: return null
        val known = roster[botId]
        return Speaker(
            botId = botId,
            name = known?.name ?: obj.stringOr("name"),
            color = known?.color ?: obj.stringOr("color"),
        )
    }

    /** 团队名册：state.bots[] 的 id → 名称与颜色（群页成员归属以此为先）。 */
    fun roster(state: Json.Value?): Map<String, Speaker> {
        val bots = ((state as? Json.Value.Object)?.members?.get("bots") as? Json.Value.Array)?.items ?: return emptyMap()
        return bots.mapNotNull { b ->
            val o = b as? Json.Value.Object ?: return@mapNotNull null
            val id = o.stringOr("id") ?: return@mapNotNull null
            id to Speaker(botId = id, name = o.stringOr("name"), color = o.stringOr("color"))
        }.toMap()
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

    /** 上游 mobile.js ROUND_EVENTS：room-chat 把群轮次事件展开为 kind=event、event=<名> 的条目。 */
    private val ROUND_EVENTS = setOf("done", "stopped", "working", "approval", "failed", "missed", "lookup", "resumed", "cut")

    /**
     * 群轮次事件条目的显示文案（对齐 0.3.10 client.js RelayEvent 的中英文案）；非轮次事件回 null。
     * 成员名以名册为先、条目 name 为后备，都缺时为「某个 Bot」（不显示原始 id）；failed 的原因与网页端
     * failureLabel 同序：已知错误码的本地化标签 → 条目 text → code → 「未知错误」。
     */
    fun roundEventLine(raw: DsbotModels.ChatItem, roster: Map<String, Speaker> = emptyMap()): String? {
        val obj = raw.raw as? Json.Value.Object ?: return null
        if (raw.kind != "event") return null
        val event = obj.stringOr("event") ?: return null
        if (event !in ROUND_EVENTS) return null
        val name = obj.stringOr("botId")?.let { roster[it]?.name } ?: obj.stringOr("name") ?: I18n.tr("某个 Bot", "a Bot")
        val text = obj.stringOr("text")
        return when (event) {
            "working" -> I18n.tr("$name 开始做了", "$name started on it")
            "approval" -> I18n.tr("$name 停下来等你批准", "$name paused for your approval")
            "done" -> I18n.tr("$name 做完了", "$name finished its work")
            "stopped" -> I18n.tr("你停下了 $name 的工作", "You stopped $name's work")
            "missed" -> I18n.tr("$name 没有及时回应", "$name didn't answer in time")
            "lookup" -> if (text != null) I18n.tr("$name 在自己的对话里查找「$text」", "$name looked up its own chat for “$text”")
            else I18n.tr("$name 查看了自己的对话", "$name looked up its own chat")
            "resumed" -> I18n.tr("$name 的工作被重启打断，已经接着做", "$name was cut off by a restart and picked its work back up")
            "cut" -> I18n.tr("$name 的工作又被重启打断，已停下", "$name was cut off by a restart again and stopped")
            else -> {
                val code = obj.stringOr("code")
                val reason = code?.let { failureLabel(it) } ?: text ?: code ?: I18n.tr("未知错误", "Unknown error")
                I18n.tr("$name 停下了：$reason。换个模型，或让它继续。", "$name stopped: $reason. Switch its model or ask it to go on.")
            }
        }
    }

    /** 0.3.10 client.js FAILURES：已知失败码 → 中英标签（knownFailure）；未知码回 null。 */
    private val FAILURES = mapOf(
        "MISSING_CREDENTIAL" to ("模型还没有 API Key" to "The model has no API key"),
        "INVALID_CREDENTIAL" to ("API Key 格式不对" to "The API key is not in the right format"),
        "AUTH" to ("API 密钥无效" to "The API key was rejected"),
        "CONTENT_POLICY" to ("模型服务的内容审核拦截了这条内容" to "The model service blocked the content"),
        "QUOTA" to ("账户额度已用尽" to "The account is out of quota"),
        "ACCOUNT_QUOTA" to ("账户额度已用尽" to "The account is out of quota"),
        "ACCOUNT_SIGN_IN_REQUIRED" to ("DeepSeek 账号需要登录" to "The DeepSeek account needs a sign-in"),
        "ACCOUNT_SIGNED_OUT" to ("DeepSeek 账号已退出登录" to "The DeepSeek account is signed out"),
        "ACCOUNT_TOKEN_INVALID" to ("DeepSeek 账号的登录失效了" to "The DeepSeek account sign-in has expired"),
        "RATE_LIMIT" to ("模型请求太频繁" to "Too many requests to the model"),
        "CONTEXT_WINDOW_EXCEEDED" to ("对话太长，超出模型上限" to "The conversation is too long for the model"),
        "INVALID_REQUEST" to ("模型服务拒绝了请求" to "The model service refused the request"),
        "SERVER" to ("模型服务出错" to "The model service failed"),
        "TIMEOUT" to ("模型响应超时" to "The model took too long to answer"),
        "TRANSPORT" to ("连不上模型服务" to "Could not reach the model service"),
        "MODEL_SWITCH_COMPACTION_FAILED" to ("切换模型时无法压缩对话" to "The conversation could not be compacted for the model switch"),
        "SESSION" to ("它的群聊会话打不开" to "Its group conversation could not open"),
        "SESSION_RESET" to ("它的群聊会话打不开，下次会换一个新的" to "Its group conversation could not open, so it starts a new one next time"),
    )

    private fun failureLabel(code: String): String? = FAILURES[code]?.let { (zh, en) -> I18n.tr(zh, en) }

    /** 0.3.5 的 running 为 botId 数组；0.3.10 为布尔，进行中时以 speaking 的成员作为运行者。 */
    private fun runningOf(obj: Json.Value.Object?): List<String> =
        when (val running = obj?.members?.get("running")) {
            is Json.Value.Array -> idsOf(running)
            is Json.Value.BoolValue -> if (running.value) idsOf(obj.members["speaking"]) else emptyList()
            else -> emptyList()
        }

    /** botId 数组（0.3.5）或单个 botId 字符串（0.3.10 speaking）；null 与其他形态为空。 */
    private fun idsOf(value: Json.Value?): List<String> = when (value) {
        is Json.Value.Array -> value.items.mapNotNull { (it as? Json.Value.StringValue)?.value?.takeIf { id -> id.isNotEmpty() } }
        is Json.Value.StringValue -> listOfNotNull(value.value.takeIf { it.isNotEmpty() })
        else -> emptyList()
    }

    private fun Json.Value.Object.stringOr(key: String): String? =
        (members[key] as? Json.Value.StringValue)?.value?.takeIf { it.isNotEmpty() }

    private fun Json.Value.Object.numberOr(key: String): Long? =
        (members[key] as? Json.Value.NumberValue)?.raw?.toLongOrNull()
}
