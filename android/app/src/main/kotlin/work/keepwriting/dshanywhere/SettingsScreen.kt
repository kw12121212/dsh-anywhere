package work.keepwriting.dshanywhere

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import work.keepwriting.core.HarnessUrl
import work.keepwriting.core.TunnelStateMachine

/**
 * 设置页：查看与修改 harness URL。实时校验（非法禁用保存并显示原因）；保存成功返回
 * 主页面并把原始地址交给主页面加载一次；取消或返回不修改配置。预留 Tailscale 卡片
 * 位置（android-tstunnel 填充）。
 */
@Composable
fun SettingsScreen(graph: AppGraph, onSaved: () -> Unit, onCancelled: () -> Unit) {
    var input by remember { mutableStateOf(graph.cleanUrl.orEmpty()) }
    var saveError by remember { mutableStateOf(false) }
    val errorCategory = remember(input) { HarnessUrl.errorCategory(input) }
    val inputPresent = input.isNotBlank()
    val tunnel = graph.tunnel

    // App 打开设置页时按需启动隧道（未配置时不触碰 tsnet）。
    LaunchedEffect(Unit) { tunnel.ensureUp() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    saveError = false
                },
                label = { Text(stringResource(R.string.settings_url_label)) },
                isError = inputPresent && errorCategory != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp),
                singleLine = true,
            )
            if (inputPresent && errorCategory != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(categoryMessage(errorCategory)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancelled) {
                    Text(stringResource(R.string.settings_cancel))
                }
                Button(
                    onClick = {
                        try {
                            graph.save(input)
                            onSaved()
                        } catch (_: Exception) {
                            saveError = true
                        }
                    },
                    enabled = errorCategory == null && inputPresent,
                ) {
                    Text(stringResource(R.string.settings_save))
                }
            }
            if (saveError) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.save_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(32.dp))
            TunnelCard(tunnel)
            Spacer(Modifier.height(24.dp))
            UpdateCard(graph.updates)
        }
    }
}

/** 应用更新卡片：当前版本、检查更新、新版本下载与安装（android-self-update）。 */
@Composable
private fun UpdateCard(updates: UpdateManager) {
    val context = LocalContext.current
    // 通知权限（API 33+）：点击下载时至多请求一次，结果不影响下载（拒绝时设置页仍可手动安装）。
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val currentVersion = try { pm.getPackageInfo(context.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
    Card(modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 480.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.update_current_version, currentVersion),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            when (updates.state) {
                UpdateManager.State.CHECKING -> Text(stringResource(R.string.update_check), style = MaterialTheme.typography.bodyMedium)
                UpdateManager.State.UP_TO_DATE -> Text(stringResource(R.string.update_up_to_date), style = MaterialTheme.typography.bodyMedium)
                UpdateManager.State.AVAILABLE -> {
                    Text(stringResource(R.string.update_available, updates.availableVersion ?: "?"), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                                PackageManager.PERMISSION_GRANTED
                            if (updates.claimNotificationPermissionRequest(granted)) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        updates.download()
                    }) { Text(stringResource(R.string.update_download)) }
                }
                UpdateManager.State.DOWNLOADING -> Text(
                    stringResource(R.string.update_downloading, (updates.downloadProgress * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                UpdateManager.State.READY_TO_INSTALL -> Button(onClick = { updates.promptInstall() }) {
                    Text(stringResource(R.string.update_install))
                }
                UpdateManager.State.FAILED -> Text(
                    stringResource(R.string.update_failed) + "：" + updates.failureReason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                UpdateManager.State.IDLE -> {}
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { updates.check() }, enabled = updates.state != UpdateManager.State.CHECKING) {
                Text(stringResource(R.string.update_check))
            }
        }
    }
}

/** Tailscale 卡片：状态与节点名展示，未配置/需要key/失败时可粘贴 key 连接，可清除。 */
@Composable
private fun TunnelCard(tunnel: TunnelManager) {
    var keyInput by remember { mutableStateOf("") }
    val stateText = when (tunnel.state) {
        TunnelStateMachine.State.NOT_CONFIGURED -> stringResource(R.string.tunnel_state_not_configured)
        TunnelStateMachine.State.CONNECTING -> stringResource(R.string.tunnel_state_connecting)
        TunnelStateMachine.State.CONNECTED -> stringResource(R.string.tunnel_state_connected)
        TunnelStateMachine.State.NEEDS_KEY -> stringResource(R.string.tunnel_state_needs_key)
        TunnelStateMachine.State.FAILED -> stringResource(R.string.tunnel_state_failed)
    }
    Card(modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 480.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.tunnel_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.tunnel_node_label, tunnel.nodeName),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(stateText, style = MaterialTheme.typography.bodyMedium)
            if (tunnel.state == TunnelStateMachine.State.FAILED && tunnel.failureReason.isNotEmpty()) {
                Text(
                    tunnel.failureReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (tunnel.state != TunnelStateMachine.State.CONNECTED) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text(stringResource(R.string.tunnel_key_hint)) },
                    // key 不回显（密码型），也不进任何日志。
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { tunnel.clear(); keyInput = "" },
                        enabled = tunnel.state != TunnelStateMachine.State.NOT_CONFIGURED,
                    ) { Text(stringResource(R.string.tunnel_clear)) }
                    Button(
                        onClick = { tunnel.connectWithKey(keyInput); keyInput = "" },
                        enabled = keyInput.isNotBlank() && tunnel.state != TunnelStateMachine.State.CONNECTING,
                    ) { Text(stringResource(R.string.tunnel_connect)) }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { tunnel.clear() }) { Text(stringResource(R.string.tunnel_clear)) }
            }
        }
    }
}
