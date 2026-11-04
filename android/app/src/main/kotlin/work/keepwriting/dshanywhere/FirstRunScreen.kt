package work.keepwriting.dshanywhere

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import work.keepwriting.core.HarnessUrl

/**
 * 首次设置页：无有效配置时显示。实时校验（非法禁用按钮并显示原因；空输入只禁用、
 * 不显示错误），保存成功进入主页面；保存失败显示固定错误并停留（不含 token）。
 */
@Composable
fun FirstRunScreen(graph: AppGraph, onComplete: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf(false) }
    val errorCategory = remember(input) { HarnessUrl.errorCategory(input) }
    val inputPresent = input.isNotBlank()

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
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    saveError = false
                },
                label = { Text(stringResource(R.string.first_run_hint)) },
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
            Button(
                onClick = {
                    try {
                        graph.save(input)
                        onComplete()
                    } catch (_: Exception) {
                        // 校验失败不会到这里（按钮只在合法时可用），只可能是写入失败。
                        saveError = true
                    }
                },
                enabled = errorCategory == null && inputPresent,
            ) {
                Text(stringResource(R.string.first_run_button))
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

/** 把 core 的错误类别映射到界面文案（固定文本，不回显输入）。 */
internal fun categoryMessage(category: HarnessUrl.ErrorCategory): Int = when (category) {
    HarnessUrl.ErrorCategory.EMPTY -> R.string.error_empty
    HarnessUrl.ErrorCategory.MALFORMED -> R.string.error_malformed
    HarnessUrl.ErrorCategory.NOT_ABSOLUTE -> R.string.error_not_absolute
    HarnessUrl.ErrorCategory.UNSUPPORTED_SCHEME -> R.string.error_unsupported_scheme
}
