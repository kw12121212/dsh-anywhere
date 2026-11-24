package work.keepwriting.dshanywhere

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.suspendCancellableCoroutine
import work.keepwriting.core.BotManage
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.ImBridges
import work.keepwriting.core.Json
import work.keepwriting.core.QrMatrix
import kotlin.coroutines.resume

/*
 * IM 接入（dsbot-im-bridges）：IM 页（等你允许、我的 IM 机器人、添加平台）、添加页（字段表单与微信扫码登录）与详情页。
 * 数据来自 state.im 与 live imStatus；写操作经 ManageApi 发出、不自动重试，成功后由 state 刷新显示。
 * 密钥字段不回显、显示期间禁止截屏，值不写日志与诊断上报。
 */

/** IM 页：等你允许的请求、已添加的 IM 机器人、可添加的平台。 */
@Composable
fun ImPage(state: Json.Value.Object?, api: ManageApi, onOpen: (String) -> Unit, onAdd: (String) -> Unit) {
    val view = remember(state) { ImBridges.view(state) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(tr("IM 接入", "IM"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        Text(
            tr("把 Bot 接到 Telegram、微信、飞书等聊天软件，在那里和 Bot 对话。", "Connect Bots to Telegram, WeChat, Feishu, and other chat apps and talk to them there."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (view == null) {
            Text(tr("此远端不支持 IM 接入。", "This server does not offer IM."), modifier = Modifier.padding(16.dp))
            return@Column
        }
        ErrorLine(error) { error = null }
        val waiting = ImBridges.waiting(view)
        if (waiting.isNotEmpty()) {
            SectionTitle(tr("等你允许", "Waiting for approval"))
            for ((conn, req) in waiting) {
                RequestRow(conn, req, withSource = true, busy = "im:${conn.id}:pair:${req.id}" in api.inFlight) { allow ->
                    error = null
                    api.action("im:${conn.id}:pair:${req.id}", "im-pair", ImBridges.Requests.pair(conn.id, req.id, allow), {}) { error = it }
                }
            }
        }
        if (view.connections.isNotEmpty()) {
            SectionTitle(tr("我的 IM 机器人", "Your IM bots"))
            val bots = BotManage.botNames(state)
            for (conn in view.connections) {
                val status = ImBridges.statusOf(state, conn.id)
                val st = ImBridges.stateOf(conn, status)
                Column(modifier = Modifier.fillMaxWidth().clickable { onOpen(conn.id) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ImBridges.title(conn), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (conn.requests.isNotEmpty()) Badge("${conn.requests.size}")
                    }
                    Text(
                        listOfNotNull(ImBridges.platformOf(conn.platform).name, ImBridges.stateText(conn, status), conn.botId?.let { bots[it] }?.let { tr("对话：$it", "Talks to $it") }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (st) {
                            "error" -> MaterialTheme.colorScheme.error
                            "ready" -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        SectionTitle(if (view.connections.isEmpty()) tr("选择平台", "Choose a platform") else tr("添加 IM 机器人", "Add an IM bot"))
        for (p in ImBridges.addablePlatforms(view)) {
            val count = view.connections.count { it.platform == p.id }
            ValueRow(p.name, tr("连接", "Connect"), hint = if (count > 0) tr("已添加 $count 个", "$count added") else null) { onAdd(p.id) }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(color = MaterialTheme.colorScheme.error, shape = MaterialTheme.shapes.small) {
        Text(text, color = MaterialTheme.colorScheme.onError, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun RequestRow(conn: ImBridges.Conn, req: ImBridges.Request, withSource: Boolean, busy: Boolean, onAnswer: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(req.name, style = MaterialTheme.typography.bodyLarge)
            if (withSource) Text(tr("想通过 ${ImBridges.title(conn)} 对话", "Wants to talk through ${ImBridges.title(conn)}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { onAnswer(false) }, enabled = !busy) { Text(tr("拒绝", "Deny")) }
        OutlinedButton(onClick = { onAnswer(true) }, enabled = !busy) { Text(tr("允许", "Allow")) }
    }
}

/** 选择对话的 Bot（或群聊绑定时另含群聊与「未绑定」）的对话框。 */
@Composable
private fun TargetPicker(
    state: Json.Value.Object?,
    title: String,
    rooms: Boolean,
    selected: ImBridges.Target?,
    onPick: (ImBridges.Target?) -> Unit,
    onDismiss: () -> Unit,
) {
    val bots = ImBridges.bots(state)
    val roomList = if (rooms) ImBridges.rooms(state) else emptyList()
    val options = buildList<Triple<ImBridges.Target?, String, String?>> {
        if (rooms) add(Triple(null, tr("未绑定", "Not bound"), null))
        for ((id, name) in bots) add(Triple(ImBridges.Target(id, null), name, null))
        for ((id, name) in roomList) add(Triple(ImBridges.Target(null, id), name, null))
    }
    val headers = buildMap {
        if (rooms) {
            put(1, tr("Bot", "Bots"))
            if (roomList.isNotEmpty()) put(1 + bots.size, tr("群聊", "Group chats"))
        }
    }
    PickerDialog(title, options, selected, headers, onPick = { onPick(it); onDismiss() }, onDismiss = onDismiss)
}

// ---- 添加 ----

/** 添加 IM 机器人：开通步骤、对话的 Bot、字段表单；扫码平台为二维码登录。成功后 [onDone] 返回。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImAddPage(state: Json.Value.Object?, platformId: String, api: ManageApi, onDone: () -> Unit) {
    val context = LocalContext.current
    val platform = ImBridges.platformOf(platformId)
    val bots = remember(state) { ImBridges.bots(state) }
    var botId by remember { mutableStateOf(bots.firstOrNull()?.first) }
    var typed by remember { mutableStateOf(mapOf<String, String>()) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = "im:add" in api.inFlight
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Text(platform.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        platform.steps.forEachIndexed { i, (zh, en) ->
            Text("${i + 1}. ${tr(zh, en)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        ImBridges.linkOf(platform, typed["domain"])?.let { (url, site) ->
            TextButton(onClick = { openExternal(context, url) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("打开 $site", "Open $site")) }
        }
        ErrorLine(error) { error = null }
        ValueRow(tr("对话对象", "Talks to"), botId?.let { id -> bots.firstOrNull { it.first == id }?.second } ?: tr("选择 Bot", "Choose a Bot"), enabled = !busy) { picking = true }

        if (platform.qrLogin) {
            val bot = botId
            if (bot == null) {
                Text(tr("先选择对话的 Bot。", "Choose a Bot first."), modifier = Modifier.padding(16.dp))
            } else {
                QrLogin(platform.id, bot, api, onDone)
            }
        } else {
            SecureWhileShown()
            for (f in platform.fields) {
                if (f.options.isNotEmpty()) {
                    SectionTitle(f.label)
                    val value = ImBridges.formValues(platform, typed)[f.key]
                    FlowRow(modifier = Modifier.padding(horizontal = 12.dp)) {
                        for ((id, label) in f.options) {
                            FilterChip(selected = value == id, enabled = !busy, onClick = { typed = typed + (f.key to id) }, label = { Text(label) }, modifier = Modifier.padding(4.dp))
                        }
                    }
                } else if (f.secret) {
                    SecretField(typed[f.key].orEmpty(), { typed = typed + (f.key to it) }, f.hint?.let { "${f.label}（$it…）" } ?: f.label, enabled = !busy)
                } else {
                    androidx.compose.material3.OutlinedTextField(
                        value = typed[f.key].orEmpty(),
                        onValueChange = { typed = typed + (f.key to it) },
                        label = { Text(f.label) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            val problem = ImBridges.addProblem(platform, botId, typed)
            OutlinedButton(
                onClick = {
                    error = null
                    api.action("im:add", "im-add", ImBridges.Requests.add(platform.id, botId.orEmpty(), ImBridges.formValues(platform, typed)), { onDone() }) { error = it }
                },
                enabled = problem == null && !busy,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(if (busy) tr("连接中…", "Connecting…") else tr("连接", "Connect")) }
        }
    }
    if (picking) {
        TargetPicker(state, tr("对话对象", "Talks to"), rooms = false, selected = botId?.let { ImBridges.Target(it, null) },
            onPick = { t -> t?.botId?.let { botId = it } }, onDismiss = { picking = false })
    }
}

/**
 * 一次写操作的挂起包装（经 ManageApi 原始应答通道：在途去重、慢端点长读超时）。
 * 同一 [key] 已在途时请求被丢弃、本挂起不会恢复：调用方须保证 key 不与仍在途的请求相同（扫码按轮次区分）。
 */
private suspend fun ManageApi.call(key: String, endpoint: String, payload: String): DsbotApi.Result =
    suspendCancellableCoroutine { cont -> actionRaw(key, endpoint, payload) { if (cont.isActive) cont.resume(it) } }

private fun resultError(r: DsbotApi.Result): String? = when (r) {
    is DsbotApi.Result.Ok -> null
    is DsbotApi.Result.BotError -> BotManage.errorText(r.code, r.message)
    is DsbotApi.Result.Failure -> BotManage.errorText(null, null)
}

/**
 * 扫码登录（网页端 QrLogin）：取二维码后反复等待结果，done 返回、expired 停止并可换一个；离开页面即停止。
 * 二维码供另一台设备扫描，也可复制链接或在其他应用（微信）中打开。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QrLogin(platform: String, botId: String, api: ManageApi, onDone: () -> Unit) {
    val context = LocalContext.current
    var round by remember { mutableIntStateOf(0) }
    var login by remember { mutableStateOf<ImBridges.LoginStart?>(null) }
    var status by remember { mutableStateOf("waiting") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(botId, round) {
        login = null
        status = "waiting"
        error = null
        val started = api.call("im:login:start:$round", "im-login-start", ImBridges.Requests.loginStart(platform, botId))
        resultError(started)?.let { error = it; return@LaunchedEffect }
        val l = ImBridges.loginStart((started as DsbotApi.Result.Ok).value) ?: run { error = BotManage.errorText(null, null); return@LaunchedEffect }
        login = l
        while (true) {
            val r = api.call("im:login:wait:$round", "im-login-wait", ImBridges.Requests.loginWait(platform, botId, l.loginId))
            resultError(r)?.let { error = it; return@LaunchedEffect }
            val s = ImBridges.loginStatus((r as DsbotApi.Result.Ok).value)
            if (s == "done") { onDone(); return@LaunchedEffect }
            status = s
            if (s == "expired") return@LaunchedEffect
        }
    }
    val ended = status == "expired" || error != null
    val code = login
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        val matrix = remember(code?.qr) { code?.qr?.let(QrMatrix::encode) }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp).background(Color.White)) {
            if (matrix != null && !ended) QrImage(matrix)
            if (status == "scanned") Text("✓", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            error ?: ImBridges.loginCaption(status, code != null),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (code != null && !ended) {
            Text(
                tr("请用另一台设备的微信扫描；只有这一台手机时，可复制链接或在微信中打开。", "Scan with WeChat on another device. With only this phone, copy the link or open it in WeChat."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { SensitiveClipboard.copy(context, code.qr) }) { Text(tr("复制链接", "Copy link")) }
                TextButton(onClick = { openExternal(context, code.qr) }) { Text(tr("在其他应用中打开", "Open in another app")) }
            }
        }
        if (ended) OutlinedButton(onClick = { round++ }) { Text(tr("换一个二维码", "Get a new code")) }
    }
}

/** 二维码点阵绘制（四周留静区）。 */
@Composable
private fun QrImage(m: QrMatrix) {
    Canvas(modifier = Modifier.size(232.dp)) {
        val total = m.size + QrMatrix.QUIET * 2
        val cell = size.minDimension / total
        drawRect(Color.White)
        for (y in 0 until m.size) for (x in 0 until m.size) {
            if (m.isDark(x, y)) drawRect(Color.Black, topLeft = Offset((x + QrMatrix.QUIET) * cell, (y + QrMatrix.QUIET) * cell), size = Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

// ---- 详情 ----

/** IM 机器人详情：状态、重试、启用、对话 Bot、请求、已连接账号、聊天（群聊绑定）、移除。 */
@Composable
fun ImDetailPage(state: Json.Value.Object?, id: String, api: ManageApi, onBack: () -> Unit) {
    val conn = remember(state, id) { ImBridges.conn(state, id) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var picking by remember { mutableStateOf<String?>(null) } // "bot" 或聊天 key
    if (conn == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(tr("这个 IM 机器人已不存在。", "This IM bot no longer exists."), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onBack) { Text(tr("返回", "Back")) }
        }
        return
    }
    val R = ImBridges.Requests
    fun act(op: String, endpoint: String, payload: String, onOk: (Json.Value) -> Unit = {}) {
        error = null
        api.action("im:$id:$op", endpoint, payload, onOk) { error = it }
    }
    fun busy(op: String) = "im:$id:$op" in api.inFlight
    val status = ImBridges.statusOf(state, id)
    val st = ImBridges.stateOf(conn, status)
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(ImBridges.title(conn), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        Text(
            ImBridges.platformOf(conn.platform).name + " · " + ImBridges.stateText(conn, status),
            style = MaterialTheme.typography.bodySmall,
            color = if (st == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        ErrorLine(error) { error = null }
        if (st == "error") TextButton(onClick = { act("retry", "im-retry", R.retry(id)) }, enabled = !busy("retry"), modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("重试", "Try again")) }
        SwitchRow(tr("启用", "Enabled"), null, conn.enabled, !busy("enabled")) { act("enabled", "im-update", R.setEnabled(id, it)) }
        ValueRow(tr("对话对象", "Talks to"), conn.botId?.let { b -> ImBridges.bots(state).firstOrNull { it.first == b }?.second } ?: tr("未选择", "None"), enabled = !busy("bot")) { picking = "bot" }

        if (conn.requests.isNotEmpty()) {
            SectionTitle(tr("等你允许", "Waiting for approval"))
            for (req in conn.requests) {
                RequestRow(conn, req, withSource = false, busy = busy("pair:${req.id}")) { allow -> act("pair:${req.id}", "im-pair", R.pair(id, req.id, allow)) }
            }
        }

        SectionTitle(tr("已连接的账号", "Accounts"))
        if (conn.paired.isEmpty()) {
            Text(tr("给 IM 机器人发一条消息，即可连接你的账号", "Send the IM bot a message to connect your account"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
        for (p in conn.paired) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp)) {
                Text(p.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    confirm = tr("移除账号「${p.name}」？", "Remove the account \"${p.name}\"?") to { act("unpair:${p.id}", "im-unpair", R.unpair(id, p.id)) }
                }, enabled = !busy("unpair:${p.id}")) { Text(tr("移除", "Remove"), color = MaterialTheme.colorScheme.error) }
            }
        }

        if (conn.chats.isNotEmpty()) {
            SectionTitle(tr("聊天", "Chats"))
            for (chat in conn.chats) {
                val group = chat.kind == "group"
                Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ImBridges.chatName(conn, chat), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(if (group) tr("群聊", "Group") else tr("私聊", "Direct"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = {
                            confirm = tr("移除聊天「${ImBridges.chatName(conn, chat)}」？", "Remove the chat \"${ImBridges.chatName(conn, chat)}\"?") to { act("chat:${chat.key}", "im-chat", R.removeChat(id, chat.key)) }
                        }, enabled = !busy("chat:${chat.key}")) { Text(tr("移除", "Remove"), color = MaterialTheme.colorScheme.error) }
                    }
                    if (group) {
                        TextButton(onClick = { picking = chat.key }, enabled = !busy("chat:${chat.key}")) {
                            Text(tr("绑定到：", "Bound to: ") + (ImBridges.targetName(state, chat.target) ?: tr("未绑定", "Not bound")))
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

        TextButton(onClick = {
            confirm = tr("移除这个 IM 机器人？它的账号和聊天也会一起移除。", "Remove this IM bot? Its accounts and chats are removed too.") to {
                // 移除后回到 IM 页（只在仍停留在本页时，由 onBack 判断）
                act("remove", "im-remove", R.remove(id)) { onBack() }
            }
        }, enabled = !busy("remove"), modifier = Modifier.padding(8.dp)) { Text(tr("移除 IM 机器人", "Remove IM bot"), color = MaterialTheme.colorScheme.error) }
    }

    confirm?.let { (title, run) ->
        ConfirmDialog(title, tr("移除后不能恢复。", "This cannot be undone."), tr("移除", "Remove"), onConfirm = run, onDismiss = { confirm = null })
    }
    picking?.let { which ->
        if (which == "bot") {
            TargetPicker(state, tr("对话对象", "Talks to"), rooms = false, selected = conn.botId?.let { ImBridges.Target(it, null) },
                onPick = { t -> t?.botId?.let { b -> act("bot", "im-update", R.setBot(id, b)) } }, onDismiss = { picking = null })
        } else {
            val chat = conn.chats.firstOrNull { it.key == which }
            TargetPicker(state, tr("绑定到", "Bound to"), rooms = true, selected = chat?.target,
                onPick = { t -> act("chat:$which", "im-chat", R.bindChat(id, which, t)) }, onDismiss = { picking = null })
        }
    }
}
