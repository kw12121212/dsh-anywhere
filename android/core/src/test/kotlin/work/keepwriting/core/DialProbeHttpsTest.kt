package work.keepwriting.core

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DialProbe 对 https 地址的探测与会话判定（fix-android-https-probe）：本地起真实 TLS
 * 服务端（测试时用 JDK 自带 keytool 生成 SAN=dsh.test 的自签证书），客户端注入只信任
 * 该证书的 TLS 工厂；dial 一律连到本地端口，URL 主机名 dsh.test 用于 SNI 与主机名校验。
 */
class DialProbeHttpsTest {
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() {
        cleanups.forEach { runCatching(it) }
    }

    @Test
    fun httpsResponseMeansReachable() {
        val port = startTlsServer("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        val result = DialProbe.probe("https://dsh.test:$port/", dialTo(port), tls = TestTls.trustingFactory)
        assertEquals(DialProbe.ProbeResult.Reachable, result)
    }

    @Test
    fun httpsSessionExpiredMarkerMatched() {
        val marker = SessionAuth.EXPIRED_MARKER
        val port = startTlsServer(
            "HTTP/1.1 401 Unauthorized\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${marker.length}\r\nConnection: close\r\n\r\n$marker",
        )
        assertTrue(DialProbe.isSessionExpired("https://dsh.test:$port/", "session=s1", dialTo(port), tls = TestTls.trustingFactory))
    }

    @Test
    fun httpsOtherResponseIsNotSessionExpired() {
        val port = startTlsServer("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
        assertFalse(DialProbe.isSessionExpired("https://dsh.test:$port/", "session=s1", dialTo(port), tls = TestTls.trustingFactory))
    }

    @Test
    fun untrustedCertificateIsTlsFailure() {
        // 平台默认信任链不信任自签证书：证书校验保持开启，判为安全连接失败。
        val port = startTlsServer("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        val result = DialProbe.probe("https://dsh.test:$port/", dialTo(port))
        assertEquals(DialProbe.ProbeResult.Unreachable(ProbeFailure.Reason.TLS), result)
    }

    @Test
    fun hostnameMismatchIsTlsFailure() {
        // 证书只签给 dsh.test：主机名校验保持开启，其他主机名判为安全连接失败。
        val port = startTlsServer("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        val result = DialProbe.probe("https://other.test:$port/", dialTo(port), tls = TestTls.trustingFactory)
        assertEquals(DialProbe.ProbeResult.Unreachable(ProbeFailure.Reason.TLS), result)
    }

    private fun dialTo(port: Int) = DialProbe.Dial { _, _ -> Socket("127.0.0.1", port) }

    /** 起 TLS 服务端：读到请求头结束即写回固定响应。 */
    private fun startTlsServer(rawResponse: String): Int {
        val ss = TestTls.serverContext().serverSocketFactory
            .createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as javax.net.ssl.SSLServerSocket
        cleanups += { ss.close() }
        Thread {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Exception) { return@Thread }
                Thread {
                    client.use { c ->
                        try {
                            val input = c.getInputStream()
                            var window = "    "
                            while (true) {
                                val b = input.read()
                                if (b < 0) return@use
                                window = window.substring(1) + b.toChar()
                                if (window == "\r\n\r\n") break
                            }
                            c.getOutputStream().apply {
                                write(rawResponse.toByteArray(Charsets.ISO_8859_1)); flush()
                            }
                        } catch (_: Exception) {
                            // 客户端拒绝证书时握手失败：服务端侧忽略。
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return ss.localPort
    }

}
