package work.keepwriting.dshanywhere

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import work.keepwriting.core.CapabilityLibrary
import work.keepwriting.core.CapabilityLibrary.Mode
import work.keepwriting.core.Json

/*
 * 能力（dsbot-capabilities）：团队能力库列表、编辑页与单个 Bot 的能力页。
 * 数据都来自 state（能力库、bots[].capabilities/packs、live capabilityStatuses），写操作经 ManageApi
 * 发出、不自动重试，成功后由 state 刷新显示。能力配置（含 $DSH_SECRET_ 引用原文）不写日志与诊断上报。
 */

private const val SECRET_HINT = "{\"API_KEY\": \"\$DSH_SECRET_MY_KEY\"}"

/** 团队能力库列表：待批准置顶、出错其次；行内批准、编辑与二次确认删除。 */
@Composable
fun CapabilitiesPage(state: Json.Value.Object?, api: ManageApi, onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val items = CapabilityLibrary.listOrder(state, CapabilityLibrary.library(state))
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<CapabilityLibrary.Item?>(null) }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(tr("能力", "Capabilities"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        Text(
            tr("团队共享的 MCP 服务器、技能与能力包；在 Bot 详情的「能力」中为每个 Bot 开启。", "MCP servers, skills, and packs shared by the team. Turn them on for each Bot under Capabilities in its details."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("＋ 添加能力", "+ Add capability")) }
        ErrorLine(error) { error = null }
        if (items.isEmpty()) {
            Text(tr("还没有保存的能力。", "No capabilities saved."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        for (item in items) {
            CapabilityRow(
                state = state,
                item = item,
                approving = "capability:approve:${item.name}" in api.inFlight,
                onApprove = {
                    error = null
                    api.action("capability:approve:${item.name}", "capability-approve", CapabilityLibrary.Requests.approve(item.name), {}) { error = it }
                },
                onEdit = { onEdit(item.name) },
                onDelete = { deleting = item },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("删除「${item.name}」？", "Delete \"${item.name}\"?")) },
            text = { Text(tr("会从所有 Bot 与能力包中移除，删除后不能恢复。", "It is removed from every Bot and pack. This cannot be undone.")) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    error = null
                    api.action("capability:delete:${item.name}", "capability-delete", CapabilityLibrary.Requests.delete(item.name), {}) { error = it }
                }) { Text(tr("删除", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun CapabilityRow(state: Json.Value.Object?, item: CapabilityLibrary.Item, approving: Boolean, onApprove: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val status = CapabilityLibrary.listStatus(state, item)
    val source = CapabilityLibrary.sourceBot(state, item)
    val users = CapabilityLibrary.users(state, item.name)
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(start = 8.dp)) {
                Text(CapabilityLibrary.kindLabel(item), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Text(CapabilityLibrary.secondary(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = listOf(
            if (source == null) tr("你添加的", "Added by you") else tr("由 $source 提议", "Proposed by $source"),
            if (users.isEmpty()) tr("未被 Bot 使用", "Not used by any Bot") else tr("使用者：", "Used by: ") + users.joinToString("、"),
        ).joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CapabilityLibrary.statusText(status)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (status?.state == "error" || status?.state == "needs-approval") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        Row {
            if (item.needsApproval) {
                TextButton(onClick = onApprove, enabled = !approving) { Text(tr("批准", "Allow")) }
            }
            TextButton(onClick = onEdit) { Text(tr("编辑", "Edit")) }
            TextButton(onClick = onDelete) { Text(tr("删除", "Delete"), color = MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * 新增或编辑一项能力（网页端 CapabilityForm）：编辑已保存能力时名称与种类只读；
 * 环境变量、请求头与技能内容在全屏编辑器中编辑。本地校验失败不发送；保存成功后 [onDone] 返回。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CapabilityEditPage(state: Json.Value.Object?, api: ManageApi, name: String?, onDone: () -> Unit) {
    val library = CapabilityLibrary.library(state)
    val existing = name?.let { n -> library.firstOrNull { it.name == n } }
    if (name != null && existing == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(tr("这项能力已不存在。", "This capability no longer exists."), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onDone) { Text(tr("返回", "Back")) }
        }
        return
    }
    var draft by remember(name) { mutableStateOf(existing?.let(CapabilityLibrary::draftOf) ?: CapabilityLibrary.Draft()) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    val editingExisting = draft.originalName != null
    val busy = "capability:save" in api.inFlight
    val nameIssue = if (!editingExisting && draft.name.isNotEmpty()) CapabilityLibrary.nameProblem(draft.name, library.map { it.name })?.let(CapabilityLibrary::nameProblemText) else null

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Text(if (editingExisting) tr("编辑能力", "Edit capability") else tr("添加能力", "Add capability"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        ErrorLine(error) { error = null }

        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it.take(CapabilityLibrary.NAME_MAX)) },
            label = { Text(tr("名称", "Name")) },
            placeholder = { Text("my-tool") },
            enabled = !editingExisting,
            singleLine = true,
            isError = nameIssue != null,
            supportingText = nameIssue?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        SectionTitle(tr("类型", "Type"))
        FlowRow(modifier = Modifier.padding(horizontal = 12.dp)) {
            for ((kind, label) in listOf(CapabilityLibrary.MCP to tr("MCP 服务器", "MCP server"), CapabilityLibrary.SKILL to tr("技能", "Skill"), CapabilityLibrary.PACK to tr("能力包", "Pack"))) {
                FilterChip(selected = draft.kind == kind, enabled = !editingExisting, onClick = { draft = draft.copy(kind = kind) }, label = { Text(label) }, modifier = Modifier.padding(4.dp))
            }
        }

        OutlinedTextField(
            value = draft.description,
            onValueChange = { draft = draft.copy(description = it.take(2000)) },
            label = { Text(tr("说明", "Description")) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        when (draft.kind) {
            CapabilityLibrary.MCP -> {
                SectionTitle(tr("连接方式", "Connection"))
                FlowRow(modifier = Modifier.padding(horizontal = 12.dp)) {
                    for ((t, label) in listOf(CapabilityLibrary.STDIO to tr("本地命令", "Local command"), CapabilityLibrary.HTTP to tr("HTTP 服务", "HTTP server"))) {
                        FilterChip(selected = draft.transport == t, onClick = { draft = draft.copy(transport = t) }, label = { Text(label) }, modifier = Modifier.padding(4.dp))
                    }
                }
                if (draft.transport == CapabilityLibrary.STDIO) {
                    MonoField(tr("命令", "Command"), draft.command, singleLine = true) { draft = draft.copy(command = it) }
                    MonoField(tr("参数（每行一项）", "Arguments (one per line)"), draft.argsText, singleLine = false) { draft = draft.copy(argsText = it) }
                    ValueRow(tr("环境变量（JSON）", "Environment (JSON)"), preview(draft.envText), hint = tr("可用 \$DSH_SECRET_名称 引用密钥", "Use \$DSH_SECRET_NAME to reference a secret")) { editing = "env" }
                } else {
                    MonoField("URL", draft.url, singleLine = true) { draft = draft.copy(url = it) }
                    ValueRow(tr("请求头（JSON）", "Headers (JSON)"), preview(draft.headersText), hint = tr("可用 \$DSH_SECRET_名称 引用密钥", "Use \$DSH_SECRET_NAME to reference a secret")) { editing = "headers" }
                }
                if (!editingExisting && draft.transport == CapabilityLibrary.STDIO) {
                    Text(tr("本地命令在电脑上运行。", "Local commands run on the computer."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            CapabilityLibrary.SKILL -> {
                ValueRow(tr("技能说明", "Skill instructions"), preview(draft.content).ifEmpty { tr("点按编辑", "Tap to edit") }, hint = tr("${draft.content.length} 个字符", "${draft.content.length} characters")) { editing = "content" }
            }
            else -> {
                SectionTitle(tr("包含", "Includes"))
                val candidates = CapabilityLibrary.packCandidates(library, draft.name)
                if (candidates.isEmpty()) {
                    Text(tr("请先保存一个 MCP 服务器或技能。", "Save an MCP server or skill first."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                }
                for (c in candidates) {
                    val on = c.name in draft.items
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                        draft = draft.copy(items = if (on) draft.items - c.name else draft.items + c.name)
                    }.padding(horizontal = 8.dp)) {
                        Checkbox(checked = on, onCheckedChange = { draft = draft.copy(items = if (on) draft.items - c.name else draft.items + c.name) })
                        Column {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge)
                            Text(CapabilityLibrary.kindLabel(c), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.padding(16.dp)) {
            TextButton(onClick = onDone) { Text(tr("取消", "Cancel")) }
            Button(enabled = !busy && nameIssue == null && draft.name.isNotBlank(), onClick = {
                error = null
                when (val built = CapabilityLibrary.build(draft, if (editingExisting) emptyList() else library.map { it.name })) {
                    is CapabilityLibrary.Built.Invalid -> error = built.message
                    is CapabilityLibrary.Built.Ok -> api.action("capability:save", "capability-save", built.payload, { onDone() }) { error = it }
                }
            }) { Text(if (editingExisting) tr("保存修改", "Save changes") else tr("添加能力", "Add capability")) }
        }
    }

    editing?.let { field ->
        val (title, value) = when (field) {
            "env" -> tr("环境变量（JSON）", "Environment (JSON)") to draft.envText
            "headers" -> tr("请求头（JSON）", "Headers (JSON)") to draft.headersText
            else -> tr("技能说明", "Skill instructions") to draft.content
        }
        FullScreenEditor(title, value, mono = field != "content", placeholder = if (field == "content") null else SECRET_HINT, onDismiss = { editing = null }) { text ->
            draft = when (field) {
                "env" -> draft.copy(envText = text)
                "headers" -> draft.copy(headersText = text)
                else -> draft.copy(content = text)
            }
            editing = null
        }
    }
}

/** 预览：首行（截断）；空 JSON 对象显示为空。 */
private fun preview(text: String): String {
    val t = text.trim()
    if (t.isEmpty() || t == "{}") return ""
    val first = t.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && it != "{" } ?: t
    return if (first.length > 40) first.take(40) + "…" else first
}

@Composable
private fun MonoField(label: String, value: String, singleLine: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** 全屏多行编辑器：「完成」回填，「取消」放弃；不自动保存到服务端。 */
@Composable
private fun FullScreenEditor(title: String, initial: String, mono: Boolean, placeholder: String?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().imePadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onDone(text) }) { Text(tr("完成", "Done")) }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = placeholder?.let { { Text(it, fontFamily = FontFamily.Monospace) } },
                    textStyle = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = !mono),
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            }
        }
    }
}

/** 单个 Bot 的能力页（网页端 BotCapabilitiesCard）：每项常驻/按需/关闭三段选择，在途时该项禁用。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BotCapabilitiesPage(state: Json.Value.Object?, botId: String, botName: String, api: ManageApi) {
    var error by remember { mutableStateOf<String?>(null) }
    val rows = CapabilityLibrary.botRows(state, botId)
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(tr("能力：", "Capabilities: ") + botName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        Text(
            tr("常驻：每次对话都加载；按需：列入目录，Bot 需要时才加载。", "Always on: loaded in every chat. In catalog: the Bot loads it only when it needs it."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        ErrorLine(error) { error = null }
        if (rows.isEmpty()) {
            Text(tr("团队还没有能力，先在团队页的「能力」中添加。", "The team has no capabilities yet. Add one under Capabilities on the team page."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        for (row in rows) {
            val key = "botcap:$botId:${row.item.name}"
            val busy = key in api.inFlight
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(row.item.name, style = MaterialTheme.typography.bodyLarge)
                Text(CapabilityLibrary.kindLabel(row.item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for ((pack, mode) in row.viaPacks) {
                    val via = if (mode == Mode.ALWAYS) tr("经由 $pack 常驻", "Always on via $pack") else tr("经由 $pack 按需", "In catalog via $pack")
                    Text((if (row.mode == Mode.OFF) tr("关闭 · ", "Off · ") else "") + via, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (row.needsApproval) {
                    Text(tr("待批准 · 请在团队页的「能力」中批准", "Needs approval · Allow it under Capabilities on the team page"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                CapabilityLibrary.statusText(row.status)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (row.status?.state == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                FlowRow {
                    for ((mode, label) in listOf(Mode.ALWAYS to tr("常驻", "Always on"), Mode.CATALOG to tr("按需", "In catalog"), Mode.OFF to tr("关闭", "Off"))) {
                        FilterChip(selected = row.mode == mode, enabled = !busy, onClick = {
                            if (row.mode != mode) {
                                error = null
                                api.action(key, "bot-capability", CapabilityLibrary.Requests.attach(botId, row.item.name, mode), {}) { error = it }
                            }
                        }, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}
