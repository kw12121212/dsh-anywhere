package work.keepwriting.dshanywhere

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import work.keepwriting.core.DshAgents
import work.keepwriting.core.Json

/**
 * DSH Agent 聊天页（dsbot-member-agents）：复用私聊页的 Agent 模式（agent-chat 读取、发送箱、stop-agent），
 * 「会话」菜单提供新会话、重命名当前会话、归档当前会话与移除 DSH Agent（不可逆操作二次确认）。
 * Agent 会话没有 stream/message 推送：连接层在 live/revision 与兜底轮询时刷新 [controller]。
 */
@Composable
fun AgentChatPage(
    controller: ChatController,
    state: Json.Value.Object?,
    agentId: String,
    api: ManageApi,
    onOpenLink: (String, Boolean) -> Unit,
    media: ChatMedia?,
    onRemoved: () -> Unit,
) {
    val agent = DshAgents.agentOf(state, agentId)
    // 工作中状态取 live work.agents
    val running = DshAgents.running(state, agentId)
    LaunchedEffect(running) { controller.syncWorking(running) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 会话标题不在 state 里，App 看不到：重命名成功给一条提示
    var info by remember { mutableStateOf<String?>(null) }
    fun act(key: String, endpoint: String, payload: String, onOk: (Json.Value) -> Unit = {}) {
        error = null
        api.action("agent:$agentId:$key", endpoint, payload, onOk) { error = it }
    }
    val session = agent?.currentSession

    Column(modifier = Modifier.fillMaxSize()) {
        Text(agent?.name ?: "DSH Agent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        ErrorLine(error) { error = null }
        InfoLine(info, error = false) { info = null }
        BotChatPage(
            controller = controller,
            botId = agentId,
            parts = emptyList(),
            onOpenLink = onOpenLink,
            state = state,
            entrySessionId = session,
            media = media,
            agentMode = true,
            onAgentMenu = { menu = true },
        )
    }

    if (menu) {
        AlertDialog(
            onDismissRequest = { menu = false },
            title = { Text(tr("会话", "Sessions")) },
            text = {
                Column {
                    TextButton(onClick = {
                        menu = false
                        act("new", "agent-session", DshAgents.Requests.newSession(agentId)) { controller.sessionChanged() }
                    }) { Text(tr("新会话", "New session")) }
                    if (session != null) {
                        TextButton(onClick = { menu = false; renaming = true }) { Text(tr("重命名当前会话", "Rename this session")) }
                        TextButton(onClick = {
                            menu = false
                            confirm = tr("归档当前会话？归档后显示上一个会话。", "Archive this session? The previous session is shown next.") to {
                                act("archive", "agent-archive", DshAgents.Requests.archive(agentId, session)) { controller.sessionChanged() }
                            }
                        }) { Text(tr("归档当前会话", "Archive this session")) }
                    }
                    TextButton(onClick = {
                        menu = false
                        confirm = tr("移除 DSH Agent？它的会话都会归档。", "Remove the DSH Agent? All its sessions are archived.") to {
                            act("remove", "remove-agent", DshAgents.Requests.remove(agentId)) { onRemoved() }
                        }
                    }) { Text(tr("移除 DSH Agent", "Remove the DSH Agent"), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { menu = false }) { Text(tr("关闭", "Close")) } },
        )
    }
    if (renaming && session != null) {
        var title by remember { mutableStateOf("") }
        val problem = DshAgents.titleProblem(title)
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(tr("重命名当前会话", "Rename this session")) },
            text = {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true,
                    isError = title.isNotEmpty() && problem != null,
                    supportingText = if (title.isNotEmpty() && problem != null) ({ Text(problem) }) else null,
                )
            },
            confirmButton = {
                TextButton(enabled = problem == null, onClick = {
                    renaming = false
                    act("rename", "agent-rename", DshAgents.Requests.rename(agentId, session, title)) { info = tr("已重命名为「${title.trim()}」", "Renamed to \"${title.trim()}\"") }
                }) { Text(tr("保存", "Save")) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(tr("取消", "Cancel")) } },
        )
    }
    confirm?.let { (text, run) ->
        ConfirmDialog(tr("请确认", "Please confirm"), text, tr("确认", "Confirm"), onConfirm = run, onDismiss = { confirm = null })
    }
}
