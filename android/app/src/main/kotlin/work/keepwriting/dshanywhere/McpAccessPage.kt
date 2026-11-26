package work.keepwriting.dshanywhere

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import work.keepwriting.core.Json
import work.keepwriting.core.McpAccess

/*
 * MCP 访问（dsbot-mcp-access）：
 * 1) 连接：MCP 访问开关与端口（mcp-set），状态说明与电脑本机访问地址；
 * 2) 新增客户端（mcp-client-add，只发名称，范围用服务端缺省）：成功后一次性显示令牌——只放在本页 remember 内存状态
 *    （不用 rememberSaveable，离开页面即丢弃），经 SensitiveClipboard 复制并清理，显示期间 FLAG_SECURE；
 * 3) 客户端列表：最近使用或可访问对象、改名与范围勾选（mcp-client-update）、二次确认删除（mcp-client-remove）。
 * 四个写端点都返回新视图，成功后直接采用（设计决策 adopt-returned-view）；写操作单次发出、在途禁用、不自动重试。
 * 令牌明文只出现在令牌卡，不进日志、诊断上报与任何持久存储。
 */

private const val KEY_SET = "mcp:set"
private const val KEY_ADD = "mcp:add"
private fun updateKey(id: String) = "mcp:update:$id"

/** 任一 MCP 写操作在途（与网页端单一 busy 一致：在途期间整页写控件禁用，避免后到的旧视图覆盖另一次修改）。 */
private fun mcpBusy(api: ManageApi): Boolean = api.inFlight.any { it.startsWith("mcp:") }
private fun removeKey(id: String) = "mcp:remove:$id"

private fun statusText(status: McpAccess.Status): String = when (status) {
    is McpAccess.Status.Error -> status.message
    McpAccess.Status.Off -> tr("已关闭，其他 Agent 无法访问 DS Bot。", "Off. Other agents cannot reach DS Bot.")
    is McpAccess.Status.On -> tr("已开启，运行 DS Bot 的电脑上持有令牌的 Agent 可经电脑本机地址 ${status.url} 访问。", "On. Agents with a token on the computer that runs DS Bot can reach it at the computer's local address ${status.url}")
    McpAccess.Status.Connecting -> tr("连接中…", "Connecting…")
}

private fun timeText(ms: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(ms))

