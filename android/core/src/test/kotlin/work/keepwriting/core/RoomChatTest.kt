package work.keepwriting.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RoomChat 构造/解码测试（dsbot-group-chat，0.3.5 实测形态）。 */
class RoomChatTest {

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
