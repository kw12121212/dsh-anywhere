package work.keepwriting.dshanywhere

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.DsbotSystem
import work.keepwriting.core.I18n
import work.keepwriting.core.Json
import work.keepwriting.core.ModelProviders
import work.keepwriting.core.Schedules
import work.keepwriting.core.SettingsOverview
import work.keepwriting.core.Usage
import java.time.ZoneId

/*
 * Settings 概览页（dsbot-settings-overview）：对照 DS-Bot 0.3.10 client.js OverviewPage 与 settings.js
 * 汇总团队成员、需要处理告警、状态汇总磁贴（模型/连接/工具/近 7 天用量/定时任务/外观）以及分组设置页面列表与状态徽标。
 * 不新增端点：静态数据来自 stateSnapshot，按需并行读取 providers / usage（经 UsageStore 单飞缓存）/ schedules / update-check；
 * 代次号防乱序，失败区块降级保持其余部分可用，不自动重试。
 */

@Composable
fun SettingsOverviewPage(
    state: Json.Value.Object?,
    api: ManageApi,
    usageStore: UsageStore,
    cachedUpdate: DsbotSystem.Update?,
    onUpdateLoaded: (DsbotSystem.Update) -> Unit,
    visible: Boolean,
    onNavigate: (SettingsOverview.NavTarget) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    var providers by remember { mutableStateOf<ModelProviders.View?>(null) }
    var providersLoaded by remember { mutableStateOf(false) }
    var providersUnsupported by remember { mutableStateOf(false) }
    var usageView by remember { mutableStateOf(usageStore.view) }
    var usageUnsupported by remember { mutableStateOf(false) }
    var schedulesView by remember { mutableStateOf<Schedules.View?>(null) }
    var schedulesUnsupported by remember { mutableStateOf(false) }
    var updateView by remember { mutableStateOf(cachedUpdate) }
    var round by remember { mutableIntStateOf(0) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val gen = remember { intArrayOf(0) }

    LaunchedEffect(cachedUpdate) {
        if (cachedUpdate != null) updateView = cachedUpdate
    }

    // 页面可见或手动刷新时并行读取按需端点；代次号防止慢应答覆盖新轮次，bot/not-found 隐藏对应项，其余失败降级。
    LaunchedEffect(visible, round) {
        if (!visible) return@LaunchedEffect
        val mine = ++gen[0]
        nowMs = System.currentTimeMillis()
        api.read("providers", ModelProviders.Requests.list()) { r ->
            if (mine != gen[0]) return@read
            when {
                SettingsOverview.isUnsupported(r) -> {
                    providersUnsupported = true
                    providersLoaded = true
                }
                r is DsbotApi.Result.Ok -> ModelProviders.decode(r.value)?.let { v ->
                    providersUnsupported = false
                    providers = v
                    providersLoaded = true
                }
            }
        }
        api.read("schedules", Schedules.Requests.list()) { r ->
            if (mine != gen[0]) return@read
            nowMs = System.currentTimeMillis()
            when {
                SettingsOverview.isUnsupported(r) -> schedulesUnsupported = true
                r is DsbotApi.Result.Ok -> Schedules.decode(r.value)?.let { v ->
                    schedulesUnsupported = false
                    schedulesView = v
                }
            }
        }
        if (updateView == null || round > 0) {
            api.read("update-check", DsbotSystem.Requests.check(false)) { r ->
                if (mine != gen[0]) return@read
                (r as? DsbotApi.Result.Ok)?.let { ok ->
                    DsbotSystem.decodeUpdate(ok.value)?.let { u ->
                        updateView = u
                        onUpdateLoaded(u)
                    }
                }
            }
        }
        val usageResult = usageStore.load(api, zone)
        if (isActive && mine == gen[0]) {
            when {
                SettingsOverview.isUnsupported(usageResult) -> usageUnsupported = true
                usageResult is DsbotApi.Result.Ok -> Usage.decode(usageResult.value)?.let { v ->
                    usageUnsupported = false
                    usageStore.view = v
                    usageView = v
                }
            }
        }
    }

    // usage 扫描中且页面可见、App 在前台时每 3 秒单飞重读，离开、退后台或遇瞬态失败即停止自动重试，点「刷新」可重新拉起。
    LaunchedEffect(usageView?.scanning, visible, round) {
        if (usageView?.scanning != true || !visible) return@LaunchedEffect
        while (isActive) {
            delay(SettingsOverview.SCAN_POLL_MS)
            val usageResult = usageStore.load(api, zone)
            if (!isActive) break
            when {
                SettingsOverview.isUnsupported(usageResult) -> {
                    usageUnsupported = true
                    break
                }
                usageResult is DsbotApi.Result.Ok -> {
                    val decoded = Usage.decode(usageResult.value) ?: break
                    usageUnsupported = false
                    usageStore.view = decoded
                    usageView = decoded
                }
                else -> break
            }
        }
    }

    val lang = I18n.lang
    val snapshot = remember(
        state,
        providers,
        providersLoaded,
        providersUnsupported,
        usageView,
        usageUnsupported,
        schedulesView,
        schedulesUnsupported,
        updateView,
        nowMs,
        zone,
        lang,
    ) {
        SettingsOverview.derive(
            SettingsOverview.Inputs(
                state = state,
                providers = providers,
                providersLoaded = providersLoaded,
                providersUnsupported = providersUnsupported,
                usage = usageView,
                usageUnsupported = usageUnsupported,
                schedules = schedulesView,
                schedulesUnsupported = schedulesUnsupported,
                update = updateView,
                nowMs = nowMs,
                zone = zone,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("概览", "Overview"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { round++ }) {
                Text(tr("刷新", "Refresh"))
            }
        }

        if (snapshot.alerts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("需要处理", "Needs your attention"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                for (alert in snapshot.alerts) {
                    AlertCard(alert = alert, onClick = { onNavigate(alert.target) })
                }
            }
        }

        TeamSummaryCard(team = snapshot.team, onNavigate = onNavigate)

        TilesGrid(snapshot = snapshot, onNavigate = onNavigate)

        for (group in snapshot.groups) {
            GroupSection(group = group, onNavigate = onNavigate)
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AlertCard(alert: SettingsOverview.Alert, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    alert.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                alert.meta?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                alert.actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun TeamSummaryCard(team: SettingsOverview.TeamSummary, onNavigate: (SettingsOverview.NavTarget) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("团队", "Team"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onNavigate(SettingsOverview.NavTarget.TeamHome) }) {
                Text(
                    tr(
                        "Bot · ${team.botCount}   群聊 · ${team.roomCount}",
                        "Bots · ${team.botCount}   Group chats · ${team.roomCount}",
                    ),
                )
            }
        }
        val items = buildList<@Composable (Modifier) -> Unit> {
            for (bot in team.shownBots) {
                add { mod ->
                    BotSlotCard(bot = bot, modifier = mod, onClick = { onNavigate(SettingsOverview.NavTarget.BotDetails(bot.id)) })
                }
            }
            if (team.moreCount > 0) {
                add { mod ->
                    ExtraSlotCard(
                        badge = "+${team.moreCount}",
                        label = tr("全部 Bot", "All Bots"),
                        modifier = mod,
                        onClick = { onNavigate(SettingsOverview.NavTarget.TeamHome) },
                    )
                }
            }
            add { mod ->
                ExtraSlotCard(
                    badge = "＋",
                    label = tr("新建 Bot", "New Bot"),
                    modifier = mod,
                    onClick = { onNavigate(SettingsOverview.NavTarget.NewBot) },
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (rowItems in items.chunked(3)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (slot in rowItems) slot(Modifier.weight(1f))
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun BotSlotCard(bot: SettingsOverview.BotSlot, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                Surface(
                    color = teamColorOf(bot.color, MaterialTheme.colorScheme.primary),
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp),
                ) {}
                if (bot.needsKey) {
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        shape = CircleShape,
                        modifier = Modifier.size(10.dp),
                    ) {}
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (bot.isMain) "${bot.name} · ${tr("主", "Main")}" else bot.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                bot.modelLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ExtraSlotCard(badge: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Text(badge, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(" ", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TilesGrid(snapshot: SettingsOverview.Snapshot, onNavigate: (SettingsOverview.NavTarget) -> Unit) {
    val tiles = buildList<@Composable (Modifier) -> Unit> {
        add { mod ->
            val t = snapshot.modelsTile
            SummaryTile(
                title = tr("模型", "Models"),
                value = "${t.usableCount}",
                unit = tr("个模型", "models"),
                chips = t.chips,
                modifier = mod,
                onClick = { onNavigate(t.target) },
            )
        }
        snapshot.connectTile?.let { t ->
            add { mod ->
                SummaryTile(
                    title = tr("连接", "Connect"),
                    value = "${t.connectedCount}",
                    unit = tr("已连接", "connected"),
                    chips = t.chips,
                    modifier = mod,
                    onClick = { onNavigate(t.target) },
                )
            }
        }
        snapshot.toolsTile?.let { t ->
            add { mod ->
                SummaryTile(
                    title = tr("工具", "Tools"),
                    value = "${t.capabilityCount}",
                    unit = tr("项能力", "capabilities"),
                    chips = t.chips,
                    modifier = mod,
                    onClick = { onNavigate(t.target) },
                )
            }
        }
        snapshot.usageTile?.let { t ->
            add { mod ->
                SummaryTile(
                    title = tr("近 7 天", "Last 7 days"),
                    value = t.totalText,
                    unit = "tokens",
                    footText = t.todayText,
                    extra = if (t.barPercents.isNotEmpty()) {
                        {
                            Row(
                                modifier = Modifier.height(28.dp).width(56.dp),
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                t.barPercents.forEachIndexed { idx, pct ->
                                    val isToday = idx == t.barPercents.lastIndex
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight((pct / 100f).coerceIn(0.08f, 1f))
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(
                                                if (isToday) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outlineVariant,
                                            ),
                                    )
                                }
                            }
                        }
                    } else null,
                    modifier = mod,
                    onClick = { onNavigate(SettingsOverview.NavTarget.Usage) },
                )
            }
        }
        snapshot.schedulesTile?.let { t ->
            add { mod ->
                SummaryTile(
                    title = tr("定时任务", "Scheduled tasks"),
                    value = t.activeCount?.toString() ?: "–",
                    unit = tr("个在跑", "active"),
                    footText = t.nextHint,
                    modifier = mod,
                    onClick = { onNavigate(SettingsOverview.NavTarget.Schedules) },
                )
            }
        }
        add { mod ->
            val t = snapshot.appearanceTile
            SummaryTile(
                title = tr("外观", "Appearance"),
                value = t.themeLabel,
                unit = null,
                footText = t.meta,
                extra = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ThemeSwatchDot(bg = t.lightBg, accent = t.lightAccent)
                        ThemeSwatchDot(bg = t.darkBg, accent = t.darkAccent)
                    }
                },
                modifier = mod,
                onClick = { onNavigate(SettingsOverview.NavTarget.TeamPrefs) },
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (rowTiles in tiles.chunked(2)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (tile in rowTiles) tile(Modifier.weight(1f))
                if (rowTiles.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ThemeSwatchDot(bg: Int, accent: Int) {
    Surface(
        color = Color(0xFF000000L or (bg.toLong() and 0xFFFFFFL)),
        shape = CircleShape,
        tonalElevation = 1.dp,
        modifier = Modifier.size(22.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF000000L or (accent.toLong() and 0xFFFFFFL))),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryTile(
    title: String,
    value: String,
    unit: String?,
    chips: List<SettingsOverview.Chip> = emptyList(),
    footText: String? = null,
    extra: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        value,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (unit != null) {
                        Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                extra?.invoke()
            }
            if (chips.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (chip in chips) {
                        val (bg, fg) = when (chip.tone) {
                            SettingsOverview.ChipTone.OK -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                            SettingsOverview.ChipTone.WARN -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                            SettingsOverview.ChipTone.DEFAULT -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
                            Text(
                                chip.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = fg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            } else if (!footText.isNullOrBlank()) {
                Text(
                    footText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun GroupSection(group: SettingsOverview.PageGroup, onNavigate: (SettingsOverview.NavTarget) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            group.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Column {
                group.rows.forEachIndexed { idx, row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigate(row.target) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(row.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        row.badge?.let { badge ->
                            val badgeColor = when (row.tone) {
                                SettingsOverview.RowTone.ERROR -> MaterialTheme.colorScheme.error
                                SettingsOverview.RowTone.WARN -> MaterialTheme.colorScheme.tertiary
                                SettingsOverview.RowTone.OK -> MaterialTheme.colorScheme.primary
                                SettingsOverview.RowTone.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                badge,
                                style = MaterialTheme.typography.bodySmall,
                                color = badgeColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (idx < group.rows.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
            }
        }
    }
}
