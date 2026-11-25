package work.keepwriting.dshanywhere

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import work.keepwriting.core.BotManage
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.ImBridges
import work.keepwriting.core.Json
import work.keepwriting.core.Schedules
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/*
 * 定时任务（dsbot-schedules）：日程列表（分组、暂停/恢复、删除）、新建与 Bot 详情的入口行。
 * 日程不在 state 中：schedules 按需读取（页面打开期间约每分钟重读），写端点返回新视图直接采用。
 */

private sealed interface ScLoad {
    data object Loading : ScLoad
    data class Ok(val view: Schedules.View) : ScLoad
    data class Failed(val text: String, val unsupported: Boolean) : ScLoad
}

private fun scLoadOf(r: DsbotApi.Result): ScLoad = when (r) {
    is DsbotApi.Result.Ok -> Schedules.decode(r.value)?.let { ScLoad.Ok(it) } ?: ScLoad.Failed(tr("服务端应答无法识别", "The server answer could not be read"), false)
    is DsbotApi.Result.BotError -> ScLoad.Failed(BotManage.errorText(r.code, r.message), r.code == "bot/not-found")
    is DsbotApi.Result.Failure -> ScLoad.Failed(BotManage.errorText(null, null), false)
}

@Composable
private fun ScNote(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

/** 日程页；[filterOwner] 为某 Bot 时只显示它的任务（可切换为全部）。 */
@Composable
fun SchedulesPage(state: Json.Value.Object?, api: ManageApi, filterOwner: String?, onCreate: (String?) -> Unit) {
    var load by remember { mutableStateOf<ScLoad>(ScLoad.Loading) }
    var owner by remember(filterOwner) { mutableStateOf(filterOwner) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var deleting by remember { mutableStateOf<Schedules.Task?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val gen = remember { intArrayOf(0) }
    val lastRefresh = remember { longArrayOf(0L) }
    val zone = remember { ZoneId.systemDefault() }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    fun reload(background: Boolean = false) {
        val mine = ++gen[0]
        api.read("schedules", Schedules.Requests.list()) {
            if (mine != gen[0]) return@read
            val next = scLoadOf(it)
            val ts = System.currentTimeMillis()
            lastRefresh[0] = ts
            // 后台定期轮询遇瞬时网络失败时不清空已显示的列表；远端明确不支持（bot/not-found）除外
            if (background && load is ScLoad.Ok && next is ScLoad.Failed && !next.unsupported) return@read
            load = next
            now = ts
        }
    }
    // 页面打开且 App 在前台期间约每分钟重读（下次运行与逾期随时间变化；网页端同样轮询）；远端不支持时停止轮询
    LaunchedEffect(Unit) {
        reload(background = false)
        while (true) {
            delay(60_000)
            if ((load as? ScLoad.Failed)?.unsupported == true) break
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) reload(background = load is ScLoad.Ok)
        }
    }
    // 从后台返回前台且距上次刷新已达 60 秒时立即重读，不等下一轮 60 秒刻度
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START &&
                (load as? ScLoad.Failed)?.unsupported != true &&
                lastRefresh[0] > 0L &&
                System.currentTimeMillis() - lastRefresh[0] >= 60_000L
            ) {
                reload(background = load is ScLoad.Ok)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val applyView: (Json.Value) -> Unit = { v ->
        val decoded = Schedules.decode(v)
        if (decoded != null) {
            gen[0]++
            val ts = System.currentTimeMillis()
            load = ScLoad.Ok(decoded)
            now = ts
            lastRefresh[0] = ts
        } else {
            reload(background = load is ScLoad.Ok)
        }
    }
    fun act(key: String, endpoint: String, payload: String) {
        error = null
        api.action(key, endpoint, payload, applyView) { error = it; reload(background = load is ScLoad.Ok) }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(tr("定时任务", "Scheduled tasks"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        when (val l = load) {
            ScLoad.Loading -> ScNote(tr("读取中…", "Loading…"))
            is ScLoad.Failed -> {
                if (l.unsupported) Text(tr("此远端不支持定时任务。", "This server does not offer scheduled tasks."), modifier = Modifier.padding(16.dp))
                else {
                    ErrorLine(l.text) {}
                    TextButton(onClick = { load = ScLoad.Loading; reload() }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("重试", "Try again")) }
                }
            }
            is ScLoad.Ok -> {
                val view = l.view
                if (!view.available) {
                    Text(tr("需要在 DSH 设置的「插件」页开启 Automation tasks。", "Switch on Automation tasks on the Plugins page in DSH Settings."), modifier = Modifier.padding(16.dp))
                    return@Column
                }
                Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { onCreate(owner) }) { Text(tr("＋ 新建任务", "+ New task")) }
                    if (owner != null) TextButton(onClick = { owner = null }) { Text(tr("显示全部", "Show all")) }
                }
                ErrorLine(error) { error = null }
                val groups = Schedules.groups(state, view, owner)
                if (groups.isEmpty()) ScNote(tr("还没有定时任务。", "No scheduled tasks yet."))
                for (g in groups) {
                    SectionTitle(if (g.isRoom) tr("群聊：", "Group: ") + g.name else g.name)
                    for (t in g.tasks) {
                        val taskKey = "${t.sessionId.orEmpty()}:${t.id}"
                        val taskInFlightKey = "sc:task:$taskKey"
                        val open = taskKey in expanded
                        Column(modifier = Modifier.fillMaxWidth().clickable { expanded = if (open) expanded - taskKey else expanded + taskKey }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(t.title, style = MaterialTheme.typography.bodyLarge,
                                color = if (t.status == "active") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(Schedules.timingText(t, now, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!g.isRoom && !t.roomId.isNullOrEmpty()) {
                                val rName = Schedules.roomName(state, t.roomId) ?: t.roomId
                                Text(tr("群聊：", "Group: ") + rName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(Schedules.nextText(t, now, zone), style = MaterialTheme.typography.bodySmall,
                                color = if (Schedules.isOverdue(t, now)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            if (t.prompt.isNotBlank() && t.prompt != t.title) {
                                Text(t.prompt, style = MaterialTheme.typography.bodySmall, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                            }
                            Row {
                                val busy = taskInFlightKey in api.inFlight
                                if (Schedules.canPause(view, t, now)) {
                                    TextButton(enabled = !busy, onClick = { act(taskInFlightKey, "schedule-pause", Schedules.Requests.pause(t, t.status != "active")) }) {
                                        Text(if (t.status == "active") tr("暂停", "Pause") else tr("恢复", "Resume"))
                                    }
                                }
                                TextButton(enabled = !busy, onClick = { deleting = t }) { Text(tr("删除", "Delete"), color = MaterialTheme.colorScheme.error) }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
    deleting?.let { t ->
        val taskInFlightKey = "sc:task:${t.sessionId.orEmpty()}:${t.id}"
        ConfirmDialog(tr("删除「${t.title}」？", "Delete \"${t.title}\"?"), tr("删除后不再运行，不能恢复。", "It will not run again. This cannot be undone."), tr("删除", "Delete"),
            onConfirm = { act(taskInFlightKey, "schedule-delete", Schedules.Requests.delete(t)) }, onDismiss = { deleting = null })
    }
}

/** Bot 详情的「定时任务」行：该 Bot 进行中的任务数，点按进入只显示它的日程页。远端不支持或未开启时不显示。 */
@Composable
fun BotSchedulesRow(botId: String, api: ManageApi, onOpen: () -> Unit) {
    var view by remember { mutableStateOf<Schedules.View?>(null) }
    val gen = remember { intArrayOf(0) }
    LaunchedEffect(botId) {
        val mine = ++gen[0]
        api.read("schedules", Schedules.Requests.list()) { r ->
            if (mine == gen[0]) view = (r as? DsbotApi.Result.Ok)?.let { Schedules.decode(it.value) }
        }
    }
    val v = view ?: return
    if (!v.available) return
    val n = Schedules.activeCount(v, botId)
    SectionTitle(tr("定时任务", "Scheduled tasks"))
    ValueRow(tr("定时任务", "Scheduled tasks"), if (n > 0) tr("$n 个进行中", "$n active") else tr("新建", "New"), onClick = onOpen)
}

// ---- 新建 ----

private enum class Kind { ONCE, DAILY, WEEKLY, EVERY }

/** 新建定时任务（网页端 TaskForm）：发送对象、名称、要做的事与时间规则；成功后 [onDone]。 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScheduleCreatePage(state: Json.Value.Object?, api: ManageApi, start: String?, onDone: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val bots = remember(state) { ImBridges.bots(state) }
    val rooms = remember(state) { ImBridges.rooms(state) }
    var target by remember { mutableStateOf(start ?: bots.firstOrNull()?.first ?: rooms.firstOrNull()?.first) }
    LaunchedEffect(bots, rooms) {
        if (target == null) target = start ?: bots.firstOrNull()?.first ?: rooms.firstOrNull()?.first
    }
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(Kind.DAILY) }
    // 一次：默认一小时后整点（网页端 inAnHour）
    var onceDate by remember { mutableStateOf(LocalDateTime.now(zone).plusHours(1).withMinute(0).withSecond(0).withNano(0)) }
    var hour by remember { mutableIntStateOf(9) }
    var minute by remember { mutableIntStateOf(0) }
    var weekdays by remember { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
    var count by remember { mutableStateOf("1") }
    var unit by remember { mutableIntStateOf(60) }
    var picking by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = "sc:create" in api.inFlight
    val whenValue: Schedules.When = when (kind) {
        Kind.ONCE -> Schedules.When.Once(onceDate)
        Kind.DAILY -> Schedules.When.Daily(hour, minute)
        Kind.WEEKLY -> Schedules.When.Weekly(hour, minute, weekdays)
        Kind.EVERY -> Schedules.When.Every(count.toIntOrNull() ?: 0, unit)
    }
    val problem = Schedules.createProblem(target, title, prompt, whenValue, System.currentTimeMillis(), zone)
    val targetName = target?.let { id -> bots.firstOrNull { it.first == id }?.second ?: rooms.firstOrNull { it.first == id }?.second }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Text(tr("新建定时任务", "New scheduled task"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        ErrorLine(error) { error = null }
        ValueRow(tr("发送给", "Send to"), targetName ?: tr("选择", "Choose"), enabled = !busy) { picking = true }
        OutlinedTextField(title, { title = it.take(Schedules.TITLE_MAX) }, label = { Text(tr("名称", "Name")) }, placeholder = { Text(tr("晨间简报", "Morning briefing")) }, singleLine = true, enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        OutlinedTextField(prompt, { prompt = it }, label = { Text(tr("要做的事", "What to do")) }, placeholder = { Text(tr("总结昨天以来的变化和今天需要我处理的事。", "Sum up what changed since yesterday and what needs me today.")) },
            minLines = 3, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        SectionTitle(tr("时间", "When"))
        FlowRow(modifier = Modifier.padding(horizontal = 12.dp)) {
            for ((k, label) in listOf(Kind.ONCE to tr("一次", "Once"), Kind.DAILY to tr("每天", "Every day"), Kind.WEEKLY to tr("每周", "Every week"), Kind.EVERY to tr("重复", "Repeat"))) {
                FilterChip(selected = kind == k, enabled = !busy, onClick = { kind = k }, label = { Text(label) }, modifier = Modifier.padding(4.dp))
            }
        }
        when (kind) {
            Kind.ONCE -> {
                ValueRow(tr("日期", "Date"), onceDate.toLocalDate().toString(), enabled = !busy) { pickingDate = true }
                ValueRow(tr("时刻", "Time"), String.format(java.util.Locale.ROOT, "%02d:%02d", onceDate.hour, onceDate.minute), enabled = !busy) { pickingTime = true }
            }
            Kind.DAILY, Kind.WEEKLY -> {
                if (kind == Kind.WEEKLY) {
                    FlowRow(modifier = Modifier.padding(horizontal = 12.dp)) {
                        for (d in 1..7) FilterChip(selected = d in weekdays, enabled = !busy, onClick = { weekdays = if (d in weekdays) weekdays - d else weekdays + d },
                            label = { Text(Schedules.dayName(d)) }, modifier = Modifier.padding(2.dp))
                    }
                }
                ValueRow(tr("时刻", "Time"), String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute), hint = tr("时区：${zone.id}", "Time zone: ${zone.id}"), enabled = !busy) { pickingTime = true }
            }
            Kind.EVERY -> Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("每隔", "Every"))
                    OutlinedTextField(count, { count = it.filter(Char::isDigit).take(5) }, singleLine = true, enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.padding(horizontal = 8.dp).width(96.dp))
                }
                FlowRow {
                    for ((m, label) in listOf(1 to tr("分钟", "minutes"), 60 to tr("小时", "hours"), 1440 to tr("天", "days"))) {
                        FilterChip(selected = unit == m, enabled = !busy, onClick = { unit = m }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
        }
        if (problem != null && (title.isNotEmpty() || prompt.isNotEmpty())) ScNote(problem, error = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            OutlinedButton(enabled = problem == null && !busy, onClick = {
                val freshProblem = Schedules.createProblem(target, title, prompt, whenValue, System.currentTimeMillis(), zone)
                if (freshProblem != null) {
                    error = freshProblem
                    return@OutlinedButton
                }
                error = null
                api.action("sc:create", "schedule-create", Schedules.Requests.create(target!!, title, prompt, whenValue, zone), { onDone() }) { error = it }
            }) { Text(if (busy) tr("创建中…", "Creating…") else tr("创建任务", "Create task")) }
        }
    }
    if (picking) {
        val options = bots.map { Triple(it.first, it.second, null as String?) } + rooms.map { Triple(it.first, it.second, null as String?) }
        val headers = buildMap { put(0, tr("Bot", "Bots")); if (rooms.isNotEmpty()) put(bots.size, tr("群聊", "Group chats")) }
        PickerDialog(tr("发送给", "Send to"), options, target, headers, onPick = { target = it; picking = false }, onDismiss = { picking = false })
    }
    if (pickingDate) {
        val ds = rememberDatePickerState(initialSelectedDateMillis = onceDate.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    ds.selectedDateMillis?.let { ms -> onceDate = LocalDateTime.of(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate(), onceDate.toLocalTime()) }
                    pickingDate = false
                }) { Text(tr("确定", "OK")) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(tr("取消", "Cancel")) } },
        ) { DatePicker(state = ds) }
    }
    if (pickingTime) {
        val once = kind == Kind.ONCE
        val ts = rememberTimePickerState(initialHour = if (once) onceDate.hour else hour, initialMinute = if (once) onceDate.minute else minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(onClick = {
                    if (once) onceDate = LocalDateTime.of(onceDate.toLocalDate(), LocalTime.of(ts.hour, ts.minute)) else { hour = ts.hour; minute = ts.minute }
                    pickingTime = false
                }) { Text(tr("确定", "OK")) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text(tr("取消", "Cancel")) } },
            text = { TimePicker(state = ts) },
        )
    }
}
