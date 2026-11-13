package work.keepwriting.dshanywhere

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import work.keepwriting.core.AnrDetection
import work.keepwriting.core.DiagBatching
import work.keepwriting.core.DiagEndpoint
import work.keepwriting.core.DiagEndpointConfig
import work.keepwriting.core.DiagErrorDedup
import work.keepwriting.core.DiagJson
import work.keepwriting.core.DiagKinds
import work.keepwriting.core.DiagLimits
import work.keepwriting.core.DiagQueue
import work.keepwriting.core.DiagReport
import work.keepwriting.core.DiagText
import work.keepwriting.core.ProbeFailure
import work.keepwriting.core.Socks5Dialer
import work.keepwriting.core.TailnetRouting
import work.keepwriting.core.TunnelStateMachine
import work.keepwriting.core.diagTimestamp
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * 诊断上报装配（diagnostics-reporting 规格）：崩溃类捕获（未捕获异常链回系统默认处理、
 * ANR 看门狗）与错误态捕获（加载失败按原因分类、隧道失败按原因分类，同会话同类一次），
 * 报告经 core 的构造/脱敏/有界队列组包后发送到用户配置的 pca 端点。发送分流复用
 * TailnetRouting 判定：tailnet 端点经内嵌节点 SOCKS5 + TLS 包装，其余直连；失败静默
 * 入队、下次启动或下次错误触发点重试；4xx 丢弃；任何路径不得阻塞界面或触发隧道启动。
 * 未配置端点时零行为：不产生、不保存、不发送任何报告。
 */
class DiagnosticsManager private constructor(private val appContext: Context) {
    /** 已配置的规范化端点；null 为未配置（设置卡片显示用）。 */
    var endpoint by mutableStateOf<String?>(null)
        private set

    private val endpointConfig = DiagEndpointConfig(File(appContext.filesDir, "config").toPath())
    private val queue = DiagQueue(File(appContext.filesDir, "diagnostics-queue").toPath())
    private val dedup = DiagErrorDedup()
    private val worker = HandlerThread("dsh-diag").apply { start() }
    private val handler = Handler(worker.looper)
    @Volatile
    private var sendInFlight = false
    @Volatile
    private var captureInstalled = false
    @Volatile
    private var anrReported = false

    /** 主线程最近一次心跳时间（看门狗线程与主线程共享）。 */
    @Volatile
    private var mainTickMs = 0L

    /** 版本与设备信息（进程启动时注入一次；core 保持纯 JVM 不依赖 android.*）。 */
    data class BuildInfo(val versionName: String, val versionCode: Long, val device: String, val osVersion: String)

    @Volatile
    private var buildInfo = BuildInfo("?", 0L, "?", "?")

    /** 启动时装载：读端点配置、注入构建信息；已配置时安装捕获器并补报待发队列。 */
    fun start(info: BuildInfo) {
        buildInfo = info
        when (val load = endpointConfig.load()) {
            is DiagEndpointConfig.Load.Ok -> {
                endpoint = load.endpoint
                installCapture()
                triggerSend()
            }
            DiagEndpointConfig.Load.Missing -> {}
            DiagEndpointConfig.Load.Invalid -> {} // 配置损坏视同未配置：零行为，用户可重新保存
        }
    }

    /** 校验并保存端点（设置卡片）；返回规范化端点，非法返回 null 且不修改配置。 */
    fun saveEndpoint(raw: String): String? {
        val normalized = try {
            endpointConfig.save(raw)
        } catch (_: IllegalArgumentException) {
            return null
        }
        endpoint = normalized
        installCapture()
        triggerSend()
        return normalized
    }

    /** 清除端点与待发队列，回到未配置（此后不再有任何上报行为）。 */
    fun clearEndpoint() {
        endpointConfig.clear()
        queue.clear()
        endpoint = null
    }

    /** 主页面显示本地错误页时的错误态报告（同会话同原因一次）。 */
    fun recordLoadError(cleanUrl: String, reason: ProbeFailure.Reason) {
        if (endpoint == null) return
        if (dedup.seen("load:${reason.name}")) return
        // 去重在调用方（内存级，无 I/O）；落盘与发送移到 worker 线程，不在主线程做文件 I/O。
        val threadName = Thread.currentThread().name
        val message = "harness 加载失败（${reasonText(reason)}）：${work.keepwriting.core.HarnessUrl.redact(cleanUrl)}"
        handler.post { enqueue(DiagKinds.ERROR, message, stackTrace = "", threadName = threadName) }
    }

