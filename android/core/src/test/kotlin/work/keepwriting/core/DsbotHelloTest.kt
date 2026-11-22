package work.keepwriting.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * hello 握手与能力位（dsbot-baseline-0310，tech/dsbot-api 的 hello-capabilities）：
 * 夹具来自 DS-Bot 0.3.10 真实应答的脱敏副本（FINDINGS.md 第 12 节）。
 */
class DsbotHelloTest {
    private fun fixture(name: String): String =
        checkNotNull(DsbotHelloTest::class.java.getResource("/dsbot/$name")).readText(Charsets.UTF_8)

    private fun result(name: String): DsbotApi.Result = DsbotApi.parseResponse(200, fixture(name))

    @Test
    fun `请求只提供客户端支持的 api 版本`() {
        assertEquals("""{"endpoint":"hello","payload":{"api":[1]}}""", DsbotApi.buildRequest("hello", DsbotHello.REQUEST_PAYLOAD))
    }

    @Test
    fun `hello 夹具解出 api 能力名单与 notices 游标`() {
        val ok = result("hello.json") as DsbotApi.Result.Ok
        val hello = assertNotNull(DsbotHello.decode(ok.value))
        assertEquals(1, hello.api)
        assertEquals("0.3.10", hello.pluginVersion)
        assertNotNull(hello.boot)
        assertNotNull(hello.seq)
        assertTrue("notices" in hello.capabilities && "send-images" in hello.capabilities)
        assertTrue("file-download-chunks" in hello.capabilities)
    }

    @Test
    fun `声明的能力可用而未声明的能力不可用`() {
        val caps = Capabilities.from(result("hello.json"))
        assertEquals(Capabilities.Mode.DECLARED, caps.mode)
        assertTrue(caps.has("notices"))
        assertTrue(caps.has("markdown-image"))
        assertFalse(caps.has("spike-undeclared-capability"))
        assertFalse(caps.versionMismatch)
        assertNotNull(caps.boot)
    }

    @Test
    fun `旧版远端没有 hello 时按 LEGACY 隐藏全部新能力`() {
        // 0.3.6 对未知端点回 bot/not-found（0.3.10 实测同一分支的应答形态）
        val caps = Capabilities.from(result("error-unknown-endpoint.json"))
        assertEquals(Capabilities.LEGACY, caps)
        assertFalse(caps.has("notices"))
        assertFalse(caps.has("state"))
        assertFalse(caps.versionMismatch, "LEGACY 只隐藏新能力，版本提示由 update-check 比对负责")
    }

    @Test
    fun `网络失败与鉴权失败都不当作已声明`() {
        assertEquals(Capabilities.LEGACY, Capabilities.from(DsbotApi.Result.Failure(null, DsbotApi.Reason.NETWORK)))
        assertEquals(Capabilities.LEGACY, Capabilities.from(DsbotApi.Result.Failure(401, DsbotApi.Reason.AUTH_EXPIRED)))
    }

    @Test
    fun `协商不出共同 api 版本时判为不兼容并提示版本不一致`() {
        val caps = Capabilities.from(result("hello-api-unsupported.json"))
        assertEquals(Capabilities.Mode.INCOMPATIBLE, caps.mode)
        assertTrue(caps.versionMismatch)
        assertFalse(caps.has("notices"), "名单虽在，api 不兼容时不按名单开放")
    }

    @Test
    fun `远端 api 高于客户端支持版本时判为不兼容`() {
        val value = checkNotNull(Json.parse("""{"api":2,"capabilities":["notices"],"boot":"b","seq":0}"""))
        val caps = Capabilities.from(DsbotApi.Result.Ok(value))
        assertEquals(Capabilities.Mode.INCOMPATIBLE, caps.mode)
        assertFalse(caps.has("notices"))
    }

    @Test
    fun `畸形应答降级为 LEGACY 不抛错`() {
        for (raw in listOf("null", "[]", "\"hello\"", """{"api":1}""", """{"api":1,"capabilities":"notices"}""")) {
            val value = checkNotNull(Json.parse(raw))
            assertNull(DsbotHello.decode(value), raw)
            assertEquals(Capabilities.LEGACY, Capabilities.from(DsbotApi.Result.Ok(value)), raw)
        }
    }

    @Test
    fun `未知字段与非字符串能力名被忽略`() {
        val value = checkNotNull(Json.parse("""{"api":1,"capabilities":["notices",3,null,"feed"],"future":{"x":1},"seq":"bad"}"""))
        val hello = assertNotNull(DsbotHello.decode(value))
        assertEquals(setOf("notices", "feed"), hello.capabilities)
        assertNull(hello.seq)
        assertNull(hello.boot)
    }

