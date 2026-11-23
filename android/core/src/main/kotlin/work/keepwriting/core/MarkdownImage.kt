package work.keepwriting.core

/**
 * Markdown 本地图片（dsbot-rich-render）：markdown-image 的请求体与应答解码（实测，FINDINGS 第 18 节，
 * 夹具 markdown-image-ok/unchanged.json）。成功 {mime, bytes(base64), size, rev}；带 knownRev 且未变时
 * {mime, unchanged:true, size, rev}（不回传 bytes）。
 */
object MarkdownImage {
    fun request(sessionId: String, path: String, knownRev: String?): String =
        "{\"sessionId\":${JsonString.of(sessionId)},\"path\":${JsonString.of(path)}" +
            (knownRev?.let { ",\"knownRev\":${JsonString.of(it)}" } ?: "") + "}"

    data class Reply(val mime: String?, val base64: String?, val size: Long?, val rev: String?, val unchanged: Boolean)

    /** 解码应答；既没有 bytes 也不是 unchanged 的为 null。 */
    fun decode(value: Json.Value?): Reply? {
        val o = value as? Json.Value.Object ?: return null
        val unchanged = (o.members["unchanged"] as? Json.Value.BoolValue)?.value == true
        val base64 = (o.members["bytes"] as? Json.Value.StringValue)?.value
        if (!unchanged && base64 == null) return null
        return Reply(
            mime = (o.members["mime"] as? Json.Value.StringValue)?.value,
            base64 = base64,
            size = (o.members["size"] as? Json.Value.NumberValue)?.raw?.toLongOrNull(),
            rev = (o.members["rev"] as? Json.Value.StringValue)?.value,
            unchanged = unchanged,
        )
    }

    /** data: 图片 URI 的 base64 部分（只认 image/ 类型且带 ;base64）；其余为 null。 */
    fun dataUriBase64(src: String): String? {
        if (!src.startsWith("data:", ignoreCase = true)) return null
        val comma = src.indexOf(',')
        if (comma < 0) return null
        val meta = src.substring(5, comma)
        if (!meta.startsWith("image/", ignoreCase = true) || !meta.contains(";base64", ignoreCase = true)) return null
        return src.substring(comma + 1)
    }
}
