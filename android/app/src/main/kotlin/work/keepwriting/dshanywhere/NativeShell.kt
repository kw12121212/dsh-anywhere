package work.keepwriting.dshanywhere

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.SetupCheck
import work.keepwriting.core.TeamHome
import work.keepwriting.core.DsbotConnection
import work.keepwriting.core.DsbotModels
import work.keepwriting.core.DsbotVersions
import work.keepwriting.core.DialProbe
import work.keepwriting.core.FeedSync
import work.keepwriting.core.ProbeFailure
import work.keepwriting.core.TailnetRouting
import work.keepwriting.core.TunnelStateMachine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 原生导航外壳（dsbot-native-shell）：单 Activity 自管返回栈（团队页→私聊/群聊→
 * 详情，页面为骨架占位），顶部设置入口；全局连接状态条与版本提示条（可本次关闭）；
 * harness 不可达、会话失效、403、隧道未就绪各有本地页面。连接编排经 core 的
 * DsbotConnection（探测/401 重试一次/gate/feed 退避重连/前后台生命周期）。
 */
@Composable
fun NativeShell(graph: AppGraph, onOpenSettings: () -> Unit, onNeedSetup: () -> Unit) {
    val connection = remember { NativeShellConnection(graph) }
    // 前后台：生命周期事件驱动状态机（后台断 feed、回前台重走连接）
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> connection.onForeground()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> connection.onBackground()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        connection.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            connection.stop()
        }
    }
    ShellScaffold(connection = connection, onOpenSettings = onOpenSettings)
    // 隧道联动（tunnel-gated-tailnet-load）：tailnet 目标在等待面板下、隧道连上后自动继续
    val tunnelState = graph.tunnel.state
    LaunchedEffect(tunnelState) {
        val clean = graph.cleanUrl ?: return@LaunchedEffect
        val host = try {
            java.net.URI(clean).host ?: ""
        } catch (_: Exception) {
            ""
        }
        if (tunnelState == TunnelStateMachine.State.CONNECTED && TailnetRouting.isTailnetTarget(host)) {
            connection.retry()
        }
    }
    // 设置页保存（requestReload 递增 reloadTick）后重新走连接流程
    LaunchedEffect(graph.reloadTick) {
        if (graph.reloadTick > 0) connection.retry()
    }
    BackHandler(enabled = connection.page !is ShellPage.Team) { connection.goBack() }
    LaunchedEffect(graph.configState) {
        if (graph.configState != AppGraph.ConfigState.Ready) onNeedSetup()
    }
}

/** 返回栈页面：团队页为根；聊天页带目标 id；详情面板带目标 id。 */
sealed interface ShellPage {
    data object Team : ShellPage
    data object NeedsYou : ShellPage
    data class Chat(val targetId: String) : ShellPage
    data class Details(val targetId: String) : ShellPage
}

