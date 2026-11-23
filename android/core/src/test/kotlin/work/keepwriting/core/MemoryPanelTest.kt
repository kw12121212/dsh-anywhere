package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dsbot-memory：按真实形态脱敏的 memory-view / memory-history 夹具解码、分组排序、移动目标、校验与请求体。 */
class MemoryPanelTest {
    private fun text(name: String) = checkNotNull(MemoryPanelTest::class.java.getResource("/dsbot/$name")).readText(Charsets.UTF_8)
    private fun value(name: String): Json.Value = (Json.parse(text(name)) as Json.Value.Object).members["value"]!!
    private fun obj(t: String) = Json.parse(t) as Json.Value.Object
    private val bot = MemoryPanel.Owner.Bot("session-bot-1")
    private val room = MemoryPanel.Owner.Room("session-room-1")

    @Test
    fun botViewFixture() {
        val v = assertNotNull(MemoryPanel.decodeView(value("memory-view-bot.json")))
        assertEquals(4, v.entries.size)
        assertEquals(300, v.entryLimit)
        assertNull(v.summary)
        assertFalse(v.summarizing)
        assertEquals("bot:session-bot-1", v.keys["own"])
        assertEquals(3, v.places.size)
        val t = assertNotNull(v.tidy)
        assertEquals("tidy-1", t.batch)
        // 上游 memory-tidy.js 写 merged/moved/retired；界面按语言翻译
        assertEquals(listOf("merged", "moved", "retired"), t.items.map { it.first })
        assertEquals(listOf("合并", "移动", "淘汰"), t.items.map { MemoryPanel.tidyOpLabel(it.first) })
        assertEquals("other", MemoryPanel.tidyOpLabel("other"))
        assertTrue("memory-ask" in MemoryPanel.SLOW_ENDPOINTS)
        // 分组：own 在前、team 在后；组内置顶在前
        val groups = MemoryPanel.groups(v)
        assertEquals(listOf("own", "team"), groups.map { it.scope })
        assertEquals(listOf("m-2", "m-1"), groups[0].entries.map { it.id })
        assertTrue(groups[1].entries.first().expired)
        assertEquals("2026-12-31", groups[0].entries[0].until)
        for (s in listOf("own", "team", "group")) assertTrue(MemoryPanel.scopeLabel(s).isNotEmpty())
    }

    @Test
    fun roomViewEmptyAndMalformed() {
        val v = assertNotNull(MemoryPanel.decodeView(value("memory-view-room-empty.json")))
        assertTrue(v.entries.isEmpty())
        assertTrue(MemoryPanel.groups(v).isEmpty())
        assertNull(v.tidy)
        // 不是视图（缺 entries）、条目缺 text：降级
        assertNull(MemoryPanel.decodeView(obj("""{"botId":"x"}""")))
        assertNull(MemoryPanel.decodeView(Json.Value.NullValue))
        val odd = MemoryPanel.decodeView(obj("""{"entries":[{"scope":"own"},{"text":"t"}],"tidy":{"items":[]}}"""))!!
        assertEquals(1, odd.entries.size)
        assertEquals(MemoryPanel.ENTRY_MAX_DEFAULT, odd.entryLimit)
        // tidy 缺 batch 无法撤销：视为没有
        assertNull(odd.tidy)
    }

