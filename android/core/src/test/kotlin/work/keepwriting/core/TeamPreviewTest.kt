package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dsbot-read-state：团队页预览优先级（0.3.10 state 形态）与摘要清洗。 */
class TeamPreviewTest {
    private fun state(extra: String) = checkNotNull(
        DsbotModels.decodeState(
            checkNotNull(
                Json.parse(
                    """{"revision":1,"bots":[
                      {"id":"b1","name":"A","sessionId":"s1","role":"研究","preview":"最后一条"},
                      {"id":"b2","name":"B","sessionId":"s2"}],
                     "rooms":[{"id":"r1","name":"项目群","members":["b1","b2"],"sessionId":"r1","parts":["g-1"],"preview":"A：群里最后一条"}],
                     "sections":[]$extra}""",
                ),
            ),
        ),
    )

    private fun bot(st: DsbotModels.State, id: String = "b1") = TeamPreview.ofBot(st.bots.first { it.id == id }, st)

    private fun room(st: DsbotModels.State) = TeamPreview.ofRoom(st.rooms.single(), st)

    @Test
    fun botPriorityAnswerThenApprovalThenWorkThenGroupThenSummaryThenRole() {
        val all = state(
            ""","questions":{"s1":{"id":"q"}},"work":{"approvals":[{"id":"a","botId":"b1"}],"bots":{"b1":{"tool":"bash"}},"rooms":{"r1":[{"botId":"b1","tool":"web_search"}]}}""",
        )
        assertEquals(TeamPreview.Preview(TeamPreview.Kind.ANSWER, "等你回答"), bot(all))
        assertTrue(bot(all)!!.waiting)
        val noQuestion = state(""","work":{"approvals":[{"id":"a","botId":"b1"}],"bots":{"b1":{"tool":"bash"}}}""")
        assertEquals(TeamPreview.Kind.APPROVAL, bot(noQuestion)!!.kind)
        val working = state(""","work":{"bots":{"b1":{"tool":"bash"}}}""")
        assertEquals(TeamPreview.Preview(TeamPreview.Kind.WORK, "运行命令"), bot(working))
        assertFalse(bot(working)!!.waiting)
        val inGroup = state(""","work":{"rooms":{"r1":[{"botId":"b1","tool":"web_search"}],"r9":[{"botId":"b1"}]}}""")
        assertEquals(TeamPreview.Preview(TeamPreview.Kind.GROUP_WORK, "项目群 · 联网搜索 +1"), bot(inGroup))
        assertEquals(TeamPreview.Preview(TeamPreview.Kind.SUMMARY, "最后一条"), bot(state("")))
        val roleOnly = state("").let { st -> TeamPreview.ofBot(st.bots[0].copy(raw = Json.parse("""{"id":"b1","role":"研究"}""") as Json.Value.Object), st) }
        assertEquals(TeamPreview.Preview(TeamPreview.Kind.ROLE, "研究"), roleOnly)
        assertNull(bot(state(""), "b2"))
    }

    @Test
    fun botApprovalSourcesAndExclusions() {
        assertEquals(TeamPreview.Kind.ANSWER, bot(state(""","views":{"s1":{"id":"v"}}"""))!!.kind) // 卡片也算待回答
        assertEquals(TeamPreview.Kind.APPROVAL, bot(state(""","secretRequests":{"b1":[{"id":"r","status":"pending"}]}"""))!!.kind)
        assertEquals(TeamPreview.Kind.APPROVAL, bot(state(""","connectorRequests":{"b1":[{"id":"r"}]}"""))!!.kind)
        assertEquals(TeamPreview.Kind.APPROVAL, bot(state(""","learning":{"b1":[{"id":"l"}]}"""))!!.kind)
        // 不可作答、群里的审批、已取消的请求、别的会话的提问都不算
        val none = state(
            ""","questions":{"s-old":{"id":"q"}},"secretRequests":{"b1":[{"id":"r","status":"canceled"}]},"work":{"approvals":[{"id":"a","botId":"b1","answerable":false},{"id":"g","botId":"b1","roomId":"r1"}]}""",
        )
        assertEquals(TeamPreview.Kind.SUMMARY, bot(none)!!.kind)
    }

