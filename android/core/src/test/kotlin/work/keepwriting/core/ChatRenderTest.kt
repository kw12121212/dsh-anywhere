package work.keepwriting.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ChatRender/StreamBuffer 测试（dsbot-bot-chat）：chat-page 夹具驱动渲染语义，
 * 流式状态机覆盖正常、乱序与定格路径。
 */
class ChatRenderTest {
    private fun fixturePage(): DsbotModels.ChatPage =
        DsbotModels.decodeChatPage(fixtureValue("chat-page.json"))

    private fun fixtureValue(name: String): Json.Value {
        val res = ChatRenderTest::class.java.getResource("/dsbot/$name")
        return ((checkNotNull(Json.parse(checkNotNull(res).readText(Charsets.UTF_8))) as Json.Value.Object)
            .members.getValue("value"))
    }

    @Test
    fun `夹具页渲染出用户与 Bot 消息`() {
        val page = ChatRender.render(fixturePage())
        val messages = page.items.filterIsInstance<ChatRender.Item.Message>()
        assertTrue(messages.isNotEmpty())
        assertTrue(messages.any { it.fromUser })
        assertTrue(messages.any { !it.fromUser })
        // 分段边界/checkpoint 类 kind 不产生可见条目：夹具含 event/answer/closed，
        // 渲染后消息条数 = items 里的 user/bot 条数
        val raw = fixturePage().items
        val textKinds = raw.count { it.kind == "user" || it.kind == "bot" }
        assertEquals(textKinds, messages.size)
    }

    @Test
    fun `附件引用条目透传原始 JSON`() {
        val json = """
            {"sessionId":"s1","items":[
              {"kind":"image","image":{"attachmentId":"sha256:aa","mediaType":"image/png","bytes":70,"width":1,"height":1,"name":"t.png"}},
              {"kind":"file","file":{"attachmentId":"sha256:bb","name":"doc.pdf","bytes":1024}},
              {"kind":"file","file":{"url":"no-ref"}}]}
        """.trimIndent()
        val page = ChatRender.render(DsbotModels.decodeChatPage(checkNotNull(Json.parse(json))))
        val image = page.items[0] as ChatRender.Item.Image
        assertTrue(image.attachmentRefJson!!.contains("\"attachmentId\":\"sha256:aa\""))
        assertEquals("t.png", image.name)
        val file = page.items[1] as ChatRender.Item.FileAttachment
        assertEquals("doc.pdf", file.name)
        assertTrue(file.refJson.contains("sha256:bb"))
        // 无 attachmentId 的 file 引用不可见（无法经会话接口取回）
        assertEquals(2, page.items.size)
    }

    @Test
    fun `夹具对象形态 cursor 透传`() {
        // 真实 0.3.x 的 chat 应答 cursor 是对象 {"part","beforeSeq"}（夹具同构）：
        // 解码与渲染必须保留原始值形态，字符串化会丢结构、按字符串解码会静默变 null
        val page = fixturePage()
        val cursor = page.cursor
        assertTrue(cursor is Json.Value.Object, "夹具 cursor 应为对象形态")
        assertEquals("""{"part":0,"beforeSeq":2497}""", Json.encode(cursor!!))
        val rendered = ChatRender.render(page)
        assertEquals(cursor, rendered.cursor)
        assertFalse(rendered.exhausted)
    }

    @Test
    fun `工具与图片条目折叠为卡与引用`() {
        val json = """
            {"sessionId":"s1","items":[
              {"kind":"tool","call":{"name":"web_search"},"label":"搜索市场数据"},
              {"kind":"image","image":{"url":"img://x/1","name":"chart.png"}},
              {"kind":"answer","note":"已选择方案 A"},
              {"kind":"checkpoint","turn":9},
              {"kind":"part-boundary"}]}
        """.trimIndent()
        val page = ChatRender.render(DsbotModels.decodeChatPage(checkNotNull(Json.parse(json))))
        assertEquals(3, page.items.size)
        val tool = page.items[0] as ChatRender.Item.ToolCard
        assertEquals("web_search", tool.name)
        assertEquals("搜索市场数据", tool.summary)
        val image = page.items[1] as ChatRender.Item.Image
        assertEquals("img://x/1", image.ref)
        assertEquals("chart.png", image.name)
        val line = page.items[2] as ChatRender.Item.SystemLine
        assertEquals("已选择方案 A", line.text)
    }

