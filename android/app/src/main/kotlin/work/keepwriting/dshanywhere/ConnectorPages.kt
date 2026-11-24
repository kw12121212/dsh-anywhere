package work.keepwriting.dshanywhere

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import work.keepwriting.core.ConnectorSettings
import work.keepwriting.core.ConnectorSettings.Action
import work.keepwriting.core.ConnectorSettings.ConnectKind
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.Json
import work.keepwriting.core.MailSettings

/*
 * 连接器与邮箱（dsbot-connectors-mail）：连接器列表、详情（按鉴权类型的连接与登录面板）、添加其他 MCP 服务器与邮箱页。
 * 数据来自 state.connectors（live connectorsView），写操作经 ManageApi 发出、不自动重试，成功后由 state 刷新显示。
 * key、token、Client Secret 与邮箱授权码只进请求体：输入框不回显、表单显示期间禁止截屏，不写日志与诊断上报。
 * OAuth 回调落在 DS-Bot 所在电脑的 127.0.0.1：手机上把浏览器停留的地址粘贴回来完成，或复制授权链接到电脑完成。
 */

/** GitHub 创建细粒度 token 的地址（网页端 TOKEN_URL）。 */
private const val GITHUB_TOKEN_URL = "https://github.com/settings/personal-access-tokens/new"

/**
 * App 复制的设备码、授权链接与回调地址：带专用标签并标记敏感；回到 App 时若剪贴板仍是本 App 复制的即清除，
 * 另在约 2 分钟后兜底清除（后台读不到剪贴板时跳过，等回到前台再清）。
 */
