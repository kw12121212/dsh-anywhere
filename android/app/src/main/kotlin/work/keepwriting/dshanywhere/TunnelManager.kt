package work.keepwriting.dshanywhere

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import work.keepwriting.core.TunnelStateMachine
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * 内嵌 tailnet 隧道生命周期（android-tstunnel）：进程内单例、按需启动，全部
 * tsnet 调用（阻塞）只在单一后台线程执行，不使用前台服务。状态转移判定在
 * core 的 TunnelStateMachine；节点状态持久 filesDir/tstunnel（免 key 重连的
 * 持久层），auth key 只在注册完成前存 App 私有 SharedPreferences（注册成功即删除），
 * 绝不进日志与界面。
 *
 * 隧道可选（milestone intake 问题 tunnel-optional）：无 key 且无节点状态时
 * ensureUp 不触碰 tsnet（Up 无 key 无状态会永久阻塞）。
 */
@SuppressLint("ApplySharedPref")
class TunnelManager private constructor(context: Context) {
    /** SOCKS5 回环端点（已连接后有效；android-proxy-routing 消费）。 */
    data class SocksEndpoint(val addr: String, val credential: String)

    var state by mutableStateOf(TunnelStateMachine.State.NOT_CONFIGURED)
        private set
    var nodeName by mutableStateOf(NODE_HOSTNAME)
        private set
    /** FAILED 态的原因说明（已脱敏，不含 auth key）。 */
    var failureReason by mutableStateOf("")
        private set
    var socksEndpoint by mutableStateOf<SocksEndpoint?>(null)
        private set

    private val appContext = context.applicationContext
    private val stateDir = File(appContext.filesDir, "tstunnel")
    private val prefs = appContext.getSharedPreferences("tunnel", Context.MODE_PRIVATE)
    private val worker = HandlerThread("dsh-tunnel").apply { start() }
    private val handler = Handler(worker.looper)
    private var node: tstunnel.Node? = null

    fun hasAuthKey(): Boolean = prefs.getString(PREF_AUTH_KEY, null)?.isNotEmpty() == true

    /** 已注册节点的持久状态是否存在（免 key 重连的前提）。 */
    fun hasPersistedState(): Boolean = File(stateDir, "tailscaled.state").isFile

    /** 按需启动（App 打开时调用）：判定由 core 状态机做，未配置时不触碰 tsnet；
     *  已连接时幂等直返（在役节点绝不重建——同进程关闭再重建未经构造性验证）。 */
    fun ensureUp() {
        handler.post { startIfNeeded() }
    }

    /** 粘贴 auth key 发起连接（设置页卡片）。 */
    fun connectWithKey(key: String) {
        if (key.isBlank()) return
        handler.post { connectNow(key) }
    }

    /**
     * 阻塞直到连接尝试结束并返回最终状态（首次设置页/设置页的保存前验证；
     * 只可在后台线程调用）：有 key 时同 [connectWithKey]，null 时同 [ensureUp]
     * 凭已有节点状态重连。在同一隧道线程上执行，与其他入口串行。
     * 在役且已连接时直返（排在前面的 ensureUp 可能刚连上，绝不重建在役节点）；
     * 带 key 的尝试未连上时删除该 key（验证页的输入框仍持有 key，重试会重新提交）；
     * 此前本无节点时连同半注册的节点状态一并丢弃——否则之后每次启动都会凭被拒的
     * key 或残留状态尝试登录，用户留空 key 时隧道也不再是「未配置」。
     */
    fun connectAndAwait(key: String?): TunnelStateMachine.State {
        val done = CompletableFuture<TunnelStateMachine.State>()
        handler.post {
            try {
                if (node != null && state == TunnelStateMachine.State.CONNECTED) return@post
                if (key.isNullOrBlank()) {
                    startIfNeeded()
                } else {
                    val hadNode = hasPersistedState()
                    connectNow(key)
                    if (state != TunnelStateMachine.State.CONNECTED) {
                        prefs.edit().remove(PREF_AUTH_KEY).apply()
                        if (!hadNode) discardNode()
                    }
                }
            } finally {
                done.complete(state)
            }
        }
        return done.get()
    }

    private fun connectNow(key: String) {
        // 幂等守卫（tech/android-client 的 tunnel-single-worker）：在役且已连接即直返。
        if (node != null && state == TunnelStateMachine.State.CONNECTED) return
        prefs.edit().putString(PREF_AUTH_KEY, key).apply()
        setState(TunnelStateMachine.State.CONNECTING)
        startNode(key)
    }

    /** 清除：关节点、删 key 与节点状态（含 logpolicy 缓存），彻底回到未配置。 */
    fun clear() {
        handler.post {
            discardNode()
            prefs.edit().remove(PREF_AUTH_KEY).apply()
            setState(TunnelStateMachine.cleared(), reason = "")
        }
    }