    @Test
    fun roomPriority() {
        assertEquals(TeamPreview.Kind.ANSWER, room(state(""","questions":{"g-1":{"id":"q"}}"""))!!.kind) // 成员群内会话的提问
        assertEquals(TeamPreview.Kind.APPROVAL, room(state(""","work":{"approvals":[{"id":"a","botId":"b1","roomId":"r1"}]}"""))!!.kind)
        assertEquals(
            TeamPreview.Preview(TeamPreview.Kind.GROUP_WORK, "B · 运行命令 +1"),
            room(state(""","work":{"rooms":{"r1":[{"botId":"b2","tool":"bash"},{"botId":"b1"}]}}""")),
        )
        assertEquals(TeamPreview.Preview(TeamPreview.Kind.SUMMARY, "A：群里最后一条"), room(state("")))
    }

    @Test
    fun cleanUnwrapsServerReplyRecords() {
        assertNull(TeamPreview.clean(null))
        assertNull(TeamPreview.clean("   "))
        assertEquals("你好 世界", TeamPreview.clean("[Message from Alice]\n你好\n  世界"))
        assertEquals("结果如下", TeamPreview.clean("[Reply from 研究主管] 结果如下"))
        assertEquals("浏览器交接：需要登录 — 登录后点完成（已完成）", TeamPreview.clean("[Browser handoff] 需要登录 — 登录后点完成\nAnswer: done"))
        assertEquals("浏览器交接：需要登录（已跳过）", TeamPreview.clean("[Browser handoff] 需要登录 Answer: skip"))
        assertEquals("已回答「选哪个窗口？」：周六, 自填", TeamPreview.clean("[Answer to question card] 选哪个窗口？\nAnswer: 周六, 自填"))
        assertEquals(
            "已回答「Q1」：A1；已回答「Q2」：A2",
            TeamPreview.clean("[Answer to question card] Q1\nAnswer: A1\n\n[Answer to question card] Q2\nAnswer: A2"),
        )
        // 服务端预览已折叠空白：混合记录在包装开头处切开逐条清洗，答案里的空格不影响
        assertEquals(
            "浏览器交接：r（已完成）；已回答「Q」：周六 上午",
            TeamPreview.clean("[Browser handoff] r Answer: done [Answer to question card] Q Answer: 周六 上午"),
        )
        assertEquals(
            "已回答「Q」：A；已回复卡片「T」",
            TeamPreview.clean("[Answer to question card] Q Answer: A [Answer to view card] T Answer: Submitted"),
        )
        assertEquals("已回答「Q」：已跳过", TeamPreview.clean("[Answer to question card] Q\nAnswer: (skipped)"))
        assertEquals("已回复卡片「巡检表」", TeamPreview.clean("[Answer to view card] 巡检表\nAnswer: Submitted"))
        assertEquals("已回复卡片", TeamPreview.clean("[Answer to view card]\nAnswer: Submitted"))
        assertEquals("研究主管：浏览器交接：x（已完成）", TeamPreview.clean("研究主管：[Browser handoff] x Answer: done")) // 群预览只清洗正文
        assertEquals("普通文本 [不是包装]", TeamPreview.clean("普通文本 [不是包装]"))
        // 以全角冒号结尾的普通文本与只有名字的群预览保留原文，不丢预览
        assertEquals("以下是今天的安排：", TeamPreview.clean("以下是今天的安排："))
        assertEquals("研究主管：", TeamPreview.clean("研究主管："))
    }

    @Test
    fun englishTexts() {
        val before = I18n.lang
        I18n.lang = ChatBars.Lang.EN
        try {
            assertEquals("Waiting for your answer", bot(state(""","questions":{"s1":{"id":"q"}}"""))!!.text)
            assertEquals("Waiting for your approval", bot(state(""","learning":{"b1":[{"id":"l"}]}"""))!!.text)
            assertEquals("Browser handoff: login (done)", TeamPreview.clean("[Browser handoff] login Answer: done"))
            assertEquals("Answered \"Q\": skipped", TeamPreview.clean("[Answer to question card] Q Answer: (skipped)"))
        } finally {
            I18n.lang = before
        }
    }
}
