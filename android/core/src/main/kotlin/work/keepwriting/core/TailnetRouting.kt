package work.keepwriting.core

import java.net.InetSocketAddress
import java.net.Socket

/**
 * 分流判定（android-proxy-routing 的核心策略，milestone intake 问题 tunnel-optional）：
 * tailnet 目标经内嵌节点、其他目标经手机网络直连；手机本机回环目标拒绝。
 * 判定全部基于字面形态，不做任何 DNS 解析——解析本身就是要防的泄漏。
 */
object TailnetRouting {
    /** 平台直连的建连超时。 */
    const val DIRECT_CONNECT_TIMEOUT_MS = 15_000

    /**
     * 目标是否属于 tailnet 内：字面 IPv4 落在 CGNAT 100.64.0.0/10；IPv6 前缀
     * fd7a:115c:a1e0:；主机名以 .ts.net 结尾；或单标签名（MagicDNS 短名/裸主机名）。
     */
    fun isTailnetTarget(host: String): Boolean {
        val h = host.trim().trimEnd('.').removePrefix("[").removeSuffix("]").lowercase()
        if (h.isEmpty()) return false
        // 手机本机回环（localhost 等无点短名）不是 tailnet 目标：直连路径处理。
        if (isLoopbackTarget(h)) return false
        parseIPv4(h)?.let { return it[0] == 100 && it[1] in 64..127 }
        if (h.contains(':')) return h.startsWith("fd7a:115c:a1e0:")
        if (h.endsWith(".ts.net")) return true
        return !h.contains('.')
    }

    /**
     * 目标是否为手机本机回环（不 DNS）：127.0.0.0/8、::1、0.0.0.0、localhost。
     * 代理桥拒绝这类目标——WebView 去掉隐式回环直连规则后，127.0.0.1 上出现的
     * 质询只可能来自桥本身。
     */
    fun isLoopbackTarget(host: String): Boolean {
        val h = host.trim().trimEnd('.').removePrefix("[").removeSuffix("]").lowercase()
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h == "::1" || h == "0:0:0:0:0:0:0:1" || h == "0.0.0.0" || h == "::") return true
        val parts = h.split('.')
        return parts.size == 4 && parts[0] == "127" && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    /** 组合分流拨号：tailnet 目标交 [tailnet]，其余交 [direct]（默认平台直连）。 */
    fun splitDial(
        tailnet: (String, Int) -> Socket,
        direct: (String, Int) -> Socket = ::directConnect,
    ): (String, Int) -> Socket = { host, port ->
        if (isTailnetTarget(host)) tailnet(host, port) else direct(host, port)
    }

    /** 经平台网络栈直连（系统 DNS）；失败时关闭 socket 再抛出。 */
    fun directConnect(host: String, port: Int): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), DIRECT_CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
        return s
    }

    private fun parseIPv4(s: String): IntArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0 until 4) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v < 0 || v > 255) return null
            out[i] = v
        }
        return out
    }
}
