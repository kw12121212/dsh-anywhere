package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BotTaskDetail 与 ChatBars.taskOf 单元测试（dsbot-bot-task-detail）。 */
class BotTaskDetailTest {
    private fun parse(text: String): Json.Value = checkNotNull(Json.parse(text))

    private fun realFixture(name: String): Json.Value {
        val text = checkNotNull(BotTaskDetailTest::class.java.getResource("/dsbot/$name")).readText(Charsets.UTF_8)
        val root = parse(text) as Json.Value.Object
        return checkNotNull(root.members["value"])
    }

    @Test
    fun readRequestOmitsNullCursorAndFalseSteps() {
        assertEquals(
            """{"botId":"b1","taskId":"k_1"}""",
            BotTaskDetail.Requests.read("b1", "k_1"),
        )
        assertEquals(
            """{"botId":"b1","taskId":"k_1"}""",
            BotTaskDetail.Requests.read("b1", "k_1", cursor = Json.Value.NullValue, steps = false),
        )
        val cursor = parse("""{"part":0,"beforeSeq":42}""")
        assertEquals(
            """{"botId":"b1","taskId":"k_1","cursor":{"part":0,"beforeSeq":42},"steps":true}""",
            BotTaskDetail.Requests.read("b1", "k_1", cursor = cursor, steps = true),
        )
    }

    @Test
    fun sendRequestTrimsAndRejectsBlank() {
        assertNull(BotTaskDetail.Requests.send("b1", "k_1", ""))
        assertNull(BotTaskDetail.Requests.send("b1", "k_1", "   \n\t "))
        assertEquals(
            """{"botId":"b1","taskId":"k_1","text":"请补充表格"}""",
            BotTaskDetail.Requests.send("b1", "k_1", "  请补充表格  "),
        )
        assertEquals(
            """{"botId":"b1","taskId":"k_1"}""",
            BotTaskDetail.Requests.stop("b1", "k_1"),
        )
    }

    @Test
    fun decodeRealServerStepsFixture() {
        val page = BotTaskDetail.decodePage(realFixture("bot-task-read-steps.json"))
        assertNotNull(page.sessionId)
        assertNull(page.cursor)
        assertTrue(page.exhausted) // 上游回 cursor:null 必须判尽
        assertTrue(page.records.isNotEmpty())
        val steps = page.records.map { it.entry }.filterIsInstance<BotTaskDetail.Entry.Steps>()
        assertEquals(1, steps.size)
        assertTrue(steps[0].tools.isNotEmpty())
        assertTrue(BotTaskDetail.hasBotReply(page.records))
    }

    @Test
    fun decodeTaskBriefStepsDividerAndChatItems() {
        val value = parse(
            """{
              "sessionId": "ts-2",
              "cursor": {"part": 1, "beforeSeq": 10},
              "items": [
                {"kind": "divider", "time": 1000},
                {"kind": "task-brief", "name": "研究主管", "text": "整理季度报告", "seq": 10, "turn": 1, "sessionId": "ts-2"},
                {"kind": "user", "text": "加上对比图", "seq": 11, "turn": 1, "sessionId": "ts-2"},
                {"kind": "bot", "text": "已完成报告", "seq": 12, "turn": 1, "sessionId": "ts-2",
                 "files": [{"name": "report.md", "sessionId": "ts-2", "seq": 12, "index": 0}]},
                {"kind": "steps", "turn": 1, "ms": 2450, "tools": [
                  {"name": "web_search", "label": "Searching online", "ok": true},
                  {"name": "bash", "ok": false},
                  {"bad": 1}
                ]},
                {"kind": "steps", "turn": 2, "tools": []},
                {"kind": "unknown_kind", "text": "ignore me"}
              ]
            }""",
        )
        val page = BotTaskDetail.decodePage(value)
        assertEquals("ts-2", page.sessionId)
        assertFalse(page.exhausted)
        assertNotNull(page.cursor)

        // divider, task-brief, user, bot, presented-file, steps(turn 1); empty steps and unknown_kind dropped
        assertEquals(6, page.records.size)
        val divider = assertIs<BotTaskDetail.Entry.Divider>(page.records[0].entry)
        assertEquals(1000L, divider.time)

        val brief = assertIs<BotTaskDetail.Entry.TaskBrief>(page.records[1].entry)
        assertEquals("研究主管", brief.fromName)
        assertEquals("整理季度报告", brief.text)

        val user = assertIs<BotTaskDetail.Entry.Chat>(page.records[2].entry)
        val userMsg = assertIs<ChatRender.Item.Message>(user.item)
        assertTrue(userMsg.fromUser)
        assertEquals("加上对比图", userMsg.markdown)

        val bot = assertIs<BotTaskDetail.Entry.Chat>(page.records[3].entry)
        val botMsg = assertIs<ChatRender.Item.Message>(bot.item)
        assertFalse(botMsg.fromUser)
        assertEquals("已完成报告", botMsg.markdown)

        val file = assertIs<BotTaskDetail.Entry.Chat>(page.records[4].entry)
        val pf = assertIs<ChatRender.Item.PresentedFile>(file.item)
        assertEquals("report.md", pf.name)
        assertEquals(12L, page.records[4].seq)

        val steps = assertIs<BotTaskDetail.Entry.Steps>(page.records[5].entry)
        assertEquals(1L, steps.turn)
        assertEquals(2450L, steps.durationMs)
        assertEquals(2, steps.tools.size)
        assertEquals("Searching online", steps.tools[0].displayName())
        assertEquals(true, steps.tools[0].ok)
        assertEquals("bash", steps.tools[1].displayName())
        assertEquals(false, steps.tools[1].ok)
    }