@Composable
private fun ShellScaffold(connection: NativeShellConnection, onOpenSettings: () -> Unit) {
    var showSidePanel by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.main_open_settings)) }
            TextButton(onClick = { connection.retry() }) { Text(stringResource(R.string.main_refresh)) }
        }
        StatusBar(connection.status)
        val banner = connection.versionBanner
        if (banner != null && !connection.versionBannerDismissed) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.version_banner_mismatch, banner.first, banner.second),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { connection.dismissVersionBanner() }) {
                    Text(stringResource(R.string.version_banner_dismiss), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            val current: ShellPage = connection.page
            when {
                connection.tunnelPanel != null -> {
                    val panel = connection.tunnelPanel!!
                    TunnelGatePanel(
                        title = panel.titleArg?.let { stringResource(panel.titleRes, it) }
                            ?: stringResource(panel.titleRes),
                        detail = panel.detail,
                        hint = stringResource(panel.hintRes),
                        showRetry = panel.showRetry,
                        onOpenSettings = onOpenSettings,
                        onRetryTunnel = { connection.retryTunnel() },
                    )
                }
                connection.errorMessage != null -> NativeErrorPage(
                    url = connection.cleanUrlText,
                    message = connection.errorMessage!!,
                    hintText = stringResource(
                        when (SetupCheck.errorHint(connection.cleanUrlText)) {
                            SetupCheck.Hint.CHECK_TAILSCALE -> R.string.error_hint_tailscale
                            SetupCheck.Hint.CHECK_ADDRESS -> R.string.error_hint_address
                        },
                    ),
                    onRetry = { connection.retry() },
                    onChangeAddress = onOpenSettings,
                )
                connection.status == DsbotConnection.Status.AUTH_EXPIRED -> ReauthPanel(onOpenSettings)
                connection.status == DsbotConnection.Status.FORBIDDEN_HOST -> ForbiddenPanel(onOpenSettings)
                current is ShellPage.Chat -> {
                    // working/parts 联动：state 刷新的 Entry 字段同步进控制器与分段入口；
                    // 群（ROOM）走群聊页，Bot 走私聊页
                    val entry = connection.team?.sections
                        ?.flatMap { it.entries }
                        ?.firstOrNull { it.id == current.targetId }
                    if (entry?.kind == TeamHome.Entry.Kind.ROOM) {
                        // 成员名册：room.members（bot id）→ 团队 Bot 名映射（提及/停止成员入口）
                        val botNames = connection.team?.sections.orEmpty()
                            .flatMap { it.entries }
                            .filter { it.kind == TeamHome.Entry.Kind.BOT }
                            .associate { it.id to it.name }
                        // 成对构造（id 与名一一对应——避免 mapNotNull 丢项后 zip 错位停错 bot）
                        val roster = entry.members.mapNotNull { id -> botNames[id]?.let { id to it } }
                        RoomChatPage(
                            controller = connection.roomChatController,
                            roomId = current.targetId,
                            entry = entry,
                            memberNames = roster.map { it.second },
                            memberIds = roster.map { it.first },
                        )
                    } else {
                        LaunchedEffect(entry?.working) { connection.chatController.syncWorking(entry?.working ?: false) }
                        BotChatPage(
                            controller = connection.chatController,
                            botId = current.targetId,
                            parts = entry?.parts ?: emptyList(),
                            onOpenSide = {
                                val sid = entry?.sessionId
                                if (sid != null) {
                                    // 主聊天快照来源：主聊天 rows 尾部文本条目（side-ask 附最近消息）
                                    connection.sideChatController.snapshotProvider = {
                                        connection.chatController.rows
                                            .mapNotNull { r -> (r.item as? work.keepwriting.core.ChatRender.Item.Message)?.let { m -> m.fromUser to m.markdown } }
                                    }
                                    connection.sideChatController.open(sid)
                                    showSidePanel = true
                                } else {
                                    connection.sideChatController.postNoticeStatic("会话信息不可用，无法侧问")
                                }
                            },
                        )
                    }
                }
                current is ShellPage.Details -> SkeletonPage("details:${current.targetId}")
                current is ShellPage.NeedsYou -> NeedsYouPage(connection.team?.pending ?: emptyList()) { id -> connection.openChat(id ?: "unknown") }
                else -> TeamPage(
                    team = connection.team,
                    onOpenChat = { id -> connection.openChat(id ?: "unknown") },
                    onOpenNeedsYou = { connection.openNeedsYou() },
                )
            }
        }
        if (showSidePanel) {
            SideChatPanel(controller = connection.sideChatController, onDismiss = { showSidePanel = false })
        }
    }
}

@Composable
private fun StatusBar(status: DsbotConnection.Status) {
    when (status) {
        DsbotConnection.Status.CONNECTING -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.height(16.dp).padding(end = 8.dp))
            Text(stringResource(R.string.main_connecting), style = MaterialTheme.typography.bodySmall)
        }
        DsbotConnection.Status.ONLINE -> {}
        DsbotConnection.Status.RECONNECTING -> Text(
            stringResource(R.string.connection_reconnecting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        else -> {}
    }
}

