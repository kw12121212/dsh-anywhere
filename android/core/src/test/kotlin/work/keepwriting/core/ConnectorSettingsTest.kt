package work.keepwriting.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.keepwriting.core.ConnectorSettings.Action
import work.keepwriting.core.ConnectorSettings.ConnectKind

class ConnectorSettingsTest {
    @BeforeEach
    fun resetLang() {
        I18n.source = null
        I18n.lang = ChatBars.Lang.ZH
    }

    /** 0.3.10 live connectors 形态（connectors/index.js rowFor + mail/index.js mailView）。 */
    private val state = Json.parse(
        """
        {
          "bots": [ { "id": "b1", "name": "小助手" }, { "id": "b2", "name": "研究员" } ],
          "connectors": {
            "connectors": [
              { "id": "github", "name": "GitHub", "category": "dev", "about": "Repositories, issues, and pull requests",
                "auth": { "kind": "oauth", "scopes": ["repo"], "client": { "id": "Ov23", "deviceFlow": true }, "token": true },
                "state": "connecting", "account": null, "offFor": [], "tools": 0,
                "pending": { "kind": "device", "userCode": "ABCD-1234", "verificationUri": "https://github.com/login/device", "expiresAt": 1 } },
              { "id": "notion", "name": "Notion", "category": "docs", "about": "Pages, databases, and wiki content",
                "auth": { "kind": "oauth" }, "state": "off", "account": null, "offFor": [], "tools": 0 },
              { "id": "linear", "name": "Linear", "category": "work", "about": "Issues, projects, and cycles",
                "auth": { "kind": "oauth" }, "state": "connecting", "account": null, "offFor": [], "tools": 0,
                "pending": { "kind": "browser", "url": "https://linear.app/oauth/authorize?state=s1" }, "redirectUri": "http://127.0.0.1:53682/callback" },
              { "id": "slack", "name": "Slack", "category": "work", "about": "Channels and messages",
                "auth": { "kind": "oauth", "clientSetup": { "kind": "slack", "appUrl": "https://api.slack.com/apps" } },
                "state": "needs-client", "account": null, "offFor": [], "tools": 0, "redirectUri": "http://127.0.0.1:53682/callback" },
              { "id": "amap", "name": "Amap", "category": "search", "about": "Maps, places, and routes in China",
                "auth": { "kind": "key", "query": "key", "keyUrl": "https://lbs.amap.com/api/mcp-server/create" }, "state": "off", "account": null, "offFor": [], "tools": 0 },
              { "id": "exa", "name": "Exa", "category": "search", "about": "Web search for agents",
                "auth": { "kind": "none" }, "state": "ready", "account": null, "offFor": ["b2"], "tools": 3 },
              { "id": "sentry", "name": "Sentry", "category": "dev", "about": "Errors, traces, and releases",
                "auth": { "kind": "oauth" }, "state": "error", "error": "fetch failed", "account": "me@x", "readOnly": false, "offFor": [], "tools": 0 },
              { "id": "figma", "name": "Figma", "category": "design", "about": "Design files, components, and comments",
                "auth": { "kind": "oauth" }, "state": "needs-sign-in", "account": "me", "offFor": [], "tools": 0 },
              { "id": "my-docs", "name": "My docs", "category": "custom", "about": "https://docs.example.com/mcp",
                "auth": { "kind": "custom" }, "state": "ready", "account": null, "readOnly": true, "offFor": [], "tools": 2, "custom": true },
              { "id": "local", "name": "Local", "category": "custom", "about": "npx -y x",
                "auth": { "kind": "custom" }, "state": "off", "account": null, "offFor": [], "tools": 0, "custom": true },
              { "id": "mail", "name": "Mailbox" },
              { "name": "无 id" },
              "junk"
            ],
            "mail": { "accounts": [ { "id": "m1", "address": "a@qq.com", "provider": "qq", "auth": "password", "state": "ready" } ],
                      "google": false, "allowedRecipients": ["boss@x.com"], "noDraftFor": ["b1"] }
          }
        }
        """.trimIndent(),
    ) as Json.Value.Object

    private fun row(id: String) = ConnectorSettings.row(state, id)!!

    @Test
    fun `gating by connectors object presence`() {
        assertTrue(ConnectorSettings.has(state))
        assertFalse(ConnectorSettings.has(Json.parse("""{"bots":[]}""") as Json.Value.Object))
        assertFalse(ConnectorSettings.has(Json.parse("""{"connectors":[]}""") as Json.Value.Object))
        assertFalse(ConnectorSettings.has(null))
        assertNull(ConnectorSettings.view(null))
    }

