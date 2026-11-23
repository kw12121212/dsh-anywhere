package work.keepwriting.dshanywhere

import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import work.keepwriting.core.PhonePrefs
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 入口 Activity：按配置状态路由——无有效配置进首次设置页，有效配置进原生骨架（dsbot-native-shell）。
 * 返回键优先级：骨架内导航后退 > 设置页返回骨架 > 系统默认（退出）。
 */
class MainActivity : ComponentActivity() {
    private enum class Screen { FirstRun, Main, Settings }

    private lateinit var graph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 进程内单例：Activity 重建复用在役代理桥与更新状态。
        graph = AppGraph.get(applicationContext)
        graph.load()
        // 隧道按需启动（未配置时不触碰 tsnet；已连接时幂等直返）——
        // 重启后凭持久状态自动重连（tunnel-reconnect-after-restart）。
        graph.tunnel.ensureUp()
        // 启动后静默检查更新（每进程一次，失败不打扰；android-self-update）。
        graph.updates.checkOnStartup()
        // 通知点击冷启动路径：消费 INSTALL_READY action（通知 → 点击 → 调起安装器）。
        // 只在首次创建时消费：界面重建（savedInstanceState 非空）与从最近任务重新进入
        // （启动意图仍是 INSTALL_READY）都不得再次调起安装器或授权页。
        if (savedInstanceState == null &&
            (intent.flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        ) {
            handleInstallReady(intent)
        }
        setContent {
            // 手机本机外观与语言（dsbot-phone-prefs）：明暗配色（Android 12+ 动态配色）与按语言本地化的资源
            val phone = graph.phone
            val dark = PhonePrefs.isDark(phone.appearance, androidx.compose.foundation.isSystemInDarkTheme())
            val baseContext = androidx.compose.ui.platform.LocalContext.current
            val scheme = when {
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ->
                    if (dark) androidx.compose.material3.dynamicDarkColorScheme(baseContext) else androidx.compose.material3.dynamicLightColorScheme(baseContext)
                dark -> androidx.compose.material3.darkColorScheme()
                else -> androidx.compose.material3.lightColorScheme()
            }
            // 系统栏跟随应用外观（而非主题父类或系统）：图标明暗随之切换，否则浅色页上白图标、深色页上黑图标不可读；
            // Android 15 以下不强制沉浸，栏底色同步为界面底色（15+ 栏透明、这两项设置无效）
            val barColor = scheme.background.toArgb()
            androidx.compose.runtime.SideEffect {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = barColor
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = barColor
                }
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            val lang = phone.lang
            val localized = remember(lang, baseContext) { localizedContext(baseContext, lang) }
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides localized) {
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember {
                        mutableStateOf(if (graph.configState == AppGraph.ConfigState.Ready) Screen.Main else Screen.FirstRun)
                    }
                    when (screen) {
                        Screen.FirstRun -> FirstRunScreen(graph, onComplete = { screen = Screen.Main })
                        else -> Box(modifier = Modifier.fillMaxSize()) {
                            // 外壳单实例、设置期间保持组合（叠加而非替换）：返回栈与连接
                            // 状态在设置期间保留，返回即回原骨架页面（settings-entry-reachable）。
                            NativeShell(
                                graph,
                                covered = screen == Screen.Settings,
                                onOpenSettings = { screen = Screen.Settings },
                                onNeedSetup = { screen = Screen.FirstRun },
                            )
                            if (screen == Screen.Settings) {
                                SettingsScreen(
                                    graph,
                                    onSaved = {
                                        // 保存成功：requestReload 重读磁盘并递增 reloadTick，
                                        // 外壳的 LaunchedEffect(reloadTick) 驱动重新走连接流程。
                                        graph.requestReload()
                                        screen = Screen.Main
                                    },
                                    onCancelled = { screen = Screen.Main },
                                )
                            }
                        }
                    }
                    BackHandler(enabled = screen == Screen.Settings) { screen = Screen.Main }
                }
            }
            }
        }
    }

    /** 按界面语言覆盖资源（values / values-en）的 Context：其余行为仍委托给 Activity。 */
    private fun localizedContext(base: android.content.Context, lang: work.keepwriting.core.ChatBars.Lang): android.content.Context {
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(if (lang == work.keepwriting.core.ChatBars.Lang.EN) java.util.Locale.ENGLISH else java.util.Locale.SIMPLIFIED_CHINESE)
        val res = base.createConfigurationContext(config).resources
        return object : android.content.ContextWrapper(base) {
            override fun getResources(): android.content.res.Resources = res
        }
    }

    override fun onStart() {
        super.onStart()
        graph.updates.appInForeground = true
    }

    override fun onStop() {
        super.onStop()
        graph.updates.appInForeground = false
        graph.updates.onLeftForeground()
        CookieManager.getInstance().flush()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleInstallReady(intent)
    }

    override fun onResume() {
        super.onResume()
        graph.phone.refreshLang() // 跟随系统时，系统语言可能在后台被改
        // 启动与回到前台不跳转任何权限页：「安装未知应用」只在调起安装器时按需引导，
        // 从授权页返回后由更新管理器决定是否调起（未授权不再跳转）；通知权限在设置页
        // 点击下载时至多请求一次。
        graph.updates.onResumed()
    }

    override fun onPause() {
        super.onPause()
        // 会话 cookie 持久化双保险：进入后台即落盘，强杀后 cookie 仍在
        // （另一处在 NativeSession 建会话成功后的原子写）。
        CookieManager.getInstance().flush()
    }

    private fun handleInstallReady(intent: android.content.Intent?) {
        if (intent?.action == UpdateManager.ACTION_INSTALL_READY) {
            graph.updates.promptInstall()
        }
    }
}
