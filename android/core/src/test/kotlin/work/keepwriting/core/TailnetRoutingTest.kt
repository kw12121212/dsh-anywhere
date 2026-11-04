package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 分流与回环判定的表驱动测试（边界地址与主机名形态）。 */
class TailnetRoutingTest {
    @Test
    fun tailnetTargetTable() {
        // CGNAT 边界：100.63.x 不属于、100.64.0.0 属于、100.127.255.255 属于、100.128.0.0 不属于。
        val tailnet = listOf(
            "100.64.0.0", "100.64.0.1", "100.100.1.2", "100.127.255.255",
            "fd7a:115c:a1e0::1", "fd7a:115c:a1e0:dead:beef::1",
            "gamepc.ts.net", "GAMEPC.tail-scale.ts.net",
            "gamepc", "GAMEPC", "GamePc", "gamepc.", "GAMEPC.",
        )
        val notTailnet = listOf(
            // 回环不是 tailnet 目标（localhost 是无点短名，需显式排除）。
            "localhost", "LocalHost.",
            "100.63.255.255", "100.128.0.0", "100.0.0.1", "10.0.2.2", "192.168.1.1",
            "fe80::1", "fd7b::1", "fd7a:115c:a1f0::1",
            "example.org", "sub.example.org", "example.ts.network", "ts.net", "a.b.ts.net.example.com",
            "", "  ",
        )
        for (h in tailnet) assertTrue(TailnetRouting.isTailnetTarget(h), "应判 tailnet：$h")
        for (h in notTailnet) assertFalse(TailnetRouting.isTailnetTarget(h), "应判非 tailnet：$h")
        // a.b.ts.net.example.com 是多标签公网域名（不以 .ts.net 结尾）——修正：endsWith 判定
        // "ts.net.example.com" 结尾不含 .ts.net 结束形态，确认 assertFalse 命中。
    }

    @Test
    fun loopbackTargetTable() {
        val loopback = listOf(
            "127.0.0.1", "127.0.0.2", "127.255.255.255",
            "localhost", "LOCALHOST", "x.localhost", "LocalHost.",
            "::1", "[::1]", "0:0:0:0:0:0:0:1", "0.0.0.0", "::",
        )
        val notLoopback = listOf("10.0.2.2", "100.64.0.1", "gamepc", "example.org", "128.0.0.1")
        for (h in loopback) assertTrue(TailnetRouting.isLoopbackTarget(h), "应判回环：$h")
        for (h in notLoopback) assertFalse(TailnetRouting.isLoopbackTarget(h), "应判非回环：$h")
    }

    @Test
    fun splitDialRoutesByTarget() {
        var tailnetDialed = ""
        var directDialed = ""
        val dial = TailnetRouting.splitDial(
            tailnet = { h, _ -> tailnetDialed = h; java.net.Socket() },
            direct = { h, _ -> directDialed = h; java.net.Socket() },
        )
        dial("gamepc", 13080)
        assertEquals("gamepc", tailnetDialed)
        dial("example.org", 443)
        assertEquals("example.org", directDialed)
        dial("100.64.10.20", 22)
        assertEquals("100.64.10.20", tailnetDialed)
    }
}