    @Test
    fun `decodes rows and skips invalid and mail entries`() {
        val view = ConnectorSettings.view(state)!!
        assertEquals(listOf("github", "notion", "linear", "slack", "amap", "exa", "sentry", "figma", "my-docs", "local"), view.rows.map { it.id })
        val gh = row("github")
        assertEquals("oauth", gh.auth.kind)
        assertEquals("Ov23", gh.auth.clientId)
        assertTrue(gh.auth.deviceFlow)
        assertTrue(gh.auth.token)
        assertEquals(ConnectorSettings.Pending.Device("ABCD-1234", "https://github.com/login/device"), gh.pending)
        assertEquals(ConnectorSettings.Pending.Browser("https://linear.app/oauth/authorize?state=s1"), row("linear").pending)
        assertEquals("http://127.0.0.1:53682/callback", row("slack").redirectUri)
        assertEquals("slack", row("slack").auth.clientSetupKind)
        assertEquals("https://api.slack.com/apps", row("slack").auth.clientSetupAppUrl)
        assertEquals("https://lbs.amap.com/api/mcp-server/create", row("amap").auth.keyUrl)
        assertEquals(listOf("b2"), row("exa").offFor)
        assertEquals(3, row("exa").tools)
        assertNull(row("exa").readOnly)
        assertEquals(true, row("my-docs").readOnly)
        assertTrue(row("my-docs").custom)
        assertEquals("fetch failed", row("sentry").error)
        assertEquals(1, view.mail.accounts.size)
    }

    @Test
    fun `import response view is unwrapped`() {
        val v = Json.parse("""{"view":{"connectors":[{"id":"x","name":"X","auth":{"kind":"custom"},"state":"ready","custom":true}],"mail":{"accounts":[]}},"created":["x"]}""")
        assertEquals(listOf("x"), ConnectorSettings.decodeView(v)!!.rows.map { it.id })
    }

    @Test
    fun `status text follows web statusText`() {
        assertEquals("等待登录…", ConnectorSettings.statusText(row("github")))
        assertEquals("这个服务需要应用客户端 ID", ConnectorSettings.statusText(row("slack")))
        assertEquals("需要重新登录", ConnectorSettings.statusText(row("figma")))
        assertEquals("无法连接：fetch failed", ConnectorSettings.statusText(row("sentry")))
        assertEquals("已连接 · 3 个工具", ConnectorSettings.statusText(row("exa")))
        assertEquals("页面、数据库和 Wiki 内容", ConnectorSettings.statusText(row("notion")))
        val connecting = row("notion").copy(state = "connecting")
        assertEquals("连接中…", ConnectorSettings.statusText(connecting))
        assertEquals("已连接：me", ConnectorSettings.statusText(row("figma").copy(state = "ready")))
        // 未收录的简介原样显示；英文界面显示英文
        assertEquals("npx -y x", ConnectorSettings.statusText(row("local")))
        I18n.lang = ChatBars.Lang.EN
        assertEquals("Pages, databases, and wiki content", ConnectorSettings.statusText(row("notion")))
        assertEquals("Connected · 3 tools", ConnectorSettings.statusText(row("exa")))
    }

    @Test
    fun `connect kind per auth`() {
        assertEquals(ConnectKind.DIRECT, ConnectorSettings.connectKind(row("exa")))
        assertEquals(ConnectKind.KEY, ConnectorSettings.connectKind(row("amap")))
        assertEquals(ConnectKind.SIGN_IN, ConnectorSettings.connectKind(row("notion")))
        // 有内置客户端：登录；接受 token 但无客户端：填 token
        assertEquals(ConnectKind.SIGN_IN, ConnectorSettings.connectKind(row("github")))
        val tokenOnly = row("github").copy(auth = row("github").auth.copy(clientId = null))
        assertEquals(ConnectKind.TOKEN, ConnectorSettings.connectKind(tokenOnly))
        assertNull(ConnectorSettings.connectKind(row("local")))
    }

