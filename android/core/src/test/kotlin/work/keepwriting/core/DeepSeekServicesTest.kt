package work.keepwriting.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DeepSeek 服务纯逻辑测试（dsbot-deepseek-services）：
 * 真实脱敏夹具解码、偏好默认合并与音色列表、OCR/TTS/ASR 状态派生、
 * 计费字符长度（含 BMP 与扩展区汉字）与请求体字节上限、WAV 头校验与 Base64 解码、
 * 全部端点请求体字面值与慢端点登记。
 */
class DeepSeekServicesTest {
    @AfterEach
    fun resetLang() {
        I18n.lang = ChatBars.Lang.ZH
    }

    private fun validWav(pcmBytes: Int = 8, streamingSize: Boolean = false): ByteArray {
        val totalSize = 44 + pcmBytes
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(if (streamingSize) -1 else totalSize - 8)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16) // PCM fmt chunk size
        buf.putShort(1) // PCM
        buf.putShort(1) // mono
        buf.putInt(16000)
        buf.putInt(32000)
        buf.putShort(2)
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(if (streamingSize) -1 else pcmBytes)
        repeat(pcmBytes) { buf.put(0) }
        return buf.array()
    }

    @Test
    fun `从真实脱敏夹具解码 deepseek-services 视图`() {
        val text = DeepSeekServicesTest::class.java.getResource("/dsbot/deepseek-services.json")!!.readText(Charsets.UTF_8)
        val ok = DsbotApi.parseResponse(200, text) as DsbotApi.Result.Ok
        val view = assertNotNull(DeepSeekServices.decode(ok.value))
        assertTrue(view.key.configured)
        assertNull(view.key.reason)
        assertEquals(true, view.ocr?.supported)
        assertEquals(false, view.ocr?.enabled)
        assertEquals(true, view.ocr?.configured)
        assertEquals(listOf("stella", "tide"), view.tts?.voices)
        assertEquals(listOf("stella", "tide"), view.availableVoices)
        assertEquals(false, view.speech?.registered)
        assertEquals("deepseek-asr", view.speech?.providerId)
        assertEquals(false, view.speech?.ready)
        assertTrue(view.speech?.reason?.contains("speech-to-text") == true)
        assertEquals(DeepSeekServices.Preferences(ocr = false, tts = "system", voice = "stella"), view.preferences)
        assertTrue(view.isDefault)
    }

    @Test
    fun `偏好合并、自定义音色并入与默认判定`() {
        val raw = Json.parse(
            """{
                "endpoint":"https://api.deepseek.com",
                "key":{"ref":"DEEPSEEK_API_KEY","configured":true},
                "ocr":{"supported":true,"enabled":true,"configured":true},
                "tts":{"supported":true,"enabled":true,"configured":true,"voices":["stella","tide"]},
                "speech":{"registered":true,"providerId":"deepseek-asr","configured":true,"ready":true},
                "preferences":{"ocr":true,"tts":"deepseek","voice":"nova"}
            }""",
        )
        val view = assertNotNull(DeepSeekServices.decode(raw))
        assertEquals(DeepSeekServices.Preferences(ocr = true, tts = "deepseek", voice = "nova"), view.preferences)
        assertEquals(listOf("stella", "tide", "nova"), view.availableVoices)
        assertFalse(view.isDefault)
        assertEquals("Stella", DeepSeekServices.voiceLabel("stella"))
        assertEquals("Tide", DeepSeekServices.voiceLabel("tide"))
    }

    @Test
    fun `非对象或无关对象解码返回 null`() {
        assertNull(DeepSeekServices.decode(null))
        assertNull(DeepSeekServices.decode(Json.parse("[]")))
        assertNull(DeepSeekServices.decode(Json.parse("""{"unrelated":true}""")))
    }

    @Test
    fun `OCR、TTS 与 ASR 状态行按 0_3_10 client 语义派生`() {
        I18n.lang = ChatBars.Lang.ZH
        assertEquals(DeepSeekServices.StatusLine(DeepSeekServices.Tone.OFF, "已关闭"), DeepSeekServices.ocrState(null))
        assertEquals(
            DeepSeekServices.StatusLine(DeepSeekServices.Tone.MISSING, "API key missing"),
            DeepSeekServices.ocrState(DeepSeekServices.CapabilityStatus(true, false, false, "API key missing")),
        )
        assertEquals(
            DeepSeekServices.StatusLine(DeepSeekServices.Tone.READY, "已就绪"),
            DeepSeekServices.ocrState(DeepSeekServices.CapabilityStatus(true, true, true, null)),
        )
        assertEquals(
            DeepSeekServices.StatusLine(DeepSeekServices.Tone.OFF, "已关闭"),
            DeepSeekServices.ttsState(DeepSeekServices.TtsStatus(true, false, true, listOf("stella"), null)),
        )
        assertEquals(
            DeepSeekServices.StatusLine(DeepSeekServices.Tone.MISSING, "no voice input"),
            DeepSeekServices.speechState(DeepSeekServices.SpeechStatus(false, "deepseek-asr", true, false, "no voice input")),
        )
        assertEquals(
            DeepSeekServices.Tone.READY,
            DeepSeekServices.speechState(DeepSeekServices.SpeechStatus(true, "deepseek-asr", true, true, null)).tone,
        )

        I18n.lang = ChatBars.Lang.EN
        assertEquals(DeepSeekServices.StatusLine(DeepSeekServices.Tone.READY, "Ready"), DeepSeekServices.ttsState(DeepSeekServices.TtsStatus(true, true, true, listOf("stella"), null)))
    }

    @Test
    fun `TTS 计费字符长度与 60 KiB 请求体预检对齐上游`() {
        // 英文/数字/标点计 1，汉字计 2（含扩展区代理对 𠀀 U+20000）
        assertEquals(5, DeepSeekServices.billedLength("Hello"))
        assertEquals(4, DeepSeekServices.billedLength("你好"))
        assertEquals(7, DeepSeekServices.billedLength("Hi你好!"))
        assertEquals(2, DeepSeekServices.billedLength("\uD840\uDC00")) // U+20000 Han Ext B

        assertNotNull(DeepSeekServices.previewWarning("   ", "stella"))
        assertNull(DeepSeekServices.previewWarning("你好，世界", "stella"))
        val tooManyBilled = "汉".repeat(5_001) // 10,002 billed > 10,000
        assertNotNull(DeepSeekServices.previewWarning(tooManyBilled, "stella"))
        val exactlyMaxBilled = "a".repeat(10_000)
        assertNull(DeepSeekServices.previewWarning(exactlyMaxBilled, "stella"))

        // 上游请求体字节估算（含 UTF-8 多字节与 JSON 转义）与 60 KiB 上限分支
        val utf8Bytes = DeepSeekServices.upstreamRequestBytes("你好\"a\"", "stella")
        assertEquals(
            """{"text":"你好\"a\"","voice_id":"stella","format":"wav"}""".toByteArray(Charsets.UTF_8).size,
            utf8Bytes,
        )
        val escapedNearLimit = "\u0001".repeat(10_000) // billed = 10,000，JSON 转义后占 60,000 字节
        val overBodyVoice = "v".repeat(2_000) // 总请求体 > 61,440 字节（60 KiB）
        assertTrue(DeepSeekServices.upstreamRequestBytes(escapedNearLimit, overBodyVoice) > DeepSeekServices.TTS_BODY_MAX)
        assertNotNull(DeepSeekServices.previewWarning(escapedNearLimit, overBodyVoice))
    }

    @Test
    fun `WAV 头校验与 Base64 解码覆盖合法、流式与截断输入`() {
        val wav = validWav(16)
        assertTrue(DeepSeekServices.isValidWav(wav))
        val b64 = Base64.getEncoder().encodeToString(wav)
        val ok = DeepSeekServices.decodeSynthesize(Json.parse("""{"audioBase64":"$b64","mediaType":"audio/wav"}"""))
        assertNotNull(ok)
        assertContentEquals(wav, ok.bytes)
        assertEquals("audio/wav", ok.mediaType)
        assertEquals("SynthesizedAudio(bytes=${wav.size}B, mediaType=audio/wav)", ok.toString())

        // 流式未知长度 0xFFFFFFFF 允许
        val streamWav = validWav(16, streamingSize = true)
        assertTrue(DeepSeekServices.isValidWav(streamWav))

        // 截断的 WAV 必须拒绝
        val truncated = wav.copyOf(wav.size - 4)
        assertFalse(DeepSeekServices.isValidWav(truncated))
        val truncB64 = Base64.getEncoder().encodeToString(truncated)
        assertNull(DeepSeekServices.decodeSynthesize(Json.parse("""{"audioBase64":"$truncB64"}""")))

        // 非 WAV 或非法 Base64 拒绝
        assertFalse(DeepSeekServices.isValidWav("not-a-wav-header-at-all".toByteArray()))
        assertNull(DeepSeekServices.decodeSynthesize(Json.parse("""{"audioBase64":"!!!invalid!!!"}""")))
        assertNull(DeepSeekServices.decodeSynthesize(Json.parse("""{"audioBase64":""}""")))
    }

    @Test
    fun `请求体构造与慢端点集合字面值正确`() {
        assertEquals("{}", DeepSeekServices.Requests.status())
        assertEquals("""{"ocr":true}""", DeepSeekServices.Requests.setOcr(true))
        assertEquals("""{"ocr":false}""", DeepSeekServices.Requests.setOcr(false))
        assertEquals("""{"ocr":null}""", DeepSeekServices.Requests.setOcr(null))
        assertEquals("""{"tts":"deepseek"}""", DeepSeekServices.Requests.setTts("deepseek"))
        assertEquals("""{"tts":"system"}""", DeepSeekServices.Requests.setTts("system"))
        assertEquals("""{"tts":null}""", DeepSeekServices.Requests.setTts(null))
        assertEquals("""{"voice":"tide"}""", DeepSeekServices.Requests.setVoice("tide"))
        assertEquals("""{"voice":null}""", DeepSeekServices.Requests.setVoice(null))
        assertEquals("""{"ocr":null,"tts":null,"voice":null}""", DeepSeekServices.Requests.resetDefaults())
        assertEquals("""{"text":"你好"}""", DeepSeekServices.Requests.synthesize("你好"))
        assertEquals("""{"text":"你好","voice":"tide"}""", DeepSeekServices.Requests.synthesize("你好", "tide"))
        assertTrue("deepseek-synthesize" in DeepSeekServices.SLOW_ENDPOINTS)
        assertFalse("deepseek-services" in DeepSeekServices.SLOW_ENDPOINTS)
    }
}
