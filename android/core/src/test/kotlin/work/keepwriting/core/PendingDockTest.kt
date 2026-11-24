package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dsbot-pending-dock：从 state 汇集待处理项（0.3.10 源码核对形态）、排序、作答请求体、学习提案与逐行差异。 */
class PendingDockTest {
    private fun obj(t: String) = Json.parse(t) as Json.Value.Object

    /** 0.3.10 state 片段：私聊 bot-1（当前分段 s-bot-1）与群 room-1（成员群内会话 g-1）。 */
    private val state = obj(
        """{
          "questions":{
            "s-bot-1":{"id":"q-1","question":"选哪个窗口？","options":[{"label":"周六","description":"低峰"},{"label":"周日"}],"multiSelect":false,"allowCustom":true},
            "s-other":{"id":"q-x","question":"别的会话","options":[]},
            "g-1":{"id":"q-g","question":"群里问","options":[{"label":"好"}],"multiSelect":true}},
          "views":{"s-bot-1":{"id":"v-1","title":"巡检表","view":{"blocks":[]}}},
          "work":{"approvals":[
            {"id":"ap-1","sessionId":"s-bot-1","botId":"bot-1","tool":"bash","reason":"运行部署脚本","answerable":true},
            {"id":"ap-2","sessionId":"s-bot-1","botId":"bot-1","tool":"edit","answerable":false},
            {"id":"ap-3","sessionId":"g-1","botId":"bot-1","roomId":"room-1","tool":"bash"},
            {"id":"ap-4","sessionId":"t-1","botId":"bot-1","tool":"write","taskId":"task-1","taskTitle":"整理周报"}]},
          "secretRequests":{"bot-1":[
            {"id":"sr-1","sessionId":"s-bot-1","name":"OPENAI_KEY","purpose":"调用模型","kind":"fill","status":"pending"},
            {"id":"sr-2","sessionId":"s-bot-1","name":"OLD","status":"canceled"}]},
          "connectorRequests":{"bot-1":[
            {"id":"cr-1","sessionId":"s-bot-1","connectorId":"gmail","name":"Gmail","purpose":"读邮件","status":"pending"},
            {"id":"cr-2","sessionId":"s-old-part","connectorId":"x","status":"pending"}]},
          "learning":{"bot-1":[{"id":"lp-1","kind":"skill","action":"create","name":"写周报","content":"a\nb"}]},
          "browserHolds":{"bot-1":{"by":"bot","reason":"需要你登录","note":"登录网站后点完成","callId":"c-9"},"bot-2":{"by":"user","reason":"x"}}
        }""",
    )

    @Test
    fun privateChatCollectsAllKindsInPriorityOrder() {
        val items = PendingDock.collect(state, setOf("s-bot-1"), botId = "bot-1", roomId = null, answered = emptySet())
        assertEquals(listOf("lp-1", "ap-1", "ap-4", "q-1", "sr-1", "cr-1", "handoff:bot-1:c-9", "v-1"), items.map { it.id })
        assertEquals(PendingDock.Kind.LEARNING, items[0].kind)
        assertEquals("bash", items[1].title)
        assertEquals("整理周报 · write", items[2].title) // 后台任务里的审批带任务标题
        assertEquals("选哪个窗口？", items[3].title)
        val q = assertIs<PendingDock.Payload.Question>(items[3].payload)
        assertEquals("s-bot-1", q.sessionId)
        assertEquals(listOf("周六", "周日"), q.options.map { it.label })
        assertTrue(q.allowCustom)
        assertEquals("登录网站后点完成", items[6].title)
        assertEquals(0, PendingDock.expandedIndex(items))
    }

    @Test
    fun answerableFalseOtherSessionsAndSettledAreExcluded() {
        val ids = PendingDock.collect(state, setOf("s-bot-1"), "bot-1", null, emptySet()).map { it.id }
        assertTrue("ap-2" !in ids) // answerable=false
        assertTrue("ap-3" !in ids) // 群里的审批不在私聊
        assertTrue("q-x" !in ids && "q-g" !in ids)
        assertTrue("sr-2" !in ids) // 已取消
        assertTrue("cr-2" !in ids) // 旧分段的请求
    }

    @Test
    fun groupChatCollectsRoomApprovalsAndMemberSessionQuestions() {
        val items = PendingDock.collect(state, setOf("room-1", "g-1"), botId = null, roomId = "room-1", answered = emptySet())
        assertEquals(listOf("ap-3", "q-g"), items.map { it.id })
        assertTrue(assertIs<PendingDock.Payload.Question>(items[1].payload).multiSelect)
    }