@Composable
private fun SkeletonPage(label: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.page_skeleton_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NativeErrorPage(url: String, message: String, hintText: String, onRetry: () -> Unit, onChangeAddress: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
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
private fun ReauthPanel(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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

@Composable
private fun ForbiddenPanel(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.forbidden_host_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.forbidden_host_hint), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenSettings) { Text(stringResource(R.string.reauth_open_settings)) }
    }
}

data class TunnelPanelData(
    val titleRes: Int,
    val titleArg: String? = null,
    val detail: String,
    val hintRes: Int,
    val showRetry: Boolean,
)

@Composable
private fun TunnelGatePanel(
    title: String,
    detail: String,
    hint: String,
    showRetry: Boolean,
    onOpenSettings: () -> Unit,
    onRetryTunnel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
                Button(onClick = onRetryTunnel) { Text(stringResource(R.string.tunnel_retry_connect)) }
            }
        }
    }
}

/**
 * 连接编排器：把 DsbotConnection 的动作落到 NativeSession/HttpSocket/DialProbe 并把
 * 结果喂回状态机。**探测/建会话/gate/取 state/退避全部串行跑在单线程 executor**
 * （评审修复：裸线程的竞态面——pendingRawUrl 双消费、退避打断健康订阅、状态可见性）；
 * feed 订阅自身长阻塞，放独立线程并持代际令牌（[feedGeneration]），被取代/停止时
 * 靠 stop 谓词与读超时（≤30s）自然收尾。
 */
class NativeShellConnection(private val graph: AppGraph) : DsbotConnection.Listener, FeedSync.Listener {
    var status by mutableStateOf(DsbotConnection.Status.CONNECTING)
        private set
    var versionBanner by mutableStateOf<Pair<String, String>?>(null)
        private set
    var versionBannerDismissed by mutableStateOf(false)
        private set
    var page by mutableStateOf<ShellPage>(ShellPage.Team)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var tunnelPanel by mutableStateOf<TunnelPanelData?>(null)
        private set
    val cleanUrlText: String get() = graph.cleanUrl ?: ""

    /** 最近一次同步的团队视图（团队页渲染输入；feed 事件驱动的刷新经 onStateFetched 流经此处）。 */
    var team by mutableStateOf<TeamHome.Team?>(null)
        private set

    /** 最近一次全量/合并 state（增量合并的基底）。 */
    private var lastState: DsbotModels.State? = null

    /**
     * 聊天控制器（dsbot-bot-chat）：api 闭包投递到 executor 串行执行后回调——
     * 控制器在主线程调起（LaunchedEffect/onClick），同步网络会触发
     * NetworkOnMainThreadException 被吞成静默失败。
     */
    val chatController: ChatController by lazy {
        ChatController(
            api = { endpoint, payloadJson, onResult ->
                executor.execute {
                    val clean = graph.cleanUrl
                    val result = if (clean == null) {
                        DsbotApi.Result.Failure(null, DsbotApi.Reason.NETWORK)
                    } else {
                        try {
                            val res = graph.nativeSession.apiRequest(
                                "POST", DsbotApi.url(clean, "/api/bot"),
                                "application/json", DsbotApi.buildRequest(endpoint, payloadJson).toByteArray(),
                            )
                            DsbotApi.parseResponse(res.status, res.bodyText())
                        } catch (_: Exception) {
                            DsbotApi.Result.Failure(null, DsbotApi.Reason.NETWORK)
                        }
                    }
                    onResult(result)
                }
            },
            execute = { r -> executor.execute(r) },
            upload = { sessionId, name, mediaType, bytes, onResult ->
                executor.execute { uploadAttachment(sessionId, name, mediaType, bytes, onResult) }
            },
            muxCall = { endpoint, requestJson, onResult ->
                executor.execute { muxCall(endpoint, requestJson, onResult) }
            },
        )
    }

