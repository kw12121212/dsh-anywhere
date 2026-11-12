package work.keepwriting.core

import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** 扁平卡（callId 在条目顶层）与真实 questions[] 嵌套的双路径快速验证。 */
class CardFlatTest {
    private fun page(json: String): DsbotModels.ChatPage =
        DsbotModels.decodeChatPage(checkNotNull(Json.parse(json)) as Json.Value.Object)

    @Test
    fun `扁平卡与嵌套 questions 都产出 Question`() {
        val flat = page("""{"sessionId":"s","items":[
            {"kind":"answer","callId":"c1","question":"选？","options":[{"label":"A"},{"label":"B"}]}]}""")
        val flatCard = assertNotNull(Cards.fromChatItem(flat.items[0]))
        val fq = assertIs<Cards.Card.Question>(flatCard)
        kotlin.test.assertEquals(2, fq.options.size)

        val nested = page("""{"sessionId":"s","items":[
            {"kind":"answer","questions":[{"id":"q1","question":"选？","options":[{"label":"A"}]}]}]}""")
        val nestedCard = assertNotNull(Cards.fromChatItem(nested.items[0]))
        val nq = assertIs<Cards.Card.Question>(nestedCard)
        kotlin.test.assertEquals(1, nq.options.size)
    }
}
