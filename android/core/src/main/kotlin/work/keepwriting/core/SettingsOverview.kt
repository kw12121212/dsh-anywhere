package work.keepwriting.core

import java.time.Instant
import java.time.ZoneId

/**
 * Settings 概览页（dsbot-settings-overview）：对照 DS-Bot 0.3.10 client.js OverviewPage 与
 * settings.js 的纯逻辑派生——需要处理告警（Attention）、团队 Bot 槽位（Team）、6 个状态汇总磁贴
 * （模型、连接、工具、近 7 天用量、定时任务、外观）以及分组设置列表与实时状态徽标（HomeList）。
 * 不新增 /api/bot 端点，全部由既有 state/live 字段与 providers/usage/schedules/update-check 派生。
 */
object SettingsOverview {
    /** Team 概览区最多显示的槽位数（与 client.js TEAM_SLOTS = 6 一致，含「新建 Bot」槽位）。 */
    const val TEAM_SLOTS = 6

    /** usage.scanning 为真时的静默刷新间隔（与 client.js useWeek 的 3s 一致）。 */
    const val SCAN_POLL_MS = 3_000L

    sealed interface NavTarget {
        data object TeamHome : NavTarget
        data object NewBot : NavTarget
        data class BotDetails(val botId: String) : NavTarget
        data object TeamPrefs : NavTarget
        data object TeamWorkspace : NavTarget
        data object SectionsManage : NavTarget
        data object ModelProviders : NavTarget
        data object BotModels : NavTarget
        data object LocalAgents : NavTarget
        data object DeepSeekServices : NavTarget
        data object Schedules : NavTarget
        data object PrivateArchives : NavTarget
        data object Connectors : NavTarget
        data object Im : NavTarget
        data object McpAccess : NavTarget
        data object ComputerSettings : NavTarget
        data object Capabilities : NavTarget
        data object Secrets : NavTarget
        data object Usage : NavTarget
        data object AboutDsBot : NavTarget
        data object Diagnostics : NavTarget
    }

    enum class ChipTone { DEFAULT, OK, WARN }
    data class Chip(val label: String, val tone: ChipTone = ChipTone.DEFAULT)

    data class Alert(
        val id: String,
        val title: String,
        val meta: String?,
        val actionLabel: String,
        val target: NavTarget,
    )

    data class BotSlot(
        val id: String,
        val name: String,
        val color: String?,
        val isMain: Boolean,
        val needsKey: Boolean,
        val modelLine: String,
    )

    data class TeamSummary(
        val botCount: Int,
        val roomCount: Int,
        val shownBots: List<BotSlot>,
        val moreCount: Int,
    )

    data class ModelsTile(val usableCount: Int, val chips: List<Chip>, val target: NavTarget)
    data class ConnectTile(val connectedCount: Int, val chips: List<Chip>, val target: NavTarget)
    data class ToolsTile(val capabilityCount: Int, val chips: List<Chip>, val target: NavTarget)

    data class WeekUsageTile(
        val totalText: String,
        val todayText: String?,
        val barPercents: List<Int>,
        val scanning: Boolean,
    )

    data class SchedulesTile(val activeCount: Int?, val nextHint: String?)

    data class AppearanceTile(
        val themeLabel: String,
        val lightBg: Int,
        val lightAccent: Int,
        val darkBg: Int,
        val darkAccent: Int,
        val meta: String,
    )

    enum class RowTone { NORMAL, OK, WARN, ERROR }

    data class PageRow(
        val id: String,
        val label: String,
        val badge: String? = null,
        val tone: RowTone = RowTone.NORMAL,
        val target: NavTarget,
    )

    data class PageGroup(val id: String, val title: String, val rows: List<PageRow>)

    data class Inputs(
        val state: Json.Value.Object?,
        val providers: ModelProviders.View? = null,
        val providersLoaded: Boolean = false,
        val providersUnsupported: Boolean = false,
        val usage: Usage.View? = null,
        val usageUnsupported: Boolean = false,
        val schedules: Schedules.View? = null,
        val schedulesUnsupported: Boolean = false,
        val update: DsbotSystem.Update? = null,
        val nowMs: Long = 0L,
        val zone: ZoneId = ZoneId.of("UTC"),
    )

