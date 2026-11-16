package work.keepwriting.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * MarkdownParse 测试（fix-android-chat-links-and-copy）：块/行内解析行为以网页端渲染
 * 为契约（lesson-0d4784：接受集配负向测试）。样本为全合成文本，仅保留 bot 汇报的
 * 结构形态（列表行内嵌服务器本地路径文件链接），不含真实对话内容（fixtures-sanitized）。
 */
class MarkdownParseTest {

    /** 全合成汇报样本（结构与 bot 收工汇报同构）：文件链接全部位于 - 列表行。 */
    private val sampleReport = """
        今天的批次完成了，用时比上一批短。

        **本批结果：合成示例条目（对照说明）**

        - [带图页](/home/u1/.dsh/bot/workspace/deliverables/2026-11-16/demo-2026-11-16.paste.html) · [PDF 5 页](/home/u1/.dsh/bot/workspace/deliverables/2026-11-16/demo-2026-11-16.pdf) · [成片 60 秒](/home/u1/.dsh/bot/workspace/deliverables/2026-11-16/demo-60s.mp4) · [日报](/home/u1/.dsh/bot/workspace/deliverables/2026-11-16/REPORT.md)

        正文 800 字、检查通过。

        ## 复核

        1. 两项指标都过
        2. 每屏各一个数
    """.trimIndent()

    // ---- 行为表征（搬迁等价）：块级形态与原文保留 ----

    @Test
    fun `块级形态按行解析`() {
        val blocks = MarkdownParse.parseBlocks(sampleReport)
        assertEquals(7, blocks.size)
        assertIs<MarkdownParse.MdBlock.Paragraph>(blocks[0])
        assertIs<MarkdownParse.MdBlock.Bullet>(blocks[2])
        assertIs<MarkdownParse.MdBlock.Heading>(blocks[4]).also { assertEquals(2, it.level) }
        assertIs<MarkdownParse.MdBlock.Ordered>(blocks[5]).also { assertEquals("1", it.index) }
        assertIs<MarkdownParse.MdBlock.Ordered>(blocks[6]).also { assertEquals("2", it.index) }
    }