    /** 隧道进入失败/需要 key 状态时的错误态报告（同会话同原因一次；reason 已脱敏）。 */
    fun recordTunnelFailure(state: TunnelStateMachine.State, sanitizedReason: String) {
        if (endpoint == null) return
        if (state != TunnelStateMachine.State.FAILED && state != TunnelStateMachine.State.NEEDS_KEY) return
        val key = "tunnel:" + sanitizedReason.substringBefore('\n').take(60)
        if (dedup.seen(key)) return
        val threadName = Thread.currentThread().name
        val message =
            "隧道${if (state == TunnelStateMachine.State.NEEDS_KEY) "需要重新输入 key" else "连接失败"}：$sanitizedReason"
        handler.post { enqueue(DiagKinds.ERROR, message, stackTrace = "", threadName = threadName) }
    }

    // ---- 内部：构造、入队与发送 ----

    private fun enqueue(kind: String, message: String, stackTrace: String, threadName: String = Thread.currentThread().name) {
        queue.add(
            DiagReport(
                id = UUID.randomUUID().toString(),
                kind = kind,
                occurredAt = diagTimestamp(System.currentTimeMillis()),
                appVersionName = buildInfo.versionName,
                appVersionCode = buildInfo.versionCode,
                device = buildInfo.device,
                osVersion = buildInfo.osVersion,
                thread = threadName,
                message = DiagText.truncateBytes(DiagText.sanitize(message), DiagLimits.MAX_MESSAGE_BYTES),
                stackTrace = DiagText.truncateBytes(DiagText.sanitize(stackTrace), DiagLimits.MAX_STACK_BYTES),
            )
        )
        triggerSend()
    }

    /** 崩溃类：未捕获异常处理器里同步落盘（进程即将终止，不做网络发送）。 */
    private fun installCapture() {
        if (captureInstalled) return
        captureInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                if (endpoint != null && buildInfo.versionName != "?") {
                    queue.add(crashReport(threadName = t.name, e = e))
                }
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
        startAnrWatchdog()
    }

    private fun crashReport(threadName: String, e: Throwable): DiagReport =
        DiagReport(
            id = UUID.randomUUID().toString(),
            kind = DiagKinds.CRASH,
            occurredAt = diagTimestamp(System.currentTimeMillis()),
            appVersionName = buildInfo.versionName,
            appVersionCode = buildInfo.versionCode,
            device = buildInfo.device,
            osVersion = buildInfo.osVersion,
            thread = threadName,
            message = DiagText.truncateBytes(DiagText.sanitize(e.toString()), DiagLimits.MAX_MESSAGE_BYTES),
            stackTrace = DiagText.truncateBytes(
                DiagText.sanitize(android.util.Log.getStackTraceString(e)),
                DiagLimits.MAX_STACK_BYTES,
            ),
        )

