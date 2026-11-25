package work.keepwriting.dshanywhere

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import work.keepwriting.core.BotManage
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.DsbotSystem
import work.keepwriting.core.DsbotVersions
import work.keepwriting.core.I18n
import java.time.ZoneId

/*
 * 关于 DS Bot 与反馈诊断（dsbot-system-about）：远端 DS-Bot 与 dsh 版本、与 App 已测基准比对、更新检查与在电脑上安装
 * （二次确认、安装中前台约 2 秒轮询、重连后重读），源码/更新说明链接与 Star 数；诊断页显示远端诊断报告、最近错误、
 * 完整日志与二次确认清理。均为按需端点：入口不门控，按应答降级（tech/dsbot-api 的 system-endpoints-0310）。
 * 远端诊断文本只在页面内存中显示，不写入日志、诊断上报、剪贴板与磁盘。
 */

private sealed interface SysLoad<out T> {
    data object Loading : SysLoad<Nothing>
    data class Ok<T>(val value: T) : SysLoad<T>
    data class Failed(val text: String, val unsupported: Boolean) : SysLoad<Nothing>
}

private fun failedOf(r: DsbotApi.Result): SysLoad.Failed = when (r) {
    is DsbotApi.Result.BotError -> SysLoad.Failed(BotManage.errorText(r.code, r.message), r.code == "bot/not-found")
    is DsbotApi.Result.Failure -> SysLoad.Failed(BotManage.errorText(null, null), false)
    is DsbotApi.Result.Ok -> SysLoad.Failed(tr("服务端应答无法识别", "The server answer could not be read"), false)
}

/** 待确认的安装：DS-Bot 本体或 dsh。 */
private enum class InstallTarget { BOT, DSH }

