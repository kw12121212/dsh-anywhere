package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dsbot-rich-render：show_view 卡片（deep-view 0.1.2 组件树）的校验、公式、图表、导出与作答，以及 Markdown 增补。 */
class ViewCardTest {
    private fun obj(t: String) = Json.parse(t) as Json.Value.Object

    private fun decode(view: String, title: String = "T") =
        ViewCard.decode("s", obj("""{"id":"v1","title":"$title","view":$view}"""))

    private fun eval(src: String, inputs: Map<String, Any> = emptyMap()): Any? {
        val c = ViewFormula.compile(src)
        return c.tree?.let { ViewFormula.evaluate(it, ViewFormula.scope(inputs, emptyMap())).first }
    }

    @Test
    fun formulaSemanticsMatchDeepView() {
        assertEquals(7.0, eval("1 + 2 * 3"))
        assertEquals(9.0, eval("(1 + 2) * 3"))
        assertEquals("a1", eval("'a' + 1"))
        assertEquals(30.0, eval("price * qty", mapOf("price" to 10.0, "qty" to 3.0)))
        assertEquals("1,234.50", eval("format(1234.5, 2)"))
        assertEquals("12.5%", eval("percent(0.125, 1)"))
        assertEquals("2.50", eval("fixed(2.499, 2)"))
        assertEquals(3.0, eval("round(2.5)")) // JS Math.round：.5 向上
        assertEquals(5.0, eval("clamp(9, 0, 5)"))
        assertEquals("高", eval("x > 3 ? '高' : '低'", mapOf("x" to 4.0)))
        assertEquals(true, eval("!false && 1 == 1"))
        assertEquals(false, eval("1 == '1'")) // 严格相等
        assertEquals("0.3", ViewFormula.show(eval("0.1 + 0.2")))
        assertEquals("3", ViewFormula.show(3.0))
        assertEquals("Infinity", ViewFormula.show(eval("1 / 0")))
        // 解析失败与未知函数、引用自身
        assertTrue(ViewFormula.compile("1 +").error!!.contains("ends too early"))
        assertTrue(ViewFormula.compile("evil(1)").error!!.contains("unknown function"))
        assertTrue(ViewFormula.compile("'abc").error!!.contains("unclosed"))
        val loop = ViewFormula.scope(emptyMap(), mapOf("a" to ViewFormula.compile("a + 1").tree!!))
        assertTrue(ViewFormula.evaluate(ViewFormula.compile("a").tree!!, loop).second!!.contains("refers to itself"))
        assertTrue(ViewFormula.evaluate(ViewFormula.compile("'x' * 2").tree!!, loop).second!!.contains("not a number"))
    }

    @Test
    fun measuredBlueprintDecodesAndAnswers() {
        val entry = Json.parse(checkNotNull(ViewCardTest::class.java.getResource("/dsbot/view-blueprint-choice.json")).readText())
        val bp = assertIs<ViewCard.Decoded.Ok>(ViewCard.decode("s-1", entry)).blueprint
        assertTrue(bp.interactive)
        assertEquals(listOf("direction", "scene", "heading", "dark"), ViewCard.inputsOf(bp.root).map { ViewCard.str(it, "id") })
        val body = ViewCard.reply(bp, "go", ViewCard.Answer.Submit, mapOf("scene" to "scene-2"))
        val parsed = Json.parse(body) as Json.Value.Object
        assertEquals("s-1", (parsed.members["sessionId"] as Json.Value.StringValue).value)
        assertEquals("go", (parsed.members["action"] as Json.Value.StringValue).value)
        val inputs = ((parsed.members["values"] as Json.Value.Object).members["inputs"] as Json.Value.Object).members
        assertEquals("scene-2", (inputs["scene"] as Json.Value.StringValue).value) // 动过的取当前值
        assertEquals("direction-1", (inputs["direction"] as Json.Value.StringValue).value) // 没动过的取初始值
    }

