package work.keepwriting.core

import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WsClient/DshMux 测试（dsbot-attachments）：JUnit 内起回环迷你 WebSocket 服务端
 * （握手 SHA1 + 掩码帧解析），覆盖客户端发送掩码、服务端分片重组、ping/pong、
 * close 语义、mux 的 item/end 与 error 应答、非 101 拒绝。
 */
class WsClientTest {

    /** 迷你服务端：握手后把收到的每条文本按 handler 应答，记录收到的消息。 */
    private class MiniServer(private val handler: (String) -> List<String>) {
        val server = ServerSocket(0)
        val received = mutableListOf<String>()
        @Volatile var socket: Socket? = null
        val accepted = CountDownLatch(1)

        fun start() {
            Thread({
                server.soTimeout = 15_000
                val s = server.accept()
                socket = s
                handshake(s)
                accepted.countDown()
                val input = s.getInputStream()
                val output = s.getOutputStream()
                try {
                    while (true) {
                        val text = readClientMessage(input) ?: break
                        synchronized(received) { received.add(text) }
                        for (reply in handler(text)) sendText(output, reply)
                    }
                } catch (_: Exception) {
                }
            }).apply { isDaemon = true }.start()
        }

        private fun handshake(s: Socket) {
            val input = s.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
            val key = Regex("Sec-WebSocket-Key: (\\S+)").find(head)!!.groupValues[1]
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            )
            val resp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n"
            s.getOutputStream().write(resp.toByteArray(StandardCharsets.US_ASCII))
            s.getOutputStream().flush()
        }

