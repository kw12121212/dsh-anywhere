package work.keepwriting.dshanywhere

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import work.keepwriting.core.HarnessUrl
import work.keepwriting.core.SetupCheck
import work.keepwriting.core.TunnelStateMachine

/**
 * 首次设置页：无有效配置时显示。一页提供可选的 Tailscale auth key 与 dsh 地址；地址
 * 实时校验（非法禁用按钮并显示原因；空输入只禁用、不显示错误）。点击「开始使用」
 * 先验证（填了 key 先连隧道，再探测地址可达；android-setup-verify），失败在对应输入
 * 下显示原因且不保存；通过才保存并进入主页面。key 不回显，错误文案不含 token 与 key。
 */
@Composable
fun FirstRunScreen(graph: AppGraph, onComplete: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf<SetupCheck.Stage?>(null) }
    var failure by remember { mutableStateOf<SetupCheck.Result?>(null) }
    val errorCategory = remember(input) { HarnessUrl.errorCategory(input) }
    val inputPresent = input.isNotBlank()
    val tunnel = graph.tunnel
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.first_run_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.first_run_welcome), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            if (graph.configState == AppGraph.ConfigState.InvalidConfig) {
                Text(
                    stringResource(R.string.first_run_invalid_config),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
            }

            Column(modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)) {
                Text(stringResource(R.string.first_run_tunnel_section), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                if (tunnel.state == TunnelStateMachine.State.CONNECTED) {
                    Text(
                        stringResource(R.string.first_run_tunnel_connected, tunnel.nodeName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = {
                            keyInput = it
                            failure = null
                        },
                        label = { Text(stringResource(R.string.first_run_tunnel_label)) },
                        // key 不回显（密码型），也不进任何日志。
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = failure is SetupCheck.Result.MissingKey || failure is SetupCheck.Result.TunnelFailed,
                        enabled = !verifying,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    when (val f = failure) {
                        SetupCheck.Result.MissingKey -> ErrorLine(stringResource(R.string.verify_missing_key))
                        is SetupCheck.Result.TunnelFailed -> {
                            ErrorLine(tunnelStateText(f.state))
                            if (tunnel.failureReason.isNotEmpty()) ErrorLine(tunnel.failureReason)
                        }
                        else -> {}
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        saveError = false
                        failure = null
                    },
                    label = { Text(stringResource(R.string.first_run_hint)) },
                    isError = (inputPresent && errorCategory != null) || failure is SetupCheck.Result.Unreachable,
                    enabled = !verifying,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (inputPresent && errorCategory != null) {
                    ErrorLine(stringResource(categoryMessage(errorCategory)))
                }
                (failure as? SetupCheck.Result.Unreachable)?.let { f ->
                    ErrorLine(stringResource(reasonMessage(f.reason)))
                    ErrorLine(stringResource(verifyHint(input)))
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    // 同一帧内连点：重组禁用按钮之前只受理第一次。
                    if (verifying) return@Button
                    failure = null
                    saveError = false
                    verifying = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            graph.verifySetup(input, keyInput) { stage = it }
                        }
                        verifying = false
                        stage = null
                        if (result is SetupCheck.Result.Ok) {
                            try {
                                graph.save(input)
                                onComplete()
                            } catch (_: Exception) {
                                // 校验已通过，只可能是写入失败。
                                saveError = true
                            }
                        } else {
                            failure = result
                        }
                    }
                },
                enabled = errorCategory == null && inputPresent && !verifying,
            ) {
                Text(stringResource(R.string.first_run_button))
            }
            if (verifying) {
                Spacer(Modifier.height(12.dp))
                VerifyProgress(stage)
            }
            if (saveError) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.save_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** 验证进度：转圈 + 当前阶段（连接 Tailscale / 检查地址）。 */
@Composable
internal fun VerifyProgress(stage: SetupCheck.Stage?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            stringResource(
                when (stage) {
                    SetupCheck.Stage.TUNNEL -> R.string.verify_stage_tunnel
                    SetupCheck.Stage.PROBE -> R.string.verify_stage_probe
                    null -> R.string.settings_verifying
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun ErrorLine(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

/** 地址不可达时的检查提示：tailnet 地址额外提醒确认主机在 tailnet 中在线。 */
internal fun verifyHint(rawUrl: String): Int {
    val clean = try {
        HarnessUrl.normalize(rawUrl)
    } catch (_: HarnessUrl.ValidationError) {
        return R.string.verify_hint_address
    }
    return when (SetupCheck.errorHint(clean)) {
        SetupCheck.Hint.CHECK_TAILSCALE -> R.string.verify_hint_tailscale
        SetupCheck.Hint.CHECK_ADDRESS -> R.string.verify_hint_address
    }
}

/** 把 core 的错误类别映射到界面文案（固定文本，不回显输入）。 */
internal fun categoryMessage(category: HarnessUrl.ErrorCategory): Int = when (category) {
    HarnessUrl.ErrorCategory.EMPTY -> R.string.error_empty
    HarnessUrl.ErrorCategory.MALFORMED -> R.string.error_malformed
    HarnessUrl.ErrorCategory.NOT_ABSOLUTE -> R.string.error_not_absolute
    HarnessUrl.ErrorCategory.UNSUPPORTED_SCHEME -> R.string.error_unsupported_scheme
}
