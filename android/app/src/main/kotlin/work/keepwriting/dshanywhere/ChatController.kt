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
    /** 打开/重取失败回调（chat-load-error-diagnostics 诊断上报）：executor 内触发，
     *  参数为原因代码（bot 错误码或 "network"）；不改变 notice 行为。 */
    private val onLoadFailure: ((code: String) -> Unit)? = null,
    /** 缓存键的主机部分（harness 的 host:port；dsbot-chat-cache），换地址后旧缓存不串用。 */
    private val hostOf: () -> String? = { null },
    /**
     * 聊天读取通道（dsbot-chat-cache）：网络在独立 IO 线程执行、回调投回状态 executor，
     * 慢读取不阻塞缓存上屏与其他聊天的打开；缺省退回 [api]。
     */
    private val readApi: (endpoint: String, payloadJson: String, onResult: (DsbotApi.Result) -> Unit) -> Unit = api,
) {
    /** 渲染行（稳定 key 供 LazyColumn 锚定：earlier 前插保持滚动位置）。 */
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

    /** 滚动恢复请求（dsbot-chat-cache）：从缓存打开时自增，UI 把列表滚到 [restorePosition]。 */
    var restoreSignal by mutableIntStateOf(0)
        private set
    /** 当前显示的会话 id（Bot 当前分段或打开的旧分段；读取确认后更新）：停靠区按它取 state 的待处理项。 */
    var shownSessionId by mutableStateOf<String?>(null)
        private set

    /** 恢复目标：(botId, 位置)——UI 只恢复属于当前聊天的位置。 */
    var restorePosition: Pair<String, work.keepwriting.core.ScrollMemory.Position>? = null
        private set

    // ---- 缓存与单飞（dsbot-chat-cache；全部在 executor 线程读写）----
    private val cache = work.keepwriting.core.ChatCache<ChatRender.Item>()
    private val scrollMemory = work.keepwriting.core.ScrollMemory()
    private val flight = work.keepwriting.core.ReadFlight()
    /** 下一次读取强制整体替换（分段切换后：新分段与旧列表无关）。 */
    private var forceReset = false
    /** 排队重跑时一并补发的完成回调与滚到最新（分段切换的提示等）；换目标时清空。 */
    private var queuedDone: (() -> Unit)? = null
    private var queuedJump = false

    private fun keyOf(id: String) = work.keepwriting.core.ChatCache.key(hostOf(), "bot", id)

    private fun saveToCache() {
        val id = botId ?: return
        if (rows.isEmpty()) return
        cache.put(keyOf(id), work.keepwriting.core.ChatCache.Entry(rows.map { it.item }, earlierCursor, currentSessionId))
    }

    /** 新首页与当前列表按内容复用行 key（未变消息不重组、不跳动）。 */
    private fun reconcile(fresh: List<ChatRender.Item>) {
        val old = rows.toList()
        val match = work.keepwriting.core.RowIdentity.match(old.map { it.item }, fresh)
        val next = fresh.mapIndexed { j, item -> if (match[j] >= 0) old[match[j]] else Row(nextRowKey++, item) }
        rows.clear()
        rows.addAll(next)
    }

    /** 页面离开时保存滚动位置（UI 线程调用，投递到 executor）。 */
    fun saveScroll(index: Int, offset: Int) {
        execute(Runnable { botId?.let { scrollMemory.save(keyOf(it), index, offset) } })
    }

    /** 启动预取（只写缓存，不改当前显示）：上次打开的聊天。 */
    fun prefetch(id: String) {
        execute(Runnable {
            if (botId == id || cache[keyOf(id)] != null) return@Runnable
            readApi("chat", "{\"botId\":${JsonString.of(id)}}") { res ->
                if (res !is DsbotApi.Result.Ok || botId == id || cache[keyOf(id)] != null) return@readApi
                val page = DsbotModels.decodeChatPage(res.value)
                val rendered = ChatRender.render(page)
                cache.put(keyOf(id), work.keepwriting.core.ChatCache.Entry(rendered.items, rendered.cursor, page.sessionId))
            }
        })
    }

    /** 前台兜底轮询（executor 线程调用）：流式输出进行中跳过，避免打断逐字气泡。 */
    fun pollRefresh() {
        val id = botId ?: return
        if (streamingText != null || stream.inProgress()) return
        openOnExecutor(id, keepStream = true)
    }

    private val stream = ChatRender.StreamBuffer()
    private var botId: String? = null
    private var currentSessionId: String? = null
    private var earlierCursor: Json.Value? = null
    private var openPending = false
    private var nextRowKey = 0L

    /** open 在途期间到达的实时条目（message/stream end）：重取快照可能尚未包含
     *  服务器已写入的回复，替换后按文本去重回放，避免整页替换吃掉实时消息。 */
    private val liveInFlight = mutableListOf<ChatRender.Item>()

    private fun replaceAll(items: List<ChatRender.Item>) {
        rows.clear()
        items.forEach { rows.add(Row(nextRowKey++, it)) }
    }

    private fun appendExtras(item: Json.Value.Object) {
        val extras = ChatRender.extras(DsbotModels.ChatItem(null, null, null, null, null, item))
        for (x in extras) if (rows.none { it.item == x }) appendItem(x)
    }

    private fun appendItem(item: ChatRender.Item) {
        rows.add(Row(nextRowKey++, item))
        if (openPending) liveInFlight.add(item)
    }

    /** 打开（或重进）某 Bot 私聊：缓存先上屏并恢复滚动位置，再后台刷新（dsbot-chat-cache）；
     *  重进同一聊天时不清空正在流式输出的回复。 */
    fun open(id: String) {
        execute(Runnable { openOnExecutor(id, restore = true, keepStream = true) })
    }

    /** feed 重连成功后补取当前聊天页（断线窗口内发布的 message/stream 事件不可重放，
     *  整页重取是在途缓冲之外的一致性兜底）。仅在 executor 线程内调用。 */
    fun refreshIfOpen() {
        val id = botId ?: return
        openOnExecutor(id)
    }

    /**
     * 打开/刷新（dsbot-chat-cache）：换目标时先把当前聊天写入缓存，命中目标缓存则立即上屏；
     * [restore]（页面进入）时按滚动记忆恢复位置（同一聊天重进也恢复），无记忆则滚到最新；
     * 再按单飞读取首页（同目标在途时最多排队一次）。会话 id 只在读取确认后恢复，不沿用缓存里
     * 可能过期的值。[keepStream] 为兜底轮询用（不清流式气泡），[jump] 为分段切换用（完成后滚到最新）。
     */
    private fun openOnExecutor(
        id: String,
        done: (() -> Unit)? = null,
        keepStream: Boolean = false,
        jump: Boolean = false,
        restore: Boolean = false,
    ) {
        if (botId != id) {
            saveToCache()
            botId = id
            currentSessionId = null // 切换目标：旧 sessionId 立即失效，读取确认前发送被拒绝而非错发旧会话
            shownSessionId = null
            streamingText = null
            liveInFlight.clear()
            queuedDone = null
            queuedJump = false
            forceReset = false
            refreshOutboxView() // 发送箱按聊天保存：换聊天显示目标聊天自己的本地行
            val hit = cache[keyOf(id)]
            if (hit != null) {
                replaceAll(hit.items)
                earlierCursor = hit.cursor
                canLoadEarlier = hit.cursor != null
                notice = null
            } else {
                rows.clear()
                earlierCursor = null
                canLoadEarlier = false
            }
        } else if (!keepStream) {
            streamingText = null
        }
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
            is work.keepwriting.core.ReadFlight.Begin.Queued -> {
                if (done != null) queuedDone = done
                queuedJump = queuedJump || jump
                return
            }
            work.keepwriting.core.ReadFlight.Begin.Ignored -> {
                if (done != null) queuedDone = done
                queuedJump = queuedJump || jump
                return
            }
        }
        openPending = true
        val payload = "{\"botId\":${JsonString.of(id)}}"
        readApi("chat", payload) { res ->
            val fin = flight.finish(gen)
            if (fin.stale || botId != id) return@readApi // 已被更新的 open 或分段切换取代
            openPending = false
            val wasEmpty = rows.isEmpty()
            when (res) {
                is DsbotApi.Result.Ok -> {
                    val page = DsbotModels.decodeChatPage(res.value)
                    val rendered = ChatRender.render(page)
                    bumpNewest(id, page.items.maxOfOrNull { it.time ?: 0L })
                    currentSessionId = page.sessionId
                    shownSessionId = page.sessionId
                    // 合并决策在 core：首页与当前列表重叠则保留更早记录与游标，否则整体替换
                    val merge = work.keepwriting.core.ChatRefresh.merge(
                        rows.map { it.item }, rendered.items, forceReset,
                        isLocal = ::isLocalItem, matches = ::sameMessage,
                    )
                    forceReset = false
                    if (merge.changed) reconcile(merge.items)
                    if (!merge.keepCursor) {
                        earlierCursor = rendered.cursor
                        canLoadEarlier = rendered.cursor != null
                    }
                    // 回放在途实时条目（快照未含的重取期间到达的消息；按文本+方向去重）
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
                    if (wasEmpty || jump) jumpSignal++
                    saveToCache()
                    reconcileOutbox()
                }
                is DsbotApi.Result.BotError -> {
                    liveInFlight.clear() // 重取失败：丢弃在途缓冲，避免下次回放过期条目
                    notice = tr("加载聊天失败：${res.code}", "Could not load chat: ${res.code}")
                    onLoadFailure?.invoke(res.code)
                }
                is DsbotApi.Result.Failure -> {
                    liveInFlight.clear()
                    notice = tr("加载聊天失败（网络）", "Could not load chat (network)")
                    onLoadFailure?.invoke("network")
                }
            }
            done?.invoke() // 分段切换等后续提示在重取完成后生效（不被 notice=null 覆盖）
            if (fin.rerun) {
                val nextDone = queuedDone
                val nextJump = queuedJump
                queuedDone = null
                queuedJump = false
                openOnExecutor(id, nextDone, keepStream = true, jump = nextJump)
            }
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
                    saveToCache()
                }
                is DsbotApi.Result.BotError -> notice = tr("更早记录加载失败：${res.code}", "Could not load earlier messages: ${res.code}")
                is DsbotApi.Result.Failure -> notice = tr("更早记录加载失败（网络）", "Could not load earlier messages (network)")
            }
        }
    }

    // ---- 发送箱（dsbot-outbox-retry；全部在 executor 线程读写）----

    private val outboxStore = work.keepwriting.core.Outbox()
    /** 当前聊天的本地待确认消息（发送中 / 未发送），渲染在聊天末尾。 */
    var outbox by mutableStateOf<List<work.keepwriting.core.Outbox.Entry>>(emptyList())
        private set
    /** 发送成功次数：UI 据此给一次轻触感反馈。 */
    var sentSignal by mutableIntStateOf(0)
        private set
    /** 新消息进入发送箱：UI 滚到列表末尾。 */
    var sendScrollSignal by mutableIntStateOf(0)
        private set

    private fun refreshOutboxView() {
        // 单次写入不可变列表（后台线程 clear+addAll 会让某一帧看到空列表而闪烁）
        outbox = botId?.let { outboxStore.entries(keyOf(it)) } ?: emptyList()
    }

    /** 当前列表已知的最大 seq：新本地行的对账窗口（只有更新的服务端用户行能确认它）。 */
    /** 私聊的对账窗口：当前列表已知的最大 seq。 */
    private fun maxKnownSeq(): Long = rows.maxOfOrNull { (it.item as? ChatRender.Item.Message)?.seq ?: -1L } ?: -1L

    /** 用当前列表中的服务端用户行对账，消掉已被确认的本地行。 */
    private fun reconcileOutbox() {
        val id = botId ?: return
        val users = rows.mapNotNull { r ->
            (r.item as? ChatRender.Item.Message)?.takeIf { it.fromUser }?.let { work.keepwriting.core.Outbox.UserRow(it.seq, it.markdown) }
        }
        if (outboxStore.reconcile(keyOf(id), users).isNotEmpty()) refreshOutboxView()
    }

    /**
     * 发送文字：进入发送箱（输入框立即清空，本地行显示「发送中」）并返回 true；失败转「未发送」等待
     * 用户手动重试，从不自动重发。会话未就绪（open 未完成）直接拒绝，不得以 botId 冒充 sessionId。
     */
    fun send(text: String): Boolean {
        if (currentSessionId == null) {
            notice = tr("会话未就绪，稍候再发", "Session not ready, try again shortly")
            return false
        }
        execute(Runnable { enqueue(text, text, emptyList()) })
        return true
    }

    private fun enqueue(text: String, display: String, receipts: List<String>) {
        val sid = currentSessionId ?: run {
            notice = tr("会话未就绪，稍候再发", "Session not ready, try again shortly")
            return
        }
        val id = botId ?: return
        val entry = outboxStore.add(keyOf(id), text, display, receipts, maxKnownSeq())
        refreshOutboxView()
        sendScrollSignal++
        dispatch(entry, sid)
    }

    /** 发出一条本地行：无附件走 send，带附件走 session/prompt（与原来相同的请求）。 */
    private fun dispatch(entry: work.keepwriting.core.Outbox.Entry, sid: String) {
        if (entry.receipts.isEmpty()) {
            api("send", "{\"sessionId\":${JsonString.of(sid)},\"text\":${JsonString.of(entry.text)}}") { res ->
                val why = when (res) {
                    is DsbotApi.Result.Ok -> null
                    is DsbotApi.Result.BotError -> tr("发送失败：${res.code}", "Send failed: ${res.code}")
                    is DsbotApi.Result.Failure -> tr("发送失败（网络）", "Send failed (network)")
                }
                onDispatched(entry.id, why)
            }
        } else {
            val request = work.keepwriting.core.AttachmentMessages.promptRequest(
                requestId = java.util.UUID.randomUUID().toString(),
                sessionId = sid,
                receiptIds = entry.receipts,
                text = entry.text.trim(),
            )
            muxCall("session/prompt", request) { res ->
                onDispatched(entry.id, (res as? work.keepwriting.core.DshMux.Result.Err)?.let { tr("发送失败：${it.code}", "Send failed: ${it.code}") })
            }
        }
    }

    /** 成功：转「已发送」等服务端用户行出现后对账移除；失败：转「未发送」并提示。 */
    private fun onDispatched(id: Long, failure: String?) {
        if (failure == null) {
            // 请求已成功：转「已发送，等待确认」（可删除），服务端用户行出现后对账移除
            outboxStore.setState(id, work.keepwriting.core.Outbox.State.SENT)
            sentSignal++
            notice = null
        } else {
            outboxStore.setState(id, work.keepwriting.core.Outbox.State.FAILED)
            notice = tr("$failure，可点按重试", "$failure. Tap to retry")
        }
        refreshOutboxView()
    }

    /** 点按「未发送」重试：只在未发送状态下重新发出一次（发送中重复点按无效）。 */
    fun retryOutbox(id: Long) {
        execute(Runnable {
            val entry = outboxStore.get(id) ?: return@Runnable
            if (entry.state != work.keepwriting.core.Outbox.State.FAILED) return@Runnable
            if (entry.chatKey != botId?.let { keyOf(it) }) return@Runnable // 只重试当前聊天的行
            val sid = currentSessionId ?: run {
                notice = tr("会话未就绪，稍候再试", "Session not ready, try again shortly")
                return@Runnable
            }
            val sending = outboxStore.setState(id, work.keepwriting.core.Outbox.State.SENDING) ?: return@Runnable
            refreshOutboxView()
            dispatch(sending, sid)
        })
    }

    /** 删除本地行：丢弃，不发送。 */
    fun deleteOutbox(id: Long) {
        execute(Runnable {
            if (outboxStore.remove(id)) refreshOutboxView()
        })
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
                // 当前聊天的实时消息：前台可见时随之标记已读（dsbot-read-state）
                botId?.let { bumpNewest(it, (item.members["time"] as? Json.Value.NumberValue)?.raw?.toLongOrNull()) }
                if (kind == "bot" && text != null) {
                    val seq = (item.members["seq"] as? Json.Value.NumberValue)?.raw?.toLongOrNull()
                    when (val decision = ChatRender.appendBotMessage(rows.map { it.item }, text, seq)) {
                        is ChatRender.AppendDecision.Add -> appendItem(decision.item)
                        is ChatRender.AppendDecision.Upgrade ->
                            rows[decision.index] = Row(rows[decision.index].key, decision.item)
                    }
                } else if (kind == "user" && text != null) {
                    // 用户消息实时上屏（本机发出的据此消掉发送箱本地行，网页端发出的也即时可见）；
                    // 事件带 sessionId 时须是当前会话（同一 Bot 其他分段/会话的用户行不插进来）
                    val frameSession = (obj.members["sessionId"] as? Json.Value.StringValue)?.value
                    if (frameSession != null && currentSessionId != null && frameSession != currentSessionId) return
                    val seq = (item.members["seq"] as? Json.Value.NumberValue)?.raw?.toLongOrNull()
                    val turn = (item.members["turn"] as? Json.Value.NumberValue)?.raw?.toLongOrNull()
                    val known = seq != null && rows.any { (it.item as? ChatRender.Item.Message)?.let { m -> m.fromUser && m.seq == seq } == true }
                    if (seq != null && !known) appendItem(ChatRender.Item.Message(fromUser = true, markdown = text, turn = turn, seq = seq))
                    reconcileOutbox()
                }
                // 实时条目附带的交付文件与图片引用即时显示（dsbot-reader-files；重取时由合并对齐）
                if (kind == "bot" || kind == "user") appendExtras(item)
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
                notice = tr("停止失败", "Could not stop")
            }
        }
    }

    fun resume() {
        val id = botId ?: return
        api("resume", "{\"botId\":${JsonString.of(id)}}") { res ->
            if (res is DsbotApi.Result.Ok) interrupted = false else notice = tr("恢复失败", "Could not resume")
        }
    }

    /** 分段切换：丢弃该 Bot 的缓存与滚动记忆，作废在途读取（旧分段结果不再写回），下一次读取整体替换。 */
    private fun invalidate(id: String) {
        cache.invalidate(keyOf(id))
        scrollMemory.forget(keyOf(id))
        flight.reset()
        openPending = false
        liveInFlight.clear() // 旧分段的在途实时条目不回放到新分段
        queuedDone = null
        queuedJump = false
        forceReset = true
    }

    fun newPart() {
        val id = botId ?: return
        api("bot-new-part", "{\"botId\":${JsonString.of(id)}}") { res ->
            when (res) {
                is DsbotApi.Result.Ok -> {
                    interrupted = false
                    invalidate(id)
                    openOnExecutor(id, { notice = tr("已新开分段", "Started a new part") }, jump = true) // 提示在重取完成后生效
                }
                is DsbotApi.Result.BotError -> notice = tr("新开分段失败：${res.code}", "Could not start a new part: ${res.code}")
                is DsbotApi.Result.Failure -> notice = tr("新开分段失败（网络）", "Could not start a new part (network)")
            }
        }
    }

    /** 打开旧分段（或切回当前分段）：切换后重取显示对应分段条目。 */
    fun openPart(partIndex: Int, partId: String, total: Int) {
        val id = botId ?: return
        api("bot-open-part", "{\"botId\":${JsonString.of(id)},\"part\":${JsonString.of(partId)}}") { res ->
            when (res) {
                is DsbotApi.Result.Ok -> {
                    invalidate(id)
                    openOnExecutor(id, { notice = tr("已切换到分段 ${partIndex + 1}/$total", "Switched to part ${partIndex + 1}/$total") }, jump = true)
                }
                is DsbotApi.Result.BotError -> notice = tr("切换分段失败：${res.code}", "Could not switch part: ${res.code}")
                is DsbotApi.Result.Failure -> notice = tr("切换分段失败（网络）", "Could not switch part (network)")
            }
        }
    }

    // ---- 统计：弹窗展示（core 的 BotStatsText 翻译成人话）----

    var statsText by mutableStateOf<String?>(null)
        private set

    fun showStats() {
        val id = botId ?: return
        api("run-stats", "{\"botId\":${JsonString.of(id)}}") { res ->
            statsText = if (res is DsbotApi.Result.Ok) {
                work.keepwriting.core.BotStatsText.runStats(res.value).ifEmpty { tr("暂无统计数据", "No stats yet") }
            } else {
                tr("统计不可用", "Stats unavailable")
            }
        }
    }

    fun dismissStats() {
        statsText = null
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
        // 读取与照片压缩（解码/旋转/编码可达秒级）在附件 IO 线程执行，不阻塞状态 executor；
        // 结果投回 executor 入附件栏并上传（dsbot-outbox-retry）
        attachIo.execute {
            val packed = runCatching {
                val rawName = queryDisplayName(resolver, uri)
                val rawType = resolver.getType(uri)
                val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                if (raw.isEmpty()) null else ImageCompress.maybeCompress(raw, rawType, rawName)
            }.getOrNull()
            if (deleteAfterRead) runCatching { resolver.delete(uri, null, null) }
            execute(Runnable {
                if (packed == null) {
                    notice = tr("无法读取所选内容", "Cannot read the selected item")
                    return@Runnable
                }
                val att = PendingAttachment(
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
            att.failReason = tr("会话未就绪", "Session not ready")
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
                notice = tr("会话未就绪，稍候再发", "Session not ready, try again shortly")
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
            sendAttachmentsOnExecutor(sid, text)
        })
        return true
    }

    /** 带附件消息进入发送箱：回执与附件名转入本地行并清空附件栏，重试时原样重发（dsbot-outbox-retry）。 */
    private fun sendAttachmentsOnExecutor(@Suppress("UNUSED_PARAMETER") sid: String, text: String) {
        val receipts = attachments.mapNotNull { it.receiptId }
        val visible = buildString {
            if (text.isNotEmpty()) append(text).append('\n')
            attachments.forEach { append(tr("[附件] ", "[Attachment] ")).append(it.name).append('\n') }
        }.trimEnd()
        attachments.clear()
        enqueue(text.trim(), visible, receipts)
    }

    /** UI 侧提示写入（图片保存结果等）。 */

    /** 卡片作答（dsbot-interactive-cards）：/api/bot 写端点，成功后回调（调用方消隐卡片）。 */
    fun answerCard(endpoint: String, payloadJson: String, onDone: (Boolean) -> Unit) {
        execute(Runnable {
            api(endpoint, payloadJson) { res ->
                when (res) {
                    is DsbotApi.Result.Ok -> onDone(true)
                    is DsbotApi.Result.BotError -> {
                        notice = work.keepwriting.core.ChatBars.actionError(res.code)
                        onDone(false)
                    }
                    is DsbotApi.Result.Failure -> {
                        notice = work.keepwriting.core.ChatBars.actionError("network")
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
                notice = tr("会话未就绪，无法查看附件", "Session not ready, cannot open the attachment")
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

    /** 本地尚未落到服务端的条目（乐观发送、流式定格的无序号消息）：刷新合并时保留。 */
    private fun isLocalItem(item: ChatRender.Item): Boolean = item is ChatRender.Item.Message && item.seq == null

    private fun sameMessage(a: ChatRender.Item, b: ChatRender.Item): Boolean =
        a is ChatRender.Item.Message && b is ChatRender.Item.Message && a.fromUser == b.fromUser && a.markdown == b.markdown
}
