package work.keepwriting.dshanywhere

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import work.keepwriting.core.ChatRender
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.DsbotModels
import work.keepwriting.core.Json
import work.keepwriting.core.JsonString

/**
 * 聊天页控制器（dsbot-bot-chat）：负责某 Bot 私聊的数据与操作——打开（chat 端点取
 * 首页并渲染，重进即重取整体替换）、发送（send，写失败提示不重试）、流式（StreamBuffer
 * 拼接、message 事件经 ChatRender.appendBotMessage 升级/追加）、更早分页（earlier，
 * cursor 原始值透传）、停止/恢复/新开分段/打开旧分段/统计/Bot 间记录。
 *
 * 线程模型：所有状态变更与请求都收敛在注入 api 闭包背后的同一 executor 单线程
 * （openSeq 等无锁安全）；Compose 可观察状态从该线程写、主线程读。
 *
 * ID 语义：botId 是 Bot 身份（state.bots[].id，stream/message 帧按它过滤）；
 * sessionId 是会话（chat 应答回填，send 提交用）。跳转必须传 Entry.id / PendingItem.botId。
 */
class ChatController(
    private val api: (endpoint: String, payloadJson: String, onResult: (DsbotApi.Result) -> Unit) -> Unit,
    /** 状态变更收敛通道（NativeShellConnection 注入 executor::execute）：单线程读写免锁。 */
    private val execute: (Runnable) -> Unit,
    /** 附件上传通道（dsbot-attachments）：uploadFileBinary，executor 内回调。 */
    private val upload: (
        sessionId: String, name: String, mediaType: String?, bytes: ByteArray,
        onResult: (DsbotApi.Result) -> Unit,
    ) -> Unit = { _, _, _, _, _ -> },
    /** mux RPC 通道（/api/remote.mux WebSocket 短时会话）：executor 内回调。 */
    private val muxCall: (endpoint: String, requestJson: String, onResult: (work.keepwriting.core.DshMux.Result) -> Unit) -> Unit = { _, _, _ -> },
) {
    /** 渲染行（稳定 key 供 LazyColumn 锚定：earlier 前插保持滚动位置）。 */
    data class Row(val key: Long, val item: ChatRender.Item)

    val rows = mutableStateListOf<Row>()
    var streamingText by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var working by mutableStateOf(false)
        private set
    var interrupted by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var canLoadEarlier by mutableStateOf(false)
        private set
    var loadingEarlier by mutableStateOf(false)
        private set
    /** 首页加载/分段切换完成后自增：UI 据此滚动到底（与网页端重进在底部一致）。 */
    var jumpSignal by mutableIntStateOf(0)
        private set

    private val stream = ChatRender.StreamBuffer()
    private var botId: String? = null
    private var currentSessionId: String? = null
    private var earlierCursor: Json.Value? = null
    private var openSeq = 0
    private var openPending = false
    private var nextRowKey = 0L

    /** open 在途期间到达的实时条目（message/stream end）：重取快照可能尚未包含
     *  服务器已写入的回复，替换后按文本去重回放，避免整页替换吃掉实时消息。 */
    private val liveInFlight = mutableListOf<ChatRender.Item>()

    private fun replaceAll(items: List<ChatRender.Item>) {
        rows.clear()
        items.forEach { rows.add(Row(nextRowKey++, it)) }
    }

    private fun appendItem(item: ChatRender.Item) {
        rows.add(Row(nextRowKey++, item))
        if (openPending) liveInFlight.add(item)
    }

    /** 打开（或重进）某 Bot 私聊：重取首页整体替换（feed 断线期间丢失的条目由此补齐）。 */
    fun open(id: String) {
        execute(Runnable { openOnExecutor(id) })
    }

    /** feed 重连成功后补取当前聊天页（断线窗口内发布的 message/stream 事件不可重放，
     *  整页重取是在途缓冲之外的一致性兜底）。仅在 executor 线程内调用。 */
    fun refreshIfOpen() {
        val id = botId ?: return
        openOnExecutor(id)
    }

    private fun openOnExecutor(id: String, done: (() -> Unit)? = null) {
        if (openPending && botId == id) {
            done?.invoke()
            return // 上次在途：防抖动
        }
        openPending = true
        val seq = ++openSeq
        if (botId != id) currentSessionId = null // 切换目标：旧 sessionId 立即失效，在途窗口内发送被拒绝而非错发旧会话
        botId = id
        streamingText = null
        val payload = "{\"botId\":${JsonString.of(id)}}"
        api("chat", payload) { res ->
            openPending = false
            if (seq != openSeq || botId != id) return@api // 已被更新的 open 取代
            when (res) {
                is DsbotApi.Result.Ok -> {
                    val page = DsbotModels.decodeChatPage(res.value)
                    val rendered = ChatRender.render(page)
                    currentSessionId = page.sessionId
                    replaceAll(rendered.items)
                    // 回放在途实时条目（快照未含的重取期间到达的消息；按文本+方向去重）
                    val replay = liveInFlight.toList()
                    liveInFlight.clear()
                    for (extra in replay) {
                        val msg = extra as? ChatRender.Item.Message ?: continue
                        val dup = rendered.items.any {
                            it is ChatRender.Item.Message && it.fromUser == msg.fromUser && it.markdown == msg.markdown
                        }
                        if (!dup) appendItem(extra)
                    }
                    earlierCursor = rendered.cursor
                    canLoadEarlier = rendered.cursor != null
                    notice = null
                    jumpSignal++
                }
                is DsbotApi.Result.BotError -> {
                    liveInFlight.clear() // 重取失败：丢弃在途缓冲，避免下次回放过期条目
                    notice = "加载聊天失败：${res.code}"
                }
                is DsbotApi.Result.Failure -> {
                    liveInFlight.clear()
                    notice = "加载聊天失败（网络）"
                }
            }
            done?.invoke() // 分段切换等后续提示在重取完成后生效（不被 notice=null 覆盖）
        }
    }

    /** 更早记录分页：earlier 端点取上一页并前插；应答无 cursor 即到最早。 */
    fun loadEarlier() {
        val sid = currentSessionId ?: return
        val cursor = earlierCursor ?: return
        if (loadingEarlier) return
        loadingEarlier = true
        val payload = "{\"sessionId\":${JsonString.of(sid)},\"cursor\":${Json.encode(cursor)}}"
        api("earlier", payload) { res ->
            loadingEarlier = false
            when (res) {
                is DsbotApi.Result.Ok -> {
                    val rendered = ChatRender.render(DsbotModels.decodeChatPage(res.value))
                    val newRows = rendered.items.map { Row(nextRowKey++, it) }
                    rows.addAll(0, newRows)
                    earlierCursor = rendered.cursor
                    canLoadEarlier = rendered.cursor != null
                }
                is DsbotApi.Result.BotError -> notice = "更早记录加载失败：${res.code}"
                is DsbotApi.Result.Failure -> notice = "更早记录加载失败（网络）"
            }
        }
    }

    /**
     * 发送文字：立即上屏用户消息（乐观更新）并返回 true；失败时回滚该条并提示，
     * 不重试。会话未就绪（open 未完成）直接拒绝，不得以 botId 冒充 sessionId。
     */
    fun send(text: String): Boolean {
        val sid = currentSessionId ?: run {
            notice = "会话未就绪，稍候再发"
            return false
        }
        busy = true
        val optimistic = ChatRender.Item.Message(fromUser = true, markdown = text, turn = null, seq = null)
        appendItem(optimistic)
        val optimisticRowKey = rows.last().key
        api("send", "{\"sessionId\":${JsonString.of(sid)},\"text\":${JsonString.of(text)}}") { res ->
            if (res is DsbotApi.Result.Ok) {
                busy = false
                notice = null
            } else {
                // 失败回滚乐观消息并提示（不自动重试）；仅摘除仍是末行的乐观条目，
                // 且同步剔出在途缓冲——否则下次重取回放会让失败消息"复活"
                val last = rows.lastOrNull()
                if (last?.key == optimisticRowKey) rows.removeAt(rows.lastIndex)
                liveInFlight.removeAll { it === optimistic }
                busy = false
                notice = "发送失败，未自动重试"
            }
        }
        return true
    }

    /** feed 事件（经 NativeShellConnection 在 executor 线程转发）：stream 逐字、message 定格。 */
    fun onFeedEvent(event: String, dataJson: String) {
        when (event) {
            "stream" -> {
                val obj = Json.parse(dataJson) as? Json.Value.Object ?: return
                val botIdNow = botId ?: return
                val frameBot = (obj.members["botId"] as? Json.Value.StringValue)?.value
                if (frameBot != null && frameBot != botIdNow) return // 其他 Bot 的流
                val attempt = (obj.members["attemptId"] as? Json.Value.StringValue)?.value
                val phase = (obj.members["phase"] as? Json.Value.StringValue)?.value
                val text = (obj.members["text"] as? Json.Value.StringValue)?.value
                // 丢弃的帧返回 null：保留上一帧的全文，不清空气泡
                stream.onStream(frameBot, currentSessionId, attempt, phase, text)?.let { streamingText = it }
                if (phase == "end") {
                    // end 定格：有文本成条目，空流也清气泡（settle 空 return null 的坑）
                    val full = stream.settle()
                    if (full != null) appendItem(ChatRender.Item.Message(fromUser = false, markdown = full, turn = null, seq = null))
                    streamingText = null
                }
            }
            "message" -> {
                val obj = Json.parse(dataJson) as? Json.Value.Object ?: return
                val frameBot = (obj.members["botId"] as? Json.Value.StringValue)?.value
                if (frameBot != null && botId != null && frameBot != botId) return
                if (stream.inProgress()) {
                    stream.settle()
                    streamingText = null
                }
                val item = (obj.members["item"] as? Json.Value.Object) ?: return
                val kind = (item.members["kind"] as? Json.Value.StringValue)?.value
                val text = (item.members["text"] as? Json.Value.StringValue)?.value
                if (kind == "bot" && text != null) {
                    val seq = (item.members["seq"] as? Json.Value.NumberValue)?.raw?.toLongOrNull()
                    when (val decision = ChatRender.appendBotMessage(rows.map { it.item }, text, seq)) {
                        is ChatRender.AppendDecision.Add -> appendItem(decision.item)
                        is ChatRender.AppendDecision.Upgrade ->
                            rows[decision.index] = Row(rows[decision.index].key, decision.item)
                    }
                }
            }
        }
    }

    /** 工作状态联动：state 刷新时同步该 Bot 的 working（Entry.working 装配产物）。 */
    fun syncWorking(w: Boolean) {
        working = w
    }

    fun stop() {
        val id = botId ?: return
        api("stop-member-work", "{\"roomId\":\"\",\"botId\":${JsonString.of(id)}}") { res ->
            if (res is DsbotApi.Result.Ok) {
                working = false
                interrupted = true
            } else {
                notice = "停止失败"
            }
        }
    }

    fun resume() {
        val id = botId ?: return
        api("resume", "{\"botId\":${JsonString.of(id)}}") { res ->
            if (res is DsbotApi.Result.Ok) interrupted = false else notice = "恢复失败"
        }
    }

    fun newPart() {
        val id = botId ?: return
        api("bot-new-part", "{\"botId\":${JsonString.of(id)}}") { res ->
            when (res) {
                is DsbotApi.Result.Ok -> {
                    interrupted = false
                    openOnExecutor(id) { notice = "已新开分段" } // 提示在重取完成后生效
                }
                is DsbotApi.Result.BotError -> notice = "新开分段失败：${res.code}"
                is DsbotApi.Result.Failure -> notice = "新开分段失败（网络）"
            }
        }
    }

    /** 打开旧分段（或切回当前分段）：切换后重取显示对应分段条目。 */
    fun openPart(partIndex: Int, partId: String, total: Int) {
        val id = botId ?: return
        api("bot-open-part", "{\"botId\":${JsonString.of(id)},\"part\":${JsonString.of(partId)}}") { res ->
            when (res) {
                is DsbotApi.Result.Ok -> {
                    openOnExecutor(id) { notice = "已切换到分段 ${partIndex + 1}/$total" }
                }
                is DsbotApi.Result.BotError -> notice = "切换分段失败：${res.code}"
                is DsbotApi.Result.Failure -> notice = "切换分段失败（网络）"
            }
        }
    }

    // ---- 统计与 Bot 间记录：弹窗展示（pretty JSON 可读化，替代原 notice 裸串）----

    var statsText by mutableStateOf<String?>(null)
        private set
    var exchangesText by mutableStateOf<String?>(null)
        private set

    fun showStats() {
        val id = botId ?: return
        api("run-stats", "{\"botId\":${JsonString.of(id)}}") { res ->
            statsText = if (res is DsbotApi.Result.Ok) prettyJson(Json.encode(res.value)) else "统计不可用"
        }
    }

    fun dismissStats() {
        statsText = null
    }

    fun showExchanges() {
        api("exchange", "{}") { res ->
            exchangesText = if (res is DsbotApi.Result.Ok) prettyJson(Json.encode(res.value)) else "Bot 间记录不可用"
        }
    }

    fun dismissExchanges() {
        exchangesText = null
    }

    /** org.json 的缩进序列化把裸串转成多行可读形态；非 JSON 原样截断兜底。 */
    private fun prettyJson(raw: String): String = try {
        org.json.JSONTokener(raw).let { tokener ->
            when (val v = tokener.nextValue()) {
                is org.json.JSONArray -> v.toString(2)
                is org.json.JSONObject -> v.toString(2)
                else -> raw.take(500)
            }
        }
    } catch (_: Exception) {
        raw.take(500)
    }

    // ---- 附件（dsbot-attachments）----

    /** 待发送附件：Idle 待上传 / Uploading / Ready(receipt) / Failed(原因，可重试)。 */
    data class PendingAttachment(
        val id: Long,
        val name: String,
        val bytes: ByteArray,
        val isImage: Boolean,
        val mediaType: String?,
    ) {
        var status by mutableStateOf("idle") // idle|uploading|ready|failed
        var receiptId by mutableStateOf<String?>(null)
        var failReason by mutableStateOf<String?>(null)
    }

    val attachments = mutableStateListOf<PendingAttachment>()
    private var nextAttachmentId = 1L

    /** 选取完成加入附件栏并立即上传（App 主线程调用；状态变更经 executor）。 */
    fun addAttachment(name: String?, mediaType: String?, bytes: ByteArray) {
        val att = PendingAttachment(
            id = nextAttachmentId++,
            name = name?.takeIf { it.isNotBlank() } ?: "file",
            bytes = bytes,
            isImage = mediaType?.startsWith("image/") == true,
            mediaType = mediaType,
        )
        execute(Runnable {
            attachments.add(att)
            uploadAttachment(att)
        })
    }

    /**
     * 从 ContentResolver URI 读取附件（相册/文件选择器/相机输出）：读字节与查显示名
     * 都在 executor 后台执行（大文件不阻塞主线程）。相机临时文件读后即删。
     */
    fun addAttachmentFromUri(resolver: android.content.ContentResolver, uri: android.net.Uri, deleteAfterRead: Boolean = false) {
        execute(Runnable {
            val ok = runCatching {
                val name = queryDisplayName(resolver, uri)
                val mediaType = resolver.getType(uri)
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                if (bytes.isNotEmpty()) {
                    val att = PendingAttachment(
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
            if (deleteAfterRead) runCatching { resolver.delete(uri, null, null) }
        })
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

    fun removeAttachment(id: Long) {
        execute(Runnable { attachments.removeAll { it.id == id } })
    }

    fun retryAttachment(id: Long) {
        execute(Runnable {
            val att = attachments.firstOrNull { it.id == id } ?: return@Runnable
            if (att.status == "failed") uploadAttachment(att)
        })
    }

    private fun uploadAttachment(att: PendingAttachment) {
        val sid = currentSessionId ?: run {
            att.status = "failed"
            att.failReason = "会话未就绪"
            return
        }
        att.status = "uploading"
        att.failReason = null
        upload(sid, att.name, att.mediaType, att.bytes) { res ->
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

    /**
     * 发送：有附件时要求全部 ready（uploading/failed 提示），经 mux session/prompt 提交
     * （content = file receipts + text），成功后清附件栏并乐观上屏；纯文本仍走 send。
     */
    fun sendOrAttach(text: String): Boolean {
        if (attachments.isEmpty()) return send(text)
        // 校验与提交整体收敛 executor（attachments/currentSessionId 只在 executor 读写）；
        // 拒绝路径经 notice 反馈（UI 的发送按钮在附件 uploading/failed 时已禁用）
        execute(Runnable {
            val sid = currentSessionId ?: run {
                notice = "会话未就绪，稍候再发"
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
            sendAttachmentsOnExecutor(sid, text)
        })
        return true
    }

    private fun sendAttachmentsOnExecutor(sid: String, text: String) {
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
            sessionId = sid,
            receiptIds = receipts,
            text = text.trim(),
        )
        muxCall("session/prompt", request) { res ->
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
    }

    /** UI 侧提示写入（图片保存结果等）。 */

    /** 卡片作答（dsbot-interactive-cards）：/api/bot 写端点，成功后回调（调用方消隐卡片）。 */
    fun answerCard(endpoint: String, payloadJson: String, onDone: (Boolean) -> Unit) {
        execute(Runnable {
            api(endpoint, payloadJson) { res ->
                when (res) {
                    is DsbotApi.Result.Ok -> onDone(true)
                    is DsbotApi.Result.BotError -> {
                        notice = "作答失败：" + res.code
                        onDone(false)
                    }
                    is DsbotApi.Result.Failure -> {
                        notice = "作答失败（网络）"
                        onDone(false)
                    }
                }
            }
        })
    }

    fun postNotice(text: String?) {
        execute(Runnable { notice = text })
    }

    /** 附件查看：mux session/attachment 取 base64 字节（executor 内回调）。 */
    fun readAttachment(attachmentRefJson: String, onResult: (ByteArray?) -> Unit) {
        execute(Runnable {
            val sid = currentSessionId ?: run {
                notice = "会话未就绪，无法查看附件"
                onResult(null)
                return@Runnable
            }
            muxCall(
                "session/attachment",
                work.keepwriting.core.AttachmentMessages.attachmentRequest(sid, attachmentRefJson),
            ) { res ->
                when (res) {
                    is work.keepwriting.core.DshMux.Result.Ok -> {
                        // HTTP server-response 的 result.value：{attachment, data:base64}
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
}
