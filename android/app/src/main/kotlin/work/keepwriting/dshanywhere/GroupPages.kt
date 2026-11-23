package work.keepwriting.dshanywhere

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import work.keepwriting.core.BotManage
import work.keepwriting.core.GroupManage
import work.keepwriting.core.Json

/** 可搜索的 Bot 多选（成员选择与新建群共用）；私有 Bot 不出现在候选中。 */
@Composable
private fun MemberChecklist(state: Json.Value.Object?, selected: List<String>, locked: String?, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val list = GroupManage.candidates(state, query)
    Column(modifier = modifier) {
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text(tr("搜索 Bot", "Search Bots")) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(list, key = { it.id }) { c ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = c.id != locked) { onToggle(c.id) }.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Checkbox(checked = c.id in selected, enabled = c.id != locked, onCheckedChange = { onToggle(c.id) })
                    Text(c.name + (if (c.isMain) " · Main" else "") + (if (c.id == locked) tr("（管理员）", " (admin)") else ""), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/**
 * 群详情页（dsbot-folders 建框架，dsbot-group-manage 补齐）：群名、公告、成员、管理员、回复模式、统一权限、工作文件夹、
 * 侧栏置顶/隐藏、删除。写操作经 manage-write-feedback 通道，服务端拒绝显示原因。
 */
@Composable
fun RoomDetailsPage(
    state: Json.Value.Object?,
    roomId: String,
    api: ManageApi,
    flash: String?,
    onDismissFlash: () -> Unit,
    onPickFolder: () -> Unit,
    onDeleted: () -> Unit,
) {
    val room = GroupManage.roomOf(state, roomId)
    if (room == null) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("找不到这个群聊，它可能已被删除", "This group chat was not found. It may have been deleted"))
            TextButton(onClick = onDeleted) { Text(tr("回到团队", "Back to team")) }
        }
        return
    }
    var error by remember(roomId) { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val bots = GroupManage.bots(state)
    fun nameOf(id: String) = bots.firstOrNull { it.id == id }?.name ?: id
    fun busy(key: String) = "$roomId:$key" in api.inFlight
    fun act(key: String, endpoint: String, payload: String, onOk: (Json.Value) -> Unit = {}) {
        error = null
        api.action("$roomId:$key", endpoint, payload, onOk) { error = it }
    }
    val R = GroupManage.Requests
    val permissions = BotManage.permissions(state)

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(room.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        ErrorLine(error) { error = null }
        InfoLine(flash, error = false, onDismiss = onDismissFlash)

        SectionTitle(tr("资料", "Profile"))
        // 群名：显示用户起的名字；自动名时输入框为空（留空保存即回到自动名）
        EditableField(
            tr("群名（留空则按成员自动命名）", "Name (leave empty to name it after its members)"),
            if (room.named) room.name else "", busy("name"), maxLength = GroupManage.NAME_MAX,
        ) { act("name", "update-room", R.name(roomId, it)) }
        EditableField(
            tr("公告", "Notice"), room.notice, busy("notice"), multiline = true,
            problem = { GroupManage.noticeProblem(it)?.let(GroupManage::problemText) },
        ) { act("notice", "update-room", R.notice(roomId, it)) }

        SectionTitle(tr("成员（${room.members.size}）", "Members (${room.members.size})"))
        Text(room.members.joinToString("、") { nameOf(it) }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
        TextButton(enabled = !busy("members"), onClick = { picker = "members" }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("管理成员", "Manage members")) }
        ValueRow(tr("管理员", "Admin"), room.admin?.let { nameOf(it) } ?: tr("无", "None"), enabled = !busy("admin")) { picker = "admin" }

        SectionTitle(tr("回复方式", "Replies"))
        // 显示实际生效的模式（与网页端 shownMode 一致）；没有管理员时不能选管理员优先
        val shown = GroupManage.shownMode(room, bots)
        for (m in GroupManage.MODES) {
            val allowed = !busy("mode") && m != shown && (m != "admin" || room.admin != null)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = allowed) { act("mode", "update-room", R.mode(roomId, m)) }.padding(horizontal = 8.dp)) {
                RadioButton(selected = m == shown, enabled = allowed || m == shown, onClick = { if (allowed) act("mode", "update-room", R.mode(roomId, m)) })
                Column {
                    Text(GroupManage.modeLabel(m), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (m == "admin" && room.admin == null) tr("先选管理员。", "Choose an admin first.") else GroupManage.modeHint(m),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (permissions != null) {
            SectionTitle(tr("权限", "Permissions"))
            ValueRow(
                tr("统一权限预设", "Same permissions for all"),
                room.permission?.let { p -> permissions.options.firstOrNull { it.value == p }?.name ?: p } ?: tr("各 Bot 用自己的", "Each Bot's own"),
                hint = tr("设了统一预设时，所有成员在这个群里都用它。", "With a shared preset, every member uses it in this group."),
                enabled = !busy("permission"),
            ) { picker = "permission" }
        }

        SectionTitle(tr("工作文件夹", "Working folder"))
        ValueRow(tr("文件夹", "Folder"), room.workDir ?: tr("各 Bot 用自己的文件夹", "Each Bot uses its own folder"), hint = tr("设了群文件夹时，所有成员在群里都在这个文件夹工作。", "With a group folder, every member works there in this group."), onClick = onPickFolder)

        SectionTitle(tr("侧栏", "Sidebar"))
        SwitchRow(tr("置顶", "Pin"), tr("排在其他聊天之上。", "Kept above the other chats."), room.pinned, !busy("pinned")) { act("pinned", "set-flags", BotManage.Requests.setFlag(roomId, "pinned", it)) }
        SwitchRow(tr("从侧栏隐藏", "Hide from sidebar"), tr("搜索仍能找到。", "Search still finds it."), room.hidden, !busy("hidden")) { act("hidden", "set-flags", BotManage.Requests.setFlag(roomId, "hidden", it)) }

        SectionTitle(tr("管理", "Manage"))
        ValueRow(tr("删除群聊", "Delete group"), "›", hint = tr("成员仍留在团队里；群的记录会归档。", "Its members stay on the team; its history is archived."), enabled = !busy("delete")) { confirmDelete = true }
        Spacer(modifier = Modifier.padding(24.dp))
    }

    when (picker) {
        "members" -> MembersDialog(state, room, onDismiss = { picker = null }) { members ->
            picker = null
            act("members", "update-room", R.members(roomId, members))
        }
        "admin" -> PickerDialog(
            tr("管理员", "Admin"),
            listOf(Triple<String?, String, String?>(null, tr("无", "None"), null)) + room.members.map { Triple<String?, String, String?>(it, nameOf(it), null) },
            room.admin,
            onPick = { id -> picker = null; if (id != room.admin) act("admin", "update-room", R.admin(roomId, id)) },
            onDismiss = { picker = null },
        )
        "permission" -> permissions?.let { p ->
            PickerDialog(
                tr("统一权限预设", "Same permissions for all"),
                listOf(Triple<String?, String, String?>(null, tr("各 Bot 用自己的", "Each Bot's own"), null)) + p.options.map { Triple<String?, String, String?>(it.value, it.name, null) },
                room.permission,
                onPick = { v -> picker = null; if (v != room.permission) act("permission", "update-room", R.permission(roomId, v)) },
                onDismiss = { picker = null },
            )
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            tr("请确认", "Please confirm"),
            tr("删除群聊「${room.name}」？成员仍留在团队里，群的记录会归档。", "Delete \"${room.name}\"? Its members stay on the team; its history is archived."),
            tr("删除", "Delete"),
            onConfirm = { act("delete", "delete-room", R.delete(roomId)) { onDeleted() } },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** 成员管理：可搜索多选，一次以全量成员提交；人数与管理员在本地先校验。 */
@Composable
private fun MembersDialog(state: Json.Value.Object?, room: GroupManage.Room, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val selected = remember(room.id) { mutableStateListOf<String>().apply { addAll(room.members) } }
    var tried by remember { mutableStateOf(false) }
    // 管理员离开群时服务端会清空管理员，这里提示而不拦截；人数不对时拦截
    val problem = GroupManage.memberProblem(selected.toList(), null)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    TextButton(onClick = onDismiss) { Text(tr("返回", "Back")) }
                    Text(tr("成员（${selected.size}）", "Members (${selected.size})"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        tried = true
                        // 没有改动直接关闭，不发请求
                        if (selected.toSet() == room.members.toSet()) onDismiss() else if (problem == null) onSave(selected.toList())
                    }) { Text(tr("保存", "Save")) }
                }
                if (tried && problem != null) Text(GroupManage.problemText(problem), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                if (room.admin != null && room.admin !in selected) Text(tr("管理员离开后，这个群将没有管理员。", "Without its admin, the group will have no admin."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                HorizontalDivider()
                MemberChecklist(state, selected, locked = null, onToggle = { id -> if (id in selected) selected.remove(id) else selected.add(id) })
            }
        }
    }
}

/** 新建群聊（dsbot-group-manage）：选管理员与其他成员、可选群名；与网页端一致由管理员打招呼。成功后进入新群聊。 */
@Composable
fun NewRoomPage(state: Json.Value.Object?, api: ManageApi, onCreated: (String) -> Unit) {
    val bots = GroupManage.candidates(state, "")
    // 默认管理员：第一个 Main Bot（与上游 defaultAdmin 一致），没有则第一个 Bot
    var admin by remember { mutableStateOf<String?>(null) }
    val effectiveAdmin = admin ?: bots.firstOrNull { it.isMain }?.id ?: bots.firstOrNull()?.id
    val others = remember { mutableStateListOf<String>() }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var tried by remember { mutableStateOf(false) }
    var pickAdmin by remember { mutableStateOf(false) }
    val members = listOfNotNull(effectiveAdmin) + others.filter { it != effectiveAdmin }
    val problem = GroupManage.memberProblem(members, effectiveAdmin)
    val busy = "new-room" in api.inFlight
    Column(modifier = Modifier.fillMaxSize()) {
        Text(tr("新建群聊", "New group chat"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        ErrorLine(error) { error = null }
        OutlinedTextField(value = name, onValueChange = { name = it.take(GroupManage.NAME_MAX) }, singleLine = true, label = { Text(tr("群名（可选）", "Name (optional)")) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        ValueRow(tr("管理员", "Admin"), effectiveAdmin?.let { id -> bots.firstOrNull { it.id == id }?.name } ?: tr("无", "None")) { pickAdmin = true }
        if (tried && problem != null) Text(GroupManage.problemText(problem), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        Button(enabled = !busy, onClick = {
            tried = true
            val a = effectiveAdmin
            if (problem == null && a != null) {
                error = null
                api.action("new-room", "create-room", GroupManage.Requests.createRoom(a, others.toList(), name), { v -> GroupManage.createdId(v)?.let(onCreated) }) { error = it }
            }
        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { Text(if (busy) tr("创建中…", "Creating…") else tr("创建（${members.size} 个成员）", "Create (${members.size} members)")) }
        Text(tr("其他成员", "Other members"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        MemberChecklist(state, members, locked = effectiveAdmin, onToggle = { id -> if (id in others) others.remove(id) else others.add(id) }, modifier = Modifier.fillMaxSize())
    }
    if (pickAdmin) {
        PickerDialog(
            tr("管理员", "Admin"),
            bots.map { Triple(it.id, it.name + (if (it.isMain) " · Main" else ""), null as String?) },
            effectiveAdmin,
            onPick = { id -> pickAdmin = false; admin = id; others.remove(id) },
            onDismiss = { pickAdmin = false },
        )
    }
}
