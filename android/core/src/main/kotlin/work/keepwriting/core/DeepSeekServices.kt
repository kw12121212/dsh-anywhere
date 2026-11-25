package work.keepwriting.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * DeepSeek 服务（dsbot-deepseek-services）：deepseek-services 与 deepseek-services-config 应答解码、
 * 默认偏好与音色列表合并、OCR/TTS/ASR（服务端 speech 字段）状态派生、TTS 计费字符长度与请求体字节预检、
 * deepseek-synthesize WAV 校验与 Base64 解码以及请求体构造。
 * 复刻 ds-bot 0.3.10 的 src/host/deepseek-services.js、channel.js 与 client.js（services-settings.js）。
 * 音频字节仅在内存流转，不落盘、不写入日志与诊断上报。
 */
object DeepSeekServices {
    /** 上游 TTS 调用 api.deepseek.com/v1/tools/tts 限时 300 秒（TTS_TIMEOUT_MS=300_000）：走慢线程池与长读超时。 */
    val SLOW_ENDPOINTS: Set<String> = setOf("deepseek-synthesize")

    const val DEFAULT_OCR = false
    const val TTS_SYSTEM = "system"
    const val TTS_DEEPSEEK = "deepseek"
    const val DEFAULT_TTS = TTS_SYSTEM
    const val DEFAULT_VOICE = "stella"
    const val ASR_PROVIDER_ID = "deepseek-asr"

    /** 上游 TTS 限制：计费字符数上限 10,000（汉字计 2，其余计 1），请求体上限 60 KiB，返回音频上限 40 MiB。 */
    const val TTS_BILLED_MAX = 10_000
    const val TTS_BODY_MAX = 60 * 1024
    const val TTS_MAX_BYTES = 40 * 1024 * 1024

    data class KeyStatus(val ref: String?, val configured: Boolean, val reason: String?)

    data class CapabilityStatus(
        val supported: Boolean,
        val enabled: Boolean,
        val configured: Boolean,
        val reason: String?,
    )

    data class TtsStatus(
        val supported: Boolean,
        val enabled: Boolean,
        val configured: Boolean,
        val voices: List<String>,
        val reason: String?,
    )

    data class SpeechStatus(
        val registered: Boolean,
        val providerId: String?,
        val configured: Boolean,
        val ready: Boolean,
        val reason: String?,
    )

    data class Preferences(val ocr: Boolean, val tts: String, val voice: String)

    data class View(
        val endpoint: String?,
        val key: KeyStatus,
        val ocr: CapabilityStatus?,
        val tts: TtsStatus?,
        val speech: SpeechStatus?,
        val preferences: Preferences,
    ) {
        /** 可选音色列表（网页端 [...new Set([...(status?.tts?.voices ?? []), prefs.voice])]）。 */
        val availableVoices: List<String>
            get() = buildList {
                val seen = HashSet<String>()
                for (v in tts?.voices.orEmpty()) if (v.isNotBlank() && seen.add(v)) add(v)
                if (preferences.voice.isNotBlank() && seen.add(preferences.voice)) add(preferences.voice)
            }

        val isDefault: Boolean
            get() = !preferences.ocr && preferences.tts == DEFAULT_TTS && preferences.voice == DEFAULT_VOICE
    }

    enum class Tone { READY, OFF, MISSING }

    data class StatusLine(val tone: Tone, val text: String)

    data class SynthesizedAudio(val bytes: ByteArray, val mediaType: String) {
        override fun equals(other: Any?): Boolean =
            other is SynthesizedAudio && mediaType == other.mediaType && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * mediaType.hashCode() + bytes.contentHashCode()

        // 绝不展开原始音频字节到日志、异常或诊断堆栈
        override fun toString(): String = "SynthesizedAudio(bytes=${bytes.size}B, mediaType=$mediaType)"
    }

    private fun Json.Value.Object.str(k: String): String? =
        (members[k] as? Json.Value.StringValue)?.value?.takeIf { it.isNotBlank() }

    private fun Json.Value.Object.boolOrNull(k: String): Boolean? =
        (members[k] as? Json.Value.BoolValue)?.value

    private fun Json.Value.Object.obj(k: String): Json.Value.Object? =
        members[k] as? Json.Value.Object

