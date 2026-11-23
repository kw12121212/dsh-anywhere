package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dsbot-chat-cache：缓存、滚动记忆、单飞、行身份与回到最新按钮。 */
class ChatCacheTest {
    private fun entry(vararg items: String) = ChatCache.Entry(items.toList(), null, "s")

    @Test
    fun keyIncludesHostKindAndId() {
        assertEquals("h:13080:bot:a", ChatCache.key("h:13080", "bot", "a"))
        assertEquals(":room:r", ChatCache.key(null, "room", "r"))
        assertTrue(ChatCache.key("h1", "bot", "a") != ChatCache.key("h2", "bot", "a"))
        assertTrue(ChatCache.key("h", "bot", "a") != ChatCache.key("h", "room", "a"))
    }

    @Test
    fun lruEvictsLeastRecentlyUsed() {
        val c = ChatCache<String>(capacity = 3)
        c.put("a", entry("1"))
        c.put("b", entry("2"))
        c.put("c", entry("3"))
        assertEquals("1", c["a"]!!.items[0]) // 访问 a，b 变成最旧
        c.put("d", entry("4"))
        assertNull(c["b"])
        assertEquals(listOf("c", "a", "d"), c.keys())
        assertEquals(3, c.size)
    }

    @Test
    fun defaultCapacityIsSixteen() {
        val c = ChatCache<String>()
        for (i in 1..20) c.put("k$i", entry("$i"))
        assertEquals(ChatCache.CAPACITY, c.size)
        assertNull(c["k4"])
        assertEquals("5", c["k5"]!!.items[0])
    }

    @Test
    fun putExistingKeyRefreshesRecency() {
        val c = ChatCache<String>(capacity = 2)
        c.put("a", entry("1"))
        c.put("b", entry("2"))
        c.put("a", ChatCache.Entry(listOf("1b"), null, "s", extra = "公告"))
        c.put("c", entry("3")) // b 最旧被淘汰
        assertNull(c["b"])
        assertEquals("公告", c["a"]!!.extra)
    }

    @Test
    fun invalidateDropsEntry() {
        val c = ChatCache<String>()
        c.put("a", entry("1"))
        c.invalidate("a")
        assertNull(c["a"])
        c.invalidate("missing") // 不存在也不报错
    }

    @Test
    fun scrollMemoryCapacityAndValidation() {
        val m = ScrollMemory(capacity = 2)
        m.save("a", 3, 10)
        m.save("b", 5, 0)
        m.save("c", 7, 2)
        assertNull(m["a"])
        assertEquals(ScrollMemory.Position(7, 2), m["c"])
        m.save("d", -1, 0) // 非法位置不记
        assertNull(m["d"])
        m.save("e", 0, -3)
        assertNull(m["e"])
        m.forget("c")
        assertNull(m["c"])
        assertEquals(50, ScrollMemory.CAPACITY)
    }

    @Test
    fun flightStartsQueuesOnceThenIgnores() {
        val f = ReadFlight()
        val s = f.begin("a") as ReadFlight.Begin.Start
        assertEquals(ReadFlight.Begin.Queued, f.begin("a"))
        assertEquals(ReadFlight.Begin.Ignored, f.begin("a"))
        assertEquals(ReadFlight.Finish(stale = false, rerun = true), f.finish(s.gen))
        val s2 = f.begin("a") as ReadFlight.Begin.Start // 重跑
        assertEquals(ReadFlight.Finish(stale = false, rerun = false), f.finish(s2.gen))
    }

    @Test
    fun switchingTargetMakesOldResultStale() {
        val f = ReadFlight()
        val a = f.begin("a") as ReadFlight.Begin.Start
        f.begin("a") // 排队
        val b = f.begin("b") as ReadFlight.Begin.Start // 换目标：立即开始，旧排队作废
        assertTrue(b.gen > a.gen)
        assertEquals("b", f.current)
        assertEquals(ReadFlight.Finish(stale = true, rerun = false), f.finish(a.gen))
        assertEquals(ReadFlight.Finish(stale = false, rerun = false), f.finish(b.gen))
    }

    @Test
    fun afterFinishSameTargetStartsAgain() {
        val f = ReadFlight()
        val a = f.begin("a") as ReadFlight.Begin.Start
        f.finish(a.gen)
        assertTrue(f.begin("a") is ReadFlight.Begin.Start)
    }

    @Test
    fun resetDropsInFlightResultAndStartsFresh() {
        val f = ReadFlight()
        val a = f.begin("a") as ReadFlight.Begin.Start
        f.begin("a") // 排队
        f.reset() // 分段切换
        val b = f.begin("a") as ReadFlight.Begin.Start // 同目标立即重新开始，不再排队
        assertEquals(ReadFlight.Finish(stale = true, rerun = false), f.finish(a.gen))
        assertEquals(ReadFlight.Finish(stale = false, rerun = false), f.finish(b.gen))
    }

