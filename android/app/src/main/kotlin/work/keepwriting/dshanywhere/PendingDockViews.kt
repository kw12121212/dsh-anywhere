package work.keepwriting.dshanywhere

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import work.keepwriting.core.LineDiff
import work.keepwriting.core.PendingDock

/**
 * 待处理停靠区（dsbot-pending-dock）：输入框上方的手风琴，一次只展开一项。首次显示时展开第一项并固定下来，
 * 之后新出现更高优先级的项也不打断正在输入的那项；展开项被作答移出后才回落到新的第一项。整体限高为
 * 键盘之外可用高度的约一半，可滚动。作答经 [onAnswer]（控制器 answerCard，回调在 executor 线程），
 * 成功经 [onAnswered] 在主线程记入页面的已作答集合。
 */
@Composable
fun DockArea(
    items: List<PendingDock.Item>,
    onAnswer: (endpoint: String, payloadJson: String, onDone: (Boolean) -> Unit) -> Unit,
    onAnswered: (String) -> Unit,
    onNotice: (String) -> Unit,
) {
    if (items.isEmpty()) return
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    // 可用高度：Compose 视图的实际高度（API ≤34 的 adjustResize 下随键盘缩小）与「屏高 − 键盘 inset」
    // （Android 15+ edge-to-edge 下视图不缩）取小，再取一半
    val imePx = WindowInsets.ime.getBottom(density)
    val screenPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val viewHeightPx by androidx.compose.runtime.produceState(initialValue = view.height, view) {
        val listener = View.OnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ -> value = bottom - top }
        view.addOnLayoutChangeListener(listener)
        awaitDispose { view.removeOnLayoutChangeListener(listener) }
    }
    val availablePx = listOf(screenPx - imePx, viewHeightPx.toFloat()).filter { it > 0f }.minOrNull() ?: screenPx
    val maxHeight = with(density) { (availablePx * 0.5f).coerceAtLeast(120.dp.toPx()).toDp() }
    // pinned：当前展开项 id（首次为第一项）；collapsed：用户主动收起全部
    var pinned by remember { mutableStateOf<String?>(null) }
    var collapsed by remember { mutableStateOf(false) }
    val effective = pinned?.takeIf { id -> items.any { it.id == id } } ?: items.getOrNull(PendingDock.expandedIndex(items))?.id
    androidx.compose.runtime.SideEffect { if (pinned != effective) pinned = effective }
    val expandedId = if (collapsed) null else effective

    /** 作答包装：成功记入已作答并给确认触感，失败给拒绝触感（都投回主线程）。 */
    fun answer(id: String, endpoint: String, payload: String) {
        onAnswer(endpoint, payload) { ok ->
            view.post {
                if (ok) onAnswered(id)
                haptic(view, ok)
            }
        }
    }

    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
            Text(
                tr("待处理 ${items.size} 项", "${items.size} pending"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp),
            )
            for (item in items) {
                androidx.compose.runtime.key(item.id) {
                    val expanded = item.id == expandedId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                if (expanded) {
                                    collapsed = true
                                } else {
                                    pinned = item.id
                                    collapsed = false
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            item.kind.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (expanded) tr("收起", "Collapse") else tr("展开", "Expand"), style = MaterialTheme.typography.labelSmall)
                    }
                    if (expanded) DockBody(item, onNotice) { endpoint, payload -> answer(item.id, endpoint, payload) }
                }
            }
        }
    }
}

