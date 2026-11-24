package work.keepwriting.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ImBridgesTest {
    @BeforeEach
    fun resetLang() {
        I18n.source = null
        I18n.lang = ChatBars.Lang.ZH
    }

    /** 0.3.10 state.im（im/index.js imView/viewOf）与 live imStatus（imStatusView）形态。 */
    private val state = Json.parse(
        """
        {
          "bots": [ { "id": "b1", "name": "小助手" }, { "id": "b2", "name": "研究员" } ],
          "rooms": [ { "id": "r1", "name": "项目群" } ],
          "im": {
            "platforms": ["telegram", "weixin", "feishu", "future-im"],
            "connections": [
              { "id": "im_a1", "platform": "telegram", "account": { "id": "1", "name": "@ds_bot" }, "botId": "b1",
                "settings": {}, "secrets": ["token"], "enabled": true,
                "paired": [ { "id": "u1", "name": "Alice", "at": 1 } ],
                "requests": [ { "id": "u2", "name": "Bob", "chatKey": "u2", "chatKind": "direct", "at": 2 } ],
                "chats": { "u1": { "kind": "direct", "title": "", "at": 1 }, "g9": { "kind": "group", "title": "运维群", "target": { "roomId": "r1" }, "at": 3 }, "g8": { "kind": "group", "at": 4 } },
                "createdAt": 1 },
              { "id": "im_b2", "platform": "feishu", "account": null, "botId": "b2", "settings": { "domain": "lark", "appId": "cli_x" },
                "secrets": ["appSecret"], "enabled": true, "paired": [], "requests": [], "chats": {}, "createdAt": 2 },
              { "id": "im_c3", "platform": "qq", "account": null, "botId": null, "enabled": false, "paired": [], "requests": [], "chats": {} },
              { "platform": "x" }
            ]
          },
          "imStatus": { "im_a1": { "state": "ready", "at": 5 }, "im_b2": { "state": "error", "error": "app secret invalid", "at": 6 }, "im_c3": { "state": "ready", "at": 7 } }
        }
        """.trimIndent(),
    ) as Json.Value.Object

    @Test
    fun `gating by im object presence`() {
        assertTrue(ImBridges.has(state))
        assertFalse(ImBridges.has(Json.parse("""{"im":[]}""") as Json.Value.Object))
        assertFalse(ImBridges.has(null))
        assertNull(ImBridges.view(Json.parse("{}") as Json.Value.Object))
    }

    @Test
    fun `decodes connections chats and requests`() {
        val v = ImBridges.view(state)!!
        assertEquals(listOf("im_a1", "im_b2", "im_c3"), v.connections.map { it.id })
        val a = v.connections[0]
        assertEquals("@ds_bot", a.accountName)
        assertEquals(listOf(ImBridges.Paired("u1", "Alice")), a.paired)
        assertEquals(listOf(ImBridges.Request("u2", "Bob", "u2", "direct")), a.requests)
        assertEquals(setOf("u1", "g9", "g8"), a.chats.map { it.key }.toSet())
        assertEquals(ImBridges.Target(null, "r1"), a.chats.first { it.key == "g9" }.target)
        assertNull(a.chats.first { it.key == "g8" }.target)
        assertEquals("lark", v.connections[1].settings["domain"])
        assertFalse(v.connections[2].enabled)
    }

    @Test
    fun `state text follows web stateOf`() {
        val v = ImBridges.view(state)!!
        val (a, b, c) = v.connections
        assertEquals("已连接", ImBridges.stateText(a, ImBridges.statusOf(state, a.id)))
        assertEquals("无法连接：app secret invalid", ImBridges.stateText(b, ImBridges.statusOf(state, b.id)))
        assertEquals("已停用", ImBridges.stateText(c, ImBridges.statusOf(state, c.id))) // 停用优先于 ready
        assertEquals("连接中…", ImBridges.stateText(a, null))
        assertEquals("连接中…", ImBridges.stateText(a, ImBridges.Status("weird", null)))
        assertEquals("正在重试…", ImBridges.stateText(a, ImBridges.Status("retrying", null)))
        I18n.lang = ChatBars.Lang.EN
        assertEquals("Disabled", ImBridges.stateText(c, null))
    }

    @Test
    fun `titles chat names and targets`() {
        val v = ImBridges.view(state)!!
        val a = v.connections[0]
        assertEquals("@ds_bot", ImBridges.title(a))
        assertEquals("飞书 / Lark", ImBridges.title(v.connections[1]))
        assertEquals("Alice", ImBridges.chatName(a, a.chats.first { it.key == "u1" })) // 空标题 → 配对名
        assertEquals("运维群", ImBridges.chatName(a, a.chats.first { it.key == "g9" }))
        assertEquals("私聊", ImBridges.chatName(a, ImBridges.Chat("zz", "direct", null, null)))
        assertEquals("项目群", ImBridges.targetName(state, ImBridges.Target(null, "r1")))
        assertEquals("研究员", ImBridges.targetName(state, ImBridges.Target("b2", null)))
        assertNull(ImBridges.targetName(state, ImBridges.Target("gone", null)))
        assertNull(ImBridges.targetName(state, null))
        assertEquals(listOf("b1" to "小助手", "b2" to "研究员"), ImBridges.bots(state))
        assertEquals(listOf("r1" to "项目群"), ImBridges.rooms(state))
        assertEquals(listOf("im_a1" to "u2"), ImBridges.waiting(v).map { it.first.id to it.second.id })
    }

    @Test
    fun `addable platforms follow remote list in catalog order`() {
        val v = ImBridges.view(state)!!
        assertEquals(listOf("telegram", "weixin", "feishu", "future-im"), ImBridges.addablePlatforms(v).map { it.id })
        val unknown = ImBridges.platformOf("future-im")
        assertEquals("future-im", unknown.name)
        assertTrue(unknown.fields.isEmpty())
        assertTrue(ImBridges.platformOf("weixin").qrLogin)
        assertEquals(8, ImBridges.PLATFORMS.size)
        val feishu = ImBridges.platformOf("feishu")
        assertEquals("open.larksuite.com", ImBridges.linkOf(feishu, "lark")!!.second)
        assertEquals("open.feishu.cn", ImBridges.linkOf(feishu, null)!!.second)
        assertEquals(4, feishu.steps.size)
    }

    @Test
    fun `add validation and form defaults`() {
        val feishu = ImBridges.platformOf("feishu")
        assertEquals("先选择对话的 Bot", ImBridges.addProblem(feishu, "", mapOf("appId" to "a", "appSecret" to "s")))
        assertEquals("填写 App Secret", ImBridges.addProblem(feishu, "b1", mapOf("appId" to "a", "appSecret" to " ")))
        assertNull(ImBridges.addProblem(feishu, "b1", mapOf("appId" to "a", "appSecret" to "s"))) // 选项字段不必填
        assertEquals(mapOf("domain" to "feishu", "appId" to "a", "appSecret" to "s"), ImBridges.formValues(feishu, mapOf("appId" to "a", "appSecret" to "s")))
        assertEquals("lark", ImBridges.formValues(feishu, mapOf("domain" to "lark"))["domain"])
        assertEquals("feishu", ImBridges.formValues(feishu, mapOf("domain" to "bogus"))["domain"])
    }

    @Test
    fun `login answers`() {
        val start = ImBridges.loginStart(Json.parse("""{"loginId":"L1","qr":"https://liteapp.weixin.qq.com/q/x","expiresAt":1795500000000,"botId":"b1"}"""))!!
        assertEquals("L1", start.loginId)
        assertEquals(1795500000000L, start.expiresAt)
        assertNull(ImBridges.loginStart(Json.parse("""{"loginId":"L1"}""")))
        assertEquals("scanned", ImBridges.loginStatus(Json.parse("""{"status":"scanned"}""")))
        assertEquals("done", ImBridges.loginStatus(Json.parse("""{"status":"done","id":"im_x"}""")))
        assertEquals("waiting", ImBridges.loginStatus(Json.parse("""{"status":"wait"}""")))
        assertEquals("waiting", ImBridges.loginStatus(null))
        assertEquals("正在获取二维码…", ImBridges.loginCaption("waiting", false))
        assertEquals("用微信扫码", ImBridges.loginCaption("waiting", true))
        assertEquals("二维码已过期", ImBridges.loginCaption("expired", true))
        assertTrue("im-login-wait" in ImBridges.SLOW_ENDPOINTS && "im-add" in ImBridges.SLOW_ENDPOINTS)
    }

    @Test
    fun `request bodies match upstream endpoints`() {
        val R = ImBridges.Requests
        assertEquals("""{"platform":"feishu","botId":"b1","fields":{"domain":"lark","appId":"cli_x","appSecret":"s3"}}""",
            R.add("feishu", "b1", linkedMapOf("domain" to "lark", "appId" to " cli_x ", "appSecret" to "s3")))
        assertEquals("""{"id":"im_a1","enabled":false}""", R.setEnabled("im_a1", false))
        assertEquals("""{"id":"im_a1","botId":"b2"}""", R.setBot("im_a1", "b2"))
        assertEquals("""{"id":"im_a1"}""", R.remove("im_a1"))
        assertEquals("""{"id":"im_a1"}""", R.retry("im_a1"))
        assertEquals("""{"id":"im_a1","senderId":"u2","allow":true}""", R.pair("im_a1", "u2", true))
        assertEquals("""{"id":"im_a1","senderId":"u1"}""", R.unpair("im_a1", "u1"))
        assertEquals("""{"id":"im_a1","chatKey":"g9","target":{"roomId":"r1"}}""", R.bindChat("im_a1", "g9", ImBridges.Target(null, "r1")))
        assertEquals("""{"id":"im_a1","chatKey":"g9","target":{"botId":"b1"}}""", R.bindChat("im_a1", "g9", ImBridges.Target("b1", null)))
        assertEquals("""{"id":"im_a1","chatKey":"g9","target":null}""", R.bindChat("im_a1", "g9", null))
        assertEquals("""{"id":"im_a1","chatKey":"u1","remove":true}""", R.removeChat("im_a1", "u1"))
        assertEquals("""{"platform":"weixin","botId":"b1"}""", R.loginStart("weixin", "b1"))
        assertEquals("""{"platform":"weixin","botId":"b1","loginId":"L1"}""", R.loginWait("weixin", "b1", "L1"))
    }
}
