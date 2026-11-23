package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** WorkPhrase（dsbot-chat-bars-0310）：顺序对齐 0.3.10 网页端 workPhrase。 */
class WorkPhraseTest {
    private fun doing(json: String) = Json.parse(json) as Json.Value.Object

    @Test
    fun ownWordsWinAndTrailingPunctuationTrimmed() {
        assertEquals("正在整理表格", WorkPhrase.phrase(doing("""{"tool":"bash","said":"正在整理表格。。"}""")))
        assertEquals("Checking the build", WorkPhrase.phrase(doing("""{"tool":"bash","note":"Checking the build... "}""")))
        // said 为空白时回落 note
        assertEquals("备注", WorkPhrase.phrase(doing("""{"said":"  ","note":"备注"}""")))
    }

    @Test
    fun noToolIsThinking() {
        assertEquals("思考中", WorkPhrase.phrase(doing("""{"since":1}""")))
        assertEquals("思考中", WorkPhrase.phrase(null))
        assertEquals("Thinking about it", WorkPhrase.phrase(doing("{}"), ChatBars.Lang.EN))
    }

    @Test
    fun switchingPhrases() {
        assertEquals("切换工作文件夹", WorkPhrase.phrase(doing("""{"switching":true,"folder":true}""")))
        assertEquals("转入新对话", WorkPhrase.phrase(doing("""{"switching":true,"said":"ignored"}""")))
    }

    @Test
    fun exactToolsHaveOwnPhrases() {
        assertEquals("等你回答", WorkPhrase.toolLabel("ask_user_question"))
        assertEquals("询问其他 Bot", WorkPhrase.toolLabel("message_bot"))
        assertEquals("查看记忆", WorkPhrase.toolLabel("bot_recall"))
        assertEquals("写入记忆", WorkPhrase.toolLabel("remember"))
        assertEquals("在群里发言", WorkPhrase.toolLabel("post_to_group"))
        assertEquals("查看团队", WorkPhrase.toolLabel("list_bots"))
    }

    @Test
    fun patternOrderMatchesWeb() {
        assertEquals("使用电脑", WorkPhrase.toolLabel("cua"))
        assertEquals("使用电脑", WorkPhrase.toolLabel("computer_click"))
        assertEquals("联网搜索", WorkPhrase.toolLabel("web_search")) // 先于 web → 阅读网页
        assertEquals("阅读网页", WorkPhrase.toolLabel("web_fetch"))
        assertEquals("阅读网页", WorkPhrase.toolLabel("browser_open"))
        assertEquals("运行命令", WorkPhrase.toolLabel("bash"))
        assertEquals("打开文件", WorkPhrase.toolLabel("read"))
        assertEquals("打开文件", WorkPhrase.toolLabel("read_file"))
        assertEquals("查找文件", WorkPhrase.toolLabel("grep"))
        assertEquals("查找文件", WorkPhrase.toolLabel("ls"))
        assertEquals("写入修改", WorkPhrase.toolLabel("edit"))
        assertEquals("处理图片", WorkPhrase.toolLabel("draw_image")) // draw 不先命中其他规则
        // "readme_lookup"：^read\b 不命中（无词界），read_ 不命中，回落 null
        assertNull(WorkPhrase.toolLabel("readme_lookup"))
        assertEquals("Searching online", WorkPhrase.toolLabel("web_search", ChatBars.Lang.EN))
    }

    @Test
    fun cardToolsAndUnknownFallBackToGeneric() {
        assertNull(WorkPhrase.toolLabel("ask_user"))
        assertNull(WorkPhrase.toolLabel("group_relay"))
        assertNull(WorkPhrase.toolLabel("frobnicate"))
        assertEquals("处理中", WorkPhrase.phrase(doing("""{"tool":"frobnicate"}""")))
        assertEquals("处理中 · a.txt", WorkPhrase.phrase(doing("""{"tool":"ask_user","target":"a.txt"}""")))
        assertEquals("On it", WorkPhrase.phrase(doing("""{"tool":"frobnicate"}"""), ChatBars.Lang.EN))
    }

    @Test
    fun targetAppendedAndClipped() {
        assertEquals("打开文件 · notes.md", WorkPhrase.phrase(doing("""{"tool":"read","target":"notes.md"}""")))
        val long = "x".repeat(200)
        val p = WorkPhrase.phrase(doing("""{"tool":"read","target":"$long"}"""))
        assertEquals("打开文件 · ".length + 80, p.length)
    }

    @Test
    fun malformedFieldsIgnored() {
        assertEquals("思考中", WorkPhrase.phrase(doing("""{"tool":5,"said":true}""")))
        assertEquals("运行命令", WorkPhrase.phrase(doing("""{"tool":"bash","target":7}""")))
    }
}
