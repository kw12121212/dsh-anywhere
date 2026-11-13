package work.keepwriting.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * dsbot-api-client 的 core 组件测试：全部以 dsbot-api-spike 归档的脱敏夹具驱动
 * （真实结构而非编造样本，tech/dsbot-api 的 fixtures-sanitized）。
 */
class DsbotClientTest {
    private fun fixture(name: String): String {
        val res = DsbotClientTest::class.java.getResource("/dsbot/$name")
        return checkNotNull(res, { "缺少夹具 dsbot/$name" }).readText(Charsets.UTF_8)
    }

    private fun fixtureValue(name: String): Json.Value {
        val root = checkNotNull(Json.parse(fixture(name)), { "$name 不是合法 JSON" })
        return (root as Json.Value.Object).members.getValue("value")
    }

    // ---- DsbotApi 信封与解码 ----

    @Test
    fun `url 拼接去尾斜杠且子路径原样保留`() {
        assertEquals("https://h.test:13080/api/bot", DsbotApi.url("https://h.test:13080/", "/api/bot"))
        assertEquals("https://h.test:13080/api/bot", DsbotApi.url("https://h.test:13080", "/api/bot"))
        assertEquals("http://10.0.2.2:13190/api/bot/feed", DsbotApi.url("http://10.0.2.2:13190/", "/api/bot/feed"))
    }

    @Test
    fun `信封构造包含 endpoint 与 payload`() {
        assertEquals("""{"endpoint":"state","payload":{"since":1690}}""", DsbotApi.buildRequest("state", """{"since":1690}"""))
        assertEquals("""{"endpoint":"update-check","payload":null}""", DsbotApi.buildRequest("update-check", ""))
    }

    @Test
    fun `ok 信封解出 value 原始节点`() {
        val result = DsbotApi.parseResponse(200, fixture("state-unchanged.json"))
        val ok = assertIs<DsbotApi.Result.Ok>(result)
        val inner = (ok.value as Json.Value.Object).members
        assertEquals(true, (inner["unchanged"] as Json.Value.BoolValue).value)
    }

    @Test
    fun `bot 错误信封解出错误码并映射原因`() {
        val result = DsbotApi.parseResponse(200, fixture("error-envelope.json"))
        val err = assertIs<DsbotApi.Result.BotError>(result)
        assertEquals("bot/invalid", err.code)
        assertEquals(DsbotApi.Reason.BAD_INPUT, DsbotApi.reasonOf(err.code))
        assertEquals(DsbotApi.Reason.STALE, DsbotApi.reasonOf("bot/stale"))
        assertEquals(DsbotApi.Reason.REFUSED, DsbotApi.reasonOf("bot/whatever-new"))
    }

    @Test
    fun `401 与 403 映射为会话失效与 Host 拒绝`() {
        assertEquals(DsbotApi.Reason.AUTH_EXPIRED, (DsbotApi.parseResponse(401, "unauthorized") as DsbotApi.Result.Failure).reason)
        assertEquals(DsbotApi.Reason.FORBIDDEN_HOST, (DsbotApi.parseResponse(403, "forbidden") as DsbotApi.Result.Failure).reason)
        assertEquals(DsbotApi.Reason.NETWORK, (DsbotApi.parseResponse(502, "bad gateway") as DsbotApi.Result.Failure).reason)
        assertEquals(DsbotApi.Reason.SERVER_ERROR, (DsbotApi.parseResponse(200, "not json") as DsbotApi.Result.Failure).reason)
    }

    // ---- SseParser ----

    @Test
    fun `feed 夹具解析出三个帧且事件与可解析 data`() {
        val parser = SseParser()
        val frames = parser.feed(fixture("feed-stream.sse")) + parser.finish()
        assertEquals(listOf("hello", "message", "stream"), frames.map { it.event })
        frames.forEach { checkNotNull(Json.parse(it.data), { "data 不是 JSON：${it.event}" }) }
        val hello = Json.parse(frames[0].data) as Json.Value.Object
        assertTrue(hello.members.containsKey("revision"))
    }

    @Test
    fun `增量喂入与注释保活`() {
        val parser = SseParser()
        // 帧被任意切分：event 行、半个 data、注释分次喂入，空行收帧
        assertTrue(parser.feed("event: hello\r\n: keepal").isEmpty())
        assertTrue(parser.feed("ive\r\nda").isEmpty())
        val frames = parser.feed("ta: {\"revision\":7}\n\n")
        assertEquals(1, frames.size)
        assertEquals("hello", frames[0].event)
        assertEquals("""{"revision":7}""", frames[0].data)
        assertTrue(parser.finish().isEmpty())
    }

