package work.keepwriting.dshanywhere

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import work.keepwriting.core.BotManage
import work.keepwriting.core.Json

/**
 * Bot 管理（dsbot-bot-manage）的写操作与读取入口，由 NativeShellConnection 提供：
 * [action] 在主线程调用（在途键当场登记），网络在后台；成功后连接层静默补取 state，再回调 onOk(value)；
 * 失败回调可读原因（服务端 message 优先）。[efforts] 读模型的思考强度。
 */
class ManageApi(
    val inFlight: List<String>,
    val action: (key: String, endpoint: String, payload: String, onOk: (Json.Value) -> Unit, onError: (String) -> Unit) -> Unit,
    val efforts: (ref: String, cb: (BotManage.Efforts) -> Unit) -> Unit,
    /** 只读端点（读线程发出，结果回到主线程）；dsbot-avatars 起用于头像库等列表。 */
    val read: (endpoint: String, payload: String, cb: (work.keepwriting.core.DsbotApi.Result) -> Unit) -> Unit,
    /** 写操作原始应答入口（保留主线程在途去重与成功补拉 state，交调用方区分错误码；dsbot-bot-browser）。 */
    val actionRaw: (key: String, endpoint: String, payload: String, onResult: (work.keepwriting.core.DsbotApi.Result) -> Unit) -> Unit = { key, ep, body, onResult ->
        action(key, ep, body, { onResult(work.keepwriting.core.DsbotApi.Result.Ok(it)) }, { onResult(work.keepwriting.core.DsbotApi.Result.BotError("bot/refused", it)) })
    },
)

/** 写操作返回的对象 id（create-bot/duplicate-bot 的新 Bot）。 */
private fun idOf(v: Json.Value): String? = ((v as? Json.Value.Object)?.members?.get("id") as? Json.Value.StringValue)?.value

@Composable
internal fun ErrorLine(error: String?, onDismiss: () -> Unit) {
    if (error == null) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(tr("知道了", "OK")) }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
internal fun ValueRow(label: String, value: String, hint: String? = null, enabled: Boolean = true, onClick: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.padding(start = 8.dp).width(160.dp))
    }
}

@Composable
internal fun SwitchRow(label: String, hint: String?, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, enabled = enabled, onCheckedChange = onChange)
    }
}

/** 可编辑文字字段：草稿与已保存值不同时显示「保存」与「还原」；[problem] 非空时不可保存并显示原因。 */
@Composable
internal fun EditableField(label: String, saved: String, busy: Boolean, multiline: Boolean = false, maxLength: Int = Int.MAX_VALUE, problem: (String) -> String? = { null }, onSave: (String) -> Unit) {
    var draft by remember(saved) { mutableStateOf(saved) }
    val dirty = draft != saved
    val issue = if (dirty) problem(draft) else null
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(maxLength) },
            label = { Text(label) },
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            isError = issue != null,
            supportingText = when {
                issue != null -> ({ Text(issue) })
                dirty -> ({ Text(tr("未保存", "Not saved")) })
                else -> null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (dirty) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { draft = saved }) { Text(tr("还原", "Revert")) }
                TextButton(enabled = issue == null && !busy, onClick = { onSave(draft.trim()) }) { Text(tr("保存", "Save")) }
            }
        }
    }
}

