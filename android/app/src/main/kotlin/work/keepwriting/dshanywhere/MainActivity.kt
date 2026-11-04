package work.keepwriting.dshanywhere

import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 入口 Activity：按配置状态路由——无有效配置进首次设置页，有效配置进 WebView 主页面。
 * 返回键优先级：主页面网页后退 > 设置页返回主页面 > 系统默认（退出）。
 */
class MainActivity : ComponentActivity() {
    private enum class Screen { FirstRun, Main, Settings }

    private lateinit var graph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = AppGraph(applicationContext)
        graph.load()
        // 隧道按需启动（未配置时不触碰 tsnet；已连接时幂等直返）——
        // 重启后凭持久状态自动重连（tunnel-reconnect-after-restart）。
        graph.tunnel.ensureUp()
        // 启动后静默检查更新（失败不打扰；android-self-update）。
        graph.updates.check()
        // 通知点击冷启动路径：消费 INSTALL_READY action（通知 → 点击 → 调起安装器）。
        handleInstallReady(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember {
                        mutableStateOf(if (graph.configState == AppGraph.ConfigState.Ready) Screen.Main else Screen.FirstRun)
                    }
                    when (screen) {
                        Screen.FirstRun -> FirstRunScreen(graph, onComplete = { screen = Screen.Main })
                        Screen.Main -> MainWebScreen(
                            graph,
                            onOpenSettings = { screen = Screen.Settings },
                            onNeedSetup = { screen = Screen.FirstRun },
                        )
                        Screen.Settings -> SettingsScreen(
                            graph,
                            onSaved = {
                                // 保存成功：回主页面并触发重新探测加载（save 已更新内存态，
                                // requestReload 重读磁盘并递增 reloadTick 驱动主页面）。
                                graph.requestReload()
                                screen = Screen.Main
                            },
                            onCancelled = { screen = Screen.Main },
                        )
                    }
                    BackHandler(enabled = screen == Screen.Settings) { screen = Screen.Main }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        graph.updates.appInForeground = true
    }

    override fun onStop() {
        super.onStop()
        graph.updates.appInForeground = false
        CookieManager.getInstance().flush()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleInstallReady(intent)
    }

    override fun onResume() {
        super.onResume()
        // POST_NOTIFICATIONS（API 33+）运行时请求：后台更新就绪通知依赖它。
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4301)
            }
        }
        // REQUEST_INSTALL_PACKAGES 引导（design 承诺）：未授权「安装未知应用」时跳系统设置。
        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            runCatching {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 会话 cookie 持久化双保险：进入后台即落盘，强杀后 cookie 仍在
        // （另一处在 WebView onPageFinished 的会话建立点）。
        CookieManager.getInstance().flush()
    }

    private fun handleInstallReady(intent: android.content.Intent?) {
        if (intent?.action == UpdateManager.ACTION_INSTALL_READY) {
            graph.updates.promptInstall()
        }
    }
}
