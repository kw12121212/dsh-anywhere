package work.keepwriting.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 经注入拨号函数的最小 RFC6455 WebSocket 客户端（dsbot-attachments）：与 HttpSocket
 * 同层复用 NativeDial 分流（tailnet 目标经内嵌节点、https 同套接字分层 TLS），只实现
 * dsh /api/remote.mux 载体需要的子集——文本帧（客户端发送必带掩码）、服务端无掩码帧
 * 解析（含分片与 64 位长度）、ping 自动回 pong、close 帧识别。二进制帧与扩展协商
 * 不支持（dsh mux 只用文本帧，服务端对二进制帧回 1003）。
 *
 * 协议形态（0.3.5 对 dsh-api-remotes 目录与真实服务器实测）：
 *  - 握手 GET /api/remote.mux，带受信 Host 与 dsh-auth cookie（/connect 只是
 *    launch-token 首认证入口，已有会话 cookie 时无需经过）；
 *  - 调用帧 {type:"open",streamId,endpoint:"ns/method",payload:{args:{request:…}}}；
 *    下行 {type:"item",streamId,value}* → end（正常）或 error{code,message,details}；
 *    未终态放弃发 cancel。见 [DshMux]。
 */
class WsClient(
    private val dial: (String, Int) -> Socket,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val tls: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
) {
    /** 握手被服务器拒绝（非 101）。 */
    class HandshakeException(val status: Int, val bodyText: String) : Exception("websocket handshake HTTP $status")

    /** 一个已握手的 WebSocket 会话。单线程使用（mux 是请求-应答式短会话）。 */
    class Session internal constructor(
        private val socket: Socket,
        private val input: InputStream,
        private val output: OutputStream,
    ) : AutoCloseable {
        private val readBuf = ByteArray(8 * 1024)
        private var readLen = 0
        private var readPos = 0
        private var closeReceived = false
        private var sentClose = false

        val isOpen: Boolean get() = !closeReceived && !sentClose && !socket.isClosed

        fun sendText(text: String) {
            val payload = text.toByteArray(StandardCharsets.UTF_8)
            val mask = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
            val header = ByteArrayOutputStream()
            header.write(0x81) // FIN + text
            val n = payload.size
            when {
                n < 126 -> header.write(0x80 or n)
                n < 65536 -> {
                    header.write(0x80 or 126)
                    header.write((n ushr 8) and 0xFF)
                    header.write(n and 0xFF)
                }
                else -> {
                    header.write(0x80 or 127)
                    for (shift in 56 downTo 0 step 8) header.write((n ushr shift) and 0xFF)
                }
            }
            header.write(mask)
            val masked = ByteArray(n) { i -> (payload[i].toInt() xor mask[i and 3].toInt()).toByte() }
            output.write(header.toByteArray())
            output.write(masked)
            output.flush()
        }

        /** 读一个完整文本消息（按 FIN 位跨 continuation 分片重组；ping 自动回 pong；
         *  close 返回 null 并标记关闭）。 */
        fun receiveText(): String? {
            val assembled = ByteArrayOutputStream()
            var started = false
            while (true) {
                val (fin, opcode, payload) = readFrame()
                when (opcode) {
                    OPCODE_TEXT -> {
                        assembled.write(payload)
                        started = true
                        if (fin) return assembled.toString("UTF-8")
                    }
                    OPCODE_CONT -> {
                        if (!started) throw java.io.IOException("continuation frame without start")
                        assembled.write(payload)
                        if (fin) return assembled.toString("UTF-8")
                    }
                    OPCODE_BINARY -> throw java.io.IOException("unexpected binary frame")
                    OPCODE_PING -> sendControl(OPCODE_PONG, payload)
                    OPCODE_CLOSE -> {
                        closeReceived = true
                        return null
                    }
                }
            }
        }

        private fun sendControl(opcode: Int, payload: ByteArray) {
            val mask = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
            val out = ByteArrayOutputStream()
            out.write(0x80 or opcode)
            out.write(0x80 or payload.size)
            out.write(mask)
            out.write(ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i and 3].toInt()).toByte() })
            val bytes = out.toByteArray()
            output.write(bytes)
            output.flush()
        }

        /** 优雅关闭：发 close 帧后不再读。 */
        override fun close() {
            if (!sentClose) {
                runCatching { sendControl(OPCODE_CLOSE, ByteArray(0)) }
                sentClose = true
            }
            runCatching { socket.close() }
        }

        private fun fill(): Boolean {
            if (readPos < readLen) return true
            readLen = input.read(readBuf)
            readPos = 0
            return readLen > 0
        }

        private fun readExact(n: Int): ByteArray {
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                if (!fill()) throw java.io.IOException("websocket eof")
                val take = minOf(n - off, readLen - readPos)
                System.arraycopy(readBuf, readPos, out, off, take)
                readPos += take
                off += take
            }
            return out
        }

        private fun readFrame(): Triple<Boolean, Int, ByteArray> {
            val h = readExact(2)
            val fin = (h[0].toInt() and 0x80) != 0
            val opcode = h[0].toInt() and 0x0F
            val masked = (h[1].toInt() and 0x80) != 0
            var len = (h[1].toInt() and 0x7F).toLong()
            if (len == 126L) {
                val ext = readExact(2)
                len = ((ext[0].toLong() and 0xFF) shl 8) or (ext[1].toLong() and 0xFF)
            } else if (len == 127L) {
                val ext = readExact(8)
                len = 0
                for (b in ext) len = (len shl 8) or (b.toLong() and 0xFF)
            }
            if (len > MAX_FRAME_BYTES) throw java.io.IOException("websocket frame too large: $len")
            val mask = if (masked) readExact(4) else null
            val payload = readExact(len.toInt())
            if (mask != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            }
            return Triple(fin, opcode, payload)
        }

        private companion object {
            const val OPCODE_CONT = 0
            const val OPCODE_TEXT = 1
            const val OPCODE_BINARY = 2
            const val OPCODE_CLOSE = 8
            const val OPCODE_PING = 9
            const val OPCODE_PONG = 10
            const val MAX_FRAME_BYTES = 64 * 1024 * 1024
        }
    }

    /**
     * 连接并握手：host/port 为物理目标（dial 决定分流），secure 为 true 时分层 TLS，
     * path 为载体路径（/api/remote.mux），hostHeader 为受信 Host 头，headers 追加
     * （Cookie 等）。握手 101 返回会话；非 101 抛 [HandshakeException]。
     */
    fun connect(
        host: String,
        port: Int,
        secure: Boolean,
        path: String,
        hostHeader: String,
        headers: List<Pair<String, String>> = emptyList(),
    ): Session {
        val raw = dial(host, port)
        raw.soTimeout = readTimeoutMs
        raw.tcpNoDelay = true
        val socket = if (!secure) raw else try {
            // 与 HttpSocket 同款：同套接字分层 TLS（SNI + 主机名校验）
            (tls.createSocket(raw, host, port, true) as SSLSocket).also { t ->
                t.sslParameters = t.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                t.startHandshake()
            }
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            append("\r\n")
        }
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        output.write(request.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        // 读握手应答头（按行读到空行）
        val head = ByteArrayOutputStream()
        while (true) {
            val b = readByte(input)
            head.write(b)
            val tail = head.toByteArray()
            if (tail.size >= 4 && tail[tail.size - 4] == '\r'.code.toByte() && tail[tail.size - 3] == '\n'.code.toByte() &&
                tail[tail.size - 2] == '\r'.code.toByte() && tail[tail.size - 1] == '\n'.code.toByte()
            ) break
            if (tail.size > 64 * 1024) throw java.io.IOException("websocket handshake header too large")
        }
        val headText = head.toString("UTF-8")
        val statusLine = headText.lineSequence().firstOrNull() ?: throw java.io.IOException("empty handshake response")
        val status = Regex("HTTP/1\\.[01] (\\d{3})").find(statusLine)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw java.io.IOException("malformed handshake status: $statusLine")
        if (status != 101) {
            // 非 101：读掉可能的正文后按拒绝处理（服务端按 Connection: close 关闭）
            runCatching { socket.close() }
            throw HandshakeException(status, headText)
        }
        val accept = Regex("(?i)sec-websocket-accept:\\s*(\\S+)").find(headText)?.groupValues?.get(1)
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.US_ASCII)),
        )
        if (accept != null && !accept.equals(expected, ignoreCase = true)) {
            runCatching { socket.close() }
            throw java.io.IOException("websocket accept mismatch")
        }
        return Session(socket, input, output)
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw java.io.IOException("websocket eof during handshake")
        return b
    }
}
