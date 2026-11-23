package work.keepwriting.dshanywhere

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import work.keepwriting.core.ChatRender
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.FileDownload
import work.keepwriting.core.FileText
import work.keepwriting.core.Json
import work.keepwriting.core.JsonString
import work.keepwriting.core.MarkdownImage
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 聊天里的文件与图片取回（dsbot-reader-files）：Bot 交付文件经 file-download 分块下载到
 * cache/downloads/<随机目录>/（块经 core FileDownload.Assembler 校验后追加写盘，不在内存里累积）、
 * 「阅读全文」（群长帖 file-text、文本类交付文件下载后 UTF-8 解码）、图片 image（preview 与原图）。
 *
 * [readApi] 在 IO 线程请求、回调投回状态 executor；本类状态只在 executor 上写（与控制器同约定）。
 * 下载与图片内容不写日志与诊断。
 */
class ChatMedia(
    private val readApi: (endpoint: String, payloadJson: String, onResult: (DsbotApi.Result) -> Unit) -> Unit,
    private val execute: (Runnable) -> Unit,
    private val cacheRoot: File,
    /** hello 是否声明 image-preview（未声明不请求预览，直接取原图）。 */
    private val canPreview: () -> Boolean,
    /** 分流拨号（Markdown 里的 https 图片经它直连取回，dsbot-rich-render）；null 时不取外链图片。 */
    private val dial: ((String, Int) -> java.net.Socket)? = null,
) {
    /** 当前下载进度（同一时刻一个下载）；key 区分是哪个文件条目。 */
    data class Progress(val key: String, val name: String, val received: Long, val total: Long)

    var progress by mutableStateOf<Progress?>(null)
        private set

    /** 下载代次：新下载或取消即递增，旧代次的回调丢弃并清理其目录。只在状态 executor 上读写。 */
    private var generation = 0

    /** 解码与写盘的 IO 线程：不占状态 executor（只把进度与结果投回）。 */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "chat-media-io").apply { isDaemon = true } }

    fun keyOf(f: ChatRender.Item.PresentedFile): String = "${f.sessionId}#${f.seq}#${f.index}"

    /**
     * 下载交付文件到缓存；完成回调 (文件, null)，失败回调 (null, 可读原因)；取消不回调。executor 内回调。
     * [stopAfter]：收到这么多字节即停止请求后续块并当作完成（「阅读全文」只需要开头）。
     */
    fun download(f: ChatRender.Item.PresentedFile, stopAfter: Long = Long.MAX_VALUE, onDone: (File?, String?) -> Unit) {
        execute(Runnable {
            val gen = ++generation
            val dir = File(cacheRoot, "$DOWNLOADS/${UUID.randomUUID()}")
            val asm = FileDownload.Assembler(MAX_BYTES)
            var target: File? = null
            var out: FileOutputStream? = null
            val key = keyOf(f)
            progress = Progress(key, f.name, 0, -1)

            fun abandon() {
                runCatching { out?.close() }
                dir.deleteRecursively()
            }

            fun fail(reason: String) {
                abandon()
                if (generation == gen) progress = null
                onDone(null, reason)
            }

            fun request(offset: Long, version: String?) {
                readApi("file-download", FileDownload.presented(f.sessionId, f.seq, f.index, offset, version)) { res ->
                    if (generation != gen) {
                        abandon()
                        return@readApi
                    }
                    when (res) {
                        is DsbotApi.Result.Ok -> io.execute {
                            // base64 解码与写盘在 IO 线程，结果投回状态 executor 推进
                            val chunk = FileDownload.decode(res.value)
                            var wrote = false
                            if (chunk != null) {
                                wrote = try {
                                    if (out == null) {
                                        dir.mkdirs()
                                        target = File(dir, FileDownload.safeName(chunk.name.ifEmpty { f.name }))
                                        out = FileOutputStream(target!!)
                                    }
                                    out!!.write(chunk.bytes)
                                    true
                                } catch (_: Exception) {
                                    false
                                }
                            }
                            execute(Runnable {
                                if (generation != gen) return@Runnable abandon()
                                if (chunk == null) return@Runnable fail(tr("文件内容无法解析", "The file could not be read"))
                                when (val step = asm.accept(chunk)) {
                                    is FileDownload.Assembler.Step.Fail -> fail(reasonOf(step.reason))
                                    else -> {
                                        if (!wrote) return@Runnable fail(tr("写入缓存失败（空间不足？）", "Could not write to cache (out of space?)"))
                                        progress = Progress(key, f.name, asm.offset, asm.size)
                                        if (step is FileDownload.Assembler.Step.Next && asm.offset < stopAfter) {
                                            request(step.offset, step.version)
                                        } else {
                                            runCatching { out!!.close() }
                                            progress = null
                                            onDone(target, null)
                                        }
                                    }
                                }
                            })
                        }
                        is DsbotApi.Result.BotError -> fail(
                            if (res.code == "bot/not-found") tr("文件已变更或不可用", "The file changed or is no longer available")
                            else tr("下载失败：${res.code}", "Download failed: ${res.code}"),
                        )
                        is DsbotApi.Result.Failure -> fail(tr("下载失败（网络）", "Download failed (network)"))
                    }
                }
            }
            request(0, null)
        })
    }

    /** 取消当前下载（主线程或 executor 调用均可）：已收的部分随目录删除。 */
    fun cancel() {
        execute(Runnable {
            generation++
            progress = null
        })
    }

    /**
     * 文本类交付文件「阅读全文」：只下载开头（够 [READ_MAX] 即停）并在 IO 线程按 UTF-8 解码，截断处退回
     * 字符边界；回调 (文本, 是否截断, 错误)，executor 内回调。
     */
    fun readText(f: ChatRender.Item.PresentedFile, onResult: (String?, Boolean, String?) -> Unit) {
        download(f, stopAfter = READ_MAX + 1L) { file, error ->
            if (file == null) return@download onResult(null, false, error)
            io.execute {
                val decoded = try {
                    file.inputStream().use { input ->
                        val buf = ByteArray(READ_MAX + 1)
                        var n = 0
                        while (n < buf.size) {
                            val r = input.read(buf, n, buf.size - n)
                            if (r < 0) break
                            n += r
                        }
                        val cut = n > READ_MAX
                        String(buf, 0, if (cut) utf8Boundary(buf, READ_MAX) else n, Charsets.UTF_8) to cut
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    file.parentFile?.deleteRecursively()
                }
                execute(Runnable {
                    if (decoded == null) onResult(null, false, tr("文件读取失败", "Could not read the file"))
                    else onResult(decoded.first, decoded.second, null)
                })
            }
        }
    }

    /** 群长帖「阅读全文」：file-text（0.3.10 源码核对，失败给可读提示）。 */
    fun fileText(fileJson: String, onResult: (FileText.Result?, String?) -> Unit) {
        execute(Runnable {
            readApi("file-text", FileText.request(fileJson)) { res ->
                when (res) {
                    is DsbotApi.Result.Ok -> FileText.decode(res.value)?.let { onResult(it, null) }
                        ?: onResult(null, tr("全文无法解析", "The full text could not be read"))
                    is DsbotApi.Result.BotError -> onResult(null, tr("取不到全文：${res.code}", "Full text unavailable: ${res.code}"))
                    is DsbotApi.Result.Failure -> onResult(null, tr("取不到全文（网络）", "Full text unavailable (network)"))
                }
            }
        })
    }

    /**
     * 图片字节：image {sessionId, attachmentId, preview?}。[preview] 为 true 且远端未声明 image-preview 时
     * 直接回调 null（调用方随后取原图）。executor 内回调。
     */
    fun image(sessionId: String, attachmentRefJson: String, preview: Boolean, onResult: (ByteArray?) -> Unit) {
        execute(Runnable {
            if (preview && !canPreview()) return@Runnable onResult(null)
            val id = ((Json.parse(attachmentRefJson) as? Json.Value.Object)?.members?.get("attachmentId") as? Json.Value.StringValue)?.value
                ?: return@Runnable onResult(null)
            val payload = "{\"sessionId\":${JsonString.of(sessionId)},\"attachmentId\":${JsonString.of(id)}${if (preview) ",\"preview\":true" else ""}}"
            readApi("image", payload) { res ->
                val data = ((res as? DsbotApi.Result.Ok)?.value as? Json.Value.Object)?.members?.get("data") as? Json.Value.StringValue
                onResult(data?.value?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() })
            }
        })
    }

    // ---- Markdown 图片（dsbot-rich-render） ----

    private class CachedImage(val bytes: ByteArray, val rev: String?, val at: Long)

    /** 取图线程池（外链与本地图片解码）：与文件下载的 io 线程分开，慢图床不卡下载。 */
    private val imagePool = java.util.concurrent.Executors.newFixedThreadPool(2) { r -> Thread(r, "md-image").apply { isDaemon = true } }
    private val deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "md-image-deadline").apply { isDaemon = true } }

    /** 内存 LRU：总字节 ≤ 32 MB 且最多 16 张；本地图片 5 分钟内直接复用，过期带 knownRev 复验（未变不重传）。 */
    private val images = LinkedHashMap<String, CachedImage>(16, 0.75f, true)
    private var imageBytes = 0L

    /** 进行中的请求：同一张图的并发请求合并回调。 */
    private val inFlight = HashMap<String, MutableList<(ByteArray?) -> Unit>>()

    private fun cacheGet(key: String): CachedImage? = synchronized(images) { images[key] }

    private fun cachePut(key: String, img: CachedImage) = synchronized(images) {
        images.remove(key)?.let { imageBytes -= it.bytes.size }
        images[key] = img
        imageBytes += img.bytes.size
        val it = images.entries.iterator()
        while ((imageBytes > IMAGE_BUDGET || images.size > IMAGES_KEPT) && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            imageBytes -= e.value.bytes.size
            it.remove()
        }
    }

    /** 登记请求；已有同键请求在途时只追加回调并返回 false。 */
    private fun join(key: String, onResult: (ByteArray?) -> Unit): Boolean = synchronized(inFlight) {
        val waiting = inFlight[key]
        if (waiting != null) { waiting.add(onResult); false } else { inFlight[key] = mutableListOf(onResult); true }
    }

    private fun settle(key: String, bytes: ByteArray?) {
        val waiting = synchronized(inFlight) { inFlight.remove(key) }.orEmpty()
        imagePool.execute { waiting.forEach { it(bytes) } }
    }

    /**
     * Markdown 图片字节：data: 直接解码；https 经分流拨号取回（不带任何 cookie、边读边限 8 MB、整体 30 秒、只跟随
     * https 重定向、最多 3 次、只收 image 类型）；其余视为服务器本地路径，经 markdown-image。失败回调 null。
     * 回调一律在后台线程（调用方解码位图后投回主线程）。图片内容不写日志。
     */
    fun markdownImage(sessionId: String?, src: String, onResult: (ByteArray?) -> Unit) {
        val now = System.currentTimeMillis()
        when {
            src.startsWith("data:", ignoreCase = true) -> imagePool.execute {
                onResult(MarkdownImage.dataUriBase64(src)?.let { b64 ->
                    runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()?.takeIf { it.size <= IMAGE_MAX }
                })
            }
            src.startsWith("https://", ignoreCase = true) -> {
                cacheGet(src)?.let { hit -> return imagePool.execute { onResult(hit.bytes) } }
                val d = dial ?: return imagePool.execute { onResult(null) }
                if (!join(src, onResult)) return
                imagePool.execute {
                    val bytes = runCatching { fetchHttps(d, src) }.getOrNull()
                    if (bytes != null) cachePut(src, CachedImage(bytes, null, now))
                    settle(src, bytes)
                }
            }
            src.startsWith("http://", ignoreCase = true) || sessionId == null -> imagePool.execute { onResult(null) }
            else -> {
                val key = "$sessionId\n$src"
                val cached = cacheGet(key)
                if (cached != null && now - cached.at < IMAGE_FRESH_MS) return imagePool.execute { onResult(cached.bytes) }
                if (!join(key, onResult)) return
                readApi("markdown-image", MarkdownImage.request(sessionId, src, cached?.rev)) { res ->
                    // 应答回到状态 executor：解码挪到取图线程，不占状态线程
                    imagePool.execute {
                        val reply = (res as? DsbotApi.Result.Ok)?.value?.let(MarkdownImage::decode)
                        val bytes = when {
                            reply == null -> null
                            reply.unchanged -> cached?.bytes
                            else -> reply.base64?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() }
                        }?.takeIf { it.size <= IMAGE_MAX }
                        if (bytes != null) cachePut(key, CachedImage(bytes, reply?.rev, System.currentTimeMillis()))
                        settle(key, bytes)
                    }
                }
            }
        }
    }

    private fun fetchHttps(d: (String, Int) -> java.net.Socket, start: String): ByteArray? {
        // 整体期限：到点关掉当前连接（单次读超时之外，防慢速滴灌）
        var current: java.net.Socket? = null
        val timer = deadlines.schedule({ runCatching { current?.close() } }, IMAGE_DEADLINE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        val tracked: (String, Int) -> java.net.Socket = { h, p -> d(h, p).also { current = it } }
        try {
            var url = start
            repeat(4) {
                val res = work.keepwriting.core.HttpSocket(tracked, connectTimeoutMs = 10_000, readTimeoutMs = 10_000)
                    .request("GET", url, listOf("Accept" to "image/*", "User-Agent" to "DSH-Anywhere"), maxBody = IMAGE_MAX)
                when (res.status) {
                    in 300..399 -> {
                        val next = res.header("Location")?.let { java.net.URI(url).resolve(it).toString() } ?: return null
                        if (!next.startsWith("https://", ignoreCase = true)) return null
                        url = next
                    }
                    200 -> {
                        val type = res.header("Content-Type")?.lowercase().orEmpty()
                        return res.body.takeIf { type.startsWith("image/") }
                    }
                    else -> return null
                }
            }
            return null
        } finally {
            timer.cancel(false)
        }
    }

    private fun reasonOf(f: FileDownload.Assembler.Failure): String = when (f) {
        FileDownload.Assembler.Failure.VERSION_CHANGED -> tr("文件在下载中途变了，请重新下载", "The file changed during download, try again")
        FileDownload.Assembler.Failure.TOO_LARGE -> tr("文件太大，无法下载到手机", "The file is too large to download here")
        FileDownload.Assembler.Failure.OUT_OF_ORDER, FileDownload.Assembler.Failure.MALFORMED -> tr("下载的数据不完整", "The downloaded data was incomplete")
    }

    companion object {
        private const val DOWNLOADS = "downloads"
        private const val IMAGES_KEPT = 16
        private const val IMAGE_FRESH_MS = 5 * 60 * 1000L
        private const val IMAGE_MAX = 8 * 1024 * 1024
        private const val IMAGE_BUDGET = 32L * 1024 * 1024
        private const val IMAGE_DEADLINE_MS = 30_000L

        /** 阅读器显示上限：单个 Text 一次性排版，过长会卡主线程。 */
        const val READ_MAX = 512 * 1024

        /** 截断位置退回 UTF-8 字符边界（不把多字节字符切成半个）。 */
        fun utf8Boundary(buf: ByteArray, end: Int): Int {
            var i = end
            while (i > 0 && end - i < 4 && (buf[i].toInt() and 0xC0) == 0x80) i--
            return i
        }
        private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
        private const val KEEP_MS = 24L * 3600 * 1000

        /** 启动时清理 24 小时前的下载目录（分享出去的文件在此期间仍可被接收方读取）。 */
        fun cleanup(cacheRoot: File, now: Long = System.currentTimeMillis()) {
            File(cacheRoot, DOWNLOADS).listFiles()?.forEach { dir ->
                if (now - dir.lastModified() > KEEP_MS) dir.deleteRecursively()
            }
        }
    }
}
