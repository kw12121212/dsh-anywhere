package work.keepwriting.dshanywhere

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import work.keepwriting.core.BotManage
import work.keepwriting.core.Json
import work.keepwriting.core.TeamPrefs

/** 聊天页要用的团队偏好（布局、动画、Enter 行为；dsbot-prefs-theme）。未连接时为缺省。 */
val LocalTeamPrefs = compositionLocalOf { TeamPrefs.DEFAULT }

/** Enter 键发送（团队偏好）：开启时硬件键盘回车（不带 Shift）即发送并消费按键，Shift+回车仍换行。 */
fun Modifier.enterSends(enabled: Boolean, onSend: () -> Unit): Modifier =
    if (!enabled) this else onPreviewKeyEvent { e ->
        if ((e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed) {
            if (e.type == KeyEventType.KeyDown) onSend()
            true
        } else {
            false
        }
    }

/** 输入法回车键：Enter 发送开启时为「发送」，否则为缺省（多行输入时换行）。 */
fun enterImeOptions(enterToSend: Boolean) = androidx.compose.foundation.text.KeyboardOptions(
    imeAction = if (enterToSend) androidx.compose.ui.text.input.ImeAction.Send else androidx.compose.ui.text.input.ImeAction.Default,
)

private fun c(rgb: Int) = Color(0xFF000000.toInt() or rgb)

/**
 * 团队主题配色 → Material3 配色（dsbot-prefs-theme）：primary/secondary/tertiary=强调色（文字墨色同网页端 accentInk）、
 * 背景/卡片/气泡/用户气泡按主题，分隔线取文字色的网页端透明度；不加色调叠层（surfaceTint=背景）。
 */
fun teamColorScheme(p: TeamPrefs.Palette, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val accent = c(p.accent)
    val ink = c(TeamPrefs.inkOnAccent(p.accent))
    val text = c(p.text)
    return base.copy(
        primary = accent,
        onPrimary = ink,
        secondary = accent,
        onSecondary = ink,
        tertiary = accent,
        onTertiary = ink,
        background = c(p.bg),
        onBackground = text,
        surface = c(p.bg),
        onSurface = text,
        surfaceTint = c(p.bg),
        surfaceVariant = c(p.bubble),
        onSurfaceVariant = text.copy(alpha = 0.7f),
        primaryContainer = c(p.user),
        onPrimaryContainer = c(p.userText),
        secondaryContainer = c(p.bubble),
        onSecondaryContainer = text,
        tertiaryContainer = c(p.bubble),
        onTertiaryContainer = text,
        surfaceContainerLowest = c(p.bg),
        surfaceContainerLow = c(p.bg),
        surfaceContainer = c(p.card),
        surfaceContainerHigh = c(p.card),
        surfaceContainerHighest = c(p.bubble),
        outline = text.copy(alpha = 0.36f),
        outlineVariant = text.copy(alpha = 0.15f),
    )
}

/**
 * 团队偏好页（dsbot-prefs-theme / dsbot-general-advanced）：主题、强调色、消息布局、头像动画等级、
 * Enter 键行为、新 Bot 默认模型，以及可折叠的「高级」分组（记忆自动召回、记忆容量上限、命令转后台秒数）。
 * 每次只发送改动的那一项（不发宽度，服务端已存的宽度原样保留）；保存后由 state 刷新显示。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamPrefsPage(state: Json.Value.Object?, api: ManageApi) {
    val prefs = TeamPrefs.decode(state) ?: TeamPrefs.DEFAULT
    val memoryEnabled = (state?.members?.get("memory") as? Json.Value.BoolValue)?.value != false
    var error by remember { mutableStateOf<String?>(null) }
    var pickModel by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }
    var resetGen by remember { mutableStateOf(0) }
    fun set(key: String, payload: String, onOk: () -> Unit = {}) {
        error = null
        api.action("prefs:$key", "set-prefs", payload, { onOk() }) { error = it }
    }
    fun busy(key: String) = "prefs:$key" in api.inFlight
    val resetBusy = busy("resetAdvanced")
    val anyAdvBusy = resetBusy ||
        busy("memoryAutoRecall") ||
        busy("backgroundAfterSec") ||
        TeamPrefs.MemoryScope.entries.any { busy("memoryLimit:${it.key}") }
    Column(modifier = Modifier.fillMaxSize()) {
        // 错误条固定在滚动容器上方：滚动到底部操作「高级」分组失败时仍立即可见
        ErrorLine(error) { error = null }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(tr("团队偏好", "Team preferences"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            Text(
                tr("与网页端共用；明暗按本机「外观」设置。消息宽度只在电脑网页端设置。", "Shared with the web app; light or dark follows this phone's Appearance. Message width is set on the computer only."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
            )

        SectionTitle(tr("主题", "Theme"))
        FlowRow(modifier = Modifier.padding(horizontal = 12.dp)) {
            val ids = (TeamPrefs.THEME_IDS + prefs.theme).distinct()
            for (id in ids) {
                FilterChip(selected = prefs.theme == id, enabled = !busy("theme"), onClick = { if (prefs.theme != id) set("theme", TeamPrefs.Requests.theme(id)) },
                    label = { Text(TeamPrefs.themeLabel(id)) }, modifier = Modifier.padding(4.dp))
            }
        }

        SectionTitle(tr("强调色", "Accent"))
        FlowRow(modifier = Modifier.padding(horizontal = 12.dp)) {
            FilterChip(selected = prefs.accent == null, enabled = !busy("accent"), onClick = { if (prefs.accent != null) set("accent", TeamPrefs.Requests.accent(null)) },
                label = { Text(tr("主题默认", "Theme default")) }, modifier = Modifier.padding(4.dp))
            // 色块按当前明暗显示（深色下用深色映射，避免近黑色块看不见）；无障碍名为本地化色名（色块本身不朗读）
            val darkNow = MaterialTheme.colorScheme.background.luminance() < 0.5f
            for ((name, hex, darkHex) in TeamPrefs.ACCENTS) {
                val on = prefs.accent == hex
                val label = TeamPrefs.accentLabel(name)
                FilterChip(selected = on, enabled = !busy("accent"), onClick = { if (!on) set("accent", TeamPrefs.Requests.accent(hex)) },
                    label = { Text("●", color = c((if (darkNow) darkHex else hex).removePrefix("#").toInt(16)), modifier = Modifier.clearAndSetSemantics {}) },
                    modifier = Modifier.padding(4.dp).semantics { contentDescription = label })
            }
            // 网页端取色器选的其他颜色：显示为选中的「自定义」（只读，改用上面的预设或主题默认）
            val custom = prefs.accent?.takeIf { TeamPrefs.presetAccent(it) == null }
            if (custom != null) {
                FilterChip(selected = true, onClick = {}, label = {
                    Text("● " + tr("自定义", "Custom"), color = MaterialTheme.colorScheme.primary)
                }, modifier = Modifier.padding(4.dp))
            }
        }

        SectionTitle(tr("消息布局", "Message layout"))
        FlowRow(modifier = Modifier.padding(horizontal = 12.dp)) {
            for (l in listOf("bubble", "flat")) {
                FilterChip(selected = prefs.layout == l, enabled = !busy("layout"), onClick = { if (prefs.layout != l) set("layout", TeamPrefs.Requests.layout(l)) },
                    label = { Text(TeamPrefs.layoutLabel(l)) }, modifier = Modifier.padding(4.dp))
            }
        }

        SectionTitle(tr("头像动画", "Avatar motion"))
        FlowRow(modifier = Modifier.padding(horizontal = 12.dp)) {
            for (m in listOf("quiet", "normal", "lively")) {
                FilterChip(selected = prefs.motion == m, enabled = !busy("motion"), onClick = { if (prefs.motion != m) set("motion", TeamPrefs.Requests.motion(m)) },
                    label = { Text(TeamPrefs.motionLabel(m)) }, modifier = Modifier.padding(4.dp))
            }
        }
        Text(tr("手机上不显示动画头像；「安静」时工作中指示也不做动画。", "The phone shows no animated avatars; Quiet also stops the working indicator."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))

        SectionTitle(tr("输入", "Typing"))
        SwitchRow(tr("Enter 键发送", "Enter sends"), if (prefs.enterToSend) tr("回车即发送。", "Enter sends the message.") else tr("回车换行。", "Enter starts a new line."), prefs.enterToSend, !busy("enter")) {
            set("enter", TeamPrefs.Requests.enterToSend(it))
        }

        SectionTitle(tr("新 Bot 默认模型", "Default model for new Bots"))
        ValueRow(tr("模型", "Model"), BotManage.modelName(state, prefs.defaultBotModel) ?: tr("跟随 DSH 默认", "DSH default"), enabled = !busy("model")) { pickModel = true }

        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advancedOpen = !advancedOpen }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(tr("高级", "Advanced"), style = MaterialTheme.typography.titleMedium)
                if (!advancedOpen) {
                    Text(
                        tr("记忆 · 后台工作", "Memory · Background work"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = if (advancedOpen) "▾" else "▸",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }

        if (advancedOpen) {
            SectionTitle(tr("记忆", "Memory"))
            if (!memoryEnabled) {
                Text(
                    tr("当前团队已关闭记忆功能，以下配置将在开启记忆后生效。", "Team memory is currently turned off; these settings apply when memory is enabled."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            SwitchRow(
                label = tr("自动带上相关记忆", "Bring up related memory"),
                hint = if (prefs.memoryAutoRecall) {
                    tr("对话时自动检索并带上相关记忆。", "Automatically bring up related memory in chats.")
                } else {
                    tr("已关闭自动记忆召回。", "Automatic memory recall is off.")
                },
                on = prefs.memoryAutoRecall,
                enabled = !busy("memoryAutoRecall") && !resetBusy,
            ) { on ->
                set("memoryAutoRecall", TeamPrefs.Requests.memoryAutoRecall(on))
            }
            for (scope in TeamPrefs.MemoryScope.entries) {
                val saved = prefs.memoryLimits.of(scope)
                PrefsIntField(
                    label = TeamPrefs.memoryScopeLabel(scope),
                    hint = tr(
                        "默认 ${scope.defaultSize} 字符（范围 ${TeamPrefs.MEMORY_LIMIT_MIN}–${TeamPrefs.MEMORY_LIMIT_MAX}）。",
                        "Default ${scope.defaultSize} chars (range ${TeamPrefs.MEMORY_LIMIT_MIN}–${TeamPrefs.MEMORY_LIMIT_MAX}).",
                    ),
                    saved = saved,
                    resetGen = resetGen,
                    busy = busy("memoryLimit:${scope.key}") || resetBusy,
                    parse = TeamPrefs::parseMemoryLimit,
                    onSave = { value -> set("memoryLimit:${scope.key}", TeamPrefs.Requests.memoryLimit(scope, value)) },
                )
            }

            SectionTitle(tr("后台工作", "Background work"))
            PrefsIntField(
                label = tr("命令转后台（秒）", "Background commands after (seconds)"),
                hint = tr(
                    "默认 ${TeamPrefs.DEFAULT_BACKGROUND_AFTER_SEC} 秒（范围 ${TeamPrefs.BACKGROUND_AFTER_MIN_SEC}–${TeamPrefs.BACKGROUND_AFTER_MAX_SEC}；0 表示立即转后台）。",
                    "Default ${TeamPrefs.DEFAULT_BACKGROUND_AFTER_SEC}s (range ${TeamPrefs.BACKGROUND_AFTER_MIN_SEC}–${TeamPrefs.BACKGROUND_AFTER_MAX_SEC}; 0 backgrounds immediately).",
                ),
                saved = prefs.backgroundAfterSec,
                resetGen = resetGen,
                busy = busy("backgroundAfterSec") || resetBusy,
                parse = TeamPrefs::parseBackgroundAfterSec,
                onSave = { value -> set("backgroundAfterSec", TeamPrefs.Requests.backgroundAfterSec(value)) },
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                OutlinedButton(
                    enabled = prefs.hasCustomAdvanced && !anyAdvBusy,
                    onClick = { set("resetAdvanced", TeamPrefs.Requests.resetAdvanced()) { resetGen++ } },
                ) {
                    Text(tr("恢复默认", "Restore defaults"))
                }
                Text(
                    tr("仅还原上述高级项，不影响外观、宽度与默认模型。", "Resets only the advanced settings above."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        }
    }
    if (pickModel) {
        val groups = BotManage.models(state)
        val options = mutableListOf<Triple<String?, String, String?>>(Triple(null, tr("跟随 DSH 默认", "DSH default"), null))
        val headers = mutableMapOf<Int, String>()
        for (g in groups) {
            // 本地 Agent 不能作为默认模型（服务端拒绝），不列出
            val models = g.models.filter { BotManage.engineOf(it.ref) == "model" }
            if (models.isEmpty()) continue
            headers[options.size] = g.providerName
            for (m in models) options += Triple(m.ref, m.name, null)
        }
        PickerDialog(tr("新 Bot 默认模型", "Default model for new Bots"), options, prefs.defaultBotModel, headers,
            onPick = { ref -> pickModel = false; if (ref != prefs.defaultBotModel) set("model", TeamPrefs.Requests.defaultBotModel(ref)) },
            onDismiss = { pickModel = false })
    }
}

/** 高级数值项输入行：本地整数范围校验，非法输入即时提示且不可保存，仅在服务端该项值变化或恢复默认时重置草稿。 */
@Composable
private fun PrefsIntField(
    label: String,
    hint: String,
    saved: Int,
    resetGen: Int,
    busy: Boolean,
    parse: (String) -> TeamPrefs.NumberParse,
    onSave: (Int) -> Unit,
) {
    var draft by remember(saved, resetGen) { mutableStateOf(saved.toString()) }
    val parsed = parse(draft)
    val errorText = (parsed as? TeamPrefs.NumberParse.Invalid)?.message
    val parsedInt = (parsed as? TeamPrefs.NumberParse.Valid)?.value
    val dirty = draft != saved.toString()
    val canSave = parsedInt != null && parsedInt != saved && !busy
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(12) },
            label = { Text(label) },
            singleLine = true,
            enabled = !busy,
            isError = errorText != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                Text(errorText ?: hint)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (dirty) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(enabled = !busy, onClick = { draft = saved.toString() }) {
                    Text(tr("还原", "Revert"))
                }
                TextButton(enabled = canSave, onClick = { if (parsedInt != null) onSave(parsedInt) }) {
                    Text(tr("保存", "Save"))
                }
            }
        }
    }
}
