package work.keepwriting.dshanywhere

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import work.keepwriting.core.BotManage
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.Json
import work.keepwriting.core.Usage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.resume

/*
 * 用量（dsbot-usage）：总览与 Bot/群聊/Agent/其他/模型详情共用一页（网页端 UsagePage + focusId）。
 * usage 按需读取（带手机时区），页面可见且 App 在前台时按网页端节奏轮询；分桶与汇总全部在 core Usage 中计算。
 */

private sealed interface UsLoad {
    data object Loading : UsLoad
    data class Ok(val view: Usage.View) : UsLoad
    data class Failed(val text: String, val unsupported: Boolean) : UsLoad
}

private suspend fun ManageApi.readNow(endpoint: String, payload: String): DsbotApi.Result =
    suspendCancellableCoroutine { cont -> read(endpoint, payload) { if (cont.isActive) cont.resume(it) } }

/**
 * 用量的共享读取（主线程使用）：总览与各详情页共用最近一次视图，进入详情或返回时先显示它再刷新；
 * 同一时刻只有一个 usage 请求在途，后到的读取（另一页、前后台切换后重启的轮询）等待同一个应答——
 * 协程取消不会取消已发出的网络请求，在这里单飞才能不占满读取池。
 */
class UsageStore {
    var view: Usage.View? = null
    private var inFlight: CompletableDeferred<DsbotApi.Result>? = null

    suspend fun load(api: ManageApi, zone: ZoneId): DsbotApi.Result {
        val pending = inFlight ?: CompletableDeferred<DsbotApi.Result>().also { d ->
            inFlight = d
            api.read("usage", Usage.Requests.view(zone)) { r ->
                if (inFlight === d) inFlight = null
                d.complete(r)
            }
        }
        return pending.await()
    }

    /** 离开全部用量页后丢弃缓存的视图（不跨会话显示旧数字）。 */
    fun clear() {
        view = null
    }
}

/** 网页端 MODEL_INKS 的原生近似色（按全部历史用量名次着色）。 */
private val MODEL_INKS = listOf(
    Color(0xFF4D6BFE), Color(0xFFFB8C00), Color(0xFF43A047), Color(0xFF8E24AA), Color(0xFF29B6F6),
    Color(0xFFE91E63), Color(0xFFFDD835), Color(0xFF00ACC1), Color(0xFFD81B60), Color(0xFF8D6E63),
)

/** 网页端 soften：与卡片底色按 88%×强度混合。 */
private fun soften(paint: Color, card: Color, strength: Float = 1f): Color = lerp(card, paint, 0.88f * strength)

/** 页面内的着色与名称（网页端 ownerFace / models 的 face）。 */
private class Faces(val view: Usage.View, state: Json.Value.Object?, val rest: Color, val more: Color, private val primary: Color) {
    private val roster = Usage.roster(state)
    private val ranks = Usage.modelRanks(view)
    private val ownerRanks = Usage.ownerRanks(view)
    fun ownerName(at: Int): String = view.owners[at].let { Usage.ownerName(it, if (it.kind == "bot") roster[it.id]?.name else null) }
    fun ownerSub(at: Int): String = view.owners[at].let { Usage.ownerSub(it, ownerName(at), if (it.kind == "bot") roster[it.id]?.role else null) }
    /** Bot 有颜色时用团队色；未设颜色时按全部历史用量名次取调色板色（网页端按 id 派生默认色，这里求可区分）；其余为中性色。 */
    fun ownerPaint(at: Int): Color = view.owners[at].let { o ->
        if (o.kind != "bot") return@let rest
        val named = roster[o.id]?.color
        if (named != null) teamColorOf(named, primary) else MODEL_INKS[(ownerRanks[at] ?: 0) % MODEL_INKS.size]
    }
    fun modelName(at: Int) = Usage.modelName(view.models[at])
    fun modelSub(at: Int) = Usage.modelSub(view.models[at])
    fun modelPaint(at: Int): Color = ranks[at]?.let { MODEL_INKS[it % MODEL_INKS.size] } ?: rest
}

