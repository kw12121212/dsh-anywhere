package work.keepwriting.dshanywhere

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.SetupCheck
import work.keepwriting.core.TeamHome
import work.keepwriting.core.DsbotConnection
import work.keepwriting.core.DsbotModels
import work.keepwriting.core.NoticeCursor
import work.keepwriting.core.Notices
import work.keepwriting.core.Capabilities
import work.keepwriting.core.DsbotHello
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
fun NativeShell(graph: AppGraph, onOpenSettings: () -> Unit, onNeedSetup: () -> Unit, covered: Boolean = false) {
    val connection = remember { NativeShellConnection(graph) }
    // 设置页叠在外壳上时聊天页不算可见：不标记已读（dsbot-read-state）
    SideEffect { connection.covered = covered }
    // 前后台：生命周期事件驱动状态机（后台断 feed、回前台重走连接）
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    connection.resumed = true
                    connection.onForeground()
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    connection.resumed = false
                    connection.onBackground()
                }
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
    // 切换语言（dsbot-phone-prefs）：界面文案在组合期读取可观察的语言状态、调用处自行重组（不整体重建，
    // 草稿、滚动位置与已作答记录都保留）；连接里预先组装好的团队列表按新语言重组装。
    val lang = graph.phone.lang
    LaunchedEffect(lang) { connection.relocalize() }
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
        if (tunnelState == TunnelStateMachine.State.CONNECTED) {
            // 更新检查曾因隧道未就绪而失败：连上后自动补查（tailnet 更新源）
            graph.updates.onTunnelConnected()
            if (TailnetRouting.isTailnetTarget(host)) {
                connection.retry()
            }
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
        data class Chat(val targetId: String) : ShellPage
        data class Details(val targetId: String) : ShellPage
        /** 新建 Bot（dsbot-bot-manage）。 */
        data object NewBot : ShellPage
        /** 新建群聊（dsbot-group-manage）。 */
        data object NewRoom : ShellPage
        /** 团队的 DSH Agent 聊天（dsbot-member-agents）。 */
        data class AgentChat(val agentId: String) : ShellPage
        /** 记忆页与记忆历史（dsbot-memory）。 */
        data class Memory(val owner: work.keepwriting.core.MemoryPanel.Owner, val title: String) : ShellPage
        data class MemoryHistory(val owner: work.keepwriting.core.MemoryPanel.Owner) : ShellPage
        /** 团队偏好（dsbot-prefs-theme）。 */
        data object TeamPrefs : ShellPage
        /** 分组管理（dsbot-sidebar-sections）。 */
        data object SectionsManage : ShellPage
        /** 保留关闭的已删除私有 Bot（dsbot-bot-manage）。 */
        data object PrivateArchives : ShellPage
        /** 头像库，为某个 Bot 选照片（dsbot-avatars）。 */
        data class AvatarLibrary(val botId: String) : ShellPage
        /** 工作文件夹选择（dsbot-folders）。 */
        data class FolderPick(val target: FolderTarget) : ShellPage
        /** Bot 浏览器页（dsbot-bot-browser）。 */
        data class BotBrowser(val botId: String) : ShellPage
        /** 团队能力库（dsbot-capabilities）。 */
        data object Capabilities : ShellPage
        /** 新增（name=null）或编辑一项能力。 */
        data class CapabilityEdit(val name: String?) : ShellPage
        /** 单个 Bot 的能力页。 */
        data class BotCapabilities(val botId: String) : ShellPage
    }

