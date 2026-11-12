package work.keepwriting.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cards 解析/校验/降级/构造测试（dsbot-interactive-cards——Bot 内容不可信边界）。 */
class CardsTest {

    private fun obj(json: String): Json.Value.Object = checkNotNull(Json.parse(json)) as Json.Value.Object

    @Test
    fun `审批与提问卡解析`() {
        val approval = Cards.parse(obj("""{"kind":"approval","callId":"a1","title":"部署审批","detail":"发布到生产"}"""))
        val card = assertIs<Cards.Card.Approval>(approval)
        assertEquals("a1", card.callId)
        assertEquals("部署审批", card.title)

        val q = Cards.parse(obj("""
            {"kind":"question","callId":"q1","question":"选哪个维护窗口？",
             "options":[{"label":"周六","description":"低峰"},{"label":"周日"}]}
        """.trimIndent()))
        val qc = assertIs<Cards.Card.Question>(q)
        assertEquals(2, qc.options.size)
        assertEquals("周六", qc.options[0].label)
        assertEquals("低峰", qc.options[0].description)
        assertNull(qc.options[1].description)

        // 无选项降级只读
        val degraded = Cards.parse(obj("""{"kind":"question","callId":"q2","question":"空问题"}"""))
        assertIs<Cards.Card.ReadOnly>(degraded)
    }

    @Test
    fun `视图卡段落全集与嵌套`() {
        val v = Cards.parse(obj("""
            {"kind":"view","callId":"v1","title":"巡检报告",
             "text":"结论先行",
             "metrics":{"错误率":"0.1%","延迟":"23ms"},
             "table":{"header":["项","值"],"rows":[["a","1"],["b","2"]]},
             "formula":{"name":"预算","placeholder":"填数字"},
             "progress":{"label":"巡检","percent":70},
             "choice":{"name":"方案","options":["甲","乙"]},
             "confirm":"按此执行",
             "sections":[{"text":"嵌套段落"}]}
        """.trimIndent()))
        val vc = assertIs<Cards.Card.View>(v)
        assertEquals(9, vc.sections.size)
        assertIs<Cards.Card.View.Section.Text>(vc.sections[0])
        val metric = assertIs<Cards.Card.View.Section.Metric>(vc.sections[1])
        assertEquals("错误率", metric.label)
        val metric2 = assertIs<Cards.Card.View.Section.Metric>(vc.sections[2])
        assertEquals("延迟", metric2.label)
        val table = assertIs<Cards.Card.View.Section.Table>(vc.sections[3])
        assertEquals(listOf("项", "值"), table.header)
        assertEquals(2, table.rows.size)
        assertIs<Cards.Card.View.Section.Formula>(vc.sections[4])
        val progress = assertIs<Cards.Card.View.Section.Progress>(vc.sections[5])
        assertEquals(70, progress.percent)
        assertIs<Cards.Card.View.Section.Choice>(vc.sections[6])
        assertIs<Cards.Card.View.Section.Confirm>(vc.sections[7])
        assertIs<Cards.Card.View.Section.Text>(vc.sections[8])
    }

    @Test
    fun `超限与未知类型降级不崩溃`() {
        // 未知类型 → null（调用方降级只读）
        assertNull(Cards.parse(obj("""{"kind":"future-card","callId":"x"}""")))
        // 无 callId → null（无法作答）
        assertNull(Cards.parse(obj("""{"kind":"approval","title":"无编号"}""")))
        // 超大卡片 → ReadOnly 折叠
        val big = buildString {
            append("{\"kind\":\"view\",\"callId\":\"big\",\"text\":\"")
            repeat(70000) { append('x') }
            append("\"}")
        }
        val folded = Cards.parse(obj(big))
        assertIs<Cards.Card.ReadOnly>(folded)
        assertTrue(folded.body.contains("过大"))
    }

    @Test
    fun `密钥学习浏览器沙箱卡解析`() {
        val secret = Cards.parse(obj("""{"kind":"secret","callId":"s1","title":"API key","hint":"至少 4 字符"}"""))
        assertIs<Cards.Card.SecretRequest>(secret)
        val learning = Cards.parse(obj("""{"kind":"learning","callId":"l1","title":"复盘","options":["甲","乙"]}"""))
        assertIs<Cards.Card.Learning>(learning)
        val browser = Cards.parse(obj("""{"kind":"browser-handoff","callId":"b1","title":"代浏览"}"""))
        assertIs<Cards.Card.BrowserHandoff>(browser)
        val html = Cards.parse(obj("""{"kind":"html","callId":"h1","html":"<b>hi</b>"}"""))
        val hc = assertIs<Cards.Card.SandboxHtml>(html)
        assertEquals("<b>hi</b>", hc.html)
    }

