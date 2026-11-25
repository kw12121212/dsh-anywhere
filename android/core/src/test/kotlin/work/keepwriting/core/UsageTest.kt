package work.keepwriting.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 用量（dsbot-usage）。分桶、汇总、上期、排行、堆叠与数字文案以「网页端原算法对同一夹具的计算结果」为准：
 * usage-expected-*.json 由 resources/dsbot/usage-ref.mjs 在 node 中直接运行 ds-bot 0.3.10 client.js 里的
 * src/client/usage-data.js 原文生成（node usage-ref.mjs <client.js> <夹具>）。夹具 usage-view.json 由 runSpike 的
 * ProbesUsage 从本机真实服务取得并结构脱敏；usage-view-span.json 为跨 14 个月、10 个 Bot、9 个模型的确定性合成数据。
 */
class UsageTest {
    @BeforeEach
    fun resetLang() {
        I18n.source = null
        I18n.lang = ChatBars.Lang.ZH
    }

    private fun resource(name: String) = checkNotNull(UsageTest::class.java.getResource("/dsbot/$name")).readText(Charsets.UTF_8)

    private fun fixture(name: String): Json.Value = (Json.parse(resource(name)) as Json.Value.Object).members["value"]!!

    private fun view(name: String) = checkNotNull(Usage.decode(fixture(name)))

    // ---- 解码 ----

    @Test
    fun `真实结构夹具解码出 owners、models、kinds 与全部行`() {
        val v = view("usage-view.json")
        assertEquals(16, v.owners.size)
        assertEquals(mapOf("other" to 1, "bot" to 12, "room" to 3), v.owners.groupingBy { it.kind }.eachCount())
        assertTrue(v.owners.single { it.key == "room:room-15" }.deleted)
        assertEquals(3, v.models.size)
        assertEquals("Provider 1", v.models[0].providerName)
        assertNull(v.models[2].providerName)
        assertEquals(listOf("chat", "session-title", "group", "memory", "compaction", "side", "image"), v.kinds)
        assertEquals(48, v.rows.size)
        assertEquals("2026-11-25 13", v.now)
        assertFalse(v.scanning)
        val r = v.rows.first()
        assertEquals(Usage.Row("2026-11-24 16", 4, 0, 3, 1000, 4000, 100, 200, 1), r)
        assertEquals(5300, r.tokens)
        // 结构脱敏：真实名称、键与模型 id 一律替换为序号形态（ProbesUsage.fixtureOf）
        assertTrue(v.owners.all { it.kind == "other" || Regex("^Owner \\d+$").matches(it.name) })
        assertTrue(v.owners.all { Regex("^(other|bot-\\d+|room:room-\\d+|agent:agent-\\d+)$").matches(it.key) })
        assertTrue(v.models.all { Regex("^provider-\\d+/model-\\d+$").matches(it.key) && Regex("^Model \\d+$").matches(it.name) })
    }

