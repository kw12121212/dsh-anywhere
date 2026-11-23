package work.keepwriting.dshanywhere

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import work.keepwriting.core.Json
import work.keepwriting.core.ViewCard

/**
 * show_view 卡片原生绘制（dsbot-rich-render）：按 core ViewCard 校验通过的组件树递归绘制。输入值是 Compose
 * 状态，每次重组以当前输入建作用域重算公式；作答组件经 [onAnswer]("view-reply", 请求体) 提交。
 * 图表用 Canvas 绘制，不执行任何脚本、不访问网络；html 组件只显示说明。
 */
@Composable
fun ViewCardView(bp: ViewCard.Blueprint, onAnswer: (String, String) -> Unit, onDismiss: () -> Unit, onNotice: (String) -> Unit) {
    val inputs = remember(bp.id) { mutableStateMapOf<String, Any>() }
    val lookup = ViewCard.scope(bp.root, inputs.toMap())
    val ctx = RenderCtx(bp, inputs, lookup, onAnswer, onNotice)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        bp.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        Node(bp.root, ctx)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(tr("关闭", "Close")) }
        }
    }
}

/** 校验不通过的卡片：只读标题与摘要，可关闭。 */
@Composable
fun ViewCardInvalid(title: String?, summary: String?, onDismiss: () -> Unit) {
    Column {
        Text(title ?: tr("卡片", "Card"), style = MaterialTheme.typography.titleSmall)
        Text(
            summary?.takeIf { it.isNotBlank() } ?: tr("这张卡片的格式手机端无法显示，可在电脑网页端查看", "This card can't be shown on the phone; view it in the web app on your computer"),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = onDismiss) { Text(tr("关闭", "Close")) }
    }
}

private class RenderCtx(
    val bp: ViewCard.Blueprint,
    val inputs: MutableMap<String, Any>,
    val lookup: (String) -> Any,
    val onAnswer: (String, String) -> Unit,
    val onNotice: (String) -> Unit,
) {
    fun answer(action: String, a: ViewCard.Answer) = onAnswer("view-reply", ViewCard.reply(bp, action, a, inputs.toMap()))
}

private fun Json.Value.Object.s(key: String) = ViewCard.str(this, key)

private fun children(o: Json.Value.Object): List<Json.Value.Object> =
    (o.members["children"] as? Json.Value.Array)?.items.orEmpty().mapNotNull { it as? Json.Value.Object }

