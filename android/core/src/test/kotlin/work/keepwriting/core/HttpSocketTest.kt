package work.keepwriting.core

import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * HttpSocket 传输测试：本地真实服务端（明文 + TLS，TLS 复用 DialProbeHttpsTest 的
 * 自签证书模式——手写协议客户端的 https 分支必须有真实 TLS 服务端测试，
 * lesson socket-tls-tls-https-tls）。POST、重复 Set-Cookie、chunked、SSE 流式
 * 与读超时关窗都按对端可观察行为断言。
 */
class HttpSocketTest {
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() {
        cleanups.forEach { runCatching(it) }
    }

    @Test
    fun `POST 请求行与正文到达对端且响应解码 content-length`() {
        val seen = StringBuilder()
        val port = serve { client ->
            val head = readHead(client)
            seen.append(head)
            client.getOutputStream().write(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 13\r\nConnection: close\r\n\r\n{\"ok\":true}\r\n".toByteArray(),
            )
        }
        val res = HttpSocket(::dialLocal).request(
            "POST", "http://dsh.test:$port/api/bot",
            listOf("Content-Type" to "application/json"),
            """{"endpoint":"state"}""".toByteArray(),
        )
        assertEquals(200, res.status)
        assertEquals("""{"ok":true}""", res.bodyText().trimEnd('\r', '\n'))
        assertTrue(seen.startsWith("POST /api/bot HTTP/1.1\r\n"))
        assertTrue(seen.contains("Host: dsh.test:$port\r\n"))
        assertTrue(seen.contains("Content-Type: application/json\r\n"))
        assertTrue(seen.contains("Content-Length: 20\r\n"))
    }

    @Test
    fun `重复 Set-Cookie 头全部保留`() {
        val port = serve { client ->
            readHead(client)
            client.getOutputStream().write(
                "HTTP/1.1 200 OK\r\nSet-Cookie: a=1; Path=/\r\nSet-Cookie: dsh-auth-x=v1.y; HttpOnly\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
            )
        }
        val res = HttpSocket(::dialLocal).request("GET", "http://dsh.test:$port/")
        val cookies = res.headers.filter { it.first.equals("Set-Cookie", true) }
        assertEquals(2, cookies.size)
        assertEquals("dsh-auth-x=v1.y; HttpOnly", cookies[1].second)
    }

    @Test
    fun `chunked 正文解码与调用方 Host 替换`() {
        val seen = StringBuilder()
        val port = serve { client ->
            seen.append(readHead(client))
            val body = "0123456789"
            client.getOutputStream().write(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n5\r\n01234\r\n5\r\n56789\r\n0\r\n\r\n".toByteArray(),
            )
        }
        val res = HttpSocket(::dialLocal).request(
            "POST", "http://dsh.test:$port/api/bot",
            listOf("Host" to "spike-invalid.example", "Content-Type" to "application/json"),
            "{}".toByteArray(),
        )
        assertEquals(10, res.body.size)
        assertEquals("0123456789", res.bodyText())
        // 调用方提供 Host 时不再写默认头：对端只见到一个 Host
        assertEquals(1, Regex("(?m)^Host:").findAll(seen).count())
        assertTrue(seen.contains("Host: spike-invalid.example\r\n"))
    }

    @Test
    fun `feed 订阅收帧直到 stop`() {
        val port = serve { client ->
            readHead(client)
            val out = client.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
            fun sse(text: String) {
                val payload = text.toByteArray(Charsets.UTF_8)
                out.write(("%x\r\n".format(payload.size)).toByteArray())
                out.write(payload)
                out.write("\r\n".toByteArray())
                out.flush()
            }
            sse("event: hello\ndata: {\"revision\":7}\n\n")
            sse(": keepalive\n\n")
            sse("event: stream\ndata: {\"phase\":\"start\"}\n\n")
            // 保持连接开着，等客户端主动关
            Thread.sleep(5_000)
        }
        val frames = mutableListOf<Pair<String, String>>()
        val count = AtomicInteger(0)
        val n = HttpSocket(::dialLocal, readTimeoutMs = 8_000).feed(
            "http://dsh.test:$port/api/bot/feed",
            emptyList(),
            stop = { count.get() >= 2 },
        ) { e, d ->
            frames.add(e to d)
            count.incrementAndGet()
        }
        assertEquals(2, n)
        assertEquals("hello", frames[0].first)
        assertEquals("""{"revision":7}""", frames[0].second)
        assertEquals("stream", frames[1].first)
    }

    @Test
    fun `feed 多字节字符跨 chunk 边界不损坏`() {
        // 一个 chunk 的 payload 末尾是 3 字节 CJK 字符的前 2 字节，第 2 个 chunk 补齐
        // 剩余字节 + 帧尾——按字节块各自解码会得到 U+FFFD（评审 MUST FIX）。
        val full = "event: message\ndata: {\"text\":\"消息内容测试\"}\n\n".toByteArray(Charsets.UTF_8)
        val splitAt = full.size - 3 // 截在第 2 个 CJK 字符中间附近
        val port = serve { client ->
            readHead(client)
            val out = client.getOutputStream()
            fun chunk(bytes: ByteArray) {
                out.write(("%x\r\n".format(bytes.size)).toByteArray())
                out.write(bytes)
                out.write("\r\n".toByteArray())
                out.flush()
            }
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
            chunk(full.copyOfRange(0, splitAt))
            chunk(full.copyOfRange(splitAt, full.size))
            Thread.sleep(5_000)
        }
        val frames = mutableListOf<Pair<String, String>>()
        HttpSocket(::dialLocal, readTimeoutMs = 8_000).feed(
            "http://dsh.test:$port/api/bot/feed",
            emptyList(),
            stop = { frames.size >= 1 },
        ) { e, d -> frames.add(e to d) }
        assertEquals(1, frames.size)
        assertEquals("message", frames[0].first)
        assertEquals("""{"text":"消息内容测试"}""", frames[0].second)
    }

    @Test
    fun `feed 非 200 抛 FeedHttpException`() {
        val port = serve { client ->
            readHead(client)
            client.getOutputStream().write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            client.getInputStream().read()
        }
        assertFailsWith<FeedHttpException> {
            HttpSocket(::dialLocal).feed("http://dsh.test:$port/api/bot/feed", emptyList(), stop = { false }) { _, _ -> }
        }
    }

    @Test
    fun `TLS 分层请求与主机名校验`() {
        val port = serveTls { client ->
            readHead(client)
            client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
        }
        val res = HttpSocket({ _, _ -> dialTlsLocal(port) }, tls = TestTls.trustingFactory)
            .request("GET", "https://dsh.test:$port/")
        assertEquals(200, res.status)
        assertEquals("ok", res.bodyText())
        // 证书只签给 dsh.test：换主机名校验失败（lesson socket-tls-tls-https-tls）
        assertFailsWith<Exception> {
            HttpSocket({ _, _ -> dialTlsLocal(port) }, tls = TestTls.trustingFactory)
                .request("GET", "https://other.test:$port/")
        }
    }

    // ---- 本地服务端脚手架 ----

    private fun dialLocal(host: String, port: Int): Socket = Socket("127.0.0.1", port)

    private fun dialTlsLocal(port: Int): Socket = Socket("127.0.0.1", port)

    /** 明文服务端：每个连接交给 [handle]；读到 EOF 或关流即结束。 */
    private fun serve(handle: (Socket) -> Unit): Int {
        val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        cleanups += { ss.close() }
        Thread {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Exception) { return@Thread }
                Thread { client.use { runCatching { handle(it) } } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return ss.localPort
    }

    /** TLS 服务端（复用 DialProbeHttpsTest 的证书模式）。 */
    private fun serveTls(handle: (Socket) -> Unit): Int {
        val ss = TestTls.serverContext().serverSocketFactory
            .createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as javax.net.ssl.SSLServerSocket
        cleanups += { ss.close() }
        Thread {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Exception) { return@Thread }
                Thread { client.use { runCatching { handle(it) } } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return ss.localPort
    }

    private fun readHead(client: Socket): String {
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val sb = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            sb.append(line).append("\r\n")
            if (line.isEmpty()) break
        }
        return sb.toString()
    }
}
