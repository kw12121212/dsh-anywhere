package work.keepwriting.dshanywhere

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import work.keepwriting.core.BotManage
import work.keepwriting.core.DeepSeekServices
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.Json

/*
 * DeepSeek 服务（dsbot-deepseek-services）：Settings → Models → DeepSeek services 原生页。
 * 按需读取 deepseek-services 展示官方端点、API key 状态与 OCR/TTS/ASR（服务端 speech 字段）状态和原因，
 * 经 deepseek-services-config 开关 OCR、切换朗读模式与音色及 null 恢复默认，
 * 经 deepseek-synthesize 试听 WAV 并在内存中直接经 MediaDataSource 播放（绝不落盘、不写日志与诊断上报）。
 */

private sealed interface DssLoad {
    data object Loading : DssLoad
    data class Ok(val view: DeepSeekServices.View) : DssLoad
    data class Failed(val text: String, val unsupported: Boolean) : DssLoad
}

private fun dssLoadOf(r: DsbotApi.Result): DssLoad = when (r) {
    is DsbotApi.Result.Ok -> DeepSeekServices.decode(r.value)?.let { DssLoad.Ok(it) }
        ?: DssLoad.Failed(tr("服务端应答无法识别", "The server answer could not be read"), false)
    is DsbotApi.Result.BotError -> DssLoad.Failed(BotManage.errorText(r.code, r.message), r.code == "bot/not-found")
    is DsbotApi.Result.Failure -> DssLoad.Failed(BotManage.errorText(null, null), false)
}

/** 纯内存 WAV 播放器（API 26+ MediaDataSource）：直接从 ByteArray 流式读取，不写任何临时文件或日志。 */
private class MemoryWavPlayer {
    private var player: MediaPlayer? = null

    fun play(bytes: ByteArray, onComplete: () -> Unit, onError: (String) -> Unit) {
        stop()
        val mp = MediaPlayer()
        player = mp
        val source = object : MediaDataSource() {
            @Volatile
            private var data: ByteArray? = bytes

            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (size == 0) return 0
                val buf = data ?: return -1
                if (position < 0L || position >= buf.size.toLong()) return -1
                val start = position.toInt()
                val count = minOf(size, buffer.size - offset, buf.size - start)
                if (count <= 0) return -1
                System.arraycopy(buf, start, buffer, offset, count)
                return count
            }

            override fun getSize(): Long = data?.size?.toLong() ?: 0L
            override fun close() {
                data = null
            }
        }
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(source)
            mp.setOnPreparedListener { ready ->
                if (ready === player) {
                    try {
                        ready.start()
                    } catch (_: Exception) {
                        stop()
                        onError(tr("试听音频播放失败", "Could not play the preview audio"))
                    }
                } else {
                    runCatching { ready.release() }
                }
            }
            mp.setOnCompletionListener { done ->
                if (done === player) {
                    stop()
                    onComplete()
                }
            }
            mp.setOnErrorListener { failed, _, _ ->
                if (failed === player) {
                    stop()
                    onError(tr("试听音频播放失败", "Could not play the preview audio"))
                }
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            stop()
            onError(tr("试听音频播放失败", "Could not play the preview audio"))
        }
    }

    fun stop() {
        val current = player
        player = null
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.release() }
        }
    }
}