    @Test
    fun `未知 kind 与空文本降级跳过`() {
        val json = """
            {"sessionId":"s1","items":[
              {"kind":"future-kind","payload":{"x":1}},
              {"kind":"user"},
              {"kind":"event","role":"request"}]}
        """.trimIndent()
        val page = ChatRender.render(DsbotModels.decodeChatPage(checkNotNull(Json.parse(json))))
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `cursor 透传与到最早判定`() {
        // 应答带 cursor（字符串形态也放行）：可再取更早一页
        val withCursor = ChatRender.render(DsbotModels.decodeChatPage(checkNotNull(Json.parse("""{"sessionId":"s1","cursor":"c1","items":[]}"""))))
        assertEquals("c1", (withCursor.cursor as Json.Value.StringValue).value)
        assertFalse(withCursor.exhausted)
        // 应答不带 cursor：到最早
        val exhausted = ChatRender.render(DsbotModels.decodeChatPage(checkNotNull(Json.parse("""{"sessionId":"s1","items":[]}"""))))
        assertNull(exhausted.cursor)
        assertTrue(exhausted.exhausted)
    }

    @Test
    fun `message 事件追加判定升级或新增`() {
        // stream end 已定格的无序号条目：message 同文本升级补序号，不重复追加
        val items = mutableListOf<ChatRender.Item>(
            ChatRender.Item.Message(fromUser = true, markdown = "问", turn = null, seq = null),
            ChatRender.Item.Message(fromUser = false, markdown = "答", turn = null, seq = null),
        )
        val upgrade = ChatRender.appendBotMessage(items, "答", 99)
        assertTrue(upgrade is ChatRender.AppendDecision.Upgrade)
        assertEquals(1, (upgrade as ChatRender.AppendDecision.Upgrade).index)
        assertEquals(99L, upgrade.item.seq)
        // 文本不同或上一条非无序号 Bot 消息：追加
        val add = ChatRender.appendBotMessage(items, "新回答", 100)
        assertTrue(add is ChatRender.AppendDecision.Add)
        val addAfterSeq = ChatRender.appendBotMessage(
            listOf(ChatRender.Item.Message(fromUser = false, markdown = "答", turn = null, seq = 5)),
            "答",
            6,
        )
        assertTrue(addAfterSeq is ChatRender.AppendDecision.Add)
    }

    @Test
    fun `流式拼接正常路径`() {
        val b = ChatRender.StreamBuffer()
        assertEquals("", b.onStream("b1", "s1", "a1", "start", null))
        assertTrue(b.inProgress())
        assertEquals("你", b.onStream("b1", "s1", "a1", null, "你"))
        assertEquals("你好", b.onStream("b1", "s1", "a1", null, "好"))
        assertEquals("你好！", b.onStream("b1", "s1", "a1", "end", "！"))
        assertFalse(b.inProgress())
        assertEquals("你好！", b.settle())
        assertNull(b.settle()) // 定格后清空
    }

    @Test
    fun `乱序容错与幂等`() {
        val b = ChatRender.StreamBuffer()
        assertNull(b.onStream("b1", "s1", "a1", null, "孤儿文本")) // 未 start 丢弃
        assertNull(b.onStream("b1", "s1", "a1", "end", null))
        assertEquals("", b.onStream("b1", "s1", "a1", "start", null))
        assertNull(b.onStream("b1", "s1", "other-attempt", null, "错代文本")) // 非本 attempt 丢弃
        assertEquals("正", b.onStream("b1", "s1", "a1", null, "正"))
        assertEquals("正常", b.onStream("b1", "s1", "a1", "end", "常"))
        assertEquals("正常", b.onStream("b1", "s1", "a1", "end", "常")) // 重复 end 幂等
        assertEquals("正常", b.settle())
    }

    @Test
    fun `message 事件可直接定格未结束气泡`() {
        val b = ChatRender.StreamBuffer()
        assertEquals("", b.onStream("b1", "s1", "a1", "start", null))
        b.onStream("b1", "s1", "a1", null, "写一半")
        assertTrue(b.inProgress())
        assertEquals("写一半", b.settle()) // message 到达即定格（无 end 也成立）
    }

    @Test
    fun `btw 条目与普通 event 条目分流`() {
        val json = """
            {"sessionId":"s","items":[
              {"kind":"event","role":"btw","id":"btw-9","from":"研究主管","text":"插话"},
              {"kind":"event","role":"btw","callId":"btw-x","text":"无 id 用 callId"},
              {"kind":"event","role":"request","text":"普通事件"}]}
        """.trimIndent()
        val page = ChatRender.render(DsbotModels.decodeChatPage(checkNotNull(Json.parse(json))))
        val btw = page.items[0] as ChatRender.Item.Btw
        assertEquals("btw-9", btw.id)
        assertEquals("插话", btw.text)
        assertEquals("研究主管", btw.from)
        val btw2 = page.items[1] as ChatRender.Item.Btw
        assertEquals("btw-x", btw2.id)
        assertNull(btw2.from)
        // 非 btw 的 event 走 SystemLine 回退
        val line = page.items[2] as ChatRender.Item.SystemLine
        assertEquals("普通事件", line.text)
    }

}
