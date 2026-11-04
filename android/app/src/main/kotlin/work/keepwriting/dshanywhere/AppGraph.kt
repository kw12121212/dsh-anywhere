package work.keepwriting.dshanywhere

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import work.keepwriting.core.HarnessConfig
import java.io.File

/**
 * 进程内单例装配点：持有配置目录（App 私有存储 filesDir/config）与配置状态。
 * 校验、剥离与三态读取都在 core 的 HarnessConfig/HarnessUrl（tech/android-client 的
 * pure-logic-in-core），这里只做框架装配与状态暴露。
 */
class AppGraph(context: Context) {
    enum class ConfigState { NeedsSetup, InvalidConfig, Ready }

    /** 内嵌 tailnet 隧道（可选；android-tstunnel 交付）。 */
    val tunnel = TunnelManager.get(context)

    /** 进程内代理桥（android-proxy-routing）：分流拨号与明文门控锚定当前配置。 */
    val proxyBridge = ProxyBridge(context.applicationContext, tunnel) { cleanUrl }

    /** 自更新（android-self-update）。 */
    val updates = UpdateManager(context)

    var configState by mutableStateOf(ConfigState.NeedsSetup)
        private set
    var cleanUrl by mutableStateOf<String?>(null)
        private set

    // 保存成功后待主页面加载一次的原始地址（含 token）：只留存内存，不落盘、不显示；
    // android-main-webview 到来后由主页面消费。
    private var pendingRawUrl: String? = null

    private val harnessConfig = HarnessConfig(File(context.filesDir, "config").toPath())

    fun load() {
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
        pendingRawUrl = rawUrl
        return clean
    }

    /** 主页面取走待加载的原始地址（一次性）。 */
    fun consumePendingRawUrl(): String? {
        val raw = pendingRawUrl
        pendingRawUrl = null
        return raw
    }

    /** 刷新计数：主页面（或设置页保存后）调用后递增，驱动 WebView 重新探测加载。 */
    var reloadTick by mutableStateOf(0)
        private set

    /** 重读配置并请求主页面重新加载；配置失效时调用方应回到首次设置页。 */
    fun requestReload() {
        load()
        reloadTick++
    }

    /** 软重载：不重读磁盘（状态机内部驱动用，如代理覆盖生效、隧道连上自动加载）。 */
    fun requestReloadSoft() {
        reloadTick++
    }
}