@Composable
private fun DssNote(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
private fun StatusLineRow(line: DeepSeekServices.StatusLine) {
    val dotColor = when (line.tone) {
        DeepSeekServices.Tone.READY -> Color(0xFF4CAF50)
        DeepSeekServices.Tone.MISSING -> MaterialTheme.colorScheme.error
        DeepSeekServices.Tone.OFF -> MaterialTheme.colorScheme.outlineVariant
    }
    val textColor = when (line.tone) {
        DeepSeekServices.Tone.MISSING -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
        Spacer(Modifier.size(8.dp))
        Text(line.text, style = MaterialTheme.typography.bodySmall, color = textColor)
    }
}

/** DeepSeek 服务设置页。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeepSeekServicesPage(api: ManageApi, visible: Boolean = true, onOpenModels: () -> Unit) {
    var load by remember { mutableStateOf<DssLoad>(DssLoad.Loading) }
    var round by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val gen = remember { intArrayOf(0) }

    // 试听状态（仅内存；离开页面、退后台或被覆盖层遮挡立即停止并释放播放器，过期回调丢弃）
    val defaultSample = tr("你好，这是 DeepSeek 语音试听。", "Hello, this is a DeepSeek voice preview.")
    var previewText by remember { mutableStateOf(defaultSample) }
    var synthesizing by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var synthError by remember { mutableStateOf<String?>(null) }
    val synthGen = remember { intArrayOf(0) }
    val alive = remember { booleanArrayOf(true) }
    val visibleRef = remember { booleanArrayOf(visible) }
    visibleRef[0] = visible
    val wavPlayer = remember { MemoryWavPlayer() }

    fun stopPreview() {
        synthGen[0]++
        wavPlayer.stop()
        synthesizing = false
        playing = false
    }

    DisposableEffect(Unit) {
        alive[0] = true
        onDispose {
            alive[0] = false
            stopPreview()
        }
    }

    LaunchedEffect(visible) {
        if (!visible) stopPreview()
    }

    LaunchedEffect(round) {
        val mine = ++gen[0]
        api.read("deepseek-services", DeepSeekServices.Requests.status()) { r ->
            if (alive[0] && mine == gen[0]) load = dssLoadOf(r)
        }
    }

    val busy = "dss:config" in api.inFlight
    val applyView: (Json.Value) -> Unit = { v ->
        DeepSeekServices.decode(v)?.let {
            gen[0]++
            load = DssLoad.Ok(it)
            saved = true
        }
    }
    fun saveConfig(payload: String) {
        error = null
        saved = false
        api.action("dss:config", "deepseek-services-config", payload, { v -> if (alive[0]) applyView(v) }) { msg ->
            if (alive[0]) error = msg
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Text(
            tr("DeepSeek 服务", "DeepSeek services"),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
        DssNote(tr("识图、朗读与听写直接按你的 DeepSeek API key 计费。", "OCR, text-to-speech, and speech recognition bill directly on your DeepSeek API key."))

        when (val l = load) {
            DssLoad.Loading -> DssNote(tr("读取中…", "Loading…"))
            is DssLoad.Failed -> {
                if (l.unsupported) {
                    Text(
                        tr("此远端不支持 DeepSeek 服务。", "This server does not offer DeepSeek services."),
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    ErrorLine(l.text) {}
                    TextButton(
                        onClick = { load = DssLoad.Loading; round++ },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text(tr("重试", "Try again")) }
                }
            }
            is DssLoad.Ok -> {
                val view = l.view
                ErrorLine(error) { error = null }
                if (busy) DssNote(tr("保存中…", "Saving…")) else if (saved) DssNote(tr("已保存。", "Saved."))

                // ---- 1. DeepSeek API key ----
                SectionTitle(tr("DeepSeek API", "DeepSeek API"))
                StatusLineRow(
                    if (view.key.configured) {
                        DeepSeekServices.StatusLine(DeepSeekServices.Tone.READY, tr("API key 已设置", "Key set"))
                    } else {
                        DeepSeekServices.StatusLine(
                            DeepSeekServices.Tone.MISSING,
                            view.key.reason ?: tr("需要 DeepSeek API key", "Needs an API key"),
                        )
                    },
                )
                val metaParts = listOfNotNull(view.endpoint, view.key.ref)
                if (metaParts.isNotEmpty()) {
                    Text(
                        metaParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = onOpenModels) { Text(tr("管理模型与服务商", "Manage models")) }
                    TextButton(enabled = !busy, onClick = { round++ }) { Text(tr("刷新", "Refresh")) }
                    if (!view.isDefault) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                stopPreview()
                                saveConfig(DeepSeekServices.Requests.resetDefaults())
                            },
                        ) { Text(tr("恢复默认", "Reset defaults")) }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

                // ---- 2. 识图（OCR）----
                SectionTitle(tr("识图与文档阅读（OCR）", "Image and document reading (OCR)"))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(tr("使用 DeepSeek 识别图片与文档", "Use DeepSeek to read images and documents"), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            tr("Bot 会将选定文件上传至 DeepSeek 进行文字识别，本地 Office 编辑不受影响。", "Bots upload selected files to DeepSeek for text recognition; local Office editing is unchanged."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = view.preferences.ocr,
                        enabled = !busy,
                        onCheckedChange = { on -> saveConfig(DeepSeekServices.Requests.setOcr(on)) },
                    )
                }
                StatusLineRow(DeepSeekServices.ocrState(view.ocr))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

                // ---- 3. 朗读（TTS）与音色试听 ----
                SectionTitle(tr("朗读（TTS）", "Text to speech (TTS)"))
                DssNote(tr("朗读回复方式", "Read replies aloud"))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    FilterChip(
                        selected = view.preferences.tts == DeepSeekServices.TTS_SYSTEM,
                        enabled = !busy,
                        onClick = {
                            if (view.preferences.tts != DeepSeekServices.TTS_SYSTEM) {
                                stopPreview()
                                saveConfig(DeepSeekServices.Requests.setTts(DeepSeekServices.TTS_SYSTEM))
                            }
                        },
                        label = { Text(tr("系统", "System")) },
                    )
                    FilterChip(
                        selected = view.preferences.tts == DeepSeekServices.TTS_DEEPSEEK,
                        enabled = !busy,
                        onClick = {
                            if (view.preferences.tts != DeepSeekServices.TTS_DEEPSEEK) {
                                saveConfig(DeepSeekServices.Requests.setTts(DeepSeekServices.TTS_DEEPSEEK))
                            }
                        },
                        label = { Text("DeepSeek") },
                    )
                }
                if (view.preferences.tts == DeepSeekServices.TTS_DEEPSEEK) {
                    DssNote(tr("音色", "Voice"))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        for (voiceId in view.availableVoices) {
                            FilterChip(
                                selected = view.preferences.voice == voiceId,
                                enabled = !busy,
                                onClick = {
                                    if (view.preferences.voice != voiceId) {
                                        saveConfig(DeepSeekServices.Requests.setVoice(voiceId))
                                    }
                                },
                                label = { Text(DeepSeekServices.voiceLabel(voiceId)) },
                            )
                        }
                    }
                }
                StatusLineRow(DeepSeekServices.ttsState(view.tts))

                // 语音试听（纯内存 WAV 播放，音频不落盘、不写日志）
                SectionTitle(tr("语音试听", "Voice preview"))
                OutlinedTextField(
                    value = previewText,
                    onValueChange = { previewText = it; synthError = null },
                    label = { Text(tr("试听文本", "Preview text")) },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !synthesizing,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                val billed = DeepSeekServices.billedLength(previewText)
                val warn = if (previewText.isBlank()) null else DeepSeekServices.previewWarning(previewText, view.preferences.voice)
                DssNote(
                    warn ?: tr("计费长度：$billed / ${DeepSeekServices.TTS_BILLED_MAX}（汉字计 2）；音频仅在内存播放，不落盘", "Billed length: $billed / ${DeepSeekServices.TTS_BILLED_MAX} (CJK counts as 2); plays in memory only"),
                    error = warn != null,
                )
                ErrorLine(synthError) { synthError = null }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    when {
                        playing -> {
                            OutlinedButton(onClick = { stopPreview() }) { Text(tr("停止试听", "Stop")) }
                            Text(
                                tr("正在播放…", "Playing…"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        synthesizing -> {
                            OutlinedButton(onClick = { stopPreview() }) { Text(tr("取消", "Cancel")) }
                            Text(
                                tr("合成中…", "Synthesizing…"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        else -> {
                            OutlinedButton(
                                enabled = previewText.isNotBlank() && !busy,
                                onClick = {
                                    synthError = null
                                    stopPreview()
                                    synthesizing = true
                                    val mine = ++synthGen[0]
                                    val voice = view.preferences.voice
                                    api.read(
                                        "deepseek-synthesize",
                                        DeepSeekServices.Requests.synthesize(previewText, voice),
                                    ) { r ->
                                        if (!alive[0] || !visibleRef[0] || mine != synthGen[0]) return@read
                                        synthesizing = false
                                        when (r) {
                                            is DsbotApi.Result.Ok -> {
                                                val audio = DeepSeekServices.decodeSynthesize(r.value)
                                                if (audio == null) {
                                                    synthError = tr("服务端返回的试听音频无效", "The server returned invalid preview audio")
                                                } else {
                                                    playing = true
                                                    wavPlayer.play(
                                                        bytes = audio.bytes,
                                                        onComplete = {
                                                            if (alive[0] && visibleRef[0] && mine == synthGen[0]) playing = false
                                                        },
                                                        onError = { msg ->
                                                            if (alive[0] && visibleRef[0] && mine == synthGen[0]) {
                                                                playing = false
                                                                synthError = msg
                                                            }
                                                        },
                                                    )
                                                }
                                            }
                                            is DsbotApi.Result.BotError -> synthError = BotManage.errorText(r.code, r.message)
                                            is DsbotApi.Result.Failure -> synthError = BotManage.errorText(null, null)
                                        }
                                    }
                                },
                            ) { Text(tr("试听语音", "Preview voice")) }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

                // ---- 4. 听写（ASR，只读）----
                SectionTitle(tr("听写（ASR）", "Speech recognition (ASR)"))
                StatusLineRow(DeepSeekServices.speechState(view.speech))
                DssNote(tr("听写服务商切换在电脑端 DSH 语音输入设置中完成，本页仅显示服务端就绪状态。", "Dictation provider selection is managed in DSH voice input settings on desktop; this page shows server readiness."))
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}
