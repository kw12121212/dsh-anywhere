package work.keepwriting.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class SchedulesTest {
    @BeforeEach
    fun resetLang() {
        I18n.source = null
        I18n.lang = ChatBars.Lang.ZH
    }

    private val zone = ZoneId.of("Asia/Shanghai")
    /** 2026-11-24 12:00 上海。 */
    private val now = Instant.parse("2026-11-24T04:00:00Z").toEpochMilli()

    /** 0.3.10 schedulesView 形态（schedules.js：catalog 项 + sessionId + owner + state）。 */
    private val answer = Json.parse(
        """
        {"available":true,"canPause":true,"tasks":[
          {"id":"t1","title":"晨报","prompt":"总结昨天的变化","kind":"daily","time":"09:00:00","timeZone":"Asia/Shanghai","scheduledAt":"2026-11-25T01:00:00.000Z","status":"active","state":"scheduled","sessionId":"s-b1","botId":"b1"},
          {"id":"t2","title":"周报","prompt":"周报","kind":"weekly","time":"18:30:00","timeZone":"America/New_York","weekdays":[1,2,3,4,5],"scheduledAt":"2026-11-24T23:30:00.000Z","status":"paused","state":"paused","sessionId":"s-b1","botId":"b1"},
          {"id":"t3","title":"提醒","prompt":"喝水","kind":"at","scheduledAt":"2026-11-24T03:00:00.000Z","status":"active","state":"overdue","sessionId":"s-b2","botId":"b2"},
          {"id":"t4","title":"巡检","prompt":"巡检","kind":"every","everySeconds":7200,"scheduledAt":"2026-11-24T06:00:00.000Z","status":"active","sessionId":"r1","roomId":"r1"},
          {"id":"t5","title":"旧任务","prompt":"x","kind":"cron","expression":"0 9 * * 1","timeZone":"Asia/Shanghai","scheduledAt":"2025-01-06T01:00:00.000Z","status":"ended","state":"finished","sessionId":"s-b2","botId":"b2"},
          {"title":"no id"}
        ]}
        """.trimIndent(),
    )
    private val state = Json.parse("""{"bots":[{"id":"b2","name":"乙"},{"id":"b1","name":"甲"},{"id":"b3","name":"丙"}],"rooms":[{"id":"r1","name":"项目群"}]}""") as Json.Value.Object
    private val view get() = Schedules.decode(answer)!!
    private fun t(id: String) = view.tasks.first { it.id == id }

    @Test
    fun `decodes tasks and flags`() {
        assertTrue(view.available && view.canPause)
        assertEquals(listOf("t1", "t2", "t3", "t4", "t5"), view.tasks.map { it.id })
        assertEquals(listOf(1, 2, 3, 4, 5), t("t2").weekdays)
        assertEquals(7200L, t("t4").everySeconds)
        assertEquals("r1", t("t4").roomId)
        assertFalse(Schedules.decode(Json.parse("""{"available":false,"tasks":[]}"""))!!.available)
        assertNull(Schedules.decode(Json.parse("[]")))
    }

    @Test
    fun `timing text follows web timingText`() {
        assertEquals("每天 09:00", Schedules.timingText(t("t1"), now, zone))
        assertEquals("每个工作日 18:30（America/New_York）", Schedules.timingText(t("t2"), now, zone))
        assertEquals("一次，11月24日 11:00", Schedules.timingText(t("t3"), now, zone))
        assertEquals("每 2 小时", Schedules.timingText(t("t4"), now, zone))
        assertEquals("按计划 0 9 * * 1", Schedules.timingText(t("t5"), now, zone))
        assertEquals("每周一、周三 07:05", Schedules.timingText(t("t2").copy(weekdays = listOf(3, 1), time = "07:05:00", timeZone = null), now, zone))
        assertEquals("每个周末 07:05:30", Schedules.timingText(t("t2").copy(weekdays = listOf(6, 7), time = "07:05:30", timeZone = "Asia/Shanghai"), now, zone))
        assertEquals("每 45 分钟", Schedules.everyText(2700))
        assertEquals("每分钟", Schedules.everyText(60))
        assertEquals("每小时", Schedules.everyText(3600))
        assertEquals("", Schedules.everyText(0))
        assertEquals("每天", Schedules.everyText(86400))
        assertEquals("每 3 天", Schedules.everyText(3 * 86400))
        assertEquals("每周一、周三 07:05", Schedules.timingText(t("t2").copy(weekdays = listOf(3, 1, 1), time = "07:05:00", timeZone = null), now, zone))
        assertEquals("每天 07:05", Schedules.timingText(t("t2").copy(weekdays = listOf(1, 2, 3, 4, 5, 6, 7), time = "07:05:00", timeZone = null), now, zone))
        assertEquals("按计划 0 9 * * 1（America/New_York）", Schedules.timingText(t("t5").copy(timeZone = "America/New_York"), now, zone))
        assertEquals("自定义计划", Schedules.timingText(t("t5").copy(kind = "rrule"), now, zone))
        assertEquals("一次，11月24日 11:00", Schedules.timingText(t("t3").copy(kind = "after"), now, zone))
        I18n.lang = ChatBars.Lang.EN
        assertEquals("Every weekday at 18:30 (America/New_York)", Schedules.timingText(t("t2"), now, zone))
        assertEquals("Every day at 07:05", Schedules.timingText(t("t2").copy(weekdays = listOf(1, 2, 3, 4, 5, 6, 7), time = "07:05:00", timeZone = null), now, zone))
        assertEquals("On the schedule 0 9 * * 1 (America/New_York)", Schedules.timingText(t("t5").copy(timeZone = "America/New_York"), now, zone))
        assertEquals("Custom schedule", Schedules.timingText(t("t5").copy(kind = "rrule"), now, zone))
        assertEquals("Once, Nov 24, 11:00", Schedules.timingText(t("t3"), now, zone))
        assertEquals("Every minute", Schedules.everyText(60))
        assertEquals("Every hour", Schedules.everyText(3600))
    }

    @Test
    fun `next text and pausability`() {
        assertEquals("下次：11月25日 09:00", Schedules.nextText(t("t1"), now, zone))
        assertEquals("已暂停", Schedules.nextText(t("t2"), now, zone))
        assertEquals("已逾期：11月24日 11:00", Schedules.nextText(t("t3"), now, zone))
        assertTrue(Schedules.isOverdue(t("t3"), now))
        assertTrue(Schedules.isOverdue(t("t3").copy(state = null), now))
        assertFalse(Schedules.isOverdue(t("t1"), now))
        assertEquals("已结束", Schedules.nextText(t("t5"), now, zone))
        assertEquals("已逾期：11月24日 11:00", Schedules.nextText(t("t3").copy(state = null), now, zone)) // 无 state 时按时间推断
        assertEquals("2025年1月6日 09:00", Schedules.dayAndTime(Instant.parse("2025-01-06T01:00:00Z").toEpochMilli(), now, zone))
        assertTrue(Schedules.canPause(view, t("t1"), now))
        assertTrue(Schedules.canPause(view, t("t2"), now)) // 已暂停可恢复
        assertFalse(Schedules.canPause(view, t("t3"), now)) // 到点的一次性任务（at）
        assertFalse(Schedules.canPause(view, t("t3").copy(kind = "after"), now)) // 到点的一次性任务（after）
        assertTrue(Schedules.canPause(view, t("t3").copy(kind = "after", scheduledAt = "2026-11-25T01:00:00.000Z"), now)) // 未到点的 after 可暂停
        assertFalse(Schedules.canPause(view, t("t5"), now)) // 已结束
        assertFalse(Schedules.canPause(view.copy(canPause = false), t("t1"), now))
    }

    @Test
    fun `groups follow bots then rooms order`() {
        val g = Schedules.groups(state, view)
        assertEquals(listOf("b2", "b1", "r1"), g.map { it.ownerId })
        assertEquals(listOf("t3", "t5"), g[0].tasks.map { it.id })
        assertTrue(g[2].isRoom)
        assertEquals(listOf("b1"), Schedules.groups(state, view, onlyOwner = "b1").map { it.ownerId })
        assertEquals(1, Schedules.activeCount(view, "b1"))
        assertEquals(0, Schedules.activeCount(null, "b1"))
        // 实测形态：Bot 在群聊会话创建的任务同时带 botId 与 roomId，归入该 Bot 分组、不重复进群分组，并可解析群名
        val botInRoom = t("t1").copy(id = "t6", roomId = "r1")
        val withBoth = view.copy(tasks = view.tasks + botInRoom)
        val g2 = Schedules.groups(state, withBoth)
        assertEquals(listOf("t1", "t2", "t6"), g2.first { it.ownerId == "b1" }.tasks.map { it.id })
        assertEquals(listOf("t4"), g2.first { it.ownerId == "r1" }.tasks.map { it.id })
        assertEquals("项目群", Schedules.roomName(state, botInRoom.roomId))
        assertNull(Schedules.roomName(state, "missing"))
    }

    @Test
    fun `create validation mirrors TaskForm and selectorOf`() {
        val future = LocalDateTime.of(2026, 11, 24, 13, 0)
        val past = LocalDateTime.of(2026, 11, 24, 11, 59)
        assertEquals("选择发送给谁", Schedules.createProblem(null, "a", "b", Schedules.When.Daily(9, 0), now, zone))
        assertEquals("选择发送给谁", Schedules.createProblem("  ", "a", "b", Schedules.When.Daily(9, 0), now, zone))
        assertEquals("给任务起个名字", Schedules.createProblem("b1", " ", "b", Schedules.When.Daily(9, 0), now, zone))
        assertNull(Schedules.createProblem("b1", "x".repeat(Schedules.TITLE_MAX), "b", Schedules.When.Daily(9, 0), now, zone))
        assertEquals("名称不超过 120 字", Schedules.createProblem("b1", "x".repeat(121), "b", Schedules.When.Daily(9, 0), now, zone))
        assertEquals("写下 Bot 要做的事", Schedules.createProblem("b1", "a", " ", Schedules.When.Daily(9, 0), now, zone))
        assertEquals("选择将来的时间", Schedules.createProblem("b1", "a", "b", Schedules.When.Once(past), now, zone))
        assertNull(Schedules.createProblem("b1", "a", "b", Schedules.When.Once(future), now, zone))
        assertEquals("时刻无效", Schedules.createProblem("b1", "a", "b", Schedules.When.Daily(24, 0), now, zone))
        assertEquals("时刻无效", Schedules.createProblem("b1", "a", "b", Schedules.When.Weekly(9, 60, setOf(1)), now, zone))
        assertEquals("至少选一天", Schedules.createProblem("b1", "a", "b", Schedules.When.Weekly(9, 0, emptySet()), now, zone))
        assertEquals("至少选一天", Schedules.createProblem("b1", "a", "b", Schedules.When.Weekly(9, 0, setOf(0)), now, zone))
        assertEquals("至少选一天", Schedules.createProblem("b1", "a", "b", Schedules.When.Weekly(9, 0, setOf(8)), now, zone))
        assertEquals("重复间隔为 1 分钟到 7 天", Schedules.createProblem("b1", "a", "b", Schedules.When.Every(0, 60), now, zone))
        assertEquals("重复间隔为 1 分钟到 7 天", Schedules.createProblem("b1", "a", "b", Schedules.When.Every(1, 0), now, zone))
        assertEquals("重复间隔为 1 分钟到 7 天", Schedules.createProblem("b1", "a", "b", Schedules.When.Every(1, -60), now, zone))
        assertEquals("重复间隔为 1 分钟到 7 天", Schedules.createProblem("b1", "a", "b", Schedules.When.Every(8, 1440), now, zone))
        assertNull(Schedules.createProblem("b1", "a", "b", Schedules.When.Every(7, 1440), now, zone))
    }

    @Test
    fun `request bodies match upstream endpoints`() {
        val R = Schedules.Requests
        assertEquals("{}", R.list())
        assertEquals(
            """{"target":"b1","title":"晨报","prompt":"总结","when":{"kind":"daily","time":"09:05","timeZone":"Asia/Shanghai"}}""",
            R.create("b1", " 晨报 ", " 总结 ", Schedules.When.Daily(9, 5), zone),
        )
        assertEquals(
            """{"target":"r1","title":"a","prompt":"b","when":{"kind":"weekly","time":"18:30","timeZone":"Asia/Shanghai","weekdays":[1,3,5]}}""",
            R.create("r1", "a", "b", Schedules.When.Weekly(18, 30, setOf(5, 1, 3)), zone),
        )
        assertEquals("""{"kind":"at","at":"2026-11-24T05:00:00Z"}""", Schedules.whenJson(Schedules.When.Once(LocalDateTime.of(2026, 11, 24, 13, 0)), zone))
        assertEquals("""{"kind":"every","minutes":120}""", Schedules.whenJson(Schedules.When.Every(2, 60), zone))
        assertEquals("""{"sessionId":"s-b1","id":"t1","active":false}""", R.pause(t("t1"), false))
        assertEquals("""{"sessionId":"s-b1","id":"t1"}""", R.delete(t("t1")))
    }
}
