package work.keepwriting.core

/**
 * 轻量 Markdown 块/行内解析（dsbot-bot-chat 的 design 决策 markdown-lite-renderer，
 * markdown-parser-in-core 下沉自 :app 的 MarkdownText）：标题（#/##/###）、无序列表（-）、
 * 有序列表（1.）、表格（| 分隔，两行表头）、代码块（``` 围栏）为块级；粗体（**）、
 * 斜体（*）、行内代码（`）、链接（[文字](URL)）为行内。未知语法降级为纯文本段落，
 * 不崩溃（tech/dsbot-api 的 version-baseline-and-degrade）。解析接受集以网页端渲染
 * 行为为契约（lesson-0d4784：以对端参考实现为契约并配负向测试）。
 */
object MarkdownParse {

    /** 块级条目：文本块（标题/列表/段落）携带解析好的行内片段——链接在段落与列表行
     *  同样暴露（fix-android-chat-links-and-copy），代码块与表格保留原文。 */
    sealed interface MdBlock {
        data class Heading(val level: Int, val parts: List<InlinePart>) : MdBlock
        data class Bullet(val parts: List<InlinePart>) : MdBlock
        data class Ordered(val index: String, val parts: List<InlinePart>) : MdBlock
        data class Code(val code: String) : MdBlock
        data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
        data class Paragraph(val parts: List<InlinePart>) : MdBlock
    }

    enum class InlineKind { PLAIN, BOLD, ITALIC, CODE, LINK }

    data class InlinePart(val kind: InlineKind, val text: String, val url: String? = null)

    /** 纯解析（单参数快照缓存，非 Compose 状态——聊天页每条消息独立调用）。 */
    private var snapshot: Pair<String, List<MdBlock>>? = null

    fun parseBlocks(markdown: String): List<MdBlock> {
        val cached = snapshot
        if (cached?.first == markdown) return cached.second
        val parsed = parseBlocksUncached(markdown)
        snapshot = markdown to parsed
        return parsed
    }

    private fun parseBlocksUncached(markdown: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val lines = markdown.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("```") -> {
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) {
                        code.appendLine(lines[i])
                        i++
                    }
                    i++ // 跳过收尾围栏（缺失也容错）
                    out.add(MdBlock.Code(code.toString().trimEnd('\n')))
                }
                line.startsWith("### ") -> out.add(MdBlock.Heading(3, parseInline(line.removePrefix("### "))))
                line.startsWith("## ") -> out.add(MdBlock.Heading(2, parseInline(line.removePrefix("## "))))
                line.startsWith("# ") -> out.add(MdBlock.Heading(1, parseInline(line.removePrefix("# "))))
                line.startsWith("- ") -> out.add(MdBlock.Bullet(parseInline(line.removePrefix("- "))))
                Regex("^\\d+\\. ").containsMatchIn(line) -> out.add(
                    MdBlock.Ordered(line.substringBefore(". "), parseInline(line.substringAfter(". "))),
                )
                line.startsWith("|") && i + 1 < lines.size && lines[i + 1].startsWith("|") -> {
                    val header = splitTableRow(line)
                    i += 2 // 表头 + 分隔行
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].startsWith("|")) {
                        rows.add(splitTableRow(lines[i]))
                        i++
                    }
                    out.add(MdBlock.Table(header, rows))
                    continue
                }
                line.isBlank() -> {}
                else -> out.add(MdBlock.Paragraph(parseInline(line)))
            }
            i++
        }
        return out
    }

    fun parseInline(text: String): List<InlinePart> {
        val parts = mutableListOf<InlinePart>()
        var rest = text
        val bold = Regex("\\*\\*(.+?)\\*\\*")
        val italic = Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)")
        val code = Regex("`([^`]+)`")
        val link = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
        data class Hit(val start: Int, val range: IntRange, val part: InlinePart)
        while (rest.isNotEmpty()) {
            val candidates = listOfNotNull(
                bold.find(rest)?.let { Hit(it.range.first, it.range, InlinePart(InlineKind.BOLD, it.groupValues[1])) },
                code.find(rest)?.let { Hit(it.range.first, it.range, InlinePart(InlineKind.CODE, it.groupValues[1])) },
                link.find(rest)?.let { Hit(it.range.first, it.range, InlinePart(InlineKind.LINK, it.groupValues[1], it.groupValues[2])) },
                italic.find(rest)?.let { Hit(it.range.first, it.range, InlinePart(InlineKind.ITALIC, it.groupValues[1])) },
            )
            if (candidates.isEmpty()) {
                parts.add(InlinePart(InlineKind.PLAIN, rest))
                break
            }
            val hit = candidates.minByOrNull { it.start }!!
            if (hit.range.first > 0) parts.add(InlinePart(InlineKind.PLAIN, rest.substring(0, hit.range.first)))
            parts.add(hit.part)
            rest = rest.substring(hit.range.last + 1)
        }
        return parts
    }

    private fun splitTableRow(line: String): List<String> =
        line.trim('|').split('|').map { it.trim() }
}
