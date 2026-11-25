package work.keepwriting.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 用量（dsbot-usage）：usage 与 usage-summary 应答解码，以及网页端 Settings → Usage 的全部客户端计算——
 * 预设与自定义范围的分桶（RANGES/spanRange/spanBuckets/bucketsOf/spanOf）、汇总与上期对比（summarize）、
 * 按 Bot/按模型的系列与合并、排行、命中率与百分比、紧凑数字、近 53 周热力图、刻度与时段标题文案。
 * 复刻 ds-bot 0.3.10 client.js 的 src/client/usage-data.js 与 usage-page.js（tech/dsbot-api 的 usage-endpoints）。
 *
 * 小时键 'YYYY-MM-DD HH' 已由服务端按请求的 timeZone 生成；这里一律当作无时区的本地日期时间运算
 *（对应网页端把键当 UTC 毫秒处理），不再做时区换算。
 */
object Usage {
    // ---- 应答 ----

    data class Owner(val key: String, val kind: String, val id: String?, val name: String, val deleted: Boolean)

    data class Model(val key: String, val provider: String, val model: String, val name: String, val providerName: String?)

    /** 一行：[hour, owner, model, kind, input, cacheRead, cacheWrite, output, calls]。 */
    data class Row(
        val hour: String,
        val owner: Int,
        val model: Int,
        val kind: Int,
        val input: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val output: Long,
        val calls: Long,
    ) {
        /** 总 token（网页端 tokensOf）。 */
        val tokens: Long get() = input + cacheRead + cacheWrite + output
    }

    data class View(
        val owners: List<Owner>,
        val models: List<Model>,
        val kinds: List<String>,
        val rows: List<Row>,
        val now: String,
        val scanning: Boolean,
    )

