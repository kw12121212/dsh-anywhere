package work.keepwriting.core

/**
 * 私聊渲染组装器（dsbot-bot-chat）：chat/earlier 条目到渲染模型的纯函数 + 流式
 * 拼接状态机。网页端（0.3.2 client.js）是对等基准：分段边界与 checkpoint 不产生
 * 可见分隔、工具调用折叠、Markdown 主体。未知字段与语法一律降级（纯文本段落）
 * 不崩溃（tech/dsbot-api 的 version-baseline-and-degrade）。
 */
object ChatRender {
    /** 渲染条目（气泡级）：用户/Bot 消息、工具调用卡、图片引用等。 */
    sealed interface Item {
        /** 用户或 Bot 的文本消息；markdown 为原始 Markdown（App 渲染）。群聊成员消息带
         *  speakerName/speakerColor（room-chat 条目顶层归属，私聊为 null）。 */
        data class Message(
            val fromUser: Boolean,
            val markdown: String,
            val turn: Long?,
            val seq: Long?,
            val speakerName: String? = null,
            val speakerColor: String? = null,
        ) : Item
        /** 工具调用折叠卡（call 名与状态摘要）。 */
        data class ToolCard(val name: String?, val summary: String?) : Item
        /** 图片引用（消息内可显示的图片形态）。attachmentRefJson 为 dsh 附件引用对象的
         *  原始 JSON（0.3.x 服务端存储引用：attachmentId/mediaType/bytes/width/height），
         *  点击时经 mux session/attachment 取字节；ref 为直链兜底。 */
        data class Image(val ref: String?, val name: String?, val attachmentRefJson: String? = null) : Item
        /** 其他已知 kind（answer/closed/event…）：有文本时降级为不可见或系统行，缺文本跳过。 */
        data class SystemLine(val text: String?) : Item

        /** 文件附件条目：引用对象的原始 JSON（attachmentId…，点击经 session/attachment 取字节）。 */
        data class FileAttachment(val name: String?, val refJson: String) : Item

        /** 交互卡片条目（dsbot-interactive-cards）：Cards.Card 模型（解析/降级在 core 完成）。 */
        data class InteractiveCard(val card: Cards.Card) : Item

        /** btw 插话卡（dsbot-side-chat）：显示插话文本与来源，可删除（btw-delete）。 */
        data class Btw(val id: String?, val text: String, val from: String? = null) : Item
    }

    /** 分页信息（earlier/chat 的下一页 cursor，原始值形态透传）。 */
    data class Page(val items: List<Item>, val cursor: Json.Value?, val exhausted: Boolean)

    /**
     * chat 端点 items → 渲染条目：过滤分段边界与 checkpoint（网页端不可见），
     * 工具调用折叠为卡，answer/closed 事件含说明时给系统行、无文本跳过。
     */
    fun render(page: DsbotModels.ChatPage): Page {
        val out = mutableListOf<Item>()
        for (raw in page.items) {
            renderItem(raw)?.let { out.add(it) }
        }
        // cursor 存在即可再取更早一页；应答未带 cursor 视为到最早（保守：不可再翻）
        return Page(out, page.cursor, page.cursor == null)
    }

