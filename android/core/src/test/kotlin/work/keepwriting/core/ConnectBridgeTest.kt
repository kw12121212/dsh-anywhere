package work.keepwriting.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ConnectBridge / DialProbe / Socks5Dialer 的协议级测试：本地起真实 socket 服务
 * （假上游/假 SOCKS5），按字节断言门控行为与转发纪律（不依赖 Android 环境）。
 */
class ConnectBridgeTest {
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() {
        cleanups.forEach { it() }
    }

    /** 起一个假上游 HTTP 服务：返回固定状态行+正文（记录收到的请求行）。 */
    private fun fakeUpstream(statusLine: String = "HTTP/1.1 200 OK", body: String = "upstream-body"): Pair<Int, MutableList<String>> {
        val received = mutableListOf<String>()
        val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        cleanups += { ss.close() }
        Thread {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Exception) { return@Thread }
                Thread {
                    client.use { c ->
                        val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
                        val first = try { reader.readLine() } catch (_: Exception) { null }
                        if (first != null) synchronized(received) { received.add(first) }
                        // 读到空行（头结束）即响应。
                        while (true) {
                            try { if (reader.readLine().isNullOrEmpty()) break } catch (_: Exception) { break }
                        }
                        val out = c.getOutputStream()
                        out.write(("$statusLine\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray(Charsets.ISO_8859_1))
                        out.flush()
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()
        return ss.localPort to received
    }

    private fun bridge(dial: (String, Int) -> Socket, cleartext: (String) -> Boolean = { true }): ConnectBridge {
        val b = ConnectBridge(dial = dial, cleartextAllowed = cleartext, log = {})
        b.start()
        cleanups += { b.stop() }
        return b
    }

    private fun authHeader(b: ConnectBridge): String =
        "Basic " + Base64.getEncoder().encodeToString("${b.credentials.first}:${b.credentials.second}".toByteArray())

    private fun httpGet(port: Int, path: String, extraHeaders: Map<String, String> = emptyMap()): Pair<Int, String> {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10_000
            val out = s.getOutputStream()
            val sb = StringBuilder("GET $path HTTP/1.1\r\nHost: x\r\n")
            for ((k, v) in extraHeaders) sb.append("$k: $v\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val text = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            val status = Regex("HTTP/1\\.1 (\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: -1
            return status to text
        }
    }

    @Test
    fun noCredentialGets407AndDialsNothing() {
        var dialed = 0
        val b = bridge(dial = { _, _ -> dialed++; Socket() })
        val (status, _) = httpGet(b.port, "http://example.org/x")
        assertEquals(407, status)
        assertEquals(0, dialed, "无凭据不得拨号")
    }

    @Test
    fun wrongCredentialGets407() {
        val b = bridge(dial = { _, _ -> Socket() })
        val (status, _) = httpGet(b.port, "http://example.org/x", mapOf("Proxy-Authorization" to "Basic d3Jvbmc="))
        assertEquals(407, status)
    }

    @Test
    fun loopbackTargetRejectedWithoutDialing() {
        var dialed = 0
        val b = bridge(dial = { _, _ -> dialed++; Socket() })
        val (status, _) = httpGet(b.port, "http://127.0.0.1:9999/x", mapOf("Proxy-Authorization" to authHeader(b)))
        assertEquals(403, status)
        assertEquals(0, dialed, "回环目标不得拨号")
    }

    @Test
    fun cleartextToNonHarnessOriginRejected() {
        val (upPort, _) = fakeUpstream()
        var dialed = 0
        val b = bridge(dial = { _, _ -> dialed++; Socket("127.0.0.1", upPort) }, cleartext = { it.contains("harness.ok") })
        val (allowed, _) = httpGet(b.port, "http://harness.ok:13080/", mapOf("Proxy-Authorization" to authHeader(b)))
        val (denied, _) = httpGet(b.port, "http://other.example/x", mapOf("Proxy-Authorization" to authHeader(b)))
        assertEquals(200, allowed, "harness 源明文应转发")
        assertEquals(403, denied, "非 harness 源明文应拒绝")
        assertEquals(1, dialed, "只有 harness 源拨了一次")
    }

    @Test
    fun absoluteUriForwardedAsOriginForm() {
        val (upPort, received) = fakeUpstream()
        val b = bridge(dial = { _: String, _: Int -> Socket("127.0.0.1", upPort) })
        val (status, body) = httpGet(b.port, "http://upstream.test:1234/path?q=1", mapOf("Proxy-Authorization" to authHeader(b)))
        assertEquals(200, status)
        assertTrue(body.contains("upstream-body"))
        // 等假上游线程读到请求。
        var first: String? = null
        for (i in 1..50) {
            synchronized(received) { if (received.isNotEmpty()) first = received.first() }
            if (first != null) break
            Thread.sleep(100)
        }
        assertEquals("GET /path?q=1 HTTP/1.1", first, "绝对 URI 应改写为 origin-form 转发")
    }

    @Test
    fun connectTunnelPumpsBytes() {
        val (upPort, _) = fakeUpstream()
        val b = bridge(dial = { _, _ -> Socket("127.0.0.1", upPort) })
        Socket("127.0.0.1", b.port).use { s ->
            s.soTimeout = 10_000
            val out = s.getOutputStream()
            out.write("CONNECT upstream.test:443 HTTP/1.1\r\nHost: x\r\nProxy-Authorization: ${authHeader(b)}\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            val statusLine = reader.readLine()
            assertTrue(statusLine.startsWith("HTTP/1.1 200"), "CONNECT 应回 200，得到 $statusLine")
            reader.readLine() // 空行
            // 隧道透传：写字节（假上游按 HTTP 读）并读回响应。
            out.write("GET /tunnel HTTP/1.1\r\nHost: upstream.test\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val line = reader.readLine()
            assertTrue(line.startsWith("HTTP/1.1 200"), "隧道应透传上游响应，得到 $line")
        }
    }
}

/** DialProbe 的探测与会话判定（经注入 dial 到假上游）。 */
class DialProbeTest {
    @Test
    fun anyResponseMeansReachable() {
        val (port, _) = startFakeServer("HTTP/1.1 401 Unauthorized\r\nContent-Type: text/plain\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc")
        val result = DialProbe.probe("http://127.0.0.1:$port/", DialProbe.Dial { _, _ -> Socket("127.0.0.1", port) })
        assertTrue(result is DialProbe.ProbeResult.Reachable, "401 也是可达：$result")
    }

    @Test
    fun connectionRefusedClassified() {
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = closed.localPort
        closed.close()
        val result = DialProbe.probe("http://127.0.0.1:$port/", DialProbe.Dial { _, _ -> Socket("127.0.0.1", port) })
        assertTrue(result is DialProbe.ProbeResult.Unreachable, "应不可达：$result")
    }

    @Test
    fun sessionExpiredMarkerMatched() {
        val marker = SessionAuth.EXPIRED_MARKER
        val (port, _) = startFakeServer("HTTP/1.1 401 Unauthorized\r\nContent-Type: text/plain\r\nContent-Length: ${marker.length}\r\nConnection: close\r\n\r\n$marker")
        val dial = DialProbe.Dial { _, _ -> Socket("127.0.0.1", port) }
        assertTrue(DialProbe.isSessionExpired("http://127.0.0.1:$port/", "session=s1", dial))
        val (port2, _) = startFakeServer("HTTP/1.1 401 Unauthorized\r\nContent-Type: text/plain\r\nContent-Length: 6\r\nConnection: close\r\n\r\nnope!!")
        val dial2 = DialProbe.Dial { _, _ -> Socket("127.0.0.1", port2) }
        assertTrue(!DialProbe.isSessionExpired("http://127.0.0.1:$port2/", null, dial2))
    }

    private fun startFakeServer(rawResponse: String): Pair<Int, Unit> {
        val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Exception) { return@Thread }
                Thread {
                    client.use { c ->
                        // 读到头结束（\r\n\r\n）即响应。
                        var window = "    "
                        val buf = ByteArray(1)
                        while (true) {
                            val n = try { c.getInputStream().read(buf) } catch (_: Exception) { -1 }
                            if (n < 0) return@use
                            window = window.substring(1) + buf[0].toChar()
                            if (window == "\r\n\r\n") break
                        }
                        c.getOutputStream().apply {
                            write(rawResponse.toByteArray(Charsets.ISO_8859_1)); flush()
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { ss.close() } })
        return ss.localPort to Unit
    }
}