    @Test
    fun answeredIdsExcludedAndEmptyState() {
        val items = PendingDock.collect(state, setOf("s-bot-1"), "bot-1", null, answered = setOf("lp-1", "ap-1"))
        assertEquals("ap-4", items[0].id)
        assertEquals(emptyList(), PendingDock.collect(null, setOf("x"), "b", null, emptySet()))
        assertEquals(-1, PendingDock.expandedIndex(emptyList()))
        assertEquals(emptyList(), PendingDock.collect(obj("""{"questions":"bad","work":{"approvals":{}}}"""), setOf("s"), "b", null, emptySet()))
    }

    @Test
    fun handoffNeedsCallIdAndEachHandoffHasItsOwnId() {
        assertNull(PendingDock.handoffOf(state, "bot-2")) // 用户自己接管的不是待处理
        val h = PendingDock.handoffOf(state, "bot-1")!!
        assertEquals("登录网站后点完成", h.note)
        assertEquals("c-9", h.callId)
        // 第一次交接作答后（id 记入 answered），同一聊天的第二次交接（新 callId）照样出现
        val second = obj("""{"browserHolds":{"bot-1":{"by":"bot","note":"再登录一次","callId":"c-10"}}}""")
        val items = PendingDock.collect(second, setOf("s"), "bot-1", null, answered = setOf("handoff:bot-1:c-9"))
        assertEquals(listOf("handoff:bot-1:c-10"), items.map { it.id })
    }

    @Test
    fun requestBodiesMatchUpstream() {
        assertEquals("""{"id":"ap-1","choice":"allow"}""", PendingDock.Requests.approval("ap-1", true))
        assertEquals("""{"id":"ap-1","choice":"reject"}""", PendingDock.Requests.approval("ap-1", false))
        assertEquals(
            """{"sessionId":"s","questionId":"q","answer":{"selected":["周六"]}}""",
            PendingDock.Requests.answerQuestion("s", "q", listOf("周六")),
        )
        assertEquals(
            """{"sessionId":"s","questionId":"q","answer":{"selected":[],"custom":"自填"}}""",
            PendingDock.Requests.answerQuestion("s", "q", emptyList(), " 自填 "),
        )
        assertEquals("""{"sessionId":"s"}""", PendingDock.Requests.dismissQuestion("s"))
        assertEquals("""{"requestId":"r","value":"v"}""", PendingDock.Requests.secretSet("r", "v"))
        assertEquals("""{"requestId":"r"}""", PendingDock.Requests.secretCancel("r"))
        assertEquals("""{"id":"lp","approve":false}""", PendingDock.Requests.learning("lp", false))
        assertEquals("""{"requestId":"cr"}""", PendingDock.Requests.connectorCancel("cr"))
        assertEquals("""{"botId":"b","callId":"c","outcome":"skip"}""", PendingDock.Requests.giveBack("b", "c", false))
        assertEquals("""{"botId":"b","callId":"c","outcome":"done"}""", PendingDock.Requests.giveBack("b", "c", true))
        assertEquals("""{"requestId":"r"}""", PendingDock.Requests.secretAllow("r"))
        assertEquals("""{"sessionId":"s"}""", PendingDock.Requests.dismissView("s"))
        assertEquals("""{"sessionId":"s","cardId":"c"}""", PendingDock.Requests.btwDelete("s", "c"))
    }

    @Test
    fun learningLinesSkillAndAgents() {
        val create = PendingDock.learningOf(state, "bot-1").single()
        assertEquals(listOf(LineDiff.Op.ADD to "a", LineDiff.Op.ADD to "b"), create.lines().map { it.op to it.text })
        val update = LearningProposal("u", "skill", "update", "n", null, null, "a\nB\nc", "a\nb\nc\n", null, emptyList())
        // previous 结尾换行不产生多余删除行
        assertEquals(
            listOf(LineDiff.Op.SAME to "a", LineDiff.Op.DEL to "b", LineDiff.Op.ADD to "B", LineDiff.Op.SAME to "c"),
            update.lines().map { it.op to it.text },
        )
        val agents = LearningProposal.decode(
            obj("""{"id":"g","kind":"agents","path":"/w/AGENTS.md","edits":[{"op":"replace","old":"旧规则","text":"新规则\n补充","why":"更清楚"},{"op":"add","text":"x"}]}"""),
        )!!
        assertEquals("更清楚", agents.edits[0].why)
        assertEquals(
            listOf(LineDiff.Op.DEL to "旧规则", LineDiff.Op.ADD to "新规则", LineDiff.Op.ADD to "补充", LineDiff.Op.ADD to "x"),
            agents.lines().map { it.op to it.text },
        )
    }