    /** 单条目分发（私聊 render 与群聊 RoomChat.renderRoom 共用——不复制渲染代码）。 */
    fun renderItem(raw: DsbotModels.ChatItem): Item? {
        val obj = raw.raw as? Json.Value.Object ?: return null
        return when (raw.kind) {
            "user" -> raw.text?.let { Item.Message(fromUser = true, markdown = it, turn = raw.turn, seq = raw.seq) }
            "bot" -> raw.text?.let { Item.Message(fromUser = false, markdown = it, turn = raw.turn, seq = raw.seq) }
            "tool" -> Item.ToolCard(
                name = (obj.members["call"] as? Json.Value.Object)?.stringOr("name"),
                summary = obj.stringOr("label") ?: obj.stringOr("status"),
            )
            "image" -> {
                val image = obj.members["image"] as? Json.Value.Object
                // 0.3.x：image 是附件引用对象（attachmentId…），无直链 url
                Item.Image(
                    ref = image?.stringOr("url") ?: image?.stringOr("data"),
                    name = image?.stringOr("name"),
                    attachmentRefJson = if (image?.members?.containsKey("attachmentId") == true) Json.encode(image) else null,
                )
            }
            "file" -> {
                // 文件附件（dsbot-attachments）：引用对象透传，点击经 session/attachment 取字节
                val file = (obj.members["file"] ?: obj.members["attachment"]) as? Json.Value.Object
                if (file?.members?.containsKey("attachmentId") == true) {
                    Item.FileAttachment(name = file.stringOr("name"), refJson = Json.encode(file))
                } else {
                    null
                }
            }
            "answer", "closed", "event" -> {
                val card = Cards.fromChatItem(raw)
                if (card != null) {
                    Item.InteractiveCard(card)
                } else if (obj.stringOr("role") == "btw" && !raw.text.isNullOrEmpty()) {
                    // btw 插话卡（dsbot-side-chat）：id 用于 btw-delete；from 为来源 Bot
                    val id = obj.stringOr("id") ?: obj.stringOr("callId")
                    Item.Btw(id, raw.text!!, obj.stringOr("from") ?: obj.stringOr("name"))
                } else {
                    val note = obj.stringOr("note") ?: obj.stringOr("question") ?: obj.stringOr("text")
                    if (!note.isNullOrEmpty()) Item.SystemLine(note) else null
                }
            }
            // 分段边界/checkpoint/未知 kind：不可见（网页端一致）
            else -> null
        }
    }

    /**
     * 流式拼接状态机：stream start 开一个正在写的气泡，text 逐字追加，end 定格；
     * message 事件到达时若与正在写的气泡同 turn 则替换为完整条目，否则追加。
     * 乱序容错：未 start 的 text/end 丢弃；重复 end 幂等。
     */
    class StreamBuffer {
        private var writing: MutablePair? = null

        private class MutablePair(var botId: String? = null, var sessionId: String? = null, var attemptId: String? = null, var text: StringBuilder = StringBuilder(), var ended: Boolean = false)

        /** feed stream 事件：返回当前正在写气泡的最新全文（无变化返回 null）。 */
        fun onStream(botId: String?, sessionId: String?, attemptId: String?, phase: String?, text: String?): String? {
            if (phase == "start") {
                writing = MutablePair(botId, sessionId, attemptId)
                return ""
            }
            val w = writing ?: return null
            if (attemptId != null && w.attemptId != null && attemptId != w.attemptId) return null
            return when (phase) {
                null -> { // text 增量
                    if (text != null) w.text.append(text)
                    w.text.toString()
                }
                "end" -> {
                    if (!w.ended && text != null) w.text.append(text)
                    w.ended = true
                    w.text.toString()
                }
                else -> null
            }
        }

        /** 定格：取当前气泡全文并清空（未在写或未结束也可被 message 事件定格）。 */
        fun settle(): String? {
            val w = writing ?: return null
            writing = null
            return w.text.toString().ifEmpty { null }
        }

        fun inProgress(): Boolean = writing != null && writing!!.ended.not()
    }

    private fun Json.Value.Object.stringOr(key: String): String? =
        (members[key] as? Json.Value.StringValue)?.value?.takeIf { it.isNotEmpty() }

    /**
     * message 事件条目追加判定（design 决策 stream-buffer-in-core：纯逻辑下沉）：
     * stream end 已定格同文本的无序号条目时升级补序号（Upgrade），否则追加（Add）。
     */
    fun appendBotMessage(items: List<Item>, text: String, seq: Long?): AppendDecision {
        val last = items.lastOrNull() as? Item.Message
        if (last != null && !last.fromUser && last.seq == null && last.markdown == text) {
            return AppendDecision.Upgrade(items.lastIndex, last.copy(seq = seq))
        }
        return AppendDecision.Add(Item.Message(fromUser = false, markdown = text, turn = null, seq = seq))
    }

    /** appendBotMessage 的结果：在指定位替换（升级）或追加新条目。 */
    sealed interface AppendDecision {
        data class Add(val item: Item) : AppendDecision
        data class Upgrade(val index: Int, val item: Item.Message) : AppendDecision
    }
}