    private val chartView = """{"type":"stack","let":{"total":"a + b"},"children":[
        {"type":"slider","id":"a","label":"A","min":0,"max":10,"value":2},
        {"type":"number","id":"b","label":"B","value":3},
        {"type":"metric","label":"合计","value":{"=":"total"}},
        {"type":"chart","kind":"line","categories":["一月","二月"],"series":[{"name":"S","values":[1,{"=":"total"}]}],"unit":"万"},
        {"type":"progress","value":{"=":"a"},"max":10},
        {"type":"table","columns":[{"key":"k","label":"名, 称"},{"key":"v","label":"值"}],"rows":[["甲",1],["乙\"x\"",{"=":"total * 2"}]]},
        {"type":"callout","text":"注意","tone":"warn"},
        {"type":"sources","items":[{"label":"官网","url":"https://example.com"}]},
        {"type":"toggle","id":"on","label":"开"},
        {"type":"options","id":"pick","options":[{"label":"甲"},{"label":"乙"}],"multi":true}]}"""

    @Test
    fun formulasDriveChartsMetricsProgressAndCsv() {
        val bp = assertIs<ViewCard.Decoded.Ok>(decode(chartView)).blueprint
        val nodes = ViewCard.walk(bp.root)
        val lookup = ViewCard.scope(bp.root, mapOf("a" to 7.0))
        assertEquals("10", ViewCard.text(nodes.first { ViewCard.str(it, "type") == "metric" }.members["value"], lookup))
        val chart = ViewCard.chart(nodes.first { ViewCard.str(it, "type") == "chart" }, lookup)!!
        assertEquals("line", chart.kind)
        assertEquals(listOf(1.0, 10.0), chart.series.single().values)
        assertEquals(0.0 to 10.0, chart.range)
        assertEquals(70.0, ViewCard.progressPercent(nodes.first { ViewCard.str(it, "type") == "progress" }, lookup))
        assertEquals("\"名, 称\",值\r\n甲,1\r\n\"乙\"\"x\"\"\",20", ViewCard.tableCsv(nodes.first { ViewCard.str(it, "type") == "table" }, lookup))
        assertEquals(false, ViewCard.inputDefault(nodes.first { ViewCard.str(it, "type") == "toggle" }))
        val body = Json.parse(ViewCard.reply(bp, "pick", ViewCard.Answer.Options(listOf("甲", "乙")), mapOf("a" to 7.0, "on" to true))) as Json.Value.Object
        val values = body.members["values"] as Json.Value.Object
        assertEquals("""{"a":7,"b":3,"on":true}""", Json.encode(values.members["inputs"]!!))
        assertEquals("""["甲","乙"]""", Json.encode(values.members["selected"]!!))
    }

    @Test
    fun malformedOrOversizeDegrades() {
        fun reason(v: String) = assertIs<ViewCard.Decoded.Invalid>(decode(v)).reason
        assertTrue(reason("""{"type":"chart","categories":["a"],"series":[{"values":[1,2]}]}""").contains("2 values for 1 categories"))
        assertTrue(reason("""{"type":"chart","kind":"pie","categories":["a"],"series":[{"values":[1]}]}""").contains("unknown chart"))
        assertTrue(reason("""{"type":"chart","kind":"donut","categories":["a"],"series":[{"values":[1]},{"values":[2]}]}""").contains("one series"))
        assertTrue(reason("""{"type":"chart","categories":["a"],"series":[{"values":["x"]}]}""").contains("finite number"))
        val many = (1..9).joinToString(",") { """{"values":[1]}""" }
        assertTrue(reason("""{"type":"chart","categories":["a"],"series":[$many]}""").contains("more than 8 series"))
        val points = (1..51).joinToString(",") { "\"c$it\"" }
        assertTrue(reason("""{"type":"chart","categories":[$points],"series":[{"values":[${(1..51).joinToString(",")}]}]}""").contains("more than 50 points"))
        assertTrue(reason("""{"type":"script","src":"x"}""").contains("unknown component"))
        assertTrue(reason("""{"type":"text","text":"x","onClick":"y"}""").contains("unknown property"))
        assertTrue(reason("""{"type":"metric","label":"x","value":{"=":"missing * 2"}}""").contains("unknown name"))
        assertTrue(reason("""{"type":"slider","id":"a","label":"A","min":5,"max":1,"value":2}""").contains("more than min"))
        assertTrue(reason("""{"type":"sources","items":[{"label":"x","url":"javascript:alert(1)"}]}""").contains("http(s)"))
        // 9 层嵌套超过 8 层；65 个组件超过 64 个
        var deep = """{"type":"text","text":"x"}"""
        repeat(8) { deep = """{"type":"stack","children":[$deep]}""" }
        assertTrue(reason(deep).contains("levels exceeds"))
        val kids = (1..16).joinToString(",") { """{"type":"stack","children":[{"type":"text","text":"a"},{"type":"text","text":"b"},{"type":"text","text":"c"}]}""" }
        assertTrue(reason("""{"type":"stack","children":[$kids]}""").contains("components exceeds"))
        assertIs<ViewCard.Decoded.Invalid>(ViewCard.decode("s", obj("""{"title":"x","view":{}}""")))
        assertIs<ViewCard.Decoded.Invalid>(ViewCard.decode("s", Json.parse("[1]")))
        // 降级摘要
        val ok = assertIs<ViewCard.Decoded.Ok>(decode(chartView)).blueprint
        assertTrue(ViewCard.summary(ok.root).contains("合计：5"))
    }

