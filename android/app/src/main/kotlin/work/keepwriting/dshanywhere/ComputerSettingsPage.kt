package work.keepwriting.dshanywhere

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import work.keepwriting.core.ComputerSettings
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.Json

/*
 * 电脑操控设置（dsbot-computer-settings）：
 * 1) 「让 Bot 使用电脑」总开关（computer-set-enabled，返回视图直接采用）；
 * 2) 开启后的操控组件状态：加载中/就绪/无法加载/未安装/被占用，可重试（computer-provider-retry），未安装时显示服务端给出的安装命令文本；
 * 3) macOS 宿主且组件就绪时的辅助功能与屏幕录制权限（computer-system-status 只读，computer-permission 在电脑上弹出授权）；
 * 4) 各 Bot 的操控级别（computer-set-level）与已总是允许的应用授权（computer-revoke-app，二次确认）。
 * 宿主不支持（最小视图、unsupported、开启后 missing）时显示「此宿主不支持电脑操控」而非报错，开启状态下保留关闭开关。
 * 写操作经 ManageApi 单次发出、在途禁用、不自动重试；set-level 与 revoke-app 返回 true，以连接层补取的 state 刷新。
 */

private const val KEY_ENABLED = "computer:enabled"
private const val KEY_RETRY = "computer:retry"
private fun levelKey(botId: String) = "computer:level:$botId"
private fun revokeKey(botId: String, appKey: String) = "computer:revoke:$botId:$appKey"
private fun permKey(perm: String) = "computer:perm:$perm"

internal fun computerLevelLabel(level: ComputerSettings.Level): String = when (level) {
    ComputerSettings.Level.OFF -> tr("关闭", "Off")
    ComputerSettings.Level.VIEW -> tr("仅查看", "View only")
    ComputerSettings.Level.BACKGROUND -> tr("后台", "Background")
    ComputerSettings.Level.FOREGROUND -> tr("前台", "Foreground")
}

private fun levelHint(level: ComputerSettings.Level): String = when (level) {
    ComputerSettings.Level.OFF -> tr("不能使用电脑", "Cannot use the computer")
    ComputerSettings.Level.VIEW -> tr("只看：列出应用与窗口、读取状态、截图", "Look only: list apps and windows, read state, take screenshots")
    ComputerSettings.Level.BACKGROUND -> tr("可在后台操作应用，不切到前台", "Operates apps in the background without bringing them forward")
    ComputerSettings.Level.FOREGROUND -> tr("可操作应用并切到前台", "Operates apps and may bring them to the front")
}

private fun providerLabel(state: ComputerSettings.ProviderState): String = when (state) {
    ComputerSettings.ProviderState.OFF -> tr("已关闭", "Off")
    ComputerSettings.ProviderState.LOADING -> tr("正在加载操控组件…", "Loading the provider…")
    ComputerSettings.ProviderState.READY -> tr("就绪", "Ready")
    ComputerSettings.ProviderState.ERROR -> tr("无法加载操控组件", "Could not load the provider")
    ComputerSettings.ProviderState.MISSING -> tr("此 DSH 未安装操控组件", "The provider package is not installed")
    ComputerSettings.ProviderState.OCCUPIED -> tr("另一个电脑操控组件正在运行", "Another computer-use provider is running")
    ComputerSettings.ProviderState.UNSUPPORTED -> tr("此平台不支持电脑操控", "Computer use is not available on this platform")
}

private fun permLabel(key: String): String = when (key) {
    ComputerSettings.PERM_ACCESSIBILITY -> tr("辅助功能", "Accessibility")
    ComputerSettings.PERM_SCREEN_RECORDING -> tr("屏幕录制", "Screen Recording")
    else -> key
}

@Composable
private fun Note(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

/** 单选级别对话框（整行一个单选目标）。 */
@Composable
private fun LevelDialog(botName: String, current: ComputerSettings.Level, onPick: (ComputerSettings.Level) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("$botName 的电脑操控级别", "Computer use level for $botName")) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                for (level in ComputerSettings.Level.entries) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = level == current,
                            role = Role.RadioButton,
                            onClick = { onDismiss(); if (level != current) onPick(level) },
                        ).padding(vertical = 8.dp),
                    ) {
                        RadioButton(selected = level == current, onClick = null)
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(computerLevelLabel(level), style = MaterialTheme.typography.bodyLarge)
                            Text(levelHint(level), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}

/**
 * 一个 Bot 的级别行与应用授权行（电脑操控页与 Bot 详情页共用）。
 * [levelEnabled] 为 false 时级别不可修改（总开关关闭）；[hint] 为组件未就绪等说明。
 */
@Composable
internal fun ComputerBotRows(
    row: ComputerSettings.BotRow,
    api: ManageApi,
    levelEnabled: Boolean,
    label: String,
    hint: String?,
    onError: (String?) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<ComputerSettings.AppGrant?>(null) }
    val levelBusy = levelKey(row.id) in api.inFlight
    ValueRow(label, computerLevelLabel(row.level), hint = hint, enabled = levelEnabled && !levelBusy) { picking = true }
    for (app in row.apps) {
        val busy = revokeKey(row.id, app.key) in api.inFlight
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.bodyMedium)
                Text(tr("总是允许", "Always allowed"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(enabled = !busy, onClick = { revoking = app }) { Text(tr("撤销", "Revoke")) }
        }
    }
    if (picking) {
        LevelDialog(row.name, row.level, onPick = { level ->
            onError(null)
            api.action(levelKey(row.id), ComputerSettings.SET_LEVEL, ComputerSettings.Requests.setLevel(row.id, level), {}) { onError(it) }
        }, onDismiss = { picking = false })
    }
    revoking?.let { app ->
        ConfirmDialog(
            title = tr("撤销 ${app.name} 的授权？", "Revoke access to ${app.name}?"),
            text = tr(
                "${row.name} 下次使用 ${app.name} 时需要重新请求你的允许。",
                "${row.name} will have to ask again the next time it wants to use ${app.name}.",
            ),
            confirm = tr("撤销", "Revoke"),
            onConfirm = {
                onError(null)
                api.action(revokeKey(row.id, app.key), ComputerSettings.REVOKE_APP, ComputerSettings.Requests.revokeApp(row.id, app.key), {}) { onError(it) }
            },
            onDismiss = { revoking = null },
        )
    }
}

