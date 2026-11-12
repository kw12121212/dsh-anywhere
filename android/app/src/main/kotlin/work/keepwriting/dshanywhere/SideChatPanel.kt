package work.keepwriting.dshanywhere

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import work.keepwriting.core.ChatRender
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.SideChat

/**
 * Side chat 侧问面板（dsbot-side-chat）：底部抽屉——side-new 新建旁路会话、side-ask
 * 提问（附主聊天尾部快照）、side-cancel 取消、side-close 关闭；不接受附件；独立
 * rows 不污染主聊天；后台返回状态保留（controller 持活于 NativeShellConnection）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SideChatPanel(controller: SideChatController, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("侧问（不打断 Bot 工作）", style = MaterialTheme.typography.titleSmall)
            Text(
                "旁路提问不运行工具；不支持附件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (controller.rows.isEmpty()) {
                Text(
                    if (controller.sideId == null) "正在建立旁路会话…" else "提问开始旁路对话",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(controller.rows, key = { it.key }) { row ->
                        (row.item as? ChatRender.Item.Message)?.let { m ->
                            Surface(
                                color = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            ) {
                                Text(m.markdown, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (controller.pendingAsk || controller.streamingText != null) {
                Text("回答中…", style = MaterialTheme.typography.labelSmall)
            }
            controller.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("旁路提问") },
                    maxLines = 3,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty() && controller.ask(text)) input = ""
                    },
                    enabled = input.isNotBlank() && !controller.busy && controller.sideId != null,
                ) { Text("提问") }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { controller.cancel() }, enabled = controller.streamingText != null || controller.pendingAsk) { Text("取消回答") }
                TextButton(onClick = { controller.close(onDismiss) }) { Text("关闭") }
            }
        }
    }
}

/**
 * Side chat 控制器（dsbot-side-chat）：独立 rows/流式/会话；feed 事件按 side sessionId
 * 过滤（NativeShellConnection 转发——stream/message 的 sessionId 等于 side 会话 id 时路由到此）。
 */