    @Test
    fun `feed hello 帧解出 revision api 与游标`() {
        val frame = fixture("feed-stream-0310.sse").lineSequence()
            .dropWhile { it != "event: hello" }.drop(1).first().removePrefix("data: ")
        val hello = assertNotNull(DsbotHello.decodeFeedHello(frame))
        assertNotNull(hello.revision)
        assertEquals(1, hello.api)
        assertNotNull(hello.boot)
        assertNotNull(hello.seq)
    }

    @Test
    fun `0_3_6 的 feed hello 只有 revision`() {
        val hello = assertNotNull(DsbotHello.decodeFeedHello("""{"revision":1694}"""))
        assertEquals(DsbotHello.FeedHello("1694", null, null, null), hello)
        assertNull(DsbotHello.decodeFeedHello("not json"))
    }

    @Test
    fun `FeedSync 把 feed hello 转交监听者且 revision 语义不变`() {
        val hellos = mutableListOf<DsbotHello.FeedHello>()
        val fetches = mutableListOf<String?>()
        val sync = FeedSync(object : FeedSync.Listener {
            override fun fetchState(localRevision: String?, liveKey: String?) { fetches.add(localRevision) }
            override fun onFeedHello(hello: DsbotHello.FeedHello) { hellos.add(hello) }
        })
        sync.onEvent("hello", """{"revision":4092,"api":1,"boot":"b1","seq":4}""")
        assertEquals(listOf(DsbotHello.FeedHello("4092", 1, "b1", 4)), hellos)
        assertEquals(listOf<String?>(null), fetches, "本地无 revision → 全量取一次")
        sync.onEvent("revision", """{"revision":4093}""")
        assertEquals(1, hellos.size, "只有 hello 帧转交")
    }
    @Test
    fun `缺 api 字段视同协商失败`() {
        val value = checkNotNull(Json.parse("""{"capabilities":["notices"],"boot":"b","seq":0}"""))
        assertEquals(Capabilities.Mode.INCOMPATIBLE, Capabilities.from(DsbotApi.Result.Ok(value)).mode)
    }

    // ---- 重读判定（feed 首帧 hello）----

    private fun declared(boot: String = "b1"): Capabilities = Capabilities.from(
        DsbotApi.Result.Ok(checkNotNull(Json.parse("""{"api":1,"capabilities":["notices"],"boot":"$boot","seq":0}"""))),
    )

    @Test
    fun `同一 boot 的重连不重读`() {
        assertFalse(declared("b1").shouldReread(DsbotHello.FeedHello("1", 1, "b1", 9)))
    }

    @Test
    fun `boot 变化即服务重启或换插件时重读`() {
        assertTrue(declared("b1").shouldReread(DsbotHello.FeedHello("1", 1, "b2", 0)))
        // 首次读取失败（LEGACY、无 boot）而 feed 带 boot：说明远端支持握手，补读一次
        assertTrue(Capabilities.LEGACY.shouldReread(DsbotHello.FeedHello("1", 1, "b2", 0)))
    }

    @Test
    fun `远端退回不支持握手的版本时作废已声明的能力`() {
        // 已 DECLARED，重启后 feed hello 不再带 boot（0.3.6 形态）→ 必须重读，不能保留过期能力
        assertTrue(declared().shouldReread(DsbotHello.FeedHello("1694", null, null, null)))
    }

    @Test
    fun `旧版远端的 feed hello 不触发重读`() {
        assertFalse(Capabilities.LEGACY.shouldReread(DsbotHello.FeedHello("1694", null, null, null)))
    }

    // ---- 版本提示条判定 ----

    private val baseline = DsbotVersions.Remote(DsbotVersions.BASELINE_DS_BOT, DsbotVersions.BASELINE_DSH)

    @Test
    fun `基准版本且握手正常不显示提示条`() {
        assertNull(DsbotVersions.banner(baseline, declared()))
    }

    @Test
    fun `旧版远端显示提示条且带远端版本`() {
        assertEquals("0.3.6" to "0.2.2-rc.3", DsbotVersions.banner(DsbotVersions.Remote("0.3.6", "0.2.2-rc.3"), Capabilities.LEGACY))
        assertEquals("0.4.0" to "0.2.2-rc.3", DsbotVersions.banner(DsbotVersions.Remote("0.4.0", "0.2.2-rc.3"), declared()))
    }

    @Test
    fun `读不到版本且握手正常或旧版时不显示提示条`() {
        assertNull(DsbotVersions.banner(null, declared()))
        assertNull(DsbotVersions.banner(null, Capabilities.LEGACY))
        assertNull(DsbotVersions.banner(DsbotVersions.Remote(null, "0.2.2-rc.3"), Capabilities.LEGACY))
    }

    @Test
    fun `api 不兼容时即使版本一致或读不到版本也提示`() {
        val incompatible = Capabilities.from(result("hello-api-unsupported.json"))
        assertEquals(DsbotVersions.BASELINE_DS_BOT to DsbotVersions.BASELINE_DSH, DsbotVersions.banner(baseline, incompatible))
        assertEquals("?" to "?", DsbotVersions.banner(null, incompatible))
    }
}