    @Test
    fun mergeUnchangedFirstPageKeepsEarlier() {
        val current = listOf("e1", "e2", "p1", "p2", "p3")
        val r = ChatRefresh.merge(current, listOf("p1", "p2", "p3"))
        assertEquals(current, r.items)
        assertTrue(r.keepCursor)
        assertFalse(r.changed)
    }

    @Test
    fun mergeWindowMovedWithOverlapKeepsEarlierAndAppends() {
        // 翻过更早记录后来了 2 条新消息：首页窗口前移 2 条，仍与当前列表重叠 → 保留更早记录与游标
        val current = listOf("e1", "e2", "p1", "p2", "p3", "p4")
        val r = ChatRefresh.merge(current, listOf("p2", "p3", "p4", "n1", "n2"))
        assertEquals(listOf("e1", "e2", "p1", "p2", "p3", "p4", "n1", "n2"), r.items)
        assertTrue(r.keepCursor)
        assertTrue(r.changed)
    }

    @Test
    fun mergeReplacesLiveTailThatDiffers() {
        // 实时追加的条目（流式定格无序号）与服务端条目不同：重叠段之后整体换成新首页
        val current = listOf("e1", "p1", "p2", "p3", "live")
        val r = ChatRefresh.merge(current, listOf("p1", "p2", "p3", "final", "n1"))
        assertEquals(listOf("e1", "p1", "p2", "p3", "final", "n1"), r.items)
        assertTrue(r.keepCursor)
    }

    @Test
    fun mergeNoOverlapResets() {
        val current = listOf("e1", "p1", "p2", "p3")
        val r = ChatRefresh.merge(current, listOf("x1", "x2", "x3"))
        assertEquals(listOf("x1", "x2", "x3"), r.items)
        assertFalse(r.keepCursor)
        assertTrue(r.changed)
    }

    @Test
    fun mergeEmptyFreshMeansNewEmptyPart() {
        // 新开的空分段：不能当作「未变化」保留旧分段内容
        val r = ChatRefresh.merge(listOf("old1", "old2"), emptyList())
        assertEquals(emptyList(), r.items)
        assertFalse(r.keepCursor)
        assertTrue(r.changed)
        assertFalse(ChatRefresh.merge(emptyList<String>(), emptyList()).changed)
    }

    @Test
    fun mergeForceResetIgnoresOverlap() {
        val r = ChatRefresh.merge(listOf("e1", "p1", "p2"), listOf("p1", "p2"), forceReset = true)
        assertEquals(listOf("p1", "p2"), r.items)
        assertFalse(r.keepCursor)
    }

    @Test
    fun mergeRequiresRunToAvoidCoincidentalMatch() {
        // 「ok」在更早处偶然出现一次，但之后不连续：不能当作重叠点
        val current = listOf("ok", "a", "b", "c", "d")
        val r = ChatRefresh.merge(current, listOf("ok", "x", "y", "z"))
        assertFalse(r.keepCursor)
        assertEquals(listOf("ok", "x", "y", "z"), r.items)
        // 选最靠后的合格重叠点
        assertEquals(3, ChatRefresh.overlapStart(listOf("a", "b", "c", "a", "b", "c"), listOf("a", "b", "c", "d")))
        // 新首页本身不少于 3 条时，末尾不足 3 条的一致不算重叠（窗口可能已整体越过）
        assertEquals(-1, ChatRefresh.overlapStart(listOf("x", "y", "p1", "p2"), listOf("p1", "p2", "n1")))
        assertEquals(-1, ChatRefresh.overlapStart(listOf("a", "b", "c", "last"), listOf("last", "n1", "n2", "n3")))
        // 新首页本身不足 3 条：全部一致到末尾即可
        assertEquals(2, ChatRefresh.overlapStart(listOf("x", "y", "p1", "p2"), listOf("p1", "p2")))
    }

    @Test
    fun mergePicksLongestRunWithRepeatedToolCards() {
        // 相同工具卡 T 重复出现：末位的 T 不能当重叠点，真实重叠在倒数第 3 条
        val t = ChatRender.Item.ToolCard("bash", null)
        val m41 = ChatRender.Item.Message(fromUser = false, markdown = "m41", turn = 1, seq = 41)
        val x = ChatRender.Item.Message(fromUser = false, markdown = "x", turn = 1, seq = 42)
        val e = ChatRender.Item.SystemLine("earlier")
        val current = listOf<ChatRender.Item>(e, t, m41, t)
        val r = ChatRefresh.merge(current, listOf(t, m41, t, x))
        assertEquals(listOf<ChatRender.Item>(e, t, m41, t, x), r.items)
        assertTrue(r.keepCursor)
    }

