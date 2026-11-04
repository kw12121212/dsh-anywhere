package work.keepwriting.core

import java.io.InputStream
import java.net.Socket
import java.net.URI

/**
 * 经分流拨号的探测（android-proxy-routing）：连接探测与会话失效探测统一走与
 * WebView 相同的网络路径（tailnet 目标经内嵌节点、其余直连），在 socket 上手写
 * HTTP/1.1 请求。判定语义与前序直连实现完全一致：任何响应状态码即可达；401 且
 * 正文为 dsh 固定文本才会话失效。
 */
object DialProbe {
    /** 拨号器：host/port → 已连接 socket（生产为 TailnetRouting.splitDial）。 */
    fun interface Dial {
        fun connect(host: String, port: Int): Socket
    }

    sealed interface ProbeResult {
        data object Reachable : ProbeResult
        data class Unreachable(val reason: ProbeFailure.Reason) : ProbeResult
    }

    /** 可达性探测：手写 GET，收到响应状态行即视为可达，不读正文。 */
    fun probe(url: String, dial: Dial, timeoutMs: Int = 10_000): ProbeResult {
        return try {
            val u = URI(url)
            val host = u.host ?: return ProbeResult.Unreachable(ProbeFailure.Reason.OTHER)
            val port = if (u.port > 0) u.port else if (u.scheme?.lowercase() == "https") 443 else 80
            dial.connect(host, port).use { sock ->
                sock.soTimeout = timeoutMs
                writeRequest(sock, host, port, u.rawPath?.ifEmpty { null } ?: "/", cookie = null, httpsTls = isHttps(u))
                // 目标接受 TCP 后不回任何字节即关闭（EOF）：与桌面 ERR_EMPTY_RESPONSE
                // 同判——无响应即不可达。
                if (readStatusLine(sock.getInputStream()) == null) {
                    return ProbeResult.Unreachable(ProbeFailure.Reason.OTHER)
                }
                ProbeResult.Reachable
            }
        } catch (e: Exception) {
            ProbeResult.Unreachable(ProbeFailure.classify(e))
        }
    }

    /** 会话失效确认：带 cookie 探测干净地址，401 且正文为 EXPIRED_MARKER 才判定失效。 */
    fun isSessionExpired(url: String, cookieHeader: String?, dial: Dial, timeoutMs: Int = 10_000): Boolean {
        return try {
            val u = URI(url)
            val host = u.host ?: return false
            val port = if (u.port > 0) u.port else if (u.scheme?.lowercase() == "https") 443 else 80
            dial.connect(host, port).use { sock ->
                sock.soTimeout = timeoutMs
                writeRequest(sock, host, port, u.rawPath?.ifEmpty { null } ?: "/", cookieHeader, isHttps(u))
                val input = sock.getInputStream()
                val statusLine = readStatusLine(input) ?: return false
                val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return false
                if (status != 401) return false
                var contentType: String? = null
                var contentLength = -1
                var chunked = false
                while (true) {
                    val line = readHeaderLine(input) ?: break
                    if (line.isEmpty()) break
                    val k = line.substringBefore(':').trim().lowercase()
                    val v = line.substringAfter(':', "").trim()
                    when (k) {
                        "content-type" -> contentType = v
                        "content-length" -> contentLength = v.toIntOrNull() ?: -1
                        "transfer-encoding" -> if (v.lowercase().contains("chunked")) chunked = true
                    }
                }
                val body = readBody(input, contentLength, chunked, limit = SessionAuth.EXPIRED_MARKER.length + 64)
                SessionAuth.isSessionExpired(status, contentType, body)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isHttps(u: URI): Boolean = u.scheme?.lowercase() == "https"

    /** 手写请求：明文直发；https 在 socket 上分层 TLS（SNI+主机名校验）。 */
    private fun writeRequest(sock: Socket, host: String, port: Int, path: String, cookie: String?, httpsTls: Boolean) {
        val raw = sock
        val out = if (httpsTls) {
            val tls = layerTls(raw, host, port)
            tls.getOutputStream()
        } else {
            raw.getOutputStream()
        }
        val hostHeader = host + if (port != 80 && port != 443) ":$port" else ""
        val sb = StringBuilder("GET $path HTTP/1.1\r\n")
        sb.append("Host: $hostHeader\r\n")
        if (cookie != null) sb.append("Cookie: $cookie\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun layerTls(raw: Socket, host: String, port: Int): javax.net.ssl.SSLSocket {
        val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        return try {
            (factory.createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket).also { tls ->
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.startHandshake()
            }
        } catch (e: Exception) {
            try { raw.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun readStatusLine(input: InputStream): String? {
        var lines = 0
        while (lines < 4) {
            val line = readHeaderLine(input) ?: return null
            if (line.startsWith("HTTP/")) return line
            lines++
        }
        return null
    }

    private fun readHeaderLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 16 * 1024) return null
        }
    }

    private fun readBody(input: InputStream, contentLength: Int, chunked: Boolean, limit: Int): String {
        val out = StringBuilder()
        if (chunked) {
            while (out.length < limit) {
                val sizeLine = readHeaderLine(input) ?: break
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
                if (size == 0) break
                val chunk = ByteArray(minOf(size, limit - out.length))
                var off = 0
                while (off < chunk.size) {
                    val n = input.read(chunk, off, chunk.size - off)
                    if (n < 0) break
                    off += n
                }
                out.append(String(chunk, 0, off, Charsets.UTF_8))
                readHeaderLine(input) // 尾部 CRLF
            }
        } else if (contentLength > 0) {
            val buf = ByteArray(minOf(contentLength, limit))
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            out.append(String(buf, 0, off, Charsets.UTF_8))
        }
        return out.toString()
    }
}
