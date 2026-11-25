package work.keepwriting.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RoomChat 构造/解码测试（dsbot-group-chat，0.3.5 实测形态）。 */
class RoomChatTest {

    @Test
    fun `room-chat 的 cursor 为 JSON null 视为到最早`() {
        // 单分段群到最早时上游回 cursor:null：不能显示「更早消息」，否则点按会重取最新一页并前插造成重复
        val page = RoomChat.decodeRoomPage(Json.parse("""{"roomId":"r","items":[],"cursor":null}""")!!)
        assertNull(page.cursor)
        assertNotNull(RoomChat.decodeRoomPage(Json.parse("""{"roomId":"r","items":[],"cursor":{"part":0,"beforeSeq":9}}""")!!).cursor)
    }

    @Test
    fun `room-chat 应答解码成员归属与运行态`() {
        val json = """
            {"roomId":"room-1","announcement":"每周一篇","running":["b2"],"speaking":[],
             "cursor":{"part":0,"beforeSeq":1},
             "items":[
               {"kind":"user","text":"群消息","seq":1},
               {"kind":"bot","botId":"bot-1","name":"研究主管","color":"blue","text":"收到","seq":2},
               {"kind":"pass","botId":"bot-1"}]}
        """.trimIndent()
        val page = RoomChat.decodeRoomPage(checkNotNull(Json.parse(json)))
        assertEquals("room-1", page.roomId)
        assertEquals(listOf("b2"), page.running)
        assertTrue(page.cursor is Json.Value.Object)
        assertEquals(3, page.items.size)
        // 成员归属：bot 条目带 botId/name/color
        val speaker = assertNotNull(RoomChat.speakerOf(page.items[1]))
        assertEquals("bot-1", speaker.botId)
        assertEquals("研究主管", speaker.name)
        assertEquals("blue", speaker.color)
        // 用户消息无归属
        assertNull(RoomChat.speakerOf(page.items[0]))
    }

    @Test
    fun `群端点 payload 构造`() {
        assertEquals("{\"roomId\":\"room-1\"}", RoomChat.roomChatRequest("room-1"))
        assertEquals(
            "{\"roomId\":\"room-1\",\"cursor\":{\"part\":0}}",
            RoomChat.roomChatRequest("room-1", "{\"part\":0}"),
        )
        assertEquals("{\"roomId\":\"room-1\"}", RoomChat.stopRoomWork("room-1"))
        assertEquals("{\"roomId\":\"room-1\"}", RoomChat.roomProgress("room-1"))
        assertEquals(
            "{\"roomId\":\"room-1\",\"text\":\"每周更新\"}",
            RoomChat.goalDraft("room-1", "每周更新"),
        )
        assertEquals(
            "{\"roomId\":\"room-1\",\"action\":\"advance\"}",
            RoomChat.goalControl("room-1", "advance"),
        )
    }

    @Test
    fun `群渲染条目与成员归属合并`() {
        val json = """
            {"roomId":"r","items":[
              {"kind":"user","text":"问"},
              {"kind":"bot","botId":"b1","name":"甲","color":"blue","text":"答"}]}
        """.trimIndent()
        val page = RoomChat.decodeRoomPage(checkNotNull(Json.parse(json)))
        val rendered = RoomChat.renderRoom(page)
        val messages = rendered.filterIsInstance<ChatRender.Item.Message>()
        assertEquals(2, messages.size)
        // pass 条目不可见（未知 kind 降级）
        val sp = RoomChat.speakerOf(page.items[1])
        val merged = messages[1].copy(speakerName = sp?.name, speakerColor = sp?.color)
        assertEquals("甲", merged.speakerName)
        assertEquals("blue", merged.speakerColor)
    }

    private fun realFixture(name: String): Json.Value {
        val text = checkNotNull(RoomChatTest::class.java.getResource("/dsbot/$name")).readText(Charsets.UTF_8)
        return checkNotNull((Json.parse(text) as Json.Value.Object).members["value"])
    }