    @Test
    fun `chat 条目与 state 条目入口`() {
        val chatItem = DsbotModels.decodeChatPage(obj("""{"sessionId":"s","items":[
            {"kind":"answer","questions":[{"id":"q1","question":"选？","options":[{"label":"A"},{"label":"B"}]}],
             "answers":[]}]}""")).items.first()
        val fromChat = assertNotNull(Cards.fromChatItem(chatItem))
        val q = assertIs<Cards.Card.Question>(fromChat)
        assertEquals("q1", q.callId)
        assertEquals(2, q.options.size)

        val stateEntry = Cards.fromStateEntry(obj("""{"kind":"approval","callId":"ap1","title":"审批"}"""))
        assertIs<Cards.Card.Approval>(stateEntry)
    }

    @Test
    fun `作答 payload 构造`() {
        assertEquals("{\"callId\":\"a1\",\"approved\":true}", Cards.approvalAnswer("a1", true))
        assertEquals("{\"callId\":\"q1\",\"choice\":0}", Cards.answerQuestion("q1", 0))
        assertEquals("{\"callId\":\"q1\"}", Cards.dismiss("q1"))
        assertEquals("{\"callId\":\"v1\",\"values\":{\"k\":\"v\"}}", Cards.viewReply("v1", "{\"k\":\"v\"}"))
        assertEquals("{\"callId\":\"s1\",\"value\":\"abcd\"}", Cards.secretSet("s1", "abcd"))
        assertEquals("{\"callId\":\"l1\",\"choice\":1}", Cards.learningAnswer("l1", 1))
        assertEquals("{\"callId\":\"b1\",\"result\":\"done\"}", Cards.browserGiveBack("b1", "done"))
        // 值转义（密钥含引号/反斜杠）
        assertEquals("{\"callId\":\"s2\",\"value\":\"a\\\"b\"}", Cards.secretSet("s2", "a\"b"))
    }

    @Test
    fun `深度与条目上限截断降级`() {
        // 17 层嵌套 sections：walk 深度终止，产出有界段落（不崩溃）
        var json = """{"kind":"view","callId":"deep","title":"深嵌套","text":"顶层","sections":["""
        for (i in 1..17) {
            json += """{"text":"层","sections":["""
        }
        json += """{"text":"最底层"}"""
        for (i in 1..18) json += "]}"
        val v = Cards.parse(checkNotNull(Json.parse(json)) as Json.Value.Object)
        kotlin.test.assertTrue(v is Cards.Card.View || v is Cards.Card.ReadOnly)

        // 65 个选项截断到 MAX_ITEMS
        val opts = (1..65).joinToString(",") { """{"label":"选项$it"}""" }
        val q = Cards.parse(checkNotNull(Json.parse("""{"kind":"question","callId":"many","question":"多","options":[$opts]}""")) as Json.Value.Object)
        kotlin.test.assertEquals(64, (q as Cards.Card.Question).options.size)
    }

    @Test
    fun `closed 条目只读呈现不再交互`() {
        val item = DsbotModels.decodeChatPage(checkNotNull(Json.parse("""{"sessionId":"s","items":[
            {"kind":"closed","questions":[{"id":"q1","question":"已答","options":[{"label":"A"}]}]}]}""")) as Json.Value.Object).items.first()
        val card = kotlin.test.assertNotNull(Cards.fromChatItem(item))
        val ro = kotlin.test.assertIs<Cards.Card.ReadOnly>(card)
        kotlin.test.assertEquals("已答", ro.title)

        // 多问题条目标注余量
        val multi = DsbotModels.decodeChatPage(checkNotNull(Json.parse("""{"sessionId":"s","items":[
            {"kind":"answer","questions":[{"id":"q1","question":"首","options":[{"label":"A"}]},{"id":"q2","question":"次"}]}]}""")) as Json.Value.Object).items.first()
        val note = kotlin.test.assertNotNull(Cards.fromChatItem(multi))
        kotlin.test.assertTrue((note as Cards.Card.ReadOnly).title!!.contains("2"))  // 2 个问题
    }
}