    /** 解码 deepseek-services 或 deepseek-services-config 的应答 value；不是有效服务视图时返回 null。 */
    fun decode(value: Json.Value?): View? {
        val o = value as? Json.Value.Object ?: return null
        val knownKeys = listOf("endpoint", "key", "ocr", "tts", "speech", "preferences")
        if (knownKeys.none { it in o.members }) return null

        val keyObj = o.obj("key")
        val key = KeyStatus(
            ref = keyObj?.str("ref"),
            configured = keyObj?.boolOrNull("configured") == true,
            reason = keyObj?.str("reason"),
        )
        val ocr = o.obj("ocr")?.let { c ->
            CapabilityStatus(
                supported = c.boolOrNull("supported") != false,
                enabled = c.boolOrNull("enabled") == true,
                configured = c.boolOrNull("configured") != false,
                reason = c.str("reason"),
            )
        }
        val tts = o.obj("tts")?.let { c ->
            val voices = ((c.members["voices"] as? Json.Value.Array)?.items ?: emptyList())
                .mapNotNull { (it as? Json.Value.StringValue)?.value?.takeIf { s -> s.isNotBlank() } }
            TtsStatus(
                supported = c.boolOrNull("supported") != false,
                enabled = c.boolOrNull("enabled") == true,
                configured = c.boolOrNull("configured") != false,
                voices = voices,
                reason = c.str("reason"),
            )
        }
        val speech = o.obj("speech")?.let { s ->
            SpeechStatus(
                registered = s.boolOrNull("registered") == true,
                providerId = s.str("providerId"),
                configured = s.boolOrNull("configured") != false,
                ready = s.boolOrNull("ready") != false && s.boolOrNull("registered") == true && s.boolOrNull("configured") != false,
                reason = s.str("reason"),
            )
        }
        val prefObj = o.obj("preferences")
        val rawTts = prefObj?.str("tts")
        val prefs = Preferences(
            ocr = prefObj?.boolOrNull("ocr") ?: (ocr?.enabled ?: DEFAULT_OCR),
            tts = when (rawTts) {
                TTS_DEEPSEEK, TTS_SYSTEM -> rawTts
                else -> if (tts?.enabled == true) TTS_DEEPSEEK else DEFAULT_TTS
            },
            voice = prefObj?.str("voice") ?: DEFAULT_VOICE,
        )
        return View(
            endpoint = o.str("endpoint"),
            key = key,
            ocr = ocr,
            tts = tts,
            speech = speech,
            preferences = prefs,
        )
    }

    /** 识图 OCR 状态行（对齐 client.js capabilityState）。 */
    fun ocrState(ocr: CapabilityStatus?): StatusLine {
        if (ocr == null) return StatusLine(Tone.OFF, I18n.tr("已关闭", "Off"))
        val usable = ocr.supported && ocr.configured
        if (!usable) return StatusLine(Tone.MISSING, ocr.reason ?: I18n.tr("未就绪", "Not ready"))
        if (ocr.enabled) return StatusLine(Tone.READY, I18n.tr("已就绪", "Ready"))
        return StatusLine(Tone.OFF, ocr.reason ?: I18n.tr("已关闭", "Off"))
    }

    /** 朗读 TTS 状态行（对齐 client.js capabilityState）。 */
    fun ttsState(tts: TtsStatus?): StatusLine {
        if (tts == null) return StatusLine(Tone.OFF, I18n.tr("已关闭", "Off"))
        val usable = tts.supported && tts.configured
        if (!usable) return StatusLine(Tone.MISSING, tts.reason ?: I18n.tr("未就绪", "Not ready"))
        if (tts.enabled) return StatusLine(Tone.READY, I18n.tr("已就绪", "Ready"))
        return StatusLine(Tone.OFF, tts.reason ?: I18n.tr("已关闭", "Off"))
    }

    /**
     * 听写 ASR 状态行（对齐 client.js asrState；经 /api/bot 只读展示，听写服务商选择在 DSH 宿主内部通道完成）。
     */
    fun speechState(speech: SpeechStatus?): StatusLine {
        if (speech == null) return StatusLine(Tone.OFF, I18n.tr("已关闭", "Off"))
        val usable = speech.registered && speech.configured && speech.ready
        if (!usable) return StatusLine(Tone.MISSING, speech.reason ?: I18n.tr("未就绪", "Not ready"))
        return StatusLine(
            Tone.READY,
            I18n.tr("已就绪（可在电脑端 DSH 语音输入中选用）", "Ready (select in DSH voice input on desktop)"),
        )
    }

