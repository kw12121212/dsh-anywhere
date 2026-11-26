package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MCP 访问纯逻辑测试（dsbot-mcp-access）：
 * 覆盖真实夹具 mcp-off.json（本机 dsh Web 宿主关闭视图）、含客户端的完整视图、缺失与畸形字段的缺省、
 * 状态说明派生、端口与名称校验边界、范围候选（排除私有 Bot）与名称合并、勾选切换、
 * mcp-client-add 应答的视图与一次性令牌分离及脱敏、全部请求体字面值。
 */
class McpAccessTest {
    private fun obj(text: String) = Json.parse(text) as Json.Value.Object

    private fun stateWith(mcp: String) = obj(
        """
        {
          "mainBotIds": ["m1"],
          "bots": [
            { "id": "m1", "name": "Main" },
            { "id": "b2", "name": "Writer" },
            { "id": "p3", "name": "Diary", "private": true }
          ],
          "rooms": [ { "id": "r1", "name": "Launch" }, { "id": "r2" } ],
          "mcp": $mcp
        }
        """,
    )

    private val full = """
        {
          "on": true, "port": 8800, "status": "on", "error": null, "url": "http://127.0.0.1:8800/mcp",
          "clients": [
            { "id": "c1", "name": "Claude Desktop", "bots": ["m1", "gone"], "rooms": ["r1"], "createdAt": 1764000000000, "lastUsedAt": null },
            { "id": "c2", "name": "Cursor", "bots": [], "rooms": [], "createdAt": 1764000001000, "lastUsedAt": 1764100000000 }
          ]
        }
    """

    @Test
    fun decodesRealOffFixture() {
        val raw = obj(javaClass.getResource("/dsbot/mcp-off.json")!!.readText())
        val view = McpAccess.decode(raw)
        assertFalse(view.on)
        assertEquals(8797, view.port)
        assertFalse(view.listening)
        assertNull(view.error)
        assertEquals("http://127.0.0.1:8797/mcp", view.url)
        assertTrue(view.clients.isEmpty())
        assertEquals(McpAccess.Status.Off, McpAccess.status(view))
    }

    @Test
    fun gatesOnFieldPresence() {
        assertTrue(McpAccess.has(stateWith(full)))
        assertFalse(McpAccess.has(obj("""{"bots":[]}""")))
        assertFalse(McpAccess.has(obj("""{"mcp":null}""")))
        assertFalse(McpAccess.has(null))
        assertNull(McpAccess.view(obj("""{"bots":[]}""")))
    }

    @Test
    fun decodesFullViewWithClients() {
        val view = McpAccess.view(stateWith(full))!!
        assertTrue(view.on)
        assertEquals(8800, view.port)
        assertTrue(view.listening)
        assertEquals(2, view.clients.size)
        val c1 = view.clients[0]
        assertEquals("c1", c1.id)
        assertEquals("Claude Desktop", c1.name)
        assertEquals(listOf("m1", "gone"), c1.bots)
        assertEquals(listOf("r1"), c1.rooms)
        assertEquals(1764000000000L, c1.createdAt)
        assertNull(c1.lastUsedAt)
        assertEquals(1764100000000L, view.clients[1].lastUsedAt)
        assertEquals(McpAccess.Status.On("http://127.0.0.1:8800/mcp"), McpAccess.status(view))
    }

    @Test
    fun malformedFieldsFallBackToDefaults() {
        val view = McpAccess.decode(
            obj(
                """{"on":"yes","port":"8000","status":1,"error":"","url":5,
                   "clients":[1, {"name":"no id"}, {"id":"x","bots":"m1","rooms":[1,"r1","r1",""]}, {"id":" "}]}""",
            ),
        )
        assertFalse(view.on)
        assertEquals(McpAccess.DEFAULT_PORT, view.port)
        assertFalse(view.listening)
        assertNull(view.error)
        assertEquals("http://127.0.0.1:8797/mcp", view.url)
        assertEquals(1, view.clients.size)
        assertEquals("x", view.clients[0].name)
        assertTrue(view.clients[0].bots.isEmpty())
        assertEquals(listOf("r1"), view.clients[0].rooms)
        // 空对象：全部缺省
        val empty = McpAccess.decode(obj("{}"))
        assertEquals(McpAccess.DEFAULT_PORT, empty.port)
        assertTrue(empty.clients.isEmpty())
        // 越界端口回落缺省
        assertEquals(McpAccess.DEFAULT_PORT, McpAccess.decode(obj("""{"port":70000}""")).port)
    }

    @Test
    fun statusFollowsWebPrecedence() {
        val base = McpAccess.decode(obj(full))
        // error 优先于开关与 status
        assertEquals(McpAccess.Status.Error("Port 8800 is in use"), McpAccess.status(base.copy(error = "Port 8800 is in use", listening = false)))
        assertEquals(McpAccess.Status.Error("read only"), McpAccess.status(base.copy(on = false, error = "read only")))
        assertEquals(McpAccess.Status.Off, McpAccess.status(base.copy(on = false, listening = true)))
        assertEquals(McpAccess.Status.Connecting, McpAccess.status(base.copy(listening = false)))
    }

