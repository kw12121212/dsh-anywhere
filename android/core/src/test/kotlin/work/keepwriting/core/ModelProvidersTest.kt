package work.keepwriting.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ModelProvidersTest {
    @BeforeEach
    fun resetLang() {
        I18n.source = null
        I18n.lang = ChatBars.Lang.ZH
    }

    /** 0.3.10 providersView 形态（providers.js）。 */
    private val answer = Json.parse(
        """
        {
          "available": true,
          "providers": [
            { "id": "deepseek", "name": "DeepSeek", "models": [ { "id": "v4", "ref": "deepseek/v4", "name": "DeepSeek V4" } ], "declaredModels": [],
              "refreshable": false, "key": { "configured": true, "writable": true }, "removable": false, "custom": false },
            { "id": "env-route", "name": "Env", "models": [], "declaredModels": [], "refreshable": false,
              "key": { "configured": true, "writable": false }, "removable": false, "custom": false, "error": "fetch failed" },
            { "id": "my-gw", "name": "My Gateway", "models": [ { "id": "a", "ref": "my-gw/a", "name": "A" }, { "id": "b", "ref": "my-gw/b", "name": "B" } ],
              "declaredModels": [ { "id": "a", "ref": "my-gw/a", "name": "A" }, { "id": "b", "ref": "my-gw/b", "name": "B" } ],
              "refreshable": true, "key": { "configured": false, "writable": true }, "removable": true, "custom": true },
            { "id": "oauth-route", "name": "Account", "models": [ { "id": "x", "ref": "oauth-route/x", "name": "X" } ], "declaredModels": [], "refreshable": false, "key": null, "removable": false, "custom": false },
            { "name": "no id" }
          ],
          "addable": [ { "id": "zai", "name": "Z.ai" }, { "id": "groq", "name": "Groq" } ],
          "protocols": ["openai-completions", "openai-responses", "anthropic-messages"],
          "revision": 7
        }
        """.trimIndent(),
    )

    private val state = Json.parse(
        """{"bots":[
            {"id":"b1","name":"甲","model":"my-gw/b"},
            {"id":"b2","name":"乙","appliedModel":"my-gw/a"},
            {"id":"b3","name":"丙","model":"deepseek/v4"},
            {"id":"b4","name":"丁","model":"local-claude-code/sonnet"}]}""",
    ) as Json.Value.Object

    private val view get() = ModelProviders.decode(answer)!!
    private fun p(id: String) = view.providers.first { it.id == id }

    @Test
    fun `decodes providers addable protocols and revision`() {
        assertTrue(view.available)
        assertEquals(listOf("deepseek", "env-route", "my-gw", "oauth-route"), view.providers.map { it.id })
        assertEquals(listOf("Groq", "Z.ai"), view.addable.map { it.name }) // 按名称排序
        assertEquals("7", view.revision) // 原样 JSON 文本：数字
        assertEquals("\"12\"", ModelProviders.decode(Json.parse("""{"available":true,"providers":[],"revision":"12"}"""))!!.revision) // 字符串保持字符串
        assertEquals(3, view.protocols.size)
        assertNull(p("oauth-route").key)
        assertFalse(p("env-route").key!!.writable)
        assertTrue(p("my-gw").refreshable && p("my-gw").removable)
        assertFalse(ModelProviders.decode(Json.parse("""{"available":false}"""))!!.available)
        assertNull(ModelProviders.decode(Json.parse("[]")))
    }

    @Test
    fun `local routes follow upstream badgeOf`() {
        assertTrue(ModelProviders.isLocal("local-claude-code/sonnet"))
        assertTrue(ModelProviders.isLocal("local-codex-cli/gpt-5"))
        assertTrue(ModelProviders.isLocal("local-acp-agent-m1/default"))
        assertFalse(ModelProviders.isLocal("local-ollama/llama3")) // 自定义服务商 id 恰好以 local- 开头
        assertFalse(ModelProviders.isLocal("deepseek/v4"))
        assertFalse(ModelProviders.isLocal(null))
    }

    @Test
    fun `key text hint and users`() {
        assertEquals("key 已设置", ModelProviders.keyText(p("deepseek").key))
        assertEquals("key 在 DS Bot 之外设置", ModelProviders.keyText(p("env-route").key))
        assertEquals("需要 API key", ModelProviders.keyText(p("my-gw").key))
        assertNull(ModelProviders.keyText(null))
        assertEquals("fetch failed", ModelProviders.hint(p("env-route")))
        assertEquals("2 个模型", ModelProviders.hint(p("my-gw")))
        assertEquals("没有模型", ModelProviders.hint(p("env-route").copy(error = null)))
        val bots = ModelProviders.bots(state)
        assertEquals(listOf("b1", "b2"), ModelProviders.users(p("my-gw"), bots).map { it.id })
        assertEquals("OpenAI Responses", ModelProviders.protocolName("openai-responses"))
        assertEquals("weird", ModelProviders.protocolName("weird"))
    }

    @Test
    fun `draft follows web draftFrom and affected bots skip local agents`() {
        val found = ModelProviders.discovered(Json.parse("""[{"id":"c","name":"C new"},{"id":"a","contextWindow":8000},{"id":"c"},{"name":"no id"}]"""))
        val d = ModelProviders.draftFrom(p("my-gw"), found, view.revision)
        assertEquals(listOf("c", "a", "b"), d.rows.map { it.id }) // 返回的在前去重，未返回的已有在后
        assertEquals(listOf(true, true, false), d.rows.map { it.returned })
        assertEquals(listOf(false, true, true), d.rows.map { it.existing })
        assertEquals(3, d.selected.size)
        assertEquals(1, d.added)
        val d2 = d.toggle("b")
        assertEquals(1, d2.removed)
        val bots = ModelProviders.bots(state) + ModelProviders.BotRef("b5", "戊", "local-x/y", "my-gw/b")
        assertEquals(listOf("b1"), ModelProviders.affected(p("my-gw"), d2, bots).map { it.id })
        // 当前模型保留、只有已应用模型被移除的不算（评审发现）；当前模型在本地 Agent 上的不算
        val keptCurrent = ModelProviders.BotRef("b6", "己", "deepseek/v4", "my-gw/b")
        val localCurrent = ModelProviders.BotRef("b7", "庚", "local-claude-code/sonnet", "my-gw/b")
        assertTrue(ModelProviders.affected(p("my-gw"), d2, listOf(keptCurrent, localCurrent)).isEmpty())
        assertTrue(ModelProviders.affected(p("my-gw"), d, bots).isEmpty())
    }

    @Test
    fun `key validation mirrors checkKey`() {
        assertEquals("填写 API key", ModelProviders.keyProblem(" "))
        assertEquals("只粘贴 key 本身，不要 NAME=value 这一整行", ModelProviders.keyProblem("OPENAI_API_KEY=sk-1"))
        assertEquals("粘贴 key 时不要带引号", ModelProviders.keyProblem("\"sk-1\""))
        assertEquals("API key 不含空格或特殊字符", ModelProviders.keyProblem("sk 1"))
        assertEquals("API key 不含空格或特殊字符", ModelProviders.keyProblem("sk-中文"))
        assertNull(ModelProviders.keyProblem(" sk-abc_123== "))
    }

    @Test
    fun `custom provider validation and models`() {
        val P = listOf("openai-completions", "anthropic-messages")
        assertEquals("服务商 id 为小写字母、数字与连字符，以字母开头，连字符不能连续或结尾", ModelProviders.customProblem("My-GW", "https://x/v1", "openai-completions", P, "", listOf("m")))
        assertEquals("服务商 id 为小写字母、数字与连字符，以字母开头，连字符不能连续或结尾", ModelProviders.customProblem("1gw", "https://x/v1", "openai-completions", P, "", listOf("m")))
        assertEquals("端点须为 http 或 https 地址", ModelProviders.customProblem("gw", "ftp://x", "openai-completions", P, "", listOf("m")))
        assertEquals("端点须为 http 或 https 地址", ModelProviders.customProblem("gw", "not a url", "openai-completions", P, "", listOf("m")))
        assertEquals("选择协议", ModelProviders.customProblem("gw", "https://x/v1", "nope", P, "", listOf("m")))
        assertEquals("粘贴 key 时不要带引号", ModelProviders.customProblem("gw", "https://x/v1", "openai-completions", P, "'k'", listOf("m")))
        assertEquals("至少添加一个模型", ModelProviders.customProblem("gw", "https://x/v1", "openai-completions", P, "", emptyList()))
        assertNull(ModelProviders.customProblem("my-gw2", "http://localhost:8080/v1", "openai-completions", P, "", listOf("m")))
        // 上游 ROUTE_ID：连字符不能连续或结尾（评审发现）
        for (bad in listOf("gw-", "a--b", "-gw")) assertTrue(ModelProviders.customProblem(bad, "https://x/v1", "openai-completions", P, "", listOf("m"))!!.startsWith("服务商 id"), bad)
        assertNull(ModelProviders.customProblem("a-b-c1", "http://my_gw:8080/v1", "openai-completions", P, "", listOf("m"))) // 主机名带下划线可以
        val found = ModelProviders.discovered(Json.parse("""[{"id":"a"},{"id":"b"}]"""))
        assertEquals(listOf("b", "x", "y"), ModelProviders.customModels(found, setOf("b"), " x, y  b "))
        assertEquals(listOf("x", "y"), ModelProviders.typedIds("x,,y\n"))
    }

    @Test
    fun `request bodies match upstream endpoints`() {
        val R = ModelProviders.Requests
        assertEquals("""{"provider":"my-gw","value":"sk-1"}""", R.setKey("my-gw", " sk-1 "))
        assertEquals("""{"provider":"my-gw","value":null}""", R.clearKey("my-gw"))
        assertEquals("""{"provider":"zai"}""", R.addKnown("zai", " "))
        assertEquals("""{"provider":"zai","key":"k"}""", R.addKnown("zai", "k"))
        assertEquals(
            """{"custom":true,"provider":"gw","name":"GW","baseURL":"https://x/v1","api":"openai-completions","key":"k","models":[{"id":"a"},{"id":"b"}]}""",
            R.addCustom(" gw ", " GW ", " https://x/v1 ", "openai-completions", "k", listOf("a", "b")),
        )
        assertEquals("""{"custom":true,"provider":"gw","baseURL":"https://x/v1","api":"anthropic-messages","models":[{"id":"a"}]}""", R.addCustom("gw", "", "https://x/v1", "anthropic-messages", "", listOf("a")))
        assertEquals("""{"provider":"my-gw"}""", R.discoverStored("my-gw"))
        assertEquals("""{"baseURL":"https://x/v1","api":"openai-completions"}""", R.discoverEndpoint("https://x/v1", "openai-completions", ""))
        assertEquals("""{"ref":"deepseek/v4"}""", R.efforts("deepseek/v4"))
        assertEquals("""{"provider":"my-gw"}""", R.remove("my-gw"))
        val found = ModelProviders.discovered(Json.parse("""[{"id":"c","name":"C"}]"""))
        val d = ModelProviders.draftFrom(p("my-gw"), found, "7").toggle("a")
        assertEquals("""{"provider":"my-gw","models":[{"id":"c","name":"C"},{"id":"b"}],"revision":7}""", R.saveModels("my-gw", d))
        assertEquals("""{"provider":"my-gw","models":[{"id":"b"}],"revision":"12"}""", R.saveModels("my-gw", ModelProviders.draftFrom(p("my-gw"), emptyList(), "\"12\"").toggle("a"))) // 字符串 revision 原样回传
        assertEquals("""{"provider":"my-gw","models":[{"id":"b"}]}""", R.saveModels("my-gw", ModelProviders.draftFrom(p("my-gw"), emptyList(), null).toggle("a")))
        assertTrue("provider-models" in ModelProviders.SLOW_ENDPOINTS)
    }
}