@Composable
private fun Note(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

@Composable
private fun InputField(value: String, onChange: (String) -> Unit, label: String, placeholder: String?, keyboardType: KeyboardType, issue: String?, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        isError = issue != null,
        supportingText = issue?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** 一次性令牌卡：显示期间禁止截屏；复制经敏感剪贴板（离开 App 后清理）；附令牌省略的命令示例。 */
@Composable
private fun TokenCard(name: String, token: McpAccess.Token, url: String, onDismiss: () -> Unit) {
    SecureWhileShown()
    val context = LocalContext.current
    var copied by remember(token) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                tr("现在复制 $name 的令牌，它只显示这一次。", "Copy the token for $name now. It is shown once."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                token.value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text(tr("完成", "Done")) }
                TextButton(onClick = {
                    SensitiveClipboard.copy(context, token.value)
                    copied = true
                }) { Text(if (copied) tr("已复制", "Copied") else tr("复制", "Copy")) }
            }
            Text(
                tr("离开 App 后剪贴板中的令牌会被清除；离开本页后令牌不再显示。", "The token is cleared from the clipboard after you leave the app, and is not shown again after you leave this page."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                tr("以命令启动的客户端使用：", "Clients that start a command use:"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 6.dp),
            )
            SelectionContainer {
                Text(
                    "DSBOT_MCP_TOKEN=… npx -p ds-bot ds-bot-mcp --url $url",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ScopeBox(label: String, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(start = 32.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

/** 一个外部客户端：名称与最近使用/可访问对象；展开后可改名、勾选范围、删除（二次确认）。 */
@Composable
private fun ClientItem(
    client: McpAccess.Client,
    scope: McpAccess.Scope,
    api: ManageApi,
    onUpdate: (key: String, payload: String) -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val busy = mcpBusy(api)
    val reach = McpAccess.reachable(client, scope)
    val hint = client.lastUsedAt?.let { tr("最近使用 ${timeText(it)}", "Last used ${timeText(it)}") }
        ?: tr("可访问：${reach.joinToString("、").ifEmpty { "尚无" }}", "Can reach: ${reach.joinToString(", ").ifEmpty { "nothing yet" }}")
    ValueRow(client.name, if (expanded) "⌃" else "⌄", hint = hint) { expanded = !expanded }
    if (!expanded) return
    if (client.lastUsedAt != null) Note(tr("可访问：${reach.joinToString("、").ifEmpty { "尚无" }}", "Can reach: ${reach.joinToString(", ").ifEmpty { "nothing yet" }}"))
    EditableField(
        label = tr("名称", "Name"),
        saved = client.name,
        busy = busy,
        maxLength = McpAccess.NAME_MAX,
        problem = { if (McpAccess.normalizeName(it) == null) tr("名称不能为空。", "Give the client a name.") else null },
    ) { draft ->
        McpAccess.normalizeName(draft)?.let { onUpdate(updateKey(client.id), McpAccess.Requests.rename(client.id, it)) }
    }
    Note(tr("可访问的 Bot", "Bots it can reach"))
    if (scope.bots.isEmpty()) Note(tr("团队里没有可选的 Bot（私有 Bot 不能开放）。", "No Bots to choose (private Bots cannot be shared)."))
    for (bot in scope.bots) key("b:" + bot.id) {
        ScopeBox(bot.name, bot.id in client.bots, !busy) {
            onUpdate(updateKey(client.id), McpAccess.Requests.setBots(client.id, McpAccess.toggle(client.bots, bot.id)))
        }
    }
    Note(tr("可访问的群", "Groups it can reach"))
    if (scope.rooms.isEmpty()) Note(tr("团队里还没有群。", "No groups yet."))
    for (room in scope.rooms) key("r:" + room.id) {
        ScopeBox(room.name, room.id in client.rooms, !busy) {
            onUpdate(updateKey(client.id), McpAccess.Requests.setRooms(client.id, McpAccess.toggle(client.rooms, room.id)))
        }
    }
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        TextButton(enabled = !busy, onClick = { confirming = true }) { Text(tr("删除客户端", "Remove client"), color = MaterialTheme.colorScheme.error) }
    }
    if (confirming) {
        ConfirmDialog(
            title = tr("删除 ${client.name}？", "Remove ${client.name}?"),
            text = tr(
                "它的令牌会立即失效，使用该令牌的 Agent 将无法再访问 DS Bot。令牌无法找回，之后只能新建客户端并重新配置。",
                "Its token stops working at once, and agents using it can no longer reach DS Bot. The token cannot be recovered; you would need to add a new client and set it up again.",
            ),
            confirm = tr("删除", "Remove"),
            onConfirm = onRemove,
            onDismiss = { confirming = false },
        )
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
fun McpAccessPage(state: Json.Value.Object?, api: ManageApi) {
    // 写端点返回的视图在下一份 state 到来前直接采用（连接层成功后会补取 state；补取失败或晚到时页面不回退）。
    var returned by remember(state) { mutableStateOf<McpAccess.View?>(null) }
    val fromState = remember(state) { McpAccess.view(state) }
    val view = returned ?: fromState
    val scope = remember(state) { McpAccess.scope(state) }
    var error by remember { mutableStateOf<String?>(null) }
    // 一次性令牌：只在本页内存中（离开页面即丢弃），不用 rememberSaveable。
    // 第一项为客户端名称，第二项为新客户端 id（上游追加到列表末尾；该客户端被删除后令牌卡随之消失）。
    var created by remember { mutableStateOf<Triple<String, String?, McpAccess.Token>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }

    fun write(key: String, endpoint: String, payload: String, onView: (Json.Value) -> Unit = { v -> McpAccess.decodeValue(v)?.let { returned = it } }) {
        error = null
        api.action(key, endpoint, payload, onView) { error = it }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            tr("MCP 访问", "MCP access"),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
        Note(
            tr(
                "让运行 DS Bot 的电脑上的其他 Agent（Claude Desktop、Cursor、Codex 或脚本）经 MCP 与 Bot 和群对话。每个客户端有自己的令牌，只能访问给它开放的 Bot 与群。",
                "Let other agents on the computer that runs DS Bot (Claude Desktop, Cursor, Codex, or a script) talk to Bots and groups over MCP. Each client has its own token and reaches only the Bots and groups it was given.",
            ),
        )
        if (view == null) {
            Text(tr("此远端不支持 MCP 访问。", "This server does not offer MCP access."), modifier = Modifier.padding(16.dp))
            return@Column
        }
        ErrorLine(error) { error = null }

        SectionTitle(tr("连接", "Connection"))
        val status = McpAccess.status(view)
        val busy = mcpBusy(api)
        SwitchRow(tr("MCP 访问", "MCP access"), null, view.on, !busy) { on ->
            write(KEY_SET, McpAccess.SET, McpAccess.Requests.setOn(on))
        }
        Note(statusText(status), if (status is McpAccess.Status.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)

        var port by remember(view.port) { mutableStateOf(view.port.toString()) }
        val portIssue = if (port != view.port.toString() && McpAccess.parsePort(port) == null) {
            tr("端口须为 ${McpAccess.PORT_MIN}–${McpAccess.PORT_MAX} 的整数。", "The port must be a number from ${McpAccess.PORT_MIN} to ${McpAccess.PORT_MAX}.")
        } else {
            null
        }
        InputField(port, { port = it.take(5) }, tr("端口", "Port"), null, KeyboardType.Number, portIssue, !busy)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
            Text(
                tr("只有运行 DS Bot 的电脑上的程序能连接（手机不能直接连接）。", "Only programs on the computer that runs DS Bot can connect (not this phone)."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            )
            TextButton(enabled = !busy && McpAccess.canSavePort(port, view), onClick = {
                McpAccess.parsePort(port)?.let { write(KEY_SET, McpAccess.SET, McpAccess.Requests.setPort(it)) }
            }) { Text(tr("保存", "Save")) }
        }

        SectionTitle(tr("新增客户端", "Add a client"))
        val normalized = McpAccess.normalizeName(name)
        InputField(name, { name = it }, tr("名称", "Name"), "Claude Desktop", KeyboardType.Text, null, !busy)
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(enabled = !busy && normalized != null, onClick = {
                val clientName = normalized ?: return@TextButton
                created = null
                notice = null
                write(KEY_ADD, McpAccess.CLIENT_ADD, McpAccess.Requests.add(clientName)) { v ->
                    val result = McpAccess.decodeCreated(v)
                    result?.view?.let { returned = it }
                    val token = result?.token
                    if (token != null) {
                        created = Triple(clientName, result.view.clients.lastOrNull()?.id, token)
                    } else {
                        notice = tr(
                            "客户端已新建，但没有收到它的令牌。请删除该客户端后重新新建。",
                            "The client was added, but its token did not arrive. Remove the client and add it again.",
                        )
                    }
                    name = ""
                }
            }) { Text(tr("创建令牌", "Create token")) }
        }
        InfoLine(notice, error = true) { notice = null }
        // 刚新建的客户端已被删除时令牌随之作废，不再显示
        created?.takeIf { (_, clientId, _) -> clientId == null || view.clients.any { it.id == clientId } }?.let { (clientName, _, token) ->
            TokenCard(clientName, token, view.url) { created = null }
        }

        SectionTitle(tr("客户端", "Clients"))
        if (view.clients.isEmpty()) Note(tr("还没有客户端。", "No clients yet."))
        for (client in view.clients) key(client.id) {
            ClientItem(
                client = client,
                scope = scope,
                api = api,
                onUpdate = { k, payload -> write(k, McpAccess.CLIENT_UPDATE, payload) },
                onRemove = { write(removeKey(client.id), McpAccess.CLIENT_REMOVE, McpAccess.Requests.remove(client.id)) },
            )
        }
    }
}