/**
 * 用量页。[focusId] 为 owner:<key> 或 model:<key> 时显示该对象的详情；[active] 为页面可见且 App 在前台。
 * [onOpen] 进入另一对象的详情（保留当前范围与分组方式）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsagePage(
    state: Json.Value.Object?,
    api: ManageApi,
    store: UsageStore,
    focusId: String?,
    initialRange: Usage.Range?,
    initialBy: Usage.By,
    active: Boolean,
    onOpen: (focusId: String, range: Usage.Range, by: Usage.By) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    var load by remember { mutableStateOf<UsLoad>(store.view?.let { UsLoad.Ok(it) } ?: UsLoad.Loading) }
    var error by remember { mutableStateOf<String?>(null) }
    var range by remember { mutableStateOf(initialRange ?: Usage.preset(Usage.DEFAULT_RANGE)) }
    var by by remember { mutableStateOf(initialBy) }
    var picking by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }

    // 可见且前台时轮询（网页端节奏：扫描中 3 秒、平时 20 秒、失败 10 秒）；离开页面或进入后台即取消。
    // 每轮等上一次应答回来后才排下一次，天然单飞；代次号由协程取消保证（取消后的应答被丢弃）。
    LaunchedEffect(active, retryTick) {
        if (!active) return@LaunchedEffect
        while (isActive) {
            val r = store.load(api, zone)
            if (!isActive) break
            var failed = false
            when (r) {
                is DsbotApi.Result.Ok -> {
                    val v = Usage.decode(r.value)
                    if (v != null) {
                        store.view = v
                        load = UsLoad.Ok(v)
                        error = null
                    } else {
                        failed = true
                        val text = tr("服务端应答无法识别", "The server answer could not be read")
                        if (load is UsLoad.Ok) error = text else load = UsLoad.Failed(text, false)
                    }
                }
                is DsbotApi.Result.BotError -> {
                    failed = true
                    val unsupported = r.code == "bot/not-found"
                    val text = BotManage.errorText(r.code, r.message)
                    if (unsupported || load !is UsLoad.Ok) load = UsLoad.Failed(text, unsupported) else error = text
                }
                is DsbotApi.Result.Failure -> {
                    failed = true
                    val text = BotManage.errorText(null, null)
                    if (load is UsLoad.Ok) error = text else load = UsLoad.Failed(text, false)
                }
            }
            if ((load as? UsLoad.Failed)?.unsupported == true) break
            delay(Usage.pollDelayMs((load as? UsLoad.Ok)?.view?.scanning == true, failed))
        }
    }

    when (val l = load) {
        UsLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is UsLoad.Failed -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (l.unsupported) {
                Text(tr("此远端不支持用量", "This server does not support usage"), style = MaterialTheme.typography.titleMedium)
                Text(tr("升级电脑上的 DS Bot 后可在这里查看 Token 用量。", "Update DS Bot on the computer to see token usage here."), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(l.text, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { load = UsLoad.Loading; retryTick++ }, modifier = Modifier.padding(top = 8.dp)) { Text(tr("重试", "Retry")) }
            }
        }
        is UsLoad.Ok -> UsageBody(state, l.view, focusId, range, by, error, onRange = { range = it }, onBy = { by = it }, onPickSpan = { picking = true },
            onOpen = { id -> onOpen(id, range, by) })
    }

    val view = (load as? UsLoad.Ok)?.view
    if (picking && view != null) {
        val oldest = Usage.oldest(view)
        val today = Usage.today(view)
        fun ms(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
        val ds = rememberDateRangePickerState(
            initialSelectedStartDateMillis = range.from?.let(::ms),
            initialSelectedEndDateMillis = range.to?.let(::ms),
            initialDisplayedMonthMillis = ms((range.to ?: today).withDayOfMonth(1)),
            yearRange = oldest.year..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = day(utcTimeMillis).let { it >= oldest && it <= today }
                override fun isSelectableYear(year: Int): Boolean = year in oldest.year..today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(enabled = ds.selectedStartDateMillis != null, onClick = {
                    val a = ds.selectedStartDateMillis?.let(::day)
                    if (a != null) range = Usage.span(a, ds.selectedEndDateMillis?.let(::day) ?: a)
                    picking = false
                }) { Text(tr("确定", "OK")) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(tr("取消", "Cancel")) } },
        ) {
            DateRangePicker(state = ds, title = { Text(tr("选择日期", "Pick dates"), modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }, modifier = Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageBody(
    state: Json.Value.Object?,
    view: Usage.View,
    focusId: String?,
    range: Usage.Range,
    by: Usage.By,
    error: String?,
    onRange: (Usage.Range) -> Unit,
    onBy: (Usage.By) -> Unit,
    onPickSpan: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val card = MaterialTheme.colorScheme.surfaceContainer
    val ink = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val faces = remember(view, state, card, ink, primary) { Faces(view, state, lerp(card, ink, 0.30f), lerp(card, ink, 0.16f), primary) }
    val focus = remember(focusId) { Usage.Focus.parse(focusId) }
    val shaped = remember(view, range, by, focus) { Usage.shape(view, range, by, focus) }
    val heat = remember(view, shaped) { Usage.heat(view, shaped.keep) }
    val thisYear = remember { LocalDate.now().year }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        // 时间范围
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in Usage.PRESETS) FilterChip(selected = !range.custom && range.id == p.id, onClick = { onRange(p) }, label = { Text(Usage.rangeLabel(p.id)) })
            FilterChip(
                selected = range.custom, onClick = onPickSpan,
                label = { Text(if (range.custom) "📅 " + Usage.spanText(range.from!!, range.to!!, thisYear) else "📅 " + Usage.rangeLabel("custom")) },
            )
        }
        if (view.scanning) UsNote(tr("还在读取较早的聊天记录，数字可能还会变大。", "Still reading older chats; totals may grow."))
        if (error != null) UsNote(error, isError = true)
        if (view.rows.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (view.scanning) tr("正在读取聊天记录…", "Reading your chats…") else tr("还没有用量。", "No usage yet."), style = MaterialTheme.typography.titleMedium)
                Text(tr("调用模型之后，这里会显示 Token 用量。", "Token usage shows up here after a model is called."), style = MaterialTheme.typography.bodySmall)
            }
            return
        }
        val focused = shaped.focused
        val facePaint: Color? = when {
            shaped.ownerAt >= 0 -> faces.ownerPaint(shaped.ownerAt)
            shaped.modelAt >= 0 -> faces.modelPaint(shaped.modelAt)
            else -> null
        }
        if (focused && facePaint != null) {
            val name = if (shaped.ownerAt >= 0) faces.ownerName(shaped.ownerAt) else faces.modelName(shaped.modelAt)
            val sub = if (shaped.ownerAt >= 0) faces.ownerSub(shaped.ownerAt) else faces.modelSub(shaped.modelAt)
            UsProfile(name, sub, facePaint, shaped.share, Usage.shareText(shaped.share, range, thisYear))
        }
        UsStats(range, shaped.part, thisYear)
        // 系列着色：Bot 用团队颜色、模型按名次着色、其余与其他为中性色；同色重复时逐次变淡（网页端 repeats）
        val fills = run {
            val seen = HashMap<Color, Int>()
            shaped.series.associate { s ->
                val o = s.ownerAt
                val m = s.modelAt
                val paint = when {
                    s.key == "all" -> facePaint ?: faces.rest
                    s.key == "more" -> faces.more
                    s.key == "rest" -> faces.rest
                    o != null -> faces.ownerPaint(o)
                    m != null -> faces.modelPaint(m)
                    else -> faces.rest
                }
                val n = seen[paint] ?: 0
                seen[paint] = n + 1
                s.key to soften(paint, card, listOf(1f, 0.6f, 0.36f)[n % 3])
            }
        }
        val names = shaped.series.associate { s ->
            val o = s.ownerAt
            val m = s.modelAt
            s.key to when {
                s.key == "more" -> if (by == Usage.By.BOT) tr("其余 Bot", "More Bots") else tr("其余模型", "More models")
                s.key == "rest" -> tr("其他", "Other")
                o != null -> faces.ownerName(o)
                m != null -> faces.modelName(m)
                else -> ""
            }
        }
        UsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Token 走势", "Tokens over time"), style = MaterialTheme.typography.titleSmall)
                Text("  " + Usage.unitLabel(range.unit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (!focused) {
                    FilterChip(selected = by == Usage.By.BOT, onClick = { onBy(Usage.By.BOT) }, label = { Text(tr("按 Bot", "By Bot")) })
                    Spacer(Modifier.width(4.dp))
                    FilterChip(selected = by == Usage.By.MODEL, onClick = { onBy(Usage.By.MODEL) }, label = { Text(tr("按模型", "By model")) })
                }
            }
            val tipFills = if (focused && facePaint != null) Usage.PARTS.zip(listOf(1f, 0.6f, 0.32f)).associate { (k, st) -> k to soften(facePaint, card, st) } else fills
            val tipNames = if (focused) Usage.PARTS.associateWith { Usage.partLabel(it) } else names
            UsChart(range, shaped.columns, fills, tipFills, tipNames)
            if (!focused && (shaped.series.size > 1 || shaped.series.firstOrNull()?.let { it.ownerAt != null || it.modelAt != null } == true)) {
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (s in shaped.series) {
                        val target = s.ownerAt?.let { Usage.Focus.owner(view.owners[it]).id } ?: s.modelAt?.let { Usage.Focus.model(view.models[it]).id }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.let { m -> if (target != null) m.clickable { onOpen(target) } else m }.padding(vertical = 4.dp)) {
                            Box(Modifier.size(8.dp).background(fills[s.key] ?: faces.rest, CircleShape))
                            Text(" " + (names[s.key] ?: ""), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        val whole = shaped.part.total.total
        val nothing: @Composable () -> Unit = { Text(tr("这段时间没有用量。", "Nothing in this range."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val rowFill: (Color) -> Color = { p -> if (facePaint != null) soften(facePaint, card, 0.8f) else soften(p, card) }
        @Composable
        fun ownerRow(at: Int, sum: Usage.Sum) {
            val paint = faces.ownerPaint(at)
            UsRow(faces.ownerName(at), faces.ownerSub(at), paint, fills["o$at"] ?: rowFill(paint), sum, whole) { onOpen(Usage.Focus.owner(view.owners[at]).id) }
        }
        @Composable
        fun modelRow(at: Int, sum: Usage.Sum) {
            val paint = faces.modelPaint(at)
            UsRow(faces.modelName(at), faces.modelSub(at), paint, fills["m$at"] ?: rowFill(paint), sum, whole) { onOpen(Usage.Focus.model(view.models[at]).id) }
        }
        if (focused && facePaint != null) {
            val kinds = Usage.ranked(shaped.part.kinds)
            if (shaped.ownerAt >= 0) {
                val used = Usage.ranked(shaped.part.models)
                UsListCard(tr("模型", "Models"), used.size) { if (used.isEmpty()) nothing() else used.forEach { (at, s) -> modelRow(at, s) } }
            } else {
                val owners = Usage.ranked(shaped.part.owners)
                UsListCard(tr("谁在用", "Who used it"), owners.size) { if (owners.isEmpty()) nothing() else owners.forEach { (at, s) -> ownerRow(at, s) } }
            }
            val botFocus = shaped.ownerAt >= 0 && Usage.isBot(view, shaped.ownerAt)
            UsListCard(tr("用途", "What for"), null) {
                if (kinds.isEmpty()) nothing() else kinds.forEach { (at, s) ->
                    UsRow(Usage.kindLabel(view.kinds[at], botFocus), "", null, soften(facePaint, card, 0.8f), s, whole, null)
                }
            }
            UsHeat(heat, facePaint, card)
        } else {
            val owners = Usage.ranked(shaped.all.owners)
            val bots = owners.filter { Usage.isBot(view, it.first) }
            val rest = owners.filter { !Usage.isBot(view, it.first) }
            UsListCard(tr("Bot", "Bots"), bots.size) {
                if (owners.isEmpty()) nothing()
                bots.forEach { (at, s) -> ownerRow(at, s) }
                if (bots.isNotEmpty() && rest.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                rest.forEach { (at, s) -> ownerRow(at, s) }
            }
            val used = Usage.ranked(shaped.all.models)
            UsListCard(tr("模型", "Models"), used.size) { if (used.isEmpty()) nothing() else used.forEach { (at, s) -> modelRow(at, s) } }
            UsHeat(heat, MaterialTheme.colorScheme.primary, card)
        }
    }
}

@Composable
private fun UsNote(text: String, isError: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

@Composable
private fun UsCard(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun UsListCard(title: String, count: Int?, content: @Composable () -> Unit) = UsCard {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (count != null) Text("  $count", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    content()
}

/** 详情页顶部：占全部用量比例的圆环、名称与副标题。 */
@Composable
private fun UsProfile(name: String, sub: String, paint: Color, share: Double, shareText: String) = UsCard {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 6.dp.toPx()
                drawArc(track, 0f, 360f, false, style = Stroke(w), topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w))
                val sweep = (share.coerceIn(0.0, 1.0) * 360).toFloat()
                if (sweep > 0f) drawArc(paint, -90f, sweep, false, style = Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round), topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w))
            }
            Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", fontWeight = FontWeight.Bold, color = paint)
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(shareText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 统计卡（网页端 Stats）：总 Token 与升降、缓存命中率、输出、调用次数。 */
@Composable
private fun UsStats(range: Usage.Range, s: Usage.Summary, thisYear: Int) = UsCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(Usage.lastText(range, thisYear), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val change = s.change
        if (change != null) {
            Text("  " + Usage.changeText(change), style = MaterialTheme.typography.labelMedium,
                color = if (change >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
        }
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(Usage.compact(s.total.total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(" Token", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
    }
    if (s.change != null) Text(Usage.compareText(range), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        for ((label, value) in listOf(
            tr("缓存命中率", "Cache hit rate") to "${Usage.hitRate(s.total)}%",
            tr("输出", "Output") to Usage.compact(s.total.output),
            tr("调用次数", "Calls") to Usage.full(s.total.calls),
        )) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** 堆叠柱状图（网页端 Chart）：点按柱子显示该时段明细，再点同一根或空白处关闭。 */
@Composable
private fun UsChart(range: Usage.Range, columns: List<Usage.ColumnView>, fills: Map<String, Color>, tipFills: Map<String, Color>, tipNames: Map<String, String>) {
    var picked by remember(range, columns.size) { mutableStateOf<Int?>(null) }
    val top = Usage.niceTop(columns.maxOfOrNull { it.total } ?: 0)
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val stub = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val count = columns.size
    Canvas(
        Modifier.fillMaxWidth().height(190.dp).padding(top = 8.dp).pointerInput(columns) {
            detectTapGestures { p ->
                val gutter = 34.dp.toPx()
                val w = (size.width - gutter) / count.coerceAtLeast(1)
                val at = ((p.x - gutter) / w).toInt()
                picked = if (p.x < gutter || at !in 0 until count || picked == at) null else at
            }
        },
    ) {
        val gutter = 34.dp.toPx()
        val tickH = 16.dp.toPx()
        val plotH = size.height - tickH
        for (level in listOf(1f, 0.5f, 0f)) {
            val y = plotH * (1 - level)
            drawLine(grid, Offset(gutter, y), Offset(size.width, y), strokeWidth = 1f)
            val text = if (level == 0f) "0" else Usage.compact(Math.round(top * level.toDouble()))
            val m = measurer.measure(text, label)
            drawText(m, topLeft = Offset(0f, (y - m.size.height / 2f).coerceIn(0f, plotH - m.size.height)))
        }
        val w = (size.width - gutter) / count.coerceAtLeast(1)
        val bar = (w * 0.7f).coerceAtLeast(1f)
        columns.forEachIndexed { at, c ->
            val x = gutter + at * w + (w - bar) / 2
            if (picked == at) drawRect(highlight, Offset(gutter + at * w, 0f), Size(w, plotH))
            if (c.total > 0) {
                var y = plotH
                val totalH = plotH * c.total / top
                for ((key, value) in c.parts) {
                    if (value <= 0) continue
                    val h = totalH * value / c.total
                    drawRect(fills[key] ?: grid, Offset(x, y - h), Size(bar, h))
                    y -= h
                }
            } else {
                drawRoundRect(stub, Offset(x, plotH - 2.dp.toPx()), Size(bar, 2.dp.toPx()), CornerRadius(1f))
            }
            if (Usage.showsTick(range, at, count)) {
                val m = measurer.measure(c.tick, label)
                val cx = (gutter + at * w + w / 2 - m.size.width / 2f).coerceIn(gutter, size.width - m.size.width)
                drawText(m, topLeft = Offset(cx, plotH + 2.dp.toPx()))
            }
        }
    }
    val c = picked?.let { columns.getOrNull(it) } ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(c.title, style = MaterialTheme.typography.labelLarge)
            Text(tr("${Usage.full(c.total)} Token", "${Usage.full(c.total)} tokens"), style = MaterialTheme.typography.bodySmall)
            for ((key, value) in c.tip.filter { it.second > 0 }.sortedByDescending { it.second }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(tipFills[key] ?: grid, CircleShape))
                    Text(" " + (tipNames[key] ?: key), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(Usage.compact(value), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** 排行行（网页端 Row2）：名称、副标题、占比条与紧凑数字和百分比。 */
@Composable
private fun UsRow(name: String, sub: String, glyph: Color?, fill: Color, sum: Usage.Sum, whole: Long, onClick: (() -> Unit)?) {
    val width = if (whole > 0) maxOf(if (sum.total > 0) 0.02f else 0f, sum.total.toFloat() / whole) else 0f
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 6.dp)) {
        if (glyph != null) {
            Box(Modifier.size(28.dp).background(glyph.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", style = MaterialTheme.typography.labelMedium, color = glyph)
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (sub.isNotEmpty()) Text("  $sub", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(width.coerceIn(0f, 1f)).height(4.dp).background(fill, RoundedCornerShape(2.dp)))
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 10.dp)) {
            Text(Usage.compact(sum.total), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("${Usage.percent(sum.total.toDouble(), whole.toDouble())}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 近 53 周每日活跃度（网页端 Heat）：点按某天显示当天 Token 数。 */
@Composable
private fun UsHeat(heat: Usage.Heat, paint: Color, card: Color) = UsCard {
    var picked by remember(heat) { mutableStateOf<Usage.HeatCell?>(null) }
    val levels = listOf(0.3f, 0.52f, 0.76f, 1f)
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val todayRing = MaterialTheme.colorScheme.outline
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("活跃度", "Activity"), style = MaterialTheme.typography.titleSmall)
        Text("  " + Usage.activeText(heat.active), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // 高度 = 月份标签行 + 7 行方格（方格边长 = 宽度 / 53），避免方格溢出到图例上
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp)) {
    Canvas(
        Modifier.fillMaxWidth().height(12.dp + maxWidth / Usage.HEAT_WEEKS * 7).pointerInput(heat) {
            detectTapGestures { p ->
                val cell = size.width / Usage.HEAT_WEEKS.toFloat()
                val top = 12.dp.toPx()
                val week = (p.x / cell).toInt()
                val day = ((p.y - top) / cell).toInt()
                // 月份标签行（p.y < top）不对应任何方格
                val hit = if (p.y < top) null else heat.cells.firstOrNull { it.week == week && it.day == day && !it.future }
                picked = if (hit == null || hit == picked) null else hit
            }
        },
    ) {
        val cell = size.width / Usage.HEAT_WEEKS
        val top = 12.dp.toPx()
        val gap = (cell * 0.16f).coerceAtLeast(1f)
        for ((week, d) in heat.monthLabels) {
            val m = measurer.measure(Usage.monthLabel(d), label)
            if (week * cell + m.size.width <= size.width) drawText(m, topLeft = Offset(week * cell, 0f))
        }
        for (c in heat.cells) {
            if (c.future) continue
            val color = if (c.level > 0) soften(paint, card, levels[c.level - 1]) else empty
            val o = Offset(c.week * cell + gap / 2, top + c.day * cell + gap / 2)
            val s = Size(cell - gap, cell - gap)
            drawRoundRect(color, o, s, CornerRadius(cell * 0.2f))
            if (c.date == heat.today || c == picked) drawRoundRect(todayRing, o, s, CornerRadius(cell * 0.2f), style = Stroke(1.5f))
        }
    }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        val p = picked
        if (p != null) {
            Text("${Usage.dayTitle(p.date)} · " + tr("${Usage.full(p.tokens)} Token", "${Usage.full(p.tokens)} tokens"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(tr("少 ", "Light "), style = MaterialTheme.typography.labelSmall)
        for (l in levels) Box(Modifier.padding(1.dp).size(10.dp).background(soften(paint, card, l), RoundedCornerShape(2.dp)))
        Text(tr(" 多", " Heavy"), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Bot 详情的「用量」行（usage-summary；远端 hello 声明该能力时由调用方显示）。行立即出现（读取中显示「…」），
 * 摘要读取失败时说明读不到，仍可点按进入该 Bot 的用量详情。
 */
@Composable
fun BotUsageRow(botId: String, api: ManageApi, onOpen: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    // null 为读取中；Result 为失败或已解码
    var summary by remember(botId) { mutableStateOf<Result<Usage.BotSummary>?>(null) }
    LaunchedEffect(botId) {
        val r = api.readNow("usage-summary", Usage.Requests.summary(botId, zone))
        summary = (r as? DsbotApi.Result.Ok)?.let { Usage.decodeSummary(it.value) }?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException())
    }
    val hint = when (val s = summary?.getOrNull()) {
        null -> if (summary == null) "…" else tr("暂时读不到用量摘要", "Usage summary is unavailable right now")
        else -> tr(
            "24 小时 ${Usage.compact(s.day.tokens)} · 7 天 ${Usage.compact(s.week.tokens)} · 30 天 ${Usage.compact(s.month.tokens)} Token",
            "24 hours ${Usage.compact(s.day.tokens)} · 7 days ${Usage.compact(s.week.tokens)} · 30 days ${Usage.compact(s.month.tokens)} tokens",
        )
    }
    SectionTitle(tr("用量", "Usage"))
    ValueRow(tr("用量", "Usage"), "›", hint = hint, onClick = onOpen)
}
