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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import work.keepwriting.core.BotManage
import work.keepwriting.core.BotModelBatch
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.Json
import work.keepwriting.core.ModelProviders
import kotlin.coroutines.resume

/*
 * 模型与服务商（dsbot-models-providers）：服务商列表、key、模型与思考强度、刷新并保存模型、新增与删除，以及 Bot 模型批量切换。
 * 服务商不在 state 中：providers 按需读取，写端点返回新视图直接刷新页面；key 只进请求体，不回显、不入日志。
 */

private fun errorOf(r: DsbotApi.Result): String? = when (r) {
    is DsbotApi.Result.Ok -> null
    is DsbotApi.Result.BotError -> BotManage.errorText(r.code, r.message)
    is DsbotApi.Result.Failure -> BotManage.errorText(null, null)
}

private sealed interface PvLoad {
    data object Loading : PvLoad
    data class Ok(val view: ModelProviders.View) : PvLoad
    data class Failed(val text: String, val unsupported: Boolean) : PvLoad
}

private fun pvLoadOf(r: DsbotApi.Result): PvLoad = when (r) {
    is DsbotApi.Result.Ok -> ModelProviders.decode(r.value)?.let { PvLoad.Ok(it) } ?: PvLoad.Failed(tr("服务端应答无法识别", "The server answer could not be read"), false)
    is DsbotApi.Result.BotError -> PvLoad.Failed(BotManage.errorText(r.code, r.message), r.code == "bot/not-found")
    is DsbotApi.Result.Failure -> PvLoad.Failed(BotManage.errorText(null, null), false)
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/** 模型与服务商页。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelProvidersPage(
    state: Json.Value.Object?,
    api: ManageApi,
    onAdd: () -> Unit,
    onBotModels: () -> Unit,
    onDeepSeekServices: (() -> Unit)? = null,
) {
    var load by remember { mutableStateOf<PvLoad>(PvLoad.Loading) }
    var round by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val gen = remember { intArrayOf(0) }
    val bots = remember(state) { ModelProviders.bots(state) }
    LaunchedEffect(round) {
        val mine = ++gen[0]
        api.read("providers", ModelProviders.Requests.list()) { if (mine == gen[0]) load = pvLoadOf(it) }
    }
    // 写端点返回新视图：直接采用（并让后到的旧读取失效）
    val applyView: (Json.Value) -> Unit = { v -> ModelProviders.decode(v)?.let { gen[0]++; load = PvLoad.Ok(it) } }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Text(tr("模型与服务商", "Models & providers"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        Note(tr("DSH 中设置的模型服务商与 API key；Bot 在详情中选用这些模型。", "Model providers and API keys set up in DSH. Bots pick these models in their details."))
        when (val l = load) {
            PvLoad.Loading -> Note(tr("读取中…", "Loading…"))
            is PvLoad.Failed -> {
                if (l.unsupported) Text(tr("此远端不支持模型服务商设置。", "This server does not offer provider settings."), modifier = Modifier.padding(16.dp))
                else {
                    ErrorLine(l.text) {}
                    TextButton(onClick = { load = PvLoad.Loading; round++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("重试", "Try again")) }
                }
            }
            is PvLoad.Ok -> {
                val view = l.view
                if (!view.available) {
                    Text(tr("这个 DSH 无法设置模型服务商。", "This DSH cannot set up model providers."), modifier = Modifier.padding(16.dp))
                    return@Column
                }
                FlowRow(modifier = Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = onAdd) { Text(tr("＋ 新增服务商", "+ Add a provider")) }
                    TextButton(onClick = onBotModels) { Text(tr("批量切换 Bot 模型", "Change several Bots")) }
                    if (onDeepSeekServices != null) {
                        TextButton(onClick = onDeepSeekServices) { Text(tr("DeepSeek 服务", "DeepSeek services")) }
                    }
                    // 经写通道：成功后补拉 state（带 modelsKey），Bot 的模型选择随之更新；再重读服务商
                    TextButton(enabled = "pv:catalog" !in api.inFlight, onClick = {
                        error = null
                        api.action("pv:catalog", "models", ModelProviders.Requests.refreshCatalog(), { round++ }) { error = it }
                    }) { Text(if ("pv:catalog" in api.inFlight) tr("刷新中…", "Refreshing…") else tr("刷新模型目录", "Refresh catalog")) }
                }
                ErrorLine(error) { error = null }
                SectionTitle(tr("模型服务商 · ${view.providers.size}", "Model providers · ${view.providers.size}"))
                for (p in view.providers) androidx.compose.runtime.key(p.id) {
                    ProviderRow(
                        p = p, view = view, bots = bots, api = api, open = open == p.id,
                        onToggle = { open = if (open == p.id) null else p.id },
                        onView = applyView,
                        pageRevision = view.revision,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderRow(
    p: ModelProviders.Provider,
    view: ModelProviders.View,
    bots: List<ModelProviders.BotRef>,
    api: ManageApi,
    open: Boolean,
    onToggle: () -> Unit,
    onView: (Json.Value) -> Unit,
    pageRevision: String?,
) {
    val users = ModelProviders.users(p, bots)
    var error by remember(p.id) { mutableStateOf<String?>(null) }
    var confirm by remember(p.id) { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var draft by remember(p.id) { mutableStateOf<ModelProviders.Draft?>(null) }
    var refreshing by remember(p.id) { mutableStateOf(false) }
    val efforts = remember(p.id) { mutableStateMapOf<String, String>() }
    fun busy(op: String) = "pv:${p.id}:$op" in api.inFlight
    fun act(op: String, endpoint: String, payload: String, onOk: () -> Unit = {}) {
        error = null
        api.action("pv:${p.id}:$op", endpoint, payload, { v -> onView(v); onOk() }) { error = it }
    }
    LaunchedEffect(open) { if (!open) { draft = null; error = null } }
    val openState = androidx.compose.runtime.rememberUpdatedState(open)

    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge)
            if (p.name != p.id) Text("  ${p.id}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            listOfNotNull(ModelProviders.hint(p), ModelProviders.keyText(p.key), users.takeIf { it.isNotEmpty() }?.let { u -> tr("使用者：", "Used by ") + u.joinToString("、") { it.name } }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (p.error != null || p.key?.configured == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!open) return
    ErrorLine(error) { error = null }
    // ---- key ----
    val key = p.key
    if (key != null && key.writable) {
        KeyEditor(p, busy("key")) { value, done -> act("key", "provider-key", ModelProviders.Requests.setKey(p.id, value), done) }
        if (key.configured) {
            TextButton(onClick = {
                confirm = tr("移除 ${p.name} 的 API key？", "Remove the API key for ${p.name}?") to { act("key", "provider-key", ModelProviders.Requests.clearKey(p.id)) }
            }, enabled = !busy("key"), modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("移除 key", "Remove key"), color = MaterialTheme.colorScheme.error) }
        }
    } else if (key != null) {
        Note(tr("这个 key 来自 DS Bot 之外（如环境变量），不能在这里修改。", "This key comes from outside DS Bot, such as an environment variable, and cannot be changed here."))
    }
    // ---- 模型 ----
    SectionTitle(tr("模型", "Models"))
    val d = draft
    if (d == null) {
        if (p.models.isEmpty()) Note(tr("没有模型", "No models"))
        for (m in p.models) {
            Column(modifier = Modifier.fillMaxWidth().clickable {
                if (efforts.containsKey(m.ref)) efforts.remove(m.ref) else {
                    efforts[m.ref] = tr("读取中…", "Loading…")
                    api.efforts(m.ref) { e -> if (efforts.containsKey(m.ref)) efforts[m.ref] = if (e.efforts.isEmpty()) tr("没有思考强度可选", "No thinking levels") else tr("思考强度：", "Thinking: ") + e.efforts.joinToString("、") { it.name } }
                }
            }.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(m.name, style = MaterialTheme.typography.bodyMedium)
                efforts[m.ref]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (p.refreshable) {
            TextButton(enabled = !refreshing, modifier = Modifier.padding(horizontal = 8.dp), onClick = {
                refreshing = true
                error = null
                // 先取最新视图（带 revision），再请端点列出模型，组成草稿
                api.read("providers", ModelProviders.Requests.list()) { r1 ->
                    val fresh = (r1 as? DsbotApi.Result.Ok)?.let { ModelProviders.decode(it.value) }
                    if (fresh != null) onView((r1 as DsbotApi.Result.Ok).value) // 同网页端：刷新时先采用最新视图
                    val fp = fresh?.providers?.firstOrNull { it.id == p.id }
                    if (fp == null) {
                        refreshing = false
                        error = errorOf(r1) ?: tr("这个服务商已不存在", "The provider is no longer available")
                        return@read
                    }
                    api.read("provider-models", ModelProviders.Requests.discoverStored(p.id)) { r2 ->
                        refreshing = false
                        errorOf(r2)?.let { error = it; return@read }
                        if (!openState.value) return@read // 刷新期间已收起：丢弃结果
                        draft = ModelProviders.draftFrom(fp, ModelProviders.discovered((r2 as DsbotApi.Result.Ok).value), fresh.revision ?: pageRevision)
                    }
                }
            }) { Text(if (refreshing) tr("刷新中…", "Refreshing…") else tr("刷新模型", "Refresh models")) }
        }
    } else {
        Note(tr("新增 ${d.added} · 移除 ${d.removed} · 已选 ${d.selected.size}", "${d.added} added · ${d.removed} removed · ${d.selected.size} selected"))
        val returned = d.rows.filter { it.returned }
        val unreturned = d.rows.filter { !it.returned }
        if (returned.isEmpty()) Note(tr("端点没有列出模型。", "The endpoint lists no models."))
        else SectionTitle(tr("端点返回的模型", "Models from the endpoint"))
        for (r in returned) DraftRow(r) { draft = d.toggle(r.id) }
        if (unreturned.isNotEmpty()) {
            SectionTitle(tr("端点未返回", "Upstream didn't return"))
            Note(tr("仍要保留的请手动勾选。", "Keep the ids you still want by hand."))
            for (r in unreturned) DraftRow(r) { draft = d.toggle(r.id) }
        }
        if (d.selected.isEmpty()) Note(tr("至少选一个模型", "Select at least one model"), error = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
            TextButton(onClick = { draft = null }) { Text(tr("取消", "Cancel")) }
            OutlinedButton(enabled = d.selected.isNotEmpty() && !busy("models"), onClick = {
                val save = { act("models", "provider-models-save", ModelProviders.Requests.saveModels(p.id, d)) { draft = null } }
                val affected = ModelProviders.affected(p, d, bots)
                if (affected.isEmpty()) save()
                else confirm = tr(
                    "${affected.joinToString("、") { it.name }} 正在用将被移除的模型，下一轮会改用其他可用模型（没有可用模型时会失败）。仍要保存？",
                    "${affected.joinToString(", ") { it.name }} use a model being removed and will switch to another available model on the next turn (or fail if none). Save anyway?",
                ) to save
            }) { Text(tr("保存", "Save")) }
        }
    }
    // ---- 删除 ----
    if (p.removable) {
        TextButton(onClick = {
            confirm = tr("删除 ${p.name}？使用其模型的 Bot 需要另选模型。", "Remove ${p.name}? Bots on its models need another model.") to { act("remove", "provider-remove", ModelProviders.Requests.remove(p.id)) }
        }, enabled = !busy("remove"), modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("删除服务商", "Remove provider"), color = MaterialTheme.colorScheme.error) }
    }
    confirm?.let { (text, run) ->
        ConfirmDialog(tr("请确认", "Please confirm"), text, tr("确定", "OK"), onConfirm = run, onDismiss = { confirm = null })
    }
}

@Composable
private fun DraftRow(r: ModelProviders.Row, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp)) {
        Checkbox(checked = r.selected, onCheckedChange = { onToggle() })
        Column {
            Text(r.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            if (r.name != r.id) Text(r.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** API key 编辑（不回显、禁止截屏、本地预校验）；保存成功后清空输入（值不在界面状态里停留），失败时保留以便修改。 */
@Composable
private fun KeyEditor(p: ModelProviders.Provider, busy: Boolean, onSave: (String, () -> Unit) -> Unit) {
    SecureWhileShown()
    var value by remember(p.id) { mutableStateOf("") }
    var saved by remember(p.id) { mutableStateOf(false) }
    SecretField(value, { value = it; saved = false }, if (p.key?.configured == true) tr("更换 ${p.name} API key", "Replace the ${p.name} API key") else tr("${p.name} API key", "${p.name} API key"), enabled = !busy)
    val problem = if (value.isEmpty()) null else ModelProviders.keyProblem(value)
    problem?.let { Note(it, error = true) }
    if (saved) Note(tr("已保存。", "Saved."))
    OutlinedButton(onClick = { onSave(value) { value = ""; saved = true } }, enabled = value.isNotBlank() && problem == null && !busy, modifier = Modifier.padding(horizontal = 16.dp)) { Text(tr("保存 key", "Save key")) }
}

// ---- 新增服务商 ----

/** 新增服务商：内置（从可添加列表选择，可填 key）或自定义 API（id、名称、端点、协议、key、查找或手填模型）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderAddPage(api: ManageApi, onDone: () -> Unit) {
    var load by remember { mutableStateOf<PvLoad>(PvLoad.Loading) }
    LaunchedEffect(Unit) { api.read("providers", ModelProviders.Requests.list()) { load = pvLoadOf(it) } }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Text(tr("新增服务商", "Add a provider"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        when (val l = load) {
            PvLoad.Loading -> Note(tr("读取中…", "Loading…"))
            is PvLoad.Failed -> ErrorLine(l.text) {}
            is PvLoad.Ok -> if (!l.view.available) Note(tr("这个 DSH 无法设置模型服务商。", "This DSH cannot set up model providers.")) else AddForms(l.view, api, onDone)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddForms(view: ModelProviders.View, api: ManageApi, onDone: () -> Unit) {
    SecureWhileShown()
    var custom by remember { mutableStateOf(view.addable.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = "pv:add" in api.inFlight
    FlowRow(modifier = Modifier.padding(horizontal = 12.dp)) {
        FilterChip(selected = !custom, enabled = view.addable.isNotEmpty(), onClick = { custom = false }, label = { Text(tr("内置服务商", "Known provider")) }, modifier = Modifier.padding(4.dp))
        FilterChip(selected = custom, onClick = { custom = true }, label = { Text(tr("自定义 API", "Custom API")) }, modifier = Modifier.padding(4.dp))
    }
    ErrorLine(error) { error = null }
    if (!custom) {
        var picked by remember { mutableStateOf<String?>(null) }
        var key by remember { mutableStateOf("") }
        var choosing by remember { mutableStateOf(false) }
        ValueRow(tr("服务商", "Provider"), picked?.let { id -> view.addable.firstOrNull { it.id == id }?.name } ?: tr("选择服务商", "Choose a provider")) { choosing = true }
        SecretField(key, { key = it }, tr("API key（可选）", "API key (optional)"), enabled = !busy)
        val problem = if (key.isBlank()) null else ModelProviders.keyProblem(key)
        problem?.let { Note(it, error = true) }
        OutlinedButton(enabled = picked != null && problem == null && !busy, modifier = Modifier.padding(16.dp), onClick = {
            error = null
            api.action("pv:add", "provider-add", ModelProviders.Requests.addKnown(picked!!, key), { onDone() }) { error = it }
        }) { Text(if (busy) tr("添加中…", "Adding…") else tr("添加", "Add")) }
        if (choosing) {
            PickerDialog(tr("选择服务商", "Choose a provider"), view.addable.map { Triple(it.id, it.name, it.id.takeIf { id -> id != it.name }) }, picked,
                onPick = { picked = it; choosing = false }, onDismiss = { choosing = false })
        }
    } else {
        var provider by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var baseURL by remember { mutableStateOf("") }
        var proto by remember { mutableStateOf(view.protocols.firstOrNull() ?: "openai-completions") }
        var key by remember { mutableStateOf("") }
        var typed by remember { mutableStateOf("") }
        var found by remember { mutableStateOf<List<ModelProviders.Discovered>?>(null) }
        var picked by remember { mutableStateOf(setOf<String>()) }
        var asking by remember { mutableStateOf(false) }
        @Composable
        fun F(v: String, set: (String) -> Unit, label: String, placeholder: String? = null, type: KeyboardType = KeyboardType.Text) =
            OutlinedTextField(v, set, label = { Text(label) }, placeholder = placeholder?.let { { Text(it) } }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp))
        F(provider, { provider = it.trim() }, tr("服务商 id", "Provider ID"), tr("例如 my-gateway", "e.g. my-gateway"))
        F(name, { name = it.take(ModelProviders.NAME_MAX) }, tr("名称（可选）", "Name (optional)"))
        F(baseURL, { baseURL = it.trim() }, tr("端点", "Endpoint"), "https://gateway.example/v1", KeyboardType.Uri)
        SectionTitle(tr("协议", "Protocol"))
        FlowRow(modifier = Modifier.padding(horizontal = 12.dp)) {
            for (api0 in view.protocols) FilterChip(selected = proto == api0, onClick = { proto = api0 }, label = { Text(ModelProviders.protocolName(api0)) }, modifier = Modifier.padding(4.dp))
        }
        SecretField(key, { key = it }, tr("API key（可选）", "API key (optional)"), enabled = !busy)
        SectionTitle(tr("模型", "Models"))
        TextButton(enabled = !asking && ModelProviders.endpointOk(baseURL) && (key.isBlank() || ModelProviders.keyProblem(key) == null), modifier = Modifier.padding(horizontal = 8.dp), onClick = {
            asking = true
            error = null
            api.read("provider-models", ModelProviders.Requests.discoverEndpoint(baseURL, proto, key)) { r ->
                asking = false
                errorOf(r)?.let { error = it; return@read }
                found = ModelProviders.discovered((r as DsbotApi.Result.Ok).value)
                picked = emptySet()
            }
        }) { Text(if (asking) tr("查找中…", "Asking…") else tr("从端点查找模型", "Fetch models")) }
        found?.let { list ->
            if (list.isEmpty()) Note(tr("端点没有列出模型，请在下面手填模型 id。", "The endpoint lists no models. Enter their ids below."))
            for (m in list) {
                val on = m.id in picked
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { picked = if (on) picked - m.id else picked + m.id }.padding(horizontal = 8.dp)) {
                    Checkbox(checked = on, onCheckedChange = { picked = if (it) picked + m.id else picked - m.id })
                    Text(m.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        F(typed, { typed = it }, tr("手填模型 id（逗号或空格分隔）", "Model ids, separated by commas"))
        val models = ModelProviders.customModels(found.orEmpty(), picked, typed)
        val problem = ModelProviders.customProblem(provider, baseURL, proto, view.protocols, key, models)
        if (problem != null && (provider.isNotEmpty() || baseURL.isNotEmpty())) Note(problem, error = true)
        OutlinedButton(enabled = problem == null && !busy, modifier = Modifier.padding(16.dp), onClick = {
            error = null
            api.action("pv:add", "provider-add", ModelProviders.Requests.addCustom(provider, name, baseURL, proto, key, models), { onDone() }) { error = it }
        }) { Text(if (busy) tr("添加中…", "Adding…") else tr("添加", "Add")) }
    }
}

// ---- Bot 模型批量切换 ----

private suspend fun ManageApi.write(key: String, endpoint: String, payload: String): DsbotApi.Result =
    suspendCancellableCoroutine { cont -> actionRaw(key, endpoint, payload) { if (cont.isActive) cont.resume(it) } }

/** Bot 模型批量切换（网页端 BotModelsPage）：本地 Agent 上的 Bot 不可选；逐个 update-bot，完成后汇总。 */
@Composable
fun BotModelsPage(state: Json.Value.Object?, api: ManageApi) {
    val scope = rememberCoroutineScope()
    val bots = remember(state) { ModelProviders.bots(state) }
    val groups = remember(state) { BotManage.models(state).map { g -> g.copy(models = g.models.filter { !ModelProviders.isLocal(it.ref) }) }.filter { it.models.isNotEmpty() } }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<BotModelBatch.Result>?>(null) }
    val eligible = bots.filter { !ModelProviders.isLocal(it.current) }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(tr("批量切换 Bot 模型", "Change several Bots"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        Note(tr("已选 ${selected.size} / ${bots.size}；本地 Agent 上的 Bot 保留自己的模型。", "${selected.size} of ${bots.size} selected. Bots on a local agent keep their own model."))
        Row(modifier = Modifier.padding(horizontal = 8.dp)) {
            TextButton(enabled = !busy && eligible.isNotEmpty() && selected.size < eligible.size, onClick = { selected = eligible.map { it.id }.toSet() }) { Text(tr("全选", "Select all")) }
            if (selected.isNotEmpty()) TextButton(enabled = !busy, onClick = { selected = emptySet() }) { Text(tr("清空", "Clear")) }
            OutlinedButton(enabled = !busy && selected.isNotEmpty(), onClick = { picking = true }) { Text(if (busy) tr("切换中…", "Changing…") else tr("选择模型…", "Choose a model…")) }
        }
        results?.let { Note(BotModelBatch.summaryText(BotModelBatch.summarize(it))) }
        SectionTitle(tr("Bot · ${bots.size}", "Bots · ${bots.size}"))
        val byId = results.orEmpty().associateBy { it.id }
        for (b in bots) {
            val local = ModelProviders.isLocal(b.current)
            val on = b.id in selected
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = !local && !busy) { selected = if (on) selected - b.id else selected + b.id }.padding(horizontal = 8.dp)) {
                Checkbox(checked = on, enabled = !local && !busy, onCheckedChange = { selected = if (it) selected + b.id else selected - b.id })
                Column {
                    Text(b.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        (BotManage.modelName(state, b.current) ?: tr("未设置模型", "No model")) + if (local) tr(" · 本地 Agent", " · Local agent") else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    byId[b.id]?.let { r ->
                        Text(BotModelBatch.statusText(r), style = MaterialTheme.typography.bodySmall,
                            color = if (r.status == BotModelBatch.Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    if (picking) {
        val options = groups.flatMap { g -> g.models.map { Triple(it.ref, it.name, g.providerName) } }
        val headers = buildMap { var i = 0; for (g in groups) { put(i, g.providerName); i += g.models.size } }
        PickerDialog(tr("为选中的 Bot 选择模型", "Model for the selected Bots"), options, null, headers, onDismiss = { picking = false }, onPick = { ref ->
            picking = false
            val chosen = bots.filter { it.id in selected }
            val served = groups.flatMap { g -> g.models.map { it.ref } }.toSet()
            busy = true
            val r = ++round
            // 离开页面也跑完（同网页端 runModelChange 不中断）：避免一部分 Bot 已切、其余没切且没有汇总
            scope.launch(kotlinx.coroutines.NonCancellable) {
                val out = mutableListOf<BotModelBatch.Result>()
                for (step in BotModelBatch.plan(chosen, ref, served)) {
                    val b = step.bot
                    out += when (step.action) {
                        BotModelBatch.Action.SKIP_LOCAL, BotModelBatch.Action.SKIP_UNAVAILABLE -> BotModelBatch.Result(b.id, b.name, BotModelBatch.Status.SKIPPED)
                        BotModelBatch.Action.KEEP -> BotModelBatch.Result(b.id, b.name, BotModelBatch.Status.UNCHANGED)
                        BotModelBatch.Action.CHANGE -> {
                            val res = api.write("batch:$r:${b.id}", "update-bot", BotModelBatch.request(b.id, ref))
                            errorOf(res)?.let { BotModelBatch.Result(b.id, b.name, BotModelBatch.Status.FAILED, it) } ?: BotModelBatch.Result(b.id, b.name, BotModelBatch.Status.UPDATED)
                        }
                    }
                }
                results = out
                selected = emptySet()
                busy = false
            }
        })
    }
}