    @Test
    fun parsesPortWithinServerRange() {
        assertEquals(1024, McpAccess.parsePort("1024"))
        assertEquals(65535, McpAccess.parsePort(" 65535 "))
        assertEquals(8800, McpAccess.parsePort("8800"))
        assertNull(McpAccess.parsePort("1023"))
        assertNull(McpAccess.parsePort("65536"))
        assertNull(McpAccess.parsePort("80"))
        assertNull(McpAccess.parsePort(""))
        assertNull(McpAccess.parsePort("8800.5"))
        assertNull(McpAccess.parsePort("-8800"))
        assertNull(McpAccess.parsePort("8 800"))
        assertNull(McpAccess.parsePort("99999999999"))
        assertNull(McpAccess.parsePort("８８００")) // 全角数字不接受
        val view = McpAccess.decode(obj(full))
        assertFalse(McpAccess.canSavePort("8800", view)) // 未改动
        assertTrue(McpAccess.canSavePort("8801", view))
        assertFalse(McpAccess.canSavePort("80", view))
    }

    @Test
    fun normalizesNameLikeServer() {
        assertNull(McpAccess.normalizeName(""))
        assertNull(McpAccess.normalizeName("   \n"))
        assertEquals("Claude Desktop", McpAccess.normalizeName("  Claude Desktop "))
        val long = "a".repeat(61)
        assertEquals("a".repeat(60), McpAccess.normalizeName(long))
        assertEquals("a".repeat(60), McpAccess.normalizeName("a".repeat(60)))
        // 第 60 个单元是代理对的高位：少取一个，不留半个字符
        val emoji = "a".repeat(59) + "😀" + "b"
        val cut = McpAccess.normalizeName(emoji)!!
        assertEquals("a".repeat(59), cut)
        assertFalse(Character.isHighSurrogate(cut.last()))
    }

    @Test
    fun scopeExcludesPrivateBotsAndReachableSkipsUnknown() {
        val state = stateWith(full)
        val scope = McpAccess.scope(state)
        assertEquals(listOf("m1", "b2"), scope.bots.map { it.id })
        assertEquals(listOf("Launch", "r2"), scope.rooms.map { it.name })
        val c1 = McpAccess.view(state)!!.clients[0]
        assertEquals(listOf("Main", "Launch"), McpAccess.reachable(c1, scope))
        assertTrue(McpAccess.reachable(McpAccess.view(state)!!.clients[1], scope).isEmpty())
        assertTrue(McpAccess.scope(null).bots.isEmpty())
        // 已转为私有的 Bot 仍在客户端范围里时不显示名称（候选排除私有 Bot）
        val withPrivate = McpAccess.view(stateWith(full))!!.clients[0].copy(bots = listOf("p3", "b2"))
        assertEquals(listOf("Writer", "Launch"), McpAccess.reachable(withPrivate, scope))
    }

    @Test
    fun toggleAddsOrRemoves() {
        assertEquals(listOf("m1", "b2"), McpAccess.toggle(listOf("m1"), "b2"))
        assertEquals(listOf("b2"), McpAccess.toggle(listOf("m1", "b2"), "m1"))
        assertEquals(listOf("r1"), McpAccess.toggle(emptyList(), "r1"))
    }

    @Test
    fun createdSplitsViewAndOneTimeToken() {
        val secret = "dsb_" + "Q".repeat(43)
        val value = Json.parse("""{"on":true,"port":8797,"status":"on","error":null,"url":"http://127.0.0.1:8797/mcp","clients":[{"id":"c9","name":"Codex","bots":["m1"],"rooms":[],"createdAt":1}],"token":"$secret"}""")
        val created = McpAccess.decodeCreated(value)!!
        assertEquals("Codex", created.view.clients.single().name)
        assertEquals(secret, created.token!!.value)
        // 令牌脱敏：toString 与包含它的 data class 都不输出明文
        assertFalse(created.token.toString().contains("Q".repeat(10)))
        assertFalse(created.toString().contains("Q".repeat(10)))
        assertEquals(McpAccess.Token(secret), created.token)
        // 非 dsb_ 前缀、空令牌与缺失令牌都不当作令牌
        assertNull(McpAccess.decodeCreated(Json.parse("""{"on":true,"clients":[],"token":"abc"}"""))!!.token)
        assertNull(McpAccess.decodeCreated(Json.parse("""{"on":true,"clients":[],"token":"dsb_"}"""))!!.token)
        assertNull(McpAccess.decodeCreated(Json.parse("""{"on":true,"clients":[]}"""))!!.token)
        // 不是视图
        assertNull(McpAccess.decodeCreated(Json.Value.BoolValue(true)))
        assertNull(McpAccess.decodeCreated(Json.parse("""{"token":"$secret"}""")))
        assertNull(McpAccess.decodeValue(null))
        assertNotNull(McpAccess.decodeValue(Json.parse("""{"on":false,"clients":[]}""")))
    }

    @Test
    fun requestBodiesMatchWebClientCalls() {
        val r = McpAccess.Requests
        assertEquals("""{"on":true}""", r.setOn(true))
        assertEquals("""{"on":false}""", r.setOn(false))
        assertEquals("""{"port":8800}""", r.setPort(8800))
        assertEquals("""{"name":"Claude \"D\""}""", r.add("Claude \"D\""))
        assertEquals("""{"id":"c1","name":"Cursor"}""", r.rename("c1", "Cursor"))
        assertEquals("""{"id":"c1","bots":["m1","b2"]}""", r.setBots("c1", listOf("m1", "b2")))
        assertEquals("""{"id":"c1","bots":[]}""", r.setBots("c1", emptyList()))
        assertEquals("""{"id":"c1","rooms":["r1"]}""", r.setRooms("c1", listOf("r1")))
        assertEquals("""{"id":"c1"}""", r.remove("c1"))
        assertEquals("mcp-set", McpAccess.SET)
        assertEquals("mcp-client-add", McpAccess.CLIENT_ADD)
        assertEquals("mcp-client-update", McpAccess.CLIENT_UPDATE)
        assertEquals("mcp-client-remove", McpAccess.CLIENT_REMOVE)
    }
}
