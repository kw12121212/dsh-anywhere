package work.keepwriting.dshanywhere

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import work.keepwriting.core.BotBrowser
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.ImageSizing
import work.keepwriting.core.Json
import kotlin.coroutines.resume

/**
 * IO 线程采样解码浏览器画面快照（遵守 android-client/server-image-handling）：
 * 先读边界尺寸，再按 [maxEdge] 计算 inSampleSize 解码；快照仅存于内存，不写磁盘与日志。
 */
private fun decodeSnapshotBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply {
        inSampleSize = ImageSizing.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}.getOrNull()

/**
 * Bot 浏览器页（dsbot-bot-browser）：
 * - 页面可见且 App 在前台且在线时，经 browser-frame 约每 2 秒单飞刷新画面快照，离开页面或进入后台立即停止；
 * - 画面支持双指缩放、平移与双击复位，仅保存在内存，不写日志、诊断上报与磁盘缓存；
 * - 显示持有状态（Bot 使用中 / 等待用户交接 / 用户已接管 / 已停止）并提供接管、交还（无 outcome）、停止；
 * - 按宿主能力降级：未启用插件、Bot 尚未打开浏览器、dsh Web 宿主接管与交还置灰提示而停止仍可用。
 */
@Composable
fun BotBrowserPage(
    botId: String,
    botName: String,
    state: Json.Value.Object?,
    resumed: Boolean,
    covered: Boolean,
    online: Boolean,
    api: ManageApi,
) {
    val poller = remember(botId) { BotBrowser.PollController().apply { setTarget(botId) } }
    val hold = remember(state, botId) { BotBrowser.holdOf(state, botId) }
    val hostDesktop = remember(state) { BotBrowser.hostHasDesktopChannel(state) }
    var frameBitmap by remember(botId) { mutableStateOf<ImageBitmap?>(null) }
    var frameUrl by remember(botId) { mutableStateOf("") }
    var frameTitle by remember(botId) { mutableStateOf("") }
    var frameOutcome by remember(botId) { mutableStateOf<BotBrowser.FrameOutcome?>(null) }
    var takeoverUnsupported by remember(botId, hostDesktop) { mutableStateOf(false) }
    var error by remember(botId) { mutableStateOf<String?>(null) }
    var info by remember(botId) { mutableStateOf<String?>(null) }
    var retryTick by remember(botId) { mutableStateOf(0) }
    var handledRetry by remember(botId) { mutableStateOf(0) }

    // 缩放与平移状态（1x–5x，双击或点复位回到 1x）
    var scale by remember(botId) { mutableStateOf(1f) }
    var offset by remember(botId) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(botId) { mutableStateOf(IntSize.Zero) }

    // 离开页面时立即停止轮询并清空内存中的敏感画面快照
    DisposableEffect(poller) {
        onDispose {
            poller.setActive(visible = false, foreground = false, online = false)
            frameBitmap = null
            frameUrl = ""
            frameTitle = ""
            scale = 1f
            offset = Offset.Zero
        }
    }

    // 前台且未被设置页覆盖且在线时，按 2 秒节拍单飞轮询 browser-frame
    LaunchedEffect(poller, resumed, covered, online, retryTick) {
        poller.setActive(visible = !covered, foreground = resumed, online = online)
        if (!resumed || covered || !online) return@LaunchedEffect
        var manualRetry = retryTick != handledRetry
        handledRetry = retryTick
        while (isActive) {
            val ticket = if (manualRetry) {
                manualRetry = false
                poller.retry()
            } else {
                poller.tryStart()
            }
            if (ticket != null) {
                val raw = suspendCancellableCoroutine<DsbotApi.Result> { cont ->
                    api.read("browser-frame", BotBrowser.Requests.frame(ticket.botId)) { res ->
                        if (cont.isActive) cont.resume(res)
                    }
                }
                // Base64 与 Bitmap 采样解码均在 IO 线程完成，不阻塞主线程（android-client/server-image-handling）
                val (decodedOutcome, bmp) = withContext(Dispatchers.IO) {
                    val outcome = BotBrowser.decodeFrameResult(raw)
                    val img = if (outcome is BotBrowser.FrameOutcome.Ready) {
                        decodeSnapshotBitmap(outcome.frame.bytes, BotBrowser.MAX_IMAGE_EDGE)
                    } else null
                    outcome to img
                }
                val accepted = poller.onResponse(ticket, decodedOutcome)
                if (accepted != null && isActive && poller.isActive) {
                    when (accepted) {
                        is BotBrowser.FrameOutcome.Ready -> {
                            if (bmp != null) {
                                frameBitmap = bmp
                                frameUrl = accepted.frame.url
                                frameTitle = accepted.frame.title
                                frameOutcome = accepted
                            } else {
                                frameOutcome = BotBrowser.FrameOutcome.Error(tr("无法解码浏览器画面", "Could not decode browser frame"))
                            }
                        }
                        is BotBrowser.FrameOutcome.HostOff, is BotBrowser.FrameOutcome.NoOpenBrowser -> {
                            frameBitmap = null
                            frameUrl = ""
                            frameTitle = ""
                            scale = 1f
                            offset = Offset.Zero
                            frameOutcome = accepted
                        }
                        is BotBrowser.FrameOutcome.Error -> {
                            frameOutcome = accepted
                        }
                    }
                }
            }
            if (poller.isHostOff) break
            delay(BotBrowser.POLL_INTERVAL_MS)
        }
    }

    val controls = remember(hold, hostDesktop, takeoverUnsupported, frameOutcome) {
        BotBrowser.controlsOf(hold, hostDesktop, takeoverUnsupported, frameOutcome)
    }

    fun busy(action: String) = "browser:$botId:$action" in api.inFlight

    fun runControl(actionKey: String, endpoint: String, payload: String, okHint: String) {
        error = null
        info = null
        api.actionRaw("browser:$botId:$actionKey", endpoint, payload) { result ->
            when (val outcome = BotBrowser.decodeControlResult(endpoint, result)) {
                BotBrowser.ActionOutcome.Ok -> info = okHint
                BotBrowser.ActionOutcome.DesktopOnly -> takeoverUnsupported = true
                is BotBrowser.ActionOutcome.Error -> error = outcome.message
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部状态区：Bot 名称、持有状态与当前页面标题/URL
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    tr("$botName · 浏览器", "$botName · Browser"),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (frameOutcome !is BotBrowser.FrameOutcome.HostOff) {
                    Surface(
                        color = when (hold.state) {
                            BotBrowser.HoldState.WAITING_HANDOFF -> MaterialTheme.colorScheme.tertiaryContainer
                            BotBrowser.HoldState.USER_TAKEN_OVER, BotBrowser.HoldState.STOPPED -> MaterialTheme.colorScheme.secondaryContainer
                            BotBrowser.HoldState.BOT_USING -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            BotBrowser.holdLabel(hold.state),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            if (hold.state == BotBrowser.HoldState.WAITING_HANDOFF && hold.note != null) {
                Spacer(Modifier.height(4.dp))
                Text(hold.note!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            if (frameTitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(frameTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (frameUrl.isNotBlank()) {
                Text(
                    frameUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        ErrorLine(error) { error = null }
        InfoLine(info, error = false) { info = null }

        // 控制栏：确认宿主非 HostOff 后展示，避免在未启用插件的宿主上首帧返回前短暂闪现
        if (frameOutcome != null && frameOutcome !is BotBrowser.FrameOutcome.HostOff) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                OutlinedButton(
                    enabled = controls.canTakeover && !busy("takeover"),
                    onClick = {
                        runControl(
                            "takeover", "browser-takeover",
                            BotBrowser.Requests.takeover(botId),
                            tr("已接管浏览器", "Took over the browser"),
                        )
                    },
                ) { Text(tr("接管", "Take over")) }

                OutlinedButton(
                    enabled = controls.canGiveBack && !busy("give-back"),
                    onClick = {
                        runControl(
                            "give-back", "browser-give-back",
                            BotBrowser.Requests.giveBack(botId),
                            tr("已交还给 Bot", "Gave back to the Bot"),
                        )
                    },
                ) { Text(tr("交还", "Give back")) }

                Button(
                    enabled = controls.canStop && !busy("stop"),
                    onClick = {
                        runControl(
                            "stop", "browser-stop",
                            BotBrowser.Requests.stop(botId),
                            tr("已停止 Bot 工作", "Stopped the Bot"),
                        )
                    },
                ) { Text(tr("停止", "Stop")) }
            }
            if (controls.desktopOnlyHint) {
                Text(
                    BotBrowser.desktopOnlyHintText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))

        // 画面区：仅内存快照，支持双指缩放与拖动
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .clipToBounds()
                .onSizeChanged { viewportSize = it },
        ) {
            when {
                frameOutcome is BotBrowser.FrameOutcome.HostOff -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(BotBrowser.hostOffTitle(), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            BotBrowser.hostOffDetail(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { retryTick++ }) { Text(tr("重试", "Retry")) }
                    }
                }
                frameOutcome is BotBrowser.FrameOutcome.NoOpenBrowser -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(BotBrowser.noOpenBrowserText(), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr("Bot 打开网页后画面将自动刷新。", "The snapshot updates automatically once the Bot opens a page."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                frameBitmap != null -> {
                    Image(
                        bitmap = frameBitmap!!,
                        contentDescription = tr("Bot 浏览器画面快照", "Bot browser snapshot"),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(botId) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val nextScale = (scale * zoom).coerceIn(1f, 5f)
                                    if (nextScale <= 1.01f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        val maxX = (viewportSize.width * (nextScale - 1f)) / 2f
                                        val maxY = (viewportSize.height * (nextScale - 1f)) / 2f
                                        scale = nextScale
                                        offset = Offset(
                                            x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                            y = (offset.y + pan.y).coerceIn(-maxY, maxY),
                                        )
                                    }
                                }
                            }
                            .pointerInput(botId) {
                                detectTapGestures(onDoubleTap = {
                                    scale = 1f
                                    offset = Offset.Zero
                                })
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    )
                    val refreshErr = (frameOutcome as? BotBrowser.FrameOutcome.Error)?.message
                    if (refreshErr != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                        ) {
                            Text(
                                refreshErr,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (scale > 1.01f) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        ) {
                            TextButton(onClick = { scale = 1f; offset = Offset.Zero }) {
                                Text(tr("还原缩放", "Reset zoom"), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                frameOutcome is BotBrowser.FrameOutcome.Error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            (frameOutcome as BotBrowser.FrameOutcome.Error).message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { retryTick++ }) { Text(tr("重试", "Retry")) }
                    }
                }
                else -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            tr("正在获取浏览器画面…", "Loading browser snapshot…"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
