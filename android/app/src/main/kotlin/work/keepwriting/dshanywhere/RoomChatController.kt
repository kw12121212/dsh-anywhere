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
    /** 打开/重取失败回调（chat-load-error-diagnostics 诊断上报）：executor 内触发，
     *  参数为原因代码（bot 错误码或 "network"）；不改变 notice 行为。 */
    private val onLoadFailure: ((code: String) -> Unit)? = null,
    /** 缓存键的主机部分（harness 的 host:port；dsbot-chat-cache）。 */
    private val hostOf: () -> String? = { null },
    /** 群历史读取通道：网络在独立 IO 线程、回调投回状态 executor（dsbot-chat-cache）；缺省退回 [api]。 */
    private val readApi: (endpoint: String, payloadJson: String, onResult: (DsbotApi.Result) -> Unit) -> Unit = api,
) {
    data class Row(val key: Long, val item: ChatRender.Item)

    val rows = mutableStateListOf<Row>()

    /**
     * 当前聊天读到的最新条目时间（目标 id 与服务端毫秒；dsbot-read-state 标记已读用）。带 id 是为了
     * 切换聊天时上一个聊天的时间不会被记到新聊天上。executor 线程写入。
     */
    var newest by mutableStateOf<Pair<String, Long>?>(null)
        private set

    private fun bumpNewest(id: String, at: Long?) {
        if (at == null || at <= 0) return
        val cur = newest?.takeIf { it.first == id }
        if (cur != null && cur.second >= at) return
        newest = id to at
    }
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

    /** 打开群聊：缓存先上屏并恢复滚动位置，再后台刷新（room-chat 含 running/speaking 与公告）。 */
    fun open(id: String) {
        execute(Runnable { openOnExecutor(id, restore = true) })
    }

    private var openPending = false
    private val liveInFlight = mutableListOf<ChatRender.Item>()

    /** 滚动恢复请求（dsbot-chat-cache）：自增时 UI 把列表滚到 [restorePosition]（只恢复属于当前群的位置）。 */
    var restoreSignal by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var restorePosition: Pair<String, work.keepwriting.core.ScrollMemory.Position>? = null
        private set

    // ---- 缓存与单飞（dsbot-chat-cache；全部在 executor 线程读写）----
    private val cache = work.keepwriting.core.ChatCache<ChatRender.Item>()
    private val scrollMemory = work.keepwriting.core.ScrollMemory()
    private val flight = work.keepwriting.core.ReadFlight()

    private fun keyOf(id: String) = work.keepwriting.core.ChatCache.key(hostOf(), "room", id)

    private fun saveToCache() {
        val id = roomId ?: return
        if (rows.isEmpty()) return
        cache.put(keyOf(id), work.keepwriting.core.ChatCache.Entry(rows.map { it.item }, cursor, id, extra = announcement))
    }

    private fun reconcile(fresh: List<ChatRender.Item>) {
        val old = rows.toList()
        val match = work.keepwriting.core.RowIdentity.match(old.map { it.item }, fresh)
        val next = fresh.mapIndexed { j, item -> if (match[j] >= 0) old[match[j]] else Row(nextRowKey++, item) }
        rows.clear()
        rows.addAll(next)
    }

    /** 页面离开时保存滚动位置（UI 线程调用，投递到 executor）。 */
    fun saveScroll(index: Int, offset: Int) {
        execute(Runnable { roomId?.let { scrollMemory.save(keyOf(it), index, offset) } })
    }

    /** 启动预取（只写缓存，不改当前显示）。 */
    fun prefetch(id: String) {
        execute(Runnable {
            if (roomId == id || cache[keyOf(id)] != null) return@Runnable
            readApi("room-chat", RoomChat.roomChatRequest(id)) { res ->
                if (res !is DsbotApi.Result.Ok || roomId == id || cache[keyOf(id)] != null) return@readApi
                val page = RoomChat.decodeRoomPage(res.value)
                val ann = (res.value as? Json.Value.Object)?.members?.get("announcement")?.let { (it as? Json.Value.StringValue)?.value }
                cache.put(keyOf(id), work.keepwriting.core.ChatCache.Entry(RoomChat.renderRoom(page), page.cursor, id, extra = ann))
            }
        })
    }

    /** 前台兜底轮询（executor 线程调用）：有成员流式提示时跳过，不清提示。 */
    fun pollRefresh() {
        val id = roomId ?: return
        if (streamingText != null) return
        openOnExecutor(id, keepStream = true)
    }

    /**
     * 打开/刷新（dsbot-chat-cache）：同私聊——换目标先存缓存、命中立即上屏，[restore] 时按滚动记忆恢复
     * （同一群重进也恢复），单飞读取首页后经 core 的 ChatRefresh 合并；非轮询路径清除「成员正在输入」提示
     * （断线丢掉 end 帧时不会卡住）。
     */
    private fun openOnExecutor(id: String, keepStream: Boolean = false, restore: Boolean = false) {
        if (roomId != id) {
            saveToCache()
            roomId = id
            liveInFlight.clear()
            pageUserRows = emptyList()
            pageMaxTime = -1L
            refreshOutboxView() // 发送箱按群保存：换群显示目标群自己的本地行
            val hit = cache[keyOf(id)]
            if (hit != null) {
                replaceRows(hit.items)
                cursor = hit.cursor
                canLoadEarlier = hit.cursor != null
                announcement = hit.extra
                notice = null
            } else {
                rows.clear()
                cursor = null
                canLoadEarlier = false
                announcement = null
            }
            running = emptyList()
        }
        if (!keepStream) streamingText = null
        if (restore && rows.isNotEmpty()) {
            val pos = scrollMemory[keyOf(id)]
            if (pos != null) {
                restorePosition = id to pos
                restoreSignal++
            } else {
                jumpSignal++
            }
        }
        val gen = when (val begin = flight.begin(id)) {
            is work.keepwriting.core.ReadFlight.Begin.Start -> begin.gen
            else -> return // 在途：已排队一次或忽略
        }
        openPending = true
        readApi("room-chat", RoomChat.roomChatRequest(id)) { res ->
            val fin = flight.finish(gen)
            if (fin.stale || roomId != id) return@readApi
            openPending = false
            val wasEmpty = rows.isEmpty()
            when (res) {
                is DsbotApi.Result.Ok -> {
                    val page = RoomChat.decodeRoomPage(res.value)
                    notePage(page)
                    running = page.running
                    announcement = (res.value as? Json.Value.Object)?.members?.get("announcement")?.let { (it as? Json.Value.StringValue)?.value }
                    val merge = work.keepwriting.core.ChatRefresh.merge(
                        rows.map { it.item }, RoomChat.renderRoom(page),
                        isLocal = { it is ChatRender.Item.Message && it.seq == null },
                        matches = { a, b ->
                            a is ChatRender.Item.Message && b is ChatRender.Item.Message &&
                                a.fromUser == b.fromUser && a.markdown == b.markdown
                        },
                    )
                    if (merge.changed) reconcile(merge.items)
                    if (!merge.keepCursor) {
                        cursor = page.cursor
                        canLoadEarlier = page.cursor != null
                    }
                    // 回放在途实时条目（重取期间到达的消息；按文本+方向去重）
                    val replay = liveInFlight.toList()
                    liveInFlight.clear()
                    for (extra in replay) {
                        val msg = extra as? ChatRender.Item.Message ?: continue
                        // 带 seq 的按 seq 去重（同文本的旧消息不应吞掉新到的这条），无 seq 才退回按文本
                        val dup = rows.any {
                            val m = it.item as? ChatRender.Item.Message
                            m != null && if (msg.seq != null) m.seq == msg.seq else (m.fromUser == msg.fromUser && m.markdown == msg.markdown)
                        }
                        if (!dup) appendItem(extra)
                    }
                    notice = null
                    if (wasEmpty) jumpSignal++
                    saveToCache()
                    reconcileOutbox()
                }
                is DsbotApi.Result.BotError -> {
                    liveInFlight.clear()
                    notice = tr("加载群聊失败：${res.code}", "Could not load group chat: ${res.code}")
                    onLoadFailure?.invoke(res.code)
                }
                is DsbotApi.Result.Failure -> {
                    liveInFlight.clear()
                    notice = tr("加载群聊失败（网络）", "Could not load group chat (network)")
                    onLoadFailure?.invoke("network")
                }
            }
            if (fin.rerun) openOnExecutor(id, keepStream = true)
        }
    }

    private fun replaceRows(items: List<ChatRender.Item>) {
        rows.clear()
        items.forEach { rows.add(Row(nextRowKey++, it)) }
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
                    saveToCache()
                }
                is DsbotApi.Result.BotError -> notice = tr("更早记录加载失败：${res.code}", "Could not load earlier messages: ${res.code}")
                is DsbotApi.Result.Failure -> notice = tr("更早记录加载失败（网络）", "Could not load earlier messages (network)")
            }
        }
    }

    // ---- 发送箱（dsbot-outbox-retry；全部在 executor 线程读写）----

    private val outboxStore = work.keepwriting.core.Outbox()
    /** 当前群的本地待确认消息（发送中 / 未发送），渲染在聊天末尾。 */
    var outbox by mutableStateOf<List<work.keepwriting.core.Outbox.Entry>>(emptyList())
        private set
    var sentSignal by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var sendScrollSignal by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    private fun refreshOutboxView() {
        outbox = roomId?.let { outboxStore.entries(keyOf(it)) } ?: emptyList()
    }

    /**
     * 群聊的对账依据：真实 room-chat 的用户行没有 seq（实测 {id, kind:user, text, time}），以服务端 time
     * 为顺序值。每次读取群页时记下用户行 (time, text) 与页面最大 time；窗口取发送时的最大 time（服务端时钟，
     * 与手机本地时钟无关）。
     */
    private var pageUserRows: List<work.keepwriting.core.Outbox.UserRow> = emptyList()
    private var pageMaxTime: Long = -1L

    /** 群页还没读到（不知道服务端最新 time）时不给自动确认的机会：本地行停在「已发送」，可删除。 */
    private fun window(): Long = if (pageMaxTime >= 0) pageMaxTime else Long.MAX_VALUE

    private fun notePage(page: RoomChat.RoomPage) {
        pageUserRows = page.items.filter { it.kind == "user" && it.text != null }
            .map { work.keepwriting.core.Outbox.UserRow(it.time ?: it.seq, it.text!!) }
        pageMaxTime = maxOf(pageMaxTime, page.items.maxOfOrNull { it.time ?: -1L } ?: -1L)
        roomId?.let { bumpNewest(it, page.items.maxOfOrNull { it.time ?: 0L }) }
    }

    private fun reconcileOutbox() {
        val id = roomId ?: return
        if (outboxStore.reconcile(keyOf(id), pageUserRows).isNotEmpty()) refreshOutboxView()
    }

    /** 群消息提交：进入发送箱后经 session/prompt（sessionId=room 会话；@提及/引用都在文本中）。 */
    fun send(text: String): Boolean {
        if (roomId == null) return false
        execute(Runnable { enqueue(text, text, emptyList()) })
        return true
    }

    private fun enqueue(text: String, display: String, receipts: List<String>) {
        val id = roomId ?: run {
            notice = tr("群会话未就绪，稍候再发", "Group session not ready, try again shortly")
            return
        }
        val entry = outboxStore.add(keyOf(id), text, display, receipts, window())
        refreshOutboxView()
        sendScrollSignal++
        dispatch(entry, id)
    }

    private fun dispatch(entry: work.keepwriting.core.Outbox.Entry, sessionId: String) {
        val request = work.keepwriting.core.AttachmentMessages.promptRequest(
            requestId = java.util.UUID.randomUUID().toString(),
            sessionId = sessionId,
            receiptIds = entry.receipts,
            text = if (entry.receipts.isEmpty()) entry.text else entry.text.trim(),
        )
        rpcCall("session/prompt", request) { res ->
            if (res is work.keepwriting.core.DshMux.Result.Err) {
                outboxStore.setState(entry.id, work.keepwriting.core.Outbox.State.FAILED)
                notice = tr("发送失败：${res.code}，可点按重试", "Send failed: ${res.code}. Tap to retry")
                refreshOutboxView()
            } else {
                outboxStore.setState(entry.id, work.keepwriting.core.Outbox.State.SENT)
                sentSignal++
                notice = null
                refreshOutboxView()
                // 群的 feed 不推用户消息（只推 room 信号）：成功后立即重读群页，以服务端用户行确认本地行
                if (roomId == sessionId) openOnExecutor(sessionId, keepStream = true)
            }
        }
    }

    /** 点按「未发送」重试：只在未发送状态下重新发出一次。 */
    fun retryOutbox(id: Long) {
        execute(Runnable {
            val entry = outboxStore.get(id) ?: return@Runnable
            if (entry.state != work.keepwriting.core.Outbox.State.FAILED) return@Runnable
            val room = roomId ?: return@Runnable
            if (entry.chatKey != keyOf(room)) return@Runnable // 只重试当前群的行
            val sending = outboxStore.setState(id, work.keepwriting.core.Outbox.State.SENDING) ?: return@Runnable
            refreshOutboxView()
            dispatch(sending, room)
        })
    }

    fun deleteOutbox(id: Long) {
        execute(Runnable {
            if (outboxStore.remove(id)) refreshOutboxView()
        })
    }

    // ---- 附件（dsbot-attachments；与 ChatController 同构，session 为群会话 id）----

    val attachments = mutableStateListOf<ChatController.PendingAttachment>()
    private var nextAttachmentId = 1L

    /** 从 ContentResolver URI 读取附件（相册/文件选择器）：读字节在 executor 后台。 */
    fun addAttachmentFromUri(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        // 读取与照片压缩（解码/旋转/编码可达秒级）在附件 IO 线程执行，不阻塞状态 executor；
        // 结果投回 executor 入附件栏并上传（dsbot-outbox-retry）
        attachIo.execute {
            val packed = runCatching {
                val rawName = queryDisplayName(resolver, uri)
                val rawType = resolver.getType(uri)
                val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                if (raw.isEmpty()) null else ImageCompress.maybeCompress(raw, rawType, rawName)
            }.getOrNull()
            execute(Runnable {
                if (packed == null) {
                    notice = tr("无法读取所选内容", "Cannot read the selected item")
                    return@Runnable
                }
                val att = ChatController.PendingAttachment(
                    id = nextAttachmentId++,
                    name = packed.name,
                    bytes = packed.bytes,
                    isImage = packed.mediaType?.startsWith("image/") == true,
                    mediaType = packed.mediaType,
                )
                attachments.add(att)
                uploadAttachment(att)
            })
        }
    }

    private val attachIo = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "attach-io").apply { isDaemon = true } }


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
            att.failReason = tr("群会话未就绪", "Group session not ready")
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
                        att.failReason = tr("上传应答无法解析", "Upload response could not be read")
                    }
                }
                is DsbotApi.Result.BotError -> {
                    att.status = "failed"
                    att.failReason = tr("上传被拒：${res.code}", "Upload rejected: ${res.code}")
                }
                is DsbotApi.Result.Failure -> {
                    att.status = "failed"
                    att.failReason = tr("上传失败（网络）", "Upload failed (network)")
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
                notice = tr("群会话未就绪，稍候再发", "Group session not ready, try again shortly")
                return@Runnable
            }
            if (attachments.any { it.status == "uploading" }) {
                notice = tr("附件还在上传", "Attachments are still uploading")
                return@Runnable
            }
            if (attachments.any { it.status == "failed" }) {
                notice = tr("有附件上传失败，重试或移除后再发", "An attachment failed to upload. Retry or remove it, then send")
                return@Runnable
            }
            if (attachments.any { it.status != "ready" }) return@Runnable
            if (attachments.isEmpty()) return@Runnable // 连点：上一次已把附件转入发送箱，不再发出空消息
            val receipts = attachments.mapNotNull { it.receiptId }
            val visible = buildString {
                if (text.isNotEmpty()) append(text).append('\n')
                attachments.forEach { append(tr("[附件] ", "[Attachment] ")).append(it.name).append('\n') }
            }.trimEnd()
            attachments.clear()
            enqueue(text.trim(), visible, receipts)
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
                notice = tr("群会话未就绪，无法查看附件", "Group session not ready, cannot open the attachment")
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
                            notice = tr("附件内容无法解析", "Attachment content could not be read")
                            onResult(null)
                        }
                    }
                    is work.keepwriting.core.DshMux.Result.Err -> {
                        notice = tr("附件读取失败：${res.code}", "Could not read attachment: ${res.code}")
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
                streamingText = if (phase == "end") null else tr("成员正在输入…", "A member is typing…")
            }
            "message" -> {
                val obj = Json.parse(dataJson) as? Json.Value.Object ?: return
                val session = (obj.members["sessionId"] as? Json.Value.StringValue)?.value
                if (session != null && session != id) return
                streamingText = null
                val item = (obj.members["item"] as? Json.Value.Object) ?: return
                bumpNewest(id, (item.members["time"] as? Json.Value.NumberValue)?.raw?.toLongOrNull())
                val kind = (item.members["kind"] as? Json.Value.StringValue)?.value
                val text = (item.members["text"] as? Json.Value.StringValue)?.value
                if (kind == "user" && text != null) {
                    // 用户消息实时上屏并对账发送箱（dsbot-outbox-retry）
                    val seq = (item.members["seq"] as? Json.Value.NumberValue)?.raw?.toLongOrNull()
                    val known = seq != null && rows.any { (it.item as? ChatRender.Item.Message)?.let { m -> m.fromUser && m.seq == seq } == true }
                    if (seq != null && !known) appendItem(ChatRender.Item.Message(fromUser = true, markdown = text, turn = null, seq = seq))
                    reconcileOutbox()
                }
                if (kind == "bot" || kind == "user") {
                    // 实时条目附带的交付文件与图片引用即时显示（dsbot-reader-files）——放在消息之后追加
                    val extras = ChatRender.extras(work.keepwriting.core.DsbotModels.ChatItem(null, null, null, null, null, item))
                    if (extras.isNotEmpty()) pendingExtras = extras
                }
                if (kind == "bot" && text != null) {
                    val seq = (item.members["seq"] as? Json.Value.NumberValue)?.raw?.toLongOrNull()
                    val name = (item.members["name"] as? Json.Value.StringValue)?.value
                    val color = (item.members["color"] as? Json.Value.StringValue)?.value
                    // 与私聊同款：end 定格过的无序号同文本条目升级补序号，不重复追加
                    // 群长帖的全文文件随实时条目带上（「阅读全文」，dsbot-reader-files）
                    val whole = (ChatRender.renderItem(work.keepwriting.core.DsbotModels.ChatItem("bot", text, null, seq, null, item)) as? ChatRender.Item.Message)?.wholeFileJson
                    when (val d = ChatRender.appendBotMessage(rows.map { it.item }, text, seq)) {
                        is ChatRender.AppendDecision.Add ->
                            appendItem((d.item as ChatRender.Item.Message).copy(speakerName = name, speakerColor = color, wholeFileJson = whole))
                        is ChatRender.AppendDecision.Upgrade ->
                            rows[d.index] = Row(rows[d.index].key, d.item.copy(speakerName = name, speakerColor = color, wholeFileJson = whole))
                    }
                }
                pendingExtras?.let { extras -> for (x in extras) if (rows.none { it.item == x }) appendItem(x) }
                pendingExtras = null
            }
        }
    }

    fun stopRoomWork() {
        val id = roomId ?: return
        api("stop-room-work", RoomChat.stopRoomWork(id)) { res ->
            notice = if (res is DsbotApi.Result.Ok) tr("已请求停止群工作", "Asked the group to stop") else tr("停止群失败", "Could not stop the group")
        }
    }

    fun stopMember(botId: String) {
        api("stop-member-work", "{\"roomId\":\"\",\"botId\":${work.keepwriting.core.JsonString.of(botId)}}") { res ->
            notice = if (res is DsbotApi.Result.Ok) tr("已请求停止该成员", "Asked the member to stop") else tr("停止成员失败", "Could not stop the member")
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
                    if (lines.isEmpty()) tr("当前没有成员在工作", "No member is working") else tr("成员进度：", "Member progress:") + lines.joinToString("; ").take(200)
                }
                is DsbotApi.Result.BotError -> tr("进度不可用：", "Progress unavailable: ") + res.code
                is DsbotApi.Result.Failure -> tr("进度不可用（网络）", "Progress unavailable (network)")
            }
        }
    }

    fun goalDraft(text: String) {
        val id = roomId ?: return
        api("goal-draft", RoomChat.goalDraft(id, text)) { res ->
            notice = when (res) {
                is DsbotApi.Result.Ok -> tr("目标草稿已提交：${Json.encode(res.value).take(150)}", "Goal draft submitted: ${Json.encode(res.value).take(150)}")
                is DsbotApi.Result.BotError -> tr("目标草稿失败：${res.code} ${res.message.take(60)}", "Goal draft failed: ${res.code} ${res.message.take(60)}")
                is DsbotApi.Result.Failure -> tr("目标草稿失败（网络）", "Goal draft failed (network)")
            }
        }
    }

    fun goalControl(action: String) {
        val id = roomId ?: return
        api("goal-control", RoomChat.goalControl(id, action)) { res ->
            notice = when (res) {
                is DsbotApi.Result.Ok -> tr("目标操作（$action）完成：${Json.encode(res.value).take(120)}", "Goal action ($action) done: ${Json.encode(res.value).take(120)}")
                is DsbotApi.Result.BotError -> tr("目标操作失败：${res.code}", "Goal action failed: ${res.code}")
                is DsbotApi.Result.Failure -> tr("目标操作失败（网络）", "Goal action failed (network)")
            }
        }
    }


    /** 卡片作答（dsbot-interactive-cards）：同私聊。 */
    fun answerCard(endpoint: String, payloadJson: String, onDone: (Boolean) -> Unit) {
        execute(Runnable {
            api(endpoint, payloadJson) { res ->
                when (res) {
                    is DsbotApi.Result.Ok -> onDone(true)
                    is DsbotApi.Result.BotError -> { notice = work.keepwriting.core.ChatBars.actionError(res.code); onDone(false) }
                    is DsbotApi.Result.Failure -> { notice = work.keepwriting.core.ChatBars.actionError("network"); onDone(false) }
                }
            }
        })
    }

    fun postNotice(text: String?) {
        execute(Runnable { notice = text })
    }

    // ---- 群统计：逐成员 run-stats 汇总成一份可读弹窗 ----

    var statsText by mutableStateOf<String?>(null)
        private set

    /** 汇总 [ids]（与 [names] 一一对应）各成员的 run-stats；全部应答后拼接展示。 */
    fun showStats(ids: List<String>, names: List<String>) {
        if (ids.isEmpty()) {
            statsText = tr("群内没有成员", "No members in this group")
            return
        }
        val parts = arrayOfNulls<String>(ids.size)
        val pending = java.util.concurrent.atomic.AtomicInteger(ids.size)
        ids.forEachIndexed { i, id ->
            api("run-stats", "{\"botId\":${work.keepwriting.core.JsonString.of(id)}}") { res ->
                parts[i] = if (res is DsbotApi.Result.Ok) {
                    val body = work.keepwriting.core.BotStatsText.runStats(res.value).ifEmpty { tr("暂无统计数据", "No stats yet") }
                    tr("【${names.getOrElse(i) { id }}】\n$body", "[${names.getOrElse(i) { id }}]\n$body")
                } else {
                    tr("【${names.getOrElse(i) { id }}】统计不可用", "[${names.getOrElse(i) { id }}] stats unavailable")
                }
                if (pending.decrementAndGet() == 0) {
                    statsText = parts.filterNotNull().joinToString("\n\n")
                }
            }
        }
    }

    fun dismissStats() {
        statsText = null
    }

    /** 本条实时消息附带的内容，等消息本身追加后再追加（executor 内用）。 */
    private var pendingExtras: List<ChatRender.Item>? = null

    private fun appendItem(item: ChatRender.Item) {
        rows.add(Row(nextRowKey++, item))
        if (openPending) liveInFlight.add(item)
    }
}
