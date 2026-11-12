package work.keepwriting.dshanywhere

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import work.keepwriting.core.Cards

/**
 * 交互卡片渲染（dsbot-interactive-cards）：按 Cards.Card 类型渲染交互组件并经
 * answer 闭包作答（/api/bot 写端点，payload 由 core 构造）；作答成功由调用方消隐
 * 卡片。Bot 内容不可信的降级已在 core 完成（ReadOnly 只读文本）。
 */
@Composable
fun CardView(card: Cards.Card, onAnswer: (endpoint: String, payloadJson: String) -> Unit, onNotice: (String) -> Unit = { _ -> }) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            when (card) {
                is Cards.Card.ReadOnly -> {
                    card.title?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    Text(card.body, style = MaterialTheme.typography.bodySmall, maxLines = 6)
                }
                is Cards.Card.Approval -> {
                    Text(card.title ?: "审批", style = MaterialTheme.typography.titleSmall)
                    card.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAnswer("approval-answer", Cards.approvalAnswer(card.callId, true)) }) { Text("同意") }
                        OutlinedButton(onClick = { onAnswer("approval-answer", Cards.approvalAnswer(card.callId, false)) }) { Text("驳回") }
                    }
                }
                is Cards.Card.Question -> {
                    Text(card.title ?: "提问", style = MaterialTheme.typography.titleSmall)
                    card.options.forEachIndexed { i, opt ->
                        TextButton(onClick = { onAnswer("answer-question", Cards.answerQuestion(card.callId, i)) }) {
                            Column {
                                Text(opt.label)
                                opt.description?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                    TextButton(onClick = { onAnswer("dismiss-question", Cards.dismiss(card.callId)) }) { Text("忽略") }
                }
                is Cards.Card.View -> {
                    Text(card.title ?: "视图", style = MaterialTheme.typography.titleSmall)
                    val inputs = remember(card.callId) { mutableStateMapOf<String, String>() }
                    card.sections.forEach { section ->
                        when (section) {
                            is Cards.Card.View.Section.Text -> Text(section.text, style = MaterialTheme.typography.bodySmall)
                            is Cards.Card.View.Section.Metric -> Row {
                                Text("${section.label}: ", style = MaterialTheme.typography.labelMedium)
                                Text(section.value, style = MaterialTheme.typography.labelMedium)
                            }
                            is Cards.Card.View.Section.Table -> Column {
                                Row { section.header.forEach { Text(it, modifier = Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelSmall) } }
                                section.rows.forEach { row ->
                                    Row { row.forEach { Text(it, modifier = Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodySmall) } }
                                }
                            }
                            is Cards.Card.View.Section.Formula -> OutlinedTextField(
                                value = inputs[section.name] ?: "",
                                onValueChange = { inputs[section.name] = it },
                                label = { Text(section.name) },
                                placeholder = { section.placeholder?.let { Text(it) } },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            is Cards.Card.View.Section.Progress -> Column {
                                section.label?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                LinearProgressIndicator(
                                    progress = { section.percent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            is Cards.Card.View.Section.Choice -> {
                                var picked by remember(section.name) { mutableStateOf(-1) }
                                Text(section.name, style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    section.options.forEachIndexed { i, o ->
                                        OutlinedButton(onClick = { picked = i; inputs[section.name] = section.options[i] }) {
                                            Text(if (picked == i) "● $o" else "○ $o", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                            is Cards.Card.View.Section.Confirm -> TextButton(onClick = {
                                onAnswer("view-reply", Cards.viewReply(card.callId, "{}"))
                            }) { Text(section.label) }
                        }
                    }
                    val hasInput = card.sections.any { it is Cards.Card.View.Section.Formula || it is Cards.Card.View.Section.Choice }
                    if (hasInput) {
                        Button(onClick = {
                            val values = inputs.entries.joinToString(",", "{", "}") { (k, v) ->
                                work.keepwriting.core.JsonString.of(k) + ":" + work.keepwriting.core.JsonString.of(v)
                            }
                            onAnswer("view-reply", Cards.viewReply(card.callId, values))
                        }) { Text("提交") }
                    }
                    TextButton(onClick = { onAnswer("dismiss-view", Cards.dismiss(card.callId)) }) { Text("不再显示") }
                }
                is Cards.Card.SecretRequest -> {
                    Text(card.title ?: "密钥请求", style = MaterialTheme.typography.titleSmall)
                    card.hint?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    var value by remember(card.callId) { mutableStateOf("") }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("输入密钥值（不回显）") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (value.length >= 4) onAnswer("secret-set", Cards.secretSet(card.callId, value)) else onNotice("密钥至少 4 个字符") }) { Text("提交") }
                        OutlinedButton(onClick = { onAnswer("secret-allow", Cards.dismiss(card.callId)) }) { Text("允许使用现有") }
                        TextButton(onClick = { onAnswer("secret-cancel", Cards.dismiss(card.callId)) }) { Text("取消") }
                    }
                }
                is Cards.Card.Learning -> {
                    Text(card.title ?: "学习问答", style = MaterialTheme.typography.titleSmall)
                    card.options.forEachIndexed { i, o ->
                        TextButton(onClick = { onAnswer("learning-answer", Cards.learningAnswer(card.callId, i)) }) { Text(o) }
                    }
                }
                is Cards.Card.BrowserHandoff -> {
                    Text(card.title ?: "浏览器交接", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAnswer("browser-give-back", Cards.browserGiveBack(card.callId, "done")) }) { Text("完成") }
                        OutlinedButton(onClick = { onAnswer("browser-give-back", Cards.browserGiveBack(card.callId, "skip")) }) { Text("跳过") }
                    }
                }
                is Cards.Card.SandboxHtml -> SandboxHtmlCard(card)
            }
        }
    }
}

/** 沙箱 HTML 卡：WebView 隔离——禁网络/禁导航/无 JavascriptInterface，loadDataOnly。 */
@Composable
private fun SandboxHtmlCard(card: Cards.Card.SandboxHtml) {
    Text(card.title ?: "HTML 卡片", style = MaterialTheme.typography.titleSmall)
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false // 沙箱：禁脚本执行
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.loadsImagesAutomatically = false // 连 data: URI 静态图一并禁（沙箱从严：只留文本+CSS 的取舍）
                settings.blockNetworkLoads = true // 禁网络
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean = true // 禁导航
                }
                // about:blank 基址 + loadDataOnly：无源、无网络解析
                loadDataWithBaseURL("about:blank", card.html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