class SideChatController(
    private val api: (endpoint: String, payloadJson: String, onResult: (DsbotApi.Result) -> Unit) -> Unit,
    private val execute: (Runnable) -> Unit,
) {
    val rows = mutableStateListOf<ChatController.Row>()
    var streamingText by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var sideId by mutableStateOf<String?>(null)
        private set
    /** 有未决 ask（提问已提交、回答未取回）——真实路径不走 feed stream，进行中指示与取消以此为准。 */
    var pendingAsk by mutableStateOf(false)
        private set

    /** 活跃 side 会话 id 集合（0.3.5：每次 side-ask 可能新建 chatId，事件按任一活跃 id 归属）。 */
    private val activeSideIds = mutableSetOf<String>()

    private var mainSessionId: String? = null
    private val stream = ChatRender.StreamBuffer()
    private var nextRowKey = 0L

    /** 打开面板：side-new 新建旁路会话（附当前主聊天会话 id）。executor 内。 */
    fun open(mainSessionId: String) {
        execute(Runnable {
            if (sideId != null && this.mainSessionId == mainSessionId) return@Runnable // 同一会话（后台恢复）
            val previousId = sideId
            if (previousId != null) {
                // 切换到不同 Bot 的侧问：关闭旧旁路会话并重置（M2：避免跨 Bot 污染）
                api("side-close", SideChat.close(previousId)) { _ -> }
                sideId = null
                activeSideIds.clear()
                answeredEntryIds.clear()
                rows.clear()
                streamingText = null
                pendingAsk = false
                cancelPolls()
            }
            this.mainSessionId = mainSessionId
            rows.clear()
            api("side-new", SideChat.newSide(mainSessionId)) { res ->
                when (res) {
                    is DsbotApi.Result.Ok -> {
                        val chatId = SideChat.decodeChatId(res.value)
                        sideId = chatId
                        if (chatId != null) {
                            activeSideIds.add(chatId) // M1 修复：side-new 的 chatId 也入归属集合
                        } else {
                            notice = "旁路会话建立失败"
                        }
                    }
                    is DsbotApi.Result.BotError -> notice = "旁路会话失败：${res.code}"
                    is DsbotApi.Result.Failure -> notice = "旁路会话失败（网络）"
                }
            }
        })
    }

    /** 主聊天快照来源（NativeShell 注入：主聊天 rows 尾部文本条目）。 */
    var snapshotProvider: (() -> List<Pair<Boolean, String>>)? = null

    /** side-ask：附主聊天尾部快照（delta side-ask-streams WHEN）。 */
    fun ask(text: String): Boolean {
        val main = mainSessionId ?: return false
        busy = true
        pendingAsk = true // 进行中指示与取消使能（真实路径不走 feed stream）
        rows.add(ChatController.Row(nextRowKey++, ChatRender.Item.Message(fromUser = true, markdown = text, turn = null, seq = null)))
        val snapshot = SideChat.snapshotOf(snapshotProvider?.invoke() ?: emptyList())
        api("side-ask", SideChat.ask(main, text, snapshot)) { res ->
            when (res) {
                is DsbotApi.Result.Ok -> {
                    // pendingAsk 保留至 poll 取到回答（真实路径 ask 先返回、回答 5-15s 后落 state——
                    // Ok 即清会让等待期无"回答中"指示且取消不可达）
                    // 0.3.5：ask 应答带新 chatId（每问一个会话）——加入活跃集合供 feed 归属
                    (res.value as? work.keepwriting.core.Json.Value.Object)?.let { o ->
                        (o.members["chatId"] as? work.keepwriting.core.Json.Value.StringValue)?.value?.let { cid ->
                            activeSideIds.add(cid)
                            sideId = cid
                        }
                    }
                    busy = false
                    notice = null
                    pollSideAnswer()
                }
                else -> {
                    if (rows.lastOrNull()?.item?.let { it is ChatRender.Item.Message && it.fromUser } == true) {
                        rows.removeAt(rows.lastIndex)
                    }
                    busy = false
                    pendingAsk = false
                    notice = "旁路提问失败"
                }
            }
        }
        return true
    }


    /**
     * 0.3.5 实测：side 回答不走 feed（stream/message 事件不按 side 会话推），而在
     * state.sideChats 的 entries[].answer——ask 后六轮延时轮询（3-20s）取回答。
     */
    private fun pollSideAnswer() {
        // 多轮延时轮询（scheduler 定时→executor 执行）；回答落 state.sideChats[].entries[].answer——
        // 真实回答生成 5~15s，轮次覆盖到 20s，取到回答即撤销余下轮次
        cancelPolls() // 清理上一轮
        for ((i, delayMs) in POLL_DELAYS_MS.withIndex()) {
            val isLast = i == POLL_DELAYS_MS.size - 1
            val future = pollScheduler.schedule({
                runCatching {
                    execute(Runnable {
                        pollOnce()
                        // 末轮仍无回答：收口（避免"回答中"永久卡住）
                        if (isLast && pendingAsk) {
                            pendingAsk = false
                            notice = "回答未取到（可重试提问）"
                        }
                    })
                } // executor 已关则静默（生命周期收口）
            }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            synchronized(pollFutures) { pollFutures.add(future) }
        }
    }

    private fun pollOnce() {
        if (activeSideIds.isEmpty()) return
        api("state", "{}") { res ->
            if (res is DsbotApi.Result.Ok) {
                val obj = res.value as? work.keepwriting.core.Json.Value.Object ?: return@api
                var gotNew = false
                for (session in SideChat.decodeSideSessions(obj)) {
                    if (session.id !in activeSideIds) continue
                    for (entry in session.entries) {
                        val eo = entry.raw as? work.keepwriting.core.Json.Value.Object ?: continue
                        val answer = (eo.members["answer"] as? work.keepwriting.core.Json.Value.StringValue)?.value ?: continue
                        val entryId = (eo.members["id"] as? work.keepwriting.core.Json.Value.StringValue)?.value ?: answer
                        if (answer.isNotEmpty() && !answeredEntryIds.contains(entryId)) {
                            answeredEntryIds.add(entryId) // 按条目身份去重（M2：同文本不同条目不吞）
                            rows.add(ChatController.Row(nextRowKey++, ChatRender.Item.Message(fromUser = false, markdown = answer, turn = null, seq = null)))
                            gotNew = true
                        }
                    }
                }
                if (gotNew) {
                    pendingAsk = false
                    cancelPolls() // 取到回答撤销余下轮次（M3：不浪费请求）
                }
            }
        }
    }

    private fun cancelPolls() {
        synchronized(pollFutures) {
            pollFutures.forEach { it.cancel(false) }
            pollFutures.clear()
        }
    }


    /** 面板未打开时的提示通道（NativeShell 侧问入口无会话时用）。 */
    fun postNoticeStatic(text: String) {
        execute(Runnable { notice = text })
    }

    /** 生命周期收口：撤轮询并关调度器（NativeShellConnection.stop 级联调用）。 */
    fun dispose() {
        cancelPolls()
        pollScheduler.shutdownNow()
    }

    private val pollScheduler = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "side-poll").apply { isDaemon = true }
    }.apply { setRemoveOnCancelPolicy(true) }

    private val answeredEntryIds = mutableSetOf<String>()
    private val pollFutures = mutableListOf<java.util.concurrent.ScheduledFuture<*>>()

    private companion object {
        val POLL_DELAYS_MS = listOf(3000L, 6000L, 9000L, 12000L, 16000L, 20000L)
    }

    /** feed 事件（side 会话归属）：stream 逐字、message 定格。 */
    fun onFeedEvent(event: String, dataJson: String) {
        if (activeSideIds.isEmpty()) return
        when (event) {
            "stream" -> {
                val obj = work.keepwriting.core.Json.parse(dataJson) as? work.keepwriting.core.Json.Value.Object ?: return
                val session = (obj.members["sessionId"] as? work.keepwriting.core.Json.Value.StringValue)?.value
                if (session == null || session !in activeSideIds) return
                val attempt = (obj.members["attemptId"] as? work.keepwriting.core.Json.Value.StringValue)?.value
                val phase = (obj.members["phase"] as? work.keepwriting.core.Json.Value.StringValue)?.value
                val text = (obj.members["text"] as? work.keepwriting.core.Json.Value.StringValue)?.value
                stream.onStream(null, session, attempt, phase, text)?.let { streamingText = it }
                if (phase == "end") {
                    stream.settle()?.let { full ->
                        rows.add(ChatController.Row(nextRowKey++, ChatRender.Item.Message(fromUser = false, markdown = full, turn = null, seq = null)))
                    }
                    streamingText = null
                }
            }
            "message" -> {
                val obj = work.keepwriting.core.Json.parse(dataJson) as? work.keepwriting.core.Json.Value.Object ?: return
                val session = (obj.members["sessionId"] as? work.keepwriting.core.Json.Value.StringValue)?.value
                if (session == null || session !in activeSideIds) return
                if (stream.inProgress()) {
                    stream.settle()
                    streamingText = null
                }
                val item = (obj.members["item"] as? work.keepwriting.core.Json.Value.Object) ?: return
                val kind = (item.members["kind"] as? work.keepwriting.core.Json.Value.StringValue)?.value
                val text = (item.members["text"] as? work.keepwriting.core.Json.Value.StringValue)?.value
                if (kind == "bot" && text != null) {
                    val seq = (item.members["seq"] as? work.keepwriting.core.Json.Value.NumberValue)?.raw?.toLongOrNull()
                    val items = rows.map { it.item }
                    when (val d = ChatRender.appendBotMessage(items, text, seq)) {
                        is ChatRender.AppendDecision.Add ->
                            rows.add(ChatController.Row(nextRowKey++, d.item as ChatRender.Item.Message))
                        is ChatRender.AppendDecision.Upgrade -> {
                            val idx = d.index
                            rows[idx] = ChatController.Row(rows[idx].key, d.item)
                        }
                    }
                    pendingAsk = false // feed 路径取到回答（mock）；真实路径由 pollOnce 清
                    cancelPolls()
                }
            }
        }
    }

    fun cancel() {
        val sid = sideId ?: return
        api("side-cancel", SideChat.cancel(sid)) { res ->
            if (res is DsbotApi.Result.Ok) {
                streamingText = null
                pendingAsk = false
                cancelPolls()
                notice = "已取消"
            } else {
                notice = "取消失败"
            }
        }
    }

    fun close(onDone: () -> Unit) {
        val sid = sideId ?: run { onDone(); return }
        api("side-close", SideChat.close(sid)) { res ->
            sideId = null
            activeSideIds.clear()
            answeredEntryIds.clear()
            rows.clear()
            streamingText = null
            pendingAsk = false
            cancelPolls()
            onDone()
        }
    }
}