object SensitiveClipboard {
    private const val LABEL = "dsh-anywhere-connector"
    private const val CLEAR_AFTER_MS = 120_000L
    private val handler = Handler(Looper.getMainLooper())

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText(LABEL, text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        runCatching { cm.setPrimaryClip(clip) }
        val app = context.applicationContext
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ clearIfOurs(app) }, CLEAR_AFTER_MS)
    }

    /** 剪贴板仍是本 App 复制的内容时清除（只看标签，不读内容）。 */
    fun clearIfOurs(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching {
            if (cm.primaryClipDescription?.label?.toString() != LABEL) return
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}

/** 在系统浏览器中打开地址。 */
internal fun openExternal(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** 显示期间给窗口加 FLAG_SECURE（禁止截屏与最近任务预览），离开即恢复。 */
@Composable
internal fun SecureWhileShown() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** 不回显的密钥输入框（不自动更正、密码键盘）。 */
@Composable
internal fun SecretField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun PlainField(value: String, onChange: (String) -> Unit, label: String, placeholder: String? = null, enabled: Boolean = true, keyboardType: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

private fun statusColorOf(state: String) = when (state) {
    ConnectorSettings.ERROR, ConnectorSettings.NEEDS_SIGN_IN, ConnectorSettings.NEEDS_CLIENT -> 2
    ConnectorSettings.READY -> 1
    else -> 0
}

@Composable
private fun StatusLine(text: String, tone: Int) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = when (tone) {
            2 -> MaterialTheme.colorScheme.error
            1 -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

// ---- 列表 ----

/** 连接器列表：搜索，「已连接」在前，其余按类别分组；点连接器进详情，点邮箱进邮箱页。 */
@Composable
fun ConnectorsPage(state: Json.Value.Object?, onOpen: (String) -> Unit, onOpenMailbox: () -> Unit, onAdd: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val view = remember(state) { ConnectorSettings.view(state) }
    val sections = remember(view, query, work.keepwriting.core.I18n.lang) { view?.let { ConnectorSettings.sections(it, query) } ?: emptyList() }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Text(tr("连接器", "Connectors"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        Hint(tr("团队的 Bot 都能使用已连接的外部服务；可在 Bot 详情中为单个 Bot 关闭。", "Every Bot on the team can use connected services. Turn one off for a Bot in its details."))
        if (view == null) {
            Text(tr("此远端不支持连接器。", "This server does not offer connectors."), modifier = Modifier.padding(16.dp))
            return@Column
        }
        PlainField(query, { query = it.take(100) }, tr("搜索", "Search"))
        TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("＋ 添加其他 MCP 服务器", "+ Add another MCP server")) }
        if (sections.isEmpty()) {
            Text(tr("没有匹配的连接器", "Nothing matches"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        for (section in sections) {
            SectionTitle(section.title)
            for (entry in section.entries) {
                when (entry) {
                    is ConnectorSettings.Entry.Mailbox -> EntryRow(
                        name = tr("邮箱", "Mailbox"),
                        tag = null,
                        status = MailSettings.summary(entry.mail),
                        tone = if (entry.mail.accounts.any { it.error != null }) 2 else if (entry.mail.accounts.isNotEmpty()) 1 else 0,
                        onClick = onOpenMailbox,
                    )
                    is ConnectorSettings.Entry.Connector -> EntryRow(
                        name = entry.row.name,
                        tag = if (entry.row.custom) tr("自加", "Added") else null,
                        status = ConnectorSettings.statusText(entry.row),
                        tone = statusColorOf(entry.row.state),
                        onClick = { onOpen(entry.row.id) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun EntryRow(name: String, tag: String?, status: String, tone: Int, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
            if (tag != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(start = 8.dp)) {
                    Text(tag, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        StatusLine(status, tone)
    }
}

// ---- 详情 ----

private enum class CredForm { KEY, TOKEN }

/** 连接器详情：状态、按鉴权类型与状态的动作、登录面板、客户端表单、key/token 表单与只读开关。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectorDetailPage(state: Json.Value.Object?, id: String, api: ManageApi, onBack: () -> Unit) {
    val context = LocalContext.current
    val row = remember(state, id) { ConnectorSettings.row(state, id) }
    var error by remember { mutableStateOf<String?>(null) }
    var form by remember(id) { mutableStateOf<CredForm?>(null) }
    var confirm by remember { mutableStateOf<Action?>(null) }
    if (row == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(tr("这个连接器已不存在。", "This connector no longer exists."), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onBack) { Text(tr("返回", "Back")) }
        }
        return
    }
    val R = ConnectorSettings.Requests
    val busy = api.inFlight.any { it.startsWith("connector:$id:") }
    fun act(op: String, endpoint: String, payload: String, onOk: (Json.Value) -> Unit = {}) {
        error = null
        api.action("connector:$id:$op", endpoint, payload, onOk) { error = it }
    }
    // 登录：开始后若应答里已有待完成的登录，直接打开浏览器（设备码先复制用户码）
    fun openPending(p: ConnectorSettings.Pending?) {
        when (p) {
            is ConnectorSettings.Pending.Browser -> openExternal(context, p.url)
            is ConnectorSettings.Pending.Device -> {
                SensitiveClipboard.copy(context, p.userCode)
                openExternal(context, p.verificationUri)
            }
            null -> Unit
        }
    }
    fun signIn() = act("connect", "connector-connect", R.connect(id)) { v ->
        openPending(ConnectorSettings.decodeView(v)?.rows?.firstOrNull { it.id == id }?.pending)
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f, fill = false))
            if (row.custom) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(start = 8.dp)) {
                    Text(tr("自加", "Added"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            StatusLine(ConnectorSettings.statusText(row), statusColorOf(row.state))
            if (row.state != ConnectorSettings.OFF && row.about.isNotBlank()) {
                Text(ConnectorSettings.aboutText(row.about), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ErrorLine(error) { error = null }

        if (form == null) {
            FlowRow(modifier = Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in ConnectorSettings.actions(row)) {
                    when (a) {
                        Action.CONNECT -> OutlinedButton(enabled = !busy, onClick = {
                            when (ConnectorSettings.connectKind(row)) {
                                ConnectKind.DIRECT -> act("connect", "connector-connect", R.connect(id))
                                ConnectKind.KEY -> form = CredForm.KEY
                                ConnectKind.TOKEN -> form = CredForm.TOKEN
                                ConnectKind.SIGN_IN -> signIn()
                                null -> Unit
                            }
                        }) { Text(if (busy) tr("连接中…", "Connecting…") else tr("连接", "Connect")) }
                        Action.USE_TOKEN -> TextButton(enabled = !busy, onClick = { form = CredForm.TOKEN }) { Text(tr("改用 token", "Use a token instead")) }
                        Action.SIGN_IN_AGAIN -> OutlinedButton(enabled = !busy, onClick = { signIn() }) { Text(tr("重新登录", "Sign in again")) }
                        Action.RETRY -> OutlinedButton(enabled = !busy, onClick = { act("retry", "connector-retry", R.retry(id)) }) { Text(tr("重试", "Try again")) }
                        Action.DISCONNECT -> TextButton(enabled = !busy, onClick = { confirm = Action.DISCONNECT }) { Text(tr("断开", "Disconnect"), color = MaterialTheme.colorScheme.error) }
                        Action.REMOVE -> TextButton(enabled = !busy, onClick = { confirm = Action.REMOVE }) { Text(tr("移除", "Remove"), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }

        when (val p = row.pending) {
            is ConnectorSettings.Pending.Browser -> BrowserPanel(p, busy, onOpen = { openExternal(context, p.url) }, onCopy = { SensitiveClipboard.copy(context, p.url) },
                onCancel = { act("cancel", "connector-cancel", R.cancel(id)) },
                onFinish = { input, done -> act("code", "connector-sign-in-code", R.signInCode(id, input)) { done() } })
            is ConnectorSettings.Pending.Device -> DevicePanel(p, busy,
                onCopy = { SensitiveClipboard.copy(context, p.userCode) },
                onOpen = { openExternal(context, p.verificationUri) },
                onUseToken = if (ConnectorSettings.deviceAllowsToken(row)) ({ act("cancel", "connector-cancel", R.cancel(id)) { form = CredForm.TOKEN } }) else null,
                onCancel = { act("cancel", "connector-cancel", R.cancel(id)) })
            null -> Unit
        }

        if (ConnectorSettings.showClientForm(row)) {
            ClientPanel(row, busy,
                onCopy = { uri -> SensitiveClipboard.copy(context, uri) },
                onOpenApp = { url -> openExternal(context, url) },
                onCancel = { act("cancel", "connector-cancel", R.cancel(id)) },
                onContinue = { cid, secret ->
                    act("client", "connector-client", R.client(id, cid, secret)) { v ->
                        openPending(ConnectorSettings.decodeView(v)?.rows?.firstOrNull { it.id == id }?.pending)
                    }
                })
        }

        form?.let { f ->
            CredentialForm(row, f, busy, onOpenLink = { openExternal(context, it) }, onCancel = { form = null; error = null }) { value ->
                val body = if (f == CredForm.KEY) R.connectKey(id, value) else R.connectToken(id, value)
                act("connect", "connector-connect", body) { form = null }
            }
        }

        if (ConnectorSettings.showReadOnly(row)) {
            SwitchRow(tr("只读", "Read-only"), tr("开启后 Bot 只能使用这个服务的读取类工具", "Bots can only use this service's read tools"), row.readOnly == true, !busy) {
                act("readonly", "connector-set", R.setReadOnly(id, it))
            }
        }
    }

    confirm?.let { a ->
        val remove = a == Action.REMOVE
        ConfirmDialog(
            title = if (remove) tr("移除「${row.name}」？", "Remove \"${row.name}\"?") else tr("断开「${row.name}」？", "Disconnect \"${row.name}\"?"),
            text = if (remove) tr("会删除这个自加的服务器及其登录凭据，所有 Bot 都不能再使用它。", "The added server and its sign-in are deleted. No Bot can use it anymore.")
            else tr("会删除保存的登录凭据，所有 Bot 都不能再使用它，需要时重新连接。", "Its saved sign-in is deleted and no Bot can use it until you connect again."),
            confirm = if (remove) tr("移除", "Remove") else tr("断开", "Disconnect"),
            onConfirm = {
                if (remove) act("remove", "connector-remove", R.remove(id)) else act("disconnect", "connector-disconnect", R.disconnect(id))
            },
            onDismiss = { confirm = null },
        )
    }
}

/** OAuth 浏览器登录：打开授权页、复制链接到电脑完成、粘贴落地地址完成、取消。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowserPanel(p: ConnectorSettings.Pending.Browser, busy: Boolean, onOpen: () -> Unit, onCopy: () -> Unit, onCancel: () -> Unit, onFinish: (String, () -> Unit) -> Unit) {
    var input by remember(p.url) { mutableStateOf("") }
    SectionTitle(tr("完成登录", "Finish signing in"))
    Hint(tr(
        "在浏览器中授权后，浏览器会打开一个无法访问的 127.0.0.1 页面：复制这个页面的完整地址，粘贴到下面完成登录。也可以复制授权链接，在运行 DS Bot 的电脑上打开，授权后会自动完成。",
        "After you authorize, the browser opens a 127.0.0.1 page that cannot load. Copy that page's full address and paste it below. Or copy the sign-in link and open it on the computer running DS Bot, where it finishes on its own.",
    ))
    FlowRow(modifier = Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onOpen) { Text(tr("在浏览器中打开", "Open in browser")) }
        TextButton(onClick = onCopy) { Text(tr("复制授权链接", "Copy sign-in link")) }
        TextButton(onClick = onCancel, enabled = !busy) { Text(tr("取消", "Cancel")) }
    }
    PlainField(input, { input = it }, tr("粘贴浏览器停留页面的地址", "Paste the address you land on"), placeholder = "http://127.0.0.1:…/callback?code=…", keyboardType = KeyboardType.Uri)
    OutlinedButton(onClick = { onFinish(input) { input = "" } }, enabled = input.isNotBlank() && !busy, modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(tr("完成登录", "Finish sign-in"))
    }
}

/** 设备码登录：显示用户码、复制、打开验证地址、改用 token、取消。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicePanel(p: ConnectorSettings.Pending.Device, busy: Boolean, onCopy: () -> Unit, onOpen: () -> Unit, onUseToken: (() -> Unit)?, onCancel: () -> Unit) {
    SectionTitle(tr("设备码登录", "Sign in with a code"))
    Text(p.userCode, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    Hint(tr("在 ${ConnectorSettings.hostOf(p.verificationUri)} 粘贴这个码并授权，完成后这里会自动变为已连接。", "Paste the code on ${ConnectorSettings.hostOf(p.verificationUri)} and authorize. This page updates when it is done."))
    FlowRow(modifier = Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCopy) { Text(tr("复制", "Copy")) }
        OutlinedButton(onClick = onOpen) { Text(tr("打开验证页", "Open the page")) }
        if (onUseToken != null) TextButton(onClick = onUseToken, enabled = !busy) { Text(tr("改用 token", "Use a token instead")) }
        TextButton(onClick = onCancel, enabled = !busy) { Text(tr("取消", "Cancel")) }
    }
}

/** 自带客户端（Slack 等）：回调地址、步骤、Client ID 与不回显的 Client Secret。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClientPanel(row: ConnectorSettings.Row, busy: Boolean, onCopy: (String) -> Unit, onOpenApp: (String) -> Unit, onCancel: () -> Unit, onContinue: (String, String) -> Unit) {
    SecureWhileShown()
    var clientId by remember(row.id) { mutableStateOf("") }
    var secret by remember(row.id) { mutableStateOf("") }
    val slack = row.auth.clientSetupKind == "slack"
    val app = row.auth.clientSetupAppUrl?.let(ConnectorSettings::hostOf)
    SectionTitle(tr("设置应用客户端", "Set up an app client"))
    Hint(if (slack && app != null) tr("1. 在 $app 的 OAuth & Permissions 中添加这个回调地址：", "1. In OAuth & Permissions at $app, add this redirect URL:") else tr("1. 在应用设置中添加这个回调地址：", "1. In the app settings, add this redirect URL:"))
    row.redirectUri?.let { uri ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(uri, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { onCopy(uri) }) { Text(tr("复制", "Copy")) }
        }
    }
    if (slack) Hint(tr("2. 开启应用需要的 MCP 功能与权限。", "2. Turn on the MCP features and scopes the app needs."))
    Hint(tr("${if (slack) 3 else 2}. 把 Client ID 与 Client Secret 填到这里。", "${if (slack) 3 else 2}. Copy the Client ID and Client Secret here."))
    PlainField(clientId, { clientId = it }, "Client ID", enabled = !busy)
    SecretField(secret, { secret = it }, tr("Client Secret（可选）", "Client secret (optional)"), enabled = !busy)
    FlowRow(modifier = Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.auth.clientSetupAppUrl?.let { url -> TextButton(onClick = { onOpenApp(url) }) { Text(tr("打开应用设置", "Open app settings")) } }
        TextButton(onClick = onCancel, enabled = !busy) { Text(tr("取消", "Cancel")) }
        OutlinedButton(onClick = { onContinue(clientId, secret) }, enabled = clientId.isNotBlank() && !busy) { Text(tr("继续", "Continue")) }
    }
}

/** key 或 token 表单（不回显、禁止截屏）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CredentialForm(row: ConnectorSettings.Row, form: CredForm, busy: Boolean, onOpenLink: (String) -> Unit, onCancel: () -> Unit, onConnect: (String) -> Unit) {
    SecureWhileShown()
    var value by remember(row.id, form) { mutableStateOf("") }
    val isKey = form == CredForm.KEY
    SecretField(value, { value = it }, if (isKey) tr("${row.name} key", "${row.name} key") else tr("${row.name} token", "${row.name} token"), enabled = !busy)
    FlowRow(modifier = Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            row.id == "github" -> TextButton(onClick = { onOpenLink(GITHUB_TOKEN_URL) }) { Text(tr("在 GitHub 创建 token", "Create a token on GitHub")) }
            row.auth.keyUrl != null -> TextButton(onClick = { onOpenLink(row.auth.keyUrl!!) }) { Text(tr("获取 key", "Get a key")) }
        }
        TextButton(onClick = onCancel) { Text(tr("取消", "Cancel")) }
        OutlinedButton(onClick = { onConnect(value) }, enabled = value.isNotBlank() && !busy) { Text(if (busy) tr("连接中…", "Connecting…") else tr("连接", "Connect")) }
    }
}

// ---- 添加其他 MCP 服务器 ----

private enum class AddMode { URL, COMMAND, IMPORT }

/** 添加其他 MCP 服务器：按地址（可选 OAuth）、按命令，或扫描电脑上其他应用的 MCP 配置导入。成功后 [onDone] 返回。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectorAddPage(api: ManageApi, onDone: () -> Unit) {
    var mode by remember { mutableStateOf(AddMode.URL) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var oauth by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<ConnectorSettings.ImportItem>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = "connector:add" in api.inFlight
    val R = ConnectorSettings.Requests
    LaunchedEffect(mode) {
        if (mode == AddMode.IMPORT && found == null && !scanning) {
            scanning = true
            api.read("connector-import-scan", R.importScan()) { res ->
                scanning = false
                when (res) {
                    is DsbotApi.Result.Ok -> found = ConnectorSettings.importItems(res.value)
                    is DsbotApi.Result.BotError -> { found = emptyList(); error = work.keepwriting.core.BotManage.errorText(res.code, res.message) }
                    is DsbotApi.Result.Failure -> { found = emptyList(); error = work.keepwriting.core.BotManage.errorText(null, null) }
                }
            }
        }
    }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Text(tr("添加其他 MCP 服务器", "Add another MCP server"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        ErrorLine(error) { error = null }
        FlowRow(modifier = Modifier.padding(horizontal = 12.dp)) {
            for ((m, label) in listOf(AddMode.URL to tr("地址", "Address"), AddMode.COMMAND to tr("命令", "Command"), AddMode.IMPORT to tr("从其他应用导入", "Import from other apps"))) {
                FilterChip(selected = mode == m, onClick = { mode = m; error = null }, label = { Text(label) }, modifier = Modifier.padding(4.dp))
            }
        }
        when (mode) {
            AddMode.URL, AddMode.COMMAND -> {
                val kind = if (mode == AddMode.URL) ConnectorSettings.AddKind.URL else ConnectorSettings.AddKind.COMMAND
                PlainField(name, { name = it.take(100) }, tr("名称", "Name"), enabled = !busy)
                if (mode == AddMode.URL) {
                    PlainField(url, { url = it }, tr("服务器地址", "Server address"), placeholder = "https://…", enabled = !busy, keyboardType = KeyboardType.Uri)
                    SwitchRow(tr("需要登录（OAuth）", "Sign in (OAuth)"), tr("添加后在详情页登录", "Sign in from its page after adding"), oauth, !busy) { oauth = it }
                } else {
                    PlainField(command, { command = it }, tr("命令", "Command"), placeholder = tr("例如 npx -y some-mcp", "e.g. npx -y some-mcp"), enabled = !busy)
                    Hint(tr("命令在运行 DS Bot 的电脑上执行。", "The command runs on the computer running DS Bot."))
                }
                val problem = ConnectorSettings.addProblem(kind, name, url, command)
                OutlinedButton(
                    onClick = {
                        error = null
                        val body = if (kind == ConnectorSettings.AddKind.URL) R.addUrl(name, url, oauth) else R.addCommand(name, command)
                        api.action("connector:add", "connector-add", body, { onDone() }) { error = it }
                    },
                    enabled = problem == null && !busy,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(tr("添加", "Add")) }
                // 只在填过内容后提示，免得一进页就报错
                if (problem != null && (name.isNotEmpty() || url.isNotEmpty() || command.isNotEmpty())) Hint(problem)
            }
            AddMode.IMPORT -> {
                Hint(tr("扫描运行 DS Bot 的电脑上 Claude Desktop、Cursor 与 VS Code 的 MCP 配置；导入时环境变量与请求头的值会存为密钥。", "Scans the MCP settings of Claude Desktop, Cursor, and VS Code on the computer running DS Bot. Environment and header values are saved as secrets."))
                val list = found
                when {
                    list == null -> Hint(tr("扫描中…", "Scanning…"))
                    list.isEmpty() -> Hint(tr("没有在 Claude Desktop、Cursor 或 VS Code 设置中找到 MCP 服务器。", "No MCP servers found in Claude Desktop, Cursor, or VS Code settings."))
                    else -> {
                        for (item in list) {
                            val on = item.key in picked
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { picked = if (on) picked - item.key else picked + item.key }.padding(horizontal = 8.dp),
                            ) {
                                Checkbox(checked = on, onCheckedChange = { picked = if (it) picked + item.key else picked - item.key })
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                                    Text("${ConnectorSettings.sourceLabel(item.source)} · ${item.target}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                error = null
                                api.action("connector:add", "connector-import", R.import(list.filter { it.key in picked }), { onDone() }) { error = it }
                            },
                            enabled = picked.isNotEmpty() && !busy,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) { Text(tr("导入 ${picked.size} 项", "Import ${picked.size}")) }
                    }
                }
            }
        }
    }
}

// ---- 邮箱 ----

/** 邮箱页：账号（移除二次确认）、Google 待登录（粘贴落地地址）、添加账号表单、发送规则。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MailboxPage(state: Json.Value.Object?, api: ManageApi) {
    val context = LocalContext.current
    val mail = remember(state) { ConnectorSettings.view(state)?.mail }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<MailSettings.Account?>(null) }
    val R = MailSettings.Requests
    fun act(key: String, endpoint: String, payload: String, onOk: (Json.Value) -> Unit = {}) {
        error = null
        api.action("mail:$key", endpoint, payload, onOk) { error = it }
    }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Text(tr("邮箱", "Mailbox"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        Hint(MailSettings.ABOUT)
        if (mail == null) {
            Text(tr("此远端不支持连接器。", "This server does not offer connectors."), modifier = Modifier.padding(16.dp))
            return@Column
        }
        ErrorLine(error) { error = null }
        SectionTitle(tr("账号", "Accounts"))
        if (mail.accounts.isEmpty() && mail.pending.isEmpty()) Hint(tr("还没有邮箱账号。", "No mail accounts yet."))
        for (a in mail.accounts) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(a.address, style = MaterialTheme.typography.bodyLarge)
                    StatusLine(a.error ?: tr("已连接", "Connected"), if (a.error != null) 2 else 1)
                }
                TextButton(onClick = { removing = a }, enabled = "mail:remove:${a.id}" !in api.inFlight) { Text(tr("移除", "Remove"), color = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
        for (p in mail.pending) GooglePendingRow(p, "mail:code" in api.inFlight,
            onOpen = { openExternal(context, p.url) }, onCopy = { SensitiveClipboard.copy(context, p.url) },
            onFinish = { input, done -> act("code", "mail-sign-in-code", R.signInCode(p.address, input)) { done() } })

        if (adding) {
            MailAddForm(mail, api, onOpenLink = { openExternal(context, it) }, onGooglePending = { url -> openExternal(context, url) }, onClose = { adding = false })
        } else {
            TextButton(onClick = { adding = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(if (mail.accounts.isEmpty()) tr("＋ 添加邮箱", "+ Add a mailbox") else tr("＋ 再添加一个", "+ Add another"))
            }
        }

        val rules = remember(mail, state) { MailSettings.rules(mail, state) }
        if (rules.isNotEmpty()) {
            SectionTitle(tr("发送规则", "Sending rules"))
            Hint(tr("符合规则的邮件不再弹出确认卡，Bot 直接发送。", "Mail that matches a rule is sent without a confirmation card."))
            for (rule in rules) {
                val (title, kind, key, body) = when (rule) {
                    is MailSettings.RuleRow.Recipient -> listOf(rule.address, tr("允许的收件人", "Allow recipient"), "rule:r:${rule.address}", R.removeRecipient(rule.address))
                    is MailSettings.RuleRow.Bot -> listOf(rule.name, tr("允许此 Bot 直接发送", "Allow this Bot to send directly"), "rule:b:${rule.botId}", R.botDirectOff(rule.botId))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { act(key, "mail-rule-set", body) }, enabled = "mail:$key" !in api.inFlight) { Text(tr("移除", "Remove")) }
                }
            }
        }
    }
    removing?.let { a ->
        ConfirmDialog(
            title = tr("移除 ${a.address}？", "Remove ${a.address}?"),
            text = tr("会删除保存的授权码或登录，Bot 不能再用这个邮箱收发邮件。", "Its saved password or sign-in is deleted and Bots can no longer use this mailbox."),
            confirm = tr("移除", "Remove"),
            onConfirm = { act("remove:${a.id}", "mail-account-remove", R.remove(a.id)) },
            onDismiss = { removing = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GooglePendingRow(p: MailSettings.GooglePending, busy: Boolean, onOpen: () -> Unit, onCopy: () -> Unit, onFinish: (String, () -> Unit) -> Unit) {
    var input by remember(p.url) { mutableStateOf("") }
    Text(p.address ?: "Gmail", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
    StatusLine(tr("等待在浏览器中完成 Google 登录", "Finish signing in to Google in your browser"), 0)
    Hint(tr("授权后浏览器会打开一个无法访问的 127.0.0.1 页面：复制它的完整地址粘贴到下面；也可以复制链接到运行 DS Bot 的电脑上打开。", "After you authorize, the browser opens a 127.0.0.1 page that cannot load. Paste its full address below, or open the link on the computer running DS Bot."))
    FlowRow(modifier = Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onOpen) { Text(tr("在浏览器中打开", "Open in browser")) }
        TextButton(onClick = onCopy) { Text(tr("复制登录链接", "Copy sign-in link")) }
    }
    PlainField(input, { input = it }, tr("粘贴浏览器停留页面的地址", "Paste the address you land on"), keyboardType = KeyboardType.Uri)
    OutlinedButton(onClick = { onFinish(input) { input = "" } }, enabled = input.isNotBlank() && !busy, modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(tr("完成登录", "Finish sign-in"))
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

/** 添加邮箱账号（网页端 MailForm）：服务商、开通步骤、地址、不回显的授权码、其他服务商的主机；可用时「用 Google 登录」。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MailAddForm(mail: MailSettings.Mail, api: ManageApi, onOpenLink: (String) -> Unit, onGooglePending: (String) -> Unit, onClose: () -> Unit) {
    SecureWhileShown()
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    var imapHost by remember { mutableStateOf("") }
    var smtpHost by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = "mail:add" in api.inFlight
    val chosen = MailSettings.chosenProvider(picked, address)
    val guide = MailSettings.guide(chosen)
    val google = chosen == "gmail" && mail.google
    SectionTitle(tr("添加邮箱", "Add a mailbox"))
    FlowRow(modifier = Modifier.padding(horizontal = 12.dp)) {
        for ((id, label) in MailSettings.PROVIDERS) {
            FilterChip(selected = chosen == id, enabled = !busy, onClick = { picked = id }, label = { Text(label) }, modifier = Modifier.padding(4.dp))
        }
    }
    if (guide != null) {
        guide.steps.forEachIndexed { i, step -> Hint("${i + 1}. $step") }
        FlowRow(modifier = Modifier.padding(horizontal = 12.dp)) {
            TextButton(onClick = { onOpenLink(guide.open) }) { Text(tr("打开 ${guide.host}", "Open ${guide.host}")) }
            TextButton(onClick = { onOpenLink(guide.help) }) { Text(tr("官方帮助", "Official help")) }
        }
    }
    PlainField(address, { address = it.trim() }, tr("邮箱地址", "Email address"), enabled = !busy, keyboardType = KeyboardType.Email)
    SecretField(password, { password = it }, MailSettings.passwordLabel(chosen), enabled = !busy)
    if (chosen == "other") {
        PlainField(imapHost, { imapHost = it }, tr("IMAP 主机", "IMAP host"), enabled = !busy, keyboardType = KeyboardType.Uri)
        PlainField(smtpHost, { smtpHost = it }, tr("SMTP 主机", "SMTP host"), enabled = !busy, keyboardType = KeyboardType.Uri)
    }
    ErrorLine(error) { error = null }
    val problem = MailSettings.addProblem(address, password, chosen, imapHost, smtpHost)
    FlowRow(modifier = Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (google) {
            OutlinedButton(enabled = !busy, onClick = {
                error = null
                api.action("mail:add", "mail-account-add", MailSettings.Requests.addGoogle(address), { v ->
                    // 应答为 mailView：最新的待登录项即本次
                    MailSettings.decode(v as? Json.Value.Object).pending.lastOrNull()?.let { onGooglePending(it.url) }
                    onClose()
                }) { error = it }
            }) { Text(tr("用 Google 登录", "Sign in with Google")) }
        }
        TextButton(onClick = onClose, enabled = !busy) { Text(tr("取消", "Cancel")) }
        OutlinedButton(enabled = problem == null && !busy, onClick = {
            error = null
            api.action("mail:add", "mail-account-add", MailSettings.Requests.add(address, password, chosen, imapHost, smtpHost), { password = ""; onClose() }) { error = it }
        }) { Text(if (busy) tr("验证中…", "Verifying…") else tr("添加", "Add")) }
    }
    if (problem != null && address.isNotEmpty()) Hint(problem)
}

// ---- Bot 详情的连接器分组 ----

/** Bot 详情「连接器」分组：逐个开关已连接的连接器对该 Bot 是否可用（connector-set offFor）。没有已连接的连接器时不显示。 */
@Composable
fun BotConnectorsGroup(state: Json.Value.Object?, botId: String, api: ManageApi, onError: (String) -> Unit) {
    val rows = remember(state) { ConnectorSettings.botRows(ConnectorSettings.view(state)) }
    if (rows.isEmpty()) return
    SectionTitle(tr("连接器", "Connectors"))
    for (row in rows) {
        val key = "$botId:connector:${row.id}"
        SwitchRow(row.name, ConnectorSettings.aboutText(row.about).takeIf { it.isNotBlank() }, botId !in row.offFor, key !in api.inFlight) { on ->
            api.action(key, "connector-set", ConnectorSettings.Requests.setOffFor(row.id, ConnectorSettings.offForToggled(row, botId, on)), {}, onError)
        }
    }
}