    @Test
    fun mergePagesDeduplicatesBySessionSeqAndStepsTurn() {
        val older = listOf(
            BotTaskDetail.Record("s1", 1L, 1L, BotTaskDetail.Entry.TaskBrief(null, "brief")),
            BotTaskDetail.Record("s1", 2L, 1L, BotTaskDetail.Entry.Chat(ChatRender.Item.Message(false, "old bot", 1L, 2L))),
            BotTaskDetail.Record("s1", null, 1L, BotTaskDetail.Entry.Steps(1L, listOf(BotTaskDetail.StepTool("bash", null, true)), 500L)),
            BotTaskDetail.Record("s1", 3L, 2L, BotTaskDetail.Entry.Chat(ChatRender.Item.Message(true, "overlap", 2L, 3L))),
        )
        val latest = listOf(
            BotTaskDetail.Record("s1", 3L, 2L, BotTaskDetail.Entry.Chat(ChatRender.Item.Message(true, "overlap", 2L, 3L))),
            BotTaskDetail.Record("s1", 4L, 2L, BotTaskDetail.Entry.Chat(ChatRender.Item.Message(false, "new reply", 2L, 4L))),
            BotTaskDetail.Record("s1", null, 1L, BotTaskDetail.Entry.Steps(1L, listOf(BotTaskDetail.StepTool("bash", null, true)), 600L)),
        )
        val merged = BotTaskDetail.mergePages(older, latest, "s1")
        // older 的 seq=3 与 turn=1 的 steps 均被去重
        assertEquals(5, merged.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, null), merged.map { it.seq })
        val steps = assertIs<BotTaskDetail.Entry.Steps>(merged.last().entry)
        assertEquals(600L, steps.durationMs)
    }

    @Test
    fun mergePagesKeepsSameSeqAndStepsTurnFromDifferentSegments() {
        val older = listOf(
            BotTaskDetail.Record("seg-0", 1L, 1L, BotTaskDetail.Entry.Chat(ChatRender.Item.Message(false, "seg0", 1L, 1L))),
            BotTaskDetail.Record("seg-0", null, 1L, BotTaskDetail.Entry.Steps(1L, listOf(BotTaskDetail.StepTool("bash", null, true)), 400L)),
        )
        val latest = listOf(
            BotTaskDetail.Record("seg-1", 1L, 1L, BotTaskDetail.Entry.Chat(ChatRender.Item.Message(false, "seg1", 1L, 1L))),
            BotTaskDetail.Record("seg-1", null, 1L, BotTaskDetail.Entry.Steps(1L, listOf(BotTaskDetail.StepTool("read_file", null, true)), 300L)),
        )
        val merged = BotTaskDetail.mergePages(older, latest, "seg-1")
        assertEquals(4, merged.size)
        assertEquals(2, merged.count { it.entry is BotTaskDetail.Entry.Steps })
    }

    @Test
    fun taskOfFindsBothActiveAndEndedTasks() {
        val state = parse(
            """{"bots":[{"id":"b1","tasks":[
              {"id":"k1","title":"进行中","status":"running","sessionId":"s1","origin":"fg-handoff","startedAt":1000,"updatedAt":2000,"running":true},
              {"id":"k2","title":"已结束","status":"completed","sessionId":"s2","origin":"bot-handoff","startedAt":500,"endedAt":1500,"summary":"全部完成","running":false},
              {"id":"k3","title":"失败任务","status":"failed","error":"timeout"}
            ]}]}""",
        ) as Json.Value.Object

        val active = assertNotNull(ChatBars.taskOf(state, "b1", "k1"))
        assertTrue(active.active)
        assertTrue(active.running)
        assertEquals("fg-handoff", active.origin)
        assertEquals("转自对话", ChatBars.taskOriginLabel(active.origin, ChatBars.Lang.ZH))
        assertEquals("Moved from chat", ChatBars.taskOriginLabel(active.origin, ChatBars.Lang.EN))

        val done = assertNotNull(ChatBars.taskOf(state, "b1", "k2"))
        assertFalse(done.active)
        assertEquals("completed", done.status)
        assertEquals("已完成", ChatBars.taskStatusLabel(done.status, ChatBars.Lang.ZH))
        assertEquals("Done", ChatBars.taskStatusLabel(done.status, ChatBars.Lang.EN))
        assertEquals("全部完成", done.summary)
        assertEquals(1500L, done.endedAt)
        assertEquals("同伴委派", ChatBars.taskOriginLabel(done.origin, ChatBars.Lang.ZH))

        val failed = assertNotNull(ChatBars.taskOf(state, "b1", "k3"))
        assertFalse(failed.active)
        assertEquals("timeout", failed.error)
        assertEquals("已失败", ChatBars.taskStatusLabel("failed", ChatBars.Lang.ZH))
        assertEquals("已停止", ChatBars.taskStatusLabel("stopped", ChatBars.Lang.ZH))
        assertEquals("已中断", ChatBars.taskStatusLabel("interrupted", ChatBars.Lang.ZH))

        assertNull(ChatBars.taskOf(state, "b1", "missing"))
        assertNull(ChatBars.taskOf(state, "missing", "k1"))
        assertNull(ChatBars.taskOf(null, "b1", "k1"))
    }

    @Test
    fun formatElapsedCapsFutureServerClockAndFormatsUnits() {
        assertEquals("0秒", BotTaskDetail.formatElapsed(5000L, 4000L, ChatBars.Lang.ZH))
        assertEquals("45秒", BotTaskDetail.formatElapsed(1000L, 46_000L, ChatBars.Lang.ZH))
        assertEquals("2分5秒", BotTaskDetail.formatElapsed(0L, 125_000L, ChatBars.Lang.ZH))
        assertEquals("1h 2m", BotTaskDetail.formatElapsed(0L, 3_720_000L, ChatBars.Lang.EN))
        assertEquals("1.2秒", BotTaskDetail.formatDurationMs(1234L, ChatBars.Lang.ZH))
        assertEquals("15s", BotTaskDetail.formatDurationMs(15_000L, ChatBars.Lang.EN))
        assertEquals("刚刚结束", BotTaskDetail.formatEndedTime(5000L, 4000L, ChatBars.Lang.ZH))
        assertEquals("5分钟前结束", BotTaskDetail.formatEndedTime(0L, 300_000L, ChatBars.Lang.ZH))
        assertEquals("Ended 2h ago", BotTaskDetail.formatEndedTime(0L, 7_200_000L, ChatBars.Lang.EN))
        assertEquals("3天前结束", BotTaskDetail.formatEndedTime(0L, 3L * 86_400_000L, ChatBars.Lang.ZH))
    }

    @Test
    fun errorMessagesAreReadableAndLocalized() {
        assertTrue(BotTaskDetail.readError("network", null, ChatBars.Lang.ZH).contains("网络"))
        assertTrue(BotTaskDetail.readError("bot/not-found", "Unknown endpoint bot-task-read", ChatBars.Lang.ZH).contains("不支持"))
        assertTrue(BotTaskDetail.readError("bot/not-found", "Unknown Bot", ChatBars.Lang.ZH).contains("Bot"))
        assertTrue(BotTaskDetail.sendError("bot/invalid", "Task is completed and cannot receive messages.", ChatBars.Lang.ZH).contains("已结束"))
        assertTrue(BotTaskDetail.sendError("bot/read-only", null, ChatBars.Lang.EN).contains("read-only"))
        assertTrue(BotTaskDetail.sendError(null, null, ChatBars.Lang.EN).contains("draft kept"))
    }
}
