package work.keepwriting.dshanywhere

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import work.keepwriting.core.GroupSearch
import work.keepwriting.core.Json
import work.keepwriting.core.RoomBoard
import work.keepwriting.core.RoomChat
import java.time.ZoneId

/**
 * 群共享待办与任务看板（dsbot-room-todos-tasks）：从群聊页工具行「待办与任务」进入的全屏页，
 * 上为共享待办（勾选、删除、清除已完成），下为任务看板（开放任务写备注与取消，历史可展开），
 * 与网页端 0.3.10 群概览的 Shared to-do 与 Tasks 两节一致。写操作经 ManageApi.action（在途键去重、不重试、
 * 成功后静默补取 state），页面始终按最新 state 渲染；纯逻辑在 core RoomBoard。
 */
@Composable
fun RoomBoardPage(
    roomId: String,
    state: Json.Value.Object?,
    api: ManageApi,
) {
    val room = remember(state, roomId) {
        (state?.members?.get("rooms") as? Json.Value.Array)?.items
            ?.firstOrNull { ((it as? Json.Value.Object)?.members?.get("id") as? Json.Value.StringValue)?.value == roomId }
            as? Json.Value.Object
    }
    val board = remember(room, work.keepwriting.core.I18n.lang) { RoomBoard.board(room) }
    val roster = remember(state) { RoomChat.roster(state) }
    val names = remember(roster) { roster.mapNotNull { (id, s) -> s.name?.let { id to it } }.toMap() }
    var error by remember(roomId) { mutableStateOf<String?>(null) }
    var historyOpen by remember(roomId) { mutableStateOf(false) }
    var writing by remember(roomId) { mutableStateOf<String?>(null) }
    var draft by remember(roomId) { mutableStateOf("") }
    var confirmCancel by remember(roomId) { mutableStateOf<RoomBoard.Task?>(null) }
    fun busy(key: String) = "$roomId:$key" in api.inFlight
    fun act(key: String, endpoint: String, payload: String, onOk: () -> Unit = {}) {
        error = null
        api.action("$roomId:$key", endpoint, payload, { onOk() }) { error = RoomBoard.errorText(it) }
    }
    val R = RoomBoard.Requests
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()

    if (room == null) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("找不到这个群聊，它可能已被删除", "This group chat was not found. It may have been deleted"))
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(RoomBoard.Copy.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        ErrorLine(error) { error = null }

        // ---- 共享待办 ----
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(RoomBoard.Copy.todosTitle)
                if (board.openTodos > 0) {
                    Text(RoomBoard.Copy.openCount(board.openTodos), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                }
            }
            if (board.canClear) {
                TextButton(enabled = !busy("todo-clear"), onClick = { act("todo-clear", "room-todo", R.todoClear(roomId)) }) { Text(RoomBoard.Copy.clearDone) }
            }
        }
        if (board.todos.isEmpty()) {
            Text(RoomBoard.Copy.todosEmpty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        for (todo in board.todos) {
            val rowBusy = busy("todo:${todo.id}")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp)) {
                Checkbox(
                    checked = todo.done,
                    enabled = !rowBusy,
                    onCheckedChange = { act("todo:${todo.id}", "room-todo", R.todoCheck(roomId, todo.id, !todo.done)) },
                )
                Text(
                    todo.text,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                    color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(enabled = !rowBusy, onClick = { act("todo:${todo.id}", "room-todo", R.todoRemove(roomId, todo.id)) }) {
                    Icon(Icons.Filled.Close, contentDescription = RoomBoard.Copy.delete)
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

        // ---- 任务看板 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(RoomBoard.Copy.tasksTitle)
            if (board.open.isNotEmpty()) {
                Text("${board.open.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        }
        if (board.open.isEmpty()) {
            Text(RoomBoard.Copy.tasksEmpty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        for (task in board.open) {
            TaskRow(
                task = task,
                member = RoomBoard.memberName(task, names),
                color = task.botId?.let { roster[it]?.color }?.let { speakerColor(it) },
                time = task.startedAt?.let { GroupSearch.timeLabel(it, now, zone) },
                busy = busy("task:${task.id}"),
                writing = writing == task.id,
                draft = if (writing == task.id) draft else "",
                onToggleWrite = {
                    if (writing == task.id) writing = null else { writing = task.id; draft = "" }
                },
                onDraft = { draft = it },
                onSend = {
                    val text = RoomBoard.noteText(draft) ?: return@TaskRow
                    act("task:${task.id}", "task-note", R.taskNote(roomId, task.id, text)) {
                        if (writing == task.id) { writing = null; draft = "" }
                    }
                },
                onCancel = { confirmCancel = task },
            )
        }
        if (board.past.isNotEmpty()) {
            TextButton(onClick = { historyOpen = !historyOpen }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(if (historyOpen) RoomBoard.Copy.hideHistory else RoomBoard.Copy.history(board.past.size))
            }
            if (historyOpen) {
                for (task in board.past) {
                    TaskRow(
                        task = task,
                        member = RoomBoard.memberName(task, names),
                        color = task.botId?.let { roster[it]?.color }?.let { speakerColor(it) },
                        time = task.startedAt?.let { GroupSearch.timeLabel(it, now, zone) },
                        busy = false, writing = false, draft = "",
                        onToggleWrite = {}, onDraft = {}, onSend = {}, onCancel = {},
                    )
                }
            }
        }
        Spacer(modifier = Modifier.padding(24.dp))
    }

    confirmCancel?.let { task ->
        AlertDialog(
            onDismissRequest = { confirmCancel = null },
            title = { Text(RoomBoard.Copy.cancelTask) },
            text = { Text(RoomBoard.Copy.cancelConfirm(RoomBoard.memberName(task, names))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = null
                    act("task:${task.id}", "task-cancel", R.taskCancel(roomId, task.id)) {
                        if (writing == task.id) { writing = null; draft = "" }
                    }
                }) { Text(RoomBoard.Copy.cancelTask) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text(RoomBoard.Copy.keep) } },
        )
    }
}

@Composable
private fun TaskRow(
    task: RoomBoard.Task,
    member: String,
    color: androidx.compose.ui.graphics.Color?,
    time: String?,
    busy: Boolean,
    writing: Boolean,
    draft: String,
    onToggleWrite: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val statusColor = when (task.status) {
        "active" -> androidx.compose.ui.graphics.Color(0xFF43A047)
        "review" -> MaterialTheme.colorScheme.primary
        "blocked" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(member, style = MaterialTheme.typography.titleSmall, color = color ?: MaterialTheme.colorScheme.primary, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Text(RoomBoard.statusLabel(task.status), style = MaterialTheme.typography.labelSmall, color = statusColor, modifier = Modifier.padding(start = 8.dp))
            Spacer(modifier = Modifier.weight(1f))
            if (time != null) Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(task.goal, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        val dim = MaterialTheme.colorScheme.onSurfaceVariant
        if (task.open) task.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(top = 2.dp)) }
        RoomBoard.blockedText(task)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp)) }
        if (!task.open) task.result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(top = 2.dp)) }
        if (!task.open) task.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(top = 2.dp)) }
        task.lastNote?.let { Text(RoomBoard.Copy.note(it), style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(top = 4.dp)) }
        if (task.open) {
            Row {
                TextButton(enabled = !busy, onClick = onToggleWrite) { Text(RoomBoard.Copy.addNote) }
                TextButton(enabled = !busy, onClick = onCancel) { Text(RoomBoard.Copy.cancel) }
            }
            if (writing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { onDraft(it.take(RoomBoard.NOTE_MAX)) },
                        placeholder = { Text(RoomBoard.Copy.notePlaceholder) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = !busy && RoomBoard.noteText(draft) != null, onClick = onSend) { Text(RoomBoard.Copy.send) }
                }
                Text(RoomBoard.Copy.noteSentHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
