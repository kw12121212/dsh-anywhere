package work.keepwriting.core

/**
 * 统计与 Bot 间记录的人话格式化（run-stats / exchange 应答 → 用户可读多行文本）。
 * 字段全部防御式读取：缺失即整行不显示，不抛错（version-baseline-and-degrade 的降级原则）。
 * 形态来源：2026-11-13 对真实 0.3.6 服务器实测（sessionStats/tokenUsage/contextPressure
 * 三段与 exchanges 空数组），由 BotStatsTextTest 以同一夹具锁定。
 */
object BotStatsText {

    /** run-stats 应答 → 多行摘要（轮数/步数、耗时、Token、上下文压力）。 */
    fun runStats(value: Json.Value): String {
        val obj = value as? Json.Value.Object ?: return ""
        val lines = mutableListOf<String>()

        val session = obj.members["sessionStats"] as? Json.Value.Object
        val turns = longOf(session, "turns")
        val steps = longOf(session, "steps")
        if (turns != null || steps != null) {
            lines += buildString {
                append("对话 ")
                if (turns != null) append("${turns} 轮")
                if (turns != null && steps != null) append(" · ")
                if (steps != null) append("${steps} 步")
            }
        }
        val llmMs = longOf(session, "llmMs")
        val toolMs = longOf(session, "toolMs")
        if (llmMs != null || toolMs != null) {
            lines += buildString {
                append("耗时 ")
                if (llmMs != null) append("模型 ${duration(llmMs)}")
                if (llmMs != null && toolMs != null) append(" · ")
                if (toolMs != null) append("工具 ${duration(toolMs)}")
            }
        }

        val usage = obj.members["tokenUsage"] as? Json.Value.Object
        val uncached = longOf(usage, "uncachedInputTokens")
        val output = longOf(usage, "outputTokens")
        val cacheRead = longOf(usage, "cacheReadTokens")
        if (uncached != null || output != null || cacheRead != null) {
            val parts = mutableListOf<String>()
            if (uncached != null) parts += "输入 ${count(uncached)}"
            if (output != null) parts += "输出 ${count(output)}"
            if (cacheRead != null) parts += "缓存读取 ${count(cacheRead)}"
            lines += "Token：" + parts.joinToString(" · ")
        }

        val pressure = obj.members["contextPressure"] as? Json.Value.Object
        val used = longOf(pressure, "pressureTokens")
        val window = longOf(pressure, "contextWindow")
        if (used != null && window != null && window > 0) {
            val pct = used * 100 / window
            lines += "上下文 ${count(used)} / ${count(window)}（${pct}%）"
        }
        return lines.joinToString("\n")
    }

    /** exchange 应答 → 多行记录；空数组给空态文案；条目抽取常见文本字段，抽不到退回单行 JSON。 */
    fun exchanges(value: Json.Value): String {
        val arr = value as? Json.Value.Array ?: return ""
        if (arr.items.isEmpty()) return "暂无 Bot 间记录"
        return arr.items.joinToString("\n") { item ->
            val obj = item as? Json.Value.Object
            val text = obj?.let { firstTextOf(it) }
            if (text != null) "· $text" else "· ${Json.encode(item)}"
        }
    }

    /** 常见叙事字段的第一个非空字符串（text/content/summary/title/question/answer/name）。 */
    private fun firstTextOf(obj: Json.Value.Object): String? =
        sequenceOf("text", "content", "summary", "title", "question", "answer", "name")
            .mapNotNull { key -> (obj.members[key] as? Json.Value.StringValue)?.value?.takeIf { it.isNotBlank() } }
            .firstOrNull()?.let { it.take(200) }

    private fun longOf(obj: Json.Value.Object?, key: String): Long? =
        (obj?.members?.get(key) as? Json.Value.NumberValue)?.raw?.toLongOrNull()

    /** 毫秒 → 人读时长：≥1h 用小时，≥1min 用分钟，否则秒。 */
    internal fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> String.format("%.1f 小时", s / 3600.0)
            s >= 60 -> String.format("%.1f 分钟", s / 60.0)
            else -> "${s} 秒"
        }
    }

    /** token 数 → 3 位有效数字缩写（6237275 → 6.24M；194239 → 194K；999 → 999）。 */
    internal fun count(n: Long): String = when {
        n >= 1_000_000 -> String.format("%.2fM", n / 1_000_000.0)
        n >= 10_000 -> "${n / 1000}K"
        else -> "$n"
    }
}
