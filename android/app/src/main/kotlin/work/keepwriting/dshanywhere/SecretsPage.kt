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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import work.keepwriting.core.Json
import work.keepwriting.core.SecretsSettings
import java.time.ZoneId

/*
 * 密钥设置页（dsbot-secrets-settings）：
 * 1) 落盘加密状态卡（state.secretStore 存在时显示）：明文/已加密/已锁定状态，口令加密、解锁与二次确认解密（含锁定恢复路径）；
 * 2) 密钥列表（state.secrets）：仅显示名称、用途、作用 Bot 与时间，绝不显示值；支持新增（secret-set）、
 *    展开编辑作用 Bot 与用途（secret-update）、替换值（secret-set）与二次确认删除（secret-delete）。
 * 所有敏感输入共用 SecretField（不回显、密码键盘、禁自动更正）并在表单显示期间启用 SecureWhileShown（FLAG_SECURE）。
 * 值与口令只进请求体，不进日志、诊断上报、剪贴板与持久存储。
 */

private enum class StoreForm { ENCRYPT, DECRYPT }

@Composable
private fun SecretNote(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
fun SecretsPage(state: Json.Value.Object?, api: ManageApi) {
    val entries = remember(state) { SecretsSettings.entries(state) }
    val store = remember(state) { SecretsSettings.storeStatus(state) }
    val bots = remember(state) { SecretsSettings.bots(state) }
    val readOnly = remember(state) { SecretsSettings.readOnlyReason(state) }
    val zone = remember { ZoneId.systemDefault() }
    val now = remember(state) { System.currentTimeMillis() }

    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var openName by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<SecretsSettings.Entry?>(null) }

    fun act(key: String, endpoint: String, payload: String, onOk: (Json.Value) -> Unit = {}) {
        error = null
        info = null
        api.action(key, endpoint, payload, onOk) { error = it }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Text(
            tr("密钥", "Secrets"),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
        SecretNote(
            tr(
                "Bot 看不到密钥值：在终端中以 \$DSH_SECRET_名称 使用，发送给模型前所有值都会替换为 [secret:名称]。",
                "Bots never see the values: they use them as \$DSH_SECRET_NAME in their shell, and every value is replaced by [secret:NAME] before going to a model.",
            ),
        )
        if (entries == null) {
            Text(
                tr("此远端不支持密钥管理。", "This server does not offer secrets."),
                modifier = Modifier.padding(16.dp),
            )
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
        info?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (store != null) {
            SecretStoreCard(
                store = store,
                busy = api.inFlight.any { it.startsWith("secret:store:") },
                onAction = { op, endpoint, payload, onOk -> act("secret:store:$op", endpoint, payload, onOk) },
                onNotice = { info = it },
            )
        }

        SectionTitle(tr("已保存的密钥（${entries.size}）", "Secrets · ${entries.size}"))
        if (!adding) {
            TextButton(
                onClick = { error = null; info = null; adding = true },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(tr("＋ 添加密钥", "+ Add a key"))
            }
        } else {
            AddSecretForm(
                busy = "secret:add" in api.inFlight,
                onCancel = { adding = false },
                onSave = { name, purpose, value, clear ->
                    act("secret:add", "secret-set", SecretsSettings.Requests.setNew(name, value, purpose)) {
                        clear()
                        adding = false
                        info = tr("已保存密钥 ${SecretsSettings.normalizeName(name)}。", "Saved ${SecretsSettings.normalizeName(name)}.")
                    }
                },
            )
        }

        if (entries.isEmpty() && !adding) {
            SecretNote(
                tr(
                    "还没有密钥。Bot 需要密钥时会在聊天中发出请求卡，也可以在这里提前添加。",
                    "No keys yet. When a Bot needs one, it shows a card in its chat, or you can add one here.",
                ),
            )
        }

        for (entry in entries) {
            val isOpen = openName == entry.name
            val rowBusy = api.inFlight.any { it.startsWith("secret:item:${entry.name}:") }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openName = if (isOpen) null else entry.name }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Text(
                            SecretsSettings.scopeSummary(entry.scope, bots),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                if (entry.purpose.isNotBlank()) {
                    Text(
                        entry.purpose,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    SecretsSettings.metaLine(entry, bots, now, zone),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (isOpen) {
                SecretDetailEditor(
                    entry = entry,
                    bots = bots,
                    busy = rowBusy,
                    onSaveScope = { scope ->
                        act("secret:item:${entry.name}:scope", "secret-update", SecretsSettings.Requests.update(entry.name, scope = scope)) {
                            info = tr("已更新 ${entry.name} 的使用范围。", "Updated access for ${entry.name}.")
                        }
                    },
                    onSavePurpose = { purpose ->
                        act("secret:item:${entry.name}:purpose", "secret-update", SecretsSettings.Requests.update(entry.name, purpose = purpose)) {
                            info = tr("已更新 ${entry.name} 的用途。", "Updated purpose for ${entry.name}.")
                        }
                    },
                    onReplaceValue = { value, onReplaced ->
                        act("secret:item:${entry.name}:value", "secret-set", SecretsSettings.Requests.replaceValue(entry.name, value)) {
                            onReplaced()
                            info = tr("已替换 ${entry.name} 的值，Bot 下次运行命令时生效。", "Replaced ${entry.name}. Bots get the new value on their next command.")
                        }
                    },
                    onDelete = { deleting = entry },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
        Spacer(Modifier.height(24.dp))
    }

    deleting?.let { target ->
        ConfirmDialog(
            title = tr("删除密钥「${target.name}」？", "Delete \"${target.name}\"?"),
            text = tr("删除后所有 Bot 将立即失去此密钥，无法恢复。", "Bots lose this key at once. This cannot be undone."),
            confirm = tr("删除", "Delete"),
            onConfirm = {
                act("secret:item:${target.name}:delete", "secret-delete", SecretsSettings.Requests.delete(target.name)) {
                    if (openName == target.name) openName = null
                    info = tr("已删除密钥 ${target.name}。", "Deleted ${target.name}.")
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

/** 落盘加密状态卡：明文加密、锁定态解锁、已加密/锁定态二次确认解密。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecretStoreCard(
    store: SecretsSettings.StoreStatus,
    busy: Boolean,
    onAction: (op: String, endpoint: String, payload: String, onOk: (Json.Value) -> Unit) -> Unit,
    onNotice: (String) -> Unit,
) {
    var form by remember(store.mode, store.locked) { mutableStateOf<StoreForm?>(null) }
    var passphrase by remember(store.mode, store.locked, form) { mutableStateOf("") }
    var confirm by remember(store.mode, store.locked, form) { mutableStateOf("") }
    var confirmDecrypt by remember(store.mode, store.locked, form) { mutableStateOf(false) }

    val reset = {
        passphrase = ""
        confirm = ""
        form = null
        confirmDecrypt = false
    }

    // 仅在存在口令输入框时开启 FLAG_SECURE，列表闲置时不常开以免影响截图
    if (store.locked || form != null) {
        SecureWhileShown()
    }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text(tr("落盘加密", "Encryption at rest"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    SecretsSettings.storeModeLabel(store),
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        store.locked -> MaterialTheme.colorScheme.error
                        store.isEncrypted -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                SecretsSettings.storeDescription(store),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            when {
                store.locked -> {
                    val decryptMode = form == StoreForm.DECRYPT
                    val problem = SecretsSettings.passphraseProblem(passphrase)
                    SecretField(
                        value = passphrase,
                        onChange = { passphrase = it },
                        label = tr("口令（至少 8 个字符）", "Passphrase (at least 8 characters)"),
                        enabled = !busy,
                    )
                    if (passphrase.isNotEmpty() && problem != null) SecretNote(problem, error = true)
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!decryptMode) {
                            Button(
                                enabled = problem == null && !busy,
                                onClick = {
                                    onAction("unlock", "secret-unlock", SecretsSettings.Requests.unlock(passphrase)) {
                                        reset()
                                        onNotice(tr("已为当前进程解锁密钥存储。", "Secrets store unlocked for this process."))
                                    }
                                },
                            ) { Text(if (busy) tr("解锁中…", "Unlocking…") else tr("解锁", "Unlock")) }
                            TextButton(enabled = !busy, onClick = { form = StoreForm.DECRYPT }) {
                                Text(tr("改为解密为明文", "Decrypt to plaintext"))
                            }
                        } else {
                            OutlinedButton(
                                enabled = problem == null && !busy,
                                onClick = { confirmDecrypt = true },
                            ) { Text(tr("解密为明文", "Decrypt to plaintext")) }
                            TextButton(enabled = !busy, onClick = { form = null }) { Text(tr("返回解锁", "Back to unlock")) }
                        }
                    }
                }
                store.isEncrypted -> {
                    if (form == StoreForm.DECRYPT) {
                        val problem = SecretsSettings.passphraseProblem(passphrase)
                        SecretField(
                            value = passphrase,
                            onChange = { passphrase = it },
                            label = tr("输入当前口令", "Current passphrase"),
                            enabled = !busy,
                        )
                        if (passphrase.isNotEmpty() && problem != null) SecretNote(problem, error = true)
                        FlowRow(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(enabled = problem == null && !busy, onClick = { confirmDecrypt = true }) {
                                Text(tr("关闭加密", "Turn off encryption"))
                            }
                            TextButton(enabled = !busy, onClick = reset) { Text(tr("取消", "Cancel")) }
                        }
                    } else {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { form = StoreForm.DECRYPT },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        ) { Text(tr("关闭加密", "Turn off encryption")) }
                    }
                }
                else -> {
                    if (form == StoreForm.ENCRYPT) {
                        val problem = SecretsSettings.encryptProblem(passphrase, confirm)
                        SecretField(
                            value = passphrase,
                            onChange = { passphrase = it },
                            label = tr("口令（至少 8 个字符）", "Passphrase (at least 8 characters)"),
                            enabled = !busy,
                        )
                        SecretField(
                            value = confirm,
                            onChange = { confirm = it },
                            label = tr("再次输入口令", "Repeat the passphrase"),
                            enabled = !busy,
                        )
                        if ((passphrase.isNotEmpty() || confirm.isNotEmpty()) && problem != null) {
                            SecretNote(problem, error = true)
                        }
                        FlowRow(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                enabled = problem == null && !busy,
                                onClick = {
                                    onAction("encrypt", "secret-encrypt", SecretsSettings.Requests.encrypt(passphrase)) {
                                        reset()
                                        onNotice(tr("已启用落盘加密。", "Encryption at rest turned on."))
                                    }
                                },
                            ) { Text(if (busy) tr("加密中…", "Encrypting…") else tr("开启加密", "Turn on encryption")) }
                            TextButton(enabled = !busy, onClick = reset) { Text(tr("取消", "Cancel")) }
                        }
                    } else {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { form = StoreForm.ENCRYPT },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        ) { Text(tr("使用口令加密存储", "Encrypt keys at rest")) }
                    }
                }
            }
        }
    }

    if (confirmDecrypt) {
        ConfirmDialog(
            title = tr("将密钥解密为明文？", "Decrypt secrets to plaintext?"),
            text = tr(
                "验证口令后将把磁盘上的密钥文件改写为明文格式，不再需要口令即可读取。",
                "After verifying your passphrase, the secrets file on disk will be rewritten in plaintext.",
            ),
            confirm = tr("确认解密", "Decrypt"),
            onConfirm = {
                if (SecretsSettings.passphraseProblem(passphrase) != null) return@ConfirmDialog
                onAction("decrypt", "secret-decrypt", SecretsSettings.Requests.decrypt(passphrase)) {
                    reset()
                    onNotice(tr("已解密为明文存储。", "Secrets store decrypted to plaintext."))
                }
            },
            onDismiss = { confirmDecrypt = false },
        )
    }
}

/** 新增密钥表单（不回显、开启防截屏）。 */
@Composable
private fun AddSecretForm(
    busy: Boolean,
    onCancel: () -> Unit,
    onSave: (name: String, purpose: String, value: String, clear: () -> Unit) -> Unit,
) {
    SecureWhileShown()
    var name by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

    val nameProblem = SecretsSettings.nameProblem(name)
    val valueProblem = SecretsSettings.valueProblem(value)
    val ready = nameProblem == null && valueProblem == null

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(80) },
            label = { Text(tr("密钥名称（如 GITHUB_TOKEN）", "Key name (e.g. GITHUB_TOKEN)")) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (name.isNotBlank()) {
            val normalized = SecretsSettings.normalizeName(name)
            if (nameProblem != null) SecretNote(nameProblem, error = true)
            else if (normalized != name.trim()) SecretNote(tr("将保存为：$normalized", "Will be saved as: $normalized"))
        }
        OutlinedTextField(
            value = purpose,
            onValueChange = { purpose = it.take(SecretsSettings.PURPOSE_MAX) },
            label = { Text(tr("用途（可选）", "What it is for (optional)")) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SecretField(
            value = value,
            onChange = { value = it },
            label = tr("密钥值（至少 4 个字符，不回显）", "Value (at least 4 characters, hidden)"),
            enabled = !busy,
        )
        if (value.isNotEmpty() && valueProblem != null) SecretNote(valueProblem, error = true)
        SecretNote(tr("新建密钥默认对所有 Bot 可用，保存后可按需收窄范围。", "New keys are for all Bots; narrow it down afterwards."))
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                enabled = ready && !busy,
                onClick = { onSave(name, purpose, value) { name = ""; purpose = ""; value = "" } },
            ) { Text(if (busy) tr("保存中…", "Saving…") else tr("保存密钥", "Save key")) }
            TextButton(enabled = !busy, onClick = { value = ""; onCancel() }) { Text(tr("取消", "Cancel")) }
        }
    }
}

/** 展开的密钥编辑区：作用 Bot、用途、替换值（不回显）与删除。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecretDetailEditor(
    entry: SecretsSettings.Entry,
    bots: List<SecretsSettings.BotRef>,
    busy: Boolean,
    onSaveScope: (SecretsSettings.Scope) -> Unit,
    onSavePurpose: (String) -> Unit,
    onReplaceValue: (String, () -> Unit) -> Unit,
    onDelete: () -> Unit,
) {
    SecureWhileShown()
    var allMode by remember(entry.name, entry.scope) { mutableStateOf(entry.scope is SecretsSettings.Scope.All) }
    var picked by remember(entry.name, entry.scope) {
        mutableStateOf((entry.scope as? SecretsSettings.Scope.Bots)?.botIds?.toSet() ?: emptySet())
    }
    var purpose by remember(entry.name, entry.purpose) { mutableStateOf(entry.purpose) }
    var newValue by remember(entry.name) { mutableStateOf("") }

    val scopeUnchanged = SecretsSettings.sameScope(entry.scope, allMode, picked)
    val purposeUnchanged = purpose.trim() == entry.purpose.trim()
    val valueProblem = SecretsSettings.valueProblem(newValue)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(
                tr("谁可以使用", "Who may use it"),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = allMode, enabled = !busy, onClick = { allMode = true }, label = { Text(tr("所有 Bot", "All Bots")) })
                FilterChip(selected = !allMode, enabled = !busy, onClick = { allMode = false }, label = { Text(tr("指定 Bot", "Chosen Bots")) })
            }
            if (!allMode) {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (bot in bots) {
                        val on = bot.id in picked
                        FilterChip(
                            selected = on,
                            enabled = !busy,
                            onClick = { picked = if (on) picked - bot.id else picked + bot.id },
                            label = { Text(bot.name) },
                        )
                    }
                }
            }
            OutlinedButton(
                enabled = !scopeUnchanged && !busy,
                onClick = { onSaveScope(if (allMode) SecretsSettings.Scope.All else SecretsSettings.Scope.Bots(picked.toList())) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Text(tr("保存权限", "Save access")) }

            Text(
                tr("用途", "What it is for"),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
            )
            OutlinedTextField(
                value = purpose,
                onValueChange = { purpose = it.take(SecretsSettings.PURPOSE_MAX) },
                label = { Text(tr("用途说明", "Purpose")) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedButton(
                enabled = !purposeUnchanged && !busy,
                onClick = { onSavePurpose(purpose) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Text(tr("保存用途", "Save")) }

            Text(
                tr("替换密钥值", "Replace the value"),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
            )
            SecretField(
                value = newValue,
                onChange = { newValue = it },
                label = tr("输入新值（不回显，至少 4 个字符）", "New value (hidden, at least 4 characters)"),
                enabled = !busy,
            )
            if (newValue.isNotEmpty() && valueProblem != null) SecretNote(valueProblem, error = true)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    enabled = valueProblem == null && !busy,
                    onClick = { onReplaceValue(newValue) { newValue = "" } },
                ) { Text(tr("替换值", "Replace")) }
                TextButton(enabled = !busy, onClick = onDelete) {
                    Text(tr("删除", "Delete"), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
