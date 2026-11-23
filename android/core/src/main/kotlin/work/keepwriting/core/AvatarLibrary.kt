package work.keepwriting.core

/**
 * 头像库（dsbot-avatars）：与服务端一致的图片格式与大小判定、data URL 编解码、头像库分页与导入结果解码、
 * 方形裁剪区域计算与请求体。上限与魔数按 ds-bot 0.3.10 avatar.js（MAX_BYTES=(160000-23)/4*3）源码核对，
 * 端点形态见 tech/dsbot-api 的 avatar-library-endpoints-0310。图片内容只经 API 传输，这里不记日志。
 */
object AvatarLibrary {
    /** 服务端 data URL 上限（constants.js MAX_AVATAR_IMAGE）。 */
    const val MAX_URL = 160_000

    /** 图片字节上限：(MAX_URL - "data:image/jpeg;base64,".length) / 4 * 3。 */
    const val MAX_BYTES = (MAX_URL - 23) / 4 * 3

    /** 推荐边长（上游提示 256×256 方形）。 */
    const val EDGE = 256

    enum class Problem { TOO_LARGE, NOT_IMAGE }

    sealed interface Encoded {
        data class Ok(val url: String, val mime: String) : Encoded
        data class Bad(val problem: Problem) : Encoded
    }

    data class Item(val id: String, val name: String, val mime: String?, val bytes: Long, val createdAt: Long, val image: String)

    data class Page(val items: List<Item>, val total: Int, val next: Int) {
        val hasMore: Boolean get() = next < total
    }

    data class Failed(val name: String, val message: String)

    data class ImportResult(val imported: List<String>, val duplicates: List<String>, val failed: List<Failed>)

    /** 方形裁剪区域（原图像素坐标）。 */
    data class Square(val left: Int, val top: Int, val size: Int)

    private fun ascii(b: ByteArray, from: Int, to: Int): String = String(b, from, to - from, Charsets.ISO_8859_1)

    /** 按魔数判定 PNG/JPEG/GIF/WebP；其他返回 null（与上游 mimeOf 一致）。 */
    fun mimeOf(b: ByteArray): String? = when {
        b.size >= 8 && ascii(b, 0, 8) == "\u0089PNG\r\n\u001a\n" -> "image/png"
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 6 && (ascii(b, 0, 6) == "GIF87a" || ascii(b, 0, 6) == "GIF89a") -> "image/gif"
        b.size >= 12 && ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 12) == "WEBP" -> "image/webp"
        else -> null
    }

    /** 编码为 data URL 并按服务端规则校验（先判大小再判格式，顺序同上游 toDataUrl）。 */
    fun encode(bytes: ByteArray): Encoded {
        if (bytes.size > MAX_BYTES) return Encoded.Bad(Problem.TOO_LARGE)
        val mime = mimeOf(bytes) ?: return Encoded.Bad(Problem.NOT_IMAGE)
        val url = "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
        if (url.length > MAX_URL) return Encoded.Bad(Problem.TOO_LARGE)
        return Encoded.Ok(url, mime)
    }

    /** 解出 data URL 的图片字节（显示用）；不是 base64 图片 data URL 时返回 null。 */
    fun decode(url: String?): ByteArray? {
        if (url == null || !url.startsWith("data:image/")) return null
        val comma = url.indexOf(',')
        if (comma < 0 || !url.substring(0, comma).contains(";base64")) return null
        return runCatching { java.util.Base64.getDecoder().decode(url.substring(comma + 1).trim()) }.getOrNull()
    }

    fun problemText(p: Problem): String = when (p) {
        Problem.TOO_LARGE -> I18n.tr("图片压缩后仍超过服务端上限（约 120 KB），无法导入", "The image is still over the server limit (about 120 KB) after compression")
        Problem.NOT_IMAGE -> I18n.tr("无法读取这张图片（只支持 PNG、JPEG、WebP、GIF）", "This image cannot be read (PNG, JPEG, WebP or GIF only)")
    }

    // ---- 应答解码 ----

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.num(k: String) = (members[k] as? Json.Value.NumberValue)?.raw?.toDoubleOrNull()?.toLong()
    private fun Json.Value.Object.arr(k: String) = (members[k] as? Json.Value.Array)?.items.orEmpty()

    /** avatar-library 应答（实测空库 {items:[], total:0, next:offset}）；缺 id 或 image 的条目跳过。 */
    fun decodePage(value: Json.Value?): Page {
        val o = value as? Json.Value.Object ?: return Page(emptyList(), 0, 0)
        val items = o.arr("items").mapNotNull { e ->
            val eo = e as? Json.Value.Object ?: return@mapNotNull null
            val id = eo.str("id") ?: return@mapNotNull null
            val image = eo.str("image") ?: return@mapNotNull null
            Item(id, eo.str("name") ?: "", eo.str("mime"), eo.num("bytes") ?: 0, eo.num("createdAt") ?: 0, image)
        }
        val next = o.num("next")?.toInt() ?: items.size
        return Page(items, o.num("total")?.toInt() ?: items.size, next)
    }

    /** avatar-import 应答 {imported[], duplicates[], failed[{name,message}]}（源码核对）。 */
    fun decodeImport(value: Json.Value?): ImportResult {
        val o = value as? Json.Value.Object ?: return ImportResult(emptyList(), emptyList(), emptyList())
        fun names(k: String) = o.arr(k).mapNotNull { (it as? Json.Value.Object)?.let { e -> e.str("name") ?: e.str("id") } }
        val failed = o.arr("failed").mapNotNull { f ->
            val fo = f as? Json.Value.Object ?: return@mapNotNull null
            Failed(fo.str("name") ?: "", fo.str("message")?.takeIf { it.isNotBlank() } ?: I18n.tr("导入失败", "Import failed"))
        }
        return ImportResult(names("imported"), names("duplicates"), failed)
    }

    /** 导入结果摘要（界面一行）。 */
    fun importSummary(r: ImportResult): String = I18n.tr(
        "新增 ${r.imported.size} 张，重复 ${r.duplicates.size} 张，失败 ${r.failed.size} 张",
        "${r.imported.size} added, ${r.duplicates.size} already there, ${r.failed.size} failed",
    )

    // ---- 方形裁剪 ----

    /**
     * 方形裁剪区域：边长 = 短边 / zoom（zoom 夹在 1..4），中心按 [cx]/[cy]（0..1 的图内比例）放置并夹在图内。
     * 宽高无效时返回 null。
     */
    fun cropSquare(width: Int, height: Int, zoom: Float, cx: Float, cy: Float): Square? {
        if (width <= 0 || height <= 0) return null
        val z = zoom.coerceIn(1f, 4f)
        val size = maxOf(1, (minOf(width, height) / z).toInt())
        val left = (cx.coerceIn(0f, 1f) * width - size / 2f).toInt().coerceIn(0, width - size)
        val top = (cy.coerceIn(0f, 1f) * height - size / 2f).toInt().coerceIn(0, height - size)
        return Square(left, top, size)
    }

    // ---- 请求体 ----

    object Requests {
        fun library(offset: Int): String = "{\"offset\":$offset}"

        fun import(items: List<Pair<String, String>>): String =
            "{\"items\":[" + items.joinToString(",") { (name, url) -> "{\"name\":" + JsonString.of(name) + ",\"image\":" + JsonString.of(url) + "}" } + "]}"

        fun delete(id: String): String = "{\"id\":" + JsonString.of(id) + "}"
    }
}