@Composable
fun AboutDsBotPage(api: ManageApi, visible: Boolean, online: Boolean, onDiagnostics: () -> Unit) {
    val context = LocalContext.current
    var load by remember { mutableStateOf<SysLoad<DsbotSystem.Update>>(SysLoad.Loading) }
    var stars by remember { mutableStateOf<Int?>(null) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<InstallTarget?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // update-check 应答不可取消且可能乱序（首读、检查、轮询、安装应答）：只采用最新一次发起的
    val gen = remember { intArrayOf(0) }
    val polling = remember { booleanArrayOf(false) }
    val zone = remember { ZoneId.systemDefault() }

    fun adopt(mine: Int, r: DsbotApi.Result, quiet: Boolean) {
        if (mine != gen[0]) return
        now = System.currentTimeMillis()
        val decoded = (r as? DsbotApi.Result.Ok)?.let { DsbotSystem.decodeUpdate(it.value) }
        when {
            decoded != null -> load = SysLoad.Ok(decoded)
            // 安装中轮询失败（DS-Bot 重装时宿主短暂卸载）不报错，保持安装中显示，连接恢复后按重读结果显示
            quiet -> {}
            else -> load = failedOf(r)
        }
    }

    fun read(fresh: Boolean, quiet: Boolean = false, done: () -> Unit = {}) {
        val mine = ++gen[0]
        api.read("update-check", DsbotSystem.Requests.check(fresh)) { r -> done(); adopt(mine, r, quiet) }
    }

    // 首读与重连（离线→在线，含电脑上的服务更新后重启）时重读版本；Star 数只在页面打开期间读（上游缓存 1 小时）
    LaunchedEffect(online, round) {
        if (!online) return@LaunchedEffect
        // 已有视图时（重连后）静默重读：失败保留原视图与安装中轮询，不整页换成错误（评审发现）
        read(fresh = false, quiet = load is SysLoad.Ok)
        api.read("repo-stars", DsbotSystem.Requests.stars()) { r -> (r as? DsbotApi.Result.Ok)?.let { stars = DsbotSystem.stars(it.value) } }
    }

    val update = (load as? SysLoad.Ok)?.value
    val installing = DsbotSystem.installing(update)
    // 安装中且页面可见、App 在前台、已连接时约 2 秒单飞重读；任一条件不满足即停止
    LaunchedEffect(installing, visible, online) {
        if (!installing || !visible || !online) return@LaunchedEffect
        while (true) {
            delay(DsbotSystem.POLL_MS)
            // 单飞；手动检查进行中时跳过，免得轮询的代次号让检查结果作废（评审发现）
            if (polling[0] || checking) continue
            polling[0] = true
            read(fresh = false, quiet = true) { polling[0] = false }
        }
    }

    fun check() {
        if (checking) return
        checking = true
        error = null
        val mine = ++gen[0]
        api.read("update-check", DsbotSystem.Requests.check(true)) { r ->
            checking = false
            if (mine != gen[0]) return@read
            if (r is DsbotApi.Result.Ok) adopt(mine, r, quiet = false)
            else error = failedOf(r).text
        }
    }

    fun install(target: InstallTarget) {
        error = null
        val (key, endpoint, body) = when (target) {
            InstallTarget.BOT -> Triple("sys:update-install", "update-install", DsbotSystem.Requests.install())
            InstallTarget.DSH -> Triple("sys:dsh-update-install", "dsh-update-install", DsbotSystem.Requests.dshInstall())
        }
        // 写通道不自动重试；应答即安装开始后的视图，直接采用并使在途读取失效
        api.action(key, endpoint, body, { value ->
            val current = (load as? SysLoad.Ok)?.value
            val next = when (target) {
                InstallTarget.BOT -> DsbotSystem.decodeUpdate(value)?.let { v -> current?.copy(bot = v.bot) ?: v }
                InstallTarget.DSH -> DsbotSystem.decodeDsh(value)?.let { d -> current?.copy(dsh = d) }
            }
            if (next != null) {
                gen[0]++
                now = System.currentTimeMillis()
                load = SysLoad.Ok(next)
            } else {
                // 应答缺视图（宿主不提供 dsh 更新等）：按 update-check 重读
                read(fresh = false)
            }
        }) { error = it }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(tr("关于 DS Bot", "About DS Bot"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        ErrorLine(error) { error = null }
        when (val l = load) {
            // 未连接时首读不会发出：说明原因，连上后自动读取（评审发现：原先一直显示读取中）
            SysLoad.Loading -> SysHint(if (online) tr("读取中…", "Loading…") else tr("未连接，连接后自动读取。", "Not connected. It loads once connected."))
            is SysLoad.Failed -> {
                if (l.unsupported) Text(tr("此远端不支持版本检查。", "This server does not offer version checks."), modifier = Modifier.padding(16.dp))
                else {
                    ErrorLine(l.text) {}
                    TextButton(onClick = { load = SysLoad.Loading; round++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("重试", "Try again")) }
                }
            }
            is SysLoad.Ok -> {
                val u = l.value
                VersionCard(u, stars, onOpen = { openExternal(context, it) })
                SectionTitle(tr("更新", "Updates"))
                val botRow = DsbotSystem.botRow(u.bot, now, zone)
                UpdateRow("DS-Bot", botRow, checking, api, "sys:update-install", onCheck = ::check, onInstall = { confirm = InstallTarget.BOT })
                u.bot.notesUrl?.takeIf { u.bot.available }?.let { url ->
                    TextButton(onClick = { openExternal(context, url) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("新版本说明", "What's new")) }
                }
                u.dsh?.takeIf { DsbotSystem.showsDsh(it) }?.let { d ->
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    UpdateRow("DSH", DsbotSystem.dshRow(d), checking, api, "sys:dsh-update-install", onCheck = ::check, onInstall = { confirm = InstallTarget.DSH })
                }
                SectionTitle(tr("链接", "Links"))
                u.bot.repoUrl?.let { url ->
                    ValueRow(tr("源码", "Source code"), tr("打开", "Open"), hint = url.removePrefix("https://")) { openExternal(context, url) }
                }
                u.bot.notesUrl?.let { url ->
                    ValueRow(tr("更新说明", "Release notes"), tr("打开", "Open"), hint = null) { openExternal(context, url) }
                }
            }
        }
        ValueRow(tr("反馈与诊断", "Feedback & diagnostics"), tr("打开", "Open"), hint = tr("DS-Bot 最近的错误与错误日志", "Recent DS Bot errors and the error log"), onClick = onDiagnostics)
    }

    confirm?.let { target ->
        val u = update
        val version = when (target) {
            InstallTarget.BOT -> u?.bot?.latest
            InstallTarget.DSH -> u?.dsh?.verified
        }.orEmpty()
        val name = if (target == InstallTarget.BOT) "DS-Bot" else "DSH"
        ConfirmDialog(
            title = tr("在电脑上把 $name 更新到 $version？", "Update $name to $version on the computer?"),
            text = tr(
                "更新在运行 DSH 的电脑上执行：服务可能重新加载或需要重启 DSH，当前会话可能断开需要重连；更新后远端版本将偏离本 App 已测基准（DS-Bot ${DsbotVersions.BASELINE_DS_BOT}、DSH ${DsbotVersions.BASELINE_DSH}），部分功能可能不可用。",
                "The update runs on the computer that runs DSH: the service may reload or need a DSH restart, and this session may disconnect and reconnect. Afterwards the server will differ from the versions this app was tested with (DS Bot ${DsbotVersions.BASELINE_DS_BOT}, DSH ${DsbotVersions.BASELINE_DSH}); some features may not work.",
            ),
            confirm = tr("更新", "Update"),
            onConfirm = { install(target) },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun SysHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun VersionCard(u: DsbotSystem.Update, stars: Int?, onOpen: (String) -> Unit) {
    val baseline = DsbotSystem.baseline(u)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("DS Bot", style = MaterialTheme.typography.titleMedium)
                    Text(tr("远端 DS-Bot ", "Server DS Bot ") + (u.bot.current ?: "?"), style = MaterialTheme.typography.bodyMedium)
                    Text(tr("远端 DSH ", "Server DSH ") + (u.dsh?.current ?: "?"), style = MaterialTheme.typography.bodyMedium)
                }
                u.bot.repoUrl?.let { url ->
                    OutlinedButton(onClick = { onOpen(url) }) {
                        Text("★ Star" + (stars?.let { " · " + DsbotSystem.starText(it) } ?: ""))
                    }
                }
            }
            Text(
                tr("App 已测基准：DS-Bot ${DsbotVersions.BASELINE_DS_BOT}、DSH ${DsbotVersions.BASELINE_DSH}", "Tested with: DS Bot ${DsbotVersions.BASELINE_DS_BOT}, DSH ${DsbotVersions.BASELINE_DSH}"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                DsbotSystem.baselineText(baseline), style = MaterialTheme.typography.bodySmall,
                color = if (baseline == DsbotSystem.Baseline.MISMATCHED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun UpdateRow(
    label: String, row: DsbotSystem.Row, checking: Boolean, api: ManageApi, key: String,
    onCheck: () -> Unit, onInstall: () -> Unit,
) {
    val sending = key in api.inFlight
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            row.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        when (row.button) {
            DsbotSystem.Button.NONE -> {}
            DsbotSystem.Button.CHECK, DsbotSystem.Button.CHECK_DISABLED ->
                OutlinedButton(onClick = onCheck, enabled = row.button == DsbotSystem.Button.CHECK && !checking) {
                    Text(if (checking) tr("检查中…", "Checking…") else tr("检查更新", "Check for updates"))
                }
            DsbotSystem.Button.INSTALL, DsbotSystem.Button.RETRY ->
                Button(onClick = onInstall, enabled = !sending) {
                    Text(if (row.button == DsbotSystem.Button.RETRY) tr("重试", "Try again") else tr("更新", "Update"))
                }
            DsbotSystem.Button.INSTALLING -> Button(onClick = {}, enabled = false) { Text(tr("更新中…", "Updating…")) }
        }
    }
}

@Composable
fun DsbotDiagnosticsPage(api: ManageApi) {
    var load by remember { mutableStateOf<SysLoad<DsbotSystem.Report>>(SysLoad.Loading) }
    var log by remember { mutableStateOf<SysLoad<String>?>(null) } // null = 未展开
    var round by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val gen = remember { intArrayOf(0, 0) } // [报告, 日志] 的代次
    val zone = remember { ZoneId.systemDefault() }

    fun readLog() {
        val mine = ++gen[1]
        log = SysLoad.Loading
        api.read("diagnostics-log", DsbotSystem.Requests.log()) { r ->
            if (mine != gen[1]) return@read
            log = (r as? DsbotApi.Result.Ok)?.let { ok -> DsbotSystem.logText(ok.value)?.let { SysLoad.Ok(it) } } ?: failedOf(r)
        }
    }

    LaunchedEffect(round) {
        val mine = ++gen[0]
        api.read("diagnostics", DsbotSystem.Requests.diagnostics(I18n.lang)) { r ->
            if (mine != gen[0]) return@read
            now = System.currentTimeMillis()
            load = (r as? DsbotApi.Result.Ok)?.let { ok -> DsbotSystem.decodeReport(ok.value)?.let { SysLoad.Ok(it) } } ?: failedOf(r)
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(tr("反馈与诊断", "Feedback & diagnostics"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        SysHint(tr("远端 DS-Bot 记录的版本、系统与最近错误；密钥、令牌与家目录已由服务端隐藏。", "Versions, system, and recent errors recorded by DS Bot on the server. Keys, tokens, and the home folder are hidden by the server."))
        ErrorLine(error) { error = null }
        note?.let { SysHint(it) }
        when (val l = load) {
            SysLoad.Loading -> SysHint(tr("读取中…", "Loading…"))
            is SysLoad.Failed -> {
                if (l.unsupported) Text(tr("此远端不支持诊断。", "This server does not offer diagnostics."), modifier = Modifier.padding(16.dp))
                else {
                    ErrorLine(l.text) {}
                    TextButton(onClick = { load = SysLoad.Loading; round++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("重试", "Try again")) }
                }
            }
            is SysLoad.Ok -> {
                val report = l.value
                SectionTitle(tr("诊断信息", "Diagnostics"))
                for ((label, value) in DsbotSystem.factLines(report.facts)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.3f))
                        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.7f))
                    }
                }
                SectionTitle(tr("最近错误 · ${report.total}", "Recent errors · ${report.total}"))
                if (report.entries.isEmpty()) {
                    SysHint(tr("暂无错误记录。模型调用失败与 DS-Bot 的问题会按新到旧显示在这里。", "No errors recorded. Failed model calls and DS Bot problems show here, newest first."))
                }
                for (e in report.entries) {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            "●", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp, top = 2.dp),
                            color = if (e.level == "warn") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(DsbotSystem.entrySummary(e), style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                            Text(DsbotSystem.entryMeta(e, now, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DsbotSystem.codeTag(e)?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
                SectionTitle(tr("错误日志", "Error log"))
                SysHint(tr("保存在电脑上，重启后保留，最多约 1 MB。", "Kept on the computer across restarts, up to about 1 MB."))
                Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { if (log == null) readLog() else { gen[1]++; log = null } }) {
                        Text(if (log == null) tr("查看完整日志", "Show full log") else tr("收起日志", "Hide log"))
                    }
                    TextButton(
                        onClick = { note = null; confirmClear = true },
                        enabled = report.total > 0 && "sys:diagnostics-clear" !in api.inFlight,
                    ) { Text(tr("清理日志", "Clear log"), color = if (report.total > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                when (val lg = log) {
                    null -> {}
                    SysLoad.Loading -> SysHint(tr("读取中…", "Loading…"))
                    is SysLoad.Failed -> ErrorLine(lg.text) { log = null }
                    is SysLoad.Ok -> Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            lg.value.ifEmpty { tr("暂无记录。", "No entries.") },
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, softWrap = false,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = tr("清理 DS-Bot 错误日志？", "Clear the DS Bot error log?"),
            text = tr("会清空电脑上保存的错误记录，网页端也会一并清空，无法恢复。", "This empties the error records kept on the computer, for the web page too. It cannot be undone."),
            confirm = tr("清理", "Clear"),
            onConfirm = {
                error = null
                api.action("sys:diagnostics-clear", "diagnostics-clear", DsbotSystem.Requests.clear(), {
                    note = tr("已清理错误日志。", "Cleared the error log.")
                    if (log != null) readLog()
                    load = SysLoad.Loading
                    round++
                }) { error = it }
            },
            onDismiss = { confirmClear = false },
        )
    }
}
