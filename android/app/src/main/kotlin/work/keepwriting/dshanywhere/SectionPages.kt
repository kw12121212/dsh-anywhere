package work.keepwriting.dshanywhere

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import work.keepwriting.core.TeamHome
import work.keepwriting.core.TeamSections

/** 分组名称对话框（新建与重命名共用）：本地先按服务端规则提示空名与重名，问题解决前不能提交。 */
@Composable
internal fun SectionNameDialog(
    title: String,
    initial: String,
    sections: List<TeamSections.Section>,
    selfId: String?,
    allowEmpty: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    // 新建时可留空（服务端取「New section」并加序号）；改名时空名不允许
    val problem = if (allowEmpty && TeamSections.clean(draft).isEmpty()) null else TeamSections.nameProblem(sections, draft, selfId)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(TeamSections.NAME_MAX * 2) },
                    singleLine = true,
                    placeholder = { Text(tr("分组名称", "Section name")) },
                    isError = problem != null && draft.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
                val hint = problem?.takeIf { draft.isNotEmpty() }
                    ?: if (TeamSections.clean(draft).length >= TeamSections.NAME_MAX) tr("至多 ${TeamSections.NAME_MAX} 个字，超出部分不保存。", "Up to ${TeamSections.NAME_MAX} characters; the rest is dropped.") else null
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
            }
        },
        confirmButton = { TextButton(enabled = problem == null, onClick = { onDismiss(); onConfirm(draft) }) { Text(tr("保存", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}

/**
 * 分组管理页（dsbot-sidebar-sections）：新建、重命名、拖动「≡」排序（松手一次提交完整顺序，失败恢复原顺序并显示原因）、
 * 删除（二次确认，组内条目回到未分组）。显示顺序以 state 为准；拖动与提交期间用本地顺序。
 */
@Composable
fun SectionsManagePage(team: TeamHome.Team?, sections: List<TeamSections.Section>, api: ManageApi) {
    var error by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf<TeamSections.Section?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TeamSections.Section?>(null) }
    val serverIds = sections.map { it.id }
    // 拖动/提交期间的本地顺序；null 表示跟随 state。分组增删时只保留仍存在的、并把新分组接在后面
    var local by remember { mutableStateOf<List<String>?>(null) }
    val order = local?.let { l -> l.filter { it in serverIds } + serverIds.filter { it !in l } } ?: serverIds
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(0f) }
    val byId = sections.associateBy { it.id }
    val ordering = "sections:order" in api.inFlight
    // 手势协程只在键变化时重启：经 rememberUpdatedState 读最新的顺序与服务端顺序
    val latestOrder by rememberUpdatedState(order)
    val latestServer by rememberUpdatedState(serverIds)

    fun submit(next: List<String>) {
        val server = latestServer
        if (next == server || !TeamSections.isPermutation(server, next)) {
            local = null
            return
        }
        api.action("sections:order", "section-order", TeamSections.Requests.order(next), { local = null }) {
            local = null // 失败：恢复为服务端顺序
            error = it
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(tr("管理分组", "Manage sections"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        Text(
            tr("拖动 ≡ 调整顺序，松手后保存；与网页端侧栏共用。", "Drag ≡ to reorder; the order saves when you let go. Shared with the web sidebar."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
        )
        ErrorLine(error) { error = null }
        TextButton(onClick = { error = null; creating = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("＋ 新建分组", "+ New section")) }
        if (sections.isEmpty()) {
            Text(tr("还没有分组。", "No sections yet."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
        val dragStart = dragId?.let { order.indexOf(it) }?.takeIf { it >= 0 }
        val dragTarget = if (dragStart != null) TeamSections.targetIndex(dragStart, dragOffset, rowHeight, order.size) else null
        for ((index, id) in order.withIndex()) {
            key(id) {
            val sec = byId[id]
            if (sec != null) {
            val count = team?.sections?.firstOrNull { it.id == id }?.entries?.size ?: 0
            val dragging = dragId == id
            val shiftY = when {
                dragging -> dragOffset
                dragStart != null && dragTarget != null && dragStart < dragTarget && index in (dragStart + 1)..dragTarget -> -rowHeight
                dragStart != null && dragTarget != null && dragStart > dragTarget && index in dragTarget until dragStart -> rowHeight
                else -> 0f
            }
            Box(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).zIndex(if (dragging) 1f else 0f)) {
                Surface(
                    tonalElevation = if (dragging) 6.dp else 0.dp,
                    shadowElevation = if (dragging) 6.dp else 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { if (rowHeight == 0f || dragging) rowHeight = it.height.toFloat() }
                        .graphicsLayer { translationY = shiftY },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
                        Text(
                            "≡",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (ordering) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(sec.name, style = MaterialTheme.typography.bodyLarge)
                            Text(tr("$count 个对话", "$count conversations") + if (sec.collapsed) tr(" · 已折叠", " · collapsed") else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(enabled = "section:rename:$id" !in api.inFlight, onClick = { error = null; naming = sec }) { Text(tr("重命名", "Rename")) }
                        TextButton(enabled = "section:delete:$id" !in api.inFlight, onClick = { error = null; deleting = sec }) {
                            Text(tr("删除", "Delete"), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                // 拖动热区放在不平移的容器上：拖动期间手势坐标不随行平移产生回馈跳动，松手后一次提交新顺序
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(56.dp)
                        .fillMaxHeight()
                        .semantics { contentDescription = tr("拖动调整「${sec.name}」的位置", "Drag to move ${sec.name}") }
                        .pointerInput(id, ordering) {
                            if (ordering) return@pointerInput
                            detectDragGestures(
                                onDragStart = { error = null; local = latestOrder; dragId = id; dragOffset = 0f },
                                onDragEnd = {
                                    val cur = latestOrder
                                    val from = cur.indexOf(id)
                                    val to = if (from >= 0) TeamSections.targetIndex(from, dragOffset, rowHeight, cur.size) else -1
                                    dragId = null
                                    dragOffset = 0f
                                    if (from >= 0 && to >= 0 && to != from) {
                                        val next = TeamSections.move(cur, from, to)
                                        local = next
                                        submit(next)
                                    } else {
                                        local = null
                                    }
                                },
                                onDragCancel = { dragId = null; dragOffset = 0f; local = null },
                            ) { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            }
                        },
                )
            }
            }
            }
        }
    }
    if (creating) {
        SectionNameDialog(tr("新建分组", "New section"), "", sections, null, allowEmpty = true, onConfirm = { name ->
            api.action("section:create", "section-save", TeamSections.Requests.create(name), {}) { error = it }
        }, onDismiss = { creating = false })
    }
    naming?.let { sec ->
        SectionNameDialog(tr("重命名分组", "Rename section"), sec.name, sections, sec.id, allowEmpty = false, onConfirm = { name ->
            if (TeamSections.clean(name) != sec.name) {
                api.action("section:rename:${sec.id}", "section-save", TeamSections.Requests.rename(sec.id, name), {}) { error = it }
            }
        }, onDismiss = { naming = null })
    }
    deleting?.let { sec ->
        val count = team?.sections?.firstOrNull { it.id == sec.id }?.entries?.size ?: 0
        ConfirmDialog(
            tr("删除分组「${sec.name}」？", "Delete section \"${sec.name}\"?"),
            if (count > 0) tr("组内 $count 个对话回到未分组，对话本身不受影响。网页端同步。", "Its $count conversations move back to Ungrouped; the conversations themselves are kept. The web app updates too.")
            else tr("分组是空的。网页端同步。", "The section is empty. The web app updates too."),
            tr("删除", "Delete"),
            onConfirm = { api.action("section:delete:${sec.id}", "section-delete", TeamSections.Requests.delete(sec.id), {}) { error = it } },
            onDismiss = { deleting = null },
        )
    }
}

/** 条目长按「移到分组」（set-flags 只改这一个条目）：列出全部分组与「不分组」，当前所在项打勾。 */
@Composable
internal fun MoveToSectionDialog(entry: TeamHome.Entry, sections: List<TeamSections.Section>, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(Triple<String?, String, String?>(null, tr("不分组", "No section"), null)) +
        sections.map { Triple<String?, String, String?>(it.id, it.name, null) }
    PickerDialog(tr("把「${entry.name}」移到分组", "Move \"${entry.name}\" to a section"), options, entry.sectionId, onPick = { id ->
        onDismiss()
        if (id != entry.sectionId) onPick(id)
    }, onDismiss = onDismiss)
}