    @Test
    fun sameIdAcrossScopes() {
        // 上游 entryId 只在同一记忆范围内唯一（无 meta.id 时取正文 sha256 前 4 位）：own 与 team 可同 id、同正文
        val v = MemoryPanel.decodeView(
            obj("""{"entries":[{"scope":"own","topic":null,"text":"同一句","id":"a1b2"},{"scope":"team","topic":null,"text":"同一句","id":"a1b2"}]}"""),
        )!!
        val groups = MemoryPanel.groups(v)
        assertEquals(listOf("own", "team"), groups.map { it.scope })
        // 列表键带范围与位置，跨组不冲突
        val keys = groups.flatMap { g -> g.entries.mapIndexed { i, e -> MemoryPanel.rowKey(g.scope, i, e) } }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun moveTargetsExcludeCurrent() {
        val v = MemoryPanel.decodeView(value("memory-view-bot.json"))!!
        val fromOwn = MemoryPanel.moveTargets(v, "own").map { it.key }
        assertEquals(listOf("bot:session-bot-2", "group:session-room-1", "team"), fromOwn)
        val fromTeam = MemoryPanel.moveTargets(v, "team").map { it.key }
        assertTrue("bot:session-bot-1" in fromTeam && "team" !in fromTeam)
    }

    @Test
    fun entryLimit() {
        assertNotNull(MemoryPanel.entryProblem("  ", 300))
        assertNotNull(MemoryPanel.entryProblem("x".padEnd(301, 'x'), 300))
        assertNull(MemoryPanel.entryProblem(" " + "x".padEnd(300, 'x') + " ", 300))
    }

    @Test
    fun historyFixtureAndErrors() {
        val h = MemoryPanel.decodeHistory(value("memory-history.json"))
        assertEquals(listOf("h-3", "h-2", "h-1"), h.rows.map { it.id })
        assertTrue(h.rows[1].restored)
        assertEquals("h-1", h.more)
        assertEquals("user", h.rows[0].by)
        assertTrue(MemoryPanel.decodeHistory(obj("""{"rows":[],"more":null}""")).rows.isEmpty())
        assertNull(MemoryPanel.decodeHistory(obj("""{"rows":[],"more":null}""")).more)
        for (op in listOf("add", "remove", "replace")) assertTrue(MemoryPanel.opLabel(op) != op)
        val err = assertIs<DsbotApi.Result.BotError>(DsbotApi.parseResponse(200, text("memory-restore-unknown.json")))
        assertEquals("That change is not in the change log of this memory.", BotManage.errorText(err.code, err.message))
    }

    @Test
    fun askResult() {
        val view = text("memory-view-bot.json").let { (Json.parse(it) as Json.Value.Object).members["value"]!! }
        val r = MemoryPanel.decodeAsk(
            Json.Value.Object(
                linkedMapOf(
                    "reply" to Json.Value.StringValue("已改为周四"),
                    "changes" to Json.parse("""[{"op":"edit","scope":"own","text":"周四发周报","before":"周五发周报","ok":true},{"op":"add","scope":"team","text":"x","ok":false,"error":"looks like a key"}]""")!!,
                    "view" to view,
                ),
            ),
        )
        assertEquals("已改为周四", r.reply)
        assertEquals("周五发周报", r.changes[0].before)
        assertFalse(r.changes[1].ok)
        assertEquals("looks like a key", r.changes[1].error)
        assertEquals(4, r.view!!.entries.size)
        assertNull(MemoryPanel.decodeAsk(null).view)
    }

    @Test
    fun requestBodies() {
        val R = MemoryPanel.Requests
        val e = MemoryPanel.Entry("own", "work", "周报\"五\"", "m-2", null, null, null, false, false)
        val plain = e.copy(topic = null)
        assertEquals("""{"botId":"session-bot-1"}""", R.view(bot))
        assertEquals("""{"roomId":"session-room-1"}""", R.view(room))
        assertEquals("""{"botId":"session-bot-1","text":"改成周四"}""", R.ask(bot, " 改成周四 "))
        assertEquals("""{"botId":"session-bot-1","scope":"own","topic":"work","text":"周报\"五\"","pin":true}""", R.pin(bot, e, true))
        assertEquals("""{"botId":"session-bot-1","scope":"own","topic":null,"text":"周报\"五\""}""", R.forget(bot, plain))
        assertEquals("""{"botId":"session-bot-1","scope":"own","topic":"work","text":"周报\"五\"","next":"周四"}""", R.edit(bot, e, " 周四 "))
        assertEquals("""{"roomId":"session-room-1","scope":"group","text":"新条目"}""", R.add(room, "group", "新条目"))
        assertEquals("""{"botId":"session-bot-1","scope":"own","topic":"work","text":"周报\"五\"","to":"team"}""", R.move(bot, e, "team"))
        assertEquals("""{"botId":"session-bot-1","topic":null,"text":"周报\"五\"","always":true}""", R.layer(bot, plain, true))
        assertEquals("""{"botId":"session-bot-1"}""", R.history(bot, null))
        assertEquals("""{"botId":"session-bot-1","before":"h-1"}""", R.history(bot, "h-1"))
        assertEquals("""{"botId":"session-bot-1","id":"h-1"}""", R.restore(bot, "h-1"))
        val tidy = MemoryPanel.decodeView(value("memory-view-bot.json"))!!.tidy!!
        assertEquals("""{"botId":"session-bot-1","scope":"own","batch":"tidy-1"}""", R.tidyUndo(bot, tidy))
        // 提问截断到 2000 字
        assertEquals(2000 + """{"botId":"session-bot-1","text":""}""".length, R.ask(bot, "x".padEnd(2500, 'x')).length)
    }

    @Test
    fun enabledFlag() {
        assertTrue(MemoryPanel.enabled(obj("""{"memory":true}""")))
        assertTrue(MemoryPanel.enabled(obj("""{}""")))
        assertFalse(MemoryPanel.enabled(obj("""{"memory":false}""")))
    }
}