    @Test
    fun lineDiffCasesAndFold() {
        assertTrue(LineDiff.diff("x\ny", "x\ny").all { it.op == LineDiff.Op.SAME })
        assertEquals(listOf(LineDiff.Op.ADD to "n"), LineDiff.diff("", "n").map { it.op to it.text })
        assertEquals(listOf(LineDiff.Op.DEL to "o"), LineDiff.diff("o", "").map { it.op to it.text })
        val d = LineDiff.diff("1\n2\n3\n4", "1\n3\n4\n5")
        assertEquals(listOf("1" to LineDiff.Op.SAME, "2" to LineDiff.Op.DEL, "3" to LineDiff.Op.SAME, "4" to LineDiff.Op.SAME, "5" to LineDiff.Op.ADD), d.map { it.text to it.op })
        assertEquals(listOf("a", "b"), LineDiff.split("a\r\nb\n"))
        val big = (1..500).joinToString("\n")
        assertEquals(1000, LineDiff.diff(big, big).size) // 超规模降级为全删全增
        val lines = (1..12).map { LineDiff.Line(LineDiff.Op.ADD, "l$it") }
        assertEquals(8 to 4, LineDiff.fold(lines).let { it.shown.size to it.hidden })
        assertEquals(0, LineDiff.fold(lines.take(8)).hidden)
    }

    @Test
    fun decodeRejectsMalformed() {
        assertNull(LearningProposal.decode(obj("""{"kind":"skill"}""")))
        assertNull(ConnectorRequest.decode(obj("""{"id":""}""")))
        assertNull(LearningProposal.decode(Json.parse("[1]")))
        assertEquals(1, LearningProposal.decode(obj("""{"id":"p","edits":[1,{"op":"add","text":"t"}]}"""))!!.edits.size)
    }

    /** dsbot-connectors-mail：state.mailRequests 的待发邮件与密钥/连接器同档、只取当前会话与 pending。 */
    @Test
    fun mailSendCardsJoinTheSecretTier() {
        val st = obj(
            """{
              "connectorRequests":{"bot-1":[{"id":"cr-1","sessionId":"s-bot-1","connectorId":"mail","name":"Mailbox","status":"pending"}]},
              "mailRequests":{"bot-1":[
                {"id":"mr-1","callId":"c1","sessionId":"s-bot-1","account":"m1","to":"a@x.com","cc":"","bcc":"","subject":"周报","body":"见附件","attachments":["r.pdf"],"attachmentDetails":[{"name":"r.pdf","size":2048}],"status":"pending","askedAt":1},
                {"id":"mr-2","sessionId":"s-bot-1","account":"m1","to":"b@x.com","subject":"旧的","status":"canceled"},
                {"id":"mr-3","sessionId":"s-old","account":"m1","to":"c@x.com","subject":"别的分段","status":"pending"},
                {"id":"mr-4","sessionId":"s-bot-1","account":"m1","to":"d@x.com","subject":"","status":"pending"}]},
              "views":{"s-bot-1":{"id":"v-1","title":"卡","view":{"blocks":[]}}}
            }""",
        )
        val items = PendingDock.collect(st, setOf("s-bot-1"), "bot-1", null, emptySet())
        assertEquals(listOf("cr-1", "mr-1", "mr-4", "v-1"), items.map { it.id })
        assertEquals(PendingDock.Kind.MAIL, items[1].kind)
        assertEquals("周报", items[1].title)
        assertEquals("邮件", items[2].title)
        val m = assertIs<PendingDock.Payload.Mail>(items[1].payload).request
        assertEquals("a@x.com", m.to)
        assertEquals(listOf(MailSettings.Attachment("r.pdf", 2048)), m.attachments)
        // 群聊不汇集私聊的邮件卡；已作答的排除
        assertTrue(PendingDock.collect(st, setOf("s-bot-1"), null, "room-1", emptySet()).none { it.kind == PendingDock.Kind.MAIL })
        assertTrue(PendingDock.collect(st, setOf("s-bot-1"), "bot-1", null, setOf("mr-1")).none { it.id == "mr-1" })
    }
}
