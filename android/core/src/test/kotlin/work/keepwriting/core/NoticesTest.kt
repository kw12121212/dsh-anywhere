package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dsbot-notices-banner：真实 0.3.10 夹具解码 + 游标语义。 */
class NoticesTest {
    private fun resource(name: String) = NoticesTest::class.java.getResource("/dsbot/$name")!!.readText()

    private fun value(name: String): Json.Value {
        val o = Json.parse(resource(name)) as Json.Value.Object
        return o.members["value"] ?: o
    }

    private fun obj(text: String) = Json.parse(text)

    private fun n(seq: Long, kind: String = "message", bot: String = "b", room: String? = null) =
        Notices.Notice(seq, null, bot, "名", kind, "s", room, null, "文本")

    @Test
    fun decodesRealNoticesBatch() {
        val b = assertNotNull(Notices.decodeBatch(value("notices.json")))
        assertEquals("237ee0c9-8b6b-4495-b63d-95bd2c05da59", b.boot)
        assertEquals(4L, b.seq)
        assertFalse(b.reset)
        assertTrue(b.notices.isNotEmpty())
        assertEquals(1L, b.notices[0].seq)
        assertEquals("message", b.notices[0].kind)
        assertTrue(b.notices[0].botId.startsWith("session-"))
        assertTrue(b.notices.all { it.seq <= 4 })
    }

    @Test
    fun decodesRealResetBatch() {
        val b = assertNotNull(Notices.decodeBatch(value("notices-reset.json")))
        assertTrue(b.reset)
    }

    @Test
    fun decodesRealFeedNoticeEvent() {
        val lines = resource("feed-stream-0310.sse").lines()
        val i = lines.indexOf("event: notice")
        assertTrue(i >= 0)
        val data = lines[i + 1].removePrefix("data: ")
        val notice = assertNotNull(Notices.decode(Json.parse(data)))
        assertEquals(5L, notice.seq)
        assertEquals("message", notice.kind)
        assertTrue(Notices.shown(notice))
        assertEquals(notice.botId, Notices.target(notice))
    }

    @Test
    fun decodeRejectsMissingFields() {
        assertNull(Notices.decode(obj("""{"botId":"b","kind":"message"}""")))
        assertNull(Notices.decode(obj("""{"seq":1,"kind":"message"}""")))
        assertNull(Notices.decode(obj("""{"seq":1,"botId":"","kind":"message"}""")))
        assertNull(Notices.decode(obj("""{"seq":1,"botId":"b"}""")))
        assertNull(Notices.decode(obj("[1]")))
        assertNull(Notices.decodeBatch(obj("""{"boot":"x"}""")))
        // 批量里个别坏条目跳过
        assertEquals(1, Notices.decodeBatch(obj("""{"boot":"x","seq":2,"notices":[{"seq":2,"botId":"b","kind":"done"},{"bad":1}]}"""))!!.notices.size)
    }

    @Test
    fun kindWhitelist() {
        for (k in listOf("question", "approval", "message", "group", "done")) assertTrue(Notices.shown(n(1, k)))
        assertFalse(Notices.shown(n(1, "view")))
        assertFalse(Notices.shown(n(1, "weird")))
    }

    @Test
    fun bannerTextAndTarget() {
        // 私聊提问：无 title，摘要取 text
        val q = Notices.Notice(1, null, "bot-b", "研究员", "question", "s", null, null, "要选哪个方案？")
        assertEquals("研究员 · 提问：要选哪个方案？", Notices.banner(q))
        val m = Notices.Notice(2, null, "bot-b", null, "message", "s", null, null, "第一行\n第二行   第三行")
        assertEquals("Bot · 回复：第一行 第二行 第三行", Notices.banner(m))
        val long = Notices.Notice(3, null, "b", "B", "message", null, null, null, "字".repeat(200))
        assertEquals("B · 回复：".length + Notices.SUMMARY_MAX, Notices.banner(long).length)
        assertEquals("B · 等你审批", Notices.banner(Notices.Notice(4, null, "b", "B", "approval", null, null, null, null)))
        assertEquals("room-1", Notices.target(n(5, "group", room = "room-1")))
        assertEquals("b", Notices.target(n(6, "group", room = "")))
    }

    @Test
    fun groupNoticeTitleIsRoomNameNotSummary() {
        // 上游 roomLineAdded：{kind:group, roomId, title: room.name, text: 消息正文}（源码核对形态）
        val group = Notices.Notice(7, null, "bot-r", "研究员", "group", null, "room-1", "项目群", "第二阶段已完成")
        assertEquals("项目群 · 研究员 · 群消息：第二阶段已完成", Notices.banner(group))
        // 群任务完成：text 为空，只显示来源与类型
        val done = Notices.Notice(8, null, "bot-r", "研究员", "done", null, "room-1", "项目群", "")
        assertEquals("项目群 · 研究员 · 任务完成", Notices.banner(done))
        // 群内审批：approvalWaiting 的 text 是工具名
        val approval = Notices.Notice(9, null, "bot-r", "研究员", "approval", "s", "room-1", "项目群", "bash")
        assertEquals("项目群 · 研究员 · 等你审批：bash", Notices.banner(approval))
        // 没有 roomId 时 title 不当群名
        assertEquals("研究员 · 回复：hi", Notices.banner(Notices.Notice(10, null, "b", "研究员", "message", "s", null, "怪标题", "hi")))
    }