    @Test
    fun `代码块与表格保留原文`() {
        val blocks = MarkdownParse.parseBlocks("```kotlin\nval a = 1\n```\n\n| a | b |\n| --- | --- |\n| 1 | 2 |")
        val code = assertIs<MarkdownParse.MdBlock.Code>(blocks[0])
        assertEquals("val a = 1", code.code)
        val table = assertIs<MarkdownParse.MdBlock.Table>(blocks[1])
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun `未知语法降级为纯文本段落不崩溃`() {
        val blocks = MarkdownParse.parseBlocks("这不是任何已知语法 [[[ }}}")
        val para = assertIs<MarkdownParse.MdBlock.Paragraph>(blocks.single())
        val part = para.parts.single()
        assertEquals(MarkdownParse.InlineKind.PLAIN, part.kind)
        assertTrue(part.text.startsWith("这不是任何已知语法"))
    }

    // ---- 行为表征（搬迁等价）：行内解析 ----

    @Test
    fun `行内链接粗体与代码分段`() {
        val parts = MarkdownParse.parseInline("看 [PDF 5 页](/home/u1/.dsh/bot/workspace/deliverables/d/demo.pdf) 与 **重点** 和 `code`")
        assertEquals(MarkdownParse.InlineKind.PLAIN, parts[0].kind)
        val link = parts.first { it.kind == MarkdownParse.InlineKind.LINK }
        assertEquals("PDF 5 页", link.text)
        assertEquals("/home/u1/.dsh/bot/workspace/deliverables/d/demo.pdf", link.url)
        assertTrue(parts.any { it.kind == MarkdownParse.InlineKind.BOLD && it.text == "重点" })
        assertTrue(parts.any { it.kind == MarkdownParse.InlineKind.CODE && it.text == "code" })
    }

    @Test
    fun `负向-不完整链接语法保持纯文本`() {
        val parts = MarkdownParse.parseInline("残缺 [文字]( 和 [文字] 不成链接")
        assertTrue(parts.all { it.kind == MarkdownParse.InlineKind.PLAIN })
    }

    @Test
    fun `负向-http 与本地路径链接 URL 原样透传`() {
        val parts = MarkdownParse.parseInline("[网页](https://example.com/a) [文件](/home/u1/x.mp4)")
        val urls = parts.filter { it.kind == MarkdownParse.InlineKind.LINK }.mapNotNull { it.url }
        assertEquals(listOf("https://example.com/a", "/home/u1/x.mp4"), urls)
    }

    @Test
    fun `负向-相对路径与盘符形态链接 URL 原样透传不做限定`() {
        val parts = MarkdownParse.parseInline("[相对](./drafts/a.pdf) [盘符](C:\\x\\b.mp4) [上级](../c/d.txt)")
        val urls = parts.filter { it.kind == MarkdownParse.InlineKind.LINK }.mapNotNull { it.url }
        assertEquals(listOf("./drafts/a.pdf", "C:\\x\\b.mp4", "../c/d.txt"), urls)
    }

    @Test
    fun `标题行走行内解析并保留链接与粗体`() {
        val blocks = MarkdownParse.parseBlocks("# 报告：见 [文件](/home/u1/.dsh/bot/workspace/REPORT.md) 与 **重点**")
        val heading = assertIs<MarkdownParse.MdBlock.Heading>(blocks.single())
        assertEquals(1, heading.level)
        assertEquals(
            "/home/u1/.dsh/bot/workspace/REPORT.md",
            heading.parts.first { it.kind == MarkdownParse.InlineKind.LINK }.url,
        )
        assertTrue(heading.parts.any { it.kind == MarkdownParse.InlineKind.BOLD && it.text == "重点" })
    }

    // ---- 回归（fix-android-chat-links-and-copy）：列表行文件链接必须可暴露为行内片段 ----

    @Test
    fun `列表行文件链接暴露为 LINK 片段`() {
        val blocks = MarkdownParse.parseBlocks(sampleReport)
        val bullet = blocks.filterIsInstance<MarkdownParse.MdBlock.Bullet>().single()
        val links = bullet.parts.filter { it.kind == MarkdownParse.InlineKind.LINK }.mapNotNull { it.url }
        assertEquals(
            listOf(
                "/home/u1/.dsh/bot/workspace/deliverables/2026-11-16/demo-2026-11-16.paste.html",
                "/home/u1/.dsh/bot/workspace/deliverables/2026-11-16/demo-2026-11-16.pdf",
                "/home/u1/.dsh/bot/workspace/deliverables/2026-11-16/demo-60s.mp4",
                "/home/u1/.dsh/bot/workspace/deliverables/2026-11-16/REPORT.md",
            ),
            links,
        )
    }

    @Test
    fun `有序列表行与段落行的链接同样暴露`() {
        val blocks = MarkdownParse.parseBlocks("1. 看 [日报](/home/u1/.dsh/bot/workspace/REPORT.md)\n\n见 [说明](https://example.com)")
        val ordered = assertIs<MarkdownParse.MdBlock.Ordered>(blocks[0])
        assertEquals("/home/u1/.dsh/bot/workspace/REPORT.md", ordered.parts.first { it.kind == MarkdownParse.InlineKind.LINK }.url)
        val para = assertIs<MarkdownParse.MdBlock.Paragraph>(blocks[1])
        assertEquals("https://example.com", para.parts.first { it.kind == MarkdownParse.InlineKind.LINK }.url)
    }

    @Test
    fun `负向-无行内语法的列表行只有 PLAIN 片段`() {
        val blocks = MarkdownParse.parseBlocks("- 普通条目没有链接")
        val bullet = assertIs<MarkdownParse.MdBlock.Bullet>(blocks.single())
        assertEquals(listOf(MarkdownParse.InlineKind.PLAIN), bullet.parts.map { it.kind })
    }
}
