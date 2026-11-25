package work.keepwriting.dshanywhere

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import work.keepwriting.core.BotTaskDetail
import work.keepwriting.core.Cards
import work.keepwriting.core.ChatBars
import work.keepwriting.core.ChatRender
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.Json
import kotlin.coroutines.resume

/**
 * 后台任务最新一页的单飞读取器（主线程使用，遵循 dsbot-api/on-demand-poll-single-flight）：
 * 协程取消不会取消已发出的 ManageApi 读取，同一 (botId, taskId, steps) 同时只在途一个最新页请求，
 * 防止前后台切换或频繁切换页时多个 bot-task-read 占满 2 线程读取池。
 */
class BotTaskReadStore {
    private val inFlight = HashMap<String, CompletableDeferred<DsbotApi.Result>>()

    suspend fun loadLatest(api: ManageApi, botId: String, taskId: String, steps: Boolean): DsbotApi.Result {
        val key = "$botId|$taskId|$steps"
        val pending = inFlight[key] ?: CompletableDeferred<DsbotApi.Result>().also { d ->
            inFlight[key] = d
            api.read("bot-task-read", BotTaskDetail.Requests.read(botId, taskId, cursor = null, steps = steps)) { r ->
                if (inFlight[key] === d) inFlight.remove(key)
                d.complete(r)
            }
        }
        return pending.await()
    }
}

private suspend fun ManageApi.readPage(payload: String): DsbotApi.Result =
    suspendCancellableCoroutine { cont -> read("bot-task-read", payload) { if (cont.isActive) cont.resume(it) } }

/**
 * Bot 后台任务详情全屏页（dsbot-bot-task-detail）：
 * 从私聊后台任务条点按任务进入，复用聊天条目渲染组件展示任务说明（task-brief）、消息、输出、
 * 工具步骤（steps=true）与分段分隔线，支持更早分页、切换步骤保留已加载更早记录、前台可见 2.5s 单飞轮询、
 * 顶部停止与运行中底部追问；任务结束后自动切为只读（隐藏输入框与停止按钮，显示结束时间、摘要或错误及未发草稿复制）。
 */
