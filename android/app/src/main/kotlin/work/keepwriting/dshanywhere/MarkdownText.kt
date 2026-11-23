package work.keepwriting.dshanywhere

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
                is MdBlock.Table -> MdTable(block, onOpenLink, onCopyBlock)
                is MdBlock.Paragraph -> InlineText(block.parts, onOpenLink)
                // 任务列表：勾选框符号表示完成状态（只读；dsbot-rich-render）
                is MdBlock.Task -> InlineText(
                    listOf(InlinePart(InlineKind.PLAIN, if (block.checked) "☑ " else "☐ ")) + block.parts,
                    onOpenLink,
                )
                is MdBlock.Image -> MdImage(block)
            }
        }
    }
}

@Composable
private fun MdTable(table: MdBlock.Table, onOpenLink: ((String, Boolean) -> Unit)?, onCopy: ((String) -> Unit)?) {
    // 列宽按各列最宽单元格的显示宽度（CJK 双宽计数、上限截断）换成固定宽度；表格放在横向滚动里，
    // 列多时可左右滑动看全，不再把列压窄（dsbot-rich-render；原 fix-bot-chat-table-render 的竖排问题同样避免）。
    val widths = remember(table) { columnWidths(table) }
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) { TableGrid(table, widths, onOpenLink) }
        Row {
            if (onCopy != null) {
                TextButton(onClick = { onCopy(work.keepwriting.core.MarkdownParse.tsv(table)) }) {
                    Text(tr("复制表格", "Copy table"), style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onClick = { expanded = true }) { Text(tr("展开", "Expand"), style = MaterialTheme.typography.labelSmall) }
        }
    }
    if (expanded) {
        Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (onCopy != null) TextButton(onClick = { onCopy(work.keepwriting.core.MarkdownParse.tsv(table)) }) { Text(tr("复制表格", "Copy table")) }
                        TextButton(onClick = { expanded = false }) { Text(tr("关闭", "Close")) }
                    }
                    Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                        TableGrid(table, widths, onOpenLink)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableGrid(table: MdBlock.Table, widths: List<androidx.compose.ui.unit.Dp>, onOpenLink: ((String, Boolean) -> Unit)?) {
    Column {
        Row {
            table.header.forEachIndexed { i, cell ->
                InlineText(
                    cell,
                    onOpenLink,
                    style = MaterialTheme.typography.labelMedium,
                    outer = SpanStyle(fontWeight = FontWeight.Bold),
                    modifier = Modifier.width(widths.getOrElse(i) { 64.dp }).padding(end = 8.dp, bottom = 2.dp),
                )
            }
        }
        HorizontalDivider(modifier = Modifier.width(widths.fold(0.dp) { a, b -> a + b }), color = MaterialTheme.colorScheme.outlineVariant)
        table.rows.forEachIndexed { r, row ->
            Row {
                row.forEachIndexed { i, cell ->
                    InlineText(
                        cell,
                        onOpenLink,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(widths.getOrElse(i) { 64.dp }).padding(end = 8.dp, top = 3.dp, bottom = 3.dp),
                    )
                }
            }
            if (r != table.rows.lastIndex) {
                HorizontalDivider(modifier = Modifier.width(widths.fold(0.dp) { a, b -> a + b }), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

/** 各列固定宽度：列内最宽单元格的显示宽度（全宽字符按 2 计）× 8.5dp 加内边距，夹在 56..240dp。 */
private fun columnWidths(table: MdBlock.Table): List<androidx.compose.ui.unit.Dp> =
    columnWeights(table).map { (it * 8.5f + 12f).coerceIn(56f, 240f).dp }

/** Markdown 图片的取图入口（聊天页提供：data/https/本地路径 → 字节；null 时只显示占位）。 */
val LocalMarkdownImageLoader = androidx.compose.runtime.staticCompositionLocalOf<((String, (ByteArray?) -> Unit) -> Unit)?> { null }

/** 独占一行的图片：取回后按比例显示（最高 240dp），失败显示可点按重试的占位（dsbot-rich-render）。 */
@Composable
private fun MdImage(block: MdBlock.Image) {
    val loader = LocalMarkdownImageLoader.current
    var attempt by remember(block.src) { mutableStateOf(0) }
    var bitmap by remember(block.src) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(block.src) { mutableStateOf(false) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    androidx.compose.runtime.LaunchedEffect(block.src, attempt) {
        failed = false
        if (loader == null) {
            failed = true
            return@LaunchedEffect
        }
        loader(block.src) { bytes ->
            val bmp = bytes?.let { decodeSampled(it, 1080) }
            main.post { if (bmp != null) bitmap = bmp else failed = true }
        }
    }
    val bmp = bitmap
    when {
        bmp != null -> androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = block.alt.ifEmpty { tr("图片", "Image") },
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(vertical = 4.dp),
        )
        failed && loader == null -> Text("🖼️ ${block.alt.ifEmpty { block.src.substringAfterLast('/') }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        failed -> Text(
            "🖼️ ${block.alt.ifEmpty { block.src.substringAfterLast('/') }}" + tr("（取不到，点按重试）", " (unavailable, tap to retry)"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { attempt++ }.padding(vertical = 4.dp),
        )
        else -> Text("🖼️ …", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
    }
}

/** 按目标宽度采样解码，避免大图占满内存。 */
private fun decodeSampled(bytes: ByteArray, maxSide: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
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

/**
 * 链接长按刚触发过（dsbot-reader-files）：气泡的长按菜单在此后短时间内跳过，保证长按链接只出
 * 「选择打开方式」选择器（手指完全不动时外层长按计时不会被消费事件取消）。主线程读写。
 */
object LinkLongPress {
    private var at = 0L

    fun mark() {
        at = android.os.SystemClock.uptimeMillis()
    }

    fun recent(): Boolean = android.os.SystemClock.uptimeMillis() - at < 1500
}

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
                        LinkLongPress.mark()
                        onOpenLink(url, true)
                    }
                    url != null
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
private fun Modifier.linkLongPress(onLongPress: (Offset) -> Boolean): Modifier = pointerInput(Unit) {
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
                // 到点仍按住：命中链接才触发并接管——之后消费该指针的一切事件直至抬手；
                // 没命中链接就放手不管，外层气泡的长按菜单照常识别（dsbot-reader-files）
                if (!fired) {
                    fired = true
                    if (!onLongPress(start)) return@awaitEachGesture
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
