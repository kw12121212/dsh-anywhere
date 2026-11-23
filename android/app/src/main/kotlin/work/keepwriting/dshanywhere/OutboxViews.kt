package work.keepwriting.dshanywhere

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import work.keepwriting.core.Outbox

/**
 * 发送箱本地行（dsbot-outbox-retry）：渲染在聊天末尾的用户气泡，发送中显示「发送中…」，
 * 已发送待确认显示「已发送，等待确认」与「删除」，未发送显示「未发送，点按重试」与「删除」。重试与删除经控制器在 executor 上执行。
 */
fun LazyListScope.outboxItems(
    entries: List<Outbox.Entry>,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    items(entries, key = { "outbox-${it.id}" }) { entry ->
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), contentAlignment = Alignment.CenterEnd) {
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    color = if (entry.state == Outbox.State.FAILED) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Text(entry.display, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
                when (entry.state) {
                    Outbox.State.SENDING -> Text(
                        tr("发送中…", "Sending…"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 请求已成功、服务端消息尚未对上：提供删除作为退出路径（从不自动重发）
                    Outbox.State.SENT -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr("已发送，等待确认", "Sent, waiting for confirmation"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { onDelete(entry.id) }) {
                            Text(tr("删除", "Delete"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Outbox.State.FAILED -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onRetry(entry.id) }) {
                            Text(tr("未发送，点按重试", "Not sent, tap to retry"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { onDelete(entry.id) }) {
                            Text(tr("删除", "Delete"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/** 发送后滚到列表末尾（本地行在 rows 之后），成功时给一次轻触感反馈。 */
@Composable
fun OutboxEffects(listState: LazyListState, sendScrollSignal: Int, sentSignal: Int) {
    // 信号存在控制器上、跨页面保留：只响应进入本页之后的变化（进页不重复震动、不跳到底）
    val initialScroll = remember { sendScrollSignal }
    val initialSent = remember { sentSignal }
    LaunchedEffect(sendScrollSignal) {
        if (sendScrollSignal != initialScroll) {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }
    val view = LocalView.current
    LaunchedEffect(sentSignal) {
        if (sentSignal != initialSent) {
            Haptics.confirm(view)
        }
    }
}