/** 全屏选择页：[options] 为 (值, 标题, 说明)；选中即回调并关闭。 */
@Composable
internal fun <T> PickerDialog(title: String, options: List<Triple<T, String, String?>>, selected: T?, headers: Map<Int, String> = emptyMap(), onPick: (T) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    TextButton(onClick = onDismiss) { Text(tr("返回", "Back")) }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    options.forEachIndexed { i, (value, label, hint) ->
                        headers[i]?.let { h -> item(key = "h$i") { SectionTitle(h) } }
                        item(key = "o$i") {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onPick(value) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                    if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (value == selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPicker(state: Json.Value.Object?, selected: String?, allowDefault: Boolean, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val groups = remember(state) { BotManage.models(state) }
    val options = mutableListOf<Triple<String?, String, String?>>()
    val headers = mutableMapOf<Int, String>()
    if (allowDefault) options += Triple(null, tr("团队默认模型", "Team default model"), null)
    for (g in groups) {
        headers[options.size] = g.providerName
        for (m in g.models) options += Triple(m.ref, m.name, if (m.needsKey) tr("需要先设置密钥", "Needs a key first") else null)
    }
    PickerDialog(tr("选择模型", "Choose a model"), options, selected, headers, onPick, onDismiss)
}

@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}

@Composable
private fun ColorDot(name: String?, selected: Boolean, onClick: () -> Unit) {
    val rgb = BotManage.colorRgb(name)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(4.dp)
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0xFF000000.toInt() or rgb))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick),
    ) {
        if (name == null) Text("A", color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

/** 外观选择：形象（20 种，标签）与颜色（12 种 + 默认）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceDialog(shape: String?, color: String?, onShape: (String) -> Unit, onColor: (String?) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    TextButton(onClick = onDismiss) { Text(tr("返回", "Back")) }
                    Text(tr("外观", "Appearance"), style = MaterialTheme.typography.titleMedium)
                }
                SectionTitle(tr("形象", "Character"))
                FlowRow(modifier = Modifier.padding(horizontal = 12.dp)) {
                    for (s in BotManage.SHAPES) {
                        val on = s == shape
                        Surface(
                            color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.padding(4.dp).clickable { onShape(s) },
                        ) {
                            Text((if (on) "✓ " else "") + BotManage.shapeLabel(s), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                }
                SectionTitle(tr("颜色", "Color"))
                FlowRow(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ColorDot(null, color == null) { onColor(null) }
                    for (c in BotManage.COLORS) ColorDot(c, c == color) { onColor(c) }
                }
                Text(
                    tr("「A」为默认颜色（按 Bot 自动选取）。照片头像在头像库中设置。", "\"A\" is the default color (picked per Bot). Photos are set from the avatar library."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/** Bot 详情页（dsbot-bot-manage）。 */
@Composable
fun BotDetailsPage(
    state: Json.Value.Object?,
    botId: String,
    api: ManageApi,
    onOpenChat: (String) -> Unit,
    onDeleted: () -> Unit,
    /** 打开头像库（dsbot-avatars）为该 Bot 选照片。 */
    onOpenAvatars: () -> Unit = {},
    /** 选择工作文件夹（dsbot-folders）。 */
    onPickFolder: () -> Unit = {},
    /** 从选择页带回的一次性提示（如文件夹已保存）。 */
    flash: String? = null,
    onDismissFlash: () -> Unit = {},
    /** 打开该 Bot 的记忆页（dsbot-memory）。 */
    onOpenMemory: () -> Unit = {},
    /** 打开该 Bot 的浏览器页（dsbot-bot-browser）。 */
    onOpenBrowser: () -> Unit = {},
    /** 打开该 Bot 的能力页（dsbot-capabilities）。 */
    onOpenCapabilities: () -> Unit = {},
) {
    val profile = remember(state, botId) { BotManage.profileOf(state, botId) }
    if (profile == null) {
        // 被删除（本机或网页端）后 state 里已没有该 Bot
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            // Bot 或群在别处被删除后都会落到这里（详情页按 state 判定对象种类）
            Text(tr("找不到它，可能已被删除", "Not found. It may have been deleted"))
            TextButton(onClick = onDeleted) { Text(tr("回到团队", "Back to team")) }
        }
        return
    }
    var error by remember(botId) { mutableStateOf<String?>(null) }
    // 照片头像缩略图（dsbot-avatars）：IO 线程采样解码一次，页头与外观分节共用
    val photo = rememberThumbnail(profile.photo)
    var picker by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var efforts by remember(profile.currentModel) { mutableStateOf<BotManage.Efforts?>(null) }
    LaunchedEffect(profile.currentModel) {
        val ref = profile.currentModel
        if (ref != null) api.efforts(ref) { if (profile.currentModel == ref) efforts = it }
    }
    val names = remember(state) { BotManage.botNames(state) }
    val permissions = remember(state) { BotManage.permissions(state) }
    fun act(key: String, endpoint: String, payload: String, onOk: (Json.Value) -> Unit = {}) {
        error = null
        api.action("$botId:$key", endpoint, payload, onOk) { error = it }
    }
    fun busy(key: String) = "$botId:$key" in api.inFlight
    val R = BotManage.Requests

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            if (photo != null) {
                androidx.compose.foundation.Image(photo, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.padding(4.dp).size(36.dp).clip(CircleShape))
            } else {
                ColorDot(profile.color, false) {}
            }
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleLarge)
                val sub = listOfNotNull(profile.role.ifEmpty { null }, if (profile.isMain) "Main Bot" else null, if (profile.isPrivate) tr("私有", "Private") else null)
                Text(sub.joinToString(" · ").ifEmpty { "Bot" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ErrorLine(error) { error = null }
        InfoLine(flash, error = false, onDismiss = onDismissFlash)

        SectionTitle(tr("资料", "Profile"))
        EditableField(tr("名称", "Name"), profile.name, busy("name"), maxLength = BotManage.NAME_MAX, problem = { BotManage.nameProblem(it, names, botId)?.let(BotManage::nameProblemText) }) {
            act("name", "update-bot", R.updateText(botId, "name", it))
        }
        EditableField(tr("标签（显示在名称下）", "Label (shown under the name)"), profile.role, busy("role"), maxLength = BotManage.ROLE_MAX) {
            act("role", "update-bot", R.updateText(botId, "role", it))
        }
        EditableField(tr("指令（它在团队里负责什么）", "Instructions (its job on the team)"), profile.instructions, busy("instructions"), multiline = true) {
            act("instructions", "update-bot", R.updateText(botId, "instructions", it))
        }

        SectionTitle(tr("模型", "Model"))
        ValueRow(tr("模型", "Model"), BotManage.modelName(state, profile.currentModel) ?: tr("未选择", "None"), enabled = !busy("model")) { picker = "model" }
        val levels = efforts?.efforts.orEmpty()
        if (levels.isNotEmpty()) {
            val current = levels.firstOrNull { it.id == profile.effort }?.name ?: tr("模型默认", "Model default")
            ValueRow(tr("思考强度", "Thinking"), current, enabled = !busy("effort")) { picker = "effort" }
        }

        if (permissions != null) {
            SectionTitle(tr("权限", "Permissions"))
            val cur = profile.permission ?: permissions.defaultPreset
            val label = permissions.options.firstOrNull { it.value == cur }?.name ?: (cur ?: "—")
            ValueRow(
                tr("权限预设", "Permission preset"), label,
                hint = if (profile.permission == null) tr("未单独设置，使用默认", "Not set; uses the default") else null,
                enabled = !busy("permission"),
            ) { picker = "permission" }
        }

        SectionTitle(tr("外观", "Appearance"))
        ValueRow(
            tr("形象与颜色", "Character and color"),
            BotManage.shapeLabel(profile.shape),
            enabled = !busy("look"),
        ) { picker = "look" }
        // 照片头像（dsbot-avatars）：缩略图、从头像库选择、移除照片（保留形象）
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            if (photo != null) {
                androidx.compose.foundation.Image(photo, contentDescription = tr("照片头像", "Photo avatar"), contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(56.dp).clip(CircleShape))
            }
            Column(modifier = Modifier.weight(1f).padding(start = if (photo != null) 12.dp else 0.dp)) {
                Text(tr("照片", "Photo"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (profile.hasPhoto) tr("已设照片头像", "Photo avatar set") else tr("未设照片，显示形象", "No photo; the character is shown"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(modifier = Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = onOpenAvatars) { Text(tr("从头像库选择", "Choose from library")) }
            if (profile.hasPhoto) {
                TextButton(enabled = !busy("photo"), onClick = { act("photo", "update-bot", R.removePhoto(botId, profile.shape)) }) { Text(tr("移除照片", "Remove photo")) }
            }
        }

        SectionTitle(tr("工作文件夹", "Working folder"))
        ValueRow(tr("文件夹", "Folder"), profile.workDir ?: tr("团队工作区", "Team workspace"), enabled = !busy("folder"), onClick = onPickFolder)

        if (work.keepwriting.core.MemoryPanel.enabled(state)) {
            SectionTitle(tr("记忆", "Memory"))
            ValueRow(tr("记忆", "Memory"), "›", hint = tr("它自己的记忆与团队共享记忆", "Its own memory and the team memory"), onClick = onOpenMemory)
        }

        if (work.keepwriting.core.CapabilityLibrary.hasLibrary(state)) {
            SectionTitle(tr("能力", "Capabilities"))
            ValueRow(tr("能力", "Capabilities"), "›", hint = work.keepwriting.core.CapabilityLibrary.botSummary(state, botId), onClick = onOpenCapabilities)
        }

        SectionTitle(tr("浏览器", "Browser"))
        ValueRow(tr("Bot 浏览器", "Bot browser"), "›", hint = tr("查看当前网页画面与持有状态", "View live snapshots and control state"), onClick = onOpenBrowser)

        SectionTitle(tr("隐私", "Privacy"))
        SwitchRow(tr("私有", "Private"), BotManage.privateHint(profile), profile.isPrivate, BotManage.canTogglePrivate(profile) && !busy("private")) {
            act("private", "update-bot", R.updateBool(botId, "private", it))
        }

        SectionTitle("Main Bot")
        SwitchRow(
            "Main Bot",
            when {
                profile.isPrivate -> tr("私有 Bot 不能设为 Main Bot。", "A private Bot cannot be a Main Bot.")
                profile.onlyMain -> tr("团队唯一的 Main Bot，不能取消。", "The only Main Bot; it cannot be removed.")
                else -> tr("可以新建和修改 Bot，并主持所有群聊。", "Can create and change Bots, and runs every group chat.")
            },
            profile.isMain, BotManage.canToggleMain(profile) && !busy("main"),
        ) { act("main", "set-main", R.setMain(botId, it)) }

        if (BotManage.canPin(profile)) {
            SectionTitle(tr("侧栏", "Sidebar"))
            SwitchRow(tr("置顶", "Pin"), tr("排在其他聊天之上。", "Kept above the other chats."), profile.pinned, !busy("pinned")) {
                act("pinned", "set-flags", R.setFlag(botId, "pinned", it))
            }
            if (BotManage.canHide(profile)) {
                SwitchRow(tr("从侧栏隐藏", "Hide from sidebar"), tr("搜索仍能找到。", "Search still finds it."), profile.hidden, !busy("hidden")) {
                    act("hidden", "set-flags", R.setFlag(botId, "hidden", it))
                }
            }
        }

        SectionTitle(tr("管理", "Manage"))
        if (BotManage.canDuplicate(profile)) {
            ValueRow(tr("复制", "Duplicate"), "›", hint = tr("同样的设置，全新的记忆。", "Same settings, fresh memory."), enabled = !busy("duplicate")) {
                act("duplicate", "duplicate-bot", R.idOnly(botId)) { v -> idOf(v)?.let(onOpenChat) }
            }
        }
        if (BotManage.canDelete(profile)) {
            ValueRow(tr("删除", "Delete"), "›", hint = tr("删除后不能恢复。", "This cannot be undone."), enabled = !busy("delete")) {
                confirm = tr("删除 ${profile.name}？聊天与记忆会一并移除，不能恢复。", "Delete ${profile.name}? Its chats and memory go too; this cannot be undone.") to {
                    act("delete", "delete-bot", R.idOnly(botId)) { onDeleted() }
                }
            }
        }
        Spacer(modifier = Modifier.padding(24.dp))
    }

    when (picker) {
        "model" -> ModelPicker(state, profile.currentModel, allowDefault = false, onPick = { ref ->
            picker = null
            if (ref != null && ref != profile.currentModel) {
                if (BotManage.needsEngineConfirm(profile.currentModel, ref)) {
                    confirm = tr("切换运行方式？本地 Agent 与模型之间切换会为它的每个聊天开始新的分段。", "Change what it runs on? Switching between a local agent and a model starts a new part in each of its chats.") to {
                        act("model", "update-bot", R.updateModel(botId, ref, changeEngine = true))
                    }
                } else {
                    act("model", "update-bot", R.updateModel(botId, ref, changeEngine = false))
                }
            }
        }, onDismiss = { picker = null })
        "effort" -> PickerDialog(
            tr("思考强度", "Thinking"),
            listOf(Triple<String?, String, String?>(null, tr("模型默认", "Model default"), null)) + efforts?.efforts.orEmpty().map { Triple(it.id, it.name, it.description) },
            profile.effort,
            onPick = { id -> picker = null; act("effort", "update-bot", R.updateText(botId, "effort", id)) },
            onDismiss = { picker = null },
        )
        "permission" -> permissions?.let { p ->
            PickerDialog(
                tr("权限预设", "Permission preset"),
                p.options.map { Triple(it.value, it.name, if (it.value == p.defaultPreset) tr("默认", "Default") else null) },
                profile.permission ?: p.defaultPreset,
                onPick = { v -> picker = null; act("permission", "update-bot", R.updateText(botId, "permission", v)) },
                onDismiss = { picker = null },
            )
        }
        "look" -> AppearanceDialog(
            profile.shape, profile.color,
            onShape = { s -> picker = null; act("look", "update-bot", R.updateShape(botId, s)) },
            onColor = { c -> picker = null; act("look", "update-bot", R.updateText(botId, "color", c)) },
            onDismiss = { picker = null },
        )
    }
    confirm?.let { (text, run) ->
        ConfirmDialog(tr("请确认", "Please confirm"), text, tr("确认", "Confirm"), onConfirm = run, onDismiss = { confirm = null })
    }
}

/** 新建 Bot 页（dsbot-bot-manage）：名称本地校验后 create-bot，成功进入新 Bot 的私聊。 */
@Composable
fun NewBotPage(state: Json.Value.Object?, api: ManageApi, onCreated: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var model by remember { mutableStateOf<String?>(null) }
    var effort by remember { mutableStateOf<String?>(null) }
    var efforts by remember { mutableStateOf<BotManage.Efforts?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tried by remember { mutableStateOf(false) }
    LaunchedEffect(model) {
        effort = null
        efforts = null
        // 只接受当前所选模型的应答：读线程有 2 个，快速换模型时旧应答可能后到
        model?.let { ref -> api.efforts(ref) { if (model == ref) efforts = it } }
    }
    val names = remember(state) { BotManage.botNames(state) }
    val problem = BotManage.nameProblem(name, names)
    val busy = "new-bot" in api.inFlight
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(tr("新建 Bot", "New Bot"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        ErrorLine(error) { error = null }
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(BotManage.NAME_MAX) }, label = { Text(tr("名称", "Name")) }, singleLine = true,
            isError = tried && problem != null,
            supportingText = if (tried && problem != null) ({ Text(BotManage.nameProblemText(problem)) }) else null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        OutlinedTextField(value = role, onValueChange = { role = it.take(BotManage.ROLE_MAX) }, label = { Text(tr("标签（可选）", "Label (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        OutlinedTextField(value = instructions, onValueChange = { instructions = it }, label = { Text(tr("指令（可选）", "Instructions (optional)")) }, minLines = 3, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        ValueRow(tr("模型", "Model"), BotManage.modelName(state, model) ?: tr("团队默认模型", "Team default model")) { picker = "model" }
        val levels = efforts?.efforts.orEmpty()
        if (levels.isNotEmpty()) {
            ValueRow(tr("思考强度", "Thinking"), levels.firstOrNull { it.id == effort }?.name ?: tr("模型默认", "Model default")) { picker = "effort" }
        }
        Button(
            enabled = !busy,
            onClick = {
                tried = true
                if (problem == null) {
                    error = null
                    api.action("new-bot", "create-bot", BotManage.Requests.createBot(name, role, instructions, model, effort, null, null), { v -> idOf(v)?.let(onCreated) }) { error = it }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text(if (busy) tr("创建中…", "Creating…") else tr("创建", "Create")) }
    }
    when (picker) {
        "model" -> ModelPicker(state, model, allowDefault = true, onPick = { picker = null; model = it }, onDismiss = { picker = null })
        "effort" -> PickerDialog(
            tr("思考强度", "Thinking"),
            listOf(Triple<String?, String, String?>(null, tr("模型默认", "Model default"), null)) + efforts?.efforts.orEmpty().map { Triple(it.id, it.name, it.description) },
            effort, onPick = { picker = null; effort = it }, onDismiss = { picker = null },
        )
    }
}

/** 保留关闭的已删除私有 Bot：逐个释放（二次确认）。 */
@Composable
fun PrivateArchivesPage(state: Json.Value.Object?, api: ManageApi) {
    val list = remember(state) { BotManage.privateArchives(state) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<BotManage.PrivateArchive?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(tr("已删除的私有 Bot", "Deleted private Bots"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        Text(
            tr("这些 Bot 已删除，但它们的文件夹、记忆和聊天仍对其他 Bot 关闭。释放后其他 Bot 可以再访问，文件不会移动。", "These Bots were deleted, but their folder, memory and chats stay closed to the other Bots. Releasing opens them again; files stay where they are."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
        )
        ErrorLine(error) { error = null }
        if (list.isEmpty()) Text(tr("没有需要释放的项目", "Nothing kept closed"), modifier = Modifier.padding(16.dp))
        LazyColumn {
            items(list, key = { it.id }) { a ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.bodyLarge)
                        a.workDir?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    TextButton(enabled = "release:${a.id}" !in api.inFlight, onClick = { confirm = a }) { Text(tr("释放", "Release")) }
                }
            }
        }
    }
    confirm?.let { a ->
        ConfirmDialog(
            tr("请确认", "Please confirm"),
            tr("释放 ${a.name} 保留的文件夹、记忆和聊天？其他 Bot 将可以访问。", "Release what ${a.name} kept closed? Other Bots will be able to read it."),
            tr("释放", "Release"),
            onConfirm = { error = null; api.action("release:${a.id}", "private-release", BotManage.Requests.idOnly(a.id), {}) { error = it } },
            onDismiss = { confirm = null },
        )
    }
}