    @Test
    fun `0_3_10 实测夹具的 running 布尔与 speaking null 解码为空运行者`() {
        // dsbot-group-rounds 实测：0.3.10 room-chat 的 running 为布尔、speaking 为单个 botId 或 null
        val page = RoomChat.decodeRoomPage(realFixture("room-chat-rounds.json"))
        assertEquals(emptyList(), page.running)
        assertEquals(emptyList(), page.speaking)
        assertEquals(57, page.items.size)
    }

    @Test
    fun `0_3_10 running 为 true 时以 speaking 成员作为运行者`() {
        val page = RoomChat.decodeRoomPage(checkNotNull(Json.parse("""{"roomId":"r","items":[],"running":true,"speaking":"b7"}""")))
        assertEquals(listOf("b7"), page.running)
        assertEquals(listOf("b7"), page.speaking)
        val idle = RoomChat.decodeRoomPage(checkNotNull(Json.parse("""{"roomId":"r","items":[],"running":false,"speaking":"b7"}""")))
        assertEquals(emptyList(), idle.running)
        val unnamed = RoomChat.decodeRoomPage(checkNotNull(Json.parse("""{"roomId":"r","items":[],"running":true,"speaking":null}""")))
        assertEquals(emptyList(), unnamed.running)
    }

    @Test
    fun `实测夹具的轮次事件行全部可见且服务端展开的轮次发言保留成员归属`() {
        I18n.lang = ChatBars.Lang.ZH
        val page = RoomChat.decodeRoomPage(realFixture("room-chat-rounds.json"))
        val rendered = RoomChat.renderRoom(page)
        val lines = rendered.filterIsInstance<ChatRender.Item.SystemLine>()
        // 实测 16 条 event=working 轮次事件：此前因无 text 而不可见
        assertEquals(16, lines.size)
        assertTrue(lines.all { it.text!!.endsWith(" 开始做了") }, "文案=${lines.map { it.text }}")
        val bots = rendered.filterIsInstance<ChatRender.Item.Message>().filter { !it.fromUser }
        assertEquals(35, bots.size)
        assertTrue(bots.all { it.speakerName != null }, "轮次发言须带成员名")
    }

    private fun eventLine(event: String, extra: String = "", roster: Map<String, RoomChat.Speaker> = emptyMap()): String? =
        RoomChat.roundEventLine(
            DsbotModels.ChatItem("event", null, null, null, null,
                checkNotNull(Json.parse("""{"kind":"event","event":"$event","botId":"b1","name":"甲"$extra}"""))),
            roster,
        )