@Composable
private fun Node(o: Json.Value.Object, ctx: RenderCtx) {
    when (o.s("type")) {
        "stack" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { children(o).forEach { Node(it, ctx) } }
        "row" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            children(o).forEach { Box(modifier = Modifier.weight(1f)) { Node(it, ctx) } }
        }
        "text" -> Text(
            ViewCard.text(o.members["text"], ctx.lookup),
            style = MaterialTheme.typography.bodyMedium,
            color = if (ViewCard.resolve(o.members["tone"], ctx.lookup) == "muted") MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
        )
        "metric" -> Column {
            Text(o.s("label") ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(ViewCard.text(o.members["value"], ctx.lookup), style = MaterialTheme.typography.headlineSmall, color = toneColor(ViewCard.resolve(o.members["tone"], ctx.lookup) as? String))
            o.members["caption"]?.let { Text(ViewCard.text(it, ctx.lookup), style = MaterialTheme.typography.bodySmall) }
        }
        "table" -> ViewTable(o, ctx)
        "chart" -> ViewCard.chart(o, ctx.lookup)?.let { ChartView(it) }
        "progress" -> Column {
            val pct = ViewCard.progressPercent(o, ctx.lookup)
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(o.s("label") ?: "", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(o.members["valueLabel"]?.let { ViewCard.text(it, ctx.lookup) } ?: "${Math.round(pct)}%", style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(progress = { (pct / 100).toFloat() }, modifier = Modifier.fillMaxWidth(), color = toneColor(ViewCard.resolve(o.members["tone"], ctx.lookup) as? String).takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.primary)
        }
        "callout" -> Surface(color = calloutColor(ViewCard.resolve(o.members["tone"], ctx.lookup) as? String), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Text(ViewCard.text(o.members["text"], ctx.lookup), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(10.dp))
        }
        "sources" -> {
            val context = LocalContext.current
            Column {
                (o.members["items"] as? Json.Value.Array)?.items.orEmpty().mapNotNull { it as? Json.Value.Object }.forEach { item ->
                    val url = item.s("url") ?: return@forEach
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("↗ " + (item.s("label") ?: url), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        "html" -> Column {
            Text(o.s("summary") ?: "", style = MaterialTheme.typography.bodyMedium)
            Text(tr("（交互内容请在电脑网页端查看）", "(Open the web app on your computer for the interactive part)"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "slider" -> NumberInput(o, ctx, slider = true)
        "number" -> NumberInput(o, ctx, slider = false)
        "choice" -> ChoiceInput(o, ctx)
        "toggle" -> {
            val id = o.s("id") ?: return
            val on = (ctx.inputs[id] ?: ViewCard.inputDefault(o)) == true
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(o.s("label") ?: id, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = { ctx.inputs[id] = it })
            }
        }
        "options" -> OptionsAnswer(o, ctx)
        "confirm" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val id = o.s("id") ?: return
            Button(onClick = { ctx.answer(id, ViewCard.Answer.Confirm(true)) }) { Text(o.s("confirmLabel") ?: tr("确认", "Confirm")) }
            OutlinedButton(onClick = { ctx.answer(id, ViewCard.Answer.Confirm(false)) }) { Text(o.s("cancelLabel") ?: tr("取消", "Cancel")) }
        }
        "form" -> FormAnswer(o, ctx)
        "submit" -> {
            val id = o.s("id") ?: return
            Button(onClick = { ctx.answer(id, ViewCard.Answer.Submit) }) { Text(o.s("label") ?: tr("发送", "Send")) }
        }
    }
}

@Composable
private fun NumberInput(o: Json.Value.Object, ctx: RenderCtx, slider: Boolean) {
    val id = o.s("id") ?: return
    val min = ViewCard.finite(o.members["min"])
    val max = ViewCard.finite(o.members["max"])
    val step = ViewCard.finite(o.members["step"])
    val value = (ctx.inputs[id] as? Double) ?: (ViewCard.inputDefault(o) as? Double) ?: 0.0
    val unit = o.s("unit")?.let { " $it" } ?: ""
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(o.s("label") ?: id, modifier = Modifier.weight(1f))
            Text(work.keepwriting.core.ViewFormula.show(value) + unit, style = MaterialTheme.typography.labelLarge)
        }
        if (slider && min != null && max != null) {
            // 连续滑动、按步长吸附到 min + k·step 且夹在 [min, max]（与网页端 range 输入一致，作答不会越界）
            Slider(
                value = value.toFloat(),
                onValueChange = { v -> ctx.inputs[id] = ViewCard.snap(v.toDouble(), min, max, step) },
                valueRange = min.toFloat()..max.toFloat(),
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val d = step ?: 1.0
                OutlinedButton(onClick = { ctx.inputs[id] = clamp(value - d, min, max) }) { Text("−") }
                OutlinedButton(onClick = { ctx.inputs[id] = clamp(value + d, min, max) }) { Text("+") }
            }
        }
    }
}

private fun clamp(v: Double, min: Double?, max: Double?): Double = v.coerceIn(min ?: -Double.MAX_VALUE, max ?: Double.MAX_VALUE)

@Composable
private fun ChoiceInput(o: Json.Value.Object, ctx: RenderCtx) {
    val id = o.s("id") ?: return
    val current = ctx.inputs[id] ?: ViewCard.inputDefault(o)
    Column {
        Text(o.s("label") ?: id, style = MaterialTheme.typography.labelMedium)
        (o.members["options"] as? Json.Value.Array)?.items.orEmpty().mapNotNull { it as? Json.Value.Object }.forEach { opt ->
            val v = ViewCard.choiceValue(opt) ?: return@forEach
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = v == current, onClick = { ctx.inputs[id] = v })
                Text(opt.s("label") ?: "")
            }
        }
    }
}

@Composable
private fun OptionsAnswer(o: Json.Value.Object, ctx: RenderCtx) {
    val id = o.s("id") ?: return
    val multi = (o.members["multi"] as? Json.Value.BoolValue)?.value == true
    val picked = remember(ctx.bp.id, id) { mutableStateListOf<String>() }
    val options = (o.members["options"] as? Json.Value.Array)?.items.orEmpty().mapNotNull { it as? Json.Value.Object }
    Column {
        options.forEach { opt ->
            val label = opt.s("label") ?: return@forEach
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (multi) {
                    Checkbox(checked = label in picked, onCheckedChange = { if (it) picked.add(label) else picked.remove(label) })
                } else {
                    RadioButton(selected = label in picked, onClick = { ctx.answer(id, ViewCard.Answer.Options(listOf(label))) })
                }
                Column {
                    Text(label)
                    opt.s("description")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (multi) Button(enabled = picked.isNotEmpty(), onClick = { ctx.answer(id, ViewCard.Answer.Options(picked.toList())) }) { Text(tr("提交", "Submit")) }
    }
}

@Composable
private fun FormAnswer(o: Json.Value.Object, ctx: RenderCtx) {
    val id = o.s("id") ?: return
    val fields = (o.members["fields"] as? Json.Value.Array)?.items.orEmpty().mapNotNull { it as? Json.Value.Object }
    val values = remember(ctx.bp.id, id) { mutableStateMapOf<String, String>().apply { fields.forEach { f -> f.s("id")?.let { put(it, f.s("value") ?: "") } } } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        fields.forEach { f ->
            val fid = f.s("id") ?: return@forEach
            val readOnly = (f.members["readOnly"] as? Json.Value.BoolValue)?.value == true
            val multiline = (f.members["multiline"] as? Json.Value.BoolValue)?.value == true
            OutlinedTextField(
                value = values[fid] ?: "",
                onValueChange = { if (it.length <= ViewCard.MAX_TEXT) values[fid] = it },
                label = { Text(f.s("label") ?: fid) },
                placeholder = f.s("placeholder")?.let { p -> { Text(p) } },
                readOnly = readOnly,
                singleLine = !multiline,
                minLines = if (multiline) 3 else 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { ctx.answer(id, ViewCard.Answer.Form(values.toMap())) }) { Text(o.s("submitLabel") ?: tr("提交", "Submit")) }
            OutlinedButton(onClick = { ctx.answer(id, ViewCard.Answer.FormCanceled) }) { Text(o.s("cancelLabel") ?: tr("取消", "Cancel")) }
        }
    }
}

@Composable
private fun ViewTable(o: Json.Value.Object, ctx: RenderCtx) {
    val clipboard = LocalClipboardManager.current
    val cols = (o.members["columns"] as? Json.Value.Array)?.items.orEmpty().mapNotNull { it as? Json.Value.Object }
    val rows = (o.members["rows"] as? Json.Value.Array)?.items.orEmpty().mapNotNull { (it as? Json.Value.Array)?.items }
    Column {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row { cols.forEach { c -> Cell(c.s("label") ?: "", header = true) } }
            rows.forEach { cells -> Row { cols.indices.forEach { i -> Cell(cells.getOrNull(i)?.let { ViewCard.tableCell(it, ctx.lookup) } ?: "", header = false) } } }
        }
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(ViewCard.tableCsv(o, ctx.lookup)))
            ctx.onNotice(tr("已复制为 CSV", "Copied as CSV"))
        }) { Text(tr("复制 CSV", "Copy CSV")) }
    }
}

@Composable
private fun Cell(text: String, header: Boolean) {
    Text(
        text,
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(min = 72.dp, max = 180.dp).padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

private val PALETTE = listOf(
    Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFB8C00),
    Color(0xFF8E24AA), Color(0xFF00ACC1), Color(0xFF6D4C41), Color(0xFF546E7A),
)

/** Canvas 图表：bar/hbar 分组柱、line/area 折线与面积、donut 圆环；类别标签、最大/最小刻度与图例。 */
@Composable
fun ChartView(chart: ViewCard.Chart) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    Column {
        Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
            val (lo, hi) = chart.range
            val span = (hi - lo).takeIf { it > 0 } ?: 1.0
            // 左边距按实际标签宽度：横柱图放类别名，其余放最大/最小刻度
            val leftLabels = if (chart.kind == "hbar") chart.categories.map { it.take(6) } else listOf(ViewFmt.num(hi), ViewFmt.num(lo))
            val left = (leftLabels.maxOfOrNull { measurer.measure(it, labelStyle).size.width } ?: 0) + 8f
            val bottom = size.height - if (chart.kind == "hbar") 8f else 30f
            val top = 8f
            val right = size.width - 8f
            val n = chart.categories.size
            if (chart.kind == "donut") {
                val values = chart.series.first().values.map { maxOf(0.0, it) }
                val total = values.sum().takeIf { it > 0 } ?: 1.0
                val d = minOf(size.width, size.height) - 16f
                val stroke = d * 0.22f
                val tl = Offset((size.width - d) / 2 + stroke / 2, (size.height - d) / 2 + stroke / 2)
                val arcSize = Size(d - stroke, d - stroke)
                var start = -90f
                values.forEachIndexed { i, v ->
                    val sweep = (v / total * 360).toFloat()
                    drawArc(PALETTE[i % PALETTE.size], start, sweep, false, tl, arcSize, style = Stroke(stroke))
                    start += sweep
                }
                return@Canvas
            }
            fun y(v: Double) = bottom - ((v - lo) / span * (bottom - top)).toFloat()
            fun x(v: Double) = left + ((v - lo) / span * (right - left)).toFloat()
            // 刻度与基线
            drawLine(gridColor, Offset(left, top), Offset(left, bottom))
            if (chart.kind == "hbar") {
                drawLine(gridColor, Offset(x(0.0), top), Offset(x(0.0), bottom))
            } else {
                drawLine(gridColor, Offset(left, y(0.0)), Offset(right, y(0.0)))
                drawText(measurer, ViewFmt.num(hi), Offset(0f, top - 4f), labelStyle)
                drawText(measurer, ViewFmt.num(lo), Offset(0f, bottom - 12f), labelStyle)
            }
            val groupW = (right - left) / n
            when (chart.kind) {
                "bar" -> {
                    val barW = groupW * 0.7f / chart.series.size
                    chart.series.forEachIndexed { si, s ->
                        s.values.forEachIndexed { i, v ->
                            val x0 = left + groupW * i + groupW * 0.15f + barW * si
                            val y0 = y(maxOf(v, 0.0)); val y1 = y(minOf(v, 0.0))
                            drawRect(PALETTE[si % PALETTE.size], Offset(x0, y0), Size(barW, maxOf(1f, y1 - y0)))
                        }
                    }
                }
                "hbar" -> {
                    val groupH = (bottom - top) / n
                    val barH = groupH * 0.7f / chart.series.size
                    chart.series.forEachIndexed { si, s ->
                        s.values.forEachIndexed { i, v ->
                            val y0 = top + groupH * i + groupH * 0.15f + barH * si
                            val x0 = x(minOf(v, 0.0)); val x1 = x(maxOf(v, 0.0))
                            drawRect(PALETTE[si % PALETTE.size], Offset(x0, y0), Size(maxOf(1f, x1 - x0), barH))
                        }
                    }
                }
                else -> chart.series.forEachIndexed { si, s ->
                    val color = PALETTE[si % PALETTE.size]
                    val pts = s.values.mapIndexed { i, v -> Offset(left + groupW * (i + 0.5f), y(v)) }
                    if (chart.kind == "area" && pts.size > 1) {
                        val path = Path().apply {
                            moveTo(pts.first().x, y(0.0)); pts.forEach { lineTo(it.x, it.y) }; lineTo(pts.last().x, y(0.0)); close()
                        }
                        drawPath(path, color.copy(alpha = 0.25f))
                    }
                    for (i in 1 until pts.size) drawLine(color, pts[i - 1], pts[i], strokeWidth = 4f)
                    pts.forEach { drawCircle(color, 5f, it) }
                }
            }
            // 类别标签（过密时隔几个画一个）
            val every = maxOf(1, n / 6)
            chart.categories.forEachIndexed { i, label ->
                if (i % every != 0) return@forEachIndexed
                val text = label.take(6)
                if (chart.kind == "hbar") drawText(measurer, text, Offset(0f, top + (bottom - top) / n * (i + 0.3f)), labelStyle)
                else {
                    val w = measurer.measure(text, labelStyle).size.width
                    drawText(measurer, text, Offset((left + groupW * (i + 0.5f) - w / 2f).coerceAtLeast(0f), bottom + 4f), labelStyle)
                }
            }
        }
        // 图例：多序列显示序列名；环形图显示类别与数值
        val legend = if (chart.kind == "donut") chart.categories.zip(chart.series.first().values).map { (c, v) -> "$c ${ViewFmt.num(v)}${chart.unit ?: ""}" }
        else chart.series.mapIndexed { i, s -> s.name ?: tr("系列 ${i + 1}", "Series ${i + 1}") }.takeIf { chart.series.size > 1 || chart.series.firstOrNull()?.name != null } ?: emptyList()
        if (legend.isNotEmpty()) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                legend.forEachIndexed { i, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(PALETTE[i % PALETTE.size], CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        chart.caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = labelColor) }
        if (chart.kind != "donut") chart.unit?.let { Text(tr("单位：$it", "Unit: $it"), style = MaterialTheme.typography.labelSmall, color = labelColor) }
    }
}

private object ViewFmt {
    fun num(v: Double): String = work.keepwriting.core.ViewFormula.show(v)
}

@Composable
private fun toneColor(tone: String?): Color = when (tone) {
    "good" -> Color(0xFF2E7D32)
    "warn" -> Color(0xFFEF6C00)
    "bad" -> Color(0xFFC62828)
    else -> Color.Unspecified
}

@Composable
private fun calloutColor(tone: String?): Color = when (tone) {
    "good" -> Color(0x332E7D32)
    "warn" -> Color(0x33EF6C00)
    "bad" -> Color(0x33C62828)
    else -> MaterialTheme.colorScheme.secondaryContainer
}
