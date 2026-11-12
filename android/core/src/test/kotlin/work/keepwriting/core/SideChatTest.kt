package work.keepwriting.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SideChat 构造/解码测试（dsbot-side-chat，0.3.5 实测形态）。 */
class SideChatTest {

    @Test
    fun `端点 payload 构造`() {
        assertEquals("{\"sessionId\":\"s1\"}", SideChat.newSide("s1"))
        assertEquals(
            "{\"sessionId\":\"s1\",\"text\":\"旁路问\"}",
            SideChat.ask("s1", "旁路问"),
        )
        assertEquals(
            "{\"sessionId\":\"s1\",\"text\":\"带快照\",\"snapshot\":[{\"role\":\"user\",\"text\":\"最近\"}]}",
            SideChat.ask("s1", "带快照", "[{\"role\":\"user\",\"text\":\"最近\"}]"),
        )
        assertEquals("{\"sideId\":\"c1\"}", SideChat.cancel("c1"))
        assertEquals("{\"sideId\":\"c1\"}", SideChat.close("c1"))
        assertEquals("{\"id\":\"b1\"}", SideChat.btwDelete("b1"))
    }

    @Test
    fun `side-new 应答与 sideChats 解码`() {
        val chatId = SideChat.decodeChatId(checkNotNull(Json.parse("""{"chatId":"cc-1"}""")))
        assertEquals("cc-1", chatId)
        assertEquals(null, SideChat.decodeChatId(checkNotNull(Json.parse("""{}"""))))

        val state = checkNotNull(Json.parse("""{
            "sideChats": {"bot-1": [
              {"id":"side-a","botId":"bot-1","entries":[
                {"kind":"user","text":"旁路问题"},
                {"kind":"bot","text":"旁路回答"}]},
              {"id":"side-b","botId":"bot-1","entries":[]}
            ]}
        }""")) as Json.Value.Object
        val sessions = SideChat.decodeSideSessions(state)
        assertEquals(2, sessions.size)
        assertEquals("side-a", sessions[0].id)
        assertEquals(2, sessions[0].entries.size)
        assertEquals("旁路回答", sessions[0].entries[1].text)
        assertTrue(sessions[1].entries.isEmpty())
        // 渲染条目复用 ChatRender（kind 同聊天）
        val rendered = ChatRender.render(DsbotModels.ChatPage(null, sessions[0].entries, null))
        assertEquals(2, rendered.items.filterIsInstance<ChatRender.Item.Message>().size)
    }

    @Test
    fun `主聊天快照构造`() {
        val msgs = listOf(true to "问一", false to "答一", true to "问二")
        val snapshot = SideChat.snapshotOf(msgs, max = 2)
        // 只取尾部 2 条（答一 + 问二）
        assertEquals("[{\"role\":\"assistant\",\"text\":\"答一\"},{\"role\":\"user\",\"text\":\"问二\"}]", snapshot)
        val all = SideChat.snapshotOf(msgs, max = 5)
        assertEquals(3, Regex("\"role\"").findAll(all).count())
    }
}
