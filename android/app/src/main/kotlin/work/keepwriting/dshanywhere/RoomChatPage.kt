package work.keepwriting.dshanywhere

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import work.keepwriting.core.TeamHome

/**
 * 群聊页（dsbot-group-chat）：成员消息流（speaker 名称/颜色标识、[PASS] 经 ChatRender
 * 不可见）、公告、@ 提及弹层（成员过滤）、引用聊天选择、停止群/成员、room-progress、
 * 群目标 goal-draft/goal-control。渲染组件与私聊共用（MarkdownText 等）。
 */
@Composable
fun RoomChatPage(controller: RoomChatController, roomId: String, entry: TeamHome.Entry?, memberNames: List<String> = emptyList(), memberIds: List<String> = emptyList()) {
    val listState = rememberLazyListState()
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

    LaunchedEffect(roomId) { controller.open(roomId) }
    LaunchedEffect(controller.jumpSignal) {
        if (controller.rows.isNotEmpty()) listState.scrollToItem(controller.rows.size)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (controller.running.isNotEmpty() || members.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (controller.running.isNotEmpty()) {
                    // running 是 bot id：经名册映射为成员名（无映射时保留 id）
                    val idToName = memberIds.zip(members).toMap()
                    val names = controller.running.map { idToName[it] ?: it }
                    Text(
                        "工作中：${names.joinToString("、").take(24)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
        controller.announcement?.let {
            Text(
                "公告：$it",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            if (controller.rows.isEmpty()) {
                Text("群聊还没有消息", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    if (controller.canLoadEarlier) {
                        item(key = "load-earlier") {
                            TextButton(onClick = { controller.loadEarlier() }, modifier = Modifier.fillMaxWidth()) {
                                Text("更早的消息")
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
                                onCopyBlock = { clipboard.setText(AnnotatedString(it)) },
                                onOpenLink = { url ->
                                    // 群聊链接与私聊同语义：系统浏览器打开（原空实现导致点击无反应）
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(url),
                                            ),
                                        )
                                    }
                                },
                            )
                            is ChatRender.Item.ToolCard -> Text(
                                "工具调用：${item.name ?: "未知"}",
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
                                                    controller.postNotice("没有应用能打开 $name")
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("📎 ${item.name ?: "附件"}", style = MaterialTheme.typography.bodyMedium)
                            }
                            is ChatRender.Item.InteractiveCard -> {
                                var dismissed by remember(row.key) { mutableStateOf(false) }
                                if (!dismissed) {
                                    CardView(
                                        card = item.card,
                                        onAnswer = { ep, pj -> controller.answerCard(ep, pj) { ok -> if (ok) dismissed = true } },
                                        onNotice = { controller.postNotice(it) },
                                    )
                                }
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
                                "🖼️ ${item.name ?: "图片"}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        if (controller.streamingText != null) {
            Text(
                "正在输入…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { controller.showProgress() }) { Text("进度") }
            TextButton(onClick = { showGoalDialog = true }) { Text("目标") }
            TextButton(onClick = { showRefPicker = true }, enabled = members.isNotEmpty()) { Text("＋引用") }
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
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp))
                                    Text(" 上传中", style = MaterialTheme.typography.labelSmall)
                                }
                                "ready" -> Text("已就绪", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                "failed" -> TextButton(onClick = { controller.retryAttachment(att.id) }) {
                                    Text("重试（${att.failReason ?: "失败"}）", style = MaterialTheme.typography.labelSmall)
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
            TextButton(onClick = { showAttachMenu = true }) { Text("＋") }
            OutlinedTextField(
                value = input,
                onValueChange = { fresh ->
                    input = fresh
                    // 输入以 @ 结尾触发成员提及弹层
                    showMentions = fresh.isNotEmpty() && fresh.last() == '@'
                },
                placeholder = { Text("发送群消息（@ 提及成员）") },
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            TextButton(
                onClick = {
                    val text = input.trim()
                    if ((text.isNotEmpty() || controller.attachments.isNotEmpty()) && controller.sendOrAttach(text)) input = ""
                },
                enabled = (input.isNotBlank() || controller.attachments.isNotEmpty()) && !controller.busy &&
                    controller.attachments.none { it.status == "uploading" || it.status == "failed" },
            ) { Text("发送") }
        }
    }

    if (showAttachMenu) {
        AlertDialog(
            onDismissRequest = { showAttachMenu = false },
            title = { Text("添加附件") },
            text = {
                Column {
                    TextButton(onClick = {
                        showAttachMenu = false
                        pickImage.launch(androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ))
                    }) { Text("从相册选择图片") }
                    TextButton(onClick = {
                        showAttachMenu = false
                        pickFile.launch("*/*")
                    }) { Text("选择文件") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAttachMenu = false }) { Text("取消") } },
        )
    }

    // @ 提及弹层：过滤群成员
    if (showMentions && members.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showMentions = false },
            title = { Text("提及成员") },
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
            dismissButton = { TextButton(onClick = { showMentions = false }) { Text("取消") } },
        )
    }

    // 群目标：草稿文本 + 控制操作
    if (showGoalDialog) {
        AlertDialog(
            onDismissRequest = { showGoalDialog = false },
            title = { Text("群目标") },
            text = {
                Column {
                    OutlinedTextField(
                        value = goalText,
                        onValueChange = { goalText = it },
                        placeholder = { Text("写下这个群要达成什么（≤2000 字）") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = {
                        if (goalText.isNotBlank()) controller.goalDraft(goalText.trim())
                        goalText = ""
                    }) { Text("生成草稿") }
                    TextButton(onClick = { controller.goalControl("confirm") }) { Text("确认草稿") }
                    TextButton(onClick = { controller.goalControl("advance") }) { Text("推进下一阶段") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showGoalDialog = false }) { Text("关闭") } },
        )
    }

    // 引用聊天：选择一个 Bot 聊天作为上下文（以引用标注随消息发送）
    if (showRefPicker && members.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showRefPicker = false },
            title = { Text("引用一个聊天") },
            text = {
                Column {
                    members.forEach { name ->
                        TextButton(onClick = {
                            input = (if (input.isBlank()) "" else input + " ") + "（引用了 $name 的聊天）"
                            showRefPicker = false
                        }) { Text(name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showRefPicker = false }) { Text("取消") } },
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
    onCopyBlock: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 320.dp).clickable(onClick = onCopy),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (!fromUser && speakerName != null) {
                    Text(
                        speakerName,
                        style = MaterialTheme.typography.labelSmall,
                        color = speakerColorKey?.let { speakerColor(it) } ?: MaterialTheme.colorScheme.primary,
                    )
                }
                MarkdownText(markdown, onCopyBlock = onCopyBlock, onOpenLink = onOpenLink)
            }
        }
    }
}

/** 成员名颜色（与团队页 colorOf 同款映射的群聊内副本；未知色键与 null 走默认蓝/主题色）。 */
private fun speakerColor(name: String): androidx.compose.ui.graphics.Color = when (name) {
    "blue" -> androidx.compose.ui.graphics.Color(0xFF1E88E5)
    "green" -> androidx.compose.ui.graphics.Color(0xFF43A047)
    "violet" -> androidx.compose.ui.graphics.Color(0xFF8E24AA)
    "orange" -> androidx.compose.ui.graphics.Color(0xFFFB8C00)
    "red" -> androidx.compose.ui.graphics.Color(0xFFE53935)
    else -> androidx.compose.ui.graphics.Color(0xFF1E88E5)
}
