package work.keepwriting.dshanywhere

import android.content.Intent
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import work.keepwriting.core.CleartextPolicy
import work.keepwriting.core.DialProbe
import work.keepwriting.core.OriginPolicy
import work.keepwriting.core.ProbeFailure
import work.keepwriting.core.SessionAuth
import work.keepwriting.core.SetupCheck
import work.keepwriting.core.TailnetRouting
import work.keepwriting.core.TunnelStateMachine
import java.io.ByteArrayInputStream

/**
 * 主页面：「正在连接」→探测→WebView（可达）/本地错误页（不可达）/重新认证页（会话失效）/
 * 隧道联动页（tailnet 目标且隧道未就绪）。WebView 经 ProxyController 指向进程内代理桥
 * （tailnet 目标经内嵌节点、其余直连），探测与会话判定经同一分流路径。
 */
@Composable
fun MainWebScreen(graph: AppGraph, onOpenSettings: () -> Unit, onNeedSetup: () -> Unit) {
    val context = LocalContext.current
    var phase by remember { mutableStateOf<Phase>(Phase.Connecting) }
    var canGoBack by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var reauthRetryDone by remember { mutableStateOf(false) }
    // 代理覆盖异步生效：回调确认之后才加载页面，否则首个请求不经桥。
    var proxyReady by remember { mutableStateOf(false) }
    val proxySupported = remember { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) }
    val tunnel = graph.tunnel

    // 探测拨号：桥在役走分流（tailnet 经节点），否则平台直连（非 tailnet 行为一致）。
    val probeDial: () -> DialProbe.Dial = {
        DialProbe.Dial { host, port ->
            if (graph.proxyBridge.port >= 0) graph.proxyBridge.dialForBridge(host, port)
            else TailnetRouting.directConnect(host, port)
        }
    }

    // 探测与加载状态机：reloadTick（刷新/设置页保存）与 cleanUrl 变化都重新走一遍。
    LaunchedEffect(graph.reloadTick, graph.cleanUrl) {
        if (graph.configState != AppGraph.ConfigState.Ready) {
            onNeedSetup()
            return@LaunchedEffect
        }
        val clean = graph.cleanUrl ?: return@LaunchedEffect
        reauthRetryDone = false

        // 隧道联动：tailnet 目标且隧道未就绪时给出本地面板，不加载网页。
        val host = HarnessUrl_hostOf(clean)
        if (TailnetRouting.isTailnetTarget(host) && tunnel.state != TunnelStateMachine.State.CONNECTED) {
            phase = if (tunnel.state == TunnelStateMachine.State.NOT_CONFIGURED) Phase.TunnelPrompt else Phase.TunnelWait
            return@LaunchedEffect
        }
        if (!proxySupported) {
            phase = Phase.ProxyUnsupported
            return@LaunchedEffect
        }

        phase = Phase.Connecting
        // 隧道在役时确保桥在役（幂等）。
        withContext(Dispatchers.IO) { graph.proxyBridge.ensureStarted() }
        // 代理覆盖未生效时先等（桥端口就绪触发 DisposableEffect 应用覆盖并重载）。
        if (graph.proxyBridge.port >= 0 && !proxyReady) return@LaunchedEffect

        val dial = probeDial()
        val result = withContext(Dispatchers.IO) { DialProbe.probe(clean, dial) }
        phase = when (result) {
            DialProbe.ProbeResult.Reachable -> {
                Phase.Content(clean, graph.consumePendingRawUrl() ?: clean)
            }
            is DialProbe.ProbeResult.Unreachable -> {
                // 错误态报告（diagnostics-reporting）：未配置时 manager 内部零行为。
                graph.diagnostics.recordLoadError(clean, result.reason)
                Phase.Error(clean, result.reason)
            }
        }
    }

    // 隧道状态变化驱动：等待隧道时连接成功自动重载；隧道跌落时停桥。
    LaunchedEffect(tunnel.state) {
        if (tunnel.state == TunnelStateMachine.State.CONNECTED &&
            (phase is Phase.TunnelPrompt || phase is Phase.TunnelWait)
        ) {
            graph.requestReloadSoft()
        }
        if (tunnel.state != TunnelStateMachine.State.CONNECTED) {
            withContext(Dispatchers.IO) { graph.proxyBridge.stop() }
        }
    }

    // 代理覆盖：桥端口就绪后异步应用，确认后触发一次重载。
    DisposableEffect(graph.proxyBridge.port) {
        val port = graph.proxyBridge.port
        val controller = if (port >= 0 && proxySupported) ProxyController.getInstance() else null
        if (controller != null) {
            // 去掉隐式回环直连规则：回环目标也进桥被拒，127.0.0.1 上的质询只可能来自桥。
            val cfg = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").removeImplicitRules().build()
            controller.setProxyOverride(cfg, ContextCompat.getMainExecutor(context)) {
                proxyReady = true
                graph.requestReloadSoft()
            }
        }
        onDispose {
            proxyReady = false
            controller?.clearProxyOverride(ContextCompat.getMainExecutor(context)) {}
        }
    }

    // 返回键：网页可后退时先在网页历史后退，否则交回系统默认（退出 App）。
    BackHandler(enabled = phase is Phase.Content && canGoBack) {
        webView?.goBack()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.main_open_settings)) }
                TextButton(onClick = { graph.requestReload() }) { Text(stringResource(R.string.main_refresh)) }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when (val current = phase) {
                    Phase.Connecting -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.main_connecting), style = MaterialTheme.typography.bodyMedium)
                    }

                    is Phase.Error -> ErrorPage(
                        url = current.url,
                        reasonText = stringResource(reasonMessage(current.reason)),
                        hintText = stringResource(
                            when (SetupCheck.errorHint(current.url)) {
                                SetupCheck.Hint.CHECK_TAILSCALE -> R.string.error_hint_tailscale
                                SetupCheck.Hint.CHECK_ADDRESS -> R.string.error_hint_address
                            },
                        ),
                        onRetry = { graph.requestReload() },
                        onChangeAddress = onOpenSettings,
                    )

                    Phase.Reauth -> ReauthPage(onOpenSettings = onOpenSettings)

                    Phase.TunnelPrompt -> TunnelPanel(
                        title = stringResource(R.string.tunnel_prompt_title),
                        detail = stringResource(R.string.tunnel_prompt_detail),
                        hint = "",
                        showRetry = false,
                        onOpenSettings = onOpenSettings,
                        onRetry = {},
                    )

                    Phase.TunnelWait -> TunnelPanel(
                        title = stringResource(R.string.tunnel_wait_title, tunnelStateText(tunnel.state)),
                        detail = tunnel.failureReason,
                        hint = stringResource(R.string.tunnel_wait_hint),
                        showRetry = true,
                        onOpenSettings = onOpenSettings,
                        onRetry = { tunnel.ensureUp() },
                    )

                    Phase.ProxyUnsupported -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.proxy_unsupported), style = MaterialTheme.typography.bodyMedium)
                    }

                    is Phase.Content -> HarnessWebView(
                        harnessUrl = current.cleanUrl,
                        initialUrl = current.initialUrl,
                        bridgePort = graph.proxyBridge.port,
                        bridgeCredentials = graph.proxyBridge.credentials,
                        onWebViewReady = { w -> webView = w },
                        onCanGoBackChange = { canGoBack = it },
                        onExternal = { target ->
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target)))
                            }
                        },
                        onSessionExpiredConfirmed = {
                            if (!reauthRetryDone) {
                                reauthRetryDone = true
                                webView?.reload()
                            } else {
                                phase = Phase.Reauth
                            }
                        },
                        probeDial = probeDial,
                    )
                }
            }
        }
    }
}