@Composable
private fun ShellScaffold(connection: NativeShellConnection, onOpenSettings: () -> Unit) {
    val linkContext = androidx.compose.ui.platform.LocalContext.current
    var showSidePanel by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            StatusDot(connection.status)
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.main_open_settings)) }
            TextButton(onClick = { connection.retry() }) { Text(stringResource(R.string.main_refresh)) }
        }
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
        // 应用内提醒横幅（dsbot-notices-banner）：约 8 秒自动收起，点按进入对应聊天
        connection.noticeBanner?.let { notice ->
            androidx.compose.runtime.LaunchedEffect(notice.id) {
                kotlinx.coroutines.delay(NOTICE_BANNER_MS)
                connection.dismissNotice(notice.id)
            }
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable { connection.openNotice(notice) },
            ) {
                Text(
                    notice.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
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
                    // 本机已读（dsbot-read-state）：前台可见时以 max(条目 lastAt, 聊天内最新条目时间) 标记；
                    // 控制器的 newest 只认当前聊天 id，避免切换时把上一个聊天的时间记过来
                    val newest = if (entry?.kind == TeamHome.Entry.Kind.ROOM) connection.roomChatController.newest else connection.chatController.newest
                    val readAt = work.keepwriting.core.ReadState.readAt(current.targetId, entry?.lastAt, newest)
                    val readHost = connection.readHost
                    val visible = connection.resumed && !connection.covered
                    LaunchedEffect(readHost, current.targetId, readAt, visible) {
                        if (visible && readHost != null) connection.markRead(readHost, current.targetId, readAt)
                    }
                    // Markdown 图片取图入口（dsbot-rich-render）：本地路径按当前会话经 markdown-image 取回
                    val imageSession = if (entry?.kind == TeamHome.Entry.Kind.ROOM) current.targetId else connection.chatController.shownSessionId
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalMarkdownImageLoader provides { src: String, cb: (ByteArray?) -> Unit -> connection.media.markdownImage(imageSession, src, cb) },
                    ) {
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
                            onOpenLink = { url, chooser -> connection.openChatLink(linkContext, url, chooser) },
                            state = connection.stateSnapshot?.raw,
                            barInFlight = connection.barInFlight,
                            onBarAction = { key, endpoint, payload ->
                                connection.barAction(key, endpoint, payload) { connection.roomChatController.postNotice(it) }
                            },
                            media = connection.media,
                            onOpenDetails = { connection.openDetails(current.targetId) },
                        )
                    } else {
                        LaunchedEffect(entry?.working) { connection.chatController.syncWorking(entry?.working ?: false) }
                        BotChatPage(
                            controller = connection.chatController,
                            botId = current.targetId,
                            parts = entry?.parts ?: emptyList(),
                            onOpenLink = { url, chooser -> connection.openChatLink(linkContext, url, chooser) },
                            state = connection.stateSnapshot?.raw,
                            entrySessionId = entry?.sessionId,
                            barInFlight = connection.barInFlight,
                            onBarAction = { key, endpoint, payload ->
                                connection.barAction(key, endpoint, payload) { connection.chatController.postNotice(it) }
                            },
                            media = connection.media,
                            onOpenDetails = { connection.openDetails(current.targetId) },
                            onOpenBrowser = { bid -> connection.openBotBrowser(bid) },
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
                                    connection.sideChatController.postNoticeStatic(tr("会话信息不可用，无法侧问", "Session info unavailable, cannot open side chat"))
                                }
                            },
                        )
                    }
                    }
                }
                current is ShellPage.Details -> {
                    // 是 state 里的 Bot → Bot 详情页；否则是群（团队列表或 state 里有）→ 群详情页；都没有（已在别处删除）→ Bot 详情页的中性「找不到」
                    val snapshot = connection.stateSnapshot?.raw
                    val isBot = work.keepwriting.core.BotManage.profileOf(snapshot, current.targetId) != null
                    if (!isBot && (isRoomEntry(connection, current.targetId) || work.keepwriting.core.GroupManage.roomOf(snapshot, current.targetId) != null)) {
                        RoomDetailsPage(
                            state = connection.stateSnapshot?.raw,
                            roomId = current.targetId,
                            api = connection.manageApi,
                            flash = connection.flash,
                            onDismissFlash = { connection.flash = null },
                            onPickFolder = { connection.openFolderPick(FolderTarget.Room(current.targetId)) },
                            onDeleted = { if (connection.page == current) connection.goHome() },
                            onOpenMemory = {
                                val name = work.keepwriting.core.WorkFolders.roomName(snapshot, current.targetId) ?: ""
                                connection.openMemory(work.keepwriting.core.MemoryPanel.Owner.Room(current.targetId), tr("群记忆：", "Group memory: ") + name)
                            },
                        )
                    } else {
                        BotDetailsPage(
                            state = connection.stateSnapshot?.raw,
                            botId = current.targetId,
                            api = connection.manageApi,
                            // 写完成时用户可能已离开该页：只在仍停留在本详情页时跳转
                            onOpenChat = { id -> if (connection.page == current) connection.openChat(id) },
                            onDeleted = { if (connection.page == current) connection.goHome() },
                            onOpenAvatars = { connection.openAvatarLibrary(current.targetId) },
                            onPickFolder = { connection.openFolderPick(FolderTarget.Bot(current.targetId)) },
                            flash = connection.flash,
                            onDismissFlash = { connection.flash = null },
                            onOpenMemory = {
                                val name = work.keepwriting.core.BotManage.profileOf(snapshot, current.targetId)?.name ?: ""
                                connection.openMemory(work.keepwriting.core.MemoryPanel.Owner.Bot(current.targetId), tr("记忆：", "Memory: ") + name)
                            },
                            onOpenBrowser = { connection.openBotBrowser(current.targetId) },
                            onOpenCapabilities = { connection.openBotCapabilities(current.targetId) },
                        )
                    }
                }
                current is ShellPage.Memory -> MemoryPage(
                    owner = current.owner,
                    title = current.title,
                    api = connection.manageApi,
                    onOpenHistory = { connection.openMemoryHistory(current.owner) },
                )
                current is ShellPage.MemoryHistory -> MemoryHistoryPage(current.owner, connection.manageApi)
                current is ShellPage.TeamPrefs -> TeamPrefsPage(connection.stateSnapshot?.raw, connection.manageApi)
                current is ShellPage.SectionsManage -> SectionsManagePage(
                    team = connection.team,
                    sections = work.keepwriting.core.TeamSections.decode(connection.stateSnapshot?.raw),
                    api = connection.manageApi,
                )
                current is ShellPage.FolderPick -> {
                    val snapshot = connection.stateSnapshot?.raw
                    val target = current.target
                    val (title, folder) = when (target) {
                        is FolderTarget.Bot -> tr("工作文件夹：", "Working folder: ") + (work.keepwriting.core.BotManage.profileOf(snapshot, target.id)?.name ?: "") to
                            work.keepwriting.core.BotManage.profileOf(snapshot, target.id)?.workDir
                        is FolderTarget.Room -> tr("群文件夹：", "Group folder: ") + (work.keepwriting.core.WorkFolders.roomName(snapshot, target.id) ?: "") to
                            work.keepwriting.core.WorkFolders.roomFolder(snapshot, target.id)
                        FolderTarget.Team -> tr("团队工作区", "Team workspace") to null
                    }
                    FolderPickerPage(
                        target = target, title = title, current = folder, api = connection.manageApi,
                        // Bot/群保存成功：只在仍停留在选择页时返回详情页，并在那里提示新分段（团队工作区留在本页刷新）
                        onSaved = {
                            if (connection.page == current) {
                                connection.flash = folderSavedText()
                                connection.goBack()
                            }
                        },
                    )
                }
                current is ShellPage.AgentChat -> AgentChatPage(
                    controller = connection.agentChatController,
                    state = connection.stateSnapshot?.raw,
                    agentId = current.agentId,
                    api = connection.manageApi,
                    onOpenLink = { url, chooser -> connection.openChatLink(linkContext, url, chooser) },
                    media = connection.media,
                    // 移除成功：只在仍停留在该 Agent 聊天时回团队页
                    onRemoved = { if (connection.page == current) connection.goHome() },
                )
                current is ShellPage.NewRoom -> NewRoomPage(
                    state = connection.stateSnapshot?.raw,
                    api = connection.manageApi,
                    onCreated = { id -> if (connection.page == ShellPage.NewRoom) connection.openChat(id) },
                )
                current is ShellPage.NewBot -> NewBotPage(
                    state = connection.stateSnapshot?.raw,
                    api = connection.manageApi,
                    onCreated = { id -> if (connection.page == ShellPage.NewBot) connection.openChat(id) },
                )
                current is ShellPage.PrivateArchives -> PrivateArchivesPage(connection.stateSnapshot?.raw, connection.manageApi)
                current is ShellPage.AvatarLibrary -> AvatarLibraryPage(
                    botId = current.botId,
                    botShape = work.keepwriting.core.BotManage.profileOf(connection.stateSnapshot?.raw, current.botId)?.shape,
                    api = connection.manageApi,
                    // 设好照片后回到详情页（只在仍停留在头像库页时）
                    onDone = { if (connection.page == current) connection.goBack() },
                )
                current is ShellPage.Capabilities -> CapabilitiesPage(
                    state = connection.stateSnapshot?.raw,
                    api = connection.manageApi,
                    onAdd = { connection.openCapabilityEdit(null) },
                    onEdit = { name -> connection.openCapabilityEdit(name) },
                )
                // 保存成功后返回（只在仍停留在本编辑页时）
                current is ShellPage.CapabilityEdit -> CapabilityEditPage(
                    state = connection.stateSnapshot?.raw,
                    api = connection.manageApi,
                    name = current.name,
                    onDone = { if (connection.page == current) connection.goBack() },
                )
                current is ShellPage.BotCapabilities -> BotCapabilitiesPage(
                    state = connection.stateSnapshot?.raw,
                    botId = current.botId,
                    botName = work.keepwriting.core.BotManage.profileOf(connection.stateSnapshot?.raw, current.botId)?.name ?: current.botId,
                    api = connection.manageApi,
                )
                current is ShellPage.BotBrowser -> BotBrowserPage(
                    botId = current.botId,
                    botName = work.keepwriting.core.BotManage.profileOf(connection.stateSnapshot?.raw, current.botId)?.name ?: current.botId,
                    state = connection.stateSnapshot?.raw,
                    resumed = connection.resumed,
                    covered = connection.covered,
                    online = connection.status == DsbotConnection.Status.ONLINE,
                    api = connection.manageApi,
                )
                else -> {
                    // 某主机首次出现团队：以当前最近活动打底，免得整页未读（之后的新活动照常显示）
                    val team = connection.team
                    val host = connection.readHost
                    LaunchedEffect(team, host) {
                        if (team != null && host != null) {
                            connection.seedRead(host, team.sections.flatMap { it.entries }.associate { it.id to it.lastAt })
                        }
                    }
                    TeamPage(
                    team = connection.team,
                    onOpenChat = { id -> connection.openChat(id ?: "unknown") },
                    isUnread = { e -> connection.isUnread(e, open = false) },
                    onNewBot = { connection.openNewBot() },
                    onNewRoom = { connection.openNewRoom() },
                    // 团队的 DSH Agent（dsbot-member-agents）：只在远端声明 agents 能力时出现
                    agents = if (connection.capabilities.has(work.keepwriting.core.DshAgents.CAPABILITY)) work.keepwriting.core.DshAgents.agents(connection.stateSnapshot?.raw) else emptyList(),
                    agentRunning = { id -> work.keepwriting.core.DshAgents.running(connection.stateSnapshot?.raw, id) },
                    canAddAgent = work.keepwriting.core.DshAgents.canAdd(connection.capabilities, connection.stateSnapshot?.raw),
                    onAddAgent = { onErr -> connection.manageAction("add-agent", "add-agent", work.keepwriting.core.DshAgents.Requests.add(), {}, onErr) },
                    onOpenAgent = { id -> connection.openAgentChat(id) },
                    archivedPrivate = work.keepwriting.core.BotManage.privateArchives(connection.stateSnapshot?.raw).size,
                    onOpenArchived = { connection.openPrivateArchives() },
                    onTeamWorkspace = { connection.openFolderPick(FolderTarget.Team) },
                    onTeamPrefs = { connection.openTeamPrefs() },
                    // 能力库（dsbot-capabilities）：state 含能力库字段时出现
                    showCapabilities = work.keepwriting.core.CapabilityLibrary.hasLibrary(connection.stateSnapshot?.raw),
                    onCapabilities = { connection.openCapabilities() },
                    sections = work.keepwriting.core.TeamSections.decode(connection.stateSnapshot?.raw),
                    onCreateSection = { name, onErr ->
                        connection.manageAction("section:create", "section-save", work.keepwriting.core.TeamSections.Requests.create(name), {}, onErr)
                    },
                    onToggleSection = { id, collapsed, onErr ->
                        connection.manageAction("section:collapse:$id", "section-save", work.keepwriting.core.TeamSections.Requests.collapse(id, collapsed), {}, onErr)
                    },
                    onMoveToSection = { entryId, sectionId, onErr ->
                        connection.manageAction("section:move:$entryId", "set-flags", work.keepwriting.core.TeamSections.Requests.moveEntry(entryId, sectionId), {}, onErr)
                    },
                    onManageSections = { connection.openSectionsManage() },
                )
                }
            }
        }
        if (showSidePanel) {
            SideChatPanel(controller = connection.sideChatController, onDismiss = { showSidePanel = false })
        }
    }
}