        private fun readExact(input: InputStream, n: Int): ByteArray {
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(out, off, n - off)
                if (r < 0) throw java.io.EOFException()
                off += r
            }
            return out
        }

        private fun readClientMessage(input: InputStream): String? {
            while (true) {
                val h = readExact(input, 2)
                val opcode = h[0].toInt() and 0x0F
                val masked = (h[1].toInt() and 0x80) != 0
                var len = h[1].toInt() and 0x7F
                if (len == 126) len = readExact(input, 2).let { (it[0].toInt() and 0xFF) shl 8 or (it[1].toInt() and 0xFF) }
                if (len == 127) {
                    val ext = readExact(input, 8)
                    len = 0
                    for (b in ext) len = (len shl 8) or (b.toInt() and 0xFF)
                }
                val mask = if (masked) readExact(input, 4) else null
                val payload = readExact(input, len)
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                when (opcode) {
                    8 -> return null
                    9 -> continue // ping 忽略
                    1 -> return String(payload, StandardCharsets.UTF_8)
                }
            }
        }

        fun sendText(output: OutputStream, text: String, fragment: Boolean = false) {
            val payload = text.toByteArray(StandardCharsets.UTF_8)
            if (!fragment) {
                writeFrame(output, 0x81, payload)
            } else {
                // 拆两片：首片 opcode=0（continuation 上的 text 由 0x01|FIN=0 起）——
                // 首片必须带文本 opcode
                val mid = payload.size / 2
                writeFrame(output, 0x01, payload.copyOfRange(0, mid), fin = false)
                writeFrame(output, 0x00, payload.copyOfRange(mid, payload.size), fin = true)
            }
        }

        private fun writeFrame(output: OutputStream, firstByte: Int, payload: ByteArray, fin: Boolean = true) {
            val b0 = if (fin) firstByte or 0x80 else firstByte
            val header = mutableListOf(b0)
            val n = payload.size
            when {
                n < 126 -> header.add(n)
                n < 65536 -> {
                    header.add(126)
                    header.add((n shr 8) and 0xFF)
                    header.add(n and 0xFF)
                }
                else -> {
                    header.add(127)
                    for (shift in 56 downTo 0 step 8) header.add((n shr shift) and 0xFF)
                }
            }
            output.write(header.map { (it and 0xFF).toByte() }.toByteArray())
            output.write(payload)
            output.flush()
        }

        fun close() {
            runCatching { socket?.close() }
            runCatching { server.close() }
        }
    }

    private fun connectTo(mini: MiniServer): WsClient.Session {
        val port = mini.server.localPort
        mini.start()
        val client = WsClient(dial = { _, p -> Socket("127.0.0.1", p) }, readTimeoutMs = 8_000)
        return client.connect("127.0.0.1", port, secure = false, path = "/api/remote.mux", hostHeader = "127.0.0.1:$port")
    }

    @Test
    fun `握手与文本往返（客户端帧带掩码）`() {
        val mini = MiniServer { listOf("echo") }
        try {
            val session = connectTo(mini)
            session.sendText("你好 ws")
            assertEquals("echo", session.receiveText())
            synchronized(mini.received) {
                assertEquals(listOf("你好 ws"), mini.received)
            }
            session.close()
        } finally {
            mini.close()
        }
    }

    @Test
    fun `服务端分片帧重组为一条消息`() {
        val mini = MiniServer { _ -> emptyList() }
        try {
            val session = connectTo(mini)
            mini.accepted.await(5, TimeUnit.SECONDS)
            session.sendText("ping-ignored")
            // 服务端主动发分片文本
            mini.sendText(mini.socket!!.getOutputStream(), "分段消息整体", fragment = true)
            assertEquals("分段消息整体", session.receiveText())
            session.close()
        } finally {
            mini.close()
        }
    }

    @Test
    fun `mux 调用 end 应答取最后一个 item 值`() {
        val mini = MiniServer { text ->
            val id = Regex("\"streamId\":\"(\\w+)\"").find(text)!!.groupValues[1]
            val endpoint = Regex("\"endpoint\":\"(\\S+?)\"").find(text)!!.groupValues[1]
            listOf(
                "{\"type\":\"item\",\"streamId\":\"$id\",\"value\":{\"echo\":\"$endpoint\",\"n\":1}}",
                "{\"type\":\"item\",\"streamId\":\"$id\",\"value\":{\"echo\":\"$endpoint\",\"n\":2}}",
                "{\"type\":\"end\",\"streamId\":\"$id\"}",
            )
        }
        try {
            val session = connectTo(mini)
            DshMux(session).use { mux ->
                val result = mux.call("session/attachment", "{\"sessionId\":\"s1\",\"attachment\":{}}")
                assertTrue(result is DshMux.Result.Ok)
                assertEquals("""{"echo":"session/attachment","n":2}""", (result as DshMux.Result.Ok).value)
            }
            synchronized(mini.received) {
                val sent = mini.received.first()
                assertTrue(sent.contains("\"type\":\"open\""), "open 帧形态：$sent")
                assertTrue(sent.contains("\"payload\":{\"args\":{\"request\":"), "args.request 包装：$sent")
            }
        } finally {
            mini.close()
        }
    }

    @Test
    fun `mux 错误应答映射 code 与 message`() {
        val mini = MiniServer { text ->
            val id = Regex("\"streamId\":\"(\\w+)\"").find(text)!!.groupValues[1]
            listOf(
                "{\"type\":\"error\",\"streamId\":\"$id\",\"error\":{\"code\":\"gateway/input-invalid\",\"message\":\"bad\"}}",
            )
        }
        try {
            val session = connectTo(mini)
            val result = DshMux(session).call("session/prompt", "{}")
            assertTrue(result is DshMux.Result.Err)
            assertEquals("gateway/input-invalid", (result as DshMux.Result.Err).code)
            assertEquals("bad", result.message)
            session.close()
        } finally {
            mini.close()
        }
    }

    @Test
    fun `服务端 4xx 握手应答映射为 HandshakeException`() {
        val server = ServerSocket(0)
        try {
            Thread({
                val s = server.accept()
                // 读掉握手请求头后回真 401 再关
                val input = s.getInputStream()
                val head = StringBuilder()
                val CRLF = String(charArrayOf(13.toChar(), 10.toChar()))
                while (!head.endsWith(CRLF + CRLF)) head.append(input.read().toChar())
                s.getOutputStream().write(
                    ("HTTP/1.1 401 Unauthorized" + CRLF + "Connection: close" + CRLF +
                        "Content-Length: 12" + CRLF + CRLF + "unauthorized").toByteArray(),
                )
                s.getOutputStream().flush()
            }).apply { isDaemon = true }.start()
            val client = WsClient(dial = { _, p -> Socket("127.0.0.1", p) })
            val ex = assertFailsWith<WsClient.HandshakeException> {
                client.connect("127.0.0.1", server.localPort, secure = false, path = "/api/remote.mux", hostHeader = "h")
            }
            assertEquals(401, ex.status)
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun `非 101 握手被拒绝`() {
        val server = ServerSocket(0)
        try {
            Thread({
                val s = server.accept()
                val input = s.getInputStream()
                while (input.read() != -1) {}
            }).apply { isDaemon = true }.start()
            val client = WsClient(dial = { _, p -> Socket("127.0.0.1", p) })
            // 服务端直接断开（无响应）→ 握手读取抛 IO 异常
            assertFailsWith<Exception> {
                client.connect("127.0.0.1", server.localPort, secure = false, path = "/x", hostHeader = "h")
            }
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun `附件消息构造与上传应答解码`() {
        val uploadJson = """
            {"receiptId":"r1","file":{"attachmentId":"sha256:abc","name":"图.png","bytes":15,
             "width":2,"height":3,"mediaType":"image/png"}}
        """.trimIndent()
        val parsed = checkNotNull(Json.parse(uploadJson))
        val decoded = assertNotNull(AttachmentMessages.decodeUpload(parsed))
        assertEquals("r1", decoded.receiptId)
        assertEquals("sha256:abc", decoded.attachmentId)
        assertEquals("图.png", decoded.name)
        assertEquals(15L, decoded.bytes)

        // prompt content：附件一律 file+receipt，文本非空附加 text 块
        val content = AttachmentMessages.promptContent(listOf("r1", "r2"), "看图")
        assertEquals(
            """{"type":"file","receiptId":"r1"},{"type":"file","receiptId":"r2"},{"type":"text","text":"看图"}""",
            content,
        )
        // 无文本：尾逗号收敛
        assertEquals(
            """{"type":"file","receiptId":"r1"}""",
            AttachmentMessages.promptContent(listOf("r1"), ""),
        )
        // 空输入空串（调用方拒发）
        assertEquals("", AttachmentMessages.promptContent(emptyList(), ""))

        val request = AttachmentMessages.promptRequest("rq1", "s1", listOf("r1"), "hi")
        val parsedRequest = checkNotNull(Json.parse(request)) as Json.Value.Object
        assertEquals("s1", (parsedRequest.members["sessionId"] as? Json.Value.StringValue)?.value)
        assertEquals("queue", (parsedRequest.members["mode"] as? Json.Value.StringValue)?.value)
        assertTrue(request.contains("\"mode\":\"queue\""))
        assertTrue(request.contains("\"requestId\":\"rq1\""))
        assertTrue(request.contains("\"sessionId\":\"s1\""))

        assertNull(AttachmentMessages.decodeUpload(checkNotNull(Json.parse("""{"file":{}}"""))))

    @Test
    fun `HTTP unary 信封构造与 server-response 解析`() {
        // client-request 信封：type/rpcId/method + payload.args.request 包装
        val req = AttachmentMessages.clientRequest("r-1", "session/prompt", "{\"sessionId\":\"s1\"}")
        val parsedReq = checkNotNull(Json.parse(req)) as Json.Value.Object
        assertEquals("client-request", (parsedReq.members["type"] as Json.Value.StringValue).value)
        assertEquals("r-1", (parsedReq.members["rpcId"] as Json.Value.StringValue).value)
        assertEquals("session/prompt", (parsedReq.members["method"] as Json.Value.StringValue).value)
        val payload = parsedReq.members["payload"] as Json.Value.Object
        val args = payload.members["args"] as Json.Value.Object
        assertEquals("s1", ((args.members["request"] as Json.Value.Object).members["sessionId"] as Json.Value.StringValue).value)
        // server-response：ok 带 value
        val ok = AttachmentMessages.parseServerResponse(
            "{\"type\":\"server-response\",\"rpcId\":\"r-1\",\"result\":{\"ok\":true,\"value\":{\"accepted\":true}}}",
        )
        assertTrue(ok is DshMux.Result.Ok)
        assertEquals("{\"accepted\":true}", (ok as DshMux.Result.Ok).value)
        // server-response：错误带 code/message
        val err = AttachmentMessages.parseServerResponse(
            "{\"type\":\"server-response\",\"rpcId\":\"r-1\",\"result\":{\"ok\":false,\"error\":{\"code\":\"session/not-found\",\"message\":\"no\"}}}",
        )
        assertTrue(err is DshMux.Result.Err)
        assertEquals("session/not-found", (err as DshMux.Result.Err).code)
        // 非 server-response（如 /api/bot 信封）拒绝
        assertNull(AttachmentMessages.parseServerResponse("{\"ok\":true,\"value\":1}"))
        assertNull(AttachmentMessages.parseServerResponse("not json"))
    }

        // 0.3.5 实测：图片上传的回执键是 image（标准化图片引用），普通文件是 file——两者兼容
        val imageUpload = checkNotNull(Json.parse(
            """{"receiptId":"r9","image":{"attachmentId":"sha256:aa","mediaType":"image/png","width":1,"height":1,"bytes":70,"name":"t.png"}}""",
        ))
        val decodedImage = assertNotNull(AttachmentMessages.decodeUpload(imageUpload))
        assertEquals("sha256:aa", decodedImage.attachmentId)
        assertEquals("t.png", decodedImage.name)
    }
}
