package work.keepwriting.dshanywhere

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory
import work.keepwriting.core.Socks5Dialer
import work.keepwriting.core.TailnetRouting

/**
 * 经内嵌 tailnet 节点 SOCKS5 的 TLS 套接字工厂（自托管更新源等 https 直连场景
 * 共用）：域名字节交给代理侧解析（ts.net 走 MagicDNS）。隧道不可用（未连接）时
 * 返回 null，调用方按普通直连失败处理。
 */
object SocksTls {
    fun factory(appContext: android.content.Context): SSLSocketFactory? {
        val endpoint = TunnelManager.get(appContext).socksEndpoint ?: return null
        val host = endpoint.addr.substringBefore(':')
        val port = endpoint.addr.substringAfter(':').toIntOrNull() ?: return null
        val dialer = Socks5Dialer(host, port, "tsnet", endpoint.credential)
        val delegate = SSLSocketFactory.getDefault() as SSLSocketFactory
        return object : SSLSocketFactory() {
            override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
            override fun createSocket(s: Socket, h: String, port: Int, autoClose: Boolean) =
                delegate.createSocket(s, h, port, autoClose)
            override fun createSocket(h: String, port: Int): Socket =
                delegate.createSocket(dialer.connect(h, port), h, port, true)
            override fun createSocket(h: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                delegate.createSocket(dialer.connect(h, port), h, port, true)
            override fun createSocket(host: InetAddress, port: Int): Socket =
                createSocket(host.hostName, port)
            override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
                createSocket(host.hostName, port)
        }
    }

    /** 目标主机是否 tailnet 地址（分流判定与 DiagnosticsManager 同一语义）。 */
    fun isTailnet(host: String): Boolean = TailnetRouting.isTailnetTarget(host)
}
