package work.keepwriting.dshanywhere

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import work.keepwriting.core.ChatRender
import work.keepwriting.core.RoomChat
import work.keepwriting.core.TeamHome

/**
 * 群聊页（dsbot-group-chat）：成员消息流（speaker 名称/颜色标识、[PASS] 经 ChatRender
 * 不可见）、公告、@ 提及弹层（成员过滤）、引用聊天选择、停止群/成员、room-progress、
 * 群目标 goal-draft/goal-control。渲染组件与私聊共用（MarkdownText 等）。
 */
@Composable
fun RoomChatPage(
    controller: RoomChatController,
    roomId: String,
    entry: TeamHome.Entry?,
    memberNames: List<String> = emptyList(),
    memberIds: List<String> = emptyList(),
    onOpenLink: (String, Boolean) -> Unit = { _, _ -> },
    /** 最近 state 快照（含 live 字段），派生群目标条、群内电脑操作请求与成员工作短语（dsbot-chat-bars-0310）。 */
    state: work.keepwriting.core.Json.Value.Object? = null,
    barInFlight: List<String> = emptyList(),
    onBarAction: (String, String, String) -> Unit = { _, _, _ -> },
    /** 文件与图片取回（dsbot-reader-files）。 */
    media: ChatMedia? = null,
    /** 打开群详情页（dsbot-folders）。 */
    onOpenDetails: () -> Unit = {},
    /** 打开群内搜索页（dsbot-group-search）。 */
    onOpenSearch: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val jump = rememberJumpButton(roomId)
    val mediaUi = rememberChatMediaUi()
    OutboxEffects(listState, controller.sendScrollSignal, controller.sentSignal)
    // 待处理停靠区已作答的 id（dsbot-pending-dock）：随聊天记忆，只在主线程增删
    val dockAnswered = remember(roomId) { androidx.compose.runtime.mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var showMentions by remember { mutableStateOf(false) }
    var showGoalDialog by remember { mutableStateOf(false) }
    var goalText by remember { mutableStateOf("") }
    var showRefPicker by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val members = remember(memberNames) { memberNames }
    val context = androidx.compose.ui.platform.LocalContext.current

    // 附件选取（相册 / 文件；与私聊同通道：加入附件栏即上传，就绪后随消息提交）
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) controller.addAttachmentFromUri(context.contentResolver, uri)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) controller.addAttachmentFromUri(context.contentResolver, uri)
    }

    // 名册先于首读设置：成员名称与颜色以 state.bots 为先（dsbot-group-rounds）
    val roster = remember(state) { RoomChat.roster(state) }
    SideEffect { controller.roster = roster }
    LaunchedEffect(roomId) { controller.open(roomId) }
    LaunchedEffect(controller.jumpSignal) {
        if (controller.rows.isNotEmpty()) listState.scrollToItem(controller.rows.size)
    }

    val roomRaw = remember(state, roomId) {
        (state?.members?.get("rooms") as? work.keepwriting.core.Json.Value.Array)?.items
            ?.firstOrNull { ((it as? work.keepwriting.core.Json.Value.Object)?.members?.get("id") as? work.keepwriting.core.Json.Value.StringValue)?.value == roomId }
            as? work.keepwriting.core.Json.Value.Object
    }
    val goal = remember(roomRaw) { work.keepwriting.core.ChatBars.goal(roomRaw) }
    val roomAsks = remember(state, roomId) { work.keepwriting.core.ChatBars.computerAsks(state, roomId = roomId) }
    val roomWork = remember(state, roomId) { work.keepwriting.core.ChatBars.roomWork(state, roomId) }
    val idToName = remember(memberIds, members) { memberIds.zip(members).toMap() }

    Column(modifier = Modifier.fillMaxSize()) {
        // 群目标条（进行中/起草中/已暂停才显示），点按打开既有目标操作对话框
        if (goal != null) GoalBar(goal) { showGoalDialog = true }
        // 运行中成员的工作短语（live work.rooms；远端未返回时保留原「工作中」行）
        if (roomWork.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                for ((botId, doing) in roomWork.take(3)) {
                    WorkPhraseLine(tr("${idToName[botId] ?: botId}：${work.keepwriting.core.WorkPhrase.phrase(doing)}", "${idToName[botId] ?: botId}: ${work.keepwriting.core.WorkPhrase.phrase(doing)}"))
                }
                if (roomWork.size > 3) {
                    Text(tr("另有 ${roomWork.size - 3} 位成员工作中", "${roomWork.size - 3} more members working"), style = MaterialTheme.typography.labelSmall)
                }
            }
        } else if (controller.running.isNotEmpty() || members.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (controller.running.isNotEmpty()) {
                    // running 是 bot id：经名册映射为成员名（无映射时保留 id）
                    val names = controller.running.map { idToName[it] ?: it }
                    Text(
                        tr("工作中：${names.joinToString("、").take(24)}", "Working: ${names.joinToString(", ").take(24)}"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
        controller.announcement?.let {
            Text(
                tr("公告：$it", "Notice: $it"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            if (controller.rows.isEmpty() && controller.outbox.isEmpty()) {
                Text(tr("群聊还没有消息", "No messages in this group yet"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    if (controller.canLoadEarlier) {
                        item(key = "load-earlier") {
                            TextButton(onClick = { controller.loadEarlier() }, modifier = Modifier.fillMaxWidth()) {
                                Text(tr("更早的消息", "Earlier messages"))
                            }
                        }
                    }
                    items(controller.rows, key = { it.key }) { row ->
                        when (val item = row.item) {
                            is ChatRender.Item.Message -> MessageBubble(
                                fromUser = item.fromUser,
                                markdown = item.markdown,
                                speakerName = item.speakerName,
                                speakerColorKey = item.speakerColor,
                                onCopy = { clipboard.setText(AnnotatedString(item.markdown)) },
                                onLongPress = { mediaUi.menuFor = item },
                                onCopyBlock = { clipboard.setText(AnnotatedString(it)) },
                                onOpenLink = onOpenLink,
                                // 群里被截断的长帖：「阅读全文」经 file-text（dsbot-reader-files）
                                onReadFull = if (item.wholeFileJson != null && media != null) ({ mediaUi.menuFor = null; mediaUi.readFull(media, item.wholeFileJson!!) { controller.postNotice(it) } }) else null,
                            )
                            is ChatRender.Item.PresentedFile -> PresentedFileRow(
                                item = item,
                                progress = media?.progress?.takeIf { it.key == media.keyOf(item) },
                                onClick = { if (media != null) mediaUi.fileSheet = item },
                                onCancel = { media?.cancel() },
                            )
                            is ChatRender.Item.ToolCard -> Text(
                                tr("工具调用：${item.name ?: "未知"}", "Tool call: ${item.name ?: "unknown"}"),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                            )
                            is ChatRender.Item.FileAttachment -> Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // 与私聊同语义：直接交系统应用打开；失败时给出提示
                                        controller.readAttachment(item.refJson) { bytes ->
                                            if (bytes != null) {
                                                val name = item.name ?: "attachment"
                                                if (!AttachmentOpen.openWith(context, name, bytes)) {
                                                    controller.postNotice(tr("没有应用能打开 $name", "No app can open $name"))
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(tr("📎 ${item.name ?: "附件"}", "📎 ${item.name ?: "Attachment"}"), style = MaterialTheme.typography.bodyMedium)
                            }
                            is ChatRender.Item.InteractiveCard -> {
                                // 0.3.10 聊天里的卡片条目是回复记录（只读）；待处理项在下方停靠区（dsbot-pending-dock）
                                CardView(
                                    card = work.keepwriting.core.Cards.asReadOnly(item.card),
                                    onAnswer = { ep, pj -> controller.answerCard(ep, pj) { } },
                                    onNotice = { controller.postNotice(it) },
                                )
                            }
                            is ChatRender.Item.Btw -> Text(
                                "💬 " + item.text,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                            )
                            is ChatRender.Item.SystemLine -> Text(
                                item.text ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            )
                            is ChatRender.Item.Image -> Text(
                                tr("🖼️ ${item.name ?: "图片"}", "🖼️ ${item.name ?: "Image"}"),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .clickable(enabled = item.attachmentRefJson != null && media != null) {
                                        mediaUi.gallery = ChatRender.gallery(controller.rows.map { it.item }, item)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 2.dp),
                            )
                        }
                    }
                    // 发送箱本地行（发送中 / 未发送点按重试 / 删除；dsbot-outbox-retry）
                    outboxItems(controller.outbox, onRetry = { controller.retryOutbox(it) }, onDelete = { controller.deleteOutbox(it) })
                }
            }
            // 缓存滚动恢复/保存与「回到最新 / N 条新消息」（dsbot-chat-cache）
            ChatScrollSupport(
                chatId = roomId,
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
        ComputerAskCards(asks = roomAsks, inFlight = barInFlight, whoOf = { idToName[it] }) { ask, answer ->
            onBarAction(
                "computer:${ask.id}", "computer-app-answer",
                "{\"id\":${work.keepwriting.core.JsonString.of(ask.id)},\"answer\":\"$answer\"}",
            )
        }
        // 待处理停靠区：群会话与成员群内会话（rooms[].sessionId 与 parts）的提问/卡片，以及 roomId 归属的审批
        val roomSessions = remember(roomRaw) {
            val o = roomRaw?.members
            setOfNotNull((o?.get("sessionId") as? work.keepwriting.core.Json.Value.StringValue)?.value, roomId) +
                ((o?.get("parts") as? work.keepwriting.core.Json.Value.Array)?.items?.mapNotNull { (it as? work.keepwriting.core.Json.Value.StringValue)?.value } ?: emptyList())
        }
        val dockItems = remember(state, roomSessions, dockAnswered.size, work.keepwriting.core.I18n.lang) {
            work.keepwriting.core.PendingDock.collect(state, roomSessions, botId = null, roomId = roomId, answered = dockAnswered.toSet())
        }
        // 按聊天重置停靠区的展开/收起状态
        androidx.compose.runtime.key(roomId) {
            DockArea(
                items = dockItems,
                onAnswer = { ep, pj, done -> controller.answerCard(ep, pj, done) },
                onAnswered = { dockAnswered.add(it) },
                onNotice = { controller.postNotice(it) },
            )
        }
        if (controller.streamingText != null || controller.busy) {
            ThinkingPill(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        // 工具行可横向滚动：窄屏英文界面下五个按钮放不下（dsbot-folders 新增「详情」）
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onOpenSearch) { Text(tr("搜索", "Search")) }
            TextButton(onClick = { controller.showProgress() }) { Text(tr("进度", "Progress")) }
            TextButton(onClick = { controller.showStats(memberIds, members) }, enabled = members.isNotEmpty()) { Text(tr("统计", "Stats")) }
            TextButton(onClick = { showGoalDialog = true }) { Text(tr("目标", "Goal")) }
            TextButton(onClick = { showRefPicker = true }, enabled = members.isNotEmpty()) { Text(tr("＋引用", "+ Quote")) }
            TextButton(onClick = onOpenDetails) { Text(tr("详情", "Details")) }
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
        // 附件栏：缩略/文件 chip + 上传状态 + 移除/重试（与私聊同构）
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { showAttachMenu = true }) {
                Icon(Icons.Filled.Add, contentDescription = tr("添加附件", "Add attachment"))
            }
            // 回车发送（团队偏好 Enter 键发送）：与发送按钮同条件
            val enterToSend = LocalTeamPrefs.current.enterToSend
            // 切回单行时草稿里的换行折成空格（单行框只显示首行，避免看不见的换行被发出去）
            androidx.compose.runtime.LaunchedEffect(enterToSend) {
                if (enterToSend && input.contains('\n')) input = input.replace('\n', ' ')
            }
            val sendFromKey = {
                val text = input.trim()
                val ready = controller.attachments.none { it.status == "uploading" || it.status == "failed" }
                if (!controller.busy && ready && (text.isNotEmpty() || controller.attachments.isNotEmpty()) && controller.sendOrAttach(text)) input = ""
            }
            OutlinedTextField(
                value = input,
                onValueChange = { fresh ->
                    input = fresh
                    // 输入以 @ 结尾触发成员提及弹层
                    showMentions = fresh.isNotEmpty() && fresh.last() == '@'
                },
                placeholder = { Text(tr("发消息（@ 提及成员）", "Message (@ to mention)")) },
                // 单行（用户反馈：群聊输入框两行太高，一行足够）；@ 弹层不受影响。
                // 团队偏好关闭 Enter 发送时回车换行，输入框随换行长高（至多 4 行）
                singleLine = enterToSend,
                maxLines = if (enterToSend) 1 else 4,
                keyboardOptions = enterImeOptions(enterToSend),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { sendFromKey() }),
                modifier = Modifier.weight(1f).enterSends(enterToSend) { sendFromKey() },
            )
            Spacer(Modifier.size(8.dp))
            if (controller.busy) {
                ThinkingPill()
            } else {
                FilledIconButton(
                    onClick = {
                        val text = input.trim()
                        if ((text.isNotEmpty() || controller.attachments.isNotEmpty()) && controller.sendOrAttach(text)) input = ""
                    },
                    enabled = (input.isNotBlank() || controller.attachments.isNotEmpty()) &&
                        controller.attachments.none { it.status == "uploading" || it.status == "failed" },
                ) {
                    Icon(Icons.Filled.Send, contentDescription = tr("发送", "Send"))
                }
            }
        }
    }

    // 群统计弹窗：逐成员汇总（core 的 BotStatsText 翻译）
    controller.statsText?.let { body ->
        AlertDialog(
            onDismissRequest = { controller.dismissStats() },
            title = { Text(tr("群统计", "Group stats")) },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
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
                        pickFile.launch("*/*")
                    }) { Text(tr("选择文件", "Choose file")) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAttachMenu = false }) { Text(tr("取消", "Cancel")) } },
        )
    }

    // @ 提及弹层：过滤群成员
    if (showMentions && members.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showMentions = false },
            title = { Text(tr("提及成员", "Mention a member")) },
            text = {
                Column {
                    members.forEach { name ->
                        TextButton(onClick = {
                            input = input.dropLast(1) + "@" + name + " "
                            showMentions = false
                        }) { Text("@$name") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMentions = false }) { Text(tr("取消", "Cancel")) } },
        )
    }

    // 群目标：草稿文本 + 控制操作
    if (showGoalDialog) {
        AlertDialog(
            onDismissRequest = { showGoalDialog = false },
            title = { Text(tr("群目标", "Group goal")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = goalText,
                        onValueChange = { goalText = it },
                        placeholder = { Text(tr("写下这个群要达成什么（≤2000 字）", "Write what this group should achieve (≤ 2000 chars)")) },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = {
                        if (goalText.isNotBlank()) controller.goalDraft(goalText.trim())
                        goalText = ""
                    }) { Text(tr("生成草稿", "Draft")) }
                    TextButton(onClick = { controller.goalControl("confirm") }) { Text(tr("确认草稿", "Confirm draft")) }
                    TextButton(onClick = { controller.goalControl("advance") }) { Text(tr("推进下一阶段", "Next stage")) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showGoalDialog = false }) { Text(tr("关闭", "Close")) } },
        )
    }

    // 引用聊天：选择一个 Bot 聊天作为上下文（以引用标注随消息发送）
    if (showRefPicker && members.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showRefPicker = false },
            title = { Text(tr("引用一个聊天", "Quote a chat")) },
            text = {
                Column {
                    members.forEach { name ->
                        TextButton(onClick = {
                            input = (if (input.isBlank()) "" else input + " ") + tr("（引用了 $name 的聊天）", "(Quoted $name's chat)")
                            showRefPicker = false
                        }) { Text(name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showRefPicker = false }) { Text(tr("取消", "Cancel")) } },
        )
    }

    // 查看层：长按菜单、阅读器、交付文件菜单、图片查看器（dsbot-reader-files）
    if (media != null) {
        ChatMediaOverlays(
            ui = mediaUi,
            media = media,
            sessionId = roomId,
            onNotice = { controller.postNotice(it) },
            readAttachment = { ref, cb -> controller.readAttachment(ref, cb) },
        )
    }
}

@Composable
private fun MessageBubble(
    fromUser: Boolean,
    markdown: String,
    speakerName: String?,
    speakerColorKey: String?,
    onCopy: () -> Unit,
    onLongPress: () -> Unit,
    onCopyBlock: (String) -> Unit,
    onOpenLink: (String, Boolean) -> Unit,
    onReadFull: (() -> Unit)? = null,
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
            // 点按复制整条；长按出菜单（dsbot-reader-files），链接手势由 MarkdownText 先消费
            modifier = (if (flat) Modifier.fillMaxWidth() else Modifier.widthIn(max = 320.dp)).combinedClickable(onClick = onCopy, onLongClick = { if (!LinkLongPress.recent()) onLongPress() }),
        ) {
            Column(modifier = Modifier.padding(if (flat) 4.dp else 10.dp)) {
                if (flat && fromUser) Text(tr("你", "You"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (!fromUser && speakerName != null) {
                    Text(
                        speakerName,
                        style = MaterialTheme.typography.labelSmall,
                        color = speakerColorKey?.let { speakerColor(it) } ?: MaterialTheme.colorScheme.primary,
                    )
                }
                MarkdownText(markdown, onCopyBlock = onCopyBlock, onOpenLink = onOpenLink)
                if (onReadFull != null) {
                    TextButton(onClick = onReadFull) { Text(tr("阅读全文", "Read full text")) }
                }
            }
        }
    }
}

/** 成员名颜色（与团队页 colorOf 同款映射的群聊内副本；未知色键与 null 走默认蓝/主题色）。 */
internal fun speakerColor(name: String): androidx.compose.ui.graphics.Color = when (name) {
    "blue" -> androidx.compose.ui.graphics.Color(0xFF1E88E5)
    "green" -> androidx.compose.ui.graphics.Color(0xFF43A047)
    "violet" -> androidx.compose.ui.graphics.Color(0xFF8E24AA)
    "orange" -> androidx.compose.ui.graphics.Color(0xFFFB8C00)
    "red" -> androidx.compose.ui.graphics.Color(0xFFE53935)
    else -> androidx.compose.ui.graphics.Color(0xFF1E88E5)
}
