package work.keepwriting.dshanywhere

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import work.keepwriting.core.AvatarLibrary
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.ImageSizing
import java.io.ByteArrayOutputStream

/** 头像图片处理（dsbot-avatars）：裁剪为方形、缩放到 256×256、JPEG 逐级压缩直到通过服务端上限校验。 */
object AvatarImage {
    /** 裁剪用的位图长边上限（够裁出 256×256，又不占太多内存）。 */
    const val WORK_EDGE = 1024

    fun encode(src: Bitmap, square: AvatarLibrary.Square): AvatarLibrary.Encoded {
        val cropped = Bitmap.createBitmap(src, square.left, square.top, square.size, square.size)
        val scaled = Bitmap.createScaledBitmap(cropped, AvatarLibrary.EDGE, AvatarLibrary.EDGE, true)
        if (cropped !== src && cropped !== scaled) cropped.recycle()
        var last: AvatarLibrary.Encoded = AvatarLibrary.Encoded.Bad(AvatarLibrary.Problem.TOO_LARGE)
        for (quality in intArrayOf(85, 70, 55, 40)) {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            last = AvatarLibrary.encode(out.toByteArray())
            if (last is AvatarLibrary.Encoded.Ok) break
        }
        // 源图仍在裁剪框里显示：不回收它本身
        if (scaled !== src) scaled.recycle()
        return last
    }

    /**
     * data URL 缩略图解码：先取尺寸再按 [edge] 采样解码——上游只限字节数不限像素，网页端设的大尺寸低压缩率
     * 图片按原尺寸解码会占用数十 MB。在 IO 线程调用；SVG 等 BitmapFactory 不支持的格式返回 null。
     */
    fun thumbnail(url: String?, edge: Int = AvatarLibrary.EDGE): ImageBitmap? {
        val bytes = AvatarLibrary.decode(url) ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply { inSampleSize = ImageSizing.sampleSize(bounds.outWidth, bounds.outHeight, edge) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        }.getOrNull()
    }
}

/** 在 IO 线程解码 data URL 缩略图（无图或不支持的格式为 null）。 */
@Composable
fun rememberThumbnail(url: String?): ImageBitmap? =
    produceState<ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.IO) { AvatarImage.thumbnail(url) }
    }.value

/** 待导入的一张照片：URI 在轮到它时才解码；[temp] 为相机临时文件，读完即删。 */
private class Pending(val uri: Uri, val temp: Boolean)

/** 正在裁剪的照片（已解码）。 */
private class Loaded(val pending: Pending, val name: String, val bitmap: Bitmap)

