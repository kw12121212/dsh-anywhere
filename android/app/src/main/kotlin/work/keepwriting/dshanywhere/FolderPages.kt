package work.keepwriting.dshanywhere

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import work.keepwriting.core.BotManage
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.Json
import work.keepwriting.core.WorkFolders

/** 文件夹选择的对象（dsbot-folders）。 */
sealed interface FolderTarget {
    data class Bot(val id: String) : FolderTarget
    data class Room(val id: String) : FolderTarget
    data object Team : FolderTarget
}

/** 保存文件夹后的提示（在返回的详情页显示）。 */
fun folderSavedText(): String =
    tr("已保存。这个聊天会在新文件夹的新分段继续。", "Saved. This chat continues in a new part in the new folder.")

@Composable
internal fun InfoLine(text: String?, error: Boolean, onDismiss: () -> Unit) {
    if (text == null) return
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(tr("知道了", "OK")) }
        }
    }
}

/**
 * 文件夹选择页（dsbot-folders）：先读 folders；宿主支持浏览时以 folder-list 逐级浏览（面包屑、隐藏项开关、截断提示），
 * 另有手输完整路径、最近文件夹与「团队工作区 / 各 Bot 用自己的文件夹」快捷项。不调用 folder-pick。
 * [current] 为当前设置（null 表示默认：Bot=团队工作区、群=各 Bot 用自己的文件夹）。保存成功后 [onSaved]。
 */