    data class Snapshot(
        val alerts: List<Alert>,
        val team: TeamSummary,
        val modelsTile: ModelsTile,
        val connectTile: ConnectTile?,
        val toolsTile: ToolsTile?,
        val usageTile: WeekUsageTile?,
        val schedulesTile: SchedulesTile?,
        val appearanceTile: AppearanceTile,
        val groups: List<PageGroup>,
    )

    /** 按需读端点是否明确不支持（0.3.6 或旧服务端返回 bot/not-found）。 */
    fun isUnsupported(result: DsbotApi.Result): Boolean =
        result is DsbotApi.Result.BotError && result.code == "bot/not-found"

    private data class CatalogModel(
        val ref: String,
        val id: String,
        val name: String,
        val provider: String,
        val providerName: String,
        val needsKey: Boolean,
    )

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.bool(k: String) = (members[k] as? Json.Value.BoolValue)?.value == true
    private fun Json.Value.Object.obj(k: String) = members[k] as? Json.Value.Object
    private fun Json.Value.Object.arr(k: String) = (members[k] as? Json.Value.Array)?.items.orEmpty()
    private fun Json.Value.Object.strings(k: String) = arr(k).mapNotNull { (it as? Json.Value.StringValue)?.value }

    private fun catalogModels(state: Json.Value.Object?): List<CatalogModel> =
        state?.arr("models").orEmpty().mapNotNull { m ->
            val o = m as? Json.Value.Object ?: return@mapNotNull null
            val ref = o.str("ref")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val provider = o.str("provider")?.takeIf { it.isNotBlank() } ?: ref.substringBefore('/')
            val id = o.str("id")?.takeIf { it.isNotBlank() } ?: ref.substringAfter('/', ref)
            CatalogModel(
                ref = ref,
                id = id,
                name = o.str("name")?.takeIf { it.isNotBlank() } ?: id,
                provider = provider,
                providerName = o.str("providerName")?.takeIf { it.isNotBlank() } ?: provider,
                needsKey = o.bool("needsKey"),
            )
        }

    private fun modelDisplayName(m: CatalogModel): String =
        if (m.id == "default" && ModelProviders.isLocal(m.ref)) I18n.tr("CLI 默认", "CLI default") else m.name

    private fun botModelLine(botRef: String?, catalogByRef: Map<String, CatalogModel>): String {
        if (botRef.isNullOrBlank()) return I18n.tr("未选择模型", "No model picked yet")
        val served = catalogByRef[botRef]
        if (served != null) return modelDisplayName(served)
        val tail = botRef.substringAfter('/', botRef)
        return I18n.tr("$tail（不可用）", "$tail (not available)")
    }

