package work.keepwriting.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * dsbot-api-spike 脱敏夹具的加载校验（tech/dsbot-api 的 fixtures-sanitized 规则）：
 * 每个夹具必须能被 core 的 Json 解析（真实结构而非编造样本），SSE 流按原始形态
 * 可解析出事件，且任何夹具不得含凭据形态（cookie 名、token 查询参数）与未脱敏的
 * 会话内容（内容键的值必须是占位符形态）。夹具来源与结构说明见
 * android/core/src/spike/FINDINGS.md。
 */
class DsbotFixturesTest {
    /** 与 spike Sanitize 的内容键清单保持同步（spike source set 对 test 不可见）。
     *  answers/selected：问答卡作答数组与其元素（单复数变体，评审修复必漏点）。
     *  data/dataurl：0.3.10 媒体端点回传的 base64 文件字节。 */
    private val contentKeys = setOf(
        "text", "preview", "instructions", "brief", "notice", "question", "answer", "answers",
        "selected",
        "note", "content", "summary", "description", "goal", "draft", "kickoff",
        "label", "caption", "transcript", "role", "why", "after", "old", "purpose", "detail",
        "url", "endpoint", "baseurl", "name", "title",
        "data", "dataurl",
    )

    private fun fixture(name: String): String {
        val res = DsbotFixturesTest::class.java.getResource("/dsbot/$name")
        assertNotNull(res, "缺少夹具 dsbot/$name")
        return res.readText(Charsets.UTF_8)
    }

    private fun parse(name: String): Json.Value {
        val text = fixture(name)
        return assertNotNull(Json.parse(text), "$name 不是合法 JSON")
    }

    /** 内容键下的所有字符串叶节点（含嵌套与数组）必须已是占位符形态。 */
    private fun assertContentRedacted(value: Json.Value, inContent: Boolean, path: String) {
        when (value) {
            is Json.Value.Object -> value.members.forEach { (k, v) ->
                val zone = inContent || k.lowercase() in contentKeys
                assertContentRedacted(v, zone, "$path.$k")
            }
            is Json.Value.Array -> value.items.forEachIndexed { i, v ->
                assertContentRedacted(v, inContent, "$path[$i]")
            }
            is Json.Value.StringValue ->
                if (inContent) {
                    assertTrue(
                        value.value.startsWith("<redacted:"),
                        "$path 的内容值未脱敏：${value.value.take(24)}…",
                    )
                }
            else -> {}
        }
    }

    @Test
    fun `state 全量夹具包含视图与 live 顶层键`() {
        val value = (parse("state-full.json") as Json.Value.Object).members
        assertEquals(setOf("ok", "value"), value.keys)
        assertEquals(true, (value["ok"] as Json.Value.BoolValue).value)
        val v = (value["value"] as Json.Value.Object).members
        assertNotNull(v["revision"], "缺 revision")
        assertTrue(v["bots"] is Json.Value.Array, "缺 bots 数组")
        assertTrue(v["rooms"] is Json.Value.Array, "缺 rooms 数组")
        assertNotNull(v["liveKey"], "缺 liveKey")
        assertNotNull(v["work"], "缺 live.work 字段")
    }

    @Test
    fun `state since+live 全命中夹具仅含三个键`() {
        val value = (parse("state-unchanged.json") as Json.Value.Object).members
        assertEquals(setOf("ok", "value"), value.keys)
        val inner = (value["value"] as Json.Value.Object).members
        assertEquals(setOf("revision", "unchanged", "liveKey"), inner.keys)
        assertEquals(true, (inner["unchanged"] as Json.Value.BoolValue).value)
    }

    @Test
    fun `state since 命中夹具返回 unchanged 加 live 字段`() {
        val inner = ((parse("state-unchanged-live.json") as Json.Value.Object).members["value"] as Json.Value.Object).members
        assertEquals(true, (inner["unchanged"] as Json.Value.BoolValue).value)
        assertTrue("revision" in inner && "liveKey" in inner)
        assertNotNull((inner["work"] as? Json.Value.Object)?.members, "缺 live.work 字段")
    }

    @Test
    fun `chat 页夹具的条目带 kind 与会话归属`() {
        val inner = ((parse("chat-page.json") as Json.Value.Object).members["value"] as Json.Value.Object).members
        assertNotNull(inner["sessionId"])
        val items = inner["items"] as Json.Value.Array
        assertTrue(items.items.isNotEmpty())
        val kinds = items.items.mapNotNull { ((it as? Json.Value.Object)?.members?.get("kind") as? Json.Value.StringValue)?.value }
        assertTrue(kinds.isNotEmpty() && kinds.all { it.isNotEmpty() })
    }

    @Test
    fun `update-check 夹具带双版本来源字段`() {
        val inner = ((parse("update-check.json") as Json.Value.Object).members["value"] as Json.Value.Object).members
        assertEquals("0.3.10", (inner["current"] as Json.Value.StringValue).value)
        val dsh = (inner["dsh"] as Json.Value.Object).members
        assertEquals("0.2.2-rc.3", (dsh["current"] as Json.Value.StringValue).value)
    }

    @Test
    fun `错误信封夹具是 ok false 与 bot 错误码`() {
        val root = (parse("error-envelope.json") as Json.Value.Object).members
        assertEquals(false, (root["ok"] as Json.Value.BoolValue).value)
        val err = (root["error"] as Json.Value.Object).members
        assertEquals("bot/invalid", (err["code"] as Json.Value.StringValue).value)
        assertTrue("message" in err && err["details"] is Json.Value.Object)
    }

