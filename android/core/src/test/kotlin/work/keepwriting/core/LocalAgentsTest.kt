package work.keepwriting.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LocalAgentsTest {
    @BeforeEach
    fun resetLang() {
        I18n.source = null
        I18n.lang = ChatBars.Lang.ZH
    }

    /** 0.3.10 local-agents 应答（index.js freshList：detected 与 customView）。 */
    private val answer = Json.parse(
        """
        {
          "available": true,
          "detected": [
            { "route": "local-claude-code", "name": "Claude Code", "path": "/usr/local/bin/claude", "version": "2.1.3 (Claude Code)", "signIn": "claude login", "signedIn": true },
            { "route": "local-codex-cli", "name": "Codex", "path": "/home/u/.local/bin/codex", "version": "codex-cli 0.48.0", "signIn": "codex login", "signedIn": false },
            { "route": "devin", "name": "Devin", "path": "/opt/devin", "version": null, "signIn": "", "signedIn": null },
            { "name": "no route" }
          ],
          "custom": [
            { "id": "agent-m1", "name": "Gemini ACP", "command": "gemini", "args": ["--experimental-acp"], "route": "local-acp-agent-m1", "available": true },
            { "id": "agent-m2", "name": "Broken", "command": "/opt/missing", "args": [], "route": "local-acp-agent-m2", "available": false },
            { "name": "no id" }
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun `decodes detected and custom agents`() {
        val v = LocalAgents.decode(answer)!!
        assertTrue(v.available)
        assertEquals(listOf("local-claude-code", "local-codex-cli", "devin"), v.detected.map { it.route })
        assertEquals(listOf("agent-m1", "agent-m2"), v.custom.map { it.id })
        assertEquals(listOf("--experimental-acp"), v.custom[0].args)
        assertFalse(v.custom[1].available)
        assertNull(v.detected[2].signIn) // 空登录命令视为无
        assertFalse(LocalAgents.decode(Json.parse("""{"available":false,"detected":[],"custom":[]}"""))!!.available)
        assertTrue(LocalAgents.decode(Json.parse("""{"detected":[],"custom":[]}"""))!!.available)
        assertNull(LocalAgents.decode(Json.parse("[]")))
        assertNull(LocalAgents.decode(null))
    }

    @Test
    fun `short version and sign-in state follow web`() {
        assertEquals("2.1.3", LocalAgents.shortVersion("2.1.3 (Claude Code)"))
        assertEquals("0.48.0", LocalAgents.shortVersion("codex-cli 0.48.0"))
        assertEquals("v1.2", LocalAgents.shortVersion("devin v1.2"))
        assertEquals("", LocalAgents.shortVersion(null))
        val v = LocalAgents.decode(answer)!!
        assertEquals("已登录", LocalAgents.signInText(v.detected[0]))
        assertEquals("未登录", LocalAgents.signInText(v.detected[1]))
        assertNull(LocalAgents.signInText(v.detected[2]))
        assertFalse(LocalAgents.canSignIn(v.detected[0]))
        assertTrue(LocalAgents.canSignIn(v.detected[1]))
        assertFalse(LocalAgents.canSignIn(v.detected[2]))
        assertEquals("gemini --experimental-acp", LocalAgents.commandLine(v.custom[0]))
    }

    @Test
    fun `models answer`() {
        val m = LocalAgents.models(Json.parse("""[{"provider":"claude-code","id":"sonnet","name":"Sonnet"},{"provider":"claude-code","id":"opus"},{"name":"x"}]"""))
        assertEquals(listOf(LocalAgents.Model("sonnet", "Sonnet"), LocalAgents.Model("opus", "opus")), m)
        assertTrue(LocalAgents.models(null).isEmpty())
    }

    @Test
    fun `form validation mirrors validateCustom`() {
        assertEquals("填写名称", LocalAgents.formProblem(" ", "gemini", ""))
        assertEquals("命令须为程序名、绝对路径或 ~/ 路径", LocalAgents.formProblem("a", "my agent", ""))
        assertEquals("命令须为程序名、绝对路径或 ~/ 路径", LocalAgents.formProblem("a", "", ""))
        assertEquals("命令须为程序名、绝对路径或 ~/ 路径", LocalAgents.formProblem("a", "bin/agent", "")) // 相对路径不行
        assertEquals("参数不能包含换行", LocalAgents.formProblem("a", "gemini", "x\ny"))
        assertNull(LocalAgents.formProblem("a", "gemini", "--acp"))
        assertNull(LocalAgents.formProblem("a", "/opt/agent", ""))
        assertNull(LocalAgents.formProblem("a", "~/bin/agent", ""))
        assertNull(LocalAgents.formProblem("a", "C:\\tools\\agent.exe", ""))
        assertNull(LocalAgents.formProblem("a", "my-agent.v2", ""))
        assertEquals(listOf("--a", "b"), LocalAgents.splitArgs("  --a   b "))
        // 评审补充的边界：回车与 NUL、正斜杠盘符、相对与 ~user 路径、无分隔符盘符、命令含制表符
        assertEquals("参数不能包含换行", LocalAgents.formProblem("a", "gemini", "x\ry"))
        assertEquals("参数不能包含换行", LocalAgents.formProblem("a", "gemini", "x\u0000y"))
        assertNull(LocalAgents.formProblem("a", "D:/tools/agent", ""))
        val bad = "命令须为程序名、绝对路径或 ~/ 路径"
        assertEquals(bad, LocalAgents.formProblem("a", "./agent", ""))
        assertEquals(bad, LocalAgents.formProblem("a", "~user/x", ""))
        assertEquals(bad, LocalAgents.formProblem("a", "C:agent", ""))
        assertEquals(bad, LocalAgents.formProblem("a", "my\tagent", ""))
    }

    @Test
    fun `request bodies match upstream endpoints`() {
        val R = LocalAgents.Requests
        assertEquals("{}", R.list())
        assertEquals("{}", R.refresh())
        assertEquals("""{"route":"local-codex-cli"}""", R.models("local-codex-cli"))
        assertEquals("""{"name":"Gemini","command":"gemini","args":["--experimental-acp","-v"]}""", R.add(" Gemini ", " gemini ", "--experimental-acp  -v"))
        assertEquals("""{"name":"G","command":"gemini","args":[]}""", R.add("G", "gemini", " "))
        assertEquals("""{"id":"agent-m1","name":"G2","command":"gemini","args":["acp"]}""", R.update("agent-m1", "G2", "gemini", "acp"))
        assertEquals("""{"id":"agent-m1"}""", R.remove("agent-m1"))
        val longName = R.add("x".repeat(50), "a", "").substringAfter("\"name\":\"").substringBefore("\"")
        assertEquals(40, longName.length) // 名称截 40（同上游 slice(0, 40)）
        assertTrue("local-agent-models" in LocalAgents.SLOW_ENDPOINTS)
    }
}
