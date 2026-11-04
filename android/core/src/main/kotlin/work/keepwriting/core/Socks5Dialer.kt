package work.keepwriting.core

import java.io.IOException
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 自实现 SOCKS5 客户端拨号（libtailscale-spike 2026-09-24 抓包实证的 libcore 平台
 * 事实，embedded-client 起为正式承载）：Android 的 SocksSocketImpl 走用户名/口令
 * 认证时不查 java.net.Authenticator，直接发 System.getProperty("user.name") + 空
 * 口令（线上帧 01 04 "root" 00），对 tsnet 回环代理（Username "tsnet" + 随机口令）
 * 必然认证失败。curl 以同凭据拨同一端口成功，故按 RFC 1928/1929 自实现，认证帧带
 * 真实口令。
 *
 * 域名用 ADDRTYPE=0x03 交给代理侧解析——ts.net 名走内嵌节点的 MagicDNS，这是回环
 * 承载形态的关键一步（平台栈自己做系统 DNS 时对 ts.net 名直接 UnknownHostException，
 * 真机与模拟器同形实测）。
 *
 * 与 android/spike 的同名文件是同一份实证结论的两处载体：spike 是冻结的取证现场，
 * 这里是生产路径（loopback 模块，随协议级回归入闸）；修正平台事实时两处都要动，
 * 取证文档是共同上游。
 */
class Socks5Dialer(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val user: String,
    private val password: String,
    private val connectTimeoutMs: Int = 15_000,
) {
    /** 建立经 SOCKS5 代理到达 target 的 TCP 连接（阻塞）。 */
    fun connect(targetHost: String, targetPort: Int): Socket {
        val s = Socket()
        try {
            s.connect(java.net.InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs)
            val out = s.getOutputStream()
            val inp = s.getInputStream()

            // 问候：提供 no-auth 与 username/password 两法
            out.write(byteArrayOf(5, 2, 0, 2)); out.flush()
            val ver = inp.read(); val method = inp.read()
            if (ver != 5) throw IOException("socks5: bad greeting version $ver")
            when (method) {
                0x02 -> { // RFC 1929
                    val ub = user.toByteArray(Charsets.US_ASCII)
                    val pb = password.toByteArray(Charsets.US_ASCII)
                    if (ub.size > 255 || pb.size > 255) throw IOException("socks5: credential too long")
                    out.write(byteArrayOf(1, ub.size.toByte())); out.write(ub)
                    out.write(pb.size); out.write(pb); out.flush()
                    if (inp.read() != 1) throw IOException("socks5: bad auth version")
                    val status = inp.read()
                    if (status != 0) throw IOException("socks5: auth failed status=$status")
                }
                0x00 -> { /* 代理未要求认证（不应出现于 tsnet 回环，容忍） */ }
                else -> throw IOException("socks5: no acceptable auth method ($method)")
            }

            // CONNECT：域名形态，代理侧解析（ts.net → 内嵌节点 MagicDNS）
            val hb = targetHost.toByteArray(Charsets.US_ASCII)
            if (hb.size > 255) throw IOException("socks5: host too long")
            out.write(byteArrayOf(5, 1, 0, 3, hb.size.toByte())); out.write(hb)
            out.write(byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte())); out.flush()

            val rv = inp.read(); val rep = inp.read()
            if (rv != 5) throw IOException("socks5: bad reply version $rv")
            if (rep != 0) throw IOException("socks5: connect rejected rep=$rep")
            inp.read() // RSV
            when (val atyp = inp.read()) {
                1 -> skip(inp, 4)
                3 -> skip(inp, 1 + inp.read())
                4 -> skip(inp, 16)
                else -> throw IOException("socks5: bad atyp $atyp")
            }
            skip(inp, 2) // BND.PORT
            return s
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun skip(inp: java.io.InputStream, n: Int) {
        var left = n
        val buf = ByteArray(64)
        while (left > 0) {
            val r = inp.read(buf, 0, minOf(left, buf.size))
            if (r < 0) throw IOException("socks5: eof in reply")
            left -= r
        }
    }
}
