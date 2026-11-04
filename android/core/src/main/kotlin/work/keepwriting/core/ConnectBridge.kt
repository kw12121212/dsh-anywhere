package work.keepwriting.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程内本地代理桥（android-proxy-routing，参考项目 ConnectBridge 真机验证形态移植）：
 * 只听 127.0.0.1 随机端口，WebView 经 androidx.webkit ProxyController 指向它；每个连接
 * 按目标门控后经注入的 [dial] 到达目标（生产为 TailnetRouting.splitDial——tailnet 目标
 * 经内嵌节点 SOCKS5、其余平台直连）。
 *
 * 门控（本桥的存在理由）：
 * - 随机 Basic 凭据：无/错凭据 407 且不连接任何目标（同机其他进程无法借道）；
 * - 回环目标拒绝（127/8、::1、localhost）——去掉隐式直连规则后 127.0.0.1 上的质询
 *   只可能来自桥本身，WebView 自动应答桥凭据不会被同机其它进程的回环服务骗走；
 * - 明文（绝对 URI http）转发仅当 [cleartextAllowed] 放行（harness 源），
 *   https 的 CONNECT 隧道不受此限（TLS 由 WebView 端到端完成）；
 * - 在役连接数上限，超限直接关闭。
 *
 * 字节纪律（流式 harness 的生命线，参考项目真机教训）：CONNECT 后双向按到达分块
 * 即时下发 + flush；明文转发 Connection: close 定界；响应方向 transfer-encoding 透传。
 */
