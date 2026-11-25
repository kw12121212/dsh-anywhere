package work.keepwriting.core.spike

import work.keepwriting.core.Json

/**
 * General 高级项探针组（dsbot-general-advanced）：
 * 只读检查 state.prefs 中的高级键（memoryAutoRecall、memoryLimits、backgroundAfterSec），
 * 并仅以越界/非整数 backgroundAfterSec 探测 set-prefs 的 bot/invalid 错误形态（在 rt.save()
 * 之前抛出，不修改真实团队偏好，严格遵守 spike-write-probes-non-mutating）。
 * 注意：绝不对真实服务探测非法 memoryLimits 或 memoryAutoRecall，因为上游 channel.js
 * 246–254 行不会抛 bot/invalid，而是静默 delete 对应键并执行 rt.save()。
 */
internal class ProbesGeneralAdvanced(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    fun run(stateVal: Json.Value?) {
        inspectPrefs(stateVal)
        // 越界与小数 backgroundAfterSec 在 channel.js 244 行直接抛 bot/invalid，不触碰 state.prefs 或 rt.save()
        record("bg-over-max", """{"backgroundAfterSec":601}""")
        record("bg-negative", """{"backgroundAfterSec":-1}""")
        record("bg-decimal", """{"backgroundAfterSec":1.5}""")
    }

    private fun inspectPrefs(stateVal: Json.Value?) {
        val state = stateVal as? Json.Value.Object
        val prefs = state?.members?.get("prefs") as? Json.Value.Object
        val memoryEnabled = (state?.members?.get("memory") as? Json.Value.BoolValue)?.value
        val keys = prefs?.members?.keys?.sorted().orEmpty()
        val autoRecall = (prefs?.members?.get("memoryAutoRecall") as? Json.Value.BoolValue)?.value
        val bg = (prefs?.members?.get("backgroundAfterSec") as? Json.Value.NumberValue)?.raw
        val limits = (prefs?.members?.get("memoryLimits") as? Json.Value.Object)?.members?.mapValues {
            (it.value as? Json.Value.NumberValue)?.raw
        }
        pass(
            "general-adv-prefs",
            "state.memory=$memoryEnabled prefs键=$keys " +
                "memoryAutoRecall=$autoRecall memoryLimits=$limits backgroundAfterSec=$bg",
        )
    }

    private fun record(label: String, payload: String) {
        val res = try {
            post("set-prefs", payload)
        } catch (e: Exception) {
            fail("general-adv-$label", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val root = Json.parse(res.bodyText()) as? Json.Value.Object
        val ok = (root?.members?.get("ok") as? Json.Value.BoolValue)?.value
        val err = root?.members?.get("error") as? Json.Value.Object
        val code = (err?.members?.get("code") as? Json.Value.StringValue)?.value
        val msg = (err?.members?.get("message") as? Json.Value.StringValue)?.value
        if (res.status == 200 && ok == false && code == "bot/invalid") {
            pass("general-adv-$label", "POST set-prefs $payload → HTTP 200 ok=false code=$code message=$msg")
        } else if (res.status == 200 && ok == false && code == "bot/read-only") {
            info("general-adv-$label", "只读宿主跳过参数校验探测：HTTP 200 ok=false code=$code message=$msg")
        } else {
            fail("general-adv-$label", "期望 bot/invalid，实际 HTTP ${res.status} ok=$ok code=$code message=$msg")
        }
    }
}
