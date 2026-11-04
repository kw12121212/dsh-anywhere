package work.keepwriting.core

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Socks5Dialer 的协议级测试：本地假 SOCKS5 服务按 RFC1928/1929 应答，
 * 断言认证帧与域名形态（ADDRTYPE=0x03 交代理侧解析）。
 */
class Socks5DialerTest {
    private val servers = mutableListOf<ServerSocket>()

    @AfterTest
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
    }

    /** 起假 SOCKS5：要求 userpass（user/pass），记录 CONNECT 的目标并回一个回环 socket。 */
    private fun fakeSocks5(user: String, pass: String, rejectAuth: Boolean = false): Triple<Int, String, Int> {
        val ss = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        servers += ss
        // 回给客户端的假目标 socket：本地再起一个服务端口。
        val target = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        servers += target
        Thread {
            target.use { t ->
                while (!t.isClosed) {
                    val c = try { t.accept() } catch (_: Exception) { return@Thread }
                    // 收数据即回显。
                    Thread {
                        c.use { cc ->
                            val buf = ByteArray(1024)
                            while (true) {
                                val n = try { cc.getInputStream().read(buf) } catch (_: Exception) { break }
                                if (n < 0) break
                                cc.getOutputStream().write(buf, 0, n)
                            }
                        }
                    }.apply { isDaemon = true }.start()
                }
            }
        }.apply { isDaemon = true }.start()

        var capturedHost = ""
        var capturedPort = 0
        Thread {
            ss.use { s ->
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (_: Exception) { return@Thread }
                    c.use { sock ->
                        val inp = sock.getInputStream()
                        val out = sock.getOutputStream()
                        // 问候：读全部 method 字节（客户端发 [no-auth, userpass]），
                        // 残留字节会污染后续认证帧的解析。
                        val ver = inp.read()
                        val nMethods = inp.read()
                        repeat(nMethods) { inp.read() }
                        out.write(byteArrayOf(5, 2)); out.flush()
                        // RFC1929 认证。
                        val aver = inp.read()
                        val ulen = inp.read()
                        val ub = ByteArray(ulen); readFully(inp, ub)
                        val plen = inp.read()
                        val pb = ByteArray(plen); readFully(inp, pb)
                        val okAuth = aver == 1 && String(ub) == user && String(pb) == pass && !rejectAuth
                        out.write(byteArrayOf(1, if (okAuth) 0 else 1)); out.flush()
                        if (!okAuth) return@use
                        // CONNECT：域名形态解析。
                        assertEquals(5, inp.read())
                        assertEquals(1, inp.read()) // REP=CONNECT
                        inp.read() // RSV
                        val atyp = inp.read()
                        assertEquals(3, atyp, "目标应为域名形态（ADDRTYPE=0x03）")
                        val hlen = inp.read()
                        val hb = ByteArray(hlen); readFully(inp, hb)
                        capturedHost = String(hb)
                        capturedPort = (inp.read() shl 8) or inp.read()
                        // 回复：BND.ADDR 必须是 4 字节二进制 IPv4（写成 9 字节字符串会让
                        // 客户端 skip(4) 后泄漏 5 字节进业务流——实测教训）。
                        out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1) + byteArrayOf(
                            (target.localPort shr 8).toByte(), target.localPort.toByte(),
                        ))
                        out.flush()
                        // 双向透传到假目标。
                        val t = Socket("127.0.0.1", target.localPort)
                        val up = Thread {
                            try {
                                val buf = ByteArray(1024)
                                while (true) {
                                    val n = t.getInputStream().read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n); out.flush()
                                }
                            } catch (_: Exception) {}
                        }.apply { isDaemon = true; start() }
                        try {
                            val buf = ByteArray(1024)
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                t.getOutputStream().write(buf, 0, n); t.getOutputStream().flush()
                            }
                        } catch (_: Exception) {}
                        up.join(2000)
                        runCatching { t.close() }
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        // 等捕获可见。
        return Triple(ss.localPort, "", 0)
    }

    private fun readFully(inp: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.IOException("eof")
            off += n
        }
    }

    @Test
    fun connectAuthenticatesAndForwardsDomain() {
        val fake = fakeSocks5("tsnet", "secret123")
        val dialer = Socks5Dialer("127.0.0.1", fake.first, "tsnet", "secret123")
        val sock = dialer.connect("gamepc.ts.net", 13080)
        sock.use {
            it.getOutputStream().apply {
                write("ping".toByteArray()); flush()
            }
            val buf = ByteArray(4)
            readFully(it.getInputStream(), buf)
            assertEquals("ping", String(buf), "假目标应回显（经 SOCKS5 透传）")
        }
    }

    @Test
    fun wrongPasswordRejected() {
        val fake = fakeSocks5("tsnet", "right")
        val dialer = Socks5Dialer("127.0.0.1", fake.first, "tsnet", "wrong")
        assertFailsWith<java.io.IOException>("socks5: auth failed") { dialer.connect("gamepc", 80) }
    }
}