class ConnectBridge(
    private val dial: (host: String, port: Int) -> Socket,
    private val cleartextAllowed: (url: String) -> Boolean,
    private val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    private val log: (String) -> Unit = {},
) {
    /** 本次启动的代理凭据（Basic）：WebView 经 onReceivedHttpAuthRequest 应答。 */
    val credentials: Pair<String, String> = "dsh" to randomToken()

    private val active = AtomicInteger()
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var stopped = false

    val port: Int get() = server?.localPort ?: -1

    /** 在役 CONNECT 隧道登记（上游 socket；关它即拆隧道）。 */
    private class Tunnel(val host: String, val socket: Socket)

    private val tunnels = ConcurrentLinkedQueue<Tunnel>()

    /** 起听并返回绑定端口。 */
    fun start(): Int {
        val ss = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        server = ss
        stopped = false
        acceptThread = Thread({
            while (!stopped) {
                val client = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (!stopped) log("bridge.error kind=accept err=${e.message}")
                    return@Thread
                }
                if (active.incrementAndGet() > maxConnections) {
                    active.decrementAndGet()
                    log("bridge.reject reason=too-many-connections")
                    try { client.close() } catch (_: Exception) {}
                    continue
                }
                Thread {
                    try { serve(client) } finally { active.decrementAndGet() }
                }.apply { isDaemon = true }.start()
            }
        }, "dsh-bridge-accept").apply { isDaemon = true; start() }
        log("bridge.start port=${ss.localPort}")
        return ss.localPort
    }

    fun stop() {
        stopped = true
        for (t in tunnels) {
            try { t.socket.close() } catch (_: Exception) {}
        }
        tunnels.clear()
        try { server?.close() } catch (_: Exception) {}
        server = null
        log("bridge.stop")
    }

    private fun serve(client: Socket) {
        var kind = "?"
        var host = "?"
        var port = 0
        try {
            client.soTimeout = HEADER_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val requestLine = readLine(input) ?: return
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val parts = requestLine.split(' ')
            if (parts.size < 3) {
                respond(output, 400, "Bad Request")
                return
            }
            val method = parts[0]
            val target = parts[1]
            var pathForOrigin = "/"
            var scheme = "http"
            if (method.equals("CONNECT", ignoreCase = true)) {
                kind = "connect"
                scheme = "https"
                val hp = splitHostPort(target, 443)
                host = hp.first; port = hp.second
            } else {
                kind = "absolute"
                val u = try { URI(target) } catch (_: Exception) { null }
                if (u == null || u.host == null) {
                    respond(output, 400, "Bad Request")
                    return
                }
                host = u.host
                scheme = u.scheme?.lowercase() ?: "http"
                port = if (u.port > 0) u.port else if (scheme == "https") 443 else 80
                pathForOrigin = (u.rawPath.ifEmpty { "/" }) + (u.rawQuery?.let { "?$it" } ?: "")
            }

            // 407 门控：无/错凭据一律拒绝且不连接任何目标。
            val expected = "Basic " + Base64.getEncoder()
                .encodeToString("${credentials.first}:${credentials.second}".toByteArray(Charsets.UTF_8))
            val got = headers["proxy-authorization"]
            if (got == null || got != expected) {
                log("bridge.challenge kind=$kind host=$host")
                writeRaw(
                    output,
                    "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                        "Proxy-Authenticate: Basic realm=\"dsh-anywhere\"\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n",
                )
                return
            }

            // 回环目标拒绝：不拨号。
            if (TailnetRouting.isLoopbackTarget(host)) {
                log("bridge.deny kind=$kind host=$host reason=loopback")
                respond(output, 403, "Forbidden: loopback target")
                return
            }

            // 明文门控：只放行 harness 源的 http 转发；CONNECT 不受限。
            if (kind == "absolute" && scheme == "http" && !cleartextAllowed(target)) {
                log("bridge.deny kind=$kind host=$host reason=cleartext")
                respond(output, 403, "Forbidden: cleartext to non-harness origin")
                return
            }
            log("bridge.allow kind=$kind host=$host port=$port")

            val upstream = dial(host, port)
            try {
                upstream.soTimeout = 0
                client.soTimeout = 0
                val upIn = upstream.getInputStream()
                val upOut = upstream.getOutputStream()
                if (kind == "connect") {
                    writeRaw(output, "HTTP/1.1 200 Connection Established\r\n\r\n")
                    val t = Tunnel(host, upstream)
                    tunnels.add(t)
                    try {
                        val down = Thread({ pump(upIn, output, client) }, "dsh-bridge-down").apply { isDaemon = true; start() }
                        pump(input, upOut, upstream)
                        down.join()
                    } finally {
                        tunnels.removeIf { it === t }
                    }
                } else {
                    val sb = StringBuilder()
                    sb.append(method).append(' ').append(pathForOrigin).append(' ').append(parts[2]).append("\r\n")
                    for ((k, v) in headers) {
                        when (k) {
                            "proxy-connection", "proxy-authorization", "connection", "keep-alive" -> {}
                            else -> sb.append(k).append(": ").append(v).append("\r\n")
                        }
                    }
                    sb.append("Connection: close\r\n\r\n")
                    upOut.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                    val bodyLen = headers["content-length"]?.toIntOrNull() ?: 0
                    if (bodyLen > 0) copyExactly(input, upOut, bodyLen)
                    upOut.flush()
                    pump(upIn, output, upstream)
                }
            } finally {
                try { upstream.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            log("bridge.error kind=$kind host=$host err=${e.javaClass.simpleName}:${e.message?.take(80)}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pump(src: InputStream, dst: OutputStream, dstSocket: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: IOException) {
        } finally {
            try { dstSocket.shutdownOutput() } catch (_: Exception) {}
        }
    }

    private fun splitHostPort(s: String, defaultPort: Int): Pair<String, Int> {
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return s to defaultPort
            val host = s.substring(1, end)
            val port = s.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            return host to port
        }
        val i = s.lastIndexOf(':')
        return if (i > 0) s.substring(0, i) to (s.substring(i + 1).toIntOrNull() ?: defaultPort) else s to defaultPort
    }

    private fun respond(output: OutputStream, code: Int, msg: String) {
        val body = msg.toByteArray(Charsets.UTF_8)
        writeRaw(
            output,
            "HTTP/1.1 $code ${msg.substringBefore(':')}\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n",
        )
        output.write(body)
        output.flush()
    }

    private fun writeRaw(output: OutputStream, s: String) {
        output.write(s.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 16 * 1024) throw IOException("header line too long")
        }
        return sb.toString()
    }

    private fun copyExactly(src: InputStream, dst: OutputStream, len: Int) {
        var left = len
        val buf = ByteArray(8 * 1024)
        while (left > 0) {
            val n = src.read(buf, 0, minOf(left, buf.size))
            if (n < 0) throw IOException("eof in body")
            dst.write(buf, 0, n)
            left -= n
        }
    }

    companion object {
        /** 并发连接上限：Chromium 对单个代理最多 32 条 socket，留余量。 */
        const val DEFAULT_MAX_CONNECTIONS = 64

        /** 读请求头的期限：拨号后清零（长连接与流式响应不受限）。 */
        const val HEADER_TIMEOUT_MS = 10_000

        private fun randomToken(): String =
            ByteArray(24).also { SecureRandom().nextBytes(it) }
                .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}