/**
 * 头像库页（dsbot-avatars），从 Bot 详情页进入：分页网格、相册多选与相机导入（逐张方形裁剪后一次导入）、
 * 长按删除（二次确认）、点选即设为该 Bot 的头像并返回。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AvatarLibraryPage(botId: String, botShape: String?, api: ManageApi, onDone: () -> Unit) {
    val context = LocalContext.current
    val items = remember { mutableStateListOf<AvatarLibrary.Item>() }
    var total by remember { mutableStateOf(0) }
    var next by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    // 待裁剪的照片：整批选好后一次入队（只存 URI），逐张解码裁剪，全部处理完才导入
    val queue = remember { mutableStateListOf<Pending>() }
    var current by remember { mutableStateOf<Loaded?>(null) }
    val ready = remember { mutableStateListOf<Pair<String, String>>() }
    val local = remember { mutableStateListOf<String>() }
    var deleting by remember { mutableStateOf<AvatarLibrary.Item?>(null) }
    // 列表代次：每次从头刷新加一，过期应答丢弃（加载更多进行中也能发起刷新）
    var generation by remember { mutableStateOf(0) }
    // 与详情页「移除照片」同键（act 拼成 "$botId:photo"），二者互斥
    val photoKey = "$botId:photo"
    fun named(name: String, text: String) = name + tr("：", ": ") + text

    fun load(offset: Int) {
        if (offset == 0) generation++
        val gen = generation
        loading = true
        api.read("avatar-library", AvatarLibrary.Requests.library(offset)) { r ->
            if (gen != generation) return@read
            loading = false
            when (r) {
                is DsbotApi.Result.Ok -> {
                    val page = AvatarLibrary.decodePage(r.value)
                    if (offset == 0) items.clear()
                    items.addAll(page.items.filter { n -> items.none { it.id == n.id } })
                    total = page.total
                    next = page.next
                }
                is DsbotApi.Result.BotError -> error = work.keepwriting.core.BotManage.errorText(r.code, r.message)
                is DsbotApi.Result.Failure -> error = work.keepwriting.core.BotManage.errorText(null, null)
            }
        }
    }
    LaunchedEffect(Unit) { load(0) }

    // 全部处理完后一次导入；上一批仍在导入时保留待导入项，等它结束后补发
    fun importReady() {
        if (ready.isEmpty() || "avatar-import" in api.inFlight) return
        val batch = ready.toList()
        ready.clear()
        error = null
        api.action("avatar-import", "avatar-import", AvatarLibrary.Requests.import(batch), { v ->
            val r = AvatarLibrary.decodeImport(v)
            info = AvatarLibrary.importSummary(r) + r.failed.joinToString("") { "\n" + named(it.name, it.message) }
            load(0)
            if (queue.isEmpty() && current == null) importReady()
        }) {
            error = it
            if (queue.isEmpty() && current == null) importReady()
        }
    }

    // 队首照片：在 IO 线程读字节并解码（相机临时文件读完即删），失败给本地提示后处理下一张
    val head = queue.firstOrNull()
    LaunchedEffect(head) {
        val p = head ?: return@LaunchedEffect
        val (name, bitmap) = withContext(Dispatchers.IO) {
            val display = runCatching {
                context.contentResolver.query(p.uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()?.substringBeforeLast('.')?.take(60)?.ifBlank { null } ?: "photo"
            val bmp = runCatching {
                val bytes = context.contentResolver.openInputStream(p.uri)?.use { it.readBytes() } ?: ByteArray(0)
                ImageCompress.loadOriented(bytes, AvatarImage.WORK_EDGE)
            }.getOrNull()
            if (p.temp) runCatching { context.contentResolver.delete(p.uri, null, null) }
            display to bmp
        }
        if (bitmap == null) {
            local += named(name, AvatarLibrary.problemText(AvatarLibrary.Problem.NOT_IMAGE))
            queue.remove(p)
            if (queue.isEmpty()) importReady()
        } else {
            current = Loaded(p, name, bitmap)
        }
    }

    // 系统选择器退回 OPEN_DOCUMENT 时不受 10 张上限约束：这里再截一次
    fun enqueue(uris: List<Uri>, temp: Boolean) {
        queue.addAll(uris.take(10).map { Pending(it, temp) })
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> if (uris.isNotEmpty()) enqueue(uris, temp = false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        cameraUri = null
        if (uri != null) {
            // 取消拍照留下的空文件同样删除：照片不在本机持久保存
            if (ok) enqueue(listOf(uri), temp = true) else runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(tr("头像库", "Avatar library"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        Text(
            tr("点选照片设为头像；长按可删除。导入的照片会裁成方形并压缩。", "Tap a photo to use it; long-press to delete. Imported photos are cropped square and compressed."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
        )
        val busy = "avatar-import" in api.inFlight || queue.isNotEmpty()
        Row(modifier = Modifier.padding(horizontal = 8.dp)) {
            TextButton(enabled = !busy, onClick = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(tr("从相册导入", "Import from photos")) }
            TextButton(enabled = !busy, onClick = {
                val uri = CameraOutput.newUri(context)
                if (uri != null) { cameraUri = uri; takePicture.launch(uri) }
            }) { Text(tr("拍照", "Take photo")) }
        }
        for (line in listOfNotNull(error, info) + local) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            }
        }
        if (error != null || info != null || local.isNotEmpty()) {
            TextButton(onClick = { error = null; info = null; local.clear() }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("清除提示", "Clear messages")) }
        }
        if (items.isEmpty() && !loading) {
            Text(tr("头像库还是空的", "The avatar library is empty"), modifier = Modifier.padding(16.dp))
        }
        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize().padding(8.dp)) {
            items(items, key = { it.id }) { item ->
                val bmp = rememberThumbnail(item.image)
                Box(
                    modifier = Modifier.padding(4.dp).aspectRatio(1f).combinedClickable(
                        onClick = {
                            if (photoKey !in api.inFlight) {
                                error = null
                                api.action(photoKey, "update-bot", work.keepwriting.core.BotManage.Requests.updatePhoto(botId, botShape, item.image), { onDone() }) { error = it }
                            }
                        },
                        onLongClick = { deleting = item },
                    ),
                ) {
                    if (bmp != null) Image(bmp, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Text(item.name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.Center))
                }
            }
            if (next < total) {
                item(span = { GridItemSpan(3) }) {
                    TextButton(enabled = !loading, onClick = { load(next) }) { Text(tr("加载更多（${items.size}/$total）", "Load more (${items.size}/$total)")) }
                }
            }
        }
    }

    current?.let { c ->
        CropDialog(
            name = c.name,
            bitmap = c.bitmap,
            onDone = { square ->
                if (square != null) {
                    when (val enc = AvatarImage.encode(c.bitmap, square)) {
                        is AvatarLibrary.Encoded.Ok -> ready += c.name to enc.url
                        is AvatarLibrary.Encoded.Bad -> local += named(c.name, AvatarLibrary.problemText(enc.problem))
                    }
                }
                current = null
                queue.remove(c.pending)
                if (queue.isEmpty()) importReady()
            },
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("请确认", "Please confirm")) },
            text = { Text(tr("从头像库删除这张照片？正在使用它的 Bot 会保留自己的头像。", "Delete this photo from the library? Bots using it keep their own copy.")) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    error = null
                    api.action("avatar-delete:${item.id}", "avatar-delete", AvatarLibrary.Requests.delete(item.id), { v ->
                        info = if ((v as? work.keepwriting.core.Json.Value.BoolValue)?.value == true) {
                            tr("已从头像库删除；正在使用它的 Bot 保留各自的头像", "Deleted from the library; Bots using it keep their own copy")
                        } else {
                            tr("这张照片已不在头像库中", "This photo is no longer in the library")
                        }
                        load(0)
                    }) { error = it }
                }) { Text(tr("删除", "Delete")) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("取消", "Cancel")) } },
        )
    }
}

/** 方形裁剪：预览即裁剪结果；拖动平移、滑杆缩放；中心总是回写为实际裁剪区域的中心（拖到边缘不积累死区）。onDone(null) 表示跳过这张。 */
@Composable
private fun CropDialog(name: String, bitmap: Bitmap, onDone: (AvatarLibrary.Square?) -> Unit) {
    var zoom by remember(bitmap) { mutableStateOf(1f) }
    var cx by remember(bitmap) { mutableStateOf(0.5f) }
    var cy by remember(bitmap) { mutableStateOf(0.5f) }
    val w = bitmap.width
    val h = bitmap.height
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val square = AvatarLibrary.cropSquare(w, h, zoom, cx, cy)
    fun settle(s: AvatarLibrary.Square?) {
        if (s == null) return
        cx = (s.left + s.size / 2f) / w
        cy = (s.top + s.size / 2f) / h
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(tr("裁剪头像", "Crop avatar")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = MaterialTheme.typography.labelSmall)
                Canvas(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(bitmap) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val s = AvatarLibrary.cropSquare(w, h, zoom, cx, cy) ?: return@detectDragGestures
                            // 拖动方向与取景内容一致：向右拖看到更左边的内容
                            val perPx = s.size.toFloat() / size.width
                            settle(AvatarLibrary.cropSquare(w, h, zoom, cx - drag.x * perPx / w, cy - drag.y * perPx / h))
                        }
                    },
                ) {
                    if (square != null) {
                        drawImage(
                            image,
                            srcOffset = IntOffset(square.left, square.top),
                            srcSize = IntSize(square.size, square.size),
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        )
                    }
                }
                Text(tr("缩放", "Zoom"), style = MaterialTheme.typography.labelSmall)
                Slider(value = zoom, onValueChange = { zoom = it; settle(AvatarLibrary.cropSquare(w, h, it, cx, cy)) }, valueRange = 1f..4f)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(square) }) { Text(tr("使用", "Use")) } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(tr("跳过", "Skip")) } },
    )
}
