package work.keepwriting.dshanywhere

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
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
import work.keepwriting.core.MarkdownParse.InlineKind
import work.keepwriting.core.MarkdownParse.InlinePart
import work.keepwriting.core.MarkdownParse.MdBlock

/**
 * 轻量 Markdown 渲染（dsbot-bot-chat，design 决策 markdown-lite-renderer）：
 * 标题（#/##/###）、无序列表（-）、有序列表（1.）、表格（| 分隔，两行表头）、
 * 代码块（``` 围栏，等宽可复制由气泡级长按复制承担）、行内代码（`）、粗体（**）、
 * 斜体（*）、链接（段落与列表行同样可点：点击交上层默认打开；按住约半秒改走
 * 「选择打开方式」选择器）。未知语法降级为纯文本段落，不崩溃
 * （version-baseline-and-degrade）。块/行内解析在 core 的 MarkdownParse。
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
                // 文本块统一走行内渲染：链接在列表行与段落行同样可点（fix-android-chat-links-and-copy）
                is MdBlock.Heading -> InlineText(
                    block.parts,
                    onOpenLink,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    outer = SpanStyle(fontWeight = FontWeight.Bold),
                )
                is MdBlock.Bullet -> InlineText(
                    listOf(InlinePart(InlineKind.PLAIN, "• ")) + block.parts,
                    onOpenLink,
                )
                is MdBlock.Ordered -> InlineText(
                    listOf(InlinePart(InlineKind.PLAIN, "${block.index}. ")) + block.parts,
                    onOpenLink,
                )
                is MdBlock.Code -> Text(
                    block.code,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.let { m -> if (onCopyBlock != null) m.clickable(onClick = { onCopyBlock(block.code) }) else m },
                )
                is MdBlock.Table -> MdTable(block, onOpenLink)
                is MdBlock.Paragraph -> InlineText(block.parts, onOpenLink)
            }
        }
    }
}

@Composable
private fun MdTable(table: MdBlock.Table, onOpenLink: ((String, Boolean) -> Unit)?) {
    // 列宽按各列最宽单元格的显示宽度配权（CJK 双宽计数、上限截断）：无权重的 Row 子项在
    // 气泡宽度不足时会被压到极窄、长文本挤成一字一行竖排（fix-bot-chat-table-render）。
    val weights = remember(table) { columnWeights(table) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            table.header.forEachIndexed { i, cell ->
                InlineText(
                    cell,
                    onOpenLink,
                    style = MaterialTheme.typography.labelMedium,
                    outer = SpanStyle(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .weight(weights.getOrElse(i) { 1f })
                        .padding(end = 8.dp, bottom = 2.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        table.rows.forEachIndexed { r, row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { i, cell ->
                    InlineText(
                        cell,
                        onOpenLink,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(weights.getOrElse(i) { 1f })
                            .padding(end = 8.dp, top = 3.dp, bottom = 3.dp),
                    )
                }
            }
            if (r != table.rows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

/** 各列配权：列内最宽单元格的显示宽度（CJK 等全宽字符按 2 计），夹在 2..48——
 *  短列不塌缩、长列不独占，单元格内文本自然换行。 */
private fun columnWeights(table: MdBlock.Table): List<Float> {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    return (0 until columns).map { i ->
        val widths = sequenceOf(table.header.getOrNull(i)) + table.rows.asSequence().map { it.getOrNull(i) }
        val widest = widths.filterNotNull().maxOfOrNull { parts ->
            parts.sumOf { p -> p.text.sumOf { ch -> if (isWideChar(ch)) 2L else 1L } }
        } ?: 1L
        widest.coerceIn(2L, 48L).toFloat()
    }
}

private fun isWideChar(c: Char): Boolean = Character.UnicodeScript.of(c.code) in WIDE_SCRIPTS

private val WIDE_SCRIPTS = setOf(
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HANGUL,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
)

/** 链接文字色（无主题资源依赖，保持既有视觉）。 */
private val LinkColor = Color(0xFF1E88E5)

/** 长按识别时长：按住超过此时长即触发「选择打开方式」。取 350ms 刻意早于系统
 *  longPressTimeout（默认 400ms）——触发后立即消费后续指针事件，抢先于
 *  SelectionContainer 的长按选择，避免长按链接同时弹选择器又进入选择模式。 */
private const val LINK_LONG_PRESS_MS = 350L

/** 长按命中的额外判定边距（fp 像素）：链接矩形四周放宽，照顾粗手指。 */
private const val LINK_HIT_SLACK = 8f

@Composable
private fun InlineText(
    parts: List<InlinePart>,
    onOpenLink: ((String, Boolean) -> Unit)?,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    outer: SpanStyle? = null,
    modifier: Modifier = Modifier,
) {
    // 行内格式：粗体/斜体/行内代码整体呈现（分段拼接，简化为样式化片段）；outer 为
    // 块级附加样式（如标题加粗），与片段样式叠加
    val styled = parts
    if (onOpenLink == null) {
        Text(buildAnnotatedString(styled, null, outer), style = style, modifier = modifier)
        return
    }
    val haptic = LocalHapticFeedback.current
    // 链接矩形（布局后回填）：长按命中判定用；普通点击仍由 LinkAnnotation 自己处理。
    var linkRects by remember { mutableStateOf<List<Pair<Rect, String>>>(emptyList()) }
    Text(
        buildAnnotatedString(styled, { url -> linkAnnotation(url) { onOpenLink(url, false) } }, outer),
        style = style,
        onTextLayout = { layout -> linkRects = linkRanges(styled).mapNotNull { (s, e, url) ->
            rangeRect(layout, s, e)?.let { it to url }
        } },
        modifier = (
            if (styled.any { it.kind == InlineKind.LINK }) Modifier.linkLongPress(
                onLongPress = { pos ->
                    val url = linkRects.firstOrNull { it.first.inflate(LINK_HIT_SLACK).contains(pos) }?.second
                    if (url != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenLink(url, true)
                    }
                },
            ) else Modifier
            ) then modifier,
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
 *  所有指针变化消费掉——既阻断 BasicText 内层的链接点击检测，也取消
 *  SelectionContainer 在 Main pass 的长按选择（其检测器见到已消费事件即中止），
 *  避免长按链接同时弹「选择打开方式」又进入选择模式。 */
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
                // 到点仍按住：触发长按，之后消费该指针的一切事件直至抬手
                if (!fired) {
                    fired = true
                    onLongPress(start)
                }
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val change = ev.changes.firstOrNull { it.id == down.id } ?: continue
                    change.consume()
                    if (!change.pressed) return@awaitEachGesture
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
        val url = p.url
        if (p.kind == InlineKind.LINK && url != null) out.add(Triple(cursor, cursor + p.text.length, url))
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
    outer: SpanStyle? = null,
) = buildAnnotatedString {
    withStyle(outer ?: SpanStyle()) {
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
}

// 块模型与行内解析已下沉 core（MarkdownParse，design 决策 markdown-parser-in-core）；
// 嵌套分类器经顶部 import 引入，渲染代码沿用原名

@Composable
private fun rememberBlocks(markdown: String): List<MdBlock> =
    remember(markdown) { work.keepwriting.core.MarkdownParse.parseBlocks(markdown) }
