package work.keepwriting.dshanywhere

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import work.keepwriting.core.BotManage
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.Json
import work.keepwriting.core.MemoryPanel

/** 读取失败的可读原因。 */
private fun readError(r: DsbotApi.Result): String =
    (r as? DsbotApi.Result.BotError)?.let { BotManage.errorText(it.code, it.message) } ?: BotManage.errorText(null, null)

/**
 * 记忆页（dsbot-memory）：Bot 的「自己的记忆」与「团队共享记忆」、群的「群记忆」分组列表；条目展开/复制与操作菜单
 * （置顶、编辑、移动、层级切换、遗忘），新增，提问（进行中有加载状态），整理撤销，历史入口。写操作成功后以应答里的
 * 最新视图替换列表（记忆不在 state 里，写端点都返回视图）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MemoryPage(owner: MemoryPanel.Owner, title: String, api: ManageApi, onOpenHistory: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var view by remember { mutableStateOf<MemoryPanel.View?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val expanded = remember { mutableStateListOf<String>() }
    var menu by remember { mutableStateOf<MemoryPanel.Entry?>(null) }
    var editing by remember { mutableStateOf<MemoryPanel.Entry?>(null) }
    var moving by remember { mutableStateOf<MemoryPanel.Entry?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var question by remember { mutableStateOf("") }
    var askResult by remember { mutableStateOf<MemoryPanel.AskResult?>(null) }
    val prefix = "mem:${owner.id}"
    val asking = "$prefix:ask" in api.inFlight
    val R = MemoryPanel.Requests

    fun load() {
        api.read("memory-view", R.view(owner)) { r ->
            if (r is DsbotApi.Result.Ok) {
                view = MemoryPanel.decodeView(r.value)
                if (view == null) error = BotManage.errorText(null, null)
            } else {
                error = readError(r)
            }
        }
    }
    LaunchedEffect(owner) { load() }
    // 写操作：成功后用应答视图替换列表（与网页端一致地显示服务端结果）。写可能分别在两个读线程上，
    // 只采用最后一次发起的写的应答视图，避免较旧的视图后到把列表改回去
    var writeGen by remember { mutableStateOf(0) }
    fun write(key: String, endpoint: String, payload: String, done: String? = null) {
        error = null
        info = null
        val gen = ++writeGen
        api.action("$prefix:$key", endpoint, payload, { v ->
            if (gen == writeGen) MemoryPanel.decodeView(v)?.let { view = it } ?: load()
            info = done
        }) { error = it }
    }
    // 展开状态按（范围, id/正文）记：上游 id 只在同一范围内唯一
    fun entryKey(e: MemoryPanel.Entry) = "${e.scope}|${e.id}|${e.topic}|${e.text}"

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenHistory) { Text(tr("历史", "History")) }
        }
        ErrorLine(error) { error = null }
        InfoLine(info, error = false) { info = null }
        // 提问或下指令：进行中禁用并显示加载
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = question, onValueChange = { question = it.take(MemoryPanel.ASK_MAX) }, enabled = !asking,
                label = { Text(tr("问问它记得什么，或让它改记忆", "Ask what it remembers, or tell it to change something")) },
                modifier = Modifier.weight(1f),
            )
            if (asking) {
                CircularProgressIndicator(modifier = Modifier.padding(start = 12.dp))
            } else {
                TextButton(enabled = question.isNotBlank(), onClick = {
                    error = null
                    askResult = null
                    api.action("$prefix:ask", "memory-ask", R.ask(owner, question), { v ->
                        val r = MemoryPanel.decodeAsk(v)
                        askResult = r
                        r.view?.let { view = it }
                        question = ""
                    }) {
                        // 失败（含长时间无应答）时服务端可能已经改了记忆：重读视图并如实提示，避免用户重复下同一指令
                        error = it + tr("。请求可能已在服务端完成，已重新读取记忆，请先查看列表。", ". The request may have completed on the server; the memory was reloaded — check the list first.")
                        load()
                    }
                }) { Text(tr("发送", "Send")) }
            }
        }
        askResult?.let { r ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(10.dp)) {
                    if (r.reply.isNotBlank()) Text(r.reply, style = MaterialTheme.typography.bodyMedium)
                    for (c in r.changes) {
                        Text(
                            (if (c.ok) "✓ " else "✗ ") + MemoryPanel.opLabel(c.op) + "（" + MemoryPanel.scopeLabel(c.scope) + "）：" + c.text +
                                (if (!c.ok && c.error != null) " — " + c.error else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (c.ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = { askResult = null }) { Text(tr("收起", "Dismiss")) }
                }
            }
        }
        val v = view
        if (v == null) {
            if (error == null) Text(tr("读取中…", "Loading…"), modifier = Modifier.padding(16.dp))
            else TextButton(onClick = { error = null; load() }, modifier = Modifier.padding(8.dp)) { Text(tr("重试", "Retry")) }
            return@Column
        }
        if (v.summarizing) Text(tr("正在生成摘要…", "Writing the summary…"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        v.error?.let { Text(tr("摘要生成失败：", "Summary failed: ") + it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        v.summary?.let { Text(tr("摘要：", "Summary: ") + it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        v.tidy?.let { t ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        tr("最近一次自动整理（${MemoryPanel.scopeLabel(t.scope)}）：合并 ${t.merged}，移动 ${t.moved}，淘汰 ${t.retired}", "Last tidy (${MemoryPanel.scopeLabel(t.scope)}): ${t.merged} merged, ${t.moved} moved, ${t.retired} retired"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    for ((op, text) in t.items.take(5)) Text("· " + MemoryPanel.tidyOpLabel(op) + tr("：", ": ") + text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(enabled = "$prefix:tidy" !in api.inFlight, onClick = {
                        confirm = tr("撤销这次自动整理？被合并、移动或淘汰的条目会恢复原样。", "Undo this tidy? Merged, moved and retired entries go back as they were.") to {
                            write("tidy", "memory-tidy-undo", R.tidyUndo(owner, t), tr("已撤销整理", "Tidy undone"))
                        }
                    }) { Text(tr("撤销整理", "Undo tidy")) }
                }
            }
        }
        val groups = MemoryPanel.groups(v)
        val addScopes = if (owner is MemoryPanel.Owner.Room) listOf("group") else listOf("own", "team")
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (groups.isEmpty()) item(key = "empty") { Text(tr("还没有记忆", "No memory yet"), modifier = Modifier.padding(16.dp)) }
            for (scope in (groups.map { it.scope } + addScopes).distinct()) {
                val list = groups.firstOrNull { it.scope == scope }?.entries.orEmpty()
                item(key = "h:$scope") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp)) {
                        Text(MemoryPanel.scopeLabel(scope) + "（${list.size}）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        if (scope in addScopes) TextButton(onClick = { adding = scope }) { Text(tr("新增", "Add")) }
                    }
                }
                // 键带范围与组内位置（MemoryPanel.rowKey）：own 与 team 的条目 id 可能相同
                itemsIndexed(list, key = { i, it -> MemoryPanel.rowKey(scope, i, it) }) { _, e ->
                    val open = entryKey(e) in expanded
                    Column(
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            onClick = { if (open) expanded.remove(entryKey(e)) else expanded.add(entryKey(e)) },
                            onLongClick = { menu = e },
                        ).padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(e.text, style = MaterialTheme.typography.bodyMedium, maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(
                            if (e.pinned) tr("📌 置顶", "📌 Pinned") else null,
                            e.topic?.let { tr("主题 $it", "Topic $it") },
                            if (e.expired) tr("已过期", "Expired") else e.until?.let { tr("到 $it", "Until $it") },
                            e.added,
                        )
                        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = if (e.expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    menu?.let { e ->
        AlertDialog(
            onDismissRequest = { menu = null },
            title = { Text(tr("记忆条目", "Memory entry")) },
            text = {
                Column {
                    TextButton(onClick = { menu = null; write("pin", "memory-pin", R.pin(owner, e, !e.pinned)) }) { Text(if (e.pinned) tr("取消置顶", "Unpin") else tr("置顶", "Pin")) }
                    TextButton(onClick = { menu = null; editing = e }) { Text(tr("编辑", "Edit")) }
                    TextButton(onClick = { menu = null; clipboard.setText(AnnotatedString(e.text)); info = tr("已复制", "Copied") }) { Text(tr("复制", "Copy")) }
                    TextButton(onClick = { menu = null; moving = e }) { Text(tr("移到其他记忆", "Move to another memory")) }
                    if (e.scope == "team") {
                        // 团队条目：在主题中 → 设为所有 Bot 总是读到；已在主文件 → 放回主题
                        TextButton(onClick = { menu = null; write("layer", "memory-layer", R.layer(owner, e, always = e.topic != null)) }) {
                            Text(if (e.topic != null) tr("设为所有 Bot 总是读到", "Make every Bot always read it") else tr("放回主题（按需读取）", "Send back to a topic"))
                        }
                    }
                    TextButton(onClick = {
                        menu = null
                        confirm = tr("遗忘这条记忆？可以在历史里恢复。", "Forget this entry? You can restore it from History.") to {
                            write("forget", "memory-forget", R.forget(owner, e), tr("已遗忘", "Forgotten"))
                        }
                    }) { Text(tr("遗忘", "Forget"), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { menu = null }) { Text(tr("关闭", "Close")) } },
        )
    }
    editing?.let { e ->
        TextEntryDialog(tr("编辑记忆", "Edit entry"), e.text, view?.entryLimit ?: MemoryPanel.ENTRY_MAX_DEFAULT, onDismiss = { editing = null }) { next ->
            editing = null
            write("edit", "memory-edit", R.edit(owner, e, next))
        }
    }
    adding?.let { scope ->
        TextEntryDialog(tr("新增到", "Add to ") + MemoryPanel.scopeLabel(scope), "", view?.entryLimit ?: MemoryPanel.ENTRY_MAX_DEFAULT, onDismiss = { adding = null }) { text ->
            adding = null
            write("add", "memory-add", R.add(owner, scope, text))
        }
    }
    moving?.let { e ->
        val targets = view?.let { MemoryPanel.moveTargets(it, e.scope) }.orEmpty()
        PickerDialog(
            tr("移到", "Move to"),
            targets.map { Triple(it.key, it.name, when (it.kind) { "group" -> tr("群", "Group"); "team" -> null; else -> "Bot" }) },
            null,
            onPick = { key -> moving = null; write("move", "memory-move", R.move(owner, e, key), tr("已移动", "Moved")) },
            onDismiss = { moving = null },
        )
    }
    confirm?.let { (text, run) ->
        ConfirmDialog(tr("请确认", "Please confirm"), text, tr("确认", "Confirm"), onConfirm = run, onDismiss = { confirm = null })
    }
}

/** 文字输入对话框：超出条目上限或为空时禁用保存并提示。 */
@Composable
private fun TextEntryDialog(title: String, initial: String, limit: Int, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val problem = MemoryPanel.entryProblem(text, limit)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                // 一条记忆是一行（上游 cleanEntry 会把空白折叠成一个空格）：换行直接换成空格，所见即所存
                value = text, onValueChange = { text = it.replace('\n', ' ') }, minLines = 3,
                isError = text.isNotEmpty() && problem != null,
                supportingText = { Text(if (text.isNotEmpty() && problem != null) problem else "${text.trim().length}/$limit") },
            )
        },
        confirmButton = { TextButton(enabled = problem == null && text.trim() != initial.trim(), onClick = { onSave(text) }) { Text(tr("保存", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}

/** 记忆变更历史（dsbot-memory）：分页（more → before），可恢复未恢复过的记录；恢复后从第一页重读。 */
@Composable
fun MemoryHistoryPage(owner: MemoryPanel.Owner, api: ManageApi) {
    val rows = remember { mutableStateListOf<MemoryPanel.HistoryRow>() }
    var more by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<MemoryPanel.HistoryRow?>(null) }
    var generation by remember { mutableStateOf(0) }
    fun load(before: String?) {
        if (before == null) generation++
        val gen = generation
        loading = true
        api.read("memory-history", MemoryPanel.Requests.history(owner, before)) { r ->
            if (gen != generation) return@read
            loading = false
            if (r is DsbotApi.Result.Ok) {
                val h = MemoryPanel.decodeHistory(r.value)
                if (before == null) rows.clear()
                rows.addAll(h.rows.filter { n -> rows.none { it.id == n.id } })
                more = h.more
            } else {
                error = readError(r)
            }
        }
    }
    LaunchedEffect(owner) { load(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(tr("记忆变更历史", "Memory history"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        ErrorLine(error) { error = null }
        InfoLine(info, error = false) { info = null }
        if (rows.isEmpty() && !loading) Text(tr("还没有变更记录", "No changes yet"), modifier = Modifier.padding(16.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { row ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(MemoryPanel.opLabel(row.op) + "（" + MemoryPanel.scopeLabel(row.scope) + "）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(row.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(row.time?.take(16)?.replace('T', ' '), row.by?.let { if (it == "user") tr("你", "you") else it }).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (row.restored) {
                        Text(tr("已恢复", "Restored"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        TextButton(enabled = "mem:${owner.id}:restore" !in api.inFlight, onClick = { confirm = row }) { Text(tr("恢复", "Restore")) }
                    }
                }
            }
            if (more != null) {
                item(key = "more") { TextButton(enabled = !loading, onClick = { load(more) }) { Text(tr("加载更多", "Load more")) } }
            }
        }
    }
    confirm?.let { row ->
        ConfirmDialog(
            tr("请确认", "Please confirm"),
            tr("恢复这次「${MemoryPanel.opLabel(row.op)}」？", "Undo this \"${MemoryPanel.opLabel(row.op)}\"?"),
            tr("恢复", "Restore"),
            onConfirm = {
                error = null
                api.action("mem:${owner.id}:restore", "memory-restore", MemoryPanel.Requests.restore(owner, row.id), {
                    info = tr("已恢复", "Restored")
                    load(null)
                }) { error = it }
            },
            onDismiss = { confirm = null },
        )
    }
}