private fun isRoomEntry(connection: NativeShellConnection, id: String): Boolean =
    connection.team?.sections.orEmpty().flatMap { it.entries }.firstOrNull { it.id == id }?.kind == TeamHome.Entry.Kind.ROOM

@Composable
private fun StatusDot(status: DsbotConnection.Status) {
    // 顶栏连接状态圆点：绿=在线、琥珀=连接中/重连、红=离线（含会话失效/被拒）。
    val (color, desc) = when (status) {
        DsbotConnection.Status.ONLINE -> Color(0xFF4CAF50) to stringResource(R.string.status_online)
        DsbotConnection.Status.CONNECTING, DsbotConnection.Status.RECONNECTING ->
            Color(0xFFFFA000) to stringResource(R.string.main_connecting)
        else -> MaterialTheme.colorScheme.error to stringResource(R.string.status_offline)
    }
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .size(12.dp)
            .background(color, CircleShape)
            .semantics { contentDescription = desc },
    )
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

    /**
     * 对话内链接打开（主线程调用）：http(s) 走系统浏览器；无 scheme 的服务器本地路径
     * （Bot 常发的 /home/…/xxx.mp4 形态）经 /api/file?path= 以会话 cookie 取字节——
     * 真实 dsh 先以查询串 Location（"?path=…&v=版本"）做版本重定向再出字节，跟随后
     * 交系统应用打开（视频出播放器等）。长按链接 [chooser]=true 时经「选择打开方式」
     * 选择器由用户挑应用。失败在群/私聊 notice 提示。
     */
    fun openChatLink(context: android.content.Context, url: String, chooser: Boolean = false) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            runCatching {
                val view = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(
                    if (chooser) android.content.Intent.createChooser(view, tr("选择打开方式", "Open with"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) else view,
                )
            }
            return
        }
        if (!url.startsWith("/")) return
        executor.execute {
            val clean = graph.cleanUrl
            if (clean == null) {
                chatController.postNotice(tr("会话信息不可用，无法打开文件", "Session info unavailable, cannot open the file"))
                return@execute
            }
            var next = work.keepwriting.core.DsbotApi.url(clean, "/api/file") +
                "?path=" + java.net.URLEncoder.encode(url, "UTF-8")
            var res: work.keepwriting.core.HttpSocket.Response? = null
            // /api/file 先以查询串 Location（"?path=…&v=版本"）做一次版本重定向再出
            // 字节（dsh-api-session-controller 的 serveFile）；Location 统一经
            // HttpLocation 归并，跟随次数封顶防循环。
            for (hop in 0..2) {
                res = runCatching { graph.nativeSession.apiRequest("GET", next, null, null) }.getOrNull()
                    ?: break
                if (res.status !in 301..308) break
                val loc = res.header("Location")
                    ?.let { work.keepwriting.core.HttpLocation.resolve(next, it) }
                if (loc == null) break
                next = loc
            }
            if (res == null || res.status != 200) {
                val code = res?.status
                val reason = when (code) {
                    null -> tr("网络错误", "Network error")
                    401 -> tr("会话已过期，请重连后重试", "Session expired. Reconnect and try again")
                    403 -> tr("服务器拒绝访问该文件", "The server denied access to this file")
                    404 -> tr("文件不存在或已被移动", "The file does not exist or was moved")
                    413 -> tr("文件超过服务器大小限制（50MB）", "The file exceeds the server size limit (50 MB)")
                    else -> tr("文件获取失败（$code）", "Could not get the file ($code)")
                }
                chatController.postNotice(reason)
                roomChatController.postNotice(reason)
                return@execute
            }
            val name = url.substringAfterLast('/').ifEmpty { "file" }
            val opened = AttachmentOpen.openWith(context, name, res.body, chooser)
            if (!opened) chatController.postNotice(tr("没有应用能打开 $name", "No app can open $name"))
        }
    }
    var status by mutableStateOf(DsbotConnection.Status.CONNECTING)
        private set
    var versionBanner by mutableStateOf<Pair<String, String>?>(null)
        private set
    var versionBannerDismissed by mutableStateOf(false)
        private set

    /**
     * 远端能力集合（dsbot-baseline-0310 的 capability-handshake）：连接时读 hello，feed
     * 首帧 hello 的 boot 变化（服务重启）时重读。本里程碑新增功能的入口一律以
     * capabilities.has(…) 判定；LEGACY（0.3.6 无 hello 或读取失败）只隐藏新能力，
     * 既有私聊/群聊/卡片/附件/Side chat 入口不经此门控。
     */
    var capabilities by mutableStateOf(Capabilities.LEGACY)
        private set
    var page by mutableStateOf<ShellPage>(ShellPage.Team)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var tunnelPanel by mutableStateOf<TunnelPanelData?>(null)
        private set
    val cleanUrlText: String get() = graph.cleanUrl ?: ""

    /** Activity 是否处于前台可见（RESUMED；主线程写）：只在可见时标记已读（dsbot-read-state）。 */
    var resumed by mutableStateOf(false)

    /** 设置页叠在外壳上（主线程写）：此时聊天页不可见，不标记已读。 */
    var covered by mutableStateOf(false)

    /** 已读按 harness 主机分开：scheme://host:port（不含 token）。组合期读取即随地址变化重组。 */
    val readHost: String? get() = work.keepwriting.core.ReadState.hostKey(graph.cleanUrl)

    /** 标记已读（主线程）：只前移，非正数忽略。主机由调用方在组合期取得并作为效果键。 */
    fun markRead(host: String, id: String, at: Long) = graph.readStore.mark(host, id, at)

    fun seedRead(host: String, lastAts: Map<String, Long?>) = graph.readStore.seed(host, lastAts)

    /** 团队页未读点：组合期间调用即订阅已读状态。 */
    fun isUnread(e: TeamHome.Entry, open: Boolean): Boolean = graph.readStore.isUnread(readHost, e.id, e.lastAt, open)

    /** 最近一次同步的团队视图（团队页渲染输入；feed 事件驱动的刷新经 onStateFetched 流经此处）。 */
    var team by mutableStateOf<TeamHome.Team?>(null)
        private set

    /** 最近一次全量/合并 state（增量合并的基底）。 */
    private var lastState: DsbotModels.State? = null

    /** 语言切换后按新语言重组装团队列表（缺省分组名与待办摘要在组装时取文案；dsbot-phone-prefs）。 */
    fun relocalize() {
        executor.execute { lastState?.let { team = TeamHome.assemble(it) } }
    }

    /**
     * 可观察的最近 state 快照（dsbot-chat-bars-0310）：与 lastState 同步写入，聊天页据此用
     * core 的 ChatBars 派生任务条、电脑操作请求、修复卡、群目标与工作短语（state 应答含 live 字段）。
     */
    var stateSnapshot by mutableStateOf<DsbotModels.State?>(null)
        private set

    /** 聊天条写操作在途键（防重复点按）：主线程登记（点按即禁用），executor 完成后移除。 */
    val barInFlight = androidx.compose.runtime.mutableStateListOf<String>()

    /**
     * 聊天条写操作（bot-stop-task、computer-app-answer、computer-stop、resume），主线程调用：
     * 同键在途时直接忽略；成功后不本地改写，静默补取一次增量 state 让服务端结论尽快上屏
     * （电脑操作请求的变化不一定伴随 feed live 事件）；失败经 [onError] 给可读原因。
     */
    fun barAction(key: String, endpoint: String, payloadJson: String, onError: (String) -> Unit) {
        if (key in barInFlight) return
        barInFlight.add(key)
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
            when (result) {
                is DsbotApi.Result.Ok -> lastState.let { fetchStateOnce(it?.revision, it?.liveKey, quiet = true) }
                is DsbotApi.Result.BotError -> onError(work.keepwriting.core.ChatBars.actionError(result.code))
                is DsbotApi.Result.Failure -> onError(work.keepwriting.core.ChatBars.actionError("network"))
            }
            barInFlight.remove(key)
        }
    }

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
            onLoadFailure = { code -> graph.diagnostics.recordChatLoadError("bot", code) },
            hostOf = { cacheHost() },
            readApi = ::readOffExecutor,
        )
    }

    /**
     * DSH Agent 聊天控制器（dsbot-member-agents）：agent-chat 读取、stop-agent、不标已读；Agent 会话没有 stream/message 推送，
     * 由 applyStateResult（live/revision 与兜底轮询）刷新。
     */
    val agentChatController: ChatController by lazy {
        ChatController(
            api = { endpoint, payloadJson, onResult -> executor.execute { onResult(postBot(endpoint, payloadJson)) } },
            execute = { r -> executor.execute(r) },
            onLoadFailure = { code -> graph.diagnostics.recordChatLoadError("agent", code) },
            hostOf = { cacheHost() },
            readApi = ::readOffExecutor,
            agent = true,
        )
    }

    /** 上次触发 Agent 聊天重读时的 (revision, 该 Agent 的 live 工作项)；只在它变化或兜底轮询时重读。executor 线程读写。 */
    private var agentSignal: String? = null

    /**
     * Agent 会话没有 stream/message（dsbot-member-agents）：state 应用后（executor 线程）按需重读打开中的 Agent 聊天——
     * revision 变化、该 Agent 的 work.agents 项变化（回复正在此时出现）或兜底轮询/写后补取（[quiet]）时才读，其他 Bot 的
     * live 不触发；Agent 已不在 state 里（本机或别处移除）则不再读取并回到团队页（仍停留在该页时）。
     */
    private fun refreshAgentChat(quiet: Boolean) {
        val current = page as? ShellPage.AgentChat ?: return
        if (!inForeground) return
        val raw = stateSnapshot?.raw ?: return
        if (work.keepwriting.core.DshAgents.agentOf(raw, current.agentId) == null) {
            mainHandler.post { if (page == current) goHome() }
            return
        }
        val agentWork = ((raw.members["work"] as? work.keepwriting.core.Json.Value.Object)?.members?.get("agents") as? work.keepwriting.core.Json.Value.Object)?.members?.get(current.agentId)
        val signal = (lastState?.revision ?: "") + "|" + (agentWork?.let { work.keepwriting.core.Json.encode(it) } ?: "-")
        if (signal == agentSignal && !quiet) return
        agentSignal = signal
        agentChatController.pollRefresh()
    }

    fun openAgentChat(id: String) {
        backStack.clear()
        backStack.add(ShellPage.Team)
        flash = null
        page = ShellPage.AgentChat(id)
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
            onResult(work.keepwriting.core.DshMux.Result.Err("gateway/closed", tr("未连接", "Not connected")))
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
            onResult(parsed ?: work.keepwriting.core.DshMux.Result.Err("gateway/protocol", tr("server-response 无法解析", "server-response could not be read")))
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
            upload = { sessionId, name, mediaType, bytes, onResult ->
                executor.execute { uploadAttachment(sessionId, name, mediaType, bytes, onResult) }
            },
            onLoadFailure = { code -> graph.diagnostics.recordChatLoadError("room", code) },
            hostOf = { cacheHost() },
            readApi = ::readOffExecutor,
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

    /** feed 事件转发给私聊与群聊控制器（各自按 sessionId/botId 过滤）；notice 交应用内提醒。 */
    private fun forwardChatEvent(event: String, dataJson: String) {
        if (event == "notice") handleNotice(dataJson)
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
        inForeground = true
        executor.execute { machine.onForeground() }
    }

    fun onBackground() {
        inForeground = false // 兜底轮询随之停止（tick 检查）
        feedGeneration.incrementAndGet() // 代际作废：feed 线程靠谓词+读超时自然退出
        executor.execute { machine.onBackground() }
    }

    /** 返回栈（主线程读写）：团队页为根；聊天页总在团队页之上，详情/新建等页压在当前页之上。 */
    private val backStack = ArrayList<ShellPage>()

    private fun push(next: ShellPage) {
        backStack.add(page)
        page = next
        flash = null // 一次性提示只属于返回到的那一页
    }

    fun openChat(id: String) {
        backStack.clear()
        backStack.add(ShellPage.Team)
        flash = null
        page = ShellPage.Chat(id)
        // 记住上次打开的聊天（只存 id 与类型，启动预取用；dsbot-chat-cache）
        val kind = if (isRoom(id)) "room" else "bot"
        runCatching { graph.chatPrefs.edit().putString(LAST_CHAT_ID, id).putString(LAST_CHAT_KIND, kind).apply() }
    }

    // ---- 聊天流畅度（dsbot-chat-cache）：缓存主机、前台兜底轮询、启动预取 ----

    @Volatile private var inForeground = true
    private var prefetched = false
    private val pollStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 聊天读取的 IO 线程（dsbot-chat-cache）：网络不占状态 executor，缓存上屏与切换不被慢读取阻塞。 */
    private val readExecutor = Executors.newFixedThreadPool(2) { r -> Thread(r, "dsbot-read").apply { isDaemon = true } }

    /** POST /api/bot 同步调用（调用方线程执行）。 */
    private fun postBot(endpoint: String, payloadJson: String): DsbotApi.Result {
        val clean = graph.cleanUrl ?: return DsbotApi.Result.Failure(null, DsbotApi.Reason.NETWORK)
        return try {
            val res = graph.nativeSession.apiRequest(
                "POST", DsbotApi.url(clean, "/api/bot"),
                "application/json", DsbotApi.buildRequest(endpoint, payloadJson).toByteArray(),
                // 同步等模型的端点用长读超时（dsbot-memory）
                slow = endpoint in work.keepwriting.core.MemoryPanel.SLOW_ENDPOINTS,
            )
            DsbotApi.parseResponse(res.status, res.bodyText())
        } catch (_: Exception) {
            DsbotApi.Result.Failure(null, DsbotApi.Reason.NETWORK)
        }
    }

    /** 读取在 IO 线程执行，结果投回状态 executor 回调（单线程持有状态的约定不变）。 */
    private fun readOffExecutor(endpoint: String, payloadJson: String, onResult: (DsbotApi.Result) -> Unit) {
        readExecutor.execute {
            val result = postBot(endpoint, payloadJson)
            executor.execute { onResult(result) }
        }
    }

    /** 聊天里的文件与图片取回（dsbot-reader-files）：私聊与群聊共用，网络在读线程、状态在 executor。 */
    val media: ChatMedia by lazy {
        ChatMedia(::readOffExecutor, executor::execute, graph.cacheDir, { capabilities.has("image-preview") }, { h, p -> graph.nativeDial.dial(h, p) })
    }

    private fun cacheHost(): String? = graph.cleanUrl?.let { runCatching { java.net.URI(it).authority }.getOrNull() }

    private fun isRoom(id: String): Boolean = team?.sections.orEmpty()
        .flatMap { it.entries }.firstOrNull { it.id == id }?.kind == TeamHome.Entry.Kind.ROOM

    /** 前台兜底轮询：每 15 秒一次，前台且在线时静默补 state 增量并刷新当前聊天页（executor 线程）。 */
    private fun startPolling() {
        if (!pollStarted.compareAndSet(false, true)) return
        scheduler.scheduleWithFixedDelay(
            { executor.execute { pollTick() } },
            POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS,
        )
    }

    /** 上一个兜底轮询的 state 请求尚未处理完时不再发起新的（网络半通时 tick 不堆积）。 */
    private val pollBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun pollTick() {
        if (!inForeground || status != DsbotConnection.Status.ONLINE) return
        if (!pollBusy.compareAndSet(false, true)) return
        val base = lastState
        // state 请求的网络在 IO 线程执行，不占状态 executor；应答投回 executor 合并
        readExecutor.execute {
            val parsed = requestState(base?.revision, base?.liveKey)
            executor.execute {
                try {
                    if (status == DsbotConnection.Status.ONLINE && parsed != null) applyStateResult(parsed, quiet = true)
                    val current = page as? ShellPage.Chat
                    if (current != null && inForeground && status == DsbotConnection.Status.ONLINE) {
                        if (isRoom(current.targetId)) roomChatController.pollRefresh() else chatController.pollRefresh()
                    }
                } finally {
                    pollBusy.set(false)
                }
            }
        }
    }

    /** 首次进入在线时预取上次打开的聊天首页进缓存（不跳转）。 */
    private fun prefetchLastChat() {
        if (prefetched) return
        prefetched = true
        val prefs = runCatching { graph.chatPrefs }.getOrNull() ?: return
        val id = prefs.getString(LAST_CHAT_ID, null) ?: return
        if (prefs.getString(LAST_CHAT_KIND, "bot") == "room") roomChatController.prefetch(id) else chatController.prefetch(id)
    }

    fun openDetails(id: String) = push(ShellPage.Details(id))

    fun openNewBot() = push(ShellPage.NewBot)

    fun openNewRoom() = push(ShellPage.NewRoom)

    fun openTeamPrefs() = push(ShellPage.TeamPrefs)

    fun openSectionsManage() = push(ShellPage.SectionsManage)

    fun openMemory(owner: work.keepwriting.core.MemoryPanel.Owner, title: String) = push(ShellPage.Memory(owner, title))

    fun openMemoryHistory(owner: work.keepwriting.core.MemoryPanel.Owner) = push(ShellPage.MemoryHistory(owner))

    fun openPrivateArchives() = push(ShellPage.PrivateArchives)

    fun openAvatarLibrary(botId: String) = push(ShellPage.AvatarLibrary(botId))

    fun openFolderPick(target: FolderTarget) = push(ShellPage.FolderPick(target))

    fun openBotBrowser(botId: String) = push(ShellPage.BotBrowser(botId))

    fun openCapabilities() = push(ShellPage.Capabilities)

    fun openCapabilityEdit(name: String?) = push(ShellPage.CapabilityEdit(name))

    fun openBotCapabilities(botId: String) = push(ShellPage.BotCapabilities(botId))

    /** 从子页面带回详情页的一次性提示（如「文件夹已保存，将在新分段继续」），主线程读写。 */
    var flash by mutableStateOf<String?>(null)

    fun goBack() {
        page = if (backStack.isEmpty()) ShellPage.Team else backStack.removeAt(backStack.size - 1)
    }

    /** 回到团队页并清空返回栈（删除 Bot 后）。 */
    fun goHome() {
        backStack.clear()
        page = ShellPage.Team
    }

    /** Bot 管理写操作在途键：主线程登记（点按当场判重），executor 结束时移除。 */
    private val manageInFlight = androidx.compose.runtime.mutableStateListOf<String>()

    /**
     * Bot 管理写操作（dsbot-bot-manage），主线程调用：同键在途直接忽略；网络在读线程发出、不自动重试；
     * 成功后先静默补取一次 state 增量（结果尽快上屏，feed revision 照常驱动后续刷新），再回调 [onOk]；
     * 失败回调可读原因（服务端 message 优先）。回调都投回主线程。
     */
    fun manageAction(key: String, endpoint: String, payloadJson: String, onOk: (work.keepwriting.core.Json.Value) -> Unit, onError: (String) -> Unit) {
        manageActionRaw(key, endpoint, payloadJson) { result ->
            when (result) {
                is DsbotApi.Result.Ok -> onOk(result.value)
                is DsbotApi.Result.BotError -> onError(work.keepwriting.core.BotManage.errorText(result.code, result.message))
                is DsbotApi.Result.Failure -> onError(work.keepwriting.core.BotManage.errorText(null, null))
            }
        }
    }

    /** 写操作原始应答版本：同 manageAction 的在途去重、读线程发出与成功补拉 state，把原始 DsbotApi.Result 交回主线程。 */
    fun manageActionRaw(key: String, endpoint: String, payloadJson: String, onResult: (DsbotApi.Result) -> Unit) {
        if (key in manageInFlight) return
        manageInFlight.add(key)
        // 写请求在读线程发出（create/duplicate 可能耗时数秒，不占状态 executor）；补取 state 回到状态 executor
        readOffExecutor(endpoint, payloadJson) { result ->
            if (result is DsbotApi.Result.Ok) lastState.let { fetchStateOnce(it?.revision, it?.liveKey, quiet = true) }
            mainHandler.post {
                manageInFlight.remove(key)
                onResult(result)
            }
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 详情/新建页用的管理入口；efforts 读取在读线程，结果投回主线程。 */
    val manageApi: ManageApi by lazy {
        ManageApi(
            inFlight = manageInFlight,
            action = ::manageAction,
            efforts = { ref, cb ->
                readExecutor.execute {
                    val r = postBot("efforts", work.keepwriting.core.BotManage.Requests.efforts(ref))
                    val e = work.keepwriting.core.BotManage.efforts((r as? DsbotApi.Result.Ok)?.value)
                    mainHandler.post { cb(e) }
                }
            },
            read = { endpoint, payload, cb ->
                readExecutor.execute {
                    val r = postBot(endpoint, payload)
                    mainHandler.post { cb(r) }
                }
            },
            actionRaw = ::manageActionRaw,
        )
    }

    fun stop() {
        sideChatController.dispose()
        feedGeneration.incrementAndGet()
        scheduler.shutdownNow()
        readExecutor.shutdownNow()
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
        if (next == DsbotConnection.Status.ONLINE) {
            startPolling()
            prefetchLastChat()
        }
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
                    errorMessage = tr("连接失败", "Connection failed")
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

    override fun fetchState(localRevision: String?, liveKey: String?) = fetchStateOnce(localRevision, liveKey, quiet = false)

    /** quiet=true（聊天条写操作后的补拉）：网络失败只放弃本次，不当作 feed 断线触发重连。 */
    private fun fetchStateOnce(localRevision: String?, liveKey: String?, quiet: Boolean) {
        applyStateResult(requestState(localRevision, liveKey) ?: return, quiet)
    }

    /** state 请求（调用方线程执行网络）；网络异常返回 null（不动状态，下一轮事件重触发）。 */
    private fun requestState(localRevision: String?, liveKey: String?): DsbotApi.Result? {
        val clean = graph.cleanUrl ?: return null
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
            return null // 网络抖动：不动状态，下一轮事件重触发
        }
        return DsbotApi.parseResponse(res.status, res.bodyText())
    }

    /** 应用 state 应答（executor 线程）。quiet=true：网络失败只放弃本次，不当作 feed 断线。 */
    private fun applyStateResult(parsed: DsbotApi.Result, quiet: Boolean) {
        when (parsed) {
            is DsbotApi.Result.Ok -> {
                val full = DsbotModels.decodeState(parsed.value)
                val delta = DsbotModels.decodeStateDelta(parsed.value)
                when {
                    full != null -> {
                        machine.onStateFetched(full)
                        lastState = full
                        stateSnapshot = full
                        team = TeamHome.assemble(full)
                    }
                    delta != null -> {
                        // 增量应答：合并 live 字段集后重组装（unchanged 无 live 字段时列表不变）
                        machine.onStateFetched(delta)
                        val current = lastState
                        if (current != null) {
                            val merged = DsbotModels.mergeDelta(current, delta)
                            lastState = merged
                            stateSnapshot = merged
                            team = TeamHome.assemble(merged)
                        }
                    }
                    else -> return // 未知形态：降级不崩溃，等下一轮事件
                }
                // 团队偏好（dsbot-prefs-theme）：供配色与聊天布局使用；只在变化时写，避免无谓重组
                val prefs = work.keepwriting.core.TeamPrefs.decode(stateSnapshot?.raw)
                if (prefs != graph.teamPrefs) graph.teamPrefs = prefs
                refreshAgentChat(quiet)
            }
            is DsbotApi.Result.BotError -> {}
            is DsbotApi.Result.Failure -> when (parsed.reason) {
                DsbotApi.Reason.AUTH_EXPIRED -> machine.onGate(DsbotConnection.NativeGate.AUTH_EXPIRED)
                else -> if (!quiet) {
                    // 取增量失败按断线语义交给退避接管，不落入无提示死态
                    scheduleReconnect(machine.onFeedDisconnected(), feedGeneration.get())
                }
            }
        }
    }

    private fun checkVersion(clean: String) {
        // update-check 读不到 → remote 为 null（UNKNOWN：不按版本提示）
        val remote = try {
            val res = graph.nativeSession.apiRequest(
                "POST", DsbotApi.url(clean, "/api/bot"),
                "application/json", DsbotApi.buildRequest("update-check").toByteArray(),
            )
            (DsbotApi.parseResponse(res.status, res.bodyText()) as? DsbotApi.Result.Ok)
                ?.let { DsbotVersions.fromUpdateCheck(it.value) }
        } catch (_: Exception) {
            null
        }
        lastRemoteVersion = remote
        readCapabilities(clean)
    }

    /** 最近一次 update-check 读到的远端版本（能力重读后重算提示条用）。 */
    private var lastRemoteVersion: DsbotVersions.Remote? = null

    /** 读 hello 能力集合并重算版本提示条（executor 线程）。 */
    private fun readCapabilities(clean: String) {
        capabilities = try {
            val res = graph.nativeSession.apiRequest(
                "POST", DsbotApi.url(clean, "/api/bot"),
                "application/json", DsbotApi.buildRequest("hello", DsbotHello.REQUEST_PAYLOAD).toByteArray(),
            )
            Capabilities.from(DsbotApi.parseResponse(res.status, res.bodyText()))
        } catch (_: Exception) {
            Capabilities.LEGACY
        }
        versionBanner = DsbotVersions.banner(lastRemoteVersion, capabilities)
    }

    /** feed 首帧 hello：远端重启/换插件（boot 变化或不再带 boot）时整体重读版本与能力集合；
     *  再按 (boot, seq) 游标决定提醒补齐或重取整体状态（dsbot-notices-banner）。 */
    override fun onFeedHello(hello: DsbotHello.FeedHello) {
        if (capabilities.shouldReread(hello)) graph.cleanUrl?.let(::checkVersion)
        when (val action = noticeCursor.onHello(hello.boot, hello.seq)) {
            is NoticeCursor.HelloAction.Fill -> if (capabilities.has(NOTICES_CAPABILITY)) fetchNotices(action.boot, action.since)
            NoticeCursor.HelloAction.Reset -> fetchStateOnce(null, null, quiet = true)
            NoticeCursor.HelloAction.None -> {}
        }
    }

    // ---- 应用内提醒（dsbot-notices-banner；executor 线程读写）----

    /** 横幅：id 每次递增（重新计时 8 秒），text 为「名称 · 类型：摘要」，target 为点按进入的聊天。 */
    data class NoticeBanner(val id: Long, val text: String, val target: String)

    var noticeBanner by mutableStateOf<NoticeBanner?>(null)
        private set
    private val noticeCursor = NoticeCursor()
    private var noticeId = 0L

    fun dismissNotice(id: Long) {
        executor.execute { if (noticeBanner?.id == id) noticeBanner = null }
    }

    /** 点按横幅（主线程）：进入目标聊天。 */
    fun openNotice(banner: NoticeBanner) {
        dismissNotice(banner.id)
        openChat(banner.target)
    }

    private fun bannerEnabled(): Boolean =
        runCatching { graph.noticePrefs.getBoolean(NOTICE_BANNER_KEY, true) }.getOrDefault(true)

    private fun handleNotice(dataJson: String) {
        if (!capabilities.has(NOTICES_CAPABILITY)) return
        val notice = Notices.decode(work.keepwriting.core.Json.parse(dataJson)) ?: return
        if (!noticeCursor.accept(notice)) return // 重复或乱序
        showNotices(listOf(notice))
    }

    /** 从一批新提醒中经 core 的 pickBanner 选出要显示的一条（白名单内、非当前聊天的最后一条）；
     *  开关关闭时不显示；新提醒替换旧横幅并重新计时。 */
    private fun showNotices(fresh: List<Notices.Notice>) {
        if (!bannerEnabled()) return
        val notice = Notices.pickBanner(fresh, (page as? ShellPage.Chat)?.targetId) ?: return
        noticeBanner = NoticeBanner(++noticeId, Notices.banner(notice), Notices.target(notice))
    }

    /** 同一 boot 断线重连：notices {boot, since} 补齐（IO 线程请求，应答回 executor），只显示最新一条。 */
    private fun fetchNotices(boot: String, since: Long) {
        val payload = "{\"boot\":${work.keepwriting.core.JsonString.of(boot)},\"since\":$since}"
        readExecutor.execute {
            val result = postBot("notices", payload)
            executor.execute {
                // 请求在途期间远端已重启并 Reset 过：旧 boot 的应答作废，免得游标被改回旧 boot
                if (noticeCursor.boot != boot) return@execute
                val batch = (result as? DsbotApi.Result.Ok)?.let { Notices.decodeBatch(it.value) } ?: return@execute
                val outcome = noticeCursor.onBatch(batch)
                if (outcome.refetch) {
                    fetchStateOnce(null, null, quiet = true) // 缓冲越界或陌生 boot：重取整体状态，不补弹
                } else {
                    showNotices(outcome.fresh)
                }
            }
        }
    }

    private fun reasonText(reason: ProbeFailure.Reason): String = when (reason) {
        ProbeFailure.Reason.DNS -> tr("无法解析主机名", "Cannot resolve host")
        ProbeFailure.Reason.REFUSED -> tr("连接被拒绝", "Connection refused")
        ProbeFailure.Reason.TIMEOUT -> tr("连接超时", "Connection timed out")
        ProbeFailure.Reason.TLS -> tr("安全连接（TLS）失败", "Secure connection (TLS) failed")
        else -> tr("连接失败", "Connection failed")
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

// dsbot-chat-cache：上次打开的聊天偏好键与兜底轮询间隔
private const val LAST_CHAT_ID = "lastChatId"
private const val LAST_CHAT_KIND = "lastChatKind"
private const val POLL_SECONDS = 15L

// dsbot-notices-banner：能力名与「应用内提醒」开关键
private const val NOTICES_CAPABILITY = "notices"
const val NOTICE_BANNER_KEY = "bannerEnabled"
private const val NOTICE_BANNER_MS = 8_000L
