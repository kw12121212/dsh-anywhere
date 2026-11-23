package work.keepwriting.dshanywhere

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import work.keepwriting.core.JumpButton
import work.keepwriting.core.ScrollDistance
import work.keepwriting.core.ScrollMemory

/** 每个聊天一份「回到最新」状态（阈值约 600dp）；页面据其 away 决定流式输出时是否自动跟随。 */
@Composable
fun rememberJumpButton(chatId: String): JumpButton {
    val thresholdPx = with(LocalDensity.current) { 600.dp.toPx() }
    return remember(chatId) { JumpButton(thresholdPx) }
}

/**
 * 聊天列表的滚动辅助（dsbot-chat-cache），私聊与群聊共用：
 * - [restoreSignal] 在页面进入后变化时，恢复属于当前聊天的缓存滚动位置（首次组合时的旧信号忽略）；
 *   离开页面时经 [onSave] 保存当前位置；
 * - 离底约 600dp 时右下显示「回到最新」，期间末尾新增的条目计入「N 条新消息」；停在底部附近、
 *   或新增的是本机刚发出的消息（[lastIsMine]）时自动跟随到最新；流式期间（[freezeAway]）冻结离底判断。
 * [rowKeys] 为页面在组合期间读出的当前行 key 序列（不可变）：末尾 key 变化且旧末尾仍在列表中视为末尾新增，整体替换不计。
 */
@Composable
fun BoxScope.ChatScrollSupport(
    chatId: String,
    listState: LazyListState,
    jump: JumpButton,
    rowKeys: List<Long>,
    lastIsMine: Boolean,
    restoreSignal: Int,
    restorePosition: () -> Pair<String, ScrollMemory.Position>?,
    onSave: (index: Int, offset: Int) -> Unit,
    /** 流式气泡在列表下方占高、压缩视口期间为 true：此时不更新离底判断，免得停在底部也被判为离开。 */
    freezeAway: () -> Boolean = { false },
) {
    val scope = rememberCoroutineScope()
    var label by remember(chatId) { mutableStateOf<String?>(null) }

    // 进入页面时已存在的信号值属于上一次进入，不据此恢复
    val initialRestore = remember(chatId) { restoreSignal }
    LaunchedEffect(restoreSignal) {
        if (restoreSignal == initialRestore) return@LaunchedEffect
        val (id, pos) = restorePosition() ?: return@LaunchedEffect
        if (id == chatId) listState.scrollToItem(pos.index, pos.offset)
    }
    DisposableEffect(chatId) {
        onDispose { onSave(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }

    LaunchedEffect(listState, jump) {
        snapshotFlow {
            if (freezeAway()) return@snapshotFlow null
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            val last = visible.lastOrNull()
            if (last == null) {
                0f
            } else {
                ScrollDistance.belowPx(
                    totalItems = info.totalItemsCount,
                    lastVisibleIndex = last.index,
                    lastVisibleBottomPx = last.offset + last.size,
                    viewportEndPx = info.viewportEndOffset,
                    averageItemPx = visible.sumOf { it.size }.toFloat() / visible.size,
                )
            }
        }.collect { distance ->
            if (distance == null) return@collect
            jump.onDistance(distance)
            label = jump.label()
        }
    }

    // rowKeys 由页面在组合期间（快照隔离）读出：控制器在 executor 线程改 rows，协程里直接遍历
    // SnapshotStateList 会与之并发而 ConcurrentModificationException 闪退
    val lastKey = rowKeys.lastOrNull()
    var prevLastKey by remember(chatId) { mutableStateOf(lastKey) }
    LaunchedEffect(lastKey) {
        val prev = prevLastKey
        prevLastKey = lastKey
        if (prev == null || lastKey == null || prev == lastKey) return@LaunchedEffect
        val keys = rowKeys
        val prevIndex = keys.indexOf(prev)
        if (prevIndex < 0) return@LaunchedEffect // 整体替换（换聊天、缓存上屏）：不计数不跟随
        val appended = keys.size - 1 - prevIndex
        if (jump.away && !lastIsMine) {
            jump.onAppended(appended)
            label = jump.label()
        } else {
            jump.onJumped()
            label = null
            try {
                listState.animateScrollToItem(keys.size)
            } finally {
                jump.onJumpEnded()
            }
        }
    }

    label?.let { text ->
        ExtendedFloatingActionButton(
            onClick = {
                jump.onJumped()
                label = null
                scope.launch {
                    try {
                        listState.animateScrollToItem(rowKeys.size)
                    } finally {
                        jump.onJumpEnded() // 动画完成或被上滑打断都恢复正常判断
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        ) { Text(text) }
    }
}
