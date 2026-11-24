package work.keepwriting.dshanywhere

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import work.keepwriting.core.TeamHome

/**
 * 团队页（dsbot-team-home）：分组列表（字段存在时显示、缺失降级）、搜索、空团队首次
 * 创建引导。待办（审批/提问/卡片）直接在对应对话行的标签位提示（原「需要你」汇总入口
 * 已移除）。数据来自 NativeShellConnection 的 team 缓存（state 同步产物），本页无网络逻辑。
 */
@Composable
fun TeamPage(
    team: TeamHome.Team?,
    onOpenChat: (botId: String?) -> Unit,
    /** 本机计算的未读（dsbot-read-state）；组合期间调用以订阅已读状态。 */
    isUnread: (TeamHome.Entry) -> Boolean = { false },
    /** 新建 Bot（dsbot-bot-manage）。 */
    onNewBot: () -> Unit = {},
    /** 新建群聊（dsbot-group-manage）。 */
    onNewRoom: () -> Unit = {},
    /** 保留关闭的已删除私有 Bot 数（>0 时显示入口）。 */
    archivedPrivate: Int = 0,
    onOpenArchived: () -> Unit = {},
    /** 团队工作区（dsbot-folders）。 */
    onTeamWorkspace: () -> Unit = {},
    /** 团队偏好（dsbot-prefs-theme）。 */
    onTeamPrefs: () -> Unit = {},
    /** 团队的 DSH Agent（dsbot-member-agents；远端未声明 agents 能力时为空）。 */
    agents: List<work.keepwriting.core.DshAgents.Agent> = emptyList(),
    agentRunning: (String) -> Boolean = { false },
    canAddAgent: Boolean = false,
    /** 添加 DSH Agent；参数为失败回调（可读原因）。 */
    onAddAgent: (onError: (String) -> Unit) -> Unit = {},
    onOpenAgent: (String) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var agentError by remember { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        if (team != null) {
            // 入口按钮可横向滚动：窄屏上放不下时不挤压
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextButton(onClick = onNewBot) { Text(tr("＋ 新建 Bot", "+ New Bot")) }
                androidx.compose.material3.TextButton(onClick = onNewRoom) { Text(tr("＋ 新建群聊", "+ New group")) }
                if (canAddAgent) {
                    androidx.compose.material3.TextButton(onClick = { agentError = null; onAddAgent { agentError = it } }) { Text(tr("＋ DSH Agent", "+ DSH Agent")) }
                }
                androidx.compose.material3.TextButton(onClick = onTeamWorkspace) { Text(tr("团队工作区", "Team workspace")) }
                androidx.compose.material3.TextButton(onClick = onTeamPrefs) { Text(tr("团队偏好", "Team preferences")) }
                if (archivedPrivate > 0) {
                    androidx.compose.material3.TextButton(onClick = onOpenArchived) { Text(tr("已删除的私有 Bot（$archivedPrivate）", "Deleted private Bots ($archivedPrivate)")) }
                }
            }
            agentError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            for (a in agents) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onOpenAgent(a.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text("⌘", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (agentRunning(a.id)) tr("工作中", "Working") else tr("DSH Agent · ${a.sessions.size} 个会话", "DSH Agent · ${a.sessions.size} sessions"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.team_search_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        when {
            team == null -> CenteredHint(stringResource(R.string.team_loading))
            team.isEmpty -> EmptyTeamGuide()
            else -> TeamList(TeamHome.search(team, query), onOpenChat, isUnread)
        }
    }
}

@Composable
private fun TeamList(team: TeamHome.Team, onOpenChat: (String?) -> Unit, isUnread: (TeamHome.Entry) -> Boolean) {
    val sections = team.sections
    if (sections.all { it.entries.isEmpty() }) {
        CenteredHint(stringResource(R.string.team_search_empty))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        sections.forEachIndexed { idx, sec ->
            if (sec.entries.isEmpty()) return@forEachIndexed
            item(key = "sec:$idx:${sec.id ?: sec.name}") {
                Text(
                    sec.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(sec.entries, key = { it.id }) { e ->
                TeamEntryRow(e, unread = isUnread(e), onClick = { onOpenChat(e.id) })
            }
        }
    }
}

@Composable
private fun TeamEntryRow(e: TeamHome.Entry, unread: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Surface(
                color = colorOf(e.color),
                shape = CircleShape,
                modifier = Modifier.size(44.dp),
            ) {}
            Surface(
                color = if (e.working) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
                modifier = Modifier.size(12.dp),
            ) {}
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (e.isMain) Tag(stringResource(R.string.team_tag_main))
                if (e.isPrivate) Tag(stringResource(R.string.team_tag_private))
                // 待办行内提示：该对话有等你处理的审批/提问/卡片
                e.pendingKinds.forEach { kind ->
                    Tag(
                        text = stringResource(
                            when (kind) {
                                TeamHome.PendingItem.Kind.APPROVAL -> R.string.needs_you_approval
                                TeamHome.PendingItem.Kind.QUESTION -> R.string.needs_you_question
                                TeamHome.PendingItem.Kind.VIEW -> R.string.needs_you_view
                            },
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // 预览按优先级（dsbot-read-state）：等你回答/审批用强调色，其余为次要文字
            e.preview?.let { p ->
                Text(
                    p.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (p.waiting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (unread) {
            Spacer(Modifier.size(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                modifier = Modifier.size(10.dp).semantics { contentDescription = tr("未读", "Unread") },
            ) {}
        }
    }
}

@Composable
private fun Tag(text: String, color: Color = MaterialTheme.colorScheme.tertiary) {
    Text(
        " $text",
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@Composable
private fun CenteredHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyTeamGuide() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.team_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.team_empty_hint), style = MaterialTheme.typography.bodyMedium)
    }
}

/** 颜色名到头像圈颜色（DS-Bot 经典色系；未知色用主题色）。 */
@Composable
private fun colorOf(name: String?): Color = when (name) {
    "red" -> Color(0xFFE53935)
    "orange" -> Color(0xFFFB8C00)
    "yellow" -> Color(0xFFFDD835)
    "green" -> Color(0xFF43A047)
    "cyan" -> Color(0xFF00ACC1)
    "blue" -> Color(0xFF1E88E5)
    "violet" -> Color(0xFF8E24AA)
    "magenta" -> Color(0xFFD81B60)
    "gray", "charcoal", "graphite" -> Color(0xFF757575)
    "deepseek" -> Color(0xFF4D6BFE)
    else -> MaterialTheme.colorScheme.primary
}
