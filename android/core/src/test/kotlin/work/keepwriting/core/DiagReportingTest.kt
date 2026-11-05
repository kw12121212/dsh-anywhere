package work.keepwriting.core

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 诊断上报纯逻辑测试：脱敏（token 绝不出现）、截断（多字节安全）、JSON 编解码、
 * 端点校验、配置三态、有界队列（损坏容忍/超限丢最旧）、组包上限与同会话去重。
 */
class DiagReportingTest {
    private val tmp = Files.createTempDirectory("diag-core-test")

    @AfterTest
    fun tearDown() {
        DiagQueue.nowSource = System::currentTimeMillis
        tmp.toFile().deleteRecursively()
    }

    private fun report(
        id: String = "7d2a9c1e-0b1c-4d2e-9f01-00000000d5a1",
        message: String = "boom",
        stack: String = "at work.keepwriting.dshanywhere.Foo(Foo.kt:1)",
    ) = DiagReport(
        id = id, kind = DiagKinds.CRASH, occurredAt = "2026-11-05T13:00:00.000Z",
        appVersionName = "1.2.0", appVersionCode = 10200,
        device = "Pixel 7", osVersion = "Android 14", thread = "main",
        message = message, stackTrace = stack, breadcrumbs = listOf("a", "b"),
    )

    // ---- 脱敏 ----

    @Test
    fun sanitizeStripsTokenFromUrls() {
        val out = DiagText.sanitize("probe failed for https://h.example.test:18790/p?token=abc123&x=1 next")
        assertEquals("probe failed for https://h.example.test:18790/p?x=1 next", out)
        assertFalse(out.contains("abc123"))
    }

    @Test
    fun sanitizeNeverEchoesUnparseableUrl() {
        // 下划线主机名：URI 解析不抛错但 host 为 null（registry-based authority）→ 不回显原文
        val out = DiagText.sanitize("bad https://un_der.example.test/x?token=zz end")
        assertTrue(out.contains("<无法解析的地址>"))
        assertFalse(out.contains("un_der"))
        assertFalse(out.contains("zz"))
    }

    @Test
    fun sanitizeKillsBareTokenPairs() {
        val out = DiagText.sanitize("msg token=supersecret tail")
        assertEquals("msg token=*** tail", out)
        assertFalse(out.contains("supersecret"))
    }

    @Test
    fun truncateIsByteSafeForMultibyte() {
        val text = "密码密码密码" // 每字 3 字节
        assertEquals(6, DiagText.truncateBytes(text, 6).toByteArray(Charsets.UTF_8).size)
        assertEquals("密码", DiagText.truncateBytes(text, 7))
        assertEquals("", DiagText.truncateBytes("密", 0))
    }

    // ---- JSON 编解码 ----

    @Test
    fun encodeCarriesPlatformAndClosedFieldSet() {
        val json = DiagJson.encodeReport(report())
        assertTrue(json.contains("\"platform\":\"dsh_android\""))
        assertTrue(json.contains("\"occurred_at\""))
        assertTrue(json.contains("\"breadcrumbs\":[\"a\",\"b\"]"))
        val esc = DiagJson.encodeReport(report(message = "a\"b\\c\nd\u0001"))
        assertTrue(esc.contains("\\\""))
        assertTrue(esc.contains("\\\\"))
        assertTrue(esc.contains("\\n"))
        assertTrue(esc.contains("\\u0001"))
    }

    @Test
    fun decodeRoundtripsAndRejectsCorruptInput() {
        val r = report(message = "x\"y")
        assertEquals(r, DiagJson.decodeReport(DiagJson.encodeReport(r)))
        assertNull(DiagJson.decodeReport("{broken"))
        assertNull(DiagJson.decodeReport("""{"id":"x"}"""))
        assertNull(
            DiagJson.decodeReport(
                """{"id":"i","kind":"crash","occurred_at":"t","app_version_name":"v","app_version_code":1,""" +
                    """"device":"d","os_version":"o","thread":"t","message":"m","stack_trace":"s","breadcrumbs":[1]}"""
            )
        )
    }

    // ---- 端点校验 ----

    @Test
    fun endpointOnlyAcceptsHttps() {
        assertEquals("https://gamepc.example.ts.net:18790/", DiagEndpoint.validate("https://gamepc.example.ts.net:18790"))
        assertEquals("https://a.example.test/", DiagEndpoint.validate("https://a.example.test"))
        assertEquals("https://a.example.test/p", DiagEndpoint.validate("https://a.example.test/p#frag"))
        assertNull(DiagEndpoint.validate("http://a.example.test/"))
        assertNull(DiagEndpoint.validate("ftp://a/"))
        assertNull(DiagEndpoint.validate("not a url"))
        assertNull(DiagEndpoint.validate(""))
        // 带 query 的端点拒绝：拼 API 路径会畸形
        assertNull(DiagEndpoint.validate("https://a.example.test/p?q=1"))
    }