/** 展开项的内容：按类型渲染并以 0.3.10 的请求体作答（PendingDock.Requests）。 */
@Composable
private fun DockBody(item: PendingDock.Item, onNotice: (String) -> Unit, answer: (String, String) -> Unit) {
    val R = PendingDock.Requests
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        when (val p = item.payload) {
            is PendingDock.Payload.Approval -> {
                Text(tr("${p.taskTitle?.let { "$it · " } ?: ""}请求使用工具「${p.tool.ifEmpty { "?" }}」", "${p.taskTitle?.let { "$it · " } ?: ""}Wants to use the tool “${p.tool.ifEmpty { "?" }}”"), style = MaterialTheme.typography.titleSmall)
                p.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { answer("approval-answer", R.approval(p.id, true)) }) { Text(tr("允许", "Allow")) }
                    TextButton(onClick = { answer("approval-answer", R.approval(p.id, false)) }) { Text(tr("拒绝", "Deny")) }
                }
            }
            is PendingDock.Payload.Question -> QuestionBody(p, answer)
            is PendingDock.Payload.Secret -> SecretBody(p, answer)
            is PendingDock.Payload.View -> {
                // show_view 卡片原生绘制（dsbot-rich-render）：校验不通过降级只读摘要；解码只在蓝图变化时做
                val decoded = androidx.compose.runtime.remember(p.raw) { work.keepwriting.core.ViewCard.decode(p.sessionId, p.raw) }
                val dismiss = { answer("dismiss-view", R.dismissView(p.sessionId)) }
                when (decoded) {
                    is work.keepwriting.core.ViewCard.Decoded.Ok -> ViewCardView(decoded.blueprint, onAnswer = answer, onDismiss = dismiss, onNotice = onNotice)
                    is work.keepwriting.core.ViewCard.Decoded.Invalid -> ViewCardInvalid(
                        decoded.title ?: p.title,
                        (p.raw?.members?.get("view") as? work.keepwriting.core.Json.Value.Object)?.let { v -> runCatching { work.keepwriting.core.ViewCard.summary(v) }.getOrNull() },
                        dismiss,
                    )
                }
            }
            is PendingDock.Payload.Handoff -> {
                Text(p.note ?: tr("Bot 请你在浏览器里完成一步操作", "The Bot asks you to finish a step in the browser"), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { answer("browser-give-back", R.giveBack(p.botId, p.callId, true)) }) { Text(tr("已完成", "Done")) }
                    TextButton(onClick = { answer("browser-give-back", R.giveBack(p.botId, p.callId, false)) }) { Text(tr("跳过", "Skip")) }
                }
            }
            is PendingDock.Payload.Learning -> LearningCardView(p.proposal) { approve -> answer("learning-answer", R.learning(p.proposal.id, approve)) }
            is PendingDock.Payload.Connector -> ConnectorCardView(
                p.request,
                onCancel = { answer("connector-request-cancel", R.connectorCancel(p.request.id)) },
                onConnect = { onNotice(tr("请在电脑上的网页端完成连接器连接", "Connect the connector in the web app on your computer")) },
            )
        }
    }
}