/** 从干净地址提取主机名（供 tailnet 判定；解析失败返回空串）。 */
internal fun HarnessUrl_hostOf(url: String): String = try {
    java.net.URI(url).host ?: ""
} catch (_: Exception) {
    ""
}

private sealed interface Phase {
    data object Connecting : Phase

    /** cleanUrl：判定与策略的基准源；initialUrl：本次初始加载地址（保存后为带 token 原始地址）。 */
    data class Content(val cleanUrl: String, val initialUrl: String) : Phase
    data class Error(val url: String, val reason: ProbeFailure.Reason) : Phase
    data object Reauth : Phase
    data object TunnelPrompt : Phase
    data object TunnelWait : Phase
    data object ProxyUnsupported : Phase
}

@Composable
internal fun tunnelStateText(state: TunnelStateMachine.State): String = when (state) {
    TunnelStateMachine.State.NOT_CONFIGURED -> stringResource(R.string.tunnel_state_not_configured)
    TunnelStateMachine.State.CONNECTING -> stringResource(R.string.tunnel_state_connecting)
    TunnelStateMachine.State.CONNECTED -> stringResource(R.string.tunnel_state_connected)
    TunnelStateMachine.State.NEEDS_KEY -> stringResource(R.string.tunnel_state_needs_key)
    TunnelStateMachine.State.FAILED -> stringResource(R.string.tunnel_state_failed)
}

@Composable
private fun TunnelPanel(
    title: String,
    detail: String,
    hint: String,
    showRetry: Boolean,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
        if (hint.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(hint, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.reauth_open_settings)) }
            if (showRetry) {
                Button(onClick = onRetry) { Text(stringResource(R.string.tunnel_retry_connect)) }
            }
        }
    }
}

@Composable
private fun ErrorPage(url: String, reasonText: String, hintText: String, onRetry: () -> Unit, onChangeAddress: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(reasonText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text(url, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(hintText, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onChangeAddress) { Text(stringResource(R.string.error_change_address)) }
            Button(onClick = onRetry) { Text(stringResource(R.string.error_retry)) }
        }
    }
}