    @Test
    fun pickBannerSkipsCurrentChatAndHiddenKinds() {
        // 停在 A 时断线：B 提问（5）、A 回复（6）——不能因最新一条是当前聊天而把 B 的提问一并压掉
        val b = n(5, "question", bot = "B")
        val a = n(6, "message", bot = "A")
        assertEquals(b, Notices.pickBanner(listOf(b, a), currentTarget = "A"))
        assertEquals(a, Notices.pickBanner(listOf(b, a), currentTarget = null))
        assertEquals(null, Notices.pickBanner(listOf(a), currentTarget = "A"))
        assertEquals(b, Notices.pickBanner(listOf(b, n(7, "view", bot = "C")), currentTarget = null))
        // 群目标按 roomId 比较
        val g = n(8, "group", bot = "B", room = "room-1")
        assertEquals(null, Notices.pickBanner(listOf(g), currentTarget = "room-1"))
        assertEquals(g, Notices.pickBanner(listOf(g), currentTarget = "B"))
        assertEquals(null, Notices.pickBanner(emptyList(), currentTarget = null))
    }

    @Test
    fun firstHelloAdoptsCursorWithoutBackfill() {
        val c = NoticeCursor()
        assertEquals(NoticeCursor.HelloAction.None, c.onHello("boot-1", 40))
        assertEquals("boot-1", c.boot)
        assertEquals(40L, c.seq)
        assertFalse(c.accept(n(40))) // 首次连接前的旧提醒不弹
        assertTrue(c.accept(n(41)))
    }

    @Test
    fun liveDuplicatesAndOutOfOrderDropped() {
        val c = NoticeCursor()
        c.onHello("boot-1", 0)
        assertTrue(c.accept(n(3)))
        assertFalse(c.accept(n(3)))
        assertFalse(c.accept(n(2))) // 乱序晚到
        assertTrue(c.accept(n(4)))
        assertEquals(4L, c.seq)
    }

    @Test
    fun sameBootReconnectFills() {
        val c = NoticeCursor()
        c.onHello("boot-1", 5)
        c.accept(n(6))
        assertEquals(NoticeCursor.HelloAction.Fill("boot-1", 6), c.onHello("boot-1", 9))
        assertEquals(NoticeCursor.HelloAction.None, c.onHello("boot-1", 6)) // 没有新提醒
        val r = c.onBatch(Notices.Batch("boot-1", 9, false, listOf(n(9), n(6), n(7), n(8))))
        assertFalse(r.refetch)
        assertEquals(listOf(7L, 8L, 9L), r.fresh.map { it.seq }) // 已见的 6 丢弃，按 seq 升序
        assertEquals(9L, c.seq)
    }

    @Test
    fun bootChangeResetsWithoutRepeat() {
        val c = NoticeCursor()
        c.onHello("boot-1", 5)
        c.accept(n(6))
        assertEquals(NoticeCursor.HelloAction.Reset, c.onHello("boot-2", 3)) // dsh 重启
        assertEquals("boot-2", c.boot)
        assertEquals(3L, c.seq)
        assertFalse(c.accept(n(2)))
        assertTrue(c.accept(n(4)))
    }

    @Test
    fun helloWithoutBootAfterBootIsReset() {
        val c = NoticeCursor()
        assertEquals(NoticeCursor.HelloAction.None, c.onHello(null, null)) // 0.3.6 不带游标
        c.onHello("boot-1", 1)
        assertEquals(NoticeCursor.HelloAction.Reset, c.onHello(null, null)) // 回退到旧版
        assertNull(c.boot)
    }

    @Test
    fun liveNoticeDuringFillMakesOlderBatchItemsStale() {
        val c = NoticeCursor()
        c.onHello("boot-1", 5)
        assertEquals(NoticeCursor.HelloAction.Fill("boot-1", 5), c.onHello("boot-1", 8))
        assertTrue(c.accept(n(9))) // 补齐在途时实时提醒先到
        val r = c.onBatch(Notices.Batch("boot-1", 9, false, listOf(n(6), n(7), n(8), n(9))))
        assertFalse(r.refetch)
        assertEquals(emptyList(), r.fresh) // 都不大于已见的 9
        assertEquals(9L, c.seq)
        // 应答 seq 小于游标时游标不回退
        c.onBatch(Notices.Batch("boot-1", 3, false, emptyList()))
        assertEquals(9L, c.seq)
        // 同 boot 但 hello 不带 seq：不补齐
        assertEquals(NoticeCursor.HelloAction.None, c.onHello("boot-1", null))
    }

    @Test
    fun batchResetOrForeignBootRefetches() {
        val c = NoticeCursor()
        c.onHello("boot-1", 5)
        val r = c.onBatch(Notices.Batch("boot-1", 300, true, listOf(n(299))))
        assertTrue(r.refetch)
        assertEquals(emptyList(), r.fresh)
        assertEquals(300L, c.seq)
        val r2 = c.onBatch(Notices.Batch("boot-9", 2, false, listOf(n(2))))
        assertTrue(r2.refetch)
        assertEquals("boot-9", c.boot)
        // 真实 reset 夹具同样判为重取
        assertTrue(c.onBatch(Notices.decodeBatch(value("notices-reset.json"))!!).refetch)
    }
}