    @Test
    fun `actions follow web ConnectorRow`() {
        // 有进行中的登录或等待客户端：只显示面板
        assertEquals(emptyList<Action>(), ConnectorSettings.actions(row("github")))
        assertEquals(emptyList<Action>(), ConnectorSettings.actions(row("slack")))
        assertTrue(ConnectorSettings.showClientForm(row("slack")))
        assertFalse(ConnectorSettings.showClientForm(row("slack").copy(pending = ConnectorSettings.Pending.Browser("https://x"))))
        assertEquals(listOf(Action.SIGN_IN_AGAIN, Action.DISCONNECT), ConnectorSettings.actions(row("figma")))
        assertEquals(listOf(Action.RETRY, Action.DISCONNECT), ConnectorSettings.actions(row("sentry")))
        assertEquals(listOf(Action.RETRY, Action.REMOVE), ConnectorSettings.actions(row("my-docs").copy(state = "error")))
        assertEquals(listOf(Action.DISCONNECT), ConnectorSettings.actions(row("exa")))
        assertEquals(listOf(Action.REMOVE), ConnectorSettings.actions(row("my-docs")))
        assertEquals(listOf(Action.CONNECT), ConnectorSettings.actions(row("notion")))
        assertEquals(listOf(Action.REMOVE), ConnectorSettings.actions(row("local")))
        val ghOff = row("github").copy(state = "off", pending = null)
        assertEquals(listOf(Action.USE_TOKEN, Action.CONNECT), ConnectorSettings.actions(ghOff))
    }

    @Test
    fun `read-only switch only on connected remote services`() {
        assertTrue(ConnectorSettings.showReadOnly(row("my-docs")))
        assertFalse(ConnectorSettings.showReadOnly(row("exa")))
        assertFalse(ConnectorSettings.showReadOnly(row("sentry")))
    }

    @Test
    fun `sections put connected first and categorize the rest`() {
        val view = ConnectorSettings.view(state)!!
        val sections = ConnectorSettings.sections(view, "")
        assertEquals(listOf("connected", "docs", "search", "custom"), sections.map { it.key })
        val connected = sections[0].entries.map { it.key }
        assertEquals(listOf("mail", "github", "linear", "slack", "exa", "sentry", "figma", "my-docs"), connected)
        assertEquals(listOf("notion"), sections.first { it.key == "docs" }.entries.map { it.key })
        assertEquals(listOf("amap"), sections.first { it.key == "search" }.entries.map { it.key })
        assertEquals(listOf("local"), sections.first { it.key == "custom" }.entries.map { it.key })
        assertEquals("其他 MCP 服务器", sections.last().title)
        // 邮箱未连接时归「工作」分组
        val noMail = view.copy(mail = MailSettings.decode(null))
        val work = ConnectorSettings.sections(noMail, "").first { it.key == "work" }
        assertEquals(listOf("mail"), work.entries.map { it.key })
    }

    @Test
    fun `search matches names and about in both languages`() {
        val view = ConnectorSettings.view(state)!!
        assertEquals(listOf("notion"), ConnectorSettings.sections(view, "wiki").single().entries.map { it.key })
        assertEquals(listOf("notion"), ConnectorSettings.sections(view, "数据库和 Wiki").single().entries.map { it.key })
        assertEquals(listOf("mail"), ConnectorSettings.sections(view, "邮箱").single().entries.map { it.key })
        assertEquals(listOf("github"), ConnectorSettings.sections(view, "GITHUB").single().entries.map { it.key })
        assertTrue(ConnectorSettings.sections(view, "zzz-none").isEmpty())
    }

    @Test
    fun `bot rows and offFor toggling`() {
        val view = ConnectorSettings.view(state)
        assertEquals(listOf("exa", "my-docs"), ConnectorSettings.botRows(view).map { it.id })
        assertEquals(emptyList<String>(), ConnectorSettings.offForToggled(row("exa"), "b2", true))
        assertEquals(listOf("b2", "b1"), ConnectorSettings.offForToggled(row("exa"), "b1", false))
        assertEquals(listOf("b2"), ConnectorSettings.offForToggled(row("exa"), "b2", false))
        assertTrue(ConnectorSettings.botRows(null).isEmpty())
    }