    /** 附件上传（dsbot-attachments）：POST /api/session/uploadFileBinary（octet-stream）。 */
    private fun uploadAttachment(
        sessionId: String,
        name: String,
        mediaType: String?,
        bytes: ByteArray,
        onResult: (DsbotApi.Result) -> Unit,
    ) {
        val clean = graph.cleanUrl
        if (clean == null) {
            onResult(DsbotApi.Result.Failure(null, DsbotApi.Reason.NETWORK))
            return
        }
        try {
            val query = buildString {
                append("?sessionId=").append(java.net.URLEncoder.encode(sessionId, "UTF-8"))
                append("&name=").append(java.net.URLEncoder.encode(name, "UTF-8"))
                if (mediaType != null) append("&mediaType=").append(java.net.URLEncoder.encode(mediaType, "UTF-8"))
            }
            val res = graph.nativeSession.apiRequest(
                "POST", DsbotApi.url(clean, "/api/session/uploadFileBinary") + query,
                "application/octet-stream", bytes,
            )
            onResult(DsbotApi.parseResponse(res.status, res.bodyText()))
        } catch (_: Exception) {
            onResult(DsbotApi.Result.Failure(null, DsbotApi.Reason.NETWORK))
        }
    }

    /**
     * dsh unary RPC（dsbot-attachments）：POST 到 /api/ 加 endpoint 的 client-request
     * 信封（0.3.5 实测——session/prompt 与 session/attachment 都是 unary，stream 载体
     * open 帧会被拒 gateway/signature-invalid；早期「附件必须 WebSocket」的 spike 结论
     * 对 0.3.5 不成立，HTTP 网关已注册 session 族方法）。
     */
    private fun muxCall(endpoint: String, requestJson: String, onResult: (work.keepwriting.core.DshMux.Result) -> Unit) {
        val clean = graph.cleanUrl
        if (clean == null) {
            onResult(work.keepwriting.core.DshMux.Result.Err("gateway/closed", "未连接"))
            return
        }
        try {
            val rpcId = java.util.UUID.randomUUID().toString()
            val body = work.keepwriting.core.AttachmentMessages.clientRequest(rpcId, endpoint, requestJson)
            val res = graph.nativeSession.apiRequest(
                "POST", DsbotApi.url(clean, "/api/$endpoint"),
                "application/json", body.toByteArray(Charsets.UTF_8),
            )
            val parsed = work.keepwriting.core.AttachmentMessages.parseServerResponse(res.bodyText())
            onResult(parsed ?: work.keepwriting.core.DshMux.Result.Err("gateway/protocol", "server-response 无法解析"))
        } catch (e: Exception) {
            onResult(work.keepwriting.core.DshMux.Result.Err("gateway/closed", e.javaClass.simpleName))
        }
    }

    /** 群聊控制器（dsbot-group-chat）：同一 executor 收敛与 rpc 通道。 */
    val roomChatController: RoomChatController by lazy {
        RoomChatController(
            api = { endpoint, payloadJson, onResult ->
                executor.execute {
                    val clean = graph.cleanUrl
                    val result = if (clean == null) {
                        DsbotApi.Result.Failure(null, DsbotApi.Reason.NETWORK)
                    } else {
                        try {
                            val res = graph.nativeSession.apiRequest(
                                "POST", DsbotApi.url(clean, "/api/bot"),
                                "application/json", DsbotApi.buildRequest(endpoint, payloadJson).toByteArray(),
                            )
                            DsbotApi.parseResponse(res.status, res.bodyText())
                        } catch (_: Exception) {
                            DsbotApi.Result.Failure(null, DsbotApi.Reason.NETWORK)
                        }
                    }
                    onResult(result)
                }
            },
            execute = { r -> executor.execute(r) },
            rpcCall = { endpoint, requestJson, onResult ->
                executor.execute { muxCall(endpoint, requestJson, onResult) }
            },
        )
    }