    /** 关节点并删除节点状态（含 logpolicy 缓存）；不改界面状态。 */
    private fun discardNode() {
        runCatching { node?.close() }
        node = null
        socksEndpoint = null
        stateDir.deleteRecursively()
        stateDir.parentFile?.resolve("cache")?.resolve("Tailscale")?.deleteRecursively()
    }

    private fun startIfNeeded() {
        // 幂等守卫必须在任何状态改写之前：在役且已连接即直返，绝不拆除重建。
        if (node != null && state == TunnelStateMachine.State.CONNECTED) return
        val decision = TunnelStateMachine.decideStart(hasAuthKey(), hasPersistedState())
        setState(decision.state)
        if (!decision.shouldStart) return
        // 免 key 重连（有持久状态）或带 key 注册。
        startNode(prefs.getString(PREF_AUTH_KEY, null) ?: "")
    }

    private fun startNode(authKey: String) {
        try {
            injectNetworkFacts()
            val n = tstunnel.Node(NODE_HOSTNAME, stateDir.absolutePath, authKey)
            runCatching { node?.close() }
            node = n
            n.start()
            n.up()
            socksEndpoint = SocksEndpoint(n.loopback(), n.proxyCredential())
            failureReason = ""
            // 注册已完成：节点身份已持久在 stateDir，免 key 重连不再需要 key——不再留存
            // 可复用的 auth key（节点被删后进入需要 key，而不是被静默重新注册）。
            prefs.edit().remove(PREF_AUTH_KEY).apply()
            setState(TunnelStateMachine.State.CONNECTED)
        } catch (t: Throwable) {
            val raw = t.message ?: node?.errmsg() ?: t.javaClass.simpleName
            var classified = TunnelStateMachine.classifyUpFailure(raw)
            // Up 超时（上游对纯 NeedsLogin 挂起不报错）时读 backend 状态补分类。
            if (classified == TunnelStateMachine.State.FAILED) {
                val status = runCatching { node?.statusJSON() }.getOrNull()
                if (status != null && TunnelStateMachine.needsKeyFromStatusJson(status)) {
                    classified = TunnelStateMachine.State.NEEDS_KEY
                }
            }
            // 错误文本可能回显 key（如 URL 形式的 authkey=…），一律脱敏。
            val sanitized = if (authKey.isNotEmpty()) raw.replace(authKey, "***") else raw
            setState(classified, reason = sanitized)
            // 失败/需要 key 的错误态报告（diagnostics-reporting）：reason 已脱敏，未配置时零行为。
            DiagnosticsManager.get(appContext).recordTunnelFailure(classified, sanitized)
        }
    }

    /** tsnet 启动前的 Android 平台前置：注入 Java 网卡快照与默认路由接口名
     *  （SELinux 禁 netlink，参考项目真机实证的硬前置）。 */
    private fun injectNetworkFacts() {
        val arr = JSONArray()
        val nis = java.net.NetworkInterface.getNetworkInterfaces()
            ?: java.util.Collections.emptyEnumeration<java.net.NetworkInterface>()
        while (nis.hasMoreElements()) {
            val ni = nis.nextElement()
            val o = JSONObject()
            o.put("name", ni.name)
            o.put("index", ni.index)
            o.put("mtu", ni.mtu)
            o.put("hw", ni.hardwareAddress?.joinToString(":") { String.format("%02x", it) } ?: "")
            o.put("up", ni.isUp)
            o.put("loopback", ni.isLoopback)
            o.put("p2p", ni.isPointToPoint)
            o.put("multicast", ni.supportsMulticast())
            val addrs = JSONArray()
            for (ia in ni.interfaceAddresses) {
                val host = ia.address?.hostAddress ?: continue
                addrs.put("$host/${ia.networkPrefixLength}")
            }
            o.put("addrs", addrs)
            arr.put(o)
        }
        tstunnel.Tstunnel.setInterfacesJSON(arr.toString())
        runCatching {
            val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
            cm?.getLinkProperties(cm.activeNetwork)?.interfaceName?.takeIf { it.isNotEmpty() }?.let {
                tstunnel.Tstunnel.setDefaultRouteIfname(it)
            }
        }
    }

    private fun setState(next: TunnelStateMachine.State, reason: String = failureReason) {
        state = next
        failureReason = reason
    }

    companion object {
        /** 节点主机名：与 private-content-assistant 的节点区分（milestone 约定）。 */
        const val NODE_HOSTNAME = "dsh-anywhere-android"
        private const val PREF_AUTH_KEY = "auth_key"

        /** 进程内单例：隧道节点状态目录唯一，Activity 重建不得再起第二个节点
         *  （tailscaled.state 锁冲突会把在役连接误判失败）。 */
        @Volatile
        private var instance: TunnelManager? = null

        fun get(context: Context): TunnelManager =
            instance ?: synchronized(this) {
                instance ?: TunnelManager(context.applicationContext).also { instance = it }
            }
    }
}