    @Test
    fun anrDetectionComparesTickObservations() {
        assertTrue(AnrDetection.isUnresponsive(tickObservedBeforePost = 100L, tickAtCheck = 100L))
        assertFalse(AnrDetection.isUnresponsive(tickObservedBeforePost = 100L, tickAtCheck = 250L))
    }

    @Test
    fun timestampIsRfc3339UtcMillis() {
        assertEquals("1970-01-01T00:00:00.000Z", diagTimestamp(0))
        assertEquals("1970-01-01T00:00:01.123Z", diagTimestamp(1123))
    }

    // ---- 端点配置 ----

    @Test
    fun endpointConfigThreeStatesAndAtomicSave() {
        val cfg = DiagEndpointConfig(tmp)
        assertEquals(DiagEndpointConfig.Load.Missing, cfg.load())
        val saved = cfg.save("https://gamepc.example.ts.net:18790")
        assertEquals("https://gamepc.example.ts.net:18790/", saved)
        assertEquals(DiagEndpointConfig.Load.Ok("https://gamepc.example.ts.net:18790/"), cfg.load())
        assertFailsWith<IllegalArgumentException> { cfg.save("http://bad/") }
        assertEquals(DiagEndpointConfig.Load.Ok("https://gamepc.example.ts.net:18790/"), cfg.load())
        cfg.clear()
        assertEquals(DiagEndpointConfig.Load.Missing, cfg.load())
        Files.writeString(tmp.resolve(DiagEndpointConfig.FILE_NAME), "{oops")
        assertEquals(DiagEndpointConfig.Load.Invalid, cfg.load())
        Files.writeString(tmp.resolve(DiagEndpointConfig.FILE_NAME), "{\"diagnosticsEndpoint\":\"http://x/\"}")
        assertEquals(DiagEndpointConfig.Load.Invalid, cfg.load())
    }

    // ---- 队列 ----

    @Test
    fun queueLoadsInTimeOrderAndRemovesSent() {
        val queue = DiagQueue(tmp, capacity = 5)
        DiagQueue.nowSource = { 1000L }
        queue.add(report(id = "00000000-0000-4000-8000-000000000001"))
        DiagQueue.nowSource = { 2000L }
        queue.add(report(id = "00000000-0000-4000-8000-000000000002"))
        val loaded = queue.load()
        assertEquals(2, loaded.size)
        assertTrue(loaded[0].name < loaded[1].name)
        assertEquals("00000000-0000-4000-8000-000000000001", loaded[0].report.id)
        queue.remove(listOf(loaded[0].name))
        assertEquals(1, queue.load().size)
        queue.clear()
        assertEquals(0, queue.load().size)
    }

    @Test
    fun queueDropsCorruptFilesAndOldestBeyondCap() {
        val queue = DiagQueue(tmp, capacity = 3)
        for (i in 1..3) queue.add(report(id = "00000000-0000-4000-8000-00000000000$i"))
        assertEquals(3, queue.size())
        queue.add(report(id = "00000000-0000-4000-8000-000000000004"))
        val loaded = queue.load()
        assertEquals(3, loaded.size)
        assertEquals("00000000-0000-4000-8000-000000000002", loaded[0].report.id)
        Files.writeString(tmp.resolve(loaded[0].name), "garbage")
        val after = queue.load()
        assertEquals(2, after.size)
        assertFalse(Files.exists(tmp.resolve(loaded[0].name)))
    }

    // ---- 组包与去重 ----

    @Test
    fun batchingRespectsCountCapAndKeepsOrder() {
        val items = (1..45).map { DiagQueue.Named("$it.json", report(id = "00000000-0000-4000-8000-" + "%012d".format(it))) }
        val batches = DiagBatching.batches(items)
        assertEquals(3, batches.size)
        assertTrue(batches.all { it.size <= 20 })
        assertEquals(items.map { it.name }, batches.flatten().map { it.name })
    }

    @Test
    fun errorDedupFiresOncePerKey() {
        val dedup = DiagErrorDedup()
        assertFalse(dedup.seen("load:refused"))
        assertTrue(dedup.seen("load:refused"))
        assertFalse(dedup.seen("tunnel:failed"))
        dedup.reset()
        assertFalse(dedup.seen("load:refused"))
    }
}
