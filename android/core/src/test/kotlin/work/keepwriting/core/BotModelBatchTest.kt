package work.keepwriting.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** dsbot-models-providers：Bot 模型批量切换的计划与汇总（网页端 bot-model-batch.js）。 */
class BotModelBatchTest {
    @BeforeEach
    fun resetLang() {
        I18n.source = null
        I18n.lang = ChatBars.Lang.ZH
    }

    private val state = Json.parse(
        """{"bots":[
            {"id":"b1","name":"甲","model":"my-gw/b"},
            {"id":"b2","name":"乙","appliedModel":"my-gw/a"},
            {"id":"b3","name":"丙","model":"deepseek/v4"},
            {"id":"b4","name":"丁","model":"local-claude-code/sonnet"}]}""",
    ) as Json.Value.Object

    @Test
    fun `batch plan and summary follow web bot-model-batch`() {
        val bots = ModelProviders.bots(state)
        val plan = BotModelBatch.plan(bots, "deepseek/v4", setOf("deepseek/v4", "my-gw/a"))
        assertEquals(listOf(BotModelBatch.Action.CHANGE, BotModelBatch.Action.CHANGE, BotModelBatch.Action.KEEP, BotModelBatch.Action.SKIP_LOCAL), plan.map { it.action })
        assertTrue(BotModelBatch.plan(bots.take(1), "gone/x", setOf("deepseek/v4")).all { it.action == BotModelBatch.Action.SKIP_UNAVAILABLE })
        val results = listOf(
            BotModelBatch.Result("b1", "甲", BotModelBatch.Status.UPDATED), BotModelBatch.Result("b2", "乙", BotModelBatch.Status.FAILED, "Unknown Bot"),
            BotModelBatch.Result("b3", "丙", BotModelBatch.Status.UNCHANGED), BotModelBatch.Result("b4", "丁", BotModelBatch.Status.SKIPPED),
        )
        val s = BotModelBatch.summarize(results)
        assertEquals(BotModelBatch.Summary(4, 1, 1, 1, 1), s)
        assertEquals("已更新 1 · 未变 1 · 跳过 1 · 失败 1", BotModelBatch.summaryText(s))
        assertEquals("失败：Unknown Bot", BotModelBatch.statusText(results[1]))
        assertEquals("""{"id":"b1","model":"deepseek/v4"}""", BotModelBatch.request("b1", "deepseek/v4"))
    }

    @Test
    fun `custom provider named local-x is not skipped as a local agent`() {
        val bots = listOf(ModelProviders.BotRef("b9", "辛", "local-ollama/llama3", null))
        assertTrue(BotModelBatch.plan(bots, "deepseek/v4", setOf("deepseek/v4")).all { it.action == BotModelBatch.Action.CHANGE })
    }
}