    /** Side chat 控制器（dsbot-side-chat）：独立 rows/流式/会话，feed 按 side sessionId 路由。 */
    val sideChatController: SideChatController by lazy {
        SideChatController(
            api = { endpoint, payloadJson, onResult ->
                executor.execute {
                    val clean = graph.cleanUrl
                    val result = if (clean == null) {
                        DsbotApi.Result.Failure(null, DsbotApi.Reason.NETWORK)
                    } else {
                        try {
                            val res = graph.nativeSession.apiRequest(
                                "POST", DsbotApi.url(clean, "/api/bot"),
                                "application/json", DsbotApi.buildRequest(endpoint, payloadJson).toByteArray(),
                            )
                            DsbotApi.parseResponse(res.status, res.bodyText())
                        } catch (_: Exception) {
                            DsbotApi.Result.Failure(null, DsbotApi.Reason.NETWORK)
                        }
                    }
                    onResult(result)
                }
            },
            execute = { r -> executor.execute(r) },
        )
    }

    /** feed 事件转发给私聊与群聊控制器（各自按 sessionId/botId 过滤）。 */
    private fun forwardChatEvent(event: String, dataJson: String) {
        if (event == "stream" || event == "message") {
            chatController.onFeedEvent(event, dataJson)
            roomChatController.onFeedEvent(event, dataJson)
            sideChatController.onFeedEvent(event, dataJson) // side 会话归属过滤在控制器内
        }
    }

