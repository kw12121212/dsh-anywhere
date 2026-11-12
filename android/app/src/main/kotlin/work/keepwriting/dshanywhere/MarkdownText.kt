package work.keepwriting.dshanywhere

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.withStyle

/**
 * 轻量 Markdown 渲染（dsbot-bot-chat，design 决策 markdown-lite-renderer）：
 * 标题（#/##/###）、无序列表（-）、有序列表（1.）、表格（| 分隔，两行表头）、
 * 代码块（``` 围栏，等宽可复制由气泡级长按复制承担）、行内代码（`）、粗体（**）、
 * 斜体（*）、链接（交系统浏览器由上层处理，这里渲染为可辨文本）。
 * 未知语法降级为纯文本段落，不崩溃（version-baseline-and-degrade）。
 */
@Composable
fun MarkdownText(markdown: String, onCopyBlock: ((String) -> Unit)? = null, onOpenLink: ((String) -> Unit)? = null) {
    val blocks = rememberBlocks(markdown)
    Column {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Bold,
                )
                is MdBlock.Bullet -> Text("• ${block.text}", style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Ordered -> Text("${block.index}. ${block.text}", style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Code -> Text(
                    block.code,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.let { m -> if (onCopyBlock != null) m.clickable(onClick = { onCopyBlock(block.code) }) else m },
                )
                is MdBlock.Table -> MdTable(block)
                is MdBlock.Paragraph -> InlineText(block.text, onOpenLink)
            }
        }
    }
}

@Composable
private fun MdTable(table: MdBlock.Table) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row {
            table.header.forEach { cell ->
                Text(
                    cell,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
        table.rows.forEach { row ->
            Row {
                row.forEach { cell ->
                    Text(cell, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun InlineText(text: String, onOpenLink: ((String) -> Unit)?) {
    // 行内格式：粗体/斜体/行内代码整体呈现（分段拼接，简化为样式化片段）
    val styled = parseInline(text)
    Text(buildAnnotatedString(styled, onOpenLink), style = MaterialTheme.typography.bodyMedium)
}

private fun buildAnnotatedString(parts: List<InlinePart>, onOpenLink: ((String) -> Unit)?) = androidx.compose.ui.text.buildAnnotatedString {
    parts.forEach { part ->
        when (part.kind) {
            InlineKind.BOLD -> withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(part.text) }
            InlineKind.ITALIC -> withStyle(androidx.compose.ui.text.SpanStyle(fontStyle = FontStyle.Italic)) { append(part.text) }
            InlineKind.CODE -> withStyle(androidx.compose.ui.text.SpanStyle(fontFamily = FontFamily.Monospace)) { append(part.text) }
            InlineKind.LINK -> {
                val url = part.url
                if (url != null && onOpenLink != null) {
                    // 链接交给上层（系统浏览器 Intent）：可点样式 + 点击回调
                    val linkStyle = androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF1E88E5))
                    withStyle(linkStyle) {
                        pushLink(androidx.compose.ui.text.LinkAnnotation.Clickable(tag = url, styles = androidx.compose.ui.text.TextLinkStyles(linkStyle)) { onOpenLink(url) })
                        append(part.text)
                        pop()
                    }
                } else {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF1E88E5))) { append(part.text) }
                }
            }
            InlineKind.PLAIN -> append(part.text)
        }
    }
}

internal enum class InlineKind { PLAIN, BOLD, ITALIC, CODE, LINK }
internal data class InlinePart(val kind: InlineKind, val text: String, val url: String? = null)

private data class MdInlineSnapshot(val blocks: List<MdBlock>)
private var mdSnapshotRef: Pair<String, MdInlineSnapshot>? = null

@Composable
private fun rememberBlocks(markdown: String): List<MdBlock> {
    val cached = mdSnapshotRef
    return if (cached?.first == markdown) cached.second.blocks else {
        val parsed = parseBlocks(markdown)
        mdSnapshotRef = markdown to MdInlineSnapshot(parsed)
        parsed
    }
}

/** 纯解析（单参数快照缓存，非 Compose 状态——聊天页每条消息独立调用）。 */
internal fun parseBlocks(markdown: String): List<MdBlock> {
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
            line.startsWith("### ") -> out.add(MdBlock.Heading(3, line.removePrefix("### ")))
            line.startsWith("## ") -> out.add(MdBlock.Heading(2, line.removePrefix("## ")))
            line.startsWith("# ") -> out.add(MdBlock.Heading(1, line.removePrefix("# ")))
            line.startsWith("- ") -> out.add(MdBlock.Bullet(line.removePrefix("- ")))
            Regex("^\\d+\\. ").containsMatchIn(line) -> out.add(
                MdBlock.Ordered(line.substringBefore(". "), line.substringAfter(". ")),
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
            else -> out.add(MdBlock.Paragraph(line))
        }
        i++
    }
    return out
}

internal fun parseInline(text: String): List<InlinePart> {
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

internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Ordered(val index: String, val text: String) : MdBlock
    data class Code(val code: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}
