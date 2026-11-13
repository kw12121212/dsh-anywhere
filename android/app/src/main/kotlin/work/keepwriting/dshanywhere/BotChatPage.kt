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
    onOpenLink: (String, Boolean) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var showPartsDialog by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var viewingImage by remember { mutableStateOf<ByteArray?>(null) }
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
                controller.postNotice("已保存")
            }.onFailure { controller.postNotice("保存失败") }
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
            if (controller.rows.isEmpty()) {
                Text(
                    "还没有消息，发送第一条开始对话",
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
                            ) { Text(if (controller.loadingEarlier) "加载中…" else "更早的消息") }
                        }
                    } else {
                        item(key = "earliest") {
                            Text(
                                "已到最早",
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
                                onCopyBlock = { clipboard.setText(AnnotatedString(it)) },
                                onOpenLink = onOpenLink,
                            )
                            is ChatRender.Item.ToolCard -> ToolCardRow(item)
                            is ChatRender.Item.Image -> ImageRow(item, onOpen = {
                                val refJson = item.attachmentRefJson
                                if (refJson != null) {
                                    controller.readAttachment(refJson) { bytes -> viewingImage = bytes }
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
                                Text("📎 ${item.name ?: "附件文件"}", style = MaterialTheme.typography.bodyMedium)
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
                                                item.id?.let { id ->
                                                    controller.answerCard("btw-delete", work.keepwriting.core.SideChat.btwDelete(id)) { ok ->
                                                        if (ok) deleted = true
                                                    }
                                                }
                                            }) { Text("删除") }
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
                }
                // 流式期间自动滚动到底；更早记录前插（rows.size 也变）不滚动，key 保持视口
                val streaming by remember { derivedStateOf { controller.streamingText != null } }
                LaunchedEffect(controller.rows.size, streaming) {
                    if (controller.rows.isNotEmpty() && streaming) listState.animateScrollToItem(controller.rows.size)
                }
            }
        }
        if (controller.streamingText != null) {
            StreamingBubble(controller.streamingText ?: "")
        }
        ChatActionRow(controller, parts, onPickPart = { showPartsDialog = true }, onOpenSide = onOpenSide)
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
        // 发送/停止/恢复合一的图标按钮（现代聊天输入区形态）：Bot 工作中=停止、
        // 会话被中断=恢复、其余=发送；发送中（busy）以思考条占位。
        if (controller.working || controller.busy || controller.streamingText != null) {
            ThinkingPill(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { showAttachMenu = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加附件")
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("发送消息给 Bot") },
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            val attachmentsReady = controller.attachments.all { it.status == "ready" }
            when {
                controller.working -> FilledIconButton(onClick = { controller.stop() }) {
                    // 方形停止符（icons-core 无 Stop，自绘 18dp 圆角方块）
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(MaterialTheme.colorScheme.onPrimaryContainer, RoundedCornerShape(4.dp)),
                    )
                }
                controller.interrupted -> FilledIconButton(onClick = { controller.resume() }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "恢复会话")
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
                    Icon(Icons.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }

    // 统计 / Bot 间记录：人话摘要弹窗（core 的 BotStatsText 翻译，可滚动可复制）
    controller.statsText?.let { body ->
        AlertDialog(
            onDismissRequest = { controller.dismissStats() },
            title = { Text("统计") },
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
            confirmButton = { TextButton(onClick = { controller.dismissStats() }) { Text("关闭") } },
        )
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
                        val uri = CameraOutput.newUri(context)
                        cameraUri = uri
                        if (uri != null) takePicture.launch(uri)
                    }) { Text("拍照") }
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

    if (showPartsDialog && parts.size > 1) {
        AlertDialog(
            onDismissRequest = { showPartsDialog = false },
            title = { Text("切换分段") },
            text = {
                Column {
                    parts.forEachIndexed { i, _ ->
                        TextButton(
                            onClick = {
                                showPartsDialog = false
                                controller.openPart(i, parts[i], parts.size)
                            },
                        ) { Text("分段 ${i + 1}${if (i == parts.lastIndex) "（当前）" else ""}") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPartsDialog = false }) { Text("取消") }
            },
        )
    }

    // 图片附件查看（readAttachment 字节 → 位图）+ 保存到相册
    viewingImage?.let { bytes ->
        val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        Dialog(onDismissRequest = { viewingImage = null }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "附件图片",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("无法解码图片（${bytes.size} 字节）")
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            val saved = runCatching {
                                // API 29+ 无权限写共享存储：RELATIVE_PATH + IS_PENDING 提交
                                val values = android.content.ContentValues().apply {
                                    put(MediaStore.Images.Media.DISPLAY_NAME, "dsh-attachment-${System.currentTimeMillis()}.png")
                                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                    put(MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
                                    put(MediaStore.Images.Media.IS_PENDING, 1)
                                }
                                val uri = context.contentResolver.insert(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
                                )
                                val bmp = bitmap
                                if (uri != null && bmp != null) {
                                    context.contentResolver.openOutputStream(uri)?.use { out ->
                                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                    values.clear()
                                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                                    context.contentResolver.update(uri, values, null, null)
                                    true
                                } else {
                                    false
                                }
                            }.recover { e ->
                                android.util.Log.e("DSBOT-ATT", "save failed", e)
                                false
                            }.getOrDefault(false)
                            controller.postNotice(if (saved) "已保存到相册" else "保存失败")
                            viewingImage = null
                        }) { Text("保存到相册") }
                        TextButton(onClick = { viewingImage = null }) { Text("关闭") }
                    }
                }
            }
        }
    }

    // 文件附件查看（dsbot-attachments）：字节写 cache 临时文件交系统查看器；保存经系统文件保存器
    viewingFile?.let { (name, bytes) ->
        Dialog(onDismissRequest = { viewingFile = null }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("文件：$name（${bytes.size} 字节）", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            AttachmentOpen.openWith(context, name, bytes)
                            viewingFile = null
                        }) { Text("用其他应用打开") }
                        TextButton(onClick = {
                            pendingSaveBytes = bytes
                            saveFile.launch(name)
                            viewingFile = null
                        }) { Text("保存") }
                        TextButton(onClick = { viewingFile = null }) { Text("关闭") }
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
    onCopyBlock: (String) -> Unit,
    onOpenLink: (String, Boolean) -> Unit,
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
                "工具调用：${item.name ?: "未知"}",
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
        Text("🖼️ ${item.name ?: item.ref ?: "图片"}", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChatActionRow(controller: ChatController, parts: List<String>, onPickPart: () -> Unit, onOpenSide: () -> Unit = {}) {
    // 停止/恢复已并入输入区图标按钮；此处只保留会话操作。
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = { controller.newPart() }) { Text("新分段") }
        if (parts.size > 1) {
            TextButton(onClick = onPickPart) { Text("旧分段") }
        }
        TextButton(onClick = onOpenSide) { Text("侧问") }
        TextButton(onClick = { controller.showStats() }) { Text("统计") }
    }
}