    @Test
    fun `多行 data 拼接且无 event 默认 message`() {
        val parser = SseParser()
        val frames = parser.feed("data: a\ndata: b\n\n")
        assertEquals(1, frames.size)
        assertEquals("message", frames[0].event)
        assertEquals("a\nb", frames[0].data)
    }

    // ---- DsbotModels 解码 ----

    @Test
    fun `state 全量夹具解码出修订号与集合`() {
        val state = checkNotNull(DsbotModels.decodeState(fixtureValue("state-full.json")))
        assertEquals("1694", state.revision)
        assertTrue(state.liveKey != null)
        assertTrue(state.bots.isNotEmpty() && state.rooms.isNotEmpty())
        assertTrue(state.bots.all { it.id.isNotEmpty() })
        // raw 保留未知字段（降级显示的输入）
        assertTrue(state.raw.members.keys.size > 30)
    }

    @Test
    fun `state 未知与变形字段降级为原始节点`() {
        val mutated = """{"revision":"42","bots":[{"id":"b1","sessionId":123},
            {"no-id":true}],"rooms":[],"futureField":{"x":1}}"""
        val state = checkNotNull(DsbotModels.decodeState(checkNotNull(Json.parse(mutated))))
        assertEquals("42", state.revision)
        assertEquals(1, state.bots.size) // sessionId 非字符串降级 null 但 Bot 保留
        assertNull(state.bots[0].sessionId)
        assertTrue(state.raw.members.containsKey("futureField"))
    }

    @Test
    fun `增量夹具不再被误判为全量`() {
        // 评审 M1 回归锁：unchanged 夹具只含 revision/unchanged/liveKey，不得解成全量
        assertNull(DsbotModels.decodeState(fixtureValue("state-unchanged.json")))
        assertNull(DsbotModels.decodeState(fixtureValue("state-unchanged-live.json")))
        assertNotNull(DsbotModels.decodeState(fixtureValue("state-full.json")))
    }

    @Test
    fun `增量 live 字段合并进全量`() {
        val full = checkNotNull(DsbotModels.decodeState(fixtureValue("state-full.json")))
        val delta = checkNotNull(DsbotModels.decodeStateDelta(fixtureValue("state-unchanged-live.json")))
        val merged = DsbotModels.mergeDelta(full, delta)
        // 条目结构保留、live 键覆盖（work 来自 delta）
        assertEquals(full.bots.size, merged.bots.size)
        assertTrue(merged.raw.members.containsKey("work"))
        assertEquals(delta.revision, merged.revision)
        // 无 live 字段的纯 unchanged：原样返回
        val pure = checkNotNull(DsbotModels.decodeStateDelta(fixtureValue("state-unchanged.json")))
        assertEquals(full, DsbotModels.mergeDelta(full, pure))
    }

    @Test
    fun `state 增量三档形态解码`() {
        val deltaLive = checkNotNull(DsbotModels.decodeStateDelta(fixtureValue("state-unchanged-live.json")))
        assertTrue(deltaLive.unchanged && deltaLive.hasLiveFields)
        val deltaPure = checkNotNull(DsbotModels.decodeStateDelta(fixtureValue("state-unchanged.json")))
        assertTrue(deltaPure.unchanged && !deltaPure.hasLiveFields)
        assertNull(DsbotModels.decodeStateDelta(checkNotNull(Json.parse("""{"revision":1}"""))))
    }

    @Test
    fun `chat 页夹具解码条目与序号`() {
        val page = DsbotModels.decodeChatPage(fixtureValue("chat-page.json"))
        assertTrue(page.items.isNotEmpty())
        assertTrue(page.items.all { it.kind != null })
        assertTrue(page.items.any { it.seq != null && it.seq > 0 })
    }

    // ---- DsbotVersions ----

    @Test
    fun `update-check 夹具解码双版本并与基准比对`() {
        val remote = DsbotVersions.fromUpdateCheck(fixtureValue("update-check.json"))
        assertEquals("0.3.6", remote.dsBot)
        assertEquals("0.2.2-rc.1", remote.dsh)
        assertEquals(DsbotVersions.Verdict.MATCHED, DsbotVersions.againstBaseline(remote))
        assertEquals(
            DsbotVersions.Verdict.MISMATCHED,
            DsbotVersions.againstBaseline(DsbotVersions.Remote("0.3.3", "0.2.2-rc.1")),
        )
        assertEquals(DsbotVersions.Verdict.UNKNOWN, DsbotVersions.againstBaseline(DsbotVersions.Remote(null, "0.2.2")))
    }

