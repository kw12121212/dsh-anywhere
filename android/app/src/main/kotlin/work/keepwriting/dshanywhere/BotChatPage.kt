package work.keepwriting.dshanywhere

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import work.keepwriting.core.ChatRender
import androidx.compose.foundation.combinedClickable

/**
 * 私聊页（dsbot-bot-chat / dsbot-attachments）：消息列表（ChatRender 渲染模型 +
 * 轻量 Markdown，行带稳定 key）、输入发送（纯文本 send；带附件经 mux prompt）、
 * 附件栏（选取/预览/移除/上传状态/失败重试）、流式气泡、停止/恢复/新分段/旧分段/
 * 统计/Bot 间记录、更早分页与已到最早提示、图片附件查看与保存。
 * 数据经注入的 ChatController，本页无网络逻辑。
 */
@Composable
fun BotChatPage(
    controller: ChatController,
    botId: String,
    parts: List<String>,
    onOpenSide: () -> Unit = {},
    /** 打开 Bot 详情页（dsbot-bot-manage）。 */
    onOpenDetails: () -> Unit = {},
    /**
     * DSH Agent 模式（dsbot-member-agents）：隐藏分段/侧问/统计/详情、附件与恢复入口，操作行只有「会话」菜单（[onAgentMenu]）。
     */
    agentMode: Boolean = false,
    onAgentMenu: () -> Unit = {},
    onOpenLink: (String, Boolean) -> Unit = { _, _ -> },
    /** 最近 state 快照（含 live 字段），派生 0.3.10 聊天条（dsbot-chat-bars-0310）；null 时不显示。 */
    state: work.keepwriting.core.Json.Value.Object? = null,
    /** 该 Bot 当前分段的会话 id（state 的 bots[].sessionId）：判断是否在看旧分段。 */
    entrySessionId: String? = null,
    barInFlight: List<String> = emptyList(),
    /** 聊天条写操作：(在途键, 端点, payload JSON)。 */
    onBarAction: (String, String, String) -> Unit = { _, _, _ -> },
    /** 文件与图片取回（dsbot-reader-files）；null 时不显示交付文件操作与图片查看器（旧行为）。 */
    media: ChatMedia? = null,
    /** 打开该 Bot 的浏览器页（停靠区浏览器交接卡「接管」直达；dsbot-bot-browser）。 */
    onOpenBrowser: ((String) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val jump = rememberJumpButton(botId)
    val mediaUi = rememberChatMediaUi()
    OutboxEffects(listState, controller.sendScrollSignal, controller.sentSignal)
    // 待处理停靠区已作答的 id（dsbot-pending-dock）：随聊天记忆，只在主线程增删
    val dockAnswered = remember(botId) { androidx.compose.runtime.mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var showPartsDialog by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var viewingFile by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 相册（Photo Picker，无需存储权限；字节读取在 executor 后台）
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) controller.addAttachmentFromUri(context.contentResolver, uri)
    }
    // 文件选择器
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) controller.addAttachmentFromUri(context.contentResolver, uri)
    }
    // 相机（拍完从 FileProvider URI 读字节，读后删临时文件）
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            val uri = cameraUri
            if (uri != null) controller.addAttachmentFromUri(context.contentResolver, uri, deleteAfterRead = true)
        }
    }
    // 文件附件保存（系统文件保存器；结果回调写回字节）
    var pendingSaveBytes by remember { mutableStateOf<ByteArray?>(null) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val bytes = pendingSaveBytes
        if (uri != null && bytes != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                controller.postNotice(tr("已保存", "Saved"))
            }.onFailure { controller.postNotice(tr("保存失败", "Save failed")) }
        }
        pendingSaveBytes = null
    }

    LaunchedEffect(botId) { controller.open(botId) }
    // 首页加载/分段切换完成后滚到底（与网页端重进在底部一致）；列表首位恒有
    // 更早/已到最早头部条目，滚到 rows.size（= 最后一条之后的位）确保最新条完整进视口
    LaunchedEffect(controller.jumpSignal) {
        if (controller.rows.isNotEmpty()) listState.scrollToItem(controller.rows.size)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            if (controller.rows.isEmpty() && controller.outbox.isEmpty()) {
                Text(
                    tr("还没有消息，发送第一条开始对话", "No messages yet. Send the first one to start."),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    if (controller.canLoadEarlier) {
                        item(key = "load-earlier") {
                            TextButton(
                                onClick = { controller.loadEarlier() },
                                enabled = !controller.loadingEarlier,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (controller.loadingEarlier) tr("加载中…", "Loading…") else tr("更早的消息", "Earlier messages")) }
                        }
                    } else {
                        item(key = "earliest") {
                            Text(
                                tr("已到最早", "Reached the beginning"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                    items(controller.rows, key = { it.key }) { row ->
                        when (val item = row.item) {
                            is ChatRender.Item.Message -> MessageBubble(
                                fromUser = item.fromUser,
                                markdown = item.markdown,
                                onCopy = { clipboard.setText(AnnotatedString(item.markdown)) },
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
                                val refJson = item.attachmentRefJson
                                if (refJson != null) {
                                    // 图片查看器：同一聊天的图片左右滑动，先预览后原图（dsbot-reader-files）
                                    mediaUi.gallery = ChatRender.gallery(controller.rows.map { it.item }, item)
                                } else {
                                    item.ref?.let { ref ->
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ref))) }
                                    }
                                }
                            })
                            is ChatRender.Item.FileAttachment -> Row(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .clickable {
                                        // 直接交系统应用打开（音频/视频出系统播放器、PDF 等
                                        // 出查看器）；无应用可处理时退回内嵌对话框（含保存）。
                                        controller.readAttachment(item.refJson) { bytes ->
                                            if (bytes != null) {
                                                val name = item.name ?: "attachment"
                                                if (!AttachmentOpen.openWith(context, name, bytes)) {
                                                    viewingFile = name to bytes
                                                }
                                            }
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(tr("📎 ${item.name ?: "附件文件"}", "📎 ${item.name ?: "Attachment"}"), style = MaterialTheme.typography.bodyMedium)
                            }
                            is ChatRender.Item.InteractiveCard -> {
                                // 0.3.10 聊天里的卡片条目是回复记录（只读）；待处理项在下方停靠区（dsbot-pending-dock）
                                CardView(
                                    card = work.keepwriting.core.Cards.asReadOnly(item.card),
                                    onAnswer = { ep, pj -> controller.answerCard(ep, pj) { } },
                                    onNotice = { controller.postNotice(it) },
                                )
                            }
                            is ChatRender.Item.Btw -> {
                                var deleted by remember(row.key) { mutableStateOf(false) }
                                if (!deleted) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                                    ) {
                                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("💬 ${item.from?.let { "$it: " } ?: ""}${item.text}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                            TextButton(onClick = {
                                                val sid = controller.shownSessionId
                                                val id = item.id
                                                if (sid != null && id != null) {
                                                    // 0.3.10 btw-delete 需要 {sessionId, cardId}（dsbot-pending-dock 评审修正）
                                                    controller.answerCard("btw-delete", work.keepwriting.core.PendingDock.Requests.btwDelete(sid, id)) { ok ->
                                                        if (ok) deleted = true
                                                    }
                                                }
                                            }) { Text(tr("删除", "Delete")) }
                                        }
                                    }
                                }
                            }
                            is ChatRender.Item.SystemLine -> Text(
                                item.text ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            )
                        }
                    }
                    // 发送箱本地行（发送中 / 未发送点按重试 / 删除；dsbot-outbox-retry）
                    outboxItems(controller.outbox, onRetry = { controller.retryOutbox(it) }, onDelete = { controller.deleteOutbox(it) })
                }
                // 流式期间自动滚动到底；更早记录前插（rows.size 也变）不滚动，key 保持视口
                val streaming by remember { derivedStateOf { controller.streamingText != null } }
                LaunchedEffect(controller.rows.size, streaming) {
                    // 离底较远翻看历史时不强拉到底（由「回到最新」按钮提示；dsbot-chat-cache）
                    if (controller.rows.isNotEmpty() && streaming && !jump.away) listState.animateScrollToItem(controller.rows.size)
                }
            }
            // 缓存滚动恢复/保存与「回到最新 / N 条新消息」（dsbot-chat-cache）
            ChatScrollSupport(
                chatId = botId,
                listState = listState,
                jump = jump,
                // 组合期间读 rows（快照隔离，executor 线程并发修改不影响）；只在条数或末行变化时重算
                rowKeys = remember(controller.rows.size, controller.rows.lastOrNull()?.key) { controller.rows.map { it.key } },
                // 末行是用户消息（本机发出的经 feed/刷新确认后带 seq）时跟随到底
                lastIsMine = (controller.rows.lastOrNull()?.item as? ChatRender.Item.Message)?.fromUser == true,
                freezeAway = { controller.streamingText != null },
                restoreSignal = controller.restoreSignal,
                restorePosition = { controller.restorePosition },
                onSave = { i, o -> controller.saveScroll(i, o) },
            )
        }
        if (controller.streamingText != null) {
            StreamingBubble(controller.streamingText ?: "")
        }
        if (agentMode) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextButton(onClick = onAgentMenu) { Text(tr("会话", "Sessions")) }
            }
        } else {
            ChatActionRow(controller, parts, onPickPart = { showPartsDialog = true }, onOpenSide = onOpenSide, onOpenDetails = onOpenDetails)
        }
        controller.notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        // 附件栏：缩略图/文件 chip + 上传状态 + 移除/重试（dsbot-attachments）
        if (controller.attachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(controller.attachments, key = { it.id }) { att ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (att.isImage) "🖼 ${att.name.take(12)}" else "📄 ${att.name.take(12)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.widthIn(max = 140.dp),
                                    maxLines = 1,
                                )
                                Spacer(Modifier.size(4.dp))
                                TextButton(onClick = { controller.removeAttachment(att.id) }) { Text("✕") }
                            }
                            when (att.status) {
                                "uploading" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    ThinkingPill()
                                    Text(tr(" 上传中", " Uploading"), style = MaterialTheme.typography.labelSmall)
                                }
                                "ready" -> Text(tr("已就绪", "Ready"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                "failed" -> TextButton(onClick = { controller.retryAttachment(att.id) }) {
                                    Text(tr("重试（${att.failReason ?: "失败"}）", "Retry (${att.failReason ?: "failed"})"), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        // 0.3.10 聊天条：按远端是否返回对应字段显隐（字段缺失时整块不渲染，页面与原来一致）
        val repair = remember(state, botId) { work.keepwriting.core.ChatBars.repair(state, botId) }
        val asks = remember(state, botId) { work.keepwriting.core.ChatBars.computerAsks(state, botId = botId) }
        val tasks = remember(state, botId) { work.keepwriting.core.ChatBars.tasks(state, botId) }
        val computerBusy = remember(state, botId) { work.keepwriting.core.ChatBars.computerBusy(state, botId) }
        val workEntry = remember(state, botId) { work.keepwriting.core.ChatBars.botWork(state, botId) }
        val idJson = work.keepwriting.core.JsonString.of(botId)
        if (repair != null) {
            RepairCard(
                repair = repair,
                busy = "resume:$botId" in barInFlight,
                onRetry = { onBarAction("resume:$botId", "resume", "{\"botId\":$idJson}") },
                onTopUp = { onOpenLink(work.keepwriting.core.ChatBars.TOP_UP_URL, false) },
            )
        }
        ComputerAskCards(asks = asks, inFlight = barInFlight) { ask, answer ->
            onBarAction(
                "computer:${ask.id}", "computer-app-answer",
                "{\"id\":${work.keepwriting.core.JsonString.of(ask.id)},\"answer\":\"$answer\"}",
            )
        }
        if (computerBusy) {
            ComputerStopBar(busy = "computer-stop:$botId" in barInFlight) {
                onBarAction("computer-stop:$botId", "computer-stop", "{\"botId\":$idJson}")
            }
        }
        if (tasks.isNotEmpty()) {
            TaskBar(tasks = tasks, inFlight = barInFlight) { task ->
                onBarAction(
                    "task:${task.id}", "bot-stop-task",
                    "{\"botId\":$idJson,\"taskId\":${work.keepwriting.core.JsonString.of(task.id)}}",
                )
            }
        }
        // 待处理停靠区：从 state 汇集本聊天（当前显示的会话）的审批、提问、密钥/连接器请求、学习审批、交接与卡片；
        // 打开旧分段时不显示只属于当前分段的学习审批与交接
        val shownSid = controller.shownSessionId
        val viewingCurrent = shownSid != null && shownSid == entrySessionId
        val dockItems = remember(state, shownSid, viewingCurrent, dockAnswered.size, work.keepwriting.core.I18n.lang) {
            // 会话确认前不显示（避免先按「旧分段」过滤再出现学习审批，把展开项固定到错误的那一项）
            if (shownSid == null) return@remember emptyList()
            work.keepwriting.core.PendingDock.collect(
                state = state,
                sessionIds = setOfNotNull(shownSid),
                botId = botId,
                roomId = null,
                answered = dockAnswered.toSet(),
            ).filter { viewingCurrent || (it.kind != work.keepwriting.core.PendingDock.Kind.LEARNING && it.kind != work.keepwriting.core.PendingDock.Kind.HANDOFF) }
        }
        // 按聊天重置停靠区的展开/收起状态
        androidx.compose.runtime.key(botId) {
            DockArea(
                items = dockItems,
                onAnswer = { ep, pj, done -> controller.answerCard(ep, pj, done) },
                onAnswered = { dockAnswered.add(it) },
                onNotice = { controller.postNotice(it) },
                onOpenBrowser = onOpenBrowser,
            )
        }
        if (controller.working || controller.busy || controller.streamingText != null) {
            // 工作中显示按工具区分的工作短语（远端未返回工作详情时只显示原指示）
            // 工作详情带 roomId 时 Bot 正在群里工作：注明，避免误以为是私聊里的工作
            val phrase = if (controller.working && workEntry != null) {
                work.keepwriting.core.WorkPhrase.phrase(workEntry) +
                    (if (workEntry.members["roomId"] is work.keepwriting.core.Json.Value.StringValue) tr("（群聊中）", " (in a group)") else "")
            } else {
                null
            }
            WorkPhraseLine(phrase, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        // 发送/停止/恢复合一的图标按钮（现代聊天输入区形态）：Bot 工作中=停止、
        // 会话被中断=恢复、其余=发送；发送中（busy）以思考条占位。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!agentMode) {
                IconButton(onClick = { showAttachMenu = true }) {
                    Icon(Icons.Filled.Add, contentDescription = tr("添加附件", "Add attachment"))
                }
            }
            val attachmentsReady = controller.attachments.all { it.status == "ready" }
            // 回车发送（团队偏好 Enter 键发送）：与发送按钮同条件——工作中、待恢复、发送中或附件未就绪时不发
            val enterToSend = LocalTeamPrefs.current.enterToSend
            val sendFromKey = {
                val text = input.trim()
                val idle = !controller.working && !(controller.interrupted && !agentMode) && !controller.busy
                if (idle && attachmentsReady && (text.isNotEmpty() || controller.attachments.isNotEmpty()) && controller.sendOrAttach(text)) input = ""
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(if (agentMode) tr("发送消息给 DSH Agent", "Message the DSH Agent") else tr("发送消息给 Bot", "Message the Bot")) },
                maxLines = 4,
                keyboardOptions = enterImeOptions(enterToSend),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { sendFromKey() }),
                modifier = Modifier.weight(1f).enterSends(enterToSend) { sendFromKey() },
            )
            Spacer(Modifier.size(8.dp))
            when {
                controller.working -> FilledIconButton(onClick = { controller.stop() }) {
                    // 方形停止符（icons-core 无 Stop，自绘 18dp 圆角方块）
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(androidx.compose.material3.LocalContentColor.current, RoundedCornerShape(4.dp)),
                    )
                }
                controller.interrupted && !agentMode -> FilledIconButton(onClick = { controller.resume() }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = tr("恢复会话", "Resume session"))
                }
                controller.busy -> ThinkingPill()
                else -> FilledIconButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty() || controller.attachments.isNotEmpty()) {
                            if (controller.sendOrAttach(text)) input = ""
                        }
                    },
                    // 附件 uploading/failed 时禁发（状态读取仅作禁用提示，判定仍收敛 executor）
                    enabled = (input.isNotBlank() || controller.attachments.isNotEmpty()) &&
                        attachmentsReady,
                ) {
                    Icon(Icons.Filled.Send, contentDescription = tr("发送", "Send"))
                }
            }
        }
    }

    // 统计 / Bot 间记录：人话摘要弹窗（core 的 BotStatsText 翻译，可滚动可复制）
    controller.statsText?.let { body ->
        AlertDialog(
            onDismissRequest = { controller.dismissStats() },
            title = { Text(tr("统计", "Stats")) },
            text = {
                SelectionContainer {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(max = 420.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { controller.dismissStats() }) { Text(tr("关闭", "Close")) } },
        )
    }
    if (showAttachMenu) {
        AlertDialog(
            onDismissRequest = { showAttachMenu = false },
            title = { Text(tr("添加附件", "Add attachment")) },
            text = {
                Column {
                    TextButton(onClick = {
                        showAttachMenu = false
                        pickImage.launch(androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ))
                    }) { Text(tr("从相册选择图片", "Choose from photos")) }
                    TextButton(onClick = {
                        showAttachMenu = false
                        val uri = CameraOutput.newUri(context)
                        cameraUri = uri
                        if (uri != null) takePicture.launch(uri)
                    }) { Text(tr("拍照", "Take photo")) }
                    TextButton(onClick = {
                        showAttachMenu = false
                        pickFile.launch("*/*")
                    }) { Text(tr("选择文件", "Choose file")) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAttachMenu = false }) { Text(tr("取消", "Cancel")) } },
        )
    }

    if (showPartsDialog && parts.size > 1) {
        AlertDialog(
            onDismissRequest = { showPartsDialog = false },
            title = { Text(tr("切换分段", "Switch part")) },
            text = {
                Column {
                    parts.forEachIndexed { i, _ ->
                        TextButton(
                            onClick = {
                                showPartsDialog = false
                                controller.openPart(i, parts[i], parts.size)
                            },
                        ) { Text(tr("分段 ${i + 1}${if (i == parts.lastIndex) "（当前）" else ""}", "Part ${i + 1}${if (i == parts.lastIndex) " (current)" else ""}")) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPartsDialog = false }) { Text(tr("取消", "Cancel")) }
            },
        )
    }

    // 查看层：长按菜单、阅读器、交付文件菜单、图片查看器（dsbot-reader-files）
    if (media != null) {
        ChatMediaOverlays(
            ui = mediaUi,
            media = media,
            sessionId = controller.shownSessionId,
            onNotice = { controller.postNotice(it) },
            readAttachment = { ref, cb -> controller.readAttachment(ref, cb) },
        )
    } else {
        mediaUi.gallery?.let { (images, start) ->
            ImageGalleryDialog(
                images = images, start = start,
                load = { image, preview, cb -> val ref = image.attachmentRefJson; if (preview || ref == null) cb(null) else controller.readAttachment(ref, cb) },
                onSave = { bytes -> controller.postNotice(if (saveToGallery(context, bytes)) tr("已保存到相册", "Saved to Photos") else tr("保存失败", "Save failed")) },
                onDismiss = { mediaUi.gallery = null },
            )
        }
        mediaUi.menuFor?.let { m ->
            MessageMenuSheet(
                onDismiss = { mediaUi.menuFor = null },
                onCopy = { clipboard.setText(AnnotatedString(m.markdown)) },
                onSelect = { mediaUi.reader = m.markdown to null },
            )
        }
        mediaUi.reader?.let { (text, note) -> ReaderDialog(text, note) { mediaUi.reader = null } }
    }

    // 文件附件查看（dsbot-attachments）：字节写 cache 临时文件交系统查看器；保存经系统文件保存器
    viewingFile?.let { (name, bytes) ->
        Dialog(onDismissRequest = { viewingFile = null }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(tr("文件：$name（${bytes.size} 字节）", "File: $name (${bytes.size} bytes)"), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            AttachmentOpen.openWith(context, name, bytes)
                            viewingFile = null
                        }) { Text(tr("用其他应用打开", "Open with another app")) }
                        TextButton(onClick = {
                            pendingSaveBytes = bytes
                            saveFile.launch(name)
                            viewingFile = null
                        }) { Text(tr("保存", "Save")) }
                        TextButton(onClick = { viewingFile = null }) { Text(tr("关闭", "Close")) }
                    }
                }
            }
        }
    }
}

