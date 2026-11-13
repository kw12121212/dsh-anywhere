package work.keepwriting.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 经注入拨号函数的极简 HTTP/1.1 传输（dsbot-api-client）：与 DialProbe 同一手写
 * 请求风格——https 在同一套接字上分层 TLS（SNI+主机名校验），请求与响应走同一个
 * 套接字。相比探测多了 POST、全部响应头（含重复 Set-Cookie）、content-length/
 * chunked/EOF 三种正文形态，以及 /api/bot/feed 的流式 SSE 订阅（帧解析复用
 * [SseParser]）。生产拨号是 TailnetRouting.splitDial（tailnet 目标经内嵌节点）；
 * 目标源限定与 cookie/Host 附加由调用方负责（tech/dsbot-api 的
 * auth-cookie-and-host 与 exact-path-construction）。
 */
class HttpSocket(
    private val dial: (String, Int) -> Socket,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val tls: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
) {
    class Response(
        val status: Int,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? =
            headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

        fun bodyText(): String = String(body, Charsets.UTF_8)
    }

    /** 单次请求：不跟随重定向、不带任何状态（重定向与 cookie 由调用方决定）。 */
    fun request(
        method: String,
        url: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
    ): Response {
        val u = URI(url)
        val host = u.host ?: throw IllegalArgumentException("URL 缺主机")
        val port = if (u.port > 0) u.port else if (u.scheme?.lowercase() == "https") 443 else 80
        val path = buildString {
            append(u.rawPath?.ifEmpty { null } ?: "/")
            if (!u.rawQuery.isNullOrEmpty()) append('?').append(u.rawQuery)
        }
        return open(u, host, port).use { s ->
            val hostHeader = host + if (port != 80 && port != 443) ":$port" else ""
            val out = s.getOutputStream()
            val sb = StringBuilder("$method $path HTTP/1.1\r\n")
            // 调用方显式提供 Host 时不再写默认头（重复 Host 本身就会被对端拒绝）。
            if (headers.none { it.first.equals("Host", ignoreCase = true) }) {
                sb.append("Host: $hostHeader\r\n")
            }
            for ((k, v) in headers) sb.append("$k: $v\r\n")
            if (body != null) sb.append("Content-Length: ${body.size}\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) out.write(body)
            out.flush()
            readResponse(s.getInputStream())
        }
    }

    /**
     * 订阅 SSE：GET [url] 并把每个 (event, data) 帧回调给 [onFrame]，直到 [stop]
     * 返回 true、读超时或服务端关流。返回收到的帧数；HTTP 非 200 抛
     * [FeedHttpException]。注释保活行由 [SseParser] 处理。
     */
    fun feed(
        url: String,
        headers: List<Pair<String, String>>,
        stop: () -> Boolean,
        onFrame: (event: String, data: String) -> Unit,
    ): Int {
        val u = URI(url)
        val host = u.host ?: throw IllegalArgumentException("URL 缺主机")
        val port = if (u.port > 0) u.port else if (u.scheme?.lowercase() == "https") 443 else 80
        val parser = SseParser()
        var frames = 0
        open(u, host, port).use { s ->
            val out = s.getOutputStream()
            val sb = StringBuilder("GET ${u.rawPath ?: "/"}${if (!u.rawQuery.isNullOrEmpty()) "?" + u.rawQuery else ""} HTTP/1.1\r\n")
            // 与 request() 相同的守卫：调用方显式提供 Host 时不写默认头（重复 Host 会被对端拒绝）。
            if (headers.none { it.first.equals("Host", ignoreCase = true) }) {
                sb.append("Host: ${host + if (port != 80 && port != 443) ":$port" else ""}\r\n")
            }
            sb.append("Accept: text/event-stream\r\n")
            for ((k, v) in headers) sb.append("$k: $v\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val input = s.getInputStream()
            val status = readStatusLine(input) ?: throw FeedHttpException("无状态行")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw FeedHttpException("状态行异常")
            if (code != 200) throw FeedHttpException("feed HTTP $code", code)
            var chunked = false
            while (true) {
                val line = readLine(input) ?: throw FeedHttpException("头部中断")
                if (line.isEmpty()) break
                val k = line.substringBefore(':').trim().lowercase()
                val v = line.substringAfter(':', "").trim()
                if (k == "transfer-encoding" && v.lowercase().contains("chunked")) chunked = true
            }
            val source: InputStream = if (chunked) ChunkedInput(input) else input
            // 按「字符」读而不是按字节块再各自解码：UTF-8 多字节字符跨读边界时
            // 逐块 String(bytes) 会把截断序列替换为 U+FFFD（评审 MUST FIX），中文
            // 消息会静默丢失。InputStreamReader 的解码器持有跨读状态。
            val reader = java.io.InputStreamReader(source, Charsets.UTF_8)
            val cbuf = CharArray(4096)
            while (!stop()) {
                val n = try {
                    reader.read(cbuf)
                } catch (_: Exception) {
                    break // 读超时或连接结束：订阅窗口自然关闭
                }
                if (n < 0) break
                for (frame in parser.feed(String(cbuf, 0, n))) {
                    onFrame(frame.event, frame.data)
                    frames++
                }
            }
            for (frame in parser.finish()) {
                onFrame(frame.event, frame.data)
                frames++
            }
        }
        return frames
    }

    // ---- 底层读写 ----

    /**
     * 流式 GET：响应体直接写入 [sink]（chunked/定长/EOF 均支持），不驻留内存——
     * 大体积资产（更新 APK）经注入拨号下载用。返回 (状态码, 写出字节数)；
     * Range 续传头由调用方经 [headers] 提供。
     */
    fun get(url: String, headers: List<Pair<String, String>> = emptyList(), sink: java.io.OutputStream): Pair<Int, Long> {
        val u = URI(url)
        val host = u.host ?: throw IllegalArgumentException("URL 缺主机")
        val port = if (u.port > 0) u.port else if (u.scheme?.lowercase() == "https") 443 else 80
        val path = buildString {
            append(u.rawPath?.ifEmpty { null } ?: "/")
            if (!u.rawQuery.isNullOrEmpty()) append('?').append(u.rawQuery)
        }
        return open(u, host, port).use { s ->
            val hostHeader = host + if (port != 80 && port != 443) ":$port" else ""
            val out = s.getOutputStream()
            val sb = StringBuilder("GET $path HTTP/1.1\r\n")
            if (headers.none { it.first.equals("Host", ignoreCase = true) }) {
                sb.append("Host: $hostHeader\r\n")
            }
            for ((k, v) in headers) sb.append("$k: $v\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val input = s.getInputStream()
            val statusLine = readStatusLine(input) ?: throw HttpException("无状态行")
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw HttpException("状态码异常")
            var contentLength = -1L
            var chunked = false
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val k = line.substring(0, idx).trim()
                val v = line.substring(idx + 1).trim()
                when (k.lowercase()) {
                    "content-length" -> contentLength = v.toLongOrNull() ?: -1L
                    "transfer-encoding" -> if (v.lowercase().contains("chunked")) chunked = true
                }
            }
            val source: InputStream = if (chunked) ChunkedInput(input) else input
            val buf = ByteArray(64 * 1024)
            var written = 0L
            while (true) {
                val n = source.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
                written += n
                if (contentLength in 0..written) break
            }
            status to written
        }
    }

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
        var statusLine = readStatusLine(input) ?: throw HttpException("无状态行")
        var attempts = 0
        while (!statusLine.startsWith("HTTP/") && attempts < 4) {
            statusLine = readStatusLine(input) ?: throw HttpException("无状态行")
            attempts++
        }
        if (!statusLine.startsWith("HTTP/")) throw HttpException("状态行非 HTTP")
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw HttpException("状态码异常")

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
            when (k.lowercase()) {
                "content-length" -> contentLength = v.toIntOrNull() ?: -1
                "transfer-encoding" -> if (v.lowercase().contains("chunked")) chunked = true
            }
        }
        val body: ByteArray = when {
            status == 204 || status == 304 || status in 100..199 -> ByteArray(0)
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
            else -> input.readBytes() // Connection: close 读到 EOF
        }
        return Response(status, headers, body)
    }

    private fun readStatusLine(input: InputStream): String? {
        var lines = 0
        while (lines < 4) {
            val line = readLine(input) ?: return null
            if (line.startsWith("HTTP/")) return line
            lines++
        }
        return null
    }

    /** 读一行（\r\n 或 \n）；流结束返回 null。 */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 1 shl 20) throw HttpException("行超长")
        }
    }

    /** chunked 解码（feed 流式读也复用）。 */
    private class ChunkedInput(private val raw: InputStream) : InputStream() {
        private var remaining = 0
        private var done = false
        private var readBody = false

        override fun read(): Int {
            if (done) return -1
            if (remaining == 0) {
                if (readBody) readRawLine() // 上一块尾 CRLF
                val sizeLine = readRawLine() ?: throw HttpException("chunked 尺寸行中断")
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw HttpException("chunked 尺寸行异常")
                if (size == 0) {
                    done = true
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
            if (b < 0) throw HttpException("chunked 流中断")
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
                // 当前 chunk 读尽即返回：流式调用按块推进，不因填缓冲而阻塞，
                // 也避免中途异常丢弃已读字节（feed 订阅实测踩坑）。
                if (remaining == 0) break
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

        private fun readRawLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val c = raw.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                if (c != '\r'.code) sb.append(c.toChar())
                if (sb.length > 65536) throw HttpException("chunked 行超长")
            }
        }
    }
}

/** 传输层异常（网络/TLS/协议形态，不含任何用户输入原文）。 */
class HttpException(message: String) : Exception(message)

/** feed 订阅的 HTTP 层失败（非 200 或流中断）；status 为服务端状态码（流中断时 null）。 */
class FeedHttpException(message: String, val status: Int? = null) : Exception(message)
