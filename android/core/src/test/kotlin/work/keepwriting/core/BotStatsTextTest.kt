package work.keepwriting.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 统计/Bot 间记录人话格式化测试：夹具取自真实 0.3.6 服务器的 run-stats 应答
 * （2026-11-13 实测，见 BotStatsText 文档），锁定输出形态与字段缺失降级。
 */
class BotStatsTextTest {

    private fun parse(raw: String): Json.Value = checkNotNull(Json.parse(raw))

    @Test
    fun `真实 run-stats 应答翻译为四行摘要`() {
        val value = parse(
            """{"sessionStats":{"turns":62,"steps":364,"llmMs":3885010,"toolMs":477214,"ttftMs":2024081,
               "ttftSteps":362,"decodeMs":1860929,"decodeTokens":194239},
               "tokenUsage":{"uncachedInputTokens":6237275,"outputTokens":194239,
               "cacheReadTokens":65204352,"cacheWriteTokens":0},
               "contextPressure":{"pressureTokens":371245,"projectedTokens":371254,"contextWindow":1000000}}""",
        )
        assertEquals(
            listOf(
                "对话 62 轮 · 364 步",
                "耗时 模型 1.1 小时 · 工具 8.0 分钟",
                "Token：输入 6.24M · 输出 194K · 缓存读取 65.20M",
                "上下文 371K / 1.00M（37%）",
            ).joinToString("\n"),
            BotStatsText.runStats(value),
        )
    }

    @Test
    fun `字段缺失时整行省略不抛错`() {
        assertEquals("对话 3 轮", BotStatsText.runStats(parse("""{"sessionStats":{"turns":3}}""")))
        assertEquals("", BotStatsText.runStats(parse("{}")))
        assertEquals("", BotStatsText.runStats(parse("[1,2]")))
        // contextWindow 缺失或为 0 时该行不显示
        assertEquals(
            "对话 1 轮",
            BotStatsText.runStats(parse("""{"sessionStats":{"turns":1},"contextPressure":{"pressureTokens":5}}""")),
        )
    }

    @Test
    fun `exchanges 空数组给空态文案`() {
        assertEquals("暂无 Bot 间记录", BotStatsText.exchanges(parse("[]")))
    }

    @Test
    fun `exchanges 条目抽取叙事字段并截断`() {
        val value = parse("""[{"from":"b1","text":"市场分析完成"},{"kind":"x"}]""")
        val out = BotStatsText.exchanges(value)
        assertEquals("· 市场分析完成\n· " + Json.encode(parse("""{"kind":"x"}""")), out)
        // 长文本截断到 200 字符
        val long = "好".repeat(300)
        assertTrue(BotStatsText.exchanges(parse("""[{"text":"$long"}]""")).length < 220)
    }

    @Test
    fun `时长与计数缩写边界`() {
        assertEquals("59 秒", BotStatsText.duration(59_000))
        assertEquals("1.0 分钟", BotStatsText.duration(60_000))
        assertEquals("1.5 小时", BotStatsText.duration(5_400_000))
        assertEquals("999", BotStatsText.count(999))
        assertEquals("19K", BotStatsText.count(19_400))
        assertEquals("1.00M", BotStatsText.count(1_000_000))
    }
}