    /** 音色显示名（对齐 client.js voiceName：首字母大写）。 */
    fun voiceLabel(id: String): String =
        id.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    /**
     * TTS 计费字符长度（对齐上游 deepseek-services.js billedLength）：
     * 汉字（Unicode Script=Han，含扩展区）计 2，其余每个码点计 1。
     */
    fun billedLength(text: String): Int {
        var total = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            total += if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) 2 else 1
            i += Character.charCount(cp)
        }
        return total
    }

    /** 上游发往 /v1/tools/tts 的 JSON 请求体字节长度估算（对齐 TTS_BODY_MAX = 60 KiB）。 */
    fun upstreamRequestBytes(text: String, voice: String): Int =
        ("{\"text\":" + JsonString.of(text) + ",\"voice_id\":" + JsonString.of(voice) + ",\"format\":\"wav\"}")
            .toByteArray(Charsets.UTF_8).size

    /** 本地试听文本提示（仅作提示，不阻止服务端返回 bot/invalid 原因展示）；正常时返回 null。 */
    fun previewWarning(text: String, voice: String): String? {
        if (text.isBlank()) return I18n.tr("请输入要试听的文字", "Enter text to preview")
        val billed = billedLength(text)
        if (billed > TTS_BILLED_MAX) {
            return I18n.tr(
                "文本超出单次合成上限（$billed / $TTS_BILLED_MAX 计费字符，汉字计 2）",
                "Text exceeds single-call limit ($billed / $TTS_BILLED_MAX billed characters; CJK counts as 2)",
            )
        }
        if (upstreamRequestBytes(text, voice) > TTS_BODY_MAX) {
            return I18n.tr("请求体超过 60 KiB 上限，请缩短文本", "Request body exceeds the 60 KiB limit; shorten the text")
        }
        return null
    }

    /** 解码 deepseek-synthesize 应答 {audioBase64, mediaType} 并校验 WAV 头；无效或超限时返回 null。 */
    fun decodeSynthesize(value: Json.Value?): SynthesizedAudio? {
        val o = value as? Json.Value.Object ?: return null
        val b64 = (o.members["audioBase64"] as? Json.Value.StringValue)?.value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // 在分配解码数组前先按 base64 长度粗检上限（40 MiB 对应约 56 MB base64）
        if (b64.length.toLong() * 3L / 4L > TTS_MAX_BYTES.toLong() + 4L) return null
        val bytes = try {
            Base64.getDecoder().decode(b64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size > TTS_MAX_BYTES || !isValidWav(bytes)) return null
        val mediaType = o.str("mediaType") ?: "audio/wav"
        return SynthesizedAudio(bytes, mediaType)
    }

    /**
     * 校验 WAV 容器头完整性（对齐上游 deepseek-services.js 399–417 行）：
     * 须以 RIFF....WAVE 开头，有限 RIFF 长度须完整到达，并含完整 data 块（支持流式未知长度 0xFFFFFFFF）。
     */
    fun isValidWav(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) return false
        if (bytes[8] != 'W'.code.toByte() || bytes[9] != 'A'.code.toByte() ||
            bytes[10] != 'V'.code.toByte() || bytes[11] != 'E'.code.toByte()
        ) return false
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val riffSize = bb.getInt(4).toLong() and 0xFFFFFFFFL
        if (riffSize != 0L && riffSize != 0xFFFFFFFFL && riffSize + 8L > bytes.size.toLong()) return false
        var at = 12L
        var sawDataAt = -1L
        var sawDataSize = 0L
        while (at + 8L <= bytes.size.toLong()) {
            val pos = at.toInt()
            val isData = bytes[pos] == 'd'.code.toByte() && bytes[pos + 1] == 'a'.code.toByte() &&
                bytes[pos + 2] == 't'.code.toByte() && bytes[pos + 3] == 'a'.code.toByte()
            val size = bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            if (isData) {
                sawDataAt = at
                sawDataSize = size
            }
            val pad = size and 1L
            val step = 8L + size + pad
            if (step <= 0L) return false
            at += step
        }
        if (sawDataAt < 0L) return false
        if (sawDataSize != 0xFFFFFFFFL && sawDataAt + 8L + sawDataSize > bytes.size.toLong()) return false
        return true
    }

    /** 端点请求体构造（channel.js / deepseek-services.js 源码核对）。 */
    object Requests {
        private fun q(s: String) = JsonString.of(s)

        fun status(): String = "{}"

        fun setOcr(enabled: Boolean?): String =
            "{\"ocr\":${enabled?.toString() ?: "null"}}"

        fun setTts(mode: String?): String =
            "{\"tts\":${mode?.let(::q) ?: "null"}}"

        fun setVoice(voice: String?): String =
            "{\"voice\":${voice?.let(::q) ?: "null"}}"

        fun resetDefaults(): String = "{\"ocr\":null,\"tts\":null,\"voice\":null}"

        fun synthesize(text: String, voice: String? = null): String =
            if (voice.isNullOrBlank()) "{\"text\":" + q(text) + "}"
            else "{\"text\":" + q(text) + ",\"voice\":" + q(voice) + "}"
    }
}