    @Test
    fun `语义化比较支持预发布段`() {
        assertTrue(DsbotVersions.compare("0.2.2", "0.2.2-rc.1") > 0)
        assertTrue(DsbotVersions.compare("0.2.2-rc.2", "0.2.2-rc.1") > 0)
        assertTrue(DsbotVersions.compare("0.3.0", "0.2.9") > 0)
        assertEquals(0, DsbotVersions.compare("0.2.2-rc.1", "0.2.2-rc.1"))
        assertTrue(DsbotVersions.compare("v1.0.0+build", "1.0.0") == 0)
    }

    // ---- FeedSync 状态机 ----

    private class Recorder : FeedSync.Listener {
        val fetches = mutableListOf<Pair<String?, String?>>()
        override fun fetchState(localRevision: String?, liveKey: String?) {
            fetches.add(localRevision to liveKey)
        }
    }

    @Test
    fun `hello 与本地不一致触发全量、一致则零负载`() {
        val r = Recorder()
        val sync = FeedSync(r)
        sync.onEvent("hello", """{"revision":1690}""")
        assertEquals(listOf<Pair<String?, String?>>(null to null), r.fetches)
        sync.onStateFetched(checkNotNull(DsbotModels.decodeState(fixtureValue("state-full.json"))))
        r.fetches.clear()
        sync.onEvent("hello", """{"revision":1694}""")
        assertTrue(r.fetches.isEmpty()) // 与本地一致：hello 不触发
    }

    @Test
    fun `revision 与 live 事件触发 since 增量取 state`() {
        val r = Recorder()
        val sync = FeedSync(r)
        sync.onStateFetched(checkNotNull(DsbotModels.decodeState(fixtureValue("state-full.json"))))
        sync.onEvent("revision", """{"revision":1695}""")
        sync.onEvent("live", "{}")
        assertEquals(2, r.fetches.size)
        assertEquals("1694" to checkNotNull(DsbotModels.decodeState(fixtureValue("state-full.json"))).liveKey, r.fetches[0])
    }

    @Test
    fun `重连后重复 revision 通知不重复取`() {
        val r = Recorder()
        val sync = FeedSync(r)
        sync.onStateFetched(checkNotNull(DsbotModels.decodeState(fixtureValue("state-full.json"))))
        sync.onEvent("revision", """{"revision":1694}""") // 与本地相同（重连重复通知）
        assertTrue(r.fetches.isEmpty())
    }

    @Test
    fun `增量应答回填修订号与 liveKey`() {
        val r = Recorder()
        val sync = FeedSync(r)
        sync.onEvent("hello", """{"revision":1694}""")
        sync.onStateFetched(checkNotNull(DsbotModels.decodeStateDelta(fixtureValue("state-unchanged-live.json"))))
        assertEquals("1694", sync.localRevision)
        assertTrue(sync.liveKey != null)
    }

    @Test
    fun `断线指数退避且连接成功归零`() {
        val r = Recorder()
        val sync = FeedSync(r)
        assertEquals(1_000L, sync.onDisconnected())
        assertEquals(2_000L, sync.onDisconnected())
        assertEquals(4_000L, sync.onDisconnected())
        sync.onConnected()
        assertEquals(1_000L, sync.onDisconnected())
        assertEquals(60_000L, FeedSync.defaultBackoffMs(10))
    }

    // ---- SessionCookies ----

    @Test
    fun `Set-Cookie 解析属性与 auth 判定`() {
        val c = checkNotNull(SessionCookies.parseSetCookie("dsh-auth-abc=v1.xxx; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict"))
        assertEquals("dsh-auth-abc", c.name)
        assertEquals("v1.xxx", c.value)
        assertEquals("2592000", c.attrs["max-age"])
        assertTrue(SessionCookies.isAuthCookie(c.name))
        assertTrue(!SessionCookies.isAuthCookie("other"))
    }

    @Test
    fun `请求头形态解析与序列化往返（迁移输入）`() {
        val header = "unrelated=1; dsh-auth-xyz=v1.abc; another=2; dsh-auth-broken"
        val auth = SessionCookies.authCookiesOf(SessionCookies.parseHeader(header))
        assertEquals(1, auth.size)
        assertEquals("dsh-auth-xyz", auth[0].name)
        val restored = SessionCookies.deserialize(SessionCookies.serialize(auth))
        assertEquals(auth, restored)
        assertTrue(SessionCookies.deserialize("garbage\n\ndsh-auth-x\t").isEmpty())
    }
}
