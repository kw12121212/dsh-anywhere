package work.keepwriting.core.spike

import work.keepwriting.core.Json

/**
 * DeepSeek 服务探针组（dsbot-deepseek-services）：
 * 只读探测 deepseek-services 应答键、布尔与音色形态（不回显私有地址或路径，不写入真实偏好），
 * 并以非法参数探测 deepseek-services-config 与 deepseek-synthesize 的错误形态（均在写入或外部
 * 付费合成调用之前被服务端拒绝，严格遵守 spike-write-probes-non-mutating）。
 */
internal class ProbesDeepSeekServices(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    fun run() {
        status()
        // 非法参数在 updateServices 校验阶段即拒绝，不修改 prefs
        record("config-bad-ocr", "deepseek-services-config", """{"ocr":"bad"}""")
        record("config-bad-tts", "deepseek-services-config", """{"tts":"bad"}""")
        record("config-bad-voice", "deepseek-services-config", """{"voice":"bad-voice"}""")
        // 空文本与非法音色在 synthesize 参数校验阶段即拒绝（TTS 关闭时先回 bot/unavailable），不触发付费请求
        record("synth-empty-text", "deepseek-synthesize", """{"text":"   "}""")
        record("synth-bad-voice", "deepseek-synthesize", """{"text":"hi","voice":"bad-voice"}""")
    }

    private fun status() {
        val res = try {
            post("deepseek-services", "{}")
        } catch (e: Exception) {
            fail("dss-status", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val v = parseOk("dss-status", res) as? Json.Value.Object ?: return
        dumpJson("deepseek-services", res)
        fun obj(k: String) = v.members[k] as? Json.Value.Object
        fun bool(o: Json.Value.Object?, k: String) = (o?.members?.get(k) as? Json.Value.BoolValue)?.value
        fun str(o: Json.Value.Object?, k: String) = (o?.members?.get(k) as? Json.Value.StringValue)?.value
        val key = obj("key")
        val ocr = obj("ocr")
        val tts = obj("tts")
        val speech = obj("speech")
        val prefs = obj("preferences")
        val voices = (tts?.members?.get("voices") as? Json.Value.Array)?.items
            ?.mapNotNull { (it as? Json.Value.StringValue)?.value }.orEmpty()
        pass(
            "dss-status",
            "顶层键=${v.members.keys.sorted()} " +
                "key键=${key?.members?.keys?.sorted()}(configured=${bool(key, "configured")},hasReason=${str(key, "reason") != null}) " +
                "ocr键=${ocr?.members?.keys?.sorted()}(supported=${bool(ocr, "supported")},enabled=${bool(ocr, "enabled")},configured=${bool(ocr, "configured")},hasReason=${str(ocr, "reason") != null}) " +
                "tts键=${tts?.members?.keys?.sorted()}(supported=${bool(tts, "supported")},enabled=${bool(tts, "enabled")},configured=${bool(tts, "configured")},voices=$voices,hasReason=${str(tts, "reason") != null}) " +
                "speech键=${speech?.members?.keys?.sorted()}(registered=${bool(speech, "registered")},providerId=${str(speech, "providerId")},configured=${bool(speech, "configured")},ready=${bool(speech, "ready")},hasReason=${str(speech, "reason") != null}) " +
                "prefs键=${prefs?.members?.keys?.sorted()}(ocr=${bool(prefs, "ocr")},tts=${str(prefs, "tts")},voice=${str(prefs, "voice")})",
        )
    }

    private fun record(label: String, endpoint: String, payload: String) {
        val res = try {
            post(endpoint, payload)
        } catch (e: Exception) {
            fail("dss-$label", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val root = Json.parse(res.bodyText()) as? Json.Value.Object
        val err = root?.members?.get("error") as? Json.Value.Object
        info(
            "dss-$label",
            "POST $endpoint → HTTP ${res.status} ok=${(root?.members?.get("ok") as? Json.Value.BoolValue)?.value} " +
                "error.code=${(err?.members?.get("code") as? Json.Value.StringValue)?.value} " +
                "message=${(err?.members?.get("message") as? Json.Value.StringValue)?.value}",
        )
    }
}
