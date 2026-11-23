package work.keepwriting.core

import java.util.Base64

/**
 * 文件分块下载（dsbot-reader-files）：DS-Bot 0.3.10 `file-download` 的请求体、分块解码与拼接校验。
 * 实测（FINDINGS 第 17 节，夹具 file-download-chunk-*.json）：Bot 交付的文件以 bot 条目 files[] 自带的
 * {sessionId, seq, index} 定位，每块最多 512 KiB，应答 {name, mime, bytes(base64), size, version,
 * nextOffset, done}；续块带上首块的 version，文件变了或已不可用回 bot/not-found。
 */
object FileDownload {
    const val CHUNK_BYTES = 512 * 1024

    /** Bot 交付文件（deliverables/presented）的分块请求。 */
    fun presented(sessionId: String, seq: Long, index: Int, offset: Long, version: String?): String = buildString {
        append("{\"sessionId\":").append(JsonString.of(sessionId))
        append(",\"seq\":").append(seq).append(",\"index\":").append(index)
        append(",\"offset\":").append(offset)
        if (version != null) append(",\"version\":").append(JsonString.of(version))
        append('}')
    }

    /** 文件附件（attachmentId）的分块请求；附件块以 attachmentId 为版本，无需回传。 */
    fun attachment(sessionId: String, attachmentId: String, offset: Long): String =
        "{\"sessionId\":${JsonString.of(sessionId)},\"attachmentId\":${JsonString.of(attachmentId)},\"offset\":$offset}"

    data class Chunk(
        val name: String,
        val mime: String?,
        val bytes: ByteArray,
        val size: Long,
        val version: String?,
        val nextOffset: Long,
        val done: Boolean,
    )

    /** 解码一块；字段缺失或 base64 非法为 null。 */
    fun decode(value: Json.Value?): Chunk? {
        val o = value as? Json.Value.Object ?: return null
        val bytes = try {
            Base64.getDecoder().decode((o.members["bytes"] as? Json.Value.StringValue)?.value ?: return null)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return Chunk(
            name = (o.members["name"] as? Json.Value.StringValue)?.value ?: "file",
            mime = (o.members["mime"] as? Json.Value.StringValue)?.value,
            bytes = bytes,
            size = num(o, "size") ?: return null,
            version = (o.members["version"] as? Json.Value.StringValue)?.value,
            nextOffset = num(o, "nextOffset") ?: return null,
            done = (o.members["done"] as? Json.Value.BoolValue)?.value ?: false,
        )
    }

    /**
     * 拼接校验：从 offset 0 起逐块接收，每块必须紧接上一块（解码长度 = nextOffset - 期望 offset）、
     * 总量与首块一致、version 与首块一致；超过 [maxBytes] 中止。通过的块由调用方写盘（不在内存里累积）。
     */
    class Assembler(private val maxBytes: Long = Long.MAX_VALUE) {
        enum class Failure { VERSION_CHANGED, OUT_OF_ORDER, TOO_LARGE, MALFORMED }

        sealed interface Step {
            /** 继续请求下一块。 */
            data class Next(val offset: Long, val version: String?) : Step

            object Done : Step

            data class Fail(val reason: Failure) : Step
        }

        var offset: Long = 0
            private set
        var size: Long = -1
            private set
        var version: String? = null
            private set
        var name: String? = null
            private set
        private var failed = false

        fun accept(chunk: Chunk): Step {
            if (failed) return Step.Fail(Failure.MALFORMED)
            val first = size < 0
            val step = when {
                chunk.size < 0 || chunk.nextOffset < offset -> Step.Fail(Failure.MALFORMED)
                !first && chunk.size != size -> Step.Fail(Failure.VERSION_CHANGED)
                !first && chunk.version != version -> Step.Fail(Failure.VERSION_CHANGED)
                chunk.bytes.size.toLong() != chunk.nextOffset - offset -> Step.Fail(Failure.OUT_OF_ORDER)
                chunk.size > maxBytes -> Step.Fail(Failure.TOO_LARGE)
                chunk.nextOffset > chunk.size -> Step.Fail(Failure.MALFORMED)
                chunk.bytes.isEmpty() && chunk.nextOffset < chunk.size -> Step.Fail(Failure.MALFORMED)
                // 声称完结却没到总量：不把不完整的文件当作完成交出去
                chunk.done && chunk.nextOffset != chunk.size -> Step.Fail(Failure.MALFORMED)
                else -> null
            }
            if (step != null) {
                failed = true
                return step
            }
            if (first) {
                size = chunk.size
                version = chunk.version
                name = chunk.name
            }
            offset = chunk.nextOffset
            return if (offset == size) Step.Done else Step.Next(offset, version)
        }
    }

    /**
     * 缓存文件名：只取最后一段（去掉 / 与 \ 前的路径）、去控制字符与 Windows 保留字符、去掉 ".." 片段，
     * 以 "." 开头的加前缀，限 120 字符且保留扩展名；空回落 "file"。
     */
    fun safeName(name: String?): String {
        val last = name.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        // 去控制字符、Windows 保留字符与双向控制符（U+202A..202E、U+2066..2069、U+200E/F：防伪装扩展名）
        var s = last.filter { it >= ' ' && it != '\u007f' && it !in "<>:\"|?*" && it !in BIDI }.replace("..", "_").trim()
        if (s.isEmpty() || s == "." || s == "_") return "file"
        if (s.startsWith('.')) s = "file$s"
        if (s.length > 120) {
            val ext = s.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 10 }
            s = if (ext != null) s.take(120 - ext.length - 1) + "." + ext else s.take(120)
        }
        return s
    }

    private val BIDI = setOf('\u200E', '\u200F', '\u202A', '\u202B', '\u202C', '\u202D', '\u202E', '\u2066', '\u2067', '\u2068', '\u2069')

    private val TEXT_EXT = setOf("md", "markdown", "txt", "log", "csv", "tsv", "json", "yaml", "yml", "xml", "html", "htm")

    /** 是否可作为文本在阅读器里显示（「阅读全文」入口）。 */
    fun isText(name: String?, mime: String? = null): Boolean =
        mime?.startsWith("text/") == true || name?.substringAfterLast('.', "")?.lowercase() in TEXT_EXT

    private fun num(o: Json.Value.Object, key: String): Long? =
        (o.members[key] as? Json.Value.NumberValue)?.raw?.toLongOrNull()
}

/** 「阅读全文」的全文文件（dsbot-reader-files）：file-text {file} → {text, whole}（0.3.10 源码核对，待实测）。 */
object FileText {
    data class Result(val text: String, val whole: Boolean)

    fun request(fileJson: String): String = "{\"file\":$fileJson}"

    fun decode(value: Json.Value?): Result? {
        val o = value as? Json.Value.Object ?: return null
        val text = (o.members["text"] as? Json.Value.StringValue)?.value ?: return null
        return Result(text, (o.members["whole"] as? Json.Value.BoolValue)?.value ?: true)
    }
}
