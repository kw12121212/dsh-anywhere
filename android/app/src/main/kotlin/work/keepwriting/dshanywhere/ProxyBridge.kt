package work.keepwriting.dshanywhere

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import work.keepwriting.core.ConnectBridge
import work.keepwriting.core.TailnetRouting
import java.net.Socket

/**
 * 代理桥装配（android-proxy-routing）：桥随隧道生命周期——隧道已连接时起听
 * （dial=TailnetRouting.splitDial：tailnet 目标经内嵌节点 SOCKS5、其余平台直连），
 * 断开/清除时停。明文门控锚定当前保存的干净地址（CleartextPolicy 语义）。
 * WebView 侧由 MainWebScreen 经 ProxyController 指向桥端口并以
 * onReceivedHttpAuthRequest 应答凭据。
 */
class ProxyBridge(
    context: Context,
    private val tunnel: TunnelManager,
    private val harnessUrlProvider: () -> String?,
) {
    var port by mutableStateOf(-1)
        private set
    var credentials: Pair<String, String>? = null
        private set

    private val appContext = context.applicationContext
    private var bridge: ConnectBridge? = null
    private val direct: (String, Int) -> Socket = { host, port -> TailnetRouting.directConnect(host, port) }

    /** 分流拨号：隧道在役且有 SOCKS5 端点时 tailnet 目标经节点，否则全部直连
     *  （非 tailnet 目标两种 dial 行为一致）。 */
    private val splitDial: (String, Int) -> Socket = { host, port ->
        val endpoint = tunnel.socksEndpoint
        if (endpoint != null) {
            TailnetRouting.splitDial(
                tailnet = { h, p ->
                    Socks5DialerCompat.connect(endpoint.addr, endpoint.credential, h, p)
                },
                direct = direct,
            )(host, port)
        } else {
            direct(host, port)
        }
    }

    /** 桥的分流拨号入口（探测与会话判定复用同一分流路径）。 */
    fun dialForBridge(host: String, port: Int): Socket = splitDial(host, port)

    /** 隧道已连接且桥未在役时起听；返回端口（已在役或无隧道返回 -1）。 */
    @Synchronized
    fun ensureStarted(): Int {
        if (tunnel.state != work.keepwriting.core.TunnelStateMachine.State.CONNECTED) return -1
        if (bridge != null) return port
        val b = ConnectBridge(
            dial = splitDial,
            cleartextAllowed = { url ->
                val harness = harnessUrlProvider() ?: return@ConnectBridge false
                work.keepwriting.core.CleartextPolicy.isAllowed(harness, url)
            },
            log = {},
        )
        b.start()
        bridge = b
        port = b.port
        credentials = b.credentials
        return b.port
    }

    /** 停桥（隧道断开/清除时）。 */
    @Synchronized
    fun stop() {
        bridge?.stop()
        bridge = null
        port = -1
        credentials = null
    }

    private object Socks5DialerCompat {
        fun connect(socksAddr: String, credential: String, host: String, port: Int): Socket {
            val socksHost = socksAddr.substringBefore(':')
            val socksPort = socksAddr.substringAfter(':').toInt()
            return work.keepwriting.core.Socks5Dialer(socksHost, socksPort, "tsnet", credential)
                .connect(host, port)
        }
    }
}