    @Test
    fun formAndConfirmReplies() {
        val bp = assertIs<ViewCard.Decoded.Ok>(decode("""{"type":"stack","children":[{"type":"form","id":"f","fields":[{"id":"name","label":"名"}]},{"type":"confirm","id":"c"}]}""")).blueprint
        assertEquals(
            """{"sessionId":"s","viewId":"v1","action":"f","values":{"fields":{"name":"张三"}}}""",
            ViewCard.reply(bp, "f", ViewCard.Answer.Form(mapOf("name" to "张三")), emptyMap()),
        )
        assertEquals("""{"sessionId":"s","viewId":"v1","action":"c","values":{"confirmed":false}}""", ViewCard.reply(bp, "c", ViewCard.Answer.Confirm(false), emptyMap()))
        assertEquals("""{"sessionId":"s","viewId":"v1","action":"f","values":{"canceled":true}}""", ViewCard.reply(bp, "f", ViewCard.Answer.FormCanceled, emptyMap()))
        assertFalse(assertIs<ViewCard.Decoded.Ok>(decode("""{"type":"text","text":"只读"}""")).blueprint.interactive)
    }

    @Test
    fun formulaEdgeSemantics() {
        assertEquals("c", eval("false ? 'a' : true ? 'c' : 'd'")) // 三元右结合
        assertEquals("x", eval("'' || 'x'")) // || 返回原值
        assertEquals(0.0, eval("0 || 0"))
        assertEquals(false, eval("!!(0 / 0)")) // NaN 为假
        assertEquals(1.0, eval("7 % 3"))
        assertEquals(16.0, eval("'0x10' * 1")) // JS Number('0x10')
        assertTrue(ViewFormula.evaluate(ViewFormula.compile("'NaN' * 1").tree!!, ViewFormula.scope(emptyMap(), emptyMap())).second!!.contains("not a number"))
        assertTrue(ViewFormula.compile("(".repeat(70) + "1" + ")".repeat(70)).error!!.contains("nested more than"))
        assertEquals("1e-7", ViewFormula.show(1e-7))
        assertEquals("1.5e+21", ViewFormula.show(1.5e21))
        assertEquals("123456789012", ViewFormula.show(123456789012.0))
    }

    @Test
    fun sliderSnapStaysInRange() {
        assertEquals(8.0, ViewCard.snap(10.0, 0.0, 10.0, 4.0)) // 不越过 max 的最后一格
        assertEquals(4.0, ViewCard.snap(5.0, 0.0, 10.0, 4.0))
        assertEquals(0.3, ViewCard.snap(0.3, 0.0, 0.3, 0.1)) // 不出现 0.30000000000000004
        assertEquals(0.2, ViewCard.snap(0.24, 0.0, 0.3, 0.1))
        assertEquals(1.1, ViewCard.snap(1.12, 0.1, 2.0, 0.5))
        assertEquals(10.0, ViewCard.snap(99.0, 0.0, 10.0, null))
    }