    @Test
    fun mergeKeepsLocalUnsentItems() {
        // 读取与发送并发：快照不含刚发出的乐观消息（无序号），合并后仍保留在末尾
        val isLocal = { s: String -> s.startsWith("local:") }
        val current = listOf("e1", "p1", "p2", "p3", "local:hi")
        val r = ChatRefresh.merge(current, listOf("p1", "p2", "p3", "n1"), isLocal = isLocal)
        assertEquals(listOf("e1", "p1", "p2", "p3", "n1", "local:hi"), r.items)
        // 新首页已含同一条（按 matches 判定）则不重复
        val matches = { a: String, b: String -> a.removePrefix("local:") == b }
        val r2 = ChatRefresh.merge(current, listOf("p1", "p2", "p3", "hi"), isLocal = isLocal, matches = matches)
        assertEquals(listOf("e1", "p1", "p2", "p3", "hi"), r2.items)
        // 无重叠整体替换时同样保留；分段切换的强制重置不保留
        assertEquals(listOf("x1", "x2", "x3", "local:hi"), ChatRefresh.merge(current, listOf("x1", "x2", "x3"), isLocal = isLocal).items)
        assertEquals(listOf("x1"), ChatRefresh.merge(current, listOf("x1"), forceReset = true, isLocal = isLocal).items)
    }

    @Test
    fun rowIdentityReusesEqualItems() {
        val old = listOf("x", "y", "z")
        assertContentEquals(intArrayOf(0, 1, 2, -1), RowIdentity.match(old, listOf("x", "y", "z", "new")))
        assertContentEquals(intArrayOf(1, 2), RowIdentity.match(old, listOf("y", "z"))) // 首页窗口前移
        assertContentEquals(intArrayOf(), RowIdentity.match(old, emptyList()))
        assertContentEquals(intArrayOf(-1), RowIdentity.match(emptyList(), listOf("a")))
    }

    @Test
    fun rowIdentityDuplicatesMatchOneToOne() {
        val m = RowIdentity.match(listOf("ok", "x", "ok"), listOf("ok", "ok", "ok"))
        assertContentEquals(intArrayOf(0, 2, -1), m)
    }

    @Test
    fun rowIdentityWorksWithChatItems() {
        val a = ChatRender.Item.Message(fromUser = true, markdown = "hi", turn = 1, seq = 1)
        val b = ChatRender.Item.SystemLine("sys")
        val m = RowIdentity.match(listOf(a, b), listOf(a.copy(), ChatRender.Item.SystemLine("other"), b))
        assertContentEquals(intArrayOf(0, -1, 1), m)
    }

    @Test
    fun jumpButtonCountsOnlyWhileAway() {
        val j = JumpButton(thresholdPx = 600f)
        j.onAppended(3) // 在底部：不计数
        assertNull(j.label())
        j.onDistance(1200f)
        assertTrue(j.away)
        assertEquals("回到最新", j.label())
        j.onAppended(2)
        j.onAppended(0)
        assertEquals("2 条新消息", j.label())
        j.onDistance(100f) // 手动滑回底部附近：清零
        assertFalse(j.away)
        assertEquals(0, j.newCount)
        assertNull(j.label())
    }

    @Test
    fun jumpButtonDoesNotFlashBackWhileScrollingDown() {
        val j = JumpButton(600f)
        j.onDistance(3000f)
        j.onJumped()
        j.onDistance(2000f) // 动画途中仍很远：不重新显示
        assertNull(j.label())
        j.onDistance(10f) // 到底
        j.onDistance(900f) // 之后用户再上翻：恢复正常判断
        assertEquals("回到最新", j.label())
    }

    @Test
    fun interruptedJumpRestoresNormalJudgement() {
        val j = JumpButton(600f)
        j.onDistance(3000f)
        j.onJumped()
        j.onJumpEnded() // 动画被用户上滑打断
        j.onDistance(2500f)
        assertEquals("回到最新", j.label())
    }

    @Test
    fun jumpButtonResetsOnJump() {
        val j = JumpButton(600f)
        j.onDistance(601f)
        j.onAppended(5)
        j.onJumped()
        assertNull(j.label())
        j.onDistance(600f) // 恰好等于阈值不算远（同时结束跳转态）
        assertFalse(j.away)
    }

    @Test
    fun distanceEstimate() {
        assertEquals(0f, ScrollDistance.belowPx(0, -1, 0, 0, 100f))
        assertEquals(0f, ScrollDistance.belowPx(10, 9, 900, 1000, 100f)) // 末项完全可见
        assertEquals(50f, ScrollDistance.belowPx(10, 9, 1050, 1000, 100f)) // 末项超出 50px
        assertEquals(320f, ScrollDistance.belowPx(10, 6, 1020, 1000, 100f)) // 20 + 3×100
        assertEquals(20f, ScrollDistance.belowPx(10, 6, 1020, 1000, -5f)) // 平均高异常按 0
    }
}