    /** 从当前 state 与各按需读端点快照派生完整概览页状态。 */
    fun derive(inputs: Inputs): Snapshot {
        val state = inputs.state
        val models = catalogModels(state)
        val catalogByRef = models.associateBy { it.ref }
        val botRefs = ModelProviders.bots(state)
        val mainIds = state?.strings("mainBotIds").orEmpty().toSet()

        val usableModels = models.filter { !it.needsKey && !ModelProviders.isLocal(it.ref) }
        val hasProviders = !inputs.providersUnsupported && (inputs.providers?.available != false)
        val needsModel = !inputs.providersUnsupported && inputs.providers?.available == true && usableModels.isEmpty()
        val keyMissing = botRefs.any { b ->
            val ref = b.current
            ref != null && catalogByRef[ref]?.needsKey == true
        }

        val providerList = inputs.providers?.takeIf { it.available }?.providers.orEmpty()
        val missingProviders = providerList
            .filter { it.key != null && !it.key.configured }
            .map { p -> p to ModelProviders.users(p, botRefs) }
            .filter { it.second.isNotEmpty() }

        // ---- Attention ----
        val alerts = buildList {
            for ((provider, users) in missingProviders) {
                val sep = if (I18n.lang == ChatBars.Lang.EN) ", " else "、"
                val names = users.joinToString(sep) { it.name }
                add(
                    Alert(
                        id = "provider-key:${provider.id}",
                        title = I18n.tr("${provider.name} 需要 API key", "${provider.name} needs an API key"),
                        meta = I18n.tr("$names 在用", "Used by $names"),
                        actionLabel = I18n.tr("打开", "Open"),
                        target = NavTarget.ModelProviders,
                    ),
                )
            }
            if (isEmpty() && needsModel) {
                add(
                    Alert(
                        id = "add-provider",
                        title = I18n.tr("添加模型服务商", "Add a model provider"),
                        meta = null,
                        actionLabel = I18n.tr("去设置", "Set up"),
                        target = NavTarget.ModelProviders,
                    ),
                )
            }
            if (isEmpty() && !needsModel && keyMissing && inputs.providersLoaded) {
                add(
                    Alert(
                        id = "needs-key-blind",
                        title = I18n.tr("需要 API key", "Needs an API key"),
                        meta = null,
                        actionLabel = I18n.tr("打开", "Open"),
                        target = NavTarget.BotModels,
                    ),
                )
            }
        }

        // ---- Team ----
        val botObjects = state?.arr("bots").orEmpty().mapNotNull { it as? Json.Value.Object }
        val botSlots = botObjects.mapNotNull { b ->
            val id = b.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ref = b.str("model") ?: b.str("appliedModel")
            val botNeedsKey = ref != null && catalogByRef[ref]?.needsKey == true
            BotSlot(
                id = id,
                name = b.str("name")?.takeIf { it.isNotBlank() } ?: id,
                color = b.str("color"),
                isMain = id in mainIds,
                needsKey = botNeedsKey,
                modelLine = botModelLine(ref, catalogByRef),
            )
        }
        val roomCount = state?.arr("rooms").orEmpty().count { it is Json.Value.Object }
        val hasMoreBots = botSlots.size + 1 > TEAM_SLOTS
        val shownBots = if (hasMoreBots) botSlots.take(TEAM_SLOTS - 2) else botSlots
        val teamSummary = TeamSummary(
            botCount = botSlots.size,
            roomCount = roomCount,
            shownBots = shownBots,
            moreCount = botSlots.size - shownBots.size,
        )

        // ---- Models tile ----
        val keylessNames = missingProviders.map { it.first.name }.distinct()
        val usableProviderNames = usableModels
            .map { it.providerName }
            .distinct()
            .filter { it !in keylessNames }
        val localBotCount = botRefs.count { ModelProviders.isLocal(it.current) }
        val modelChips = buildList {
            usableProviderNames.take(3).forEach { add(Chip(it)) }
            keylessNames.forEach { add(Chip(it, ChipTone.WARN)) }
            if (localBotCount > 0) add(Chip(I18n.tr("本地 $localBotCount", "Local $localBotCount")))
        }
        val modelsTile = ModelsTile(
            usableCount = usableModels.size,
            chips = modelChips,
            target = if (hasProviders) NavTarget.ModelProviders else NavTarget.BotModels,
        )

        // ---- Connect tile ----
        val hasConnectors = ConnectorSettings.has(state)
        val hasIm = ImBridges.has(state)
        val hasMcp = McpAccess.has(state)
        val connView = ConnectorSettings.view(state)
        val imView = ImBridges.view(state)
        val mailAccounts = connView?.mail?.accounts.orEmpty()
        val activeApps = connView?.rows.orEmpty().filter { it.state != ConnectorSettings.OFF }
        val imConns = imView?.connections.orEmpty()
        val phoneObj = state?.obj("phone")
        val phoneOn = phoneObj?.bool("on") == true
        val phoneOnline = phoneOn && phoneObj?.str("status") == "online" &&
            phoneObj.arr("phones").any { (it as? Json.Value.Object)?.bool("connected") == true }
        val connectTile = if (!hasConnectors && !hasIm && !hasMcp) {
            null
        } else {
            val connectedCount = mailAccounts.size + activeApps.size + imConns.size + (if (phoneOnline) 1 else 0)
            val chips = buildList {
                if (phoneOn) {
                    add(Chip(I18n.tr("手机", "Phone"), if (phoneOnline) ChipTone.OK else ChipTone.DEFAULT))
                }
                imConns.map { ImBridges.platformOf(it.platform).name }.distinct().take(2).forEach { add(Chip(it)) }
                activeApps.take(2).forEach { add(Chip(it.name)) }
                if (mailAccounts.isNotEmpty()) add(Chip(I18n.tr("邮箱", "Mailbox")))
            }
            val target = when {
                hasConnectors -> NavTarget.Connectors
                hasIm -> NavTarget.Im
                else -> NavTarget.McpAccess
            }
            ConnectTile(connectedCount = connectedCount, chips = chips, target = target)
        }

        // ---- Tools tile ----
        val hasCapabilities = CapabilityLibrary.hasLibrary(state)
        val hasComputer = ComputerSettings.has(state)
        val hasSecrets = SecretsSettings.hasSecrets(state)
        val browserOn = state?.bool("browser") == true
        val capabilityCount = if (hasCapabilities) CapabilityLibrary.library(state).size else 0
        val computerView = ComputerSettings.view(state)
        val computerOn = computerView?.enabled == true
        val secretsCount = if (hasSecrets) SecretsSettings.entries(state)?.size ?: 0 else 0
        val toolsTile = if (!hasCapabilities && !hasComputer && !hasSecrets && !browserOn) {
            null
        } else {
            val chips = buildList {
                if (browserOn) add(Chip(I18n.tr("浏览器", "Browser")))
                if (computerOn) add(Chip(I18n.tr("电脑操控", "Computer use")))
                if (secretsCount > 0) add(Chip(I18n.tr("密钥 $secretsCount", "Secrets $secretsCount")))
            }
            val target = when {
                hasCapabilities -> NavTarget.Capabilities
                hasComputer -> NavTarget.ComputerSettings
                hasSecrets -> NavTarget.Secrets
                else -> NavTarget.TeamHome
            }
            ToolsTile(capabilityCount = capabilityCount, chips = chips, target = target)
        }

        // ---- Week usage tile ----
        val usageTile = when {
            inputs.usageUnsupported -> null
            inputs.usage == null -> WeekUsageTile("–", null, emptyList(), scanning = false)
            else -> {
                val sum = Usage.summarize(inputs.usage, Usage.preset("7d"))
                val days = sum.columns.map { it.sum.total }
                val peak = maxOf(1L, days.maxOrNull() ?: 1L)
                val bars = days.map { day ->
                    maxOf(8, Math.round(day.toDouble() / peak.toDouble() * 100.0).toInt())
                }
                val todayCompact = Usage.compact(days.lastOrNull() ?: 0L)
                WeekUsageTile(
                    totalText = Usage.compact(sum.total.total),
                    todayText = I18n.tr("今天 $todayCompact", "Today $todayCompact"),
                    barPercents = bars,
                    scanning = inputs.usage.scanning,
                )
            }
        }

        // ---- Schedules tile ----
        val schedulesTile = when {
            inputs.schedulesUnsupported -> null
            inputs.schedules == null -> SchedulesTile(activeCount = null, nextHint = null)
            else -> {
                val active = inputs.schedules.tasks.filter { it.status == "active" }
                val soonest = active.minByOrNull { t ->
                    t.scheduledAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: Long.MAX_VALUE
                }
                SchedulesTile(
                    activeCount = active.size,
                    nextHint = soonest?.let { Schedules.nextText(it, inputs.nowMs, inputs.zone) },
                )
            }
        }

        // ---- Appearance tile ----
        val prefs = TeamPrefs.decode(state) ?: TeamPrefs.DEFAULT
        val light = TeamPrefs.palette(prefs, dark = false)
        val dark = TeamPrefs.palette(prefs, dark = true)
        val effects = state?.obj("prefs")?.str("effects")?.takeIf { it == "reduced" || it == "off" } ?: "full"
        val effectsLabel = when (effects) {
            "reduced" -> I18n.tr("减弱", "Reduced")
            "off" -> I18n.tr("关闭", "None")
            else -> null
        }
        val layoutText = TeamPrefs.layoutLabel(prefs.layout)
        val appearanceMeta = listOfNotNull(layoutText, effectsLabel).joinToString(" · ")
        val appearanceTile = AppearanceTile(
            themeLabel = TeamPrefs.themeLabel(prefs.theme),
            lightBg = light.bg,
            lightAccent = light.accent,
            darkBg = dark.bg,
            darkAccent = dark.accent,
            meta = appearanceMeta,
        )

        // ---- Grouped settings list ----
        val sectionsCount = TeamSections.decode(state).size
        val archivedCount = BotManage.privateArchives(state).size

        val generalRows = listOf(
            PageRow(
                id = "team-prefs",
                label = I18n.tr("团队偏好与外观", "Team preferences & appearance"),
                badge = "${appearanceTile.themeLabel} · $layoutText",
                target = NavTarget.TeamPrefs,
            ),
            PageRow(
                id = "team-workspace",
                label = I18n.tr("团队工作区", "Team workspace"),
                target = NavTarget.TeamWorkspace,
            ),
            PageRow(
                id = "sections",
                label = I18n.tr("分组管理", "Sections"),
                badge = if (sectionsCount > 0) "$sectionsCount" else null,
                target = NavTarget.SectionsManage,
            ),
        )

        val modelsRows = buildList {
            if (!inputs.providersUnsupported) {
                val (badge, tone) = if (needsModel || keyMissing) {
                    I18n.tr("需要处理", "Attention") to RowTone.WARN
                } else {
                    I18n.tr("${usableModels.size} 个模型", "${usableModels.size} models") to RowTone.NORMAL
                }
                add(PageRow("model-providers", I18n.tr("模型与服务商", "Models & providers"), badge, tone, NavTarget.ModelProviders))
            }
            add(PageRow("bot-models", I18n.tr("批量切换 Bot 模型", "Bot models"), null, RowTone.NORMAL, NavTarget.BotModels))
            add(
                PageRow(
                    id = "local-agents",
                    label = I18n.tr("本地 Agent", "Local agents"),
                    badge = if (localBotCount > 0) I18n.tr("本地 $localBotCount", "Local $localBotCount") else null,
                    target = NavTarget.LocalAgents,
                ),
            )
            add(PageRow("deepseek-services", I18n.tr("DeepSeek 服务", "DeepSeek services"), target = NavTarget.DeepSeekServices))
        }

        val teamRows = buildList {
            if (!inputs.schedulesUnsupported) {
                add(
                    PageRow(
                        id = "schedules",
                        label = I18n.tr("定时任务", "Scheduled tasks"),
                        badge = schedulesTile?.activeCount?.let { I18n.tr("$it 个在跑", "$it active") },
                        target = NavTarget.Schedules,
                    ),
                )
            }
            if (archivedCount > 0) {
                add(
                    PageRow(
                        id = "private-archives",
                        label = I18n.tr("已删除的私有 Bot", "Deleted private Bots"),
                        badge = "$archivedCount",
                        target = NavTarget.PrivateArchives,
                    ),
                )
            }
        }

        val connectRows = buildList {
            if (hasConnectors) {
                val hasError = connView?.rows.orEmpty().any {
                    it.state == ConnectorSettings.ERROR || it.state == ConnectorSettings.NEEDS_SIGN_IN
                } || mailAccounts.any { it.error != null }
                val totalConn = activeApps.size + mailAccounts.size
                val (badge, tone) = when {
                    hasError -> I18n.tr("需处理", "Error") to RowTone.ERROR
                    totalConn > 0 -> I18n.tr("$totalConn 已连接", "$totalConn connected") to RowTone.OK
                    else -> null to RowTone.NORMAL
                }
                add(PageRow("connectors", I18n.tr("连接器", "Connectors"), badge, tone, NavTarget.Connectors))
            }
            if (hasIm) {
                val hasError = imConns.any { c ->
                    ImBridges.stateOf(c, ImBridges.statusOf(state, c.id)) == "error"
                }
                val waitingCount = imView?.let { ImBridges.waiting(it).size } ?: 0
                val (badge, tone) = when {
                    hasError -> I18n.tr("出错", "Error") to RowTone.ERROR
                    waitingCount > 0 -> I18n.tr("$waitingCount 待允许", "$waitingCount pending") to RowTone.WARN
                    imConns.isNotEmpty() -> I18n.tr("${imConns.size} 个", "${imConns.size}") to RowTone.NORMAL
                    else -> null to RowTone.NORMAL
                }
                add(PageRow("im", I18n.tr("IM 接入", "IM"), badge, tone, NavTarget.Im))
            }
            if (hasMcp) {
                val mcpView = McpAccess.view(state)
                val (badge, tone) = when (mcpView?.let { McpAccess.status(it) }) {
                    is McpAccess.Status.Error -> I18n.tr("出错", "Error") to RowTone.ERROR
                    is McpAccess.Status.On -> I18n.tr("已开启", "On") to RowTone.OK
                    McpAccess.Status.Connecting -> I18n.tr("连接中", "Connecting") to RowTone.NORMAL
                    McpAccess.Status.Off -> I18n.tr("已关闭", "Off") to RowTone.NORMAL
                    null -> null to RowTone.NORMAL
                }
                add(PageRow("mcp", I18n.tr("MCP 访问", "MCP access"), badge, tone, NavTarget.McpAccess))
            }
        }

        val toolsRows = buildList {
            if (hasComputer) {
                val (badge, tone) = if (computerOn) {
                    I18n.tr("已开启", "On") to RowTone.OK
                } else {
                    I18n.tr("已关闭", "Off") to RowTone.NORMAL
                }
                add(PageRow("computer", I18n.tr("电脑操控", "Computer use"), badge, tone, NavTarget.ComputerSettings))
            }
            if (hasCapabilities) {
                val lib = CapabilityLibrary.library(state)
                val botIds = botRefs.map { it.id }
                val hasError = botIds.any { bid ->
                    CapabilityLibrary.botStatuses(state, bid).values.any { it.state == "error" }
                }
                val hasPending = lib.any { it.needsApproval }
                val (badge, tone) = when {
                    hasError -> I18n.tr("${lib.size} 项 · 出错", "${lib.size} · Error") to RowTone.ERROR
                    hasPending -> I18n.tr("${lib.size} 项 · 待批准", "${lib.size} · Pending") to RowTone.WARN
                    else -> I18n.tr("${lib.size} 项", "${lib.size}") to RowTone.NORMAL
                }
                add(PageRow("capabilities", I18n.tr("能力", "Capabilities"), badge, tone, NavTarget.Capabilities))
            }
            if (hasSecrets) {
                val locked = SecretsSettings.storeStatus(state)?.locked == true
                val (badge, tone) = when {
                    locked -> I18n.tr("已锁定", "Locked") to RowTone.WARN
                    secretsCount > 0 -> "$secretsCount" to RowTone.NORMAL
                    else -> null to RowTone.NORMAL
                }
                add(PageRow("secrets", I18n.tr("密钥", "Secrets"), badge, tone, NavTarget.Secrets))
            }
        }

        val updateAvailable = inputs.update?.bot?.available == true || inputs.update?.dsh?.available == true
        val aboutBadge = if (updateAvailable) I18n.tr("有更新", "Update") else inputs.update?.bot?.current
        val aboutTone = if (updateAvailable) RowTone.WARN else RowTone.NORMAL
        val systemRows = buildList {
            if (!inputs.usageUnsupported) {
                add(
                    PageRow(
                        id = "usage",
                        label = I18n.tr("用量", "Usage"),
                        badge = usageTile?.takeIf { it.totalText != "–" }?.totalText,
                        target = NavTarget.Usage,
                    ),
                )
            }
            add(PageRow("about", I18n.tr("关于 DS Bot", "About DS Bot"), aboutBadge, aboutTone, NavTarget.AboutDsBot))
            add(PageRow("diagnostics", I18n.tr("反馈与诊断", "Feedback & diagnostics"), target = NavTarget.Diagnostics))
        }

        val groups = buildList {
            add(PageGroup("general", I18n.tr("常规与外观", "General & appearance"), generalRows))
            add(PageGroup("models", I18n.tr("模型", "Models"), modelsRows))
            if (teamRows.isNotEmpty()) add(PageGroup("team", I18n.tr("团队", "Team"), teamRows))
            if (connectRows.isNotEmpty()) add(PageGroup("connect", I18n.tr("连接", "Connect"), connectRows))
            if (toolsRows.isNotEmpty()) add(PageGroup("tools", I18n.tr("工具", "Tools"), toolsRows))
            add(PageGroup("system", I18n.tr("用量与关于", "Usage & about"), systemRows))
        }

        return Snapshot(
            alerts = alerts,
            team = teamSummary,
            modelsTile = modelsTile,
            connectTile = connectTile,
            toolsTile = toolsTile,
            usageTile = usageTile,
            schedulesTile = schedulesTile,
            appearanceTile = appearanceTile,
            groups = groups,
        )
    }
}
