package work.keepwriting.dshanywhere

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import work.keepwriting.core.TeamHome

/**
 * 团队页（dsbot-team-home）：分组列表（字段存在时显示、缺失降级）、搜索、「需要你」
 * 汇总与跳转、工具栏刷新入口重取全量、空团队首次创建引导。数据来自
 * NativeShellConnection 的 team 缓存（state 同步产物），本页无网络逻辑。
 */
@Composable
fun TeamPage(
    team: TeamHome.Team?,
    onOpenChat: (botId: String?) -> Unit,
    onOpenNeedsYou: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.team_search_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            val pending = team?.pending?.size ?: 0
            Text(
                text = stringResource(R.string.needs_you_title) + (if (pending > 0) " ($pending)" else ""),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = pending > 0) { onOpenNeedsYou() }
                    .padding(8.dp),
            )
        }
        when {
            team == null -> CenteredHint(stringResource(R.string.team_loading))
            team.isEmpty -> EmptyTeamGuide()
            else -> TeamList(TeamHome.search(team, query), onOpenChat)
        }
    }
}

@Composable
private fun TeamList(team: TeamHome.Team, onOpenChat: (String?) -> Unit) {
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
                TeamEntryRow(e, onClick = { onOpenChat(e.id) })
            }
        }
    }
}

@Composable
private fun TeamEntryRow(e: TeamHome.Entry, onClick: () -> Unit) {
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
            }
            val summary = e.lastSummary
            if (!summary.isNullOrEmpty()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        e.unread?.let { unread ->
            Spacer(Modifier.size(8.dp))
            Badge { Text("$unread") }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        " $text",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
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

/** 需要你汇总页（dsbot-team-home）：待处理审批/提问/卡片，点击跳转对应 Bot 私聊。 */
@Composable
fun NeedsYouPage(pending: List<TeamHome.PendingItem>, onOpen: (String?) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.needs_you_page_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        if (pending.isEmpty()) {
            CenteredHint(stringResource(R.string.needs_you_empty))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(pending) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { item.botId?.let(onOpen) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when (item.kind) {
                                TeamHome.PendingItem.Kind.APPROVAL -> stringResource(R.string.needs_you_approval)
                                TeamHome.PendingItem.Kind.QUESTION -> stringResource(R.string.needs_you_question)
                                TeamHome.PendingItem.Kind.VIEW -> stringResource(R.string.needs_you_view)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            item.summary ?: "",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        item.sessionId?.let { sid ->
                            Text(
                                sid.takeLast(8),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
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