    @Test
    fun `feed SSE 流夹具解析出 hello message stream 事件且 data 为合法 JSON`() {
        val text = fixture("feed-stream.sse")
        val events = mutableListOf<String>()
        var data: String? = null
        var pendingEvent: String? = null
        for (line in text.lineSequence()) {
            when {
                line.startsWith("event:") -> pendingEvent = line.substringAfter(':').trim()
                line.startsWith("data:") -> data = (data ?: "") + line.substringAfter(':').trim()
                line.isEmpty() && data != null -> {
                    assertNotNull(Json.parse(data!!), "feed data 不是合法 JSON：$data")
                    events.add(pendingEvent ?: "message")
                    data = null
                    pendingEvent = null
                }
            }
        }
        assertEquals(listOf("hello", "message", "stream"), events)
    }

    @Test
    fun `0_3_10 notices 夹具带游标与提醒条目`() {
        val v = ((parse("notices.json") as Json.Value.Object).members["value"] as Json.Value.Object).members
        assertNotNull(v["boot"])
        assertTrue(v["seq"] is Json.Value.NumberValue)
        assertEquals(false, (v["reset"] as Json.Value.BoolValue).value)
        val first = ((v["notices"] as Json.Value.Array).items.first() as Json.Value.Object).members
        assertTrue(setOf("seq", "time", "kind", "botId").all { it in first }, "条目键=${first.keys}")
        val reset = ((parse("notices-reset.json") as Json.Value.Object).members["value"] as Json.Value.Object).members
        assertEquals(true, (reset["reset"] as Json.Value.BoolValue).value, "陌生 boot 必须 reset")
    }

    @Test
    fun `0_3_10 新端点错误应答带登记的错误码`() {
        val expected = mapOf(
            "error-unknown-endpoint.json" to "bot/not-found",
            "markdown-image-forbidden.json" to "bot/forbidden",
            "file-text-unavailable.json" to "bot/unavailable",
            "file-download-invalid-offset.json" to "bot/invalid",
        )
        for ((name, code) in expected) {
            val result = DsbotApi.parseResponse(200, fixture(name))
            assertEquals(code, (result as DsbotApi.Result.BotError).code, name)
        }
        // 新错误码经缺省分支归拒绝类（FINDINGS 第 12 节登记）
        assertEquals(DsbotApi.Reason.REFUSED, DsbotApi.reasonOf("bot/forbidden"))
        assertEquals(DsbotApi.Reason.REFUSED, DsbotApi.reasonOf("bot/unavailable"))
    }

    @Test
    fun `0_3_10 媒体应答夹具形态`() {
        val image = ((parse("image-preview.json") as Json.Value.Object).members["value"] as Json.Value.Object).members
        assertEquals(setOf("mime", "data"), image.keys)
        val avatar = ((parse("avatar.json") as Json.Value.Object).members["value"] as Json.Value.Object).members
        assertEquals(setOf("botId", "ref", "image"), avatar.keys)
    }

    @Test
    fun `0_3_10 feed 夹具含 hello 与 notice 事件`() {
        val events = fixture("feed-stream-0310.sse").lineSequence()
            .filter { it.startsWith("event:") }.map { it.substringAfter(':').trim() }.toList()
        assertEquals("hello", events.first())
        assertTrue("notice" in events && "message" in events && "stream" in events, "事件=$events")
    }

    @Test
    fun `401 失效标记夹具与 SessionAuth 判定一致`() {
        val body = fixture("error-401-body.txt").trimEnd('\n', '\r')
        assertTrue(SessionAuth.isSessionExpired(401, "text/plain; charset=utf-8", body))
    }

    @Test
    fun `全部夹具不含凭据形态`() {
        val names = listOf(
            "state-full.json", "state-unchanged.json", "state-unchanged-live.json",
            "chat-page.json", "update-check.json", "error-envelope.json",
            "feed-stream.sse", "error-401-body.txt",
            "hello.json", "hello-api-unsupported.json", "notices.json", "notices-reset.json",
            "image-preview.json", "avatar.json", "error-unknown-endpoint.json",
            "markdown-image-forbidden.json", "file-text-unavailable.json", "file-download-invalid-offset.json",
            "feed-stream-0310.sse",
        )
        for (name in names) {
            val text = fixture(name)
            assertTrue(!text.contains("dsh-auth-"), "$name 含 cookie 名形态")
            assertTrue(!text.contains("token="), "$name 含 token 查询参数形态")
        }
    }

    @Test
    fun `全部 JSON 夹具的内容键值均为占位符形态`() {
        for (name in listOf(
            "state-full.json", "state-unchanged.json", "state-unchanged-live.json",
            "chat-page.json", "update-check.json", "error-envelope.json",
            "hello.json", "hello-api-unsupported.json", "notices.json", "notices-reset.json",
            "image-preview.json", "avatar.json", "error-unknown-endpoint.json",
            "markdown-image-forbidden.json", "file-text-unavailable.json", "file-download-invalid-offset.json",
        )) {
            assertContentRedacted(parse(name), inContent = false, name)
        }
        // feed 流的 data 行逐帧校验
        for (sse in listOf("feed-stream.sse", "feed-stream-0310.sse")) {
            var data: String? = null
            for (line in fixture(sse).lineSequence()) {
                if (line.startsWith("data:")) data = (data ?: "") + line.substringAfter(':').trim()
                if (line.isEmpty() && data != null) {
                    assertContentRedacted(parseValue(data!!), inContent = false, sse)
                    data = null
                }
            }
            // 文件末尾没有空行收尾的最后一帧也要校验
            data?.let { assertContentRedacted(parseValue(it), inContent = false, sse) }
        }
    }

    private fun parseValue(text: String): Json.Value = assertNotNull(Json.parse(text), "feed data 不是合法 JSON")
}
