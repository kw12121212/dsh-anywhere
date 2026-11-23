package work.keepwriting.dshanywhere

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import work.keepwriting.core.ChatRender
import work.keepwriting.core.FileDownload
import java.io.File

/** 长按气泡的底部菜单（dsbot-reader-files）：复制整条 / 选择文字（/ 阅读全文）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageMenuSheet(onDismiss: () -> Unit, onCopy: () -> Unit, onSelect: () -> Unit, onReadFull: (() -> Unit)? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            SheetAction(tr("复制整条", "Copy message")) { onDismiss(); onCopy() }
            SheetAction(tr("选择文字", "Select text")) { onDismiss(); onSelect() }
            if (onReadFull != null) SheetAction(tr("阅读全文", "Read full text")) { onDismiss(); onReadFull() }
        }
    }
}

/** 交付文件的操作菜单：打开 / 分享 /（文本类）阅读全文。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileActionSheet(name: String, onDismiss: () -> Unit, onOpen: () -> Unit, onShare: () -> Unit, onReadFull: (() -> Unit)?) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            SheetAction(tr("打开", "Open")) { onDismiss(); onOpen() }
            SheetAction(tr("分享", "Share")) { onDismiss(); onShare() }
            if (onReadFull != null) SheetAction(tr("阅读全文", "Read full text")) { onDismiss(); onReadFull() }
        }
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

/** 全屏可选阅读器：长文本整篇可滚动、可选择复制；[note] 说明截断等。 */
@Composable
fun ReaderDialog(text: String, note: String?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(tr("关闭", "Close")) }
                }
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                }
                SelectionContainer(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** 加载中的占位（阅读全文取回期间）：有下载进度时显示已收字节，可返回取消。 */
@Composable
fun LoadingDialog(label: String, progress: ChatMedia.Progress?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Row(modifier = Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(label)
                    if (progress != null && progress.received > 0) {
                        Text(sizeText(progress.received, progress.total), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** 交付文件条目：名称；下载中显示进度与取消。 */
@Composable
fun PresentedFileRow(item: ChatRender.Item.PresentedFile, progress: ChatMedia.Progress?, onClick: () -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            Text("📄 ${item.name}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Default, modifier = Modifier.padding(10.dp))
        }
        if (progress != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (progress.total > 0) {
                    LinearProgressIndicator(progress = { (progress.received.toFloat() / progress.total).coerceIn(0f, 1f) }, modifier = Modifier.weight(1f))
                } else {
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                }
                Text(sizeText(progress.received, progress.total), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
                TextButton(onClick = onCancel) { Text(tr("取消", "Cancel")) }
            }
        }
    }
}

private fun sizeText(received: Long, total: Long): String {
    fun mb(n: Long) = "%.1f MB".format(n / 1048576.0)
    return if (total > 0) "${mb(received)} / ${mb(total)}" else mb(received)
}

/**
 * 全屏图片查看器：同一聊天的图片左右滑动；每页先显示预览（[load] preview=true，可能为 null）再换原图。
 * 原图在内存里最多保留 [ORIGINALS_KEPT] 张（最久未看的先丢），预览较小、随查看器关闭释放。
 */
@Composable
fun ImageGalleryDialog(
    images: List<ChatRender.Item.Image>,
    start: Int,
    load: (image: ChatRender.Item.Image, preview: Boolean, onResult: (ByteArray?) -> Unit) -> Unit,
    onSave: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (images.size - 1).coerceAtLeast(0))) { images.size }
    val previews = remember { mutableStateMapOf<Int, ByteArray>() }
    val originals = remember { mutableStateMapOf<Int, ByteArray>() }
    val order = remember { ArrayDeque<Int>() }
    val failed = remember { mutableStateMapOf<Int, Boolean>() }
    // 取回回调在状态 executor 上：投回主线程再改查看器状态（order 不是线程安全的）
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Black) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${pager.currentPage + 1} / ${images.size}", color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    originals[pager.currentPage]?.let { bytes -> TextButton(onClick = { onSave(bytes) }) { Text(tr("保存到相册", "Save to Photos")) } }
                    TextButton(onClick = onDismiss) { Text(tr("关闭", "Close")) }
                }
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    LaunchedEffect(page) {
                        if (originals[page] != null) {
                            order.remove(page); order.addLast(page)
                            return@LaunchedEffect
                        }
                        val image = images[page]
                        load(image, true) { bytes -> main.post { if (bytes != null && originals[page] == null) previews[page] = bytes } }
                        load(image, false) { bytes ->
                            main.post {
                                if (bytes == null) {
                                    failed[page] = true
                                } else {
                                    originals[page] = bytes
                                    order.remove(page); order.addLast(page)
                                    // 超出上限淘汰最久未看的原图，但不淘汰正在看的那页（预览保留作退路）
                                    while (order.size > ORIGINALS_KEPT) {
                                        val victim = order.firstOrNull { it != pager.currentPage } ?: break
                                        order.remove(victim)
                                        originals.remove(victim)
                                    }
                                }
                            }
                        }
                    }
                    val bytes = originals[page] ?: previews[page]
                    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        when {
                            bitmap != null -> androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = images[page].name ?: tr("图片", "Image"),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                            failed[page] == true -> Text(tr("图片加载失败", "Could not load the image"), color = androidx.compose.ui.graphics.Color.White)
                            else -> CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

private const val ORIGINALS_KEPT = 3

/** 下载好的文件交出：分享面板或系统查看器（FileProvider URI + 只读授权）。 */
object FileShare {
    private fun uriOf(context: Context, file: File) =
        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    fun share(context: Context, file: File): Boolean = runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = AttachmentOpen.mimeOf(file.name)
            putExtra(Intent.EXTRA_STREAM, uriOf(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, tr("分享", "Share")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    fun open(context: Context, file: File): Boolean = runCatching {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriOf(context, file), AttachmentOpen.mimeOf(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(view)
    }.isSuccess

    /** 是否文本类（阅读全文入口）。 */
    fun isText(name: String): Boolean = FileDownload.isText(name)
}

/** 聊天页的查看层状态（dsbot-reader-files）：私聊与群聊共用，组合期间读取即重组。 */
class ChatMediaUi {
    var menuFor by mutableStateOf<ChatRender.Item.Message?>(null)
    var reader by mutableStateOf<Pair<String, String?>?>(null)
    var loading by mutableStateOf<String?>(null)
    var fileSheet by mutableStateOf<ChatRender.Item.PresentedFile?>(null)
    var gallery by mutableStateOf<Pair<List<ChatRender.Item.Image>, Int>?>(null)

    /** 「阅读全文」请求号：关闭加载框即作废，迟到的结果不再弹出阅读器。主线程读写。 */
    var readToken = 0

    /** 群长帖「阅读全文」：file-text 取回后进阅读器。 */
    fun readFull(media: ChatMedia, fileJson: String, onNotice: (String) -> Unit) =
        readWhole(this, media, fileJson, onNotice, android.os.Handler(android.os.Looper.getMainLooper()))
}

@Composable
fun rememberChatMediaUi(): ChatMediaUi = remember { ChatMediaUi() }

/**
 * 查看层：长按菜单、阅读器、加载占位、交付文件菜单与图片查看器。[sessionId] 为 image 端点的会话
 * （私聊当前显示的会话、群聊的群会话）；[readAttachment] 是旧形态附件取字节的兜底（session/attachment）。
 */
@Composable
fun ChatMediaOverlays(
    ui: ChatMediaUi,
    media: ChatMedia,
    sessionId: String?,
    onNotice: (String) -> Unit,
    readAttachment: (String, (ByteArray?) -> Unit) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    ui.menuFor?.let { m ->
        MessageMenuSheet(
            onDismiss = { ui.menuFor = null },
            onCopy = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.markdown))
                onNotice(tr("已复制", "Copied"))
            },
            onSelect = { ui.reader = m.markdown to null },
            onReadFull = m.wholeFileJson?.let { json -> { readWhole(ui, media, json, onNotice, main) } },
        )
    }
    ui.fileSheet?.let { f ->
        FileActionSheet(
            name = f.name,
            onDismiss = { ui.fileSheet = null },
            onOpen = {
                media.download(f) { file, error ->
                    main.post {
                        if (file == null) error?.let(onNotice)
                        else if (!FileShare.open(context, file)) onNotice(tr("没有应用能打开 ${f.name}", "No app can open ${f.name}"))
                    }
                }
            },
            onShare = {
                media.download(f) { file, error ->
                    main.post {
                        if (file == null) error?.let(onNotice)
                        else if (!FileShare.share(context, file)) onNotice(tr("无法分享 ${f.name}", "Could not share ${f.name}"))
                    }
                }
            },
            onReadFull = if (FileShare.isText(f.name)) {
                {
                    val token = ++ui.readToken
                    ui.loading = tr("正在取回全文…", "Fetching the full text…")
                    media.readText(f) { text, cut, error ->
                        main.post {
                            if (token != ui.readToken) return@post
                            ui.loading = null
                            if (text == null) error?.let(onNotice)
                            else ui.reader = text to (if (cut) tr("只显示了开头 512 KB", "Showing the first 512 KB only") else null)
                        }
                    }
                }
            } else null,
        )
    }
    ui.loading?.let { label ->
        LoadingDialog(label, media.progress) {
            ui.readToken++
            ui.loading = null
            media.cancel()
        }
    }
    ui.reader?.let { (text, note) -> ReaderDialog(text, note) { ui.reader = null } }
    ui.gallery?.let { (images, start) ->
        ImageGalleryDialog(
            images = images,
            start = start,
            load = { image, preview, cb ->
                val ref = image.attachmentRefJson
                if (ref == null || sessionId == null) {
                    if (!preview && ref != null) readAttachment(ref, cb) else cb(null)
                } else {
                    media.image(sessionId, ref, preview) { bytes ->
                        // 原图取不到（旧形态附件不在 image 端点）时退回 session/attachment
                        if (bytes == null && !preview) readAttachment(ref, cb) else cb(bytes)
                    }
                }
            },
            onSave = { bytes -> onNotice(if (saveToGallery(context, bytes)) tr("已保存到相册", "Saved to Photos") else tr("保存失败", "Save failed")) },
            onDismiss = { ui.gallery = null },
        )
    }
}

private fun readWhole(ui: ChatMediaUi, media: ChatMedia, fileJson: String, onNotice: (String) -> Unit, main: android.os.Handler) {
    val token = ++ui.readToken
    ui.loading = tr("正在取回全文…", "Fetching the full text…")
    media.fileText(fileJson) { result, error ->
        main.post {
            if (token != ui.readToken) return@post
            ui.loading = null
            if (result == null) error?.let(onNotice)
            else ui.reader = result.text to (if (!result.whole) tr("服务器只返回了开头部分", "The server returned only the beginning") else null)
        }
    }
}

/** 图片存入相册（API 29+ 无权限写共享存储：RELATIVE_PATH + IS_PENDING 提交）。 */
fun saveToGallery(context: Context, bytes: ByteArray): Boolean = runCatching {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "dsh-attachment-${System.currentTimeMillis()}.png")
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    context.contentResolver.openOutputStream(uri)?.use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
    values.clear()
    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
    context.contentResolver.update(uri, values, null, null)
    true
}.getOrDefault(false)
