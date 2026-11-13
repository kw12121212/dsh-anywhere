package work.keepwriting.dshanywhere

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import work.keepwriting.core.ChatRender
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.Json
import work.keepwriting.core.DsbotModels
import work.keepwriting.core.RoomChat

/**
 * 群聊控制器（dsbot-group-chat）：room-chat 取群历史（成员归属渲染）、群消息提交
 * （session/prompt，sessionId=room 会话；@提及即文本中的 @成员名）、停止群/成员、
 * room-progress、群目标 goal-draft/goal-control。复用 ChatController 的 executor
 * 收敛模式与流式状态机；渲染 rows 与私聊同构（ChatRender.Item + speaker 字段）。
 */
class RoomChatController(
    private val api: (endpoint: String, payloadJson: String, onResult: (DsbotApi.Result) -> Unit) -> Unit,
    private val execute: (Runnable) -> Unit,
    private val rpcCall: (endpoint: String, requestJson: String, onResult: (work.keepwriting.core.DshMux.Result) -> Unit) -> Unit,
    /** 附件上传通道（dsbot-attachments）：uploadFileBinary，executor 内回调。 */
    private val upload: (
        sessionId: String, name: String, mediaType: String?, bytes: ByteArray,
        onResult: (DsbotApi.Result) -> Unit,
    ) -> Unit = { _, _, _, _, _ -> },
) {
    data class Row(val key: Long, val item: ChatRender.Item)

    val rows = mutableStateListOf<Row>()
    var streamingText by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var announcement by mutableStateOf<String?>(null)
        private set
    var canLoadEarlier by mutableStateOf(false)
        private set
    var running by mutableStateOf<List<String>>(emptyList())
        private set
    var jumpSignal by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    private var roomId: String? = null
    private var cursor: Json.Value? = null
    private var nextRowKey = 0L

    /** 打开群聊：room-chat 取历史（含 running/speaking 与公告占位）。 */
    fun open(id: String) {
        execute(Runnable { openOnExecutor(id) })
    }

    private var openPending = false
    private val liveInFlight = mutableListOf<ChatRender.Item>()

    private fun openOnExecutor(id: String) {
        if (openPending && roomId == id) return // 在途防抖
        openPending = true
        roomId = id
        rows.clear()
        streamingText = null
        api("room-chat", RoomChat.roomChatRequest(id)) { res ->
            openPending = false
            if (roomId != id) return@api
            when (res) {
                is DsbotApi.Result.Ok -> {
                    val page = RoomChat.decodeRoomPage(res.value)
                    running = page.running
                    announcement = (res.value as? Json.Value.Object)?.members?.get("announcement")?.let { (it as? Json.Value.StringValue)?.value }
                    rows.clear()
                    RoomChat.renderRoom(page).forEach { appendItem(it) }
                    // 回放在途实时条目（重取期间到达的消息；按文本+方向去重）
                    val replay = liveInFlight.toList()
                    liveInFlight.clear()
                    for (extra in replay) {
                        val msg = extra as? ChatRender.Item.Message ?: continue
                        val dup = rows.any {
                            val m = it.item as? ChatRender.Item.Message
                            m != null && m.fromUser == msg.fromUser && m.markdown == msg.markdown
                        }
                        if (!dup) appendItem(extra)
                    }
                    cursor = page.cursor
                    canLoadEarlier = page.cursor != null
                    notice = null
                    jumpSignal++
                }
                is DsbotApi.Result.BotError -> {
                    liveInFlight.clear()
                    notice = "加载群聊失败：${res.code}"
                }
                is DsbotApi.Result.Failure -> {
                    liveInFlight.clear()
                    notice = "加载群聊失败（网络）"
                }
            }
        }
    }


    /** feed 重连后补取当前群页（断线窗口内发布的群消息不可重放）。executor 线程内调用。 */
    fun refreshIfOpen() {
        val id = roomId ?: return
        openOnExecutor(id)
    }

    fun loadEarlier() {
        val id = roomId ?: return
        val cur = cursor ?: return
        api("room-chat", RoomChat.roomChatRequest(id, Json.encode(cur))) { res ->
            when (res) {
                is DsbotApi.Result.Ok -> {
                    val page = RoomChat.decodeRoomPage(res.value)
                    val newRows = RoomChat.renderRoom(page).map { Row(nextRowKey++, it) }
                    rows.addAll(0, newRows)
                    cursor = page.cursor
                    canLoadEarlier = page.cursor != null
                }
                is DsbotApi.Result.BotError -> notice = "更早记录加载失败：${res.code}"
                is DsbotApi.Result.Failure -> notice = "更早记录加载失败（网络）"
            }
        }
    }

    /** 群消息提交：session/prompt（sessionId=room 会话；@提及/引用都在文本中）。 */
    fun send(text: String): Boolean {
        val id = roomId ?: return false
        busy = true
        val optimistic = ChatRender.Item.Message(fromUser = true, markdown = text, turn = null, seq = null)
        appendItem(optimistic)
        val optimisticRowKey = rows.last().key
        val request = work.keepwriting.core.AttachmentMessages.promptRequest(
            requestId = java.util.UUID.randomUUID().toString(),
            sessionId = id,
            receiptIds = emptyList(),
            text = text,
        )
        rpcCall("session/prompt", request) { res ->
            when (res) {
                is work.keepwriting.core.DshMux.Result.Ok -> {
                    busy = false
                    notice = null
                }
                is work.keepwriting.core.DshMux.Result.Err -> {
                    if (rows.lastOrNull()?.key == optimisticRowKey) rows.removeAt(rows.lastIndex)
                    liveInFlight.removeAll { it === optimistic }
                    busy = false
                    notice = "发送失败：${res.code}"
                }
            }
        }
        return true
    }

    // ---- 附件（dsbot-attachments；与 ChatController 同构，session 为群会话 id）----

    val attachments = mutableStateListOf<ChatController.PendingAttachment>()
    private var nextAttachmentId = 1L

    /** 从 ContentResolver URI 读取附件（相册/文件选择器）：读字节在 executor 后台。 */
    fun addAttachmentFromUri(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        execute(Runnable {
            val ok = runCatching {
                val name = queryDisplayName(resolver, uri)
                val mediaType = resolver.getType(uri)
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                if (bytes.isNotEmpty()) {
                    val att = ChatController.PendingAttachment(
                        id = nextAttachmentId++,
                        name = name,
                        bytes = bytes,
                        isImage = mediaType?.startsWith("image/") == true,
                        mediaType = mediaType,
                    )
                    attachments.add(att)
                    uploadAttachment(att)
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (!ok) notice = "无法读取所选内容"
        })
    }

    fun removeAttachment(id: Long) {
        execute(Runnable { attachments.removeAll { it.id == id } })
    }

    fun retryAttachment(id: Long) {
        execute(Runnable {
            val att = attachments.firstOrNull { it.id == id } ?: return@Runnable
            if (att.status == "failed") uploadAttachment(att)
        })
    }

    private fun uploadAttachment(att: ChatController.PendingAttachment) {
        val id = roomId ?: run {
            att.status = "failed"
            att.failReason = "群会话未就绪"
            return
        }
        att.status = "uploading"
        att.failReason = null
        upload(id, att.name, att.mediaType, att.bytes) { res ->
            when (res) {
                is DsbotApi.Result.Ok -> {
                    val decoded = work.keepwriting.core.AttachmentMessages.decodeUpload(res.value)
                    if (decoded != null) {
                        att.receiptId = decoded.receiptId
                        att.status = "ready"
                    } else {
                        att.status = "failed"
                        att.failReason = "上传应答无法解析"
                    }
                }
                is DsbotApi.Result.BotError -> {
                    att.status = "failed"
                    att.failReason = "上传被拒：${res.code}"
                }
                is DsbotApi.Result.Failure -> {
                    att.status = "failed"
                    att.failReason = "上传失败（网络）"
                }
            }
        }
    }

    /** 发送：有附件时要求全部 ready，经 mux session/prompt 提交（receipts + 文本）；
     *  成功后清附件栏并乐观上屏；纯文本仍走 send。 */
    fun sendOrAttach(text: String): Boolean {
        if (attachments.isEmpty()) return send(text)
        execute(Runnable {
            val id = roomId ?: run {
                notice = "群会话未就绪，稍候再发"
                return@Runnable
            }
            if (attachments.any { it.status == "uploading" }) {
                notice = "附件还在上传"
                return@Runnable
            }
            if (attachments.any { it.status == "failed" }) {
                notice = "有附件上传失败，重试或移除后再发"
                return@Runnable
            }
            if (attachments.any { it.status != "ready" }) return@Runnable
            busy = true
            val receipts = attachments.mapNotNull { it.receiptId }
            val visible = buildString {
                if (text.isNotEmpty()) append(text).append('\n')
                attachments.forEach { append("[附件] ").append(it.name).append('\n') }
            }.trimEnd()
            val optimisticItem = ChatRender.Item.Message(fromUser = true, markdown = visible, turn = null, seq = null)
            appendItem(optimisticItem)
            val optimisticRowKey = rows.last().key
            val request = work.keepwriting.core.AttachmentMessages.promptRequest(
                requestId = java.util.UUID.randomUUID().toString(),
                sessionId = id,
                receiptIds = receipts,
                text = text.trim(),
            )
            rpcCall("session/prompt", request) { res ->
                when (res) {
                    is work.keepwriting.core.DshMux.Result.Ok -> {
                        busy = false
                        attachments.clear()
                        notice = null
                    }
                    is work.keepwriting.core.DshMux.Result.Err -> {
                        val last = rows.lastOrNull()
                        if (last?.key == optimisticRowKey) rows.removeAt(rows.lastIndex)
                        liveInFlight.removeAll { it === optimisticItem }
                        busy = false
                        notice = "发送失败：${res.code}"
                    }
                }
            }
        })
        return true
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: android.net.Uri): String {
        runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
    }

    /** 读取群会话附件字节（session/attachment，与私聊同语义）；失败走 notice。 */
    fun readAttachment(attachmentRefJson: String, onResult: (ByteArray?) -> Unit) {
        execute(Runnable {
            val sid = roomId ?: run {
                notice = "群会话未就绪，无法查看附件"
                onResult(null)
                return@Runnable
            }
            rpcCall(
                "session/attachment",
                work.keepwriting.core.AttachmentMessages.attachmentRequest(sid, attachmentRefJson),
            ) { res ->
                when (res) {
                    is work.keepwriting.core.DshMux.Result.Ok -> {
                        val inner = res.value?.let { Json.parse(it) as? Json.Value.Object }
                        val data = (inner?.members?.get("data") as? Json.Value.StringValue)?.value
                        if (data != null) {
                            onResult(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
                        } else {
                            notice = "附件内容无法解析"
                            onResult(null)
                        }
                    }
                    is work.keepwriting.core.DshMux.Result.Err -> {
                        notice = "附件读取失败：${res.code}"
                        onResult(null)
                    }
                }
            }
        })
    }

    /** feed 事件（NativeShellConnection 转发，executor 线程）：stream 逐字、message 定格。 */
    fun onFeedEvent(event: String, dataJson: String) {
        val id = roomId ?: return
        when (event) {
            "stream" -> {
                val obj = Json.parse(dataJson) as? Json.Value.Object ?: return
                val session = (obj.members["sessionId"] as? Json.Value.StringValue)?.value
                if (session != null && session != id) return
                val phase = (obj.members["phase"] as? Json.Value.StringValue)?.value
                // 群内多成员并行：不拼接单一流（后到 start 会 conflate 前一成员的文本），
                // 流式仅作提示，条目落定一律经 message 事件（appendBotMessage 去重）
                streamingText = if (phase == "end") null else "成员正在输入…"
            }
            "message" -> {
                val obj = Json.parse(dataJson) as? Json.Value.Object ?: return
                val session = (obj.members["sessionId"] as? Json.Value.StringValue)?.value
                if (session != null && session != id) return
                streamingText = null
                val item = (obj.members["item"] as? Json.Value.Object) ?: return
                val kind = (item.members["kind"] as? Json.Value.StringValue)?.value
                val text = (item.members["text"] as? Json.Value.StringValue)?.value
                if (kind == "bot" && text != null) {
                    val seq = (item.members["seq"] as? Json.Value.NumberValue)?.raw?.toLongOrNull()
                    val name = (item.members["name"] as? Json.Value.StringValue)?.value
                    val color = (item.members["color"] as? Json.Value.StringValue)?.value
                    // 与私聊同款：end 定格过的无序号同文本条目升级补序号，不重复追加
                    when (val d = ChatRender.appendBotMessage(rows.map { it.item }, text, seq)) {
                        is ChatRender.AppendDecision.Add ->
                            appendItem((d.item as ChatRender.Item.Message).copy(speakerName = name, speakerColor = color))
                        is ChatRender.AppendDecision.Upgrade ->
                            rows[d.index] = Row(rows[d.index].key, d.item.copy(speakerName = name, speakerColor = color))
                    }
                }
            }
        }
    }

    fun stopRoomWork() {
        val id = roomId ?: return
        api("stop-room-work", RoomChat.stopRoomWork(id)) { res ->
            notice = if (res is DsbotApi.Result.Ok) "已请求停止群工作" else "停止群失败"
        }
    }

    fun stopMember(botId: String) {
        api("stop-member-work", "{\"roomId\":\"\",\"botId\":${work.keepwriting.core.JsonString.of(botId)}}") { res ->
            notice = if (res is DsbotApi.Result.Ok) "已请求停止该成员" else "停止成员失败"
        }
    }

    fun showProgress() {
        val id = roomId ?: return
        api("room-progress", RoomChat.roomProgress(id)) { res ->
            notice = when (res) {
                is DsbotApi.Result.Ok -> {
                    // value 为成员进度数组：逐元素取 botId/阶段文本；空数组=当前无进行中工作
                    val arr = res.value as? Json.Value.Array
                    val lines = arr?.items?.mapNotNull { el ->
                        val o = el as? Json.Value.Object ?: return@mapNotNull null
                        val who = listOf("botId", "name").firstNotNullOfOrNull { k ->
                            (o.members[k] as? Json.Value.StringValue)?.value
                        } ?: "?"
                        val what = listOf("stage", "task", "summary", "text").firstNotNullOfOrNull { k ->
                            (o.members[k] as? Json.Value.StringValue)?.value
                        } ?: ""
                        who + if (what.isNotEmpty()) ": " + what else ""
                    }.orEmpty()
                    if (lines.isEmpty()) "当前没有成员在工作" else "成员进度：" + lines.joinToString("; ").take(200)
                }
                is DsbotApi.Result.BotError -> "进度不可用：" + res.code
                is DsbotApi.Result.Failure -> "进度不可用（网络）"
            }
        }
    }

    fun goalDraft(text: String) {
        val id = roomId ?: return
        api("goal-draft", RoomChat.goalDraft(id, text)) { res ->
            notice = when (res) {
                is DsbotApi.Result.Ok -> "目标草稿已提交：${Json.encode(res.value).take(150)}"
                is DsbotApi.Result.BotError -> "目标草稿失败：${res.code} ${res.message.take(60)}"
                is DsbotApi.Result.Failure -> "目标草稿失败（网络）"
            }
        }
    }

    fun goalControl(action: String) {
        val id = roomId ?: return
        api("goal-control", RoomChat.goalControl(id, action)) { res ->
            notice = when (res) {
                is DsbotApi.Result.Ok -> "目标操作（$action）完成：${Json.encode(res.value).take(120)}"
                is DsbotApi.Result.BotError -> "目标操作失败：${res.code}"
                is DsbotApi.Result.Failure -> "目标操作失败（网络）"
            }
        }
    }


    /** 卡片作答（dsbot-interactive-cards）：同私聊。 */
    fun answerCard(endpoint: String, payloadJson: String, onDone: (Boolean) -> Unit) {
        execute(Runnable {
            api(endpoint, payloadJson) { res ->
                when (res) {
                    is DsbotApi.Result.Ok -> onDone(true)
                    is DsbotApi.Result.BotError -> { notice = "作答失败：" + res.code; onDone(false) }
                    is DsbotApi.Result.Failure -> { notice = "作答失败（网络）"; onDone(false) }
                }
            }
        })
    }

    fun postNotice(text: String?) {
        execute(Runnable { notice = text })
    }

    private fun appendItem(item: ChatRender.Item) {
        rows.add(Row(nextRowKey++, item))
        if (openPending) liveInFlight.add(item)
    }
}
