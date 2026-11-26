package work.keepwriting.core.spike

import work.keepwriting.core.Json

/**
 * 电脑操控设置探针组（dsbot-computer-settings）：
 * 只读登记 state.computer 键集、开关、provider 状态、macos 与各 Bot 级别/授权数量分布（不打印 Bot 名称、应用名或 id），
 * 只读调用 computer-system-status；写端点只用不存在的 botId、非法级别（在写入前被拒）取错误形态。
 * computer-permission 没有无效参数可用（上游把非法值回落成 accessibility），只在同一轮 system-status 证明宿主
 * 没有系统权限服务（ok 且 value 为 null）时才发，否则跳过——在 macOS 桌面宿主上它会真的弹出系统授权框。
 * 严格遵守 spike-write-probes-non-mutating：不调用 computer-set-enabled 与 computer-provider-retry（后者会改写并保存 provider 状态、产生新 revision）。
 */
internal class ProbesComputer(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    fun run(stateVal: Json.Value?, firstBotId: String?) {
        inspectState(stateVal)
        val noAccess = record("system-status", "computer-system-status", "{}") == Json.Value.NullValue
        record("set-level-unknown-bot", "computer-set-level", """{"botId":"spike-none","level":"view"}""")
        // 非法级别在 LEVELS 校验处抛错，早于 bot.computer 写入与 rt.save()
        if (firstBotId != null) {
            record("set-level-bad-level", "computer-set-level", "{\"botId\":${work.keepwriting.core.JsonString.of(firstBotId)},\"level\":\"spike-bad\"}")
        }
        record("revoke-unknown-bot", "computer-revoke-app", """{"botId":"spike-none","appKey":"spike"}""")
        if (noAccess) {
            record("permission-no-access", "computer-permission", """{"permission":"accessibility"}""")
        } else {
            info("cmp-permission-no-access", "跳过：system-status 未证明宿主无系统权限服务（发出会在电脑上弹出真实授权框）")
        }
        record("unknown-sub-endpoint", "computer-spike-none", "{}")
    }

    private fun inspectState(stateVal: Json.Value?) {
        val c = (stateVal as? Json.Value.Object)?.members?.get("computer") as? Json.Value.Object
        if (c == null) {
            info("cmp-state", "state 不含 computer 对象")
            return
        }
        val provider = c.members["provider"] as? Json.Value.Object
        val bots = c.members["bots"] as? Json.Value.Object
        val levels = bots?.members?.values?.mapNotNull { (it as? Json.Value.Object)?.members?.get("level") as? Json.Value.StringValue }
            ?.groupingBy { it.value }?.eachCount()
        val apps = bots?.members?.values?.sumOf { ((it as? Json.Value.Object)?.members?.get("apps") as? Json.Value.Object)?.members?.size ?: 0 }
        val botKeys = bots?.members?.values?.mapNotNull { (it as? Json.Value.Object)?.members?.keys?.sorted()?.joinToString(",") }?.toSet()
        pass(
            "cmp-state",
            "computer 键=${c.members.keys.sorted()} enabled=${(c.members["enabled"] as? Json.Value.BoolValue)?.value} " +
                "provider键=${provider?.members?.keys?.sorted()} provider.state=${(provider?.members?.get("state") as? Json.Value.StringValue)?.value} " +
                "macos=${(c.members["macos"] as? Json.Value.BoolValue)?.value} bots=${bots?.members?.size} 条目键集=$botKeys 级别分布=$levels 授权总数=$apps",
        )
    }

    /** 记录应答形态；返回 ok 时的 value（失败或异常返回 null）。 */
    private fun record(label: String, endpoint: String, payload: String): Json.Value? {
        val res = try {
            post(endpoint, payload)
        } catch (e: Exception) {
            fail("cmp-$label", "请求异常 ${e.javaClass.simpleName}")
            return null
        }
        val root = Json.parse(res.bodyText()) as? Json.Value.Object
        val err = root?.members?.get("error") as? Json.Value.Object
        val value = root?.members?.get("value")
        info(
            "cmp-$label",
            "POST $endpoint → HTTP ${res.status} ok=${(root?.members?.get("ok") as? Json.Value.BoolValue)?.value} " +
                "value=${value?.let { if (it is Json.Value.Object) "对象键=${it.members.keys.sorted()}" else Json.encode(it) }} " +
                "error.code=${(err?.members?.get("code") as? Json.Value.StringValue)?.value} " +
                "message=${(err?.members?.get("message") as? Json.Value.StringValue)?.value}",
        )
        return if ((root?.members?.get("ok") as? Json.Value.BoolValue)?.value == true) value else null
    }
}
