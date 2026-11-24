package work.keepwriting.dshanywhere

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import work.keepwriting.core.BotManage
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.LocalAgents

/*
 * 本地 Agent（dsbot-local-agents）：检测到的 Agent CLI（刷新、登录命令）、自定义 ACP Agent（新增、修改、删除）与
 * 各 Agent 的可用模型。不在 state 中：列表经只读通道按需读取，增改删经写通道，成功后重读列表（同网页端）。
 */

private sealed interface LaLoad {
    data object Loading : LaLoad
    data class Ok(val view: LocalAgents.View) : LaLoad
    data class Failed(val text: String, val unsupported: Boolean) : LaLoad
}

private sealed interface LaModels {
    data object Loading : LaModels
    data class Ok(val models: List<LocalAgents.Model>) : LaModels
    data class Failed(val text: String) : LaModels
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalAgentsPage(api: ManageApi) {
    val clipboard = LocalClipboardManager.current
    var load by remember { mutableStateOf<LaLoad>(LaLoad.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) } // "new" 或自定义 Agent id
    var removing by remember { mutableStateOf<LocalAgents.Custom?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    val models = remember { mutableStateMapOf<String, LaModels>() }
    val R = LocalAgents.Requests

    fun apply(r: DsbotApi.Result) {
        load = when (r) {
            is DsbotApi.Result.Ok -> LocalAgents.decode(r.value)?.let { LaLoad.Ok(it) } ?: LaLoad.Failed(BotManage.errorText(null, null), false)
            is DsbotApi.Result.BotError -> LaLoad.Failed(BotManage.errorText(r.code, r.message), r.code == "bot/not-found")
            is DsbotApi.Result.Failure -> LaLoad.Failed(BotManage.errorText(null, null), false)
        }
    }
    LaunchedEffect(round) { api.read("local-agents", R.list()) { apply(it) } }
    fun refresh() {
        if (refreshing) return
        refreshing = true
        error = null
        api.read("local-agent-refresh", R.refresh()) { r ->
            refreshing = false
            models.clear()
            if (r is DsbotApi.Result.Ok) apply(r) else error = (r as? DsbotApi.Result.BotError)?.let { BotManage.errorText(it.code, it.message) } ?: BotManage.errorText(null, null)
        }
    }
    fun toggleModels(route: String) {
        if (models.containsKey(route)) { models.remove(route); return }
        models[route] = LaModels.Loading
        api.read("local-agent-models", R.models(route)) { r ->
            if (!models.containsKey(route)) return@read
            models[route] = when (r) {
                is DsbotApi.Result.Ok -> LaModels.Ok(LocalAgents.models(r.value))
                is DsbotApi.Result.BotError -> LaModels.Failed(BotManage.errorText(r.code, r.message))
                is DsbotApi.Result.Failure -> LaModels.Failed(BotManage.errorText(null, null))
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Text(tr("本地 Agent", "Local agents"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        Hint2(tr("运行在电脑上的 Agent CLI；Bot 可在详情的模型选择中选用它们。", "Agent CLIs running on the computer. Bots can use them from the model picker in their details."))
        when (val l = load) {
            LaLoad.Loading -> Hint2(tr("读取中…", "Loading…"))
            is LaLoad.Failed -> {
                if (l.unsupported) Text(tr("此远端不支持本地 Agent。", "This server does not offer local agents."), modifier = Modifier.padding(16.dp))
                else {
                    ErrorLine(l.text) {}
                    TextButton(onClick = { load = LaLoad.Loading; round++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("重试", "Try again")) }
                }
            }
            is LaLoad.Ok -> {
                val view = l.view
                if (!view.available) {
                    Text(tr("这个 DSH 无法注册本地 Agent。", "This DSH cannot register local agent routes."), modifier = Modifier.padding(16.dp))
                    return@Column
                }
                ErrorLine(error) { error = null }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(tr("检测到的 Agent", "Detected agents"))
                    TextButton(onClick = ::refresh, enabled = !refreshing) { Text(if (refreshing) tr("正在检测…", "Detecting…") else tr("刷新", "Refresh")) }
                }
                if (view.detected.isEmpty()) Hint2(tr("没有找到 Agent CLI", "No agent CLIs found"))
                for (d in view.detected) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.name, style = MaterialTheme.typography.bodyLarge)
                            LocalAgents.shortVersion(d.version).takeIf { it.isNotEmpty() }?.let { Text("  $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            LocalAgents.signInText(d)?.let {
                                Text(
                                    "  · $it", style = MaterialTheme.typography.bodySmall,
                                    color = if (d.signedIn == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        d.path?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (LocalAgents.canSignIn(d)) {
                            val cmd = d.signIn!!
                            Text(tr("在电脑终端运行以登录：", "Run this in a terminal on the computer to sign in: ") + cmd, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { clipboard.setText(AnnotatedString(cmd)); copied = d.route }) {
                                Text(if (copied == d.route) tr("已复制登录命令", "Sign-in command copied") else tr("复制登录命令", "Copy sign-in command"))
                            }
                        }
                        ModelsRow(models[d.route]) { toggleModels(d.route) }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(tr("自定义 Agent", "Custom agents"))
                    if (editing != "new") TextButton(onClick = { editing = "new" }) { Text(tr("＋ 添加", "+ Add agent")) }
                }
                if (view.custom.isEmpty() && editing != "new") Hint2(tr("按命令添加兼容 ACP 的 Agent。", "ACP-compatible agents, added by command."))
                for (c in view.custom) {
                    if (editing == c.id) {
                        AgentForm(c, api, "la:update:${c.id}", onClose = { editing = null }, onSaved = { editing = null; round++ }) { n, cmd, a -> R.update(c.id, n, cmd, a) to "local-agent-update" }
                        continue
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (!c.available) Text(tr("不可用", "Unavailable"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text(LocalAgents.commandLine(c), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editing = c.id }) { Text(tr("编辑", "Edit")) }
                            TextButton(onClick = { removing = c }, enabled = "la:remove:${c.id}" !in api.inFlight) { Text(tr("删除", "Remove"), color = MaterialTheme.colorScheme.error) }
                        }
                        c.route?.let { route -> ModelsRow(models[route]) { toggleModels(route) } }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
                if (editing == "new") {
                    AgentForm(null, api, "la:add", onClose = { editing = null }, onSaved = { editing = null; round++ }) { n, cmd, a -> R.add(n, cmd, a) to "local-agent-add" }
                }
            }
        }
    }
    removing?.let { c ->
        ConfirmDialog(
            title = tr("删除「${c.name}」？", "Remove \"${c.name}\"?"),
            text = tr("使用它的 Bot 需要另选模型。", "Bots that use it will need another model."),
            confirm = tr("删除", "Remove"),
            onConfirm = {
                error = null
                api.action("la:remove:${c.id}", "local-agent-remove", R.remove(c.id), { round++ }) { error = it }
            },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun Hint2(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

/** 「可用模型」展开行：首次展开会让电脑启动该 CLI 询问。 */
@Composable
private fun ModelsRow(state: LaModels?, onToggle: () -> Unit) {
    TextButton(onClick = onToggle) { Text(if (state == null) tr("查看可用模型", "Show models") else tr("收起模型", "Hide models")) }
    when (state) {
        null -> Unit
        LaModels.Loading -> Text(tr("正在询问…（首次会在电脑上启动它）", "Asking… (starts it on the computer the first time)"), style = MaterialTheme.typography.bodySmall)
        is LaModels.Failed -> Text(state.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        is LaModels.Ok -> Text(
            if (state.models.isEmpty()) tr("没有可用模型", "No models") else state.models.joinToString("、") { it.name },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 新增或修改自定义 Agent（网页端 AgentForm）：名称、命令、参数；本地校验后发送，成功后 [onSaved]。 */
@Composable
private fun AgentForm(
    initial: LocalAgents.Custom?,
    api: ManageApi,
    key: String,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    body: (String, String, String) -> Pair<String, String>,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var command by remember { mutableStateOf(initial?.command.orEmpty()) }
    var args by remember { mutableStateOf(initial?.args.orEmpty().joinToString(" ")) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = key in api.inFlight
    val problem = LocalAgents.formProblem(name, command, args)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(name, { name = it.take(LocalAgents.NAME_MAX) }, label = { Text(tr("名称", "Name")) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp))
        OutlinedTextField(command, { command = it }, label = { Text(tr("命令", "Command")) }, placeholder = { Text("my-agent") }, singleLine = true, enabled = !busy,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp))
        OutlinedTextField(args, { args = it }, label = { Text(tr("参数", "Arguments")) }, placeholder = { Text("acp") }, singleLine = true, enabled = !busy,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp))
        if (problem != null && (name.isNotEmpty() || command.isNotEmpty())) Hint2(problem)
        ErrorLine(error) { error = null }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
            TextButton(onClick = onClose, enabled = !busy) { Text(tr("取消", "Cancel")) }
            OutlinedButton(onClick = {
                error = null
                val (payload, endpoint) = body(name, command, args)
                api.action(key, endpoint, payload, { onSaved() }) { error = it }
            }, enabled = problem == null && !busy) { Text(if (busy) tr("保存中…", "Saving…") else tr("保存", "Save")) }
        }
    }
}