    private val HOUR_KEY = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}$")

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value

    /** 非负整数（上游 count：安全整数且大于 0，否则 0）。 */
    private fun count(v: Json.Value?): Long? {
        val raw = (v as? Json.Value.NumberValue)?.raw ?: return null
        val d = raw.toDoubleOrNull() ?: return null
        if (d.isNaN() || d.isInfinite() || d != Math.floor(d) || d > 9.007199254740991E15) return 0
        return if (d > 0) d.toLong() else 0
    }

    private fun index(v: Json.Value?, size: Int): Int? {
        val raw = (v as? Json.Value.NumberValue)?.raw ?: return null
        val d = raw.toDoubleOrNull() ?: return null
        if (d != Math.floor(d) || d < 0 || d >= size) return null
        return d.toInt()
    }

    /**
     * usage 应答；不是对象或 now 不是小时键时 null。owners/models 的畸形条目以占位条目保留下标对齐；
     * 行的小时键或下标不合法时跳过该行，数值缺失或非法按 0 计（上游 count）。
     */
    fun decode(value: Json.Value?): View? {
        val o = value as? Json.Value.Object ?: return null
        val now = o.str("now")?.takeIf { HOUR_KEY.matches(it) } ?: return null
        fun arr(k: String) = (o.members[k] as? Json.Value.Array)?.items.orEmpty()
        val owners = arr("owners").mapIndexed { i, v ->
            val m = v as? Json.Value.Object
            Owner(
                key = m?.str("key") ?: "?$i",
                kind = m?.str("kind")?.takeIf { it in OWNER_KINDS } ?: "other",
                id = m?.str("id"),
                name = m?.str("name") ?: "",
                deleted = (m?.members?.get("deleted") as? Json.Value.BoolValue)?.value == true,
            )
        }
        val models = arr("models").mapIndexed { i, v ->
            val m = v as? Json.Value.Object
            Model(
                key = m?.str("key") ?: "?$i",
                provider = m?.str("provider") ?: "",
                model = m?.str("model") ?: "",
                name = m?.str("name") ?: "",
                providerName = m?.str("providerName"),
            )
        }
        val kinds = arr("kinds").map { (it as? Json.Value.StringValue)?.value ?: "other" }
        val rows = arr("rows").mapNotNull { v ->
            val r = (v as? Json.Value.Array)?.items ?: return@mapNotNull null
            if (r.size < 9) return@mapNotNull null
            val hour = (r[0] as? Json.Value.StringValue)?.value?.takeIf { HOUR_KEY.matches(it) } ?: return@mapNotNull null
            Row(
                hour = hour,
                owner = index(r[1], owners.size) ?: return@mapNotNull null,
                model = index(r[2], models.size) ?: return@mapNotNull null,
                kind = index(r[3], kinds.size) ?: return@mapNotNull null,
                input = count(r[4]) ?: 0,
                cacheRead = count(r[5]) ?: 0,
                cacheWrite = count(r[6]) ?: 0,
                output = count(r[7]) ?: 0,
                calls = count(r[8]) ?: 0,
            )
        }
        return View(owners, models, kinds, rows, now, (o.members["scanning"] as? Json.Value.BoolValue)?.value == true)
    }

    private val OWNER_KINDS = setOf("bot", "room", "agent", "other")

    /** usage-summary 的一段合计（上游 mobile.js totals）。 */
    data class Totals(val input: Long, val cacheRead: Long, val cacheWrite: Long, val output: Long, val calls: Long) {
        val tokens: Long get() = input + cacheRead + cacheWrite + output
    }

    data class SummaryModel(val key: String, val name: String, val totals: Totals)

    /** usage-summary：近 24 小时、7×24 小时、30×24 小时的合计与近 30 天按模型。 */
    data class BotSummary(val botId: String, val day: Totals, val week: Totals, val month: Totals, val models: List<SummaryModel>, val scanning: Boolean)

    private fun totals(v: Json.Value?): Totals {
        val o = v as? Json.Value.Object
        fun n(k: String) = count(o?.members?.get(k)) ?: 0
        return Totals(n("input"), n("cacheRead"), n("cacheWrite"), n("output"), n("calls"))
    }

    fun decodeSummary(value: Json.Value?): BotSummary? {
        val o = value as? Json.Value.Object ?: return null
        val botId = o.str("botId") ?: return null
        val models = (o.members["models"] as? Json.Value.Array)?.items.orEmpty().mapNotNull { v ->
            val m = v as? Json.Value.Object ?: return@mapNotNull null
            SummaryModel(m.str("key") ?: return@mapNotNull null, m.str("name") ?: "", totals(m))
        }
        return BotSummary(
            botId, totals(o.members["day"]), totals(o.members["week"]), totals(o.members["month"]), models,
            (o.members["scanning"] as? Json.Value.BoolValue)?.value == true,
        )
    }

    /** hello capabilities 中 Bot 用量摘要的能力名（Bot 详情「用量」行的门控）。 */
    const val SUMMARY_CAPABILITY = "usage-summary"

    /** 请求体（channel.js usage 与 mobile.js usageSummary 的参数）：一律带手机当前时区。 */
    object Requests {
        fun view(zone: ZoneId) = "{\"timeZone\":" + JsonString.of(zone.id) + "}"
        fun summary(botId: String, zone: ZoneId) = "{\"botId\":" + JsonString.of(botId) + ",\"timeZone\":" + JsonString.of(zone.id) + "}"
    }

    // ---- 范围与分桶 ----

    enum class Unit { HOUR, DAY, WEEK, MONTH }

    /** 范围：预设（id 为 24h/7d/30d/12w/12m）或自定义（id=custom，带 from/to 与天数）。 */
    data class Range(
        val id: String,
        val unit: Unit,
        val count: Int,
        val stride: Int,
        val from: LocalDate? = null,
        val to: LocalDate? = null,
        val days: Int = 0,
    ) {
        val custom: Boolean get() = from != null
    }

    val PRESETS: List<Range> = listOf(
        Range("24h", Unit.HOUR, 24, 6),
        Range("7d", Unit.DAY, 7, 1),
        Range("30d", Unit.DAY, 30, 5),
        Range("12w", Unit.WEEK, 12, 2),
        Range("12m", Unit.MONTH, 12, 1),
    )

    const val DEFAULT_RANGE = "7d"

    fun preset(id: String): Range = PRESETS.firstOrNull { it.id == id } ?: PRESETS.first { it.id == DEFAULT_RANGE }

    data class Bucket(val start: LocalDateTime, val end: LocalDateTime?, val key: String)

    private val HOUR_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH")

    fun hourOf(key: String): LocalDateTime = LocalDateTime.parse(key, HOUR_FMT)

    fun hourKey(t: LocalDateTime): String = t.format(HOUR_FMT)

    fun dayKey(d: LocalDate): String = d.toString()

    private fun monthKey(d: LocalDate): String = d.toString().take(7)

    fun mondayOf(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

    /** 自定义起止（网页端 spanRange）：顺序颠倒时交换；按天数选单位，刻度步长使刻度不超过约 12 个。 */
    fun span(from: LocalDate, to: LocalDate): Range {
        val (a, b) = if (to < from) to to from else from to to
        val days = ChronoUnit.DAYS.between(a, b).toInt() + 1
        val unit = when {
            days <= 2 -> Unit.HOUR
            days <= 62 -> Unit.DAY
            days <= 182 -> Unit.WEEK
            else -> Unit.MONTH
        }
        val base = Range("custom", unit, 0, 1, a, b, days)
        val count = spanBuckets(base).size
        val stride = if (unit == Unit.HOUR) 6 else listOf(1, 2, 3, 5, 7, 10, 14).firstOrNull { count / it.toDouble() <= 12 } ?: ceil(count / 12.0).toInt()
        return base.copy(count = count, stride = stride)
    }

    /** 自定义范围的桶（网页端 spanBuckets）：周与月的首尾桶按起止日期截断。 */
    fun spanBuckets(range: Range): List<Bucket> {
        val first = range.from!!.atStartOfDay()
        val end = range.to!!.plusDays(1).atStartOfDay()
        val out = mutableListOf<Bucket>()
        when (range.unit) {
            Unit.HOUR, Unit.DAY -> {
                var s = first
                while (s < end) {
                    val next = if (range.unit == Unit.HOUR) s.plusHours(1) else s.plusDays(1)
                    out += Bucket(s, next, if (range.unit == Unit.HOUR) hourKey(s) else dayKey(s.toLocalDate()))
                    s = next
                }
            }
            Unit.WEEK -> {
                var monday = mondayOf(range.from).atStartOfDay()
                while (monday < end) {
                    val next = monday.plusDays(7)
                    out += Bucket(maxOf(monday, first), minOf(next, end), dayKey(monday.toLocalDate()))
                    monday = next
                }
            }
            Unit.MONTH -> {
                var month = range.from.withDayOfMonth(1).atStartOfDay()
                while (month < end) {
                    val next = month.plusMonths(1)
                    out += Bucket(maxOf(month, first), minOf(next, end), monthKey(month.toLocalDate()))
                    month = next
                }
            }
        }
        return out
    }

    /** 预设范围的 2×count 个桶（网页端 bucketsOf）：前一半是上期，后一半是本期。 */
    fun presetBuckets(range: Range, now: String): List<Bucket> {
        val end = hourOf(now)
        val today = end.toLocalDate()
        return (range.count * 2 - 1 downTo 0).map { back ->
            val b = back.toLong()
            val start = when (range.unit) {
                Unit.HOUR -> end.minusHours(b)
                Unit.DAY -> today.minusDays(b).atStartOfDay()
                Unit.WEEK -> mondayOf(today).minusWeeks(b).atStartOfDay()
                Unit.MONTH -> today.withDayOfMonth(1).minusMonths(b).atStartOfDay()
            }
            val key = when (range.unit) {
                Unit.HOUR -> hourKey(start)
                Unit.MONTH -> monthKey(start.toLocalDate())
                else -> dayKey(start.toLocalDate())
            }
            Bucket(start, null, key)
        }
    }

    /** 范围的本期桶与界限（网页端 spanOf）：before ≤ 上期 < from ≤ 本期 ≤ to。 */
    data class Window(val buckets: List<Bucket>, val from: String, val to: String, val before: String)

    fun window(range: Range, now: String): Window {
        if (range.custom) {
            return Window(
                spanBuckets(range), "${range.from} 00", "${range.to} 23",
                hourKey(range.from!!.atStartOfDay().minusDays(range.days.toLong())),
            )
        }
        val all = presetBuckets(range, now)
        return Window(all.drop(range.count), hourKey(all[range.count].start), now, hourKey(all[0].start))
    }

    // ---- 汇总 ----

    /** 合计（网页端 blank/addRow）：输入含 cacheWrite，总数为四项之和。 */
    class Sum {
        var input = 0L
        var cacheRead = 0L
        var output = 0L
        var calls = 0L
        var total = 0L

        fun add(row: Row): Sum {
            input += row.input + row.cacheWrite
            cacheRead += row.cacheRead
            output += row.output
            calls += row.calls
            total += row.tokens
            return this
        }

        operator fun get(part: String): Long = when (part) {
            "output" -> output
            "input" -> input
            "cacheRead" -> cacheRead
            else -> total
        }
    }

    class Column(val bucket: Bucket, val sum: Sum, val stacks: LinkedHashMap<String, Long>)

    class Summary(
        val columns: List<Column>,
        val total: Sum,
        val previous: Long,
        val owners: LinkedHashMap<Int, Sum>,
        val models: LinkedHashMap<Int, Sum>,
        val kinds: LinkedHashMap<Int, Sum>,
    ) {
        /** 与上期相比的升降百分比；上期为 0 时 null。 */
        val change: Long? get() = if (previous > 0) Math.round((total.total - previous).toDouble() / previous * 100) else null
    }

    /** 网页端 summarize：[keep] 筛选行，[seriesOf] 给出堆叠系列键。 */
    fun summarize(view: View, range: Range, keep: (Row) -> Boolean = { true }, seriesOf: (Row) -> String = { "all" }): Summary {
        val w = window(range, view.now)
        val index = HashMap<String, Int>()
        w.buckets.forEachIndexed { at, b -> index[b.key] = at }
        val weeks = HashMap<String, String>()
        fun keyOf(hour: String): String = when (range.unit) {
            Unit.HOUR -> hour
            Unit.DAY -> hour.take(10)
            Unit.MONTH -> hour.take(7)
            Unit.WEEK -> {
                val day = hour.take(10)
                weeks.getOrPut(day) { dayKey(mondayOf(LocalDate.parse(day))) }
            }
        }
        val columns = w.buckets.map { Column(it, Sum(), LinkedHashMap()) }
        val total = Sum()
        var previous = 0L
        val owners = LinkedHashMap<Int, Sum>()
        val models = LinkedHashMap<Int, Sum>()
        val kinds = LinkedHashMap<Int, Sum>()
        for (row in view.rows) {
            val hour = row.hour
            if (hour < w.before || hour > w.to || !keep(row)) continue
            if (hour < w.from) {
                previous += row.tokens
                continue
            }
            val at = index[keyOf(hour)] ?: continue
            val col = columns[at]
            col.sum.add(row)
            total.add(row)
            val s = seriesOf(row)
            col.stacks[s] = (col.stacks[s] ?: 0L) + row.tokens
            owners.getOrPut(row.owner) { Sum() }.add(row)
            models.getOrPut(row.model) { Sum() }.add(row)
            kinds.getOrPut(row.kind) { Sum() }.add(row)
        }
        return Summary(columns, total, previous, owners, models, kinds)
    }

    /** 排行：去掉 0，按总数降序（同数保持出现顺序）。 */
    fun ranked(map: Map<Int, Sum>): List<Pair<Int, Sum>> = map.entries.filter { it.value.total > 0 }.sortedByDescending { it.value.total }.map { it.key to it.value }

    /** Bot 类 owner（网页端 isBot：bot 与 agent）。 */
    fun isBot(view: View, at: Int): Boolean = view.owners.getOrNull(at)?.kind.let { it == "bot" || it == "agent" }

    /** 全部历史按模型总数的名次（网页端 modelPaint 的着色顺序）。 */
    fun modelRanks(view: View): Map<Int, Int> {
        val life = LinkedHashMap<Int, Long>()
        for (r in view.rows) life[r.model] = (life[r.model] ?: 0L) + r.tokens
        return life.entries.sortedByDescending { it.value }.mapIndexed { rank, e -> e.key to rank }.toMap()
    }

    /** 全部历史按 owner 总数的名次（未设颜色的 Bot 按名次取调色板色，保证图表可区分）。 */
    fun ownerRanks(view: View): Map<Int, Int> {
        val life = LinkedHashMap<Int, Long>()
        for (r in view.rows) life[r.owner] = (life[r.owner] ?: 0L) + r.tokens
        return life.entries.sortedByDescending { it.value }.mapIndexed { rank, e -> e.key to rank }.toMap()
    }

    // ---- 页面形状（网页端 UsagePage 的 useMemo） ----

    const val TOP_SERIES = 7

    /** 详情对象：owner:<key> 或 model:<key>（网页端 focusOf）。 */
    data class Focus(val kind: String, val key: String) {
        val id: String get() = "$kind:$key"

        companion object {
            fun parse(id: String?): Focus? = when {
                id == null -> null
                id.startsWith("model:") -> Focus("model", id.removePrefix("model:"))
                id.startsWith("owner:") -> Focus("owner", id.removePrefix("owner:"))
                else -> null
            }

            fun owner(o: Owner) = Focus("owner", o.key)
            fun model(m: Model) = Focus("model", m.key)
        }
    }

    enum class By { BOT, MODEL }

    /** 图表系列：o<下标>、m<下标>、more（其余）、rest（非 Bot 的其他）、all（详情页单一系列）。 */
    data class Series(val key: String, val ownerAt: Int? = null, val modelAt: Int? = null)

    /** 一根柱：parts 为各系列堆叠值（顺序同系列），tip 为点按明细（详情页为输出/输入/缓存命中）。 */
    data class ColumnView(val key: String, val tick: String, val title: String, val total: Long, val parts: List<Pair<String, Long>>, val tip: List<Pair<String, Long>>)

    class Shaped(
        val ownerAt: Int,
        val modelAt: Int,
        val all: Summary,
        val part: Summary,
        val series: List<Series>,
        val columns: List<ColumnView>,
        val keep: (Row) -> Boolean,
    ) {
        val focused: Boolean get() = ownerAt >= 0 || modelAt >= 0
        /** 详情对象占全部用量的比例。 */
        val share: Double get() = if (all.total.total > 0) part.total.total.toDouble() / all.total.total else 0.0
    }

    val PARTS = listOf("output", "input", "cacheRead")

    fun shape(view: View, range: Range, by: By, focus: Focus?): Shaped {
        val ownerAt = if (focus?.kind == "owner") view.owners.indexOfFirst { it.key == focus.key } else -1
        val modelAt = if (focus?.kind == "model") view.models.indexOfFirst { it.key == focus.key } else -1
        val all = summarize(view, range) { r -> if (by == By.BOT) (if (isBot(view, r.owner)) "o${r.owner}" else "rest") else "m${r.model}" }
        val keep: (Row) -> Boolean = when {
            ownerAt >= 0 -> { r -> r.owner == ownerAt }
            modelAt >= 0 -> { r -> r.model == modelAt }
            else -> { _ -> true }
        }
        val focused = ownerAt >= 0 || modelAt >= 0
        val part = if (focused) summarize(view, range, keep) else all
        val series = if (focused) {
            listOf(Series("all"))
        } else if (by == By.BOT) {
            val owners = ranked(all.owners)
            val bots = owners.filter { isBot(view, it.first) }
            bots.take(TOP_SERIES).map { Series("o${it.first}", ownerAt = it.first) } +
                (if (bots.size > TOP_SERIES) listOf(Series("more")) else emptyList()) +
                (if (owners.any { !isBot(view, it.first) }) listOf(Series("rest")) else emptyList())
        } else {
            val used = ranked(all.models)
            used.take(TOP_SERIES).map { Series("m${it.first}", modelAt = it.first) } +
                (if (used.size > TOP_SERIES) listOf(Series("more")) else emptyList())
        }
        val shown = series.map { it.key }.toSet()
        val columns = part.columns.map { c ->
            val parts = if (focused) {
                listOf("all" to c.sum.total)
            } else {
                series.map { s ->
                    s.key to if (s.key == "more") c.stacks.filter { (k, _) -> k != "rest" && k !in shown }.values.sum() else (c.stacks[s.key] ?: 0L)
                }
            }
            val tip = if (focused) PARTS.map { it to c.sum[it] } else parts
            ColumnView(c.bucket.key, tickOf(range, c.bucket), titleOf(range, c.bucket), c.sum.total, parts, tip)
        }
        return Shaped(ownerAt, modelAt, all, part, series, columns, keep)
    }

    /** 第 [at] 根柱是否显示刻度：预设从现在往回数，自定义从首日往后数。 */
    fun showsTick(range: Range, at: Int, count: Int): Boolean = (if (range.custom) at else count - 1 - at) % range.stride == 0

    /** 柱状图纵轴上限（网页端 niceTop）。 */
    fun niceTop(max: Long): Long {
        if (max <= 0) return 1
        val step = Math.pow(10.0, Math.floor(Math.log10(max.toDouble())))
        return listOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0).map { it * step }.first { it >= max }.let { Math.round(it) }
    }

    // ---- 热力图与选日范围 ----

    const val HEAT_WEEKS = 53

    data class HeatCell(val date: LocalDate, val week: Int, val day: Int, val level: Int, val tokens: Long, val future: Boolean)

    class Heat(val today: LocalDate, val cells: List<HeatCell>, val monthLabels: List<Pair<Int, LocalDate>>, val active: Int)

    /** 近 53 周每日用量（网页端 Heat）：级别 1–4 按 sqrt(当日/峰值) 分级。 */
    fun heat(view: View, keep: (Row) -> Boolean): Heat {
        val today = hourOf(view.now).toLocalDate()
        val first = mondayOf(today).minusWeeks((HEAT_WEEKS - 1).toLong())
        val from = dayKey(first)
        val daily = LinkedHashMap<String, Long>()
        for (r in view.rows) {
            val k = r.hour.take(10)
            if (k >= from && keep(r)) daily[k] = (daily[k] ?: 0L) + r.tokens
        }
        val peak = daily.values.maxOrNull() ?: 0L
        val labels = mutableListOf<Pair<Int, LocalDate>>()
        val cells = mutableListOf<HeatCell>()
        for (week in 0 until HEAT_WEEKS) {
            val monday = first.plusWeeks(week.toLong())
            if (week > 0 && monday.month != monday.minusWeeks(1).month) labels += week to monday
            for (d in 0 until 7) {
                val date = monday.plusDays(d.toLong())
                val value = daily[dayKey(date)] ?: 0L
                val level = if (value > 0) max(1, ceil(4 * sqrt(value.toDouble() / peak)).toInt()) else 0
                cells += HeatCell(date, week, d, level, value, date > today)
            }
        }
        return Heat(today, cells, labels, daily.values.count { it > 0 })
    }

    /** 日历选日可选的最早日期：有记录的最早月份与 12 个月前两者中较早者的 1 日。 */
    fun oldest(view: View): LocalDate {
        val first = view.rows.minOfOrNull { it.hour } ?: view.now
        val firstMonth = hourOf(first).toLocalDate().withDayOfMonth(1)
        val yearAgo = hourOf(view.now).toLocalDate().withDayOfMonth(1).minusMonths(12)
        return minOf(firstMonth, yearAgo)
    }

    fun today(view: View): LocalDate = hourOf(view.now).toLocalDate()

    /** 轮询间隔（网页端 UsagePage.load）：读取失败 10 秒，仍在扫描 3 秒，平时 20 秒。 */
    fun pollDelayMs(scanning: Boolean, failed: Boolean): Long = when {
        failed -> 10_000L
        scanning -> 3_000L
        else -> 20_000L
    }

    // ---- 数字文案 ----

    private fun fixed(value: Double, digits: Int): String = BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()

    private fun trimmed(value: Double): String {
        val text = when {
            value >= 100 -> fixed(value, 0)
            value >= 10 -> fixed(value, 1)
            else -> fixed(value, 2)
        }
        return if (text.contains('.')) text.replace(Regex("\\.?0+$"), "") else text
    }

    /** 紧凑数字（网页端 compact）：1.2K、3.45M、1B。 */
    fun compact(count: Long): String {
        for ((size, unit) in listOf(1e9 to "B", 1e6 to "M", 1e3 to "K")) {
            if (count >= size * 0.9995) return trimmed(count / size) + unit
        }
        return count.toString()
    }

    /** 完整数字（网页端 full：按语言加千分位）。 */
    fun full(count: Long): String = NumberFormat.getIntegerInstance(locale()).format(count)

    /** 百分比（网页端 percent2）：不足 1 显示 <1，0 或无分母显示 0。 */
    fun percent(part: Double, whole: Double): String {
        if (!(whole > 0) || !(part > 0)) return "0"
        val v = part / whole * 100
        return if (v < 1) "<1" else Math.round(v).toString()
    }

    /** 缓存命中率（网页端 hitRate）：cacheRead /（输入含 cacheWrite + cacheRead）。 */
    fun hitRate(sum: Sum): String = percent(sum.cacheRead.toDouble(), (sum.input + sum.cacheRead).toDouble())

    /** 升降文案：↑ 12% / ↓ 3%。 */
    fun changeText(change: Long): String = (if (change >= 0) "↑ " else "↓ ") + Math.abs(change) + "%"

    // ---- 名称与标签 ----

    private fun en() = I18n.lang == ChatBars.Lang.EN

    private fun locale(): Locale = if (en()) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE

    fun rangeLabel(id: String): String = when (id) {
        "24h" -> I18n.tr("24 小时", "24 hours")
        "7d" -> I18n.tr("7 天", "7 days")
        "30d" -> I18n.tr("30 天", "30 days")
        "12w" -> I18n.tr("12 周", "12 weeks")
        "12m" -> I18n.tr("12 个月", "12 months")
        else -> I18n.tr("自定义", "Custom")
    }

    fun unitLabel(unit: Unit): String = when (unit) {
        Unit.HOUR -> I18n.tr("按小时", "Per hour")
        Unit.DAY -> I18n.tr("按天", "Per day")
        Unit.WEEK -> I18n.tr("按周", "Per week")
        Unit.MONTH -> I18n.tr("按月", "Per month")
    }

    fun partLabel(part: String): String = when (part) {
        "output" -> I18n.tr("输出", "Output")
        "input" -> I18n.tr("输入", "Input")
        "cacheRead" -> I18n.tr("缓存命中", "Cache hit")
        else -> part
    }

    /** 用途分类（网页端 kindLabel）；详情为某 Bot 时 chat 显示为单聊。 */
    fun kindLabel(kind: String, botFocus: Boolean): String = when (kind) {
        "chat" -> if (botFocus) I18n.tr("单聊", "Own chat") else I18n.tr("对话", "Conversations")
        "group" -> I18n.tr("群聊", "Group chats")
        "compaction" -> I18n.tr("压缩与交接", "Compaction and handoff")
        "image" -> I18n.tr("识图", "Image reading")
        "memory" -> I18n.tr("记忆", "Memory")
        "session-title" -> I18n.tr("标题", "Titles")
        "btw", "side" -> I18n.tr("侧边对话", "Side chat")
        "other" -> I18n.tr("其他", "Other")
        else -> kind
    }

    /** owner 显示名（网页端 ownerFace）：Bot 优先用团队中的当前名称。 */
    fun ownerName(o: Owner, rosterName: String?): String = when (o.kind) {
        "bot" -> rosterName?.takeIf { it.isNotEmpty() } ?: o.name.takeIf { it.isNotEmpty() } ?: I18n.tr("已删除的 Bot", "Deleted Bot")
        "room" -> o.name.takeIf { it.isNotEmpty() } ?: I18n.tr("已删除的群聊", "Deleted group chat")
        "agent" -> o.name.takeIf { it.isNotEmpty() } ?: "DSH Agent"
        else -> I18n.tr("其他", "Other")
    }

    /** owner 副标题：已删除；群聊；Bot 的角色（与名称相同时不显示）。 */
    fun ownerSub(o: Owner, name: String, role: String?): String = when (o.kind) {
        "bot" -> if (o.deleted) I18n.tr("已删除", "Deleted") else role?.trim().orEmpty().takeUnless { it.equals(name, ignoreCase = true) }.orEmpty()
        "room" -> if (o.deleted) I18n.tr("已删除", "Deleted") else I18n.tr("群聊", "Group chat")
        "agent" -> if (o.deleted) I18n.tr("已删除", "Deleted") else ""
        else -> ""
    }

    /** 团队中 Bot 的当前名称、角色与颜色（网页端 ownerFace 读 roster）。 */
    data class RosterBot(val name: String, val role: String, val color: String?)

    fun roster(state: Json.Value.Object?): Map<String, RosterBot> =
        (state?.members?.get("bots") as? Json.Value.Array)?.items.orEmpty().mapNotNull { it as? Json.Value.Object }
            .mapNotNull { b -> b.str("id")?.let { it to RosterBot(b.str("name") ?: "", b.str("role") ?: "", b.str("color")) } }.toMap()

    fun modelName(m: Model): String = m.name.takeIf { it.isNotEmpty() } ?: m.model.takeIf { it.isNotEmpty() } ?: I18n.tr("未知模型", "Unknown model")

    fun modelSub(m: Model): String = m.providerName ?: m.provider

    // ---- 日期文案 ----

    private fun fmt(pattern: String) = DateTimeFormatter.ofPattern(pattern, locale())

    private fun hourText(t: LocalDateTime) = String.format(Locale.ROOT, "%02d:00", t.hour)

    /** 起止日期（网页端 spanText）：任一端不在今年时带年份。 */
    fun spanText(from: LocalDate, to: LocalDate, thisYear: Int = LocalDate.now().year): String {
        val withYear = from.year != thisYear || to.year != thisYear
        val f = if (en()) fmt(if (withYear) "MMM d, yyyy" else "MMM d") else fmt(if (withYear) "yyyy年M月d日" else "M月d日")
        return if (from == to) from.format(f) else "${from.format(f)} – ${to.format(f)}"
    }

    /** 统计卡标题（网页端 lastText）。 */
    fun lastText(range: Range, thisYear: Int = LocalDate.now().year): String =
        if (range.custom) spanText(range.from!!, range.to!!, thisYear) else I18n.tr("最近 ${rangeLabel(range.id)}", "Last ${rangeLabel(range.id)}")

    /** 升降的比较对象（网页端 compareText）。 */
    fun compareText(range: Range): String = when {
        !range.custom -> I18n.tr("与再往前 ${rangeLabel(range.id)} 相比", "Compared with the ${rangeLabel(range.id)} before")
        range.days == 1 -> I18n.tr("与前一天相比", "Compared with the day before")
        else -> I18n.tr("与再往前 ${range.days} 天相比", "Compared with the ${range.days} days before")
    }

    /** 详情页占比文案。 */
    fun shareText(share: Double, range: Range, thisYear: Int = LocalDate.now().year): String {
        val p = percent(share, 1.0)
        return if (range.custom) {
            val s = spanText(range.from!!, range.to!!, thisYear)
            I18n.tr("占 $s 全部用量的 $p%", "$p% of all usage from $s")
        } else {
            I18n.tr("占最近 ${rangeLabel(range.id)}全部用量的 $p%", "$p% of all usage in the last ${rangeLabel(range.id)}")
        }
    }

    fun activeText(days: Int): String = if (days == 1) I18n.tr("近一年有 1 天在用", "Active on 1 day in the last year") else I18n.tr("近一年有 $days 天在用", "Active on $days days in the last year")

    /** 柱下刻度（网页端 tickOf）。 */
    fun tickOf(range: Range, b: Bucket): String = when (range.unit) {
        Unit.HOUR -> if (range.days > 1 && b.start.hour == 0) b.start.format(fmt("M/d")) else hourText(b.start)
        Unit.MONTH -> if (en()) b.start.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) else "${b.start.monthValue}月"
        else -> b.start.format(fmt("M/d"))
    }

    /** 点按明细的时段标题（网页端 titleOf）。 */
    fun titleOf(range: Range, b: Bucket, thisYear: Int = LocalDate.now().year): String {
        val s = b.start
        return when (range.unit) {
            Unit.HOUR -> s.format(fmt(if (en()) "MMM d" else "M月d日")) + " " + hourText(s) + "–" + hourText(s.plusHours(1))
            Unit.DAY -> dayTitle(s.toLocalDate())
            Unit.WEEK -> spanText(s.toLocalDate(), (b.end ?: s.plusDays(7)).toLocalDate().minusDays(1), thisYear)
            Unit.MONTH -> if (b.end != null && (s.dayOfMonth != 1 || b.end.dayOfMonth != 1)) {
                spanText(s.toLocalDate(), b.end.toLocalDate().minusDays(1), thisYear)
            } else {
                s.format(fmt(if (en()) "MMMM yyyy" else "yyyy年M月"))
            }
        }
    }

    /** 某天的完整标题（明细与热力格）：11月25日周二 / Tue, November 25。 */
    fun dayTitle(d: LocalDate): String = if (en()) d.format(fmt("EEE, MMMM d")) else d.format(fmt("M月d日")) + Schedules.dayName(d.dayOfWeek.value)

    fun monthLabel(d: LocalDate): String = if (en()) d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) else "${d.monthValue}月"
}
