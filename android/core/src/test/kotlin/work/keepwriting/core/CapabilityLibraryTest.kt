package work.keepwriting.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CapabilityLibraryTest {
    @BeforeEach
    fun resetLang() {
        I18n.source = null
        I18n.lang = ChatBars.Lang.ZH
    }

    /** 0.3.10 state 形态（channel.js view + live capabilityStatuses；libraryView 字段）。 */
    private val state = Json.parse(
        """
        {
          "bots": [
            { "id": "b1", "name": "小助手", "capabilities": ["notes", "web-pack"], "packs": [] },
            { "id": "b2", "name": "研究员", "capabilities": ["notes"], "packs": ["web-pack", "local-fs"] },
            { "id": "b3", "name": "空闲" }
          ],
          "capabilities": [
            { "name": "web-pack", "kind": "pack", "description": "", "createdBy": null, "createdAt": 1, "updatedAt": 1, "items": ["search", "notes"] },
            { "name": "search", "kind": "mcp", "description": "网页搜索", "createdBy": null, "transport": "streamable-http", "url": "https://mcp.example.com", "headers": { "Authorization": "Bearer ${'$'}DSH_SECRET_SEARCH" }, "approved": true },
            { "name": "local-fs", "kind": "mcp", "description": "", "createdBy": "b2", "transport": "stdio", "command": "npx", "args": ["-y", "fs-mcp"], "env": { "ROOT": "/tmp" }, "approved": false },
            { "name": "notes", "kind": "skill", "description": "记笔记", "createdBy": null, "content": "# Notes\nWrite things down." },
            { "kind": "skill" },
            "junk"
          ],
          "capabilityStatuses": {
            "b1": { "notes": { "state": "ready" }, "web-pack": { "state": "error", "error": "spawn ENOENT" } },
            "b2": { "notes": { "state": "connecting" } }
          }
        }
        """.trimIndent(),
    ) as Json.Value.Object

    @Test
    fun `能力库字段存在门控与解码（无效条目跳过、按名称排序）`() {
        assertTrue(CapabilityLibrary.hasLibrary(state))
        assertFalse(CapabilityLibrary.hasLibrary(Json.parse("""{"bots":[]}""") as Json.Value.Object))
        assertFalse(CapabilityLibrary.hasLibrary(null))

        val lib = CapabilityLibrary.library(state)
        assertEquals(listOf("local-fs", "notes", "search", "web-pack"), lib.map { it.name })
        val fs = lib.first { it.name == "local-fs" }
        assertEquals(CapabilityLibrary.STDIO, fs.transport)
        assertEquals(listOf("-y", "fs-mcp"), fs.args)
        assertTrue(fs.needsApproval)
        assertEquals("b2", fs.createdBy)
        val search = lib.first { it.name == "search" }
        assertFalse(search.needsApproval)
        // 密钥引用按原文保留
        assertEquals("Bearer \$DSH_SECRET_SEARCH", (search.headers!!.members["Authorization"] as Json.Value.StringValue).value)
        assertEquals(listOf("search", "notes"), lib.first { it.name == "web-pack" }.items)
    }

    @Test
    fun `挂载模式、单 Bot 状态与团队层面最严重状态`() {
        assertEquals(CapabilityLibrary.Mode.ALWAYS, CapabilityLibrary.savedMode(state, "b1", "notes"))
        assertEquals(CapabilityLibrary.Mode.CATALOG, CapabilityLibrary.savedMode(state, "b2", "local-fs"))
        assertEquals(CapabilityLibrary.Mode.OFF, CapabilityLibrary.savedMode(state, "b3", "notes"))
        assertEquals(CapabilityLibrary.Mode.OFF, CapabilityLibrary.savedMode(state, "nobody", "notes"))

        assertEquals(CapabilityLibrary.Status("error", "spawn ENOENT"), CapabilityLibrary.botStatuses(state, "b1")["web-pack"])
        assertTrue(CapabilityLibrary.botStatuses(state, "b3").isEmpty())

        // notes：b1 就绪、b2 连接中 → 连接中更严重
        assertEquals("connecting", CapabilityLibrary.teamStatus(state, "notes")!!.state)
        assertEquals("error", CapabilityLibrary.teamStatus(state, "web-pack")!!.state)
        assertNull(CapabilityLibrary.teamStatus(state, "search"))

        val lib = CapabilityLibrary.library(state).associateBy { it.name }
        // 待批准优先于服务端状态
        assertEquals("needs-approval", CapabilityLibrary.listStatus(state, lib.getValue("local-fs"))!!.state)
        assertEquals(listOf("小助手", "研究员"), CapabilityLibrary.users(state, "notes"))
        assertEquals(listOf("研究员"), CapabilityLibrary.users(state, "local-fs"))
        // search 只经由能力包 web-pack 使用（b1 常驻、b2 按需）
        assertEquals(listOf("小助手", "研究员"), CapabilityLibrary.users(state, "search"))
        assertNull(CapabilityLibrary.sourceBot(state, lib.getValue("notes")))
        assertEquals("研究员", CapabilityLibrary.sourceBot(state, lib.getValue("local-fs")))

        // 列表排序：待批准、出错、其余按名称
        assertEquals(listOf("local-fs", "web-pack", "notes", "search"), CapabilityLibrary.listOrder(state, CapabilityLibrary.library(state)).map { it.name })
    }

    @Test
    fun `Bot 详情能力摘要只计能力库中存在的项`() {
        assertEquals("2 项常驻", CapabilityLibrary.botSummary(state, "b1"))
        assertEquals("1 项常驻 · 2 项按需", CapabilityLibrary.botSummary(state, "b2"))
        assertEquals("未开启能力", CapabilityLibrary.botSummary(state, "b3"))
        val stale = Json.parse("""{"bots":[{"id":"x","capabilities":["gone"],"packs":[]}],"capabilities":[]}""") as Json.Value.Object
        assertEquals("未开启能力", CapabilityLibrary.botSummary(stale, "x"))
    }

    @Test
    fun `严重程度顺序与状态文案`() {
        assertTrue(CapabilityLibrary.severity("error") > CapabilityLibrary.severity("needs-approval"))
        assertTrue(CapabilityLibrary.severity("needs-approval") > CapabilityLibrary.severity("connecting"))
        assertTrue(CapabilityLibrary.severity("connecting") > CapabilityLibrary.severity("ready"))
        assertEquals("无法连接：boom", CapabilityLibrary.statusText(CapabilityLibrary.Status("error", "boom")))
        assertEquals("待批准", CapabilityLibrary.statusText(CapabilityLibrary.Status("needs-approval", null)))
        assertEquals("就绪", CapabilityLibrary.statusText(CapabilityLibrary.Status("ready", null)))
        assertEquals("连接中…", CapabilityLibrary.statusText(CapabilityLibrary.Status("connecting", null)))
        assertNull(CapabilityLibrary.statusText(null))
        val lib = CapabilityLibrary.library(state).associateBy { it.name }
        assertEquals("search, notes", CapabilityLibrary.secondary(lib.getValue("web-pack")))
        assertEquals("记笔记", CapabilityLibrary.secondary(lib.getValue("notes")))
        assertEquals("", CapabilityLibrary.secondary(lib.getValue("local-fs")))
        assertEquals("MCP · 本地命令", CapabilityLibrary.kindLabel(lib.getValue("local-fs")))
    }

    @Test
    fun `Bot 能力页行：能力包在前、经由能力包来源、待批准与常驻状态（同网页端 BotCapabilitiesCard）`() {
        val rows = CapabilityLibrary.botRows(state, "b1").associateBy { it.item.name }
        assertEquals("web-pack", CapabilityLibrary.botRows(state, "b1").first().item.name)
        // search 关闭但经由常驻的 web-pack 生效
        assertEquals(CapabilityLibrary.Mode.OFF, rows.getValue("search").mode)
        assertEquals(listOf("web-pack" to CapabilityLibrary.Mode.ALWAYS), rows.getValue("search").viaPacks)
        // notes 常驻：不列来源，显示就绪
        assertTrue(rows.getValue("notes").viaPacks.isEmpty())
        assertEquals("ready", rows.getValue("notes").status!!.state)
        // local-fs 关闭且无来源：不提示批准
        assertFalse(rows.getValue("local-fs").needsApproval)

        val r2 = CapabilityLibrary.botRows(state, "b2").associateBy { it.item.name }
        // 按需挂载的未批准 stdio：提示批准、不显示状态
        assertTrue(r2.getValue("local-fs").needsApproval)
        assertNull(r2.getValue("local-fs").status)
        // search 关闭，web-pack 为按需 → 关闭时也列出按需的包
        assertEquals(listOf("web-pack" to CapabilityLibrary.Mode.CATALOG), r2.getValue("search").viaPacks)

        // 在途选择覆盖：把 web-pack 关掉后 b1 的 search 不再有来源
        val pending = CapabilityLibrary.botRows(state, "b1") { if (it == "web-pack") CapabilityLibrary.Mode.OFF else CapabilityLibrary.savedMode(state, "b1", it) }
        assertTrue(pending.first { it.item.name == "search" }.viaPacks.isEmpty())
    }

    @Test
    fun `名称预校验与服务端规则一致`() {
        assertNull(CapabilityLibrary.nameProblem("my-tool"))
        assertNull(CapabilityLibrary.nameProblem("  a1-b2  "))
        assertEquals(CapabilityLibrary.NameProblem.INVALID, CapabilityLibrary.nameProblem("My-Tool"))
        assertEquals(CapabilityLibrary.NameProblem.INVALID, CapabilityLibrary.nameProblem("my_tool"))
        assertEquals(CapabilityLibrary.NameProblem.INVALID, CapabilityLibrary.nameProblem("my--tool"))
        assertEquals(CapabilityLibrary.NameProblem.INVALID, CapabilityLibrary.nameProblem("1tool"))
        assertEquals(CapabilityLibrary.NameProblem.INVALID, CapabilityLibrary.nameProblem("tool-"))
        assertEquals(CapabilityLibrary.NameProblem.INVALID, CapabilityLibrary.nameProblem(""))
        assertEquals(CapabilityLibrary.NameProblem.INVALID, CapabilityLibrary.nameProblem("a".repeat(33)))
        assertNull(CapabilityLibrary.nameProblem("a".repeat(32)))
        assertEquals(CapabilityLibrary.NameProblem.RESERVED, CapabilityLibrary.nameProblem("github"))
        assertEquals(CapabilityLibrary.NameProblem.RESERVED, CapabilityLibrary.nameProblem("mail"))
        assertEquals(CapabilityLibrary.NameProblem.TAKEN, CapabilityLibrary.nameProblem("notes", listOf("notes")))
    }

    @Test
    fun `capability-save 请求体：四种类型字面值`() {
        val stdio = CapabilityLibrary.build(
            CapabilityLibrary.Draft(name = " fs ", description = "文件", command = "npx", argsText = "-y\nfs-mcp\n", envText = """{"KEY": "${'$'}DSH_SECRET_K"}"""),
        )
        assertEquals(
            """{"name":"fs","kind":"mcp","description":"文件","transport":"stdio","command":"npx","args":["-y","fs-mcp"],"env":{"KEY":"${'$'}DSH_SECRET_K"}}""",
            (stdio as CapabilityLibrary.Built.Ok).payload,
        )
        val http = CapabilityLibrary.build(CapabilityLibrary.Draft(name = "web", transport = CapabilityLibrary.HTTP, url = "https://x", headersText = "  "))
        assertEquals("""{"name":"web","kind":"mcp","description":"","transport":"streamable-http","url":"https://x","headers":{}}""", (http as CapabilityLibrary.Built.Ok).payload)
        val skill = CapabilityLibrary.build(CapabilityLibrary.Draft(name = "s", kind = CapabilityLibrary.SKILL, content = "line1\n\"q\""))
        assertEquals("""{"name":"s","kind":"skill","description":"","content":"line1\n\"q\""}""", (skill as CapabilityLibrary.Built.Ok).payload)
        val pack = CapabilityLibrary.build(CapabilityLibrary.Draft(name = "p", kind = CapabilityLibrary.PACK, items = listOf("s", "web")))
        assertEquals("""{"name":"p","kind":"pack","description":"","items":["s","web"]}""", (pack as CapabilityLibrary.Built.Ok).payload)
    }

    @Test
    fun `capability-save 本地拒绝：不合规名称、新建同名、非对象或非文本 JSON；编辑不校验同名`() {
        assertTrue(CapabilityLibrary.build(CapabilityLibrary.Draft(name = "Bad_Name")) is CapabilityLibrary.Built.Invalid)
        assertTrue(CapabilityLibrary.build(CapabilityLibrary.Draft(name = "notes"), listOf("notes")) is CapabilityLibrary.Built.Invalid)
        assertTrue(CapabilityLibrary.build(CapabilityLibrary.Draft(name = "x", envText = "[1]")) is CapabilityLibrary.Built.Invalid)
        assertTrue(CapabilityLibrary.build(CapabilityLibrary.Draft(name = "x", envText = """{"A": 1}""")) is CapabilityLibrary.Built.Invalid)
        assertTrue(CapabilityLibrary.build(CapabilityLibrary.Draft(name = "x", envText = "{not json")) is CapabilityLibrary.Built.Invalid)
        assertTrue(CapabilityLibrary.build(CapabilityLibrary.Draft(name = "x", transport = CapabilityLibrary.HTTP, headersText = """{"A": null}""")) is CapabilityLibrary.Built.Invalid)
        assertTrue(CapabilityLibrary.build(CapabilityLibrary.Draft(name = "notes", kind = CapabilityLibrary.SKILL, originalName = "notes"), listOf("notes")) is CapabilityLibrary.Built.Ok)
    }

    @Test
    fun `编辑草稿往返：draftOf 再 build 得到等价请求体`() {
        val lib = CapabilityLibrary.library(state).associateBy { it.name }
        val d = CapabilityLibrary.draftOf(lib.getValue("local-fs"))
        assertEquals("local-fs", d.originalName)
        assertEquals("-y\nfs-mcp", d.argsText)
        assertEquals("{\n  \"ROOT\": \"/tmp\"\n}", d.envText)
        assertEquals(
            """{"name":"local-fs","kind":"mcp","description":"","transport":"stdio","command":"npx","args":["-y","fs-mcp"],"env":{"ROOT":"/tmp"}}""",
            (CapabilityLibrary.build(d) as CapabilityLibrary.Built.Ok).payload,
        )
        assertEquals("{}", CapabilityLibrary.draftOf(lib.getValue("notes")).envText)
        assertEquals(listOf("local-fs", "notes", "search"), CapabilityLibrary.packCandidates(CapabilityLibrary.library(state), "web-pack").map { it.name })
    }

    @Test
    fun `删除、批准与 Bot 挂载请求体`() {
        assertEquals("""{"name":"a-b"}""", CapabilityLibrary.Requests.delete("a-b"))
        assertEquals("""{"name":"a-b"}""", CapabilityLibrary.Requests.approve("a-b"))
        assertEquals("""{"botId":"b1","name":"n","attached":true,"mode":"always"}""", CapabilityLibrary.Requests.attach("b1", "n", CapabilityLibrary.Mode.ALWAYS))
        assertEquals("""{"botId":"b1","name":"n","attached":true,"mode":"catalog"}""", CapabilityLibrary.Requests.attach("b1", "n", CapabilityLibrary.Mode.CATALOG))
        assertEquals("""{"botId":"b1","name":"n","attached":false,"mode":"always"}""", CapabilityLibrary.Requests.attach("b1", "n", CapabilityLibrary.Mode.OFF))
    }
}