@Composable
fun FolderPickerPage(target: FolderTarget, title: String, current: String?, api: ManageApi, onSaved: () -> Unit) {
    var overview by remember { mutableStateOf<WorkFolders.Overview?>(null) }
    var listing by remember { mutableStateOf<WorkFolders.Listing?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var inputTried by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var listingBusy by remember { mutableStateOf(false) }
    var overviewFailed by remember { mutableStateOf(false) }
    var confirmTeam by remember { mutableStateOf<String?>(null) }
    val gate = remember { WorkFolders.ListingGate() }

    /** 浏览 [path]（null 为电脑上的主目录）；[fallbackHome] 时读取失败且还没有任何列表则退回主目录（起点文件夹已被删除等）。 */
    fun browse(path: String?, fallbackHome: Boolean = false) {
        val ticket = gate.next()
        listingBusy = true
        api.read("folder-list", WorkFolders.Requests.list(path)) { r ->
            if (!gate.accept(ticket)) return@read
            listingBusy = false
            val decoded = (r as? DsbotApi.Result.Ok)?.let { WorkFolders.decodeListing(it.value) }
            if (decoded != null) {
                listing = decoded
                error = null
                return@read
            }
            error = (r as? DsbotApi.Result.BotError)?.let { BotManage.errorText(it.code, it.message) } ?: BotManage.errorText(null, null)
            if (fallbackHome && listing == null && path != null) browse(null)
        }
    }
    fun loadOverview() {
        overviewFailed = false
        api.read("folders", "{}") { r ->
            if (r !is DsbotApi.Result.Ok) {
                overviewFailed = true
                error = (r as? DsbotApi.Result.BotError)?.let { BotManage.errorText(it.code, it.message) } ?: BotManage.errorText(null, null)
                return@read
            }
            val o = WorkFolders.decodeOverview(r.value)
            overview = o
            // 从当前文件夹（没有时从团队工作区）开始浏览；起点读不到时退回主目录
            if (o.canBrowse && listing == null) browse(current ?: o.team, fallbackHome = true)
        }
    }
    LaunchedEffect(Unit) { loadOverview() }

    val key = when (target) {
        is FolderTarget.Bot -> "${target.id}:folder"
        is FolderTarget.Room -> "${target.id}:folder"
        FolderTarget.Team -> "team-workspace"
    }
    val saving = key in api.inFlight
    fun save(path: String) {
        error = null
        info = null
        val (endpoint, body) = when (target) {
            is FolderTarget.Bot -> "update-bot" to WorkFolders.Requests.botFolder(target.id, path)
            is FolderTarget.Room -> "update-room" to WorkFolders.Requests.roomFolder(target.id, path)
            FolderTarget.Team -> "team-workspace" to WorkFolders.Requests.teamWorkspace(path)
        }
        api.action(key, endpoint, body, { v ->
            if (target == FolderTarget.Team) {
                // 团队工作区留在本页：显示服务端返回的实际路径（应答缺失时重读 folders）
                val team = WorkFolders.teamOf(v)
                if (team != null) overview = overview?.copy(team = team) else loadOverview()
                info = tr("团队工作区已更新。没有自己文件夹的 Bot 会在新位置的新分段继续。", "Team workspace updated. Bots without their own folder continue in a new part there.")
            } else {
                onSaved()
            }
        }) { error = it }
    }
    // 当前生效的位置：Bot 没有自己的文件夹即在团队工作区；团队工作区以 folders 读到的为准
    val effective = when (target) {
        is FolderTarget.Bot -> current ?: overview?.team
        is FolderTarget.Room -> current
        FolderTarget.Team -> overview?.team
    }
    // 团队工作区本身不能设为群文件夹（服务端视为未设，即各 Bot 用自己的文件夹）
    fun selectable(path: String) = path != effective && !(target is FolderTarget.Room && path == overview?.team)
    // 改团队工作区影响所有没有自己文件夹的 Bot、且不存在的路径会在电脑上被创建：先确认
    fun choose(path: String) = if (target == FolderTarget.Team) confirmTeam = path else save(path)

    Column(modifier = Modifier.fillMaxSize()) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        val currentText = current ?: when (target) {
            is FolderTarget.Bot -> tr("团队工作区", "Team workspace")
            is FolderTarget.Room -> tr("各 Bot 用自己的文件夹", "Each Bot uses its own folder")
            FolderTarget.Team -> overview?.team ?: "…"
        }
        Text(tr("当前：", "Current: ") + currentText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        InfoLine(error, error = true) { error = null }
        InfoLine(info, error = false) { info = null }

        // 快捷项：默认位置与最近用过的文件夹
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            when (target) {
                is FolderTarget.Bot -> if (current != null) OutlinedButton(enabled = !saving, onClick = { save("") }, modifier = Modifier.padding(4.dp)) { Text(tr("改回团队工作区", "Use the team workspace")) }
                is FolderTarget.Room -> if (current != null) OutlinedButton(enabled = !saving, onClick = { save("") }, modifier = Modifier.padding(4.dp)) { Text(tr("各 Bot 用自己的文件夹", "Each Bot uses its own folder")) }
                FolderTarget.Team -> {}
            }
            for (f in overview?.folders.orEmpty().filter { selectable(it.path) }) {
                OutlinedButton(enabled = !saving, onClick = { choose(f.path) }, modifier = Modifier.padding(4.dp)) { Text(f.title) }
            }
        }
        // 手输完整路径
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            val bad = inputTried && !WorkFolders.isFullPath(input)
            OutlinedTextField(
                value = input, onValueChange = { input = it; inputTried = false }, singleLine = true,
                label = { Text(tr("输入完整路径", "Type a full path")) },
                isError = bad,
                supportingText = if (bad) ({ Text(WorkFolders.pathProblemText()) }) else null,
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = !saving, onClick = {
                inputTried = true
                if (WorkFolders.isFullPath(input)) choose(input)
            }) { Text(tr("使用", "Use")) }
        }

        val o = overview
        when {
            o == null && overviewFailed -> TextButton(onClick = { loadOverview() }, modifier = Modifier.padding(8.dp)) { Text(tr("读取失败，点此重试", "Could not load. Tap to retry")) }
            o == null -> Text(tr("读取中…", "Loading…"), modifier = Modifier.padding(16.dp))
            !o.canBrowse -> Text(
                tr("这个 DeepSeek Harness 不支持在手机上浏览文件夹，请输入完整路径或选择最近用过的文件夹。", "This DeepSeek Harness cannot list folders for the phone. Type a full path or pick a recent folder."),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp),
            )
            else -> {
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                val l = listing
                // 面包屑：可跳回任一上级
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    l?.crumbs.orEmpty().forEachIndexed { i, c ->
                        if (i > 0) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { browse(c.path) }) { Text(c.name) }
                    }
                    l?.home?.let { home -> if (home != l.path) TextButton(onClick = { browse(home) }) { Text("~") } }
                    // 还没有任何列表（起点与主目录都读不到）时仍可重试从主目录浏览
                    if (l == null && !listingBusy) TextButton(onClick = { browse(null) }) { Text(tr("从主目录浏览", "Browse from home")) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(tr("显示隐藏文件夹", "Show hidden folders"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = showHidden, onCheckedChange = { showHidden = it })
                }
                if (l != null) {
                    Button(enabled = !saving && selectable(l.path), onClick = { choose(l.path) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(if (saving) tr("保存中…", "Saving…") else tr("选择此文件夹：", "Choose this folder: ") + l.path, maxLines = 2)
                    }
                    if (target is FolderTarget.Room && l.path == o.team) Text(tr("团队工作区不能设为群文件夹；要让成员各自工作，请选「各 Bot 用自己的文件夹」。", "The team workspace cannot be a group folder; to let members work in their own folders, choose \"Each Bot uses its own folder\"."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                    if (l.truncated) Text(tr("文件夹太多，只列出了一部分；可以直接输入路径。", "Too many folders; only some are listed. You can type the path."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    val shown = l?.let { WorkFolders.visible(it, showHidden) }.orEmpty()
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(shown, key = { it.path }) { e ->
                            Text(
                                "📁 " + e.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clickable(enabled = !listingBusy) { browse(e.path) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                    if (l != null && shown.isEmpty() && !listingBusy) Text(tr("这里没有子文件夹", "No folders here"), modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
    confirmTeam?.let { path ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmTeam = null },
            title = { Text(tr("修改团队工作区？", "Change the team workspace?")) },
            text = { Text(tr("没有自己文件夹的 Bot 会在 $path 的新分段继续；这个文件夹不存在时会在电脑上新建。", "Bots without their own folder continue in a new part in $path; the folder is created on the computer if it does not exist.")) },
            confirmButton = { TextButton(onClick = { confirmTeam = null; save(path) }) { Text(tr("修改", "Change")) } },
            dismissButton = { TextButton(onClick = { confirmTeam = null }) { Text(tr("取消", "Cancel")) } },
        )
    }
}