    @Test
    fun validationExtrasAndSingleOptionReply() {
        fun reason(v: String) = assertIs<ViewCard.Decoded.Invalid>(decode(v)).reason
        assertTrue(reason("""{"type":"stack","children":[{"type":"stack","let":{"x":"1"},"children":[{"type":"text","text":"a"}]}]}""").contains("outermost"))
        assertTrue(reason("""{"type":"html","html":"<b>x</b>","summary":"s","height":-1}""").contains("height"))
        assertTrue(reason("""{"type":"submit","id":"s","label":3}""").contains("label"))
        assertTrue(reason("""{"type":"form","id":"f","fields":[{"id":"a","label":"A","readOnly":"yes"}]}""").contains("readOnly"))
        assertTrue(reason("""{"type":"choice","id":"c","label":"C","options":[{"label":"x","value":[1]}]}""").contains("value"))
        // choice 缺省取第一个选项，toggle 缺省 false
        val bp = assertIs<ViewCard.Decoded.Ok>(decode("""{"type":"stack","children":[{"type":"choice","id":"c","label":"C","options":[{"label":"甲","value":1},{"label":"乙","value":2}]},{"type":"options","id":"o","options":[{"label":"好"},{"label":"不好"}]}]}""")).blueprint
        assertEquals(1.0, ViewCard.inputDefault(ViewCard.inputsOf(bp.root).single()))
        assertEquals(
            """{"sessionId":"s","viewId":"v1","action":"o","values":{"inputs":{"c":1},"selected":["好"]}}""",
            ViewCard.reply(bp, "o", ViewCard.Answer.Options(listOf("好")), emptyMap()),
        )
    }

    @Test
    fun markdownImageFixturesAndRequests() {
        fun fixture(n: String) = (Json.parse(checkNotNull(ViewCardTest::class.java.getResource("/dsbot/$n")).readText()) as Json.Value.Object).members["value"]
        val ok = MarkdownImage.decode(fixture("markdown-image-ok.json"))!!
        assertEquals("image/png", ok.mime)
        assertFalse(ok.unchanged)
        assertEquals(ok.size, java.util.Base64.getDecoder().decode(ok.base64).size.toLong())
        assertTrue(Regex("^\\d+:[0-9a-f]{16}$").matches(ok.rev!!))
        val same = MarkdownImage.decode(fixture("markdown-image-unchanged.json"))!!
        assertTrue(same.unchanged)
        assertNull(same.base64)
        assertEquals(ok.rev, same.rev)
        assertNull(MarkdownImage.decode(Json.parse("""{"mime":"image/png"}""")))
        assertEquals("""{"sessionId":"s","path":"/w/a.png"}""", MarkdownImage.request("s", "/w/a.png", null))
        assertEquals("""{"sessionId":"s","path":"/w/a.png","knownRev":"70:ab"}""", MarkdownImage.request("s", "/w/a.png", "70:ab"))
        assertEquals("QUJD", MarkdownImage.dataUriBase64("data:image/png;base64,QUJD"))
        assertNull(MarkdownImage.dataUriBase64("data:text/html;base64,QUJD"))
        assertNull(MarkdownImage.dataUriBase64("data:image/svg+xml,<svg/>"))
    }

    @Test
    fun markdownTasksImagesAndTsv() {
        val blocks = MarkdownParse.parseBlocks(
            """- [ ] 待办
- [x] 已完成
![截图](/home/u/shot.png)
![带空格](</tmp/a b.png>)
![网图](https://example.com/a.png)
| 名 | 值 |
|---|---|
| a	b | **1** |
| c |""",
        )
        assertEquals(MarkdownParse.MdBlock.Task(false, listOf(MarkdownParse.InlinePart(MarkdownParse.InlineKind.PLAIN, "待办"))), blocks[0])
        assertTrue((blocks[1] as MarkdownParse.MdBlock.Task).checked)
        assertEquals(MarkdownParse.MdBlock.Image("截图", "/home/u/shot.png"), blocks[2])
        assertEquals(MarkdownParse.MdBlock.Image("带空格", "/tmp/a b.png"), blocks[3])
        assertEquals("https://example.com/a.png", (blocks[4] as MarkdownParse.MdBlock.Image).src)
        assertEquals("名\t值\na b\t1\nc\t", MarkdownParse.tsv(blocks[5] as MarkdownParse.MdBlock.Table))
        // 行内图片不是独占一行时仍是普通段落（不吞文字）
        assertIs<MarkdownParse.MdBlock.Paragraph>(MarkdownParse.parseBlocks("见图 ![x](y.png) 说明")[0])
        assertNull(MarkdownParse.parseBlocks("- [y] 不是任务").filterIsInstance<MarkdownParse.MdBlock.Task>().firstOrNull())
    }
}