    @Test
    fun `畸形行被跳过，畸形 owner 保留下标，数值非法按 0`() {
        val v = checkNotNull(
            Usage.decode(
                Json.parse(
                    """
                    {"owners":[{"key":"bot-1","kind":"bot","name":"A"},7,{"key":"x","kind":"weird"}],"models":[{"key":"p/m","provider":"p","model":"m","name":""}],
                     "kinds":["chat",3],"now":"2026-11-25 13","scanning":true,"rows":[
                      ["2026-11-25 10",0,0,0,10,20,30,40,1],
                      ["2026-11-25 10",3,0,0,10,20,30,40,1],
                      ["2026-11-25 10",0,1,0,10,20,30,40,1],
                      ["2026-11-25",0,0,0,10,20,30,40,1],
                      ["2026-11-25 11",0,0,0,10],
                      "nope",
                      ["2026-11-25 12",2,0,1,-5,1.5,"x",null,2]
                    ]}
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(listOf("bot-1", "?1", "x"), v.owners.map { it.key })
        assertEquals("other", v.owners[2].kind)
        assertEquals(listOf("chat", "other"), v.kinds)
        assertTrue(v.scanning)
        assertEquals(2, v.rows.size)
        assertEquals(Usage.Row("2026-11-25 12", 2, 0, 1, 0, 0, 0, 0, 2), v.rows[1])
        assertEquals("m", Usage.modelName(v.models[0]))
        assertNull(Usage.decode(Json.parse("""{"owners":[],"now":"bad"}""")))
        assertNull(Usage.decode(Json.parse("[]")))
        // 缺数组时按空处理
        assertEquals(0, Usage.decode(Json.parse("""{"now":"2026-11-25 13"}"""))!!.rows.size)
    }

    @Test
    fun `usage-summary 解码与命中率`() {
        val s = checkNotNull(Usage.decodeSummary(fixture("usage-summary.json")))
        assertEquals("bot-1", s.botId)
        assertEquals(10_000 + 200_000 + 1_000L, s.day.tokens)
        assertEquals(300, s.month.calls)
        assertEquals(listOf("provider-1/model-1"), s.models.map { it.key })
        assertNull(Usage.decodeSummary(Json.parse("""{"day":{}}""")))
        val bare = Usage.decodeSummary(Json.parse("""{"botId":"b","day":null}"""))!!
        assertEquals(0, bare.day.tokens)
    }

    @Test
    fun `请求体带手机时区`() {
        val z = ZoneId.of("Asia/Shanghai")
        assertEquals("""{"timeZone":"Asia/Shanghai"}""", Usage.Requests.view(z))
        assertEquals("""{"botId":"b\"1","timeZone":"Asia/Shanghai"}""", Usage.Requests.summary("b\"1", z))
    }

    // ---- 与网页端算法逐项比对 ----

    private fun obj(v: Json.Value?) = v as Json.Value.Object
    private fun arr(v: Json.Value?) = (v as Json.Value.Array).items
    private fun num(v: Json.Value?) = (v as Json.Value.NumberValue).raw.toDouble().toLong()
    private fun str(v: Json.Value?) = (v as? Json.Value.StringValue)?.value

    private fun rangeOf(r: Json.Value.Object): Usage.Range {
        val from = str(r.members["from"])
        val to = str(r.members["to"])
        return if (from != null && to != null) Usage.span(LocalDate.parse(from), LocalDate.parse(to)) else Usage.preset(str(r.members["name"])!!)
    }

    private fun sumList(s: Usage.Sum) = listOf(s.input, s.cacheRead, s.output, s.calls, s.total)

    private fun assertShape(label: String, expected: Json.Value.Object, actual: Usage.Summary) {
        val m = expected.members
        assertEquals(arr(m["keys"]).map { str(it) }, actual.columns.map { it.bucket.key }, "$label keys")
        assertEquals(arr(m["sums"]).map { s -> arr(s).map { num(it) } }, actual.columns.map { sumList(it.sum) }, "$label sums")
        assertEquals(
            arr(m["stacks"]).map { s -> arr(s).map { p -> arr(p).let { str(it[0])!! to num(it[1]) } } },
            actual.columns.map { c -> c.stacks.entries.map { it.key to it.value } },
            "$label stacks",
        )
        assertEquals(arr(m["total"]).map { num(it) }, sumList(actual.total), "$label total")
        assertEquals(num(m["previous"]), actual.previous, "$label previous")
        fun rank(k: String) = arr(m[k]).map { p -> arr(p).let { num(it[0]).toInt() to num(it[1]) } }
        assertEquals(rank("owners"), Usage.ranked(actual.owners).map { it.first to it.second.total }, "$label owners")
        assertEquals(rank("models"), Usage.ranked(actual.models).map { it.first to it.second.total }, "$label models")
        assertEquals(rank("kinds"), Usage.ranked(actual.kinds).map { it.first to it.second.total }, "$label kinds")
    }

    private fun compareWithWeb(viewName: String, expectedName: String) {
        val v = view(viewName)
        val expected = obj(Json.parse(resource(expectedName)))
        val ranges = arr(expected.members["ranges"]).map { obj(it) }
        assertEquals(12, ranges.size)
        for (r in ranges) {
            val name = str(r.members["name"])!!
            val range = rangeOf(r)
            assertEquals(str(r.members["unit"]), range.unit.name.lowercase(), "$name unit")
            assertEquals(num(r.members["count"]).toInt(), range.count, "$name count")
            assertEquals(num(r.members["stride"]).toInt(), range.stride, "$name stride")
            assertEquals(num(r.members["days"]).toInt(), range.days, "$name days")
            val isBot = { row: Usage.Row -> Usage.isBot(v, row.owner) }
            assertShape("$name byBot", obj(r.members["byBot"]), Usage.summarize(v, range) { if (isBot(it)) "o${it.owner}" else "rest" })
            assertShape("$name byModel", obj(r.members["byModel"]), Usage.summarize(v, range) { "m${it.model}" })
            assertShape("$name owner1", obj(r.members["owner1"]), Usage.summarize(v, range, { it.owner == 1 }))
            assertShape("$name model2", obj(r.members["model2"]), Usage.summarize(v, range, { it.model == 2 }))
        }
        for (c in arr(expected.members["compact"])) {
            val (n, text) = arr(c)
            assertEquals(str(text), Usage.compact(num(n)), "compact ${num(n)}")
        }
        for (p in arr(expected.members["percent"])) {
            val (a, b, text) = arr(p)
            val part = (a as Json.Value.NumberValue).raw.toDouble()
            val whole = (b as Json.Value.NumberValue).raw.toDouble()
            assertEquals(str(text), Usage.percent(part, whole), "percent $part/$whole")
        }
    }

    @Test
    fun `合成跨年数据：各预设与自定义范围的数字与网页端算法一致`() = compareWithWeb("usage-view-span.json", "usage-expected-span.json")

    @Test
    fun `真实结构数据：各范围的数字与网页端算法一致`() = compareWithWeb("usage-view.json", "usage-expected-real.json")

    // ---- 范围、窗口与系列 ----

    @Test
    fun `自定义范围按天数选单位并可交换起止`() {
        val d = LocalDate.of(2026, 11, 25)
        assertEquals(Usage.Unit.HOUR, Usage.span(d.minusDays(1), d).unit)
        assertEquals(Usage.Unit.DAY, Usage.span(d.minusDays(2), d).unit)
        assertEquals(Usage.Unit.DAY, Usage.span(d.minusDays(61), d).unit)
        assertEquals(Usage.Unit.WEEK, Usage.span(d.minusDays(62), d).unit)
        assertEquals(Usage.Unit.MONTH, Usage.span(d.minusDays(182), d).unit)
        val swapped = Usage.span(d, d.minusDays(10))
        assertEquals(d.minusDays(10), swapped.from)
        assertEquals(d, swapped.to)
        assertEquals("7d", Usage.preset("bogus").id)
    }

    @Test
    fun `预设窗口：上期与本期相接，to 为 now`() {
        val w = Usage.window(Usage.preset("24h"), "2026-11-25 13")
        assertEquals("2026-11-24 14", w.from)
        assertEquals("2026-11-23 14", w.before)
        assertEquals("2026-11-25 13", w.to)
        assertEquals(24, w.buckets.size)
        val m = Usage.window(Usage.preset("12m"), "2026-11-25 13")
        assertEquals("2025-12", m.buckets.first().key)
        assertEquals("2025-12-01 00", m.from)
        assertEquals("2024-12-01 00", m.before)
        val wk = Usage.window(Usage.preset("12w"), "2026-11-25 13")
        assertEquals("2026-11-23", wk.buckets.last().key)
        val c = Usage.window(Usage.span(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10)), "2026-11-25 13")
        assertEquals("2026-10-22 00", c.before)
        assertEquals("2026-11-10 23", c.to)
    }

    @Test
    fun `总览按 Bot：前 7 个 Bot、其余 Bot 与其他，其余为未单列 Bot 之和`() {
        val v = view("usage-view-span.json")
        val s = Usage.shape(v, Usage.preset("12m"), Usage.By.BOT, null)
        assertFalse(s.focused)
        val keys = s.series.map { it.key }
        assertEquals(9, keys.size)
        assertEquals(listOf("more", "rest"), keys.takeLast(2))
        assertTrue(keys.take(7).all { it.startsWith("o") })
        for ((i, c) in s.columns.withIndex()) {
            val stacks = s.all.columns[i].stacks
            val shown = keys.toSet()
            assertEquals(stacks.filter { (k, _) -> k != "rest" && k !in shown }.values.sum(), c.parts.first { it.first == "more" }.second)
            assertEquals(c.total, c.parts.sumOf { it.second }, "柱高等于各段之和")
            assertEquals(c.parts, c.tip)
        }
        val ranked = Usage.ranked(s.all.owners).filter { Usage.isBot(v, it.first) }.take(7).map { "o${it.first}" }
        assertEquals(ranked, keys.take(7))
    }

    @Test
    fun `总览按模型：前 7 个模型与其余模型`() {
        val v = view("usage-view-span.json")
        val s = Usage.shape(v, Usage.preset("30d"), Usage.By.MODEL, null)
        assertEquals(8, s.series.size)
        assertEquals("more", s.series.last().key)
        assertTrue(s.series.take(7).all { it.modelAt != null })
        s.columns.forEach { assertEquals(it.total, it.parts.sumOf { p -> p.second }) }
    }

    @Test
    fun `详情页：单一系列、明细为输出输入缓存命中、占比`() {
        val v = view("usage-view-span.json")
        val focus = Usage.Focus.parse("owner:bot-1")!!
        val s = Usage.shape(v, Usage.preset("30d"), Usage.By.BOT, focus)
        assertTrue(s.focused)
        assertEquals(1, s.ownerAt)
        assertEquals(listOf("all"), s.series.map { it.key })
        val c = s.columns.first { it.total > 0 }
        assertEquals(listOf("all" to c.total), c.parts)
        assertEquals(listOf("output", "input", "cacheRead"), c.tip.map { it.first })
        assertEquals(s.part.total.total.toDouble() / s.all.total.total, s.share, 1e-12)
        val m = Usage.shape(v, Usage.preset("30d"), Usage.By.BOT, Usage.Focus.parse("model:p2/m2"))
        assertEquals(1, m.modelAt)
        assertEquals("model:p2/m2", Usage.Focus.model(v.models[1]).id)
        // 不存在的对象回到总览
        assertFalse(Usage.shape(v, Usage.preset("7d"), Usage.By.BOT, Usage.Focus.parse("owner:nope")).focused)
        assertNull(Usage.Focus.parse("bot:x"))
        assertNull(Usage.Focus.parse(null))
    }

    @Test
    fun `空范围：升降为 null`() {
        val v = view("usage-view.json")
        val s = Usage.summarize(v, Usage.preset("12m"))
        assertNull(s.change)
        val withPrev = Usage.summarize(view("usage-view-span.json"), Usage.preset("7d"))
        assertNotNull(withPrev.change)
        assertEquals(Math.round((withPrev.total.total - withPrev.previous).toDouble() / withPrev.previous * 100), withPrev.change)
        assertEquals("↑ 12%", Usage.changeText(12))
        assertEquals("↓ 3%", Usage.changeText(-3))
    }

    @Test
    fun `模型着色名次按全部历史总数`() {
        val v = view("usage-view.json")
        assertEquals(mapOf(0 to 0), Usage.modelRanks(v))
        val span = view("usage-view-span.json")
        val ranks = Usage.modelRanks(span)
        assertEquals((0 until 9).toSet(), ranks.values.toSet())
        val life = span.rows.groupBy { it.model }.mapValues { e -> e.value.sumOf { it.tokens } }
        assertEquals(life.maxBy { it.value }.key, ranks.entries.first { it.value == 0 }.key)
        val owners = Usage.ownerRanks(span)
        val ownerLife = span.rows.groupBy { it.owner }.mapValues { e -> e.value.sumOf { it.tokens } }
        assertEquals(ownerLife.keys, owners.keys)
        assertEquals(ownerLife.maxBy { it.value }.key, owners.entries.first { it.value == 0 }.key)
    }

    // ---- 热力图与选日 ----

    @Test
    fun `热力图：53 周、峰值为 4 级、未来日标记、按对象过滤`() {
        val v = view("usage-view-span.json")
        val h = Usage.heat(v) { true }
        assertEquals(53 * 7, h.cells.size)
        assertEquals(LocalDate.of(2026, 11, 25), h.today)
        assertEquals(LocalDate.of(2025, 11, 24), h.cells.first().date)
        assertEquals(DayOfWeekMonday, h.cells.first().date.dayOfWeek.value)
        assertEquals(4, h.cells.maxOf { it.level })
        assertTrue(h.cells.filter { it.date > h.today }.all { it.future && it.level == 0 })
        val peak = h.cells.maxOf { it.tokens }
        assertTrue(h.cells.filter { it.tokens == peak }.all { it.level == 4 })
        // 刚过峰值 1/16 的天为 2 级（ceil(4*sqrt(x))）
        h.cells.filter { it.tokens > 0 }.forEach { c ->
            assertEquals(maxOf(1, Math.ceil(4 * Math.sqrt(c.tokens.toDouble() / peak)).toInt()), c.level)
        }
        assertEquals(h.cells.count { it.tokens > 0 }, h.active)
        assertTrue(h.monthLabels.isNotEmpty())
        assertTrue(h.monthLabels.all { (week, d) -> d.dayOfMonth <= 7 && week > 0 })
        val one = Usage.heat(v) { it.owner == 1 }
        assertTrue(one.cells.sumOf { it.tokens } < h.cells.sumOf { it.tokens })
    }

    private val DayOfWeekMonday = 1

    @Test
    fun `选日最早日期为首条记录月份与 12 个月前的较早者`() {
        assertEquals(LocalDate.of(2025, 9, 1), Usage.oldest(view("usage-view-span.json")))
        assertEquals(LocalDate.of(2025, 11, 1), Usage.oldest(view("usage-view.json")))
    }

    @Test
    fun `轮询间隔与刻度显示`() {
        assertEquals(3_000, Usage.pollDelayMs(scanning = true, failed = false))
        assertEquals(20_000, Usage.pollDelayMs(scanning = false, failed = false))
        assertEquals(10_000, Usage.pollDelayMs(scanning = true, failed = true))
        val p = Usage.preset("24h")
        assertTrue(Usage.showsTick(p, 23, 24))
        assertTrue(Usage.showsTick(p, 17, 24))
        assertFalse(Usage.showsTick(p, 22, 24))
        val c = Usage.span(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))
        assertTrue(Usage.showsTick(c, 0, c.count))
        assertEquals(c.stride == 1, Usage.showsTick(c, 1, c.count))
        assertEquals(1, Usage.niceTop(0))
        assertEquals(15_000, Usage.niceTop(12_345))
        assertEquals(1_000_000, Usage.niceTop(810_000))
        assertEquals(100, Usage.niceTop(100))
    }

    // ---- 文案 ----

    @Test
    fun `中文文案`() {
        val d = LocalDate.of(2026, 11, 25)
        val r7 = Usage.preset("7d")
        assertEquals("最近 7 天", Usage.lastText(r7, 2026))
        assertEquals("与再往前 7 天 相比", Usage.compareText(r7))
        val span = Usage.span(LocalDate.of(2026, 11, 1), d)
        assertEquals("11月1日 – 11月25日", Usage.lastText(span, 2026))
        assertEquals("2025年12月30日 – 2026年1月2日", Usage.spanText(LocalDate.of(2025, 12, 30), LocalDate.of(2026, 1, 2), 2026))
        assertEquals("11月25日", Usage.spanText(d, d, 2026))
        assertEquals("与前一天相比", Usage.compareText(Usage.span(d, d)))
        assertEquals("与再往前 25 天相比", Usage.compareText(span))
        assertEquals("占最近 30 天全部用量的 42%", Usage.shareText(0.42, Usage.preset("30d"), 2026))
        assertEquals("占 11月1日 – 11月25日 全部用量的 <1%", Usage.shareText(0.001, span, 2026))
        assertEquals("近一年有 3 天在用", Usage.activeText(3))
        assertEquals("近一年有 1 天在用", Usage.activeText(1))
        assertEquals("单聊", Usage.kindLabel("chat", botFocus = true))
        assertEquals("对话", Usage.kindLabel("chat", botFocus = false))
        assertEquals("侧边对话", Usage.kindLabel("btw", false))
        assertEquals("future", Usage.kindLabel("future", false))
        assertEquals("按周", Usage.unitLabel(Usage.Unit.WEEK))
        assertEquals("缓存命中", Usage.partLabel("cacheRead"))
        val hourBucket = Usage.Bucket(LocalDateTime.of(2026, 11, 25, 23, 0), null, "2026-11-25 23")
        assertEquals("11月25日 23:00–00:00", Usage.titleOf(Usage.preset("24h"), hourBucket, 2026))
        assertEquals("23:00", Usage.tickOf(Usage.preset("24h"), hourBucket))
        val midnight = Usage.Bucket(LocalDateTime.of(2026, 11, 25, 0, 0), null, "2026-11-25 00")
        assertEquals("11/25", Usage.tickOf(Usage.span(d.minusDays(1), d), midnight))
        val dayBucket = Usage.Bucket(d.atStartOfDay(), null, "2026-11-25")
        assertEquals("11月25日周三", Usage.titleOf(r7, dayBucket, 2026))
        assertEquals("11/25", Usage.tickOf(r7, dayBucket))
        val week = Usage.Bucket(LocalDate.of(2026, 11, 23).atStartOfDay(), null, "2026-11-23")
        assertEquals("11月23日 – 11月29日", Usage.titleOf(Usage.preset("12w"), week, 2026))
        val month = Usage.Bucket(LocalDate.of(2026, 11, 1).atStartOfDay(), null, "2026-11")
        assertEquals("2026年11月", Usage.titleOf(Usage.preset("12m"), month, 2026))
        assertEquals("11月", Usage.tickOf(Usage.preset("12m"), month))
        val clipped = Usage.Bucket(LocalDate.of(2026, 11, 1).atStartOfDay(), LocalDate.of(2026, 11, 26).atStartOfDay(), "2026-11")
        assertEquals("11月1日 – 11月25日", Usage.titleOf(Usage.preset("12m"), clipped, 2026))
        assertEquals("1,234,567", Usage.full(1_234_567))
    }

    @Test
    fun `英文文案`() {
        I18n.lang = ChatBars.Lang.EN
        val d = LocalDate.of(2026, 11, 25)
        assertEquals("Last 12 weeks", Usage.lastText(Usage.preset("12w"), 2026))
        assertEquals("Compared with the 24 hours before", Usage.compareText(Usage.preset("24h")))
        assertEquals("Nov 1 – Nov 25", Usage.spanText(LocalDate.of(2026, 11, 1), d, 2026))
        assertEquals("Dec 30, 2025 – Jan 2, 2026", Usage.spanText(LocalDate.of(2025, 12, 30), LocalDate.of(2026, 1, 2), 2026))
        assertEquals("42% of all usage in the last 30 days", Usage.shareText(0.42, Usage.preset("30d"), 2026))
        assertEquals("Wed, November 25", Usage.dayTitle(d))
        assertEquals("November 2026", Usage.titleOf(Usage.preset("12m"), Usage.Bucket(LocalDate.of(2026, 11, 1).atStartOfDay(), null, "2026-11"), 2026))
        assertEquals("Nov", Usage.monthLabel(d))
        assertEquals("Active on 1 day in the last year", Usage.activeText(1))
    }

    @Test
    fun `owner 与模型的名称和副标题`() {
        val bot = Usage.Owner("b1", "bot", "b1", "旧名", false)
        assertEquals("新名", Usage.ownerName(bot, "新名"))
        assertEquals("旧名", Usage.ownerName(bot, null))
        assertEquals("已删除的 Bot", Usage.ownerName(bot.copy(name = ""), null))
        assertEquals("研究", Usage.ownerSub(bot, "新名", " 研究 "))
        assertEquals("", Usage.ownerSub(bot, "研究", "研究"))
        assertEquals("已删除", Usage.ownerSub(bot.copy(deleted = true), "x", "y"))
        val room = Usage.Owner("room:r", "room", "r", "", true)
        assertEquals("已删除的群聊", Usage.ownerName(room, null))
        assertEquals("已删除", Usage.ownerSub(room, "", null))
        assertEquals("群聊", Usage.ownerSub(room.copy(deleted = false), "", null))
        assertEquals("DSH Agent", Usage.ownerName(Usage.Owner("agent:a", "agent", "a", "", false), null))
        assertEquals("其他", Usage.ownerName(Usage.Owner("other", "other", null, "", false), null))
        val roster = Usage.roster(Json.parse("""{"bots":[{"id":"b1","name":"研究","role":"r","color":"violet"},{"id":"b2"},{"name":"无 id"}]}""") as Json.Value.Object)
        assertEquals(mapOf("b1" to Usage.RosterBot("研究", "r", "violet"), "b2" to Usage.RosterBot("", "", null)), roster)
        assertEquals(emptyMap<String, Usage.RosterBot>(), Usage.roster(null))
        val model = Usage.Model("p/m", "p", "m", "", null)
        assertEquals("m", Usage.modelName(model))
        assertEquals("未知模型", Usage.modelName(model.copy(model = "")))
        assertEquals("p", Usage.modelSub(model))
        assertEquals("P", Usage.modelSub(model.copy(providerName = "P")))
    }
}