    /** ANR 看门狗：判定在 core 的 AnrDetection（post 前后观察值比较）；发现无响应时
     *  从 Thread.getAllStackTraces() 提取主线程当前堆栈——ANR 排障最关键的字段。 */
    private fun startAnrWatchdog() {
        val main = Handler(Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread
        val tick = object : Runnable {
            override fun run() {
                mainTickMs = System.currentTimeMillis()
                anrReported = false
            }
        }
        main.post(tick)
        Thread({
            while (true) {
                Thread.sleep(AnrDetection.DEFAULT_INTERVAL_MS)
                if (endpoint == null) continue
                val observed = mainTickMs
                main.post(tick)
                Thread.sleep(AnrDetection.DEFAULT_GRACE_MS)
                if (AnrDetection.isUnresponsive(observed, mainTickMs) && !anrReported) {
                    anrReported = true
                    try {
                        val stack = Thread.getAllStackTraces()[mainThread]
                            ?.joinToString("\n") { it.toString() }
                            .orEmpty()
                        queue.add(
                            DiagReport(
                                id = UUID.randomUUID().toString(),
                                kind = DiagKinds.ANR,
                                occurredAt = diagTimestamp(System.currentTimeMillis()),
                                appVersionName = buildInfo.versionName,
                                appVersionCode = buildInfo.versionCode,
                                device = buildInfo.device,
                                osVersion = buildInfo.osVersion,
                                thread = "main",
                                message = DiagText.truncateBytes(
                                    "主线程无响应超过 ${AnrDetection.DEFAULT_GRACE_MS / 1000} 秒",
                                    DiagLimits.MAX_MESSAGE_BYTES,
                                ),
                                stackTrace = DiagText.truncateBytes(
                                    DiagText.sanitize(stack),
                                    DiagLimits.MAX_STACK_BYTES,
                                ),
                            )
                        )
                        triggerSend()
                    } catch (_: Throwable) {
                    }
                }
            }
        }, "dsh-anr-watchdog").apply { isDaemon = true }.start()
    }

    private fun triggerSend() {
        handler.post(this::sendRound)
    }

    /** 一轮发送：分批发送待发队列；网络失败保留剩余并结束本轮，4xx 丢弃该批。 */
    private fun sendRound() {
        if (endpoint == null || sendInFlight) return
        sendInFlight = true
        try {
            val configured = (endpointConfig.load() as? DiagEndpointConfig.Load.Ok)?.endpoint ?: return
            val items = queue.load()
            for (batch in DiagBatching.batches(items)) {
                when (postReports(configured, DiagJson.encodeBatch(batch.map { it.report }))) {
                    SendOutcome.DELIVERED, SendOutcome.REJECTED -> queue.remove(batch.map { it.name })
                    SendOutcome.FAILED -> return
                }
            }
        } catch (_: Throwable) {
        } finally {
            sendInFlight = false
        }
    }

    private enum class SendOutcome { DELIVERED, REJECTED, FAILED }

    /** 分流拨号（AppGraph 装配时注入）：tailnet 端点必须经它上报——Android
     *  HttpURLConnection 会先本地解析目标主机，ts.net 公网不可解析（曾长期静默失败）。 */
    @Volatile
    private var dial: ((String, Int) -> java.net.Socket)? = null

    fun attachDial(dial: (String, Int) -> java.net.Socket) {
        this.dial = dial
    }

    /** POST 报告批次：有注入拨号时经 HttpSocket（域名由代理侧解析）；否则退回
     *  URLConnection（非 tailnet 端点可用；tailnet 端点按失败入队待补发）。 */
    private fun postReports(configuredEndpoint: String, body: String): SendOutcome {
        val url = configuredEndpoint.trimEnd('/') + "/api/diagnostics/reports"
        val dialFn = dial
        if (dialFn != null) {
            val res = runCatching {
                work.keepwriting.core.HttpSocket(dialFn).request(
                    "POST", url,
                    listOf("Content-Type" to "application/json; charset=utf-8"),
                    body.toByteArray(Charsets.UTF_8),
                )
            }.getOrNull()
            return when (res?.status) {
                null -> SendOutcome.FAILED
                in 200..299 -> SendOutcome.DELIVERED
                in 400..499 -> SendOutcome.REJECTED
                else -> SendOutcome.FAILED
            }
        }
        var connection: HttpsURLConnection? = null
        return try {
            val parsed = URI(url).toURL()
            val conn = parsed.openConnection() as HttpsURLConnection
            connection = conn
            if (TailnetRouting.isTailnetTarget(parsed.host)) {
                val endpoint = TunnelManager.get(appContext).socksEndpoint ?: return SendOutcome.FAILED
                conn.sslSocketFactory = socksTlsFactory(endpoint.addr, endpoint.credential)
            }
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            when (conn.responseCode) {
                in 200..299 -> SendOutcome.DELIVERED
                in 400..499 -> SendOutcome.REJECTED
                else -> SendOutcome.FAILED
            }
        } catch (_: Throwable) {
            SendOutcome.FAILED
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** 经内嵌节点 SOCKS5 的 TLS 套接字工厂：域名字节交给代理侧解析（ts.net 走 MagicDNS）。 */
    private fun socksTlsFactory(socksAddr: String, credential: String): SSLSocketFactory {
        val host = socksAddr.substringBefore(':')
        val port = socksAddr.substringAfter(':').toInt()
        val dialer = Socks5Dialer(host, port, "tsnet", credential)
        val delegate = HttpsURLConnection.getDefaultSSLSocketFactory()
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

    private fun reasonText(reason: ProbeFailure.Reason): String = when (reason) {
        ProbeFailure.Reason.DNS -> "无法解析主机名"
        ProbeFailure.Reason.REFUSED -> "连接被拒绝"
        ProbeFailure.Reason.TIMEOUT -> "连接超时"
        ProbeFailure.Reason.TLS -> "安全连接失败"
        ProbeFailure.Reason.OTHER -> "连接失败"
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000

        @Volatile
        private var instance: DiagnosticsManager? = null

        fun get(context: Context): DiagnosticsManager =
            instance ?: synchronized(this) {
                instance ?: DiagnosticsManager(context.applicationContext).also { instance = it }
            }
    }
}