    @Test
    fun `add validation mirrors connector-add`() {

        assertEquals("填写名称", ConnectorSettings.addProblem(ConnectorSettings.AddKind.URL, " ", "https://x", ""))
        assertEquals("填写服务器地址", ConnectorSettings.addProblem(ConnectorSettings.AddKind.URL, "x", "", ""))
        assertEquals("连接器地址必须以 https:// 开头", ConnectorSettings.addProblem(ConnectorSettings.AddKind.URL, "x", "http://example.com", ""))
        assertNull(ConnectorSettings.addProblem(ConnectorSettings.AddKind.URL, "x", "https://example.com/mcp", ""))
        assertNull(ConnectorSettings.addProblem(ConnectorSettings.AddKind.URL, "x", "http://localhost:3000/mcp", ""))
        assertNull(ConnectorSettings.addProblem(ConnectorSettings.AddKind.URL, "x", "HTTP://127.0.0.1:3000", ""))
        assertEquals("填写命令", ConnectorSettings.addProblem(ConnectorSettings.AddKind.COMMAND, "x", "", " "))
        assertNull(ConnectorSettings.addProblem(ConnectorSettings.AddKind.COMMAND, "x", "", "npx -y mcp"))
    }

    @Test
    fun `import scan decoding never carries values`() {
        val v = Json.parse(
            """[{"source":"claude","name":"fs","command":"npx","args":["-y","fs"],"envNames":["TOKEN"],"headersNames":[]},
               {"source":"cursor","name":"web","url":"https://w/mcp","envNames":[],"headersNames":["Authorization"]},
               {"name":"bad"}, 3]""",
        )
        val items = ConnectorSettings.importItems(v)
        assertEquals(listOf("claude:fs", "cursor:web"), items.map { it.key })
        assertEquals("npx -y fs", items[0].target)
        assertEquals("https://w/mcp", items[1].target)
        assertEquals("Claude Desktop", ConnectorSettings.sourceLabel("claude"))
        assertEquals("VS Code", ConnectorSettings.sourceLabel("vscode"))
        assertEquals(1, ConnectorSettings.importItems(Json.parse("""{"items":[{"source":"vscode","name":"a","url":"https://a"}]}""")).size)
        assertTrue(ConnectorSettings.importItems(null).isEmpty())
    }

    @Test
    fun `request bodies match upstream endpoints`() {
        val R = ConnectorSettings.Requests
        assertEquals("""{"id":"exa"}""", R.connect("exa"))
        assertEquals("""{"id":"amap","key":"k1"}""", R.connectKey("amap", " k1 "))
        assertEquals("""{"id":"github","token":"ghp_x"}""", R.connectToken("github", "ghp_x\n"))
        assertEquals("""{"id":"x"}""", R.disconnect("x"))
        assertEquals("""{"id":"x"}""", R.remove("x"))
        assertEquals("""{"id":"x"}""", R.retry("x"))
        assertEquals("""{"id":"x"}""", R.cancel("x"))
        assertEquals("""{"id":"linear","input":"http://127.0.0.1:53682/callback?code=c&state=s1"}""", R.signInCode("linear", " http://127.0.0.1:53682/callback?code=c&state=s1 "))
        assertEquals("""{"id":"slack","clientId":"cid"}""", R.client("slack", " cid ", " "))
        assertEquals("""{"id":"slack","clientId":"cid","clientSecret":"sec"}""", R.client("slack", "cid", "sec"))
        assertEquals("""{"id":"my-docs","readOnly":true}""", R.setReadOnly("my-docs", true))
        assertEquals("""{"id":"exa","offFor":["b1","b2"]}""", R.setOffFor("exa", listOf("b1", "b2")))
        assertEquals("""{"id":"exa","offFor":[]}""", R.setOffFor("exa", emptyList()))
        assertEquals("""{"name":"Docs","url":"https://d/mcp","oauth":true}""", R.addUrl(" Docs ", "https://d/mcp", true))
        assertEquals("""{"name":"Docs","url":"https://d/mcp"}""", R.addUrl("Docs", "https://d/mcp", false))
        assertEquals("""{"name":"Fs","command":"npx -y fs"}""", R.addCommand("Fs", " npx -y fs "))
        assertEquals("{}", R.importScan())
        val items = ConnectorSettings.importItems(Json.parse("""[{"source":"claude","name":"fs","command":"npx"}]"""))
        assertEquals("""{"items":[{"source":"claude","name":"fs"}]}""", R.import(items))
    }

    @Test
    fun `slow endpoints cover verification and sign-in`() {
        for (e in listOf("mail-account-add", "mail-send-answer", "connector-connect", "connector-sign-in-code", "connector-import")) {
            assertTrue(e in ConnectorSettings.SLOW_ENDPOINTS, e)
        }
        assertFalse("connector-set" in ConnectorSettings.SLOW_ENDPOINTS)
        assertEquals("github.com/login/device", ConnectorSettings.hostOf("https://github.com/login/device"))
        assertNotNull(ConnectorSettings.categoryLabel("unknown"))
    }
}
