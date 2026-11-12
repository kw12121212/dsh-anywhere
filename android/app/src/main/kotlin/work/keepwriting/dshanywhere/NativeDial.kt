package work.keepwriting.dshanywhere

import work.keepwriting.core.TailnetRouting
import java.net.Socket

/**
 * 原生分流拨号（dsbot-native-shell，tech/android-client 的 native-network-path）：
 * tailnet 目标经内嵌节点 SOCKS5、其余手机网络直连——与旧代理桥同一分流语义，
 * 但不经任何本机监听端口。探测、会话、API 与 feed 全部经此拨号。
 */
class NativeDial(private val tunnel: TunnelManager) {
    fun dial(host: String, port: Int): Socket {
        val endpoint = tunnel.socksEndpoint ?: return TailnetRouting.directConnect(host, port)
        return TailnetRouting.splitDial(
            tailnet = { h, p ->
                val socksHost = endpoint.addr.substringBefore(':')
                val socksPort = endpoint.addr.substringAfter(':').toInt()
                work.keepwriting.core.Socks5Dialer(socksHost, socksPort, "tsnet", endpoint.credential)
                    .connect(h, p)
            },
            direct = { h, p -> TailnetRouting.directConnect(h, p) },
        )(host, port)
    }
}
