package work.keepwriting.dshanywhere

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import work.keepwriting.core.ChatBars

/**
 * 0.3.10 聊天条组件（dsbot-chat-bars-0310）：修复卡、电脑操作请求卡、后台任务条、群目标条
 * 与工作短语行。数据由 core 的 ChatBars/WorkPhrase 从 state 快照派生，组件只负责呈现与回调；
 * 写操作在途时（inFlight 含对应键）按钮禁用防重复点按。
 */

/** 系统「动画时长缩放」为 0（关闭动画/减少动态效果）时为 true。 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
            .getOrDefault(1f) == 0f
    }
}

@Composable
private fun BarSurface(content: @Composable () -> Unit) {
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { content() }
    }
}

/** 模型失败修复卡：原因、指引、重试与（DeepSeek 额度时）去充值。 */
@Composable
fun RepairCard(repair: ChatBars.Repair, busy: Boolean, onRetry: () -> Unit, onTopUp: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                repair.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                repair.guidance,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (repair.topUp) TextButton(onClick = onTopUp) { Text(tr("去充值", "Top up")) }
                TextButton(onClick = onRetry, enabled = !busy) { Text(if (busy) tr("重试中…", "Retrying…") else tr("重试", "Retry")) }
            }
        }
    }
}

/** 电脑操作请求卡：每个请求一张，拒绝 / 本次允许 / 总是允许。 */
@Composable
fun ComputerAskCards(
    asks: List<ChatBars.ComputerAsk>,
    inFlight: List<String>,
    whoOf: (String) -> String? = { null },
    onAnswer: (ChatBars.ComputerAsk, String) -> Unit,
) {
    // 最多展开 2 张，其余计数提示（避免多张卡把消息列表与输入框挤出屏幕）；作答后下一张补上
    for (ask in asks.take(ASKS_SHOWN)) {
        val busy = "computer:${ask.id}" in inFlight
        BarSurface {
            val who = whoOf(ask.botId)
            Text(
                (if (who != null) "$who " else "") + tr("请求操作电脑上的「${ask.appName}」", "Wants to use “${ask.appName}” on the computer"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onAnswer(ask, "deny") }, enabled = !busy) { Text(tr("拒绝", "Deny")) }
                TextButton(onClick = { onAnswer(ask, "once") }, enabled = !busy) { Text(tr("本次允许", "Allow once")) }
                TextButton(onClick = { onAnswer(ask, "always") }, enabled = !busy) { Text(tr("总是允许", "Always allow")) }
            }
        }
    }
    if (asks.size > ASKS_SHOWN) {
        Text(
            tr("另有 ${asks.size - ASKS_SHOWN} 个电脑操作请求", "${asks.size - ASKS_SHOWN} more computer requests"),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

private const val ASKS_SHOWN = 2

/** Bot 正在操作电脑时的停止入口。 */
@Composable
fun ComputerStopBar(busy: Boolean, onStop: () -> Unit) {
    BarSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("正在操作电脑", "Using the computer"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onStop, enabled = !busy) { Text(tr("停止操作电脑", "Stop using computer")) }
        }
    }
}

/** 后台任务条：折叠显示数量，展开后逐任务显示标题、状态与停止。 */
@Composable
fun TaskBar(tasks: List<ChatBars.Task>, inFlight: List<String>, onStop: (ChatBars.Task) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    BarSurface {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        ) {
            Text(
                tr("后台任务 ${tasks.size} 个", "Background tasks: ${tasks.size}"),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) tr("收起", "Collapse") else tr("展开", "Expand"), style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) {
            // 展开列表限高可滚动，任务多时不挤掉消息列表与输入框
            Column(modifier = Modifier.heightIn(max = 168.dp).verticalScroll(rememberScrollState())) {
            for (task in tasks) {
                val busy = "task:${task.id}" in inFlight
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(task.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            ChatBars.taskStatusLabel(task.status),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onStop(task) }, enabled = !busy) { Text(if (busy) tr("停止中…", "Stopping…") else tr("停止", "Stop")) }
                }
            }
            }
        }
    }
}

/** 群目标条：标题 · 状态 · 第 N/M 阶段 当前阶段标题，进度条按已通过阶段数。 */
@Composable
fun GoalBar(goal: ChatBars.Goal, onClick: () -> Unit) {
    BarSurface {
        Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
            Text(
                tr("目标：${goal.title.ifEmpty { "未命名" }} · ${ChatBars.goalStatusLabel(goal.status)}", "Goal: ${goal.title.ifEmpty { "Untitled" }} · ${ChatBars.goalStatusLabel(goal.status)}"),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (goal.stages.isNotEmpty()) {
                val stageText = if (goal.current >= 0) {
                    tr("第 ${goal.current + 1}/${goal.stages.size} 阶段 · ${goal.stages[goal.current].title}", "Stage ${goal.current + 1}/${goal.stages.size} · ${goal.stages[goal.current].title}")
                } else {
                    tr("已通过 ${goal.passed}/${goal.stages.size} 阶段", "Passed ${goal.passed}/${goal.stages.size} stages")
                }
                Text(stageText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(
                    progress = { goal.passed.toFloat() / goal.stages.size },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

/** 工作短语行：ThinkingPill + 短语；短语带轻量呼吸动效，减少动态效果时静态。 */
@Composable
fun WorkPhraseLine(phrase: String?, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        ThinkingPill()
        if (!phrase.isNullOrEmpty()) {
            Spacer(Modifier.width(8.dp))
            val alpha = if (rememberReducedMotion()) {
                1f
            } else {
                val t = rememberInfiniteTransition(label = "phrase")
                val a by t.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
                    label = "phraseAlpha",
                )
                a
            }
            Text(
                phrase,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(alpha),
            )
        }
    }
}