@Composable
fun BotTaskDetailPage(
    botId: String,
    taskId: String,
    state: Json.Value.Object?,
    api: ManageApi,
    store: BotTaskReadStore,
    /** 页面可见且 App 在前台（未退后台、未被设置页覆盖）。 */
    visible: Boolean,
    media: ChatMedia? = null,
    onOpenLink: (String, Boolean) -> Unit = { _, _ -> },
    /** 发送遇到任务刚结束等错误时静默补拉一次 state 以便立即切只读。 */
    onSyncState: () -> Unit = {},
    onBack: () -> Unit,
) {
    val task = remember(state, botId, taskId) { ChatBars.taskOf(state, botId, taskId) }
    val taskActive = task?.active == true
    val status = task?.status ?: ""

    var showSteps by remember(botId, taskId) { mutableStateOf(false) }
    var latestPage by remember(botId, taskId) { mutableStateOf<BotTaskDetail.Page?>(null) }
    var olderRecords by remember(botId, taskId) { mutableStateOf<List<BotTaskDetail.Record>>(emptyList()) }
    var earlierCursors by remember(botId, taskId) { mutableStateOf<List<Json.Value>>(emptyList()) }
    var olderCursorActive by remember(botId, taskId) { mutableStateOf(false) }
    var olderCursor by remember(botId, taskId) { mutableStateOf<Json.Value?>(null) }

    var loading by remember(botId, taskId) { mutableStateOf(true) }
    var loadingMore by remember(botId, taskId) { mutableStateOf(false) }
    var readError by remember(botId, taskId) { mutableStateOf<String?>(null) }
    var readErrorFromEarlier by remember(botId, taskId) { mutableStateOf(false) }
    var readErrorNotFound by remember(botId, taskId) { mutableStateOf(false) }
    var notice by remember(botId, taskId) { mutableStateOf<String?>(null) }
    var noticeIsError by remember(botId, taskId) { mutableStateOf(false) }
    var draft by remember(botId, taskId) { mutableStateOf("") }
    var reqGen by remember(botId, taskId) { mutableIntStateOf(0) }
    var nowMs by remember(botId, taskId) { mutableLongStateOf(System.currentTimeMillis()) }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val mediaUi = rememberChatMediaUi()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val view = LocalView.current

    val mergedRecords = remember(olderRecords, latestPage) {
        BotTaskDetail.mergePages(olderRecords, latestPage?.records.orEmpty(), latestPage?.sessionId)
    }
    val nextCursor = if (olderCursorActive) olderCursor else latestPage?.cursor
    val hasBotReply = remember(mergedRecords) { BotTaskDetail.hasBotReply(mergedRecords) }
    val stopping = "task:$taskId" in api.inFlight
    val sending = "task-send:$taskId" in api.inFlight

    fun showNotice(msg: String, isError: Boolean) {
        notice = msg
        noticeIsError = isError
    }

    suspend fun fetchLatest(gen: Int, steps: Boolean, quiet: Boolean): Boolean {
        if (!quiet) loading = true
        val res = store.loadLatest(api, botId, taskId, steps)
        if (gen != reqGen) return false
        if (!quiet) loading = false
        return when (res) {
            is DsbotApi.Result.Ok -> {
                latestPage = BotTaskDetail.decodePage(res.value)
                readError = null
                readErrorFromEarlier = false
                readErrorNotFound = false
                true
            }
            is DsbotApi.Result.BotError -> {
                readError = BotTaskDetail.readError(res.code, res.message)
                readErrorFromEarlier = false
                readErrorNotFound = res.code == "bot/not-found"
                false
            }
            is DsbotApi.Result.Failure -> {
                readError = BotTaskDetail.readError("network", null)
                readErrorFromEarlier = false
                readErrorNotFound = false
                false
            }
        }
    }

    fun loadEarlier() {
        val cursor = nextCursor ?: return
        if (loadingMore) return
        val gen = reqGen
        val stepsNow = showSteps
        loadingMore = true
        api.read("bot-task-read", BotTaskDetail.Requests.read(botId, taskId, cursor = cursor, steps = stepsNow)) { res ->
            if (gen != reqGen) return@read
            loadingMore = false
            when (res) {
                is DsbotApi.Result.Ok -> {
                    val prev = BotTaskDetail.decodePage(res.value)
                    olderRecords = BotTaskDetail.prependEarlier(prev.records, olderRecords, prev.sessionId ?: latestPage?.sessionId)
                    earlierCursors = earlierCursors + cursor
                    olderCursorActive = true
                    olderCursor = prev.cursor
                    readError = null
                    readErrorFromEarlier = false
                    readErrorNotFound = false
                }
                is DsbotApi.Result.BotError -> {
                    readError = BotTaskDetail.readError(res.code, res.message)
                    readErrorFromEarlier = true
                    readErrorNotFound = res.code == "bot/not-found"
                }
                is DsbotApi.Result.Failure -> {
                    readError = BotTaskDetail.readError("network", null)
                    readErrorFromEarlier = true
                    readErrorNotFound = false
                }
            }
        }
    }

    fun sendFollowup() {
        if (!taskActive || sending) return
        val text = draft.trim()
        val payload = BotTaskDetail.Requests.send(botId, taskId, text) ?: return
        val gen = reqGen
        val stepsNow = showSteps
        notice = null
        noticeIsError = false
        api.actionRaw("task-send:$taskId", "bot-task-send", payload) { res ->
            when (res) {
                is DsbotApi.Result.Ok -> {
                    Haptics.confirm(view)
                    draft = ""
                    showNotice(tr("已发送到后台任务", "Sent to background task"), isError = false)
                    scope.launch { fetchLatest(gen, stepsNow, quiet = true) }
                }
                is DsbotApi.Result.BotError -> {
                    Haptics.reject(view)
                    showNotice(BotTaskDetail.sendError(res.code, res.message), isError = true)
                    if (res.code == "bot/invalid" || res.code == "bot/not-found") onSyncState()
                    scope.launch { fetchLatest(gen, stepsNow, quiet = true) }
                }
                is DsbotApi.Result.Failure -> {
                    Haptics.reject(view)
                    showNotice(BotTaskDetail.sendError("network", null), isError = true)
                }
            }
        }
    }

    // 首次进入或切换 showSteps：递增代次号；切换步骤时保留已加载更早记录并按原游标链重读以同步步骤显隐
    LaunchedEffect(botId, taskId, showSteps) {
        val gen = ++reqGen
        val hadLatest = latestPage != null
        val cursorsToReplay = earlierCursors
        loadingMore = false
        val ok = fetchLatest(gen, showSteps, quiet = hadLatest)
        if (!ok || gen != reqGen) return@LaunchedEffect
        if (cursorsToReplay.isNotEmpty()) {
            if (!showSteps) {
                olderRecords = olderRecords.filterNot { it.entry is BotTaskDetail.Entry.Steps }
            } else {
                var rebuilt = emptyList<BotTaskDetail.Record>()
                for (cursor in cursorsToReplay) {
                    if (gen != reqGen) return@LaunchedEffect
                    val res = api.readPage(BotTaskDetail.Requests.read(botId, taskId, cursor = cursor, steps = true))
                    if (gen != reqGen) return@LaunchedEffect
                    if (res is DsbotApi.Result.Ok) {
                        val prev = BotTaskDetail.decodePage(res.value)
                        rebuilt = BotTaskDetail.prependEarlier(prev.records, rebuilt, prev.sessionId ?: latestPage?.sessionId)
                    }
                }
                if (gen == reqGen && rebuilt.isNotEmpty()) olderRecords = rebuilt
            }
        }
    }

    // 活动态前台可见轮询（每 2.5 秒等上次读完再排下一次）；不支持/不存在时停止轮询；任务转结束时执行最终刷新
    var wasActive by remember(botId, taskId) { mutableStateOf(taskActive) }
    LaunchedEffect(botId, taskId, showSteps, taskActive, visible, readErrorNotFound) {
        if (taskActive && visible && !readErrorNotFound) {
            wasActive = true
            val gen = reqGen
            while (isActive) {
                delay(BotTaskDetail.POLL_INTERVAL_MS)
                if (!isActive || gen != reqGen) break
                fetchLatest(gen, showSteps, quiet = true)
            }
        } else if (wasActive && !taskActive) {
            wasActive = false
            val gen = reqGen
            fetchLatest(gen, showSteps, quiet = true)
        }
    }

    // 活动态运行时长与结束时间相对文案定时刷新（仅前台可见时运行）
    LaunchedEffect(taskActive, visible, task?.startedAt, task?.endedAt) {
        if (visible && (task?.startedAt != null || task?.endedAt != null)) {
            while (isActive) {
                nowMs = System.currentTimeMillis()
                delay(if (taskActive) 1_000L else 30_000L)
            }
        }
    }

    // 仅首次加载完成滚到底部看最新输出（切换步骤不重置阅读位置）
    var scrolledOnce by remember(botId, taskId) { mutableStateOf(false) }
    LaunchedEffect(mergedRecords.size, loading) {
        if (!loading && !scrolledOnce && mergedRecords.isNotEmpty()) {
            scrolledOnce = true
            listState.scrollToItem(mergedRecords.size)
        }
    }

    val taskSid = latestPage?.sessionId ?: task?.sessionId
    CompositionLocalProvider(
        LocalMarkdownImageLoader provides { src: String, cb: (ByteArray?) -> Unit ->
            if (media != null) media.markdownImage(taskSid, src, cb) else cb(null)
        },
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部标题栏：返回、状态圆点、任务标题、活动态停止按钮
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onBack) { Text(tr("返回", "Back")) }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(taskStatusColor(status), CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = task?.title ?: taskId,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (taskActive) {
                        TextButton(
                            onClick = {
                                val gen = reqGen
                                val stepsNow = showSteps
                                api.action(
                                    "task:$taskId",
                                    "bot-stop-task",
                                    BotTaskDetail.Requests.stop(botId, taskId),
                                    { scope.launch { fetchLatest(gen, stepsNow, quiet = true) } },
                                    { showNotice(it, isError = true) },
                                )
                            },
                            enabled = !stopping,
                        ) {
                            Text(
                                if (stopping) tr("停止中…", "Stopping…") else tr("停止", "Stop"),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                // 次级元信息行：状态、已运行时长或结束时间、来源 + 显示/隐藏步骤开关
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) {
                    val startedAt = task?.startedAt
                    val endedAt = task?.endedAt ?: task?.updatedAt
                    val metaParts = buildList {
                        if (status.isNotEmpty()) add(ChatBars.taskStatusLabel(status))
                        if (taskActive && startedAt != null) {
                            add(BotTaskDetail.formatElapsed(startedAt, nowMs))
                        } else if (!taskActive && endedAt != null) {
                            add(BotTaskDetail.formatEndedTime(endedAt, nowMs))
                        }
                        ChatBars.taskOriginLabel(task?.origin)?.let { add(it) }
                    }
                    Text(
                        text = metaParts.joinToString(" · ").ifEmpty { tr("后台任务", "Background task") },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = showSteps,
                        onClick = { showSteps = !showSteps },
                        label = {
                            Text(
                                if (showSteps) tr("隐藏步骤", "Hide steps") else tr("显示步骤", "Show steps"),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
            }
        }

        // 读取错误提示条（按错误来源重试加载最新页或更早页）
        readError?.let { err ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        if (readErrorFromEarlier && nextCursor != null) {
                            loadEarlier()
                        } else {
                            val gen = reqGen
                            val stepsNow = showSteps
                            scope.launch { fetchLatest(gen, stepsNow, quiet = false) }
                        }
                    }) { Text(tr("重试", "Retry")) }
                }
            }
        }

        // 中部消息与步骤列表（复用聊天条目渲染组件）
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading && mergedRecords.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.size(8.dp))
                        Text(tr("加载中…", "Loading…"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                mergedRecords.isEmpty() -> {
                    val emptyText = when {
                        task?.error != null -> task.error
                        task?.summary != null -> task.summary
                        readError != null -> ""
                        task == null -> tr("该任务已不存在", "This task no longer exists")
                        else -> tr("暂无任务记录", "No messages yet")
                    }
                    if (!emptyText.isNullOrEmpty()) {
                        Text(
                            text = emptyText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (task?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    }
                }
                else -> {
                    val chatItems = remember(mergedRecords) {
                        mergedRecords.mapNotNull { (it.entry as? BotTaskDetail.Entry.Chat)?.item }
                    }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        if (nextCursor != null) {
                            item(key = "task-load-earlier") {
                                TextButton(
                                    onClick = { loadEarlier() },
                                    enabled = !loadingMore,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (loadingMore) tr("加载中…", "Loading…") else tr("更早的消息", "Earlier messages"))
                                }
                            }
                        } else {
                            item(key = "task-earliest") {
                                Text(
                                    tr("已到最早", "Reached the beginning"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                        itemsIndexed(
                            items = mergedRecords,
                            key = { idx, r -> "${r.sessionId ?: ""}:${r.seq ?: "x"}:${r.turn ?: "t"}:${r.entry.javaClass.simpleName}:$idx" },
                        ) { _, record ->
                            when (val entry = record.entry) {
                                is BotTaskDetail.Entry.TaskBrief -> TaskBriefCard(
                                    brief = entry,
                                    onCopy = {
                                        clipboard.setText(AnnotatedString(entry.text))
                                        showNotice(tr("已复制", "Copied"), isError = false)
                                    },
                                    onCopyBlock = { clipboard.setText(AnnotatedString(it)) },
                                    onOpenLink = onOpenLink,
                                )
                                is BotTaskDetail.Entry.Steps -> TaskStepsCard(entry)
                                is BotTaskDetail.Entry.Divider -> TaskSegmentDivider()
                                is BotTaskDetail.Entry.Chat -> {
                                    when (val item = entry.item) {
                                        is ChatRender.Item.Message -> MessageBubble(
                                            fromUser = item.fromUser,
                                            markdown = item.markdown,
                                            onCopy = {
                                                clipboard.setText(AnnotatedString(item.markdown))
                                                showNotice(tr("已复制", "Copied"), isError = false)
                                            },
                                            onLongPress = { mediaUi.menuFor = item },
                                            onCopyBlock = { clipboard.setText(AnnotatedString(it)) },
                                            onOpenLink = onOpenLink,
                                        )
                                        is ChatRender.Item.PresentedFile -> PresentedFileRow(
                                            item = item,
                                            progress = media?.progress?.takeIf { it.key == media.keyOf(item) },
                                            onClick = { if (media != null) mediaUi.fileSheet = item },
                                            onCancel = { media?.cancel() },
                                        )
                                        is ChatRender.Item.ToolCard -> ToolCardRow(item)
                                        is ChatRender.Item.Image -> ImageRow(item, onOpen = {
                                            if (item.attachmentRefJson != null) {
                                                mediaUi.gallery = ChatRender.gallery(chatItems, item)
                                            } else {
                                                item.ref?.let { ref ->
                                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ref))) }
                                                }
                                            }
                                        })
                                        is ChatRender.Item.InteractiveCard -> CardView(
                                            card = Cards.asReadOnly(item.card),
                                            onAnswer = { _, _ -> },
                                            onNotice = { showNotice(it, isError = false) },
                                        )
                                        is ChatRender.Item.FileAttachment -> Text(
                                            tr("📎 ${item.name ?: "附件文件"}", "📎 ${item.name ?: "Attachment"}"),
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                        )
                                        is ChatRender.Item.Btw -> Text(
                                            "💬 ${item.from?.let { "$it: " } ?: ""}${item.text}",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                        )
                                        is ChatRender.Item.SystemLine -> Text(
                                            item.text ?: "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                        if (taskActive && !loading && !hasBotReply) {
                            item(key = "task-live-indicator") {
                                WorkPhraseLine(
                                    phrase = ChatBars.taskStatusLabel(status),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 操作提示（发送成功/失败、停止失败、复制提示）
        notice?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.labelMedium,
                color = if (noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        // 底部区域：活动态显示纯文本追问输入框；结束态或任务不存在时只读（若有未发草稿允许复制）
        if (taskActive) {
            val enterToSend = LocalTeamPrefs.current.enterToSend
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(tr("向后台任务追加消息…", "Send follow-up to task…")) },
                    maxLines = 4,
                    enabled = !sending,
                    keyboardOptions = enterImeOptions(enterToSend),
                    keyboardActions = KeyboardActions(onSend = { sendFollowup() }),
                    modifier = Modifier.weight(1f).enterSends(enterToSend) { sendFollowup() },
                )
                Spacer(Modifier.size(8.dp))
                if (sending) {
                    ThinkingPill()
                } else {
                    FilledIconButton(
                        onClick = { sendFollowup() },
                        enabled = draft.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = tr("发送", "Send"))
                    }
                }
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    val label = if (status.isNotEmpty()) {
                        tr("任务已结束（${ChatBars.taskStatusLabel(status)}），当前为只读", "Task ended (${ChatBars.taskStatusLabel(status)}), read-only")
                    } else {
                        tr("当前为只读", "Read-only")
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val extra = task?.error ?: task?.summary
                    if (!extra.isNullOrEmpty() && mergedRecords.isNotEmpty()) {
                        Text(
                            extra,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (task?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (draft.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        ) {
                            Text(
                                tr("未发出草稿：$draft", "Unsent draft: $draft"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(draft))
                                showNotice(tr("草稿已复制", "Draft copied"), isError = false)
                            }) { Text(tr("复制草稿", "Copy draft")) }
                        }
                    }
                }
            }
        }
    }
    }

    // 复用聊天查看层（长按菜单、全屏可选阅读器、交付文件操作与图片查看器）
    if (media != null) {
        ChatMediaOverlays(
            ui = mediaUi,
            media = media,
            sessionId = taskSid,
            onNotice = { showNotice(it, isError = false) },
            readAttachment = { _, cb -> cb(null) },
        )
    } else {
        mediaUi.menuFor?.let { m ->
            MessageMenuSheet(
                onDismiss = { mediaUi.menuFor = null },
                onCopy = { clipboard.setText(AnnotatedString(m.markdown)) },
                onSelect = { mediaUi.reader = m.markdown to null },
            )
        }
        mediaUi.reader?.let { (text, note) -> ReaderDialog(text, note) { mediaUi.reader = null } }
    }
}

@Composable
private fun taskStatusColor(status: String): Color = when (status) {
    "running" -> Color(0xFF4CAF50)
    "waiting_approval", "waiting_work" -> Color(0xFFFFA000)
    "failed" -> MaterialTheme.colorScheme.error
    "completed" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun TaskBriefCard(
    brief: BotTaskDetail.Entry.TaskBrief,
    onCopy: () -> Unit,
    onCopyBlock: (String) -> Unit,
    onOpenLink: (String, Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onCopy),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = brief.fromName ?: tr("任务说明", "Task brief"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(4.dp))
            MarkdownText(brief.text, onCopyBlock = onCopyBlock, onOpenLink = onOpenLink)
        }
    }
}

@Composable
private fun TaskStepsCard(steps: BotTaskDetail.Entry.Steps) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            val header = buildString {
                append(tr("执行步骤（${steps.tools.size} 步）", "Steps (${steps.tools.size})"))
                steps.durationMs?.let { append(" · ").append(BotTaskDetail.formatDurationMs(it)) }
            }
            Text(
                text = header,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.size(4.dp))
            for (tool in steps.tools) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                ) {
                    val dotColor = if (tool.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    Box(modifier = Modifier.size(6.dp).background(dotColor, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = tool.displayName(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (tool.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskSegmentDivider() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            tr("新分段", "New segment"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}