/** 相机输出的 FileProvider URI（dsbot-attachments；拍完读字节即抛弃）。 */
object CameraOutput {
    fun newUri(context: android.content.Context): Uri? = runCatching {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        val file = java.io.File(dir, "shot-${System.currentTimeMillis()}.jpg")
        androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file,
        )
    }.getOrNull()
}

@Composable
private fun MessageBubble(
    fromUser: Boolean,
    markdown: String,
    onCopy: () -> Unit,
    onLongPress: () -> Unit,
    onCopyBlock: (String) -> Unit,
    onOpenLink: (String, Boolean) -> Unit,
) {
    // 平铺布局（团队偏好，dsbot-prefs-theme）：不画气泡、整宽靠左，自己的消息以「你」标注
    val flat = LocalTeamPrefs.current.flat
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (fromUser && !flat) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = when {
                flat -> androidx.compose.ui.graphics.Color.Transparent
                fromUser -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.medium,
            // 点按复制整条；长按出「复制整条 / 选择文字」菜单，部分选择在全屏阅读器里做（dsbot-reader-files）。
            // 链接自身的点击与长按选择器由 MarkdownText 先消费，不触发气泡手势。
            modifier = (if (flat) Modifier.fillMaxWidth() else Modifier.widthIn(max = 320.dp)).combinedClickable(onClick = onCopy, onLongClick = { if (!LinkLongPress.recent()) onLongPress() }),
        ) {
            Column(modifier = Modifier.padding(if (flat) 4.dp else 10.dp)) {
                if (flat && fromUser) Text(tr("你", "You"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                MarkdownText(markdown, onCopyBlock = onCopyBlock, onOpenLink = onOpenLink)
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                MarkdownText(text)
            }
        }
    }
}

@Composable
private fun ToolCardRow(item: ChatRender.Item.ToolCard) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                tr("工具调用：${item.name ?: "未知"}", "Tool call: ${item.name ?: "unknown"}"),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            item.summary?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ImageRow(item: ChatRender.Item.Image, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("🖼️ ${item.name ?: item.ref ?: "图片"}", "🖼️ ${item.name ?: item.ref ?: "Image"}"), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChatActionRow(controller: ChatController, parts: List<String>, onPickPart: () -> Unit, onOpenSide: () -> Unit = {}, onOpenDetails: () -> Unit = {}) {
    // 停止/恢复已并入输入区图标按钮；此处只保留会话操作。
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = { controller.newPart() }) { Text(tr("新分段", "New part")) }
        if (parts.size > 1) {
            TextButton(onClick = onPickPart) { Text(tr("旧分段", "Earlier parts")) }
        }
        TextButton(onClick = onOpenSide) { Text(tr("侧问", "Side chat")) }
        TextButton(onClick = { controller.showStats() }) { Text(tr("统计", "Stats")) }
        TextButton(onClick = onOpenDetails) { Text(tr("详情", "Details")) }
    }
}

