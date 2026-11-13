package work.keepwriting.dshanywhere

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.withStyle
import kotlin.time.TimeSource
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 轻量 Markdown 渲染（dsbot-bot-chat，design 决策 markdown-lite-renderer）：
 * 标题（#/##/###）、无序列表（-）、有序列表（1.）、表格（| 分隔，两行表头）、
 * 代码块（``` 围栏，等宽可复制由气泡级长按复制承担）、行内代码（`）、粗体（**）、
 * 斜体（*）、链接（点击交上层默认打开；按住约半秒改走「选择打开方式」选择器）。
 * 未知语法降级为纯文本段落，不崩溃（version-baseline-and-degrade）。
 */
@Composable
fun MarkdownText(
    markdown: String,
    onCopyBlock: ((String) -> Unit)? = null,
    /** 链接打开回调：[chooser] 为 false 走默认应用，true 时由上层弹「选择打开方式」。 */
    onOpenLink: ((url: String, chooser: Boolean) -> Unit)? = null,
) {
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

/** 链接文字色（无主题资源依赖，保持既有视觉）。 */
private val LinkColor = Color(0xFF1E88E5)

/** 长按识别时长：按住超过此时长即触发「选择打开方式」，抬手早于此为普通点击。 */
private const val LINK_LONG_PRESS_MS = 450L

/** 长按命中的额外判定边距（fp 像素）：链接矩形四周放宽，照顾粗手指。 */
private const val LINK_HIT_SLACK = 8f

@Composable
private fun InlineText(text: String, onOpenLink: ((String, Boolean) -> Unit)?) {
    // 行内格式：粗体/斜体/行内代码整体呈现（分段拼接，简化为样式化片段）
    val styled = parseInline(text)
    if (onOpenLink == null) {
        Text(buildAnnotatedString(styled, null), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val haptic = LocalHapticFeedback.current
    // 链接矩形（布局后回填）：长按命中判定用；普通点击仍由 LinkAnnotation 自己处理。
    var linkRects by remember { mutableStateOf<List<Pair<Rect, String>>>(emptyList()) }
    Text(
        buildAnnotatedString(styled) { url -> linkAnnotation(url) { onOpenLink(url, false) } },
        style = MaterialTheme.typography.bodyMedium,
        onTextLayout = { layout -> linkRects = linkRanges(styled).mapNotNull { (s, e, url) ->
            rangeRect(layout, s, e)?.let { it to url }
        } },
        modifier = if (styled.any { it.kind == InlineKind.LINK }) Modifier.linkLongPress(
            onLongPress = { pos ->
                val url = linkRects.firstOrNull { it.first.inflate(LINK_HIT_SLACK).contains(pos) }?.second
                if (url != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenLink(url, true)
                }
            },
        ) else Modifier,
    )
}

/** 链接区间 [s, e) 的覆盖矩形：本版本 TextLayoutResult 无 getRectForRange，按逐字
 *  getBoundingBox 求并集（链接标签短，跨行时取并集放宽判定即可）。 */
private fun rangeRect(layout: androidx.compose.ui.text.TextLayoutResult, s: Int, e: Int): Rect? {
    var out: Rect? = null
    for (i in s until e.coerceAtMost(layout.layoutInput.text.length)) {
        val b = runCatching { layout.getBoundingBox(i) }.getOrNull() ?: continue
        val cur = out
        out = if (cur == null) b else Rect(
            minOf(cur.left, b.left), minOf(cur.top, b.top),
            maxOf(cur.right, b.right), maxOf(cur.bottom, b.bottom),
        )
    }
    return out
}

/** 本 Compose 版本 LinkInteractionListener 只有 onClick（无 Press/Release 事件，也无
 *  PointerEventType.Cancel——取消以 !pressed 表达），长按须经旁路手势：Initial pass
 *  观察按下/计时，不消费任何事件（不干扰点击与滚动）；计时到点触发回调后把随后的
 *  up 消费掉，让 BasicText 内层的链接点击检测取消。 */
private fun Modifier.linkLongPress(onLongPress: (Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val start = down.position
        val startAt = TimeSource.Monotonic.markNow()
        var fired = false
        while (true) {
            val remaining = LINK_LONG_PRESS_MS - startAt.elapsedNow().inWholeMilliseconds
            val event = if (remaining > 0) withTimeoutOrNull(remaining) {
                awaitPointerEvent(PointerEventPass.Initial)
            } else null
            if (event == null) {
                // 到点仍按住：触发长按，之后等抬手；up 消费掉阻断内层点击
                if (!fired) {
                    fired = true
                    onLongPress(start)
                }
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val change = ev.changes.firstOrNull { it.id == down.id } ?: continue
                    if (!change.pressed) {
                        change.consume()
                        return@awaitEachGesture
                    }
                }
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
            when {
                // 未触发长按就抬手/取消：正常路径，交给内层点击
                !change.pressed -> return@awaitEachGesture
                // 按住滑远（滚动/拖出链接）：放弃本次长按
                event.type == PointerEventType.Move && !fired &&
                    (change.position - start).getDistance() > viewConfiguration.touchSlop * 4 ->
                    return@awaitEachGesture
            }
        }
    }
}

/** 行内链接的字符区间（start, end, url）——onTextLayout 换算矩形用。 */
private fun linkRanges(parts: List<InlinePart>): List<Triple<Int, Int, String>> {
    val out = mutableListOf<Triple<Int, Int, String>>()
    var cursor = 0
    for (p in parts) {
        if (p.kind == InlineKind.LINK && p.url != null) out.add(Triple(cursor, cursor + p.text.length, p.url))
        cursor += p.text.length
    }
    return out
}

private fun linkAnnotation(url: String, onClick: () -> Unit): LinkAnnotation.Clickable {
    val style = SpanStyle(color = LinkColor)
    return LinkAnnotation.Clickable(tag = url, styles = TextLinkStyles(style), linkInteractionListener = {
        onClick()
    })
}

private fun buildAnnotatedString(
    parts: List<InlinePart>,
    makeLink: ((String) -> LinkAnnotation.Clickable)?,
) = buildAnnotatedString {
    parts.forEach { part ->
        when (part.kind) {
            InlineKind.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part.text) }
            InlineKind.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(part.text) }
            InlineKind.CODE -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(part.text) }
            InlineKind.LINK -> {
                val url = part.url
                if (url != null && makeLink != null) {
                    // 链接交给上层（点击默认打开；长按走选择器）：可点样式 + 点击回调
                    withStyle(SpanStyle(color = LinkColor)) {
                        pushLink(makeLink(url))
                        append(part.text)
                        pop()
                    }
                } else {
                    withStyle(SpanStyle(color = LinkColor)) { append(part.text) }
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
