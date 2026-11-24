package work.keepwriting.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** dsbot-models-providers：state 请求带上次的 modelsKey（上游只在目录签名未变时回 unchanged）。 */
class DsbotStatePayloadTest {
    @Test
    fun `payload carries since live and models`() {
        assertEquals("{}", DsbotModels.statePayload(null, null, null))
        assertEquals("""{"since":42}""", DsbotModels.statePayload("42", null, null))
        assertEquals("""{"since":"r-1","live":"k"}""", DsbotModels.statePayload("r-1", "k", null))
        assertEquals("""{"since":42,"live":"k","models":"deepseek/v4,glm/x!|deepseek/v4"}""", DsbotModels.statePayload("42", "k", "deepseek/v4,glm/x!|deepseek/v4"))
        // 没有 since 时是全量取，不带 models
        assertEquals("""{"live":"k"}""", DsbotModels.statePayload(null, "k", "mk"))
    }

    @Test
    fun `models key is read from the state answer`() {
        assertEquals("a/b|", DsbotModels.modelsKey(Json.parse("""{"modelsKey":"a/b|"}""") as Json.Value.Object))
        assertNull(DsbotModels.modelsKey(Json.parse("""{"modelsKey":3}""") as Json.Value.Object))
        assertNull(DsbotModels.modelsKey(null))
    }
}