/** 提问：单选点按即提交；多选勾选后提交；允许自填时可输入后提交；「忽略」关闭提问。 */
@Composable
private fun QuestionBody(p: PendingDock.Payload.Question, answer: (String, String) -> Unit) {
    val R = PendingDock.Requests
    var picked by remember(p.id) { mutableStateOf(setOf<String>()) }
    var custom by remember(p.id) { mutableStateOf("") }
    Text(p.question, style = MaterialTheme.typography.titleSmall)
    p.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    for (opt in p.options) {
        val on = opt.label in picked
        TextButton(onClick = {
            if (p.multiSelect) {
                picked = if (on) picked - opt.label else picked + opt.label
            } else {
                answer("answer-question", R.answerQuestion(p.sessionId, p.id, listOf(opt.label)))
            }
        }) {
            Column {
                Text((if (p.multiSelect) (if (on) "☑ " else "☐ ") else "") + opt.label)
                opt.description?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
    if (p.allowCustom) {
        androidx.compose.material3.OutlinedTextField(
            value = custom,
            onValueChange = { custom = it.take(2000) },
            placeholder = { Text(tr("自己填写", "Your own answer")) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p.multiSelect || p.allowCustom) {
            OutlinedButton(
                onClick = { answer("answer-question", R.answerQuestion(p.sessionId, p.id, picked.toList(), custom)) },
                enabled = picked.isNotEmpty() || custom.isNotBlank(),
            ) { Text(tr("提交", "Submit")) }
        }
        TextButton(onClick = { answer("dismiss-question", R.dismissQuestion(p.sessionId)) }) { Text(tr("忽略", "Ignore")) }
    }
}

/** 密钥请求：填写新值（不回显）或允许使用已保存的同名密钥；取消即拒绝。值不进日志。 */
@Composable
private fun SecretBody(p: PendingDock.Payload.Secret, answer: (String, String) -> Unit) {
    val R = PendingDock.Requests
    var value by remember(p.id) { mutableStateOf("") }
    Text(tr("请求密钥「${p.name}」", "Requests the key “${p.name}”"), style = MaterialTheme.typography.titleSmall)
    p.purpose?.let { Text(tr("用途：$it", "Purpose: $it"), style = MaterialTheme.typography.bodySmall) }
    if (p.kind != "allow") {
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = { Text(tr("输入密钥值（不回显，至少 4 个字符）", "Enter the key (hidden, at least 4 characters)")) },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p.kind == "allow") {
            OutlinedButton(onClick = { answer("secret-allow", R.secretAllow(p.id)) }) { Text(tr("允许使用已保存的", "Allow the saved key")) }
        } else {
            // 上游 cleanValue 去首尾空白后至少 4 个字符（secrets.js MIN_VALUE）
            OutlinedButton(onClick = { answer("secret-set", R.secretSet(p.id, value.trim())) }, enabled = value.trim().length >= 4) { Text(tr("提交", "Submit")) }
        }
        TextButton(onClick = { answer("secret-cancel", R.secretCancel(p.id)) }) { Text(tr("取消", "Cancel")) }
    }
}

private fun haptic(view: View, ok: Boolean) = if (ok) Haptics.confirm(view) else Haptics.reject(view)

/** 学习审批：名称、理由、逐行差异（新增绿/删除红，超过 8 行折叠可展开），同意/拒绝。 */
@Composable
fun LearningCardView(proposal: work.keepwriting.core.LearningProposal, onDecide: (Boolean) -> Unit) {
    var showAll by remember(proposal.id) { mutableStateOf(false) }
    val lines = remember(proposal) { proposal.lines() }
    val folded = if (showAll) LineDiff.Folded(lines, 0) else LineDiff.fold(lines)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        val heading = when {
            proposal.kind == "agents" -> tr("修改 ${proposal.path ?: "AGENTS.md"}", "Edit ${proposal.path ?: "AGENTS.md"}")
            proposal.action == "update" -> tr("更新技能「${proposal.name ?: "?"}」", "Update skill “${proposal.name ?: "?"}”")
            else -> tr("新建技能「${proposal.name ?: "?"}」", "New skill “${proposal.name ?: "?"}”")
        }
        Text(heading, style = MaterialTheme.typography.titleSmall)
        // 技能提案的理由在顶层，AGENTS.md 提案的理由在每条修改上（learning.js reviewAgents）
        val reasons = listOfNotNull(proposal.why?.takeIf { it.isNotBlank() }) + proposal.edits.mapNotNull { it.why?.takeIf { w -> w.isNotBlank() } }
        for (r in reasons.distinct()) Text(tr("理由：$r", "Why: $r"), style = MaterialTheme.typography.bodySmall)
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            for (line in folded.shown) {
                val (prefix, bg) = when (line.op) {
                    LineDiff.Op.ADD -> "+ " to Color(0x3322AA44)
                    LineDiff.Op.DEL -> "- " to Color(0x33DD3333)
                    LineDiff.Op.SAME -> "  " to Color.Transparent
                }
                Text(
                    prefix + line.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().background(bg),
                )
            }
            if (folded.hidden > 0) {
                TextButton(onClick = { showAll = true }) { Text(tr("展开其余 ${folded.hidden} 行", "Show ${folded.hidden} more lines"), style = MaterialTheme.typography.labelSmall) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onDecide(true) }) { Text(tr("同意", "Approve")) }
            TextButton(onClick = { onDecide(false) }) { Text(tr("拒绝", "Deny")) }
        }
    }
}

/** 连接器请求：名称与用途；取消即拒绝该请求；连接动作提示到网页端（原生连接器页尚未上线）。 */
@Composable
fun ConnectorCardView(request: work.keepwriting.core.ConnectorRequest, onCancel: () -> Unit, onConnect: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(tr("请求接入连接器「${request.name ?: request.connectorId ?: "?"}」", "Requests the connector “${request.name ?: request.connectorId ?: "?"}”"), style = MaterialTheme.typography.titleSmall)
        request.purpose?.takeIf { it.isNotBlank() }?.let { Text(tr("用途：$it", "Purpose: $it"), style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onConnect) { Text(tr("去连接", "Connect")) }
            TextButton(onClick = onCancel) { Text(tr("取消", "Cancel")) }
        }
    }
}