    private val machine = DsbotConnection(this)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "dsbot-conn").apply { isDaemon = true } }
    // 退避定时独立线程：sleep 不得占据唯一 executor（复审修复：重连窗口内刷新/生命周期停摆）
    private val scheduler = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "dsbot-backoff").apply { isDaemon = true }
    }.apply { setRemoveOnCancelPolicy(true) }
    private val feedGeneration = AtomicLong(0)

    private val tunnel get() = graph.tunnel

    // ---- UI 动作（主线程调用）----

    fun start() {
        executor.execute { evaluateTunnelGate { machine.beginConnect() } }
    }

    fun retry() {
        executor.execute {
            errorMessage = null
            versionBannerDismissed = false
            evaluateTunnelGate { machine.beginConnect() }
        }
    }

    fun retryTunnel() {
        tunnel.ensureUp()
    }

    fun dismissVersionBanner() {
        versionBannerDismissed = true
    }

    fun onForeground() {
        executor.execute { machine.onForeground() }
    }

    fun onBackground() {
        feedGeneration.incrementAndGet() // 代际作废：feed 线程靠谓词+读超时自然退出
        executor.execute { machine.onBackground() }
    }

    fun openChat(id: String) {
        page = ShellPage.Chat(id)
    }

    fun openDetails(id: String) {
        page = ShellPage.Details(id)
    }

    fun openNeedsYou() {
        page = ShellPage.NeedsYou
    }

    fun goBack() {
        page = ShellPage.Team
    }

    fun stop() {
        sideChatController.dispose()
        feedGeneration.incrementAndGet()
        scheduler.shutdownNow()
        executor.shutdownNow()
    }

    // ---- 隧道联动（executor 线程）----

    private fun evaluateTunnelGate(ready: () -> Unit) {
        val clean = graph.cleanUrl ?: return
        val host = try {
            java.net.URI(clean).host ?: ""
        } catch (_: Exception) {
            ""
        }
        if (!TailnetRouting.isTailnetTarget(host) || tunnel.state == TunnelStateMachine.State.CONNECTED) {
            tunnelPanel = null
            ready()
            return
        }
        tunnelPanel = if (tunnel.state == TunnelStateMachine.State.NOT_CONFIGURED) {
            TunnelPanelData(
                titleRes = R.string.tunnel_prompt_title,
                detail = "",
                hintRes = R.string.tunnel_prompt_detail,
                showRetry = false,
            )
        } else {
            TunnelPanelData(
                titleRes = R.string.tunnel_wait_title,
                titleArg = tunnel.state.name,
                detail = tunnel.failureReason,
                hintRes = R.string.tunnel_wait_hint,
                showRetry = true,
            )
        }
    }

    // ---- DsbotConnection.Listener 落地（executor 线程；connectFeed 的订阅循环在 feed 线程）----

    override fun onStatus(next: DsbotConnection.Status) {
        status = next
        if (next != DsbotConnection.Status.OFFLINE) errorMessage = null
        if (next == DsbotConnection.Status.OFFLINE) versionBanner = null
    }

    override fun probeAndGate() {
        val clean = graph.cleanUrl ?: return
        val dial = DialProbe.Dial { h, p -> graph.nativeDial.dial(h, p) }
        val probe = DialProbe.probe(clean, dial)
        if (probe !is DialProbe.ProbeResult.Reachable) {
            val reason = (probe as DialProbe.ProbeResult.Unreachable).reason
            graph.diagnostics.recordLoadError(clean, reason)
            errorMessage = reasonText(reason)
            machine.onUnreachable()
            return
        }
        // 保存带 token 地址后原生建立会话（executor 串行 → token 只被消费一次）
        val pendingRaw = graph.consumePendingRawUrl()
        if (pendingRaw != null) {
            when (graph.nativeSession.establish(pendingRaw)) {
                NativeSession.EstablishResult.Ok -> {} // 成功：落回下方 gate
                is NativeSession.EstablishResult.Rejected -> {
                    machine.onGate(DsbotConnection.NativeGate.AUTH_EXPIRED)
                    return
                }
                NativeSession.EstablishResult.NetworkError -> {
                    errorMessage = "连接失败"
                    machine.onUnreachable()
                    return
                }
            }
        } else {
            graph.nativeSession.migrateFromWebViewIfNeeded(originOf(clean))
        }
        // gate：401 自动重试一次（session-expired-reauth）
        var gate = gateOf(clean)
        if (gate == DsbotConnection.NativeGate.AUTH_EXPIRED) gate = gateOf(clean)
        machine.onGate(gate)
        if (gate == DsbotConnection.NativeGate.OK) checkVersion(clean)
    }

    private fun gateOf(clean: String): DsbotConnection.NativeGate = try {
        when (graph.nativeSession.apiRequest("GET", clean, null, null).status) {
            401 -> DsbotConnection.NativeGate.AUTH_EXPIRED
            403 -> DsbotConnection.NativeGate.FORBIDDEN_HOST
            else -> DsbotConnection.NativeGate.OK
        }
    } catch (_: Exception) {
        // 可达性已由 DialProbe 前置判定；gate 自身网络错误放行，残余错误交给 feed 层暴露
        DsbotConnection.NativeGate.OK
    }

    override fun connectFeed() {
        val generation = feedGeneration.incrementAndGet()
        Thread({
            val clean = graph.cleanUrl ?: return@Thread
            try {
                executor.execute {
                    machine.onFeedConnected() // 重置退避；ONLINE 由首帧/应答确认
                    // feed 断线窗口内发布的 message/stream 事件不可重放：重连即补取当前聊天页（私聊与群聊）
                    chatController.refreshIfOpen()
                    roomChatController.refreshIfOpen()
                }
                graph.nativeSession.feed(
                    DsbotApi.url(clean, "/api/bot/feed"),
                    stop = { feedGeneration.get() != generation },
                ) { event, data -> executor.execute {
                    machine.onFeedEvent(event, data)
                    forwardChatEvent(event, data)
                } }
            } catch (e: Exception) {
                // feed HTTP 401：会话中途失效——路由 gate 复查（AUTH_EXPIRED 分流）而非盲退避；
                // 只信当前代际（复审修复：被取代的旧订阅迟到 401 不得误伤新会话）
                if (e is work.keepwriting.core.FeedHttpException && e.status == 401 &&
                    feedGeneration.get() == generation
                ) {
                    executor.execute { machine.onGate(DsbotConnection.NativeGate.AUTH_EXPIRED) }
                    return@Thread
                }
            }
            if (feedGeneration.get() == generation) {
                // 正常断线（非代际作废）：断开迁移入队 executor（不等待、不丢弃——复审修复），
                // 迁移得到的退避时长在 scheduler 上定时，到点仍回 executor 触发重连
                executor.execute {
                    val delay = machine.onFeedDisconnected()
                    scheduleReconnect(delay, generation)
                }
            }
        }, "dsbot-feed").apply { isDaemon = true }.start()
    }

    private fun scheduleReconnect(delayMs: Long, generation: Long) {
        if (delayMs <= 0 || feedGeneration.get() != generation) return
        scheduler.schedule(
            { executor.execute { machine.onBackoffElapsed() } },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun fetchState(localRevision: String?, liveKey: String?) {
        val clean = graph.cleanUrl ?: return
        val fields = buildString {
            if (localRevision != null) append("\"since\":").append(jsonNumberOrString(localRevision))
            if (localRevision != null && liveKey != null) append(",")
            if (liveKey != null) append("\"live\":").append(work.keepwriting.core.JsonString.of(liveKey))
        }
        // 空参数是 "{}"（全量取），有参数才包对象——把 "{}" 再包一层会得到非法 JSON "{{}}"，
        // 服务端整体解析失败回 bot/not-found（真实 0.3.5 实测；mock 以正则提 endpoint 掩盖过）
        val payloadJson = if (fields.isEmpty()) "{}" else "{$fields}"
        val res = try {
            graph.nativeSession.apiRequest(
                "POST", DsbotApi.url(clean, "/api/bot"),
                "application/json", DsbotApi.buildRequest("state", payloadJson).toByteArray(),
            )
        } catch (_: Exception) {
            return // 网络抖动：不动状态，下一轮事件重触发
        }
        when (val parsed = DsbotApi.parseResponse(res.status, res.bodyText())) {
            is DsbotApi.Result.Ok -> {
                val full = DsbotModels.decodeState(parsed.value)
                val delta = DsbotModels.decodeStateDelta(parsed.value)
                when {
                    full != null -> {
                        machine.onStateFetched(full)
                        lastState = full
                        team = TeamHome.assemble(full)
                    }
                    delta != null -> {
                        // 增量应答：合并 live 字段集后重组装（unchanged 无 live 字段时列表不变）
                        machine.onStateFetched(delta)
                        val current = lastState
                        if (current != null) {
                            val merged = DsbotModels.mergeDelta(current, delta)
                            lastState = merged
                            team = TeamHome.assemble(merged)
                        }
                    }
                    else -> return // 未知形态：降级不崩溃，等下一轮事件
                }
            }
            is DsbotApi.Result.BotError -> {}
            is DsbotApi.Result.Failure -> when (parsed.reason) {
                DsbotApi.Reason.AUTH_EXPIRED -> machine.onGate(DsbotConnection.NativeGate.AUTH_EXPIRED)
                else -> {
                    // 取增量失败按断线语义交给退避接管，不落入无提示死态
                    scheduleReconnect(machine.onFeedDisconnected(), feedGeneration.get())
                }
            }
        }
    }

    private fun checkVersion(clean: String) {
        try {
            val res = graph.nativeSession.apiRequest(
                "POST", DsbotApi.url(clean, "/api/bot"),
                "application/json", DsbotApi.buildRequest("update-check").toByteArray(),
            )
            val parsed = DsbotApi.parseResponse(res.status, res.bodyText())
            if (parsed is DsbotApi.Result.Ok) {
                val remote = DsbotVersions.fromUpdateCheck(parsed.value)
                versionBanner = if (DsbotVersions.againstBaseline(remote) == DsbotVersions.Verdict.MISMATCHED) {
                    (remote.dsBot ?: "?") to (remote.dsh ?: "?")
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            versionBanner = null // UNKNOWN：不提示
        }
    }

    private fun reasonText(reason: ProbeFailure.Reason): String = when (reason) {
        ProbeFailure.Reason.DNS -> "无法解析主机名"
        ProbeFailure.Reason.REFUSED -> "连接被拒绝"
        ProbeFailure.Reason.TIMEOUT -> "连接超时"
        ProbeFailure.Reason.TLS -> "安全连接（TLS）失败"
        else -> "连接失败"
    }
}

private fun jsonNumberOrString(v: String): String =
    if (v.isNotEmpty() && v.all { it.isDigit() || it == '-' }) v else work.keepwriting.core.JsonString.of(v)

private fun originOf(url: String): String = try {
    val u = java.net.URI(url)
    "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
} catch (_: Exception) {
    url
}
