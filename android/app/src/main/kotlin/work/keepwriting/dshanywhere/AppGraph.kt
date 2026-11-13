package work.keepwriting.dshanywhere

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import work.keepwriting.core.DialProbe
import work.keepwriting.core.HarnessConfig
import work.keepwriting.core.SetupCheck
import java.io.File

/**
 * 进程内单例装配点：持有配置目录（App 私有存储 filesDir/config）与配置状态。
 * 代理桥与更新状态属于进程而非 Activity：Activity 重建（深色模式、语言等配置变化）
 * 经 [get] 复用同一实例，不再起第二个代理桥（fix-android-update-lifecycle）。
 * 校验、剥离与三态读取都在 core 的 HarnessConfig/HarnessUrl（tech/android-client 的
 * pure-logic-in-core），这里只做框架装配与状态暴露。
 */
class AppGraph private constructor(context: Context) {
    enum class ConfigState { NeedsSetup, InvalidConfig, Ready }

    /** 内嵌 tailnet 隧道（可选；android-tstunnel 交付）。 */
    val tunnel = TunnelManager.get(context)

    /** 原生分流拨号（dsbot-native-shell）：tailnet 目标经内嵌节点 SOCKS5、其余直连。 */
    val nativeDial = NativeDial(tunnel)

    /** 原生会话（dsbot-api-client）：建会话/cookie 持久化/WebView 迁移与 gate 判定。 */
    val nativeSession = NativeSession(context.applicationContext) { h, p -> nativeDial.dial(h, p) }

    /** 自更新（android-self-update；经分流拨号访问自托管更新源）。 */
    val updates = UpdateManager(context) { h, p -> nativeDial.dial(h, p) }

    /** 诊断上报（diagnostics-reporting）：未配置端点时零行为。 */
    val diagnostics = DiagnosticsManager.get(context).also {
        // tailnet 端点经分流拨号上报（HttpSocket：域名由代理侧解析）
        it.attachDial { h, p -> nativeDial.dial(h, p) }
    }

    var configState by mutableStateOf(ConfigState.NeedsSetup)
        private set
    var cleanUrl by mutableStateOf<String?>(null)
        private set

    // 保存成功后待主页面加载一次的原始地址（含 token）：只留存内存，不落盘、不显示；
    // android-main-webview 到来后由主页面消费。
    private var pendingRawUrl: String? = null

    private val appContext: Context = context.applicationContext
    private val harnessConfig = HarnessConfig(File(context.filesDir, "config").toPath())

    fun load() {
        diagnostics.start(diagnosticsBuildInfo(appContext))
        nativeSession.load()
        when (val load = harnessConfig.load()) {
            HarnessConfig.Load.Missing -> configState = ConfigState.NeedsSetup
            HarnessConfig.Load.Invalid -> configState = ConfigState.InvalidConfig
            is HarnessConfig.Load.Ok -> {
                cleanUrl = load.url
                configState = ConfigState.Ready
            }
        }
    }

    /** 校验并保存：成功返回干净地址并把原始地址交给主页面加载一次；校验失败抛
     *  HarnessUrl.ValidationError 由界面显示原因，写入失败抛 IOException 由界面显示
     *  固定错误（均不含 token）。 */
    fun save(rawUrl: String): String {
        val clean = harnessConfig.save(rawUrl)
        cleanUrl = clean
        configState = ConfigState.Ready
        // 原始地址同样去首尾空白：输入法可能带入尾部空格，带进 java.net.URI 会直接
        // 抛 URISyntaxException（旧 WebView 路径能容忍、原生建会话不能）。
        pendingRawUrl = rawUrl.trim()
        return clean
    }

    /** 保存前验证（android-setup-verify；阻塞，只可在后台线程调用）：隧道经
     *  connectAndAwait，探测经与主页面相同的分流拨号（tailnet 目标经内嵌节点）。 */
    fun verifySetup(rawUrl: String, keyInput: String, onStage: (SetupCheck.Stage) -> Unit): SetupCheck.Result =
        SetupCheck.verify(
            rawUrl, keyInput, tunnel.state,
            connectTunnel = { key -> tunnel.connectAndAwait(key) },
            probe = { clean -> DialProbe.probe(clean, DialProbe.Dial { h, p -> nativeDial.dial(h, p) }) },
            onStage = onStage,
        )

    /** 主页面取走待加载的原始地址（一次性）。 */
    fun consumePendingRawUrl(): String? {
        val raw = pendingRawUrl
        pendingRawUrl = null
        return raw
    }

    /** 连接周期计数：设置页保存后递增，驱动原生外壳重新走连接流程（NativeShell 观察）。 */
    var reloadTick by mutableStateOf(0)
        private set

    /** 重读配置并请求原生外壳重新连接；配置失效时调用方应回到首次设置页。 */
    fun requestReload() {
        load()
        reloadTick++
    }

    companion object {
        /** 诊断报告的版本与设备信息（进程启动时读一次 PackageManager）。 */
        private fun diagnosticsBuildInfo(context: Context): DiagnosticsManager.BuildInfo = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            DiagnosticsManager.BuildInfo(
                versionName = info.versionName ?: "?",
                versionCode = (info.longVersionCode),
                device = android.os.Build.MODEL ?: "?",
                osVersion = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            )
        } catch (_: Exception) {
            DiagnosticsManager.BuildInfo("?", 0L, "?", "?")
        }

        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
    }
}
