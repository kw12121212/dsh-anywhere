package work.keepwriting.core.spike

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * spike 专用极简 HTTP/1.1 客户端（dsbot-api-spike 的取证工具）：复刻 DialProbe
 * 的手写请求风格——经注入的拨号函数建 TCP（生产为 TailnetRouting.splitDial，spike
 * 可注入 Socks5Dialer 或直连），https 在同一套接字上分层 TLS（SNI + 主机名校验），
 * 请求与响应都走这一个套接字。与生产类不同的是：支持 POST、保留全部响应头（含
 * 重复的 Set-Cookie）、按 content-length/chunked/EOF 三种形态读正文，并为
 * /api/bot/feed 提供流式 SSE 读取。
 */
class SpikeHttp(
    private val dial: (String, Int) -> Socket,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val tls: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
) {
    class Response(
        val status: Int,
        val reason: String,
        /** 全部响应头，保留重复出现（如多个 Set-Cookie），键为收到原文。 */
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? =
            headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

        fun bodyText(): String = String(body, Charsets.UTF_8)
    }

    /** 单次请求：不跟随重定向（由调用方决定）、不带 cookie 之外的任何状态。 */
    fun request(
        method: String,
        url: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
    ): Response {
        val u = URI(url)
        val host = u.host ?: throw IllegalArgumentException("URL 缺主机: ${Sanitize.url(url)}")
        val port = if (u.port > 0) u.port else if (u.scheme?.lowercase() == "https") 443 else 80
        val path = buildString {
            append(u.rawPath?.ifEmpty { null } ?: "/")
            if (!u.rawQuery.isNullOrEmpty()) append('?').append(u.rawQuery)
        }
        val sock = open(u, host, port)
        sock.use { s ->
            val hostHeader = host + if (port != 80 && port != 443) ":$port" else ""
            val out = s.getOutputStream()
            val sb = StringBuilder("$method $path HTTP/1.1\r\n")
            // 调用方显式提供 Host（错 Host 取证）时不再写默认头：重复 Host 本身就
            // 会被对端按 RFC 7230 拒绝，混入会污染取证归因。
            if (headers.none { it.first.equals("Host", ignoreCase = true) }) {
                sb.append("Host: $hostHeader\r\n")
            }
            for ((k, v) in headers) sb.append("$k: $v\r\n")
            if (body != null) sb.append("Content-Length: ${body.size}\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) out.write(body)
            out.flush()
            return readResponse(s.getInputStream())
        }
    }

    /** SSE 帧读取：订阅 [url]，把每个 (event, data) 回调给 [onFrame]，直到超时或
     *  [stop] 返回 true。返回收到的帧数。注释行（keepalive）被跳过。 */
    fun feed(
        url: String,
        headers: List<Pair<String, String>>,
        stop: () -> Boolean,
        onFrame: (event: String, data: String) -> Unit,
    ): Int {
        val u = URI(url)
        val host = u.host ?: throw IllegalArgumentException("URL 缺主机")
        val port = if (u.port > 0) u.port else 443
        val sock = open(u, host, port)
        sock.use { s ->
            val hostHeader = host + if (port != 80 && port != 443) ":$port" else ""
            val out = s.getOutputStream()
            val sb = StringBuilder("GET /api/bot/feed HTTP/1.1\r\n")
            sb.append("Host: $hostHeader\r\n")
            sb.append("Accept: text/event-stream\r\n")
            for ((k, v) in headers) sb.append("$k: $v\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val input = s.getInputStream()
            val statusLine = readLine(input) ?: throw IOException2("feed: 无状态行")
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw IOException2("feed: 状态行异常 ${Sanitize.plain(statusLine, "status")}")
            if (status != 200) throw IOException2("feed: HTTP $status")

            var chunked = false
            while (true) {
                val line = readLine(input) ?: throw IOException2("feed: 头部中断")
                if (line.isEmpty()) break
                val k = line.substringBefore(':').trim().lowercase()
                val v = line.substringAfter(':', "").trim()
                if (k == "transfer-encoding" && v.lowercase().contains("chunked")) chunked = true
            }
            val source: InputStream = if (chunked) ChunkedInput(input) else input
            var event = "message"
            val data = StringBuilder()
            var frames = 0
            while (!stop()) {
                val line = try {
                    readLine(source, idleLimit = readTimeoutMs)
                } catch (e: IOException2) {
                    break // 读超时或连接结束：订阅窗口关闭
                } ?: break
                when {
                    line.startsWith(":") -> {} // keepalive 注释
                    line.startsWith("event:") -> event = line.substringAfter(':').trim()
                    line.startsWith("data:") -> data.append(line.substringAfter(':').trim()).append('\n')
                    line.isEmpty() && data.isNotEmpty() -> {
                        onFrame(event, data.toString().trimEnd('\n'))
                        frames++
                        event = "message"
                        data.setLength(0)
                    }
                }
            }
            return frames
        }
    }

    // ---- 底层读写 ----

    private fun open(u: URI, host: String, port: Int): Socket {
        val raw = dial(host, port)
        try {
            raw.soTimeout = readTimeoutMs
        } catch (e: Exception) {
            try { raw.close() } catch (_: Exception) {}
            throw e
        }
        if (u.scheme?.lowercase() != "https") return raw
        return try {
            (tls.createSocket(raw, host, port, true) as SSLSocket).also { t ->
                t.sslParameters = t.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                t.startHandshake()
            }
        } catch (e: Exception) {
            try { raw.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun readResponse(input: InputStream): Response {
        var statusLine = readLine(input) ?: throw IOException2("无状态行")
        // 容忍少量前导垃圾行（与 DialProbe 相同策略）
        var attempts = 0
        while (!statusLine.startsWith("HTTP/") && attempts < 4) {
            statusLine = readLine(input) ?: throw IOException2("无状态行")
            attempts++
        }
        if (!statusLine.startsWith("HTTP/")) throw IOException2("状态行非 HTTP: ${Sanitize.plain(statusLine, "status")}")
        val parts = statusLine.split(' ', limit = 3)
        val status = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException2("状态码异常: ${Sanitize.plain(statusLine, "status")}")
        val reason = parts.getOrElse(2) { "" }

        val headers = mutableListOf<Pair<String, String>>()
        var contentLength = -1
        var chunked = false
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim()
            headers.add(k to v)
            val kl = k.lowercase()
            if (kl == "content-length") contentLength = v.toIntOrNull() ?: -1
            if (kl == "transfer-encoding" && v.lowercase().contains("chunked")) chunked = true
        }

        val body: ByteArray = when {
            status == 204 || status == 304 || (status in 100..199) -> ByteArray(0)
            chunked -> ChunkedInput(input).use { it.readBytes() }
            contentLength >= 0 -> {
                val buf = ByteArray(contentLength)
                var off = 0
                while (off < buf.size) {
                    val n = input.read(buf, off, buf.size - off)
                    if (n < 0) break
                    off += n
                }
                buf.copyOf(off)
            }
            else -> input.use { it.readBytes() } // Connection: close 读到 EOF
        }
        return Response(status, reason, headers, body)
    }

    /** 读一行（\r\n 或 \n 结尾）；流结束返回 null。 */
    private fun readLine(input: InputStream, idleLimit: Int = readTimeoutMs): String? {
        val sb = StringBuilder()
        var idle = 0
        while (true) {
            val c = try {
                input.read()
            } catch (e: java.io.InterruptedIOException) {
                idle += readTimeoutMs
                if (idle >= idleLimit) throw IOException2("读超时")
                continue
            }
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 1 shl 20) throw IOException2("行超长")
        }
    }

    /** chunked 编码解码（feed 流式读也用它包一层）。 */
    private class ChunkedInput(private val raw: InputStream) : InputStream() {
        private var remaining = 0
        private var done = false
        private var readBody = false

        override fun read(): Int {
            if (done) return -1
            if (remaining == 0) {
                if (readBody) consumeLine() // 上一块尾 CRLF
                val sizeLine = readRawLine() ?: throw IOException2("chunked: 尺寸行中断")
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                    ?: throw IOException2("chunked: 尺寸行异常")
                if (size == 0) {
                    done = true
                    // 尾部 trailer 直到空行
                    while (true) {
                        val t = readRawLine() ?: break
                        if (t.isEmpty()) break
                    }
                    return -1
                }
                remaining = size
                readBody = true
            }
            val b = raw.read()
            if (b < 0) throw IOException2("chunked: 流中断")
            remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            var i = 0
            while (i < len) {
                val c = read()
                if (c < 0) break
                b[off + i] = c.toByte()
                i++
            }
            return if (i == 0 && len > 0) -1 else i
        }

        fun readBytes(): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        private fun consumeLine() {
            // 块尾 CRLF：读两个字节（\r\n）或一行
            readRawLine()
        }

        private fun readRawLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val c = raw.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                if (c != '\r'.code) sb.append(c.toChar())
                if (sb.length > 65536) throw IOException2("chunked: 行超长")
            }
        }
    }
}

/** 内部用受检异常变体：避免与 java.io.IOException 的 SDK 差异纠缠。 */
class IOException2(message: String) : Exception(message)