    @Test
    fun `轮次事件中文文案覆盖全部 ROUND_EVENTS`() {
        I18n.lang = ChatBars.Lang.ZH
        assertEquals("甲 开始做了", eventLine("working"))
        assertEquals("甲 停下来等你批准", eventLine("approval"))
        assertEquals("甲 做完了", eventLine("done"))
        assertEquals("你停下了 甲 的工作", eventLine("stopped"))
        assertEquals("甲 没有及时回应", eventLine("missed"))
        assertEquals("甲 查看了自己的对话", eventLine("lookup"))
        assertEquals("甲 在自己的对话里查找「周报」", eventLine("lookup", ""","text":"周报""""))
        assertEquals("甲 的工作被重启打断，已经接着做", eventLine("resumed"))
        assertEquals("甲 的工作又被重启打断，已停下", eventLine("cut"))
        assertNull(eventLine("paused"), "非 room-chat 轮次事件不按轮次行渲染")
    }

    @Test
    fun `failed 原因与网页端 failureLabel 同序：已知码标签先于原始消息`() {
        I18n.lang = ChatBars.Lang.ZH
        // 上游 failed 的 text 是原始错误消息；已知码（FAILURES）以本地化标签为先
        assertEquals("甲 停下了：账户额度已用尽。换个模型，或让它继续。", eventLine("failed", ""","text":"429 quota exceeded","code":"QUOTA""""))
        assertEquals("甲 停下了：连不上模型服务。换个模型，或让它继续。", eventLine("failed", ""","code":"TRANSPORT""""))
        assertEquals("甲 停下了：上游原始消息。换个模型，或让它继续。", eventLine("failed", ""","text":"上游原始消息","code":"WEIRD""""))
        assertEquals("甲 停下了：WEIRD。换个模型，或让它继续。", eventLine("failed", ""","code":"WEIRD""""))
        assertEquals("甲 停下了：未知错误。换个模型，或让它继续。", eventLine("failed"))
    }

    @Test
    fun `轮次事件英文文案覆盖全部 ROUND_EVENTS 与 failed 回退`() {
        I18n.lang = ChatBars.Lang.EN
        try {
            assertEquals("甲 started on it", eventLine("working"))
            assertEquals("甲 paused for your approval", eventLine("approval"))
            assertEquals("甲 finished its work", eventLine("done"))
            assertEquals("You stopped 甲's work", eventLine("stopped"))
            assertEquals("甲 didn't answer in time", eventLine("missed"))
            assertEquals("甲 looked up its own chat", eventLine("lookup"))
            assertEquals("甲 looked up its own chat for “weekly”", eventLine("lookup", ""","text":"weekly""""))
            assertEquals("甲 was cut off by a restart and picked its work back up", eventLine("resumed"))
            assertEquals("甲 was cut off by a restart again and stopped", eventLine("cut"))
            assertEquals("甲 stopped: The account is out of quota. Switch its model or ask it to go on.", eventLine("failed", ""","text":"raw","code":"QUOTA""""))
            assertEquals("甲 stopped: raw. Switch its model or ask it to go on.", eventLine("failed", ""","text":"raw""""))
            assertEquals("甲 stopped: Unknown error. Switch its model or ask it to go on.", eventLine("failed"))
            assertEquals("a Bot finished its work", RoomChat.roundEventLine(
                DsbotModels.ChatItem("event", null, null, null, null, checkNotNull(Json.parse("""{"kind":"event","event":"done"}"""))),
            ))
        } finally {
            I18n.lang = ChatBars.Lang.ZH
        }
    }

    @Test
    fun `成员名称与颜色以名册为先，条目值为后备，都缺时不显示原始 id`() {
        I18n.lang = ChatBars.Lang.ZH
        val roster = RoomChat.roster(checkNotNull(Json.parse("""
            {"bots":[{"id":"b1","name":"研究主管（新名）","color":"orange"},{"id":"b2","name":"运维值班"}]}
        """.trimIndent())))
        val page = RoomChat.decodeRoomPage(checkNotNull(Json.parse("""
            {"roomId":"r","items":[
              {"id":"c:0","kind":"event","event":"working","botId":"b1","name":"研究主管"},
              {"id":"c:1","kind":"bot","botId":"b1","name":"研究主管","text":"答"},
              {"id":"c:2","kind":"bot","botId":"b2","name":"旧名","color":"green","text":"补充"},
              {"id":"c:3","kind":"bot","botId":"b3","name":"外部成员","color":"pink","text":"路过"},
              {"id":"c:4","kind":"event","event":"done","botId":"session-x"}]}
        """.trimIndent())))
        val rendered = RoomChat.renderRoom(page, roster)
        assertEquals(ChatRender.Item.SystemLine("研究主管（新名） 开始做了"), rendered[0])
        val m1 = rendered[1] as ChatRender.Item.Message
        assertEquals("研究主管（新名）" to "orange", m1.speakerName to m1.speakerColor)
        // 名册有名无色：名称取名册、颜色退回条目值
        val m2 = rendered[2] as ChatRender.Item.Message
        assertEquals("运维值班" to "green", m2.speakerName to m2.speakerColor)
        // 名册中不存在的成员：完全用条目值
        val m3 = rendered[3] as ChatRender.Item.Message
        assertEquals("外部成员" to "pink", m3.speakerName to m3.speakerColor)
        // 名册与条目都无名：「某个 Bot」，不显示原始 session id
        assertEquals(ChatRender.Item.SystemLine("某个 Bot 做完了"), rendered[4])
    }

    @Test
    fun `实测夹具缺 color 的轮次发言经名册取到成员色`() {
        I18n.lang = ChatBars.Lang.ZH
        val page = RoomChat.decodeRoomPage(realFixture("room-chat-rounds.json"))
        val ids = page.items.mapNotNull { RoomChat.speakerOf(it)?.botId }.toSet()
        val roster = ids.associateWith { RoomChat.Speaker(it, "名册-$it", "purple") }
        val bots = RoomChat.renderRoom(page, roster).filterIsInstance<ChatRender.Item.Message>().filter { !it.fromUser }
        assertEquals(35, bots.size)
        // 实测 35 条 bot 行中多数不带 color：名册在场时全部取成员色与当前名
        assertTrue(page.items.count { (it.raw as Json.Value.Object).members["kind"] == Json.Value.StringValue("bot") && "color" !in (it.raw as Json.Value.Object).members } > 0)
        assertTrue(bots.all { it.speakerColor == "purple" && it.speakerName!!.startsWith("名册-") })
    }

    @Test
    fun `进行中轮次的 live 行与已结束行同样渲染`() {
        I18n.lang = ChatBars.Lang.ZH
        val page = RoomChat.decodeRoomPage(checkNotNull(Json.parse("""
            {"roomId":"r","running":true,"speaking":"b2","items":[
              {"id":"call:0","kind":"event","event":"working","botId":"b2","name":"乙","time":1,"live":true},
              {"id":"call:1","kind":"bot","botId":"b2","name":"乙","color":"green","text":"进行中的回复","time":2,"live":true}]}
        """.trimIndent())))
        assertEquals(listOf("b2"), page.running)
        val rendered = RoomChat.renderRoom(page)
        assertEquals(ChatRender.Item.SystemLine("乙 开始做了"), rendered[0])
        val msg = rendered[1] as ChatRender.Item.Message
        assertEquals("进行中的回复", msg.markdown)
        assertEquals("乙", msg.speakerName)
    }

    @Test
    fun `非 event 条目与非轮次 event 不受轮次渲染影响`() {
        I18n.lang = ChatBars.Lang.ZH
        val page = RoomChat.decodeRoomPage(checkNotNull(Json.parse("""
            {"roomId":"r","items":[
              {"kind":"event","note":"系统提示"},
              {"kind":"bot","botId":"b1","name":"甲","text":"答"}]}
        """.trimIndent())))
        val rendered = RoomChat.renderRoom(page)
        assertEquals(ChatRender.Item.SystemLine("系统提示"), rendered[0])
        assertEquals("甲", (rendered[1] as ChatRender.Item.Message).speakerName)
    }

    @Test
    fun `中段不可见条目不使成员归属错位`() {
        // pass 夹在两条 bot 消息之间：render 后回查索引会把第二条归属到 pass 前的条目
        val json = """
            {"roomId":"r","items":[
              {"kind":"bot","botId":"b1","name":"甲","color":"blue","text":"第一段"},
              {"kind":"pass","botId":"b1"},
              {"kind":"bot","botId":"b2","name":"乙","color":"green","text":"第二段"},
              {"kind":"checkpoint","turn":9},
              {"kind":"bot","botId":"b2","name":"乙","color":"green","text":"第三段"}]}
        """.trimIndent()
        val page = RoomChat.decodeRoomPage(checkNotNull(Json.parse(json)))
        val items = RoomChat.renderRoom(page)
        val msgs = items.filterIsInstance<ChatRender.Item.Message>()
        assertEquals(3, msgs.size)
        assertEquals("甲", msgs[0].speakerName)
        assertEquals("乙", msgs[1].speakerName)
        assertEquals("乙", msgs[2].speakerName)
        assertEquals("green", msgs[2].speakerColor)
    }
}