@Composable
private fun ReauthPage(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.reauth_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.reauth_hint), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenSettings) { Text(stringResource(R.string.reauth_open_settings)) }
    }
}

/** 挂载并配置承载 harness 的 WebView：代理认证应答、导航范围、明文拦截与会话链路。 */
@Composable
private fun HarnessWebView(
    harnessUrl: String,
    initialUrl: String,
    bridgePort: Int,
    bridgeCredentials: Pair<String, String>?,
    onWebViewReady: (WebView) -> Unit,
    onCanGoBackChange: (Boolean) -> Unit,
    onExternal: (String) -> Unit,
    onSessionExpiredConfirmed: () -> Unit,
    probeDial: () -> DialProbe.Dial,
) {
    val context = LocalContext.current
    val webView = remember(initialUrl, bridgePort) {
        createHarnessWebView(
            context, harnessUrl, bridgePort, bridgeCredentials, onCanGoBackChange, onExternal,
            onSessionExpiredConfirmed, probeDial,
        )
    }
    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }
    AndroidView(
        factory = {
            webView.apply {
                onWebViewReady(this)
                loadUrl(initialUrl)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** WebView 工厂：安全基线 + 导航范围 + 明文拦截 + 代理认证应答 + 会话链路。 */
private fun createHarnessWebView(
    context: android.content.Context,
    harnessUrl: String,
    bridgePort: Int,
    bridgeCredentials: Pair<String, String>?,
    onCanGoBackChange: (Boolean) -> Unit,
    onExternal: (String) -> Unit,
    onSessionExpiredConfirmed: () -> Unit,
    probeDial: () -> DialProbe.Dial,
): WebView {
    val webView = WebView(context)
    val cookieManager = CookieManager.getInstance()
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        setSupportMultipleWindows(true)
    }
    cookieManager.setAcceptCookie(true)

    fun handleTarget(target: String): Boolean = when (OriginPolicy.classifyOpen(harnessUrl, target)) {
        OriginPolicy.OpenAction.STAY -> false
        OriginPolicy.OpenAction.EXTERNAL -> {
            onExternal(target)
            true
        }
        OriginPolicy.OpenAction.IGNORE -> true
    }

    /** App 侧带 cookie 探测干净地址（经分流路径）：401 且正文为固定文本才会话失效。 */
    fun confirmSessionExpired() {
        Thread {
            val expired = DialProbe.isSessionExpired(
                harnessUrl,
                cookieManager.getCookie(harnessUrl),
                probeDial(),
            )
            if (expired) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { onSessionExpiredConfirmed() }
            }
        }.start()
    }

    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleTarget(request.url.toString())

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // 明文收紧（纵深防御；桥层已门控，此处覆盖不经桥的路径）。
            if (!CleartextPolicy.isAllowed(harnessUrl, request.url.toString())) {
                return WebResourceResponse(
                    "text/plain", "utf-8", 502, "Cleartext Blocked", emptyMap(),
                    ByteArrayInputStream(ByteArray(0)),
                )
            }
            return null
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String,
            realm: String,
        ) {
            // 只应答本进程代理桥的质询（127.0.0.1 且凭据在役）；其他来源一律取消。
            if (host == "127.0.0.1" && bridgeCredentials != null && bridgePort >= 0) {
                handler.proceed(bridgeCredentials.first, bridgeCredentials.second)
            } else {
                handler.cancel()
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            onCanGoBackChange(view.canGoBack())
        }

        override fun onPageFinished(view: WebView, url: String) {
            onCanGoBackChange(view.canGoBack())
            val list = view.copyBackForwardList()
            val urls = (0 until list.size).map { list.getItemAtIndex(it).url }
            if (SessionAuth.hasTokenInHistory(urls, list.currentIndex)) {
                view.clearHistory()
                onCanGoBackChange(view.canGoBack())
            }
            cookieManager.flush()
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (request.isForMainFrame && errorResponse.statusCode == 401) {
                confirmSessionExpired()
            }
        }
    }
    webView.webChromeClient = object : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message,
        ): Boolean {
            val transient = WebView(view.context)
            transient.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    when (OriginPolicy.classifyOpen(harnessUrl, target)) {
                        OriginPolicy.OpenAction.STAY -> view.post { view.loadUrl(target) }
                        OriginPolicy.OpenAction.EXTERNAL -> onExternal(target)
                        OriginPolicy.OpenAction.IGNORE -> {}
                    }
                    return true
                }
            }
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = transient
            resultMsg.sendToTarget()
            return true
        }
    }
    return webView
}

internal fun reasonMessage(reason: ProbeFailure.Reason): Int = when (reason) {
    ProbeFailure.Reason.DNS -> R.string.error_reason_dns
    ProbeFailure.Reason.REFUSED -> R.string.error_reason_refused
    ProbeFailure.Reason.TIMEOUT -> R.string.error_reason_timeout
    ProbeFailure.Reason.TLS -> R.string.error_reason_tls
    ProbeFailure.Reason.OTHER -> R.string.error_reason_other
}