/** 组件未就绪（含加载中）时给级别行的说明（client.js：Bots get no computer tools until the provider is ready）。 */
internal fun computerProviderHint(view: ComputerSettings.View): String? =
    if (view.enabled && !view.provider.ready) tr("操控组件就绪前，Bot 不会获得电脑工具。", "Bots get no computer tools until the provider is ready.") else null

@Composable
fun ComputerSettingsPage(state: Json.Value.Object?, api: ManageApi, visible: Boolean) {
    // set-enabled / provider-retry 返回的视图在下一份 state 到来前直接采用。连接层 manageAction 成功后先补取 state 再回调 onOk，
    // 多数情况下新 state 已带同一视图、这里的覆盖随 remember(state) 换槽而不再生效；保留它是为补取失败或晚到时页面不回退。
    var returned by remember(state) { mutableStateOf<ComputerSettings.View?>(null) }
    val fromState = remember(state) { ComputerSettings.view(state) }
    val view = returned ?: fromState
    val rows = remember(state, view) { ComputerSettings.botRows(state, view) }
    val readOnly = remember(state) { ComputerSettings.readOnlyReason(state) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    fun applyView(endpoint: String, key: String, payload: String) {
        error = null
        note = null
        api.action(key, endpoint, payload, { v -> ComputerSettings.decodeValue(v)?.let { returned = it } }) { error = it }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            tr("电脑操控", "Computer use"),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
        Note(tr("让 Bot 在运行 DS Bot 的电脑上查看并操作应用。", "Let Bots look at and operate apps on the computer that runs DS Bot."))
        if (view == null) {
            Text(tr("此远端不支持电脑操控。", "This server does not offer computer use."), modifier = Modifier.padding(16.dp))
            return@Column
        }
        if (readOnly != null) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    tr("只读宿主：$readOnly", "Read-only host: $readOnly"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        ErrorLine(error) { error = null }
        InfoLine(note, error = false) { note = null }

        if (view.hostUnsupported) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(tr("此宿主不支持电脑操控", "This host does not support computer use"), style = MaterialTheme.typography.titleSmall)
                    val reason = view.provider.message ?: if (view.minimal) null else providerLabel(view.provider.state)
                    if (reason != null) Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        tr("电脑操控需要在装有操控组件的桌面 DS Bot 宿主上使用。", "Computer use needs a desktop DS Bot host with the computer-use provider installed."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            view.provider.installCommand?.let { cmd ->
                Note(tr("在电脑上运行以下命令后重启 DSH：", "Run this on the computer, then restart DSH:"))
                SelectionContainer {
                    Text(cmd, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                }
            }
            // 最小视图无开关可关；开启后不支持时保留关闭开关与重试
            if (view.enabled) {
                SwitchRow(tr("让 Bot 使用电脑", "Let Bots use the computer"), null, true, KEY_ENABLED !in api.inFlight) { on ->
                    applyView(ComputerSettings.SET_ENABLED, KEY_ENABLED, ComputerSettings.Requests.setEnabled(on))
                }
                if (view.provider.retryable) {
                    TextButton(enabled = KEY_RETRY !in api.inFlight, onClick = {
                        applyView(ComputerSettings.PROVIDER_RETRY, KEY_RETRY, ComputerSettings.Requests.providerRetry())
                    }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("重试", "Retry")) }
                }
            }
            // 最小视图没有 Bot 级别与授权数据；其余情况照常列出 Bot（已有的总是允许授权仍可查看与撤销，与网页端一致）
            if (view.minimal) return@Column
        } else {
        SwitchRow(
            tr("让 Bot 使用电脑", "Let Bots use the computer"),
            if (view.enabled) null else tr("开启后 DS Bot 会在电脑上加载操控组件。", "When on, DS Bot loads the computer-use provider on the computer."),
            view.enabled,
            KEY_ENABLED !in api.inFlight,
        ) { on -> applyView(ComputerSettings.SET_ENABLED, KEY_ENABLED, ComputerSettings.Requests.setEnabled(on)) }

        if (view.enabled) {
            SectionTitle(tr("操控组件", "Computer provider"))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(providerLabel(view.provider.state), style = MaterialTheme.typography.bodyLarge)
                    val detail = view.provider.message ?: view.provider.name
                    if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (view.provider.retryable) {
                    TextButton(enabled = KEY_RETRY !in api.inFlight, onClick = {
                        applyView(ComputerSettings.PROVIDER_RETRY, KEY_RETRY, ComputerSettings.Requests.providerRetry())
                    }) { Text(tr("重试", "Retry")) }
                }
            }
            if (view.showPermissions) PermissionRows(api, visible, onError = { error = it }, onNote = { note = it })
        }
        }

        SectionTitle(tr("Bot", "Bots"))
        computerProviderHint(view)?.let { Note(it) }
        if (rows.isEmpty()) Note(tr("团队里还没有 Bot。", "No Bots on the team yet."))
        for (row in rows) key(row.id) {
            ComputerBotRows(
                row = row,
                api = api,
                levelEnabled = view.enabled,
                label = row.name,
                hint = null,
                onError = { error = it },
            )
        }
        if (!view.enabled && rows.isNotEmpty()) Note(tr("打开总开关后才能修改各 Bot 的级别。", "Turn the switch on to change each Bot's level."))
    }
}

/** macOS 权限区：进入时与每次刷新读 computer-system-status；未允许项可在电脑上请求授权。 */
@Composable
private fun PermissionRows(api: ManageApi, visible: Boolean, onError: (String?) -> Unit, onNote: (String?) -> Unit) {
    var perms by remember { mutableStateOf<List<ComputerSettings.Permission>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible, generation) {
        if (!visible) return@LaunchedEffect
        val mine = generation
        api.read(ComputerSettings.SYSTEM_STATUS, ComputerSettings.Requests.systemStatus()) { r ->
            if (mine != generation) return@read
            loaded = true
            perms = (r as? DsbotApi.Result.Ok)?.let { ComputerSettings.permissions(it.value) }
            // 读取失败给出原因（on-demand-page-degrade）；ok 且 value 为 null 才是宿主暂无系统权限服务
            failure = when (r) {
                is DsbotApi.Result.Ok -> null
                is DsbotApi.Result.BotError -> work.keepwriting.core.BotManage.errorText(r.code, r.message)
                is DsbotApi.Result.Failure -> work.keepwriting.core.BotManage.errorText(null, null)
            }
        }
    }
    SectionTitle(tr("系统权限", "System permissions"))
    val list = perms
    when {
        !loaded -> Note(tr("正在读取…", "Loading…"))
        failure != null -> Note(tr("读取系统权限失败：$failure", "Could not read system permissions: $failure"), MaterialTheme.colorScheme.error)
        list == null -> Note(tr("此电脑暂时无法读取系统权限。", "System permissions cannot be read on this computer right now."))
        else -> for (p in list) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(permLabel(p.key), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (p.granted) tr("已允许", "Allowed") else tr("未允许", "Not allowed"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (p.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                if (!p.granted) {
                    TextButton(enabled = permKey(p.key) !in api.inFlight, onClick = {
                        onError(null)
                        api.action(permKey(p.key), ComputerSettings.PERMISSION, ComputerSettings.Requests.permission(p.key), {
                            onNote(tr("已在电脑上弹出「${permLabel(p.key)}」授权，请在电脑上完成后点刷新。", "A ${permLabel(p.key)} prompt opened on the computer. Finish it there, then tap Refresh."))
                        }) { onError(it) }
                    }) { Text(tr("请求授权", "Grant")) }
                }
            }
        }
    }
    TextButton(onClick = { generation++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("刷新", "Refresh")) }
}

/** Bot 详情页的「电脑操控」分节：开启时级别与授权，关闭时说明需先在电脑操控设置中开启；远端无该字段时不显示。 */
@Composable
internal fun BotComputerSection(state: Json.Value.Object?, botId: String, api: ManageApi, onOpenSettings: () -> Unit, onError: (String?) -> Unit) {
    val view = remember(state) { ComputerSettings.view(state) } ?: return
    val row = remember(state, botId) { ComputerSettings.botRow(state, botId) } ?: return
    SectionTitle(tr("电脑操控", "Computer use"))
    if (!view.enabled) {
        ValueRow(
            tr("电脑操控", "Computer use"),
            "›",
            hint = if (view.hostUnsupported) tr("此宿主不支持电脑操控", "This host does not support computer use") else tr("已关闭：需先在电脑操控设置中开启", "Off: turn it on in Computer use settings"),
            onClick = onOpenSettings,
        )
        return
    }
    // 开启时（含组件未装入的不支持宿主）照常显示级别与授权，与网页端 BotComputerCard 一致
    ComputerBotRows(
        row = row,
        api = api,
        levelEnabled = true,
        label = tr("操控级别", "Level"),
        hint = if (view.hostUnsupported) tr("此宿主不支持电脑操控", "This host does not support computer use") else computerProviderHint(view),
        onError = onError,
    )
}
