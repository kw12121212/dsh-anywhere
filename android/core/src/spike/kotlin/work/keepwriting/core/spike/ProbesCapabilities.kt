package work.keepwriting.core.spike

import work.keepwriting.core.Json

/**
 * 能力探针组（dsbot-capabilities）：只读登记 state 中能力库、Bot 挂载与 live capabilityStatuses 的形态，
 * 写端点只取错误形态——不存在的名称/Bot、服务端在写入前就拒绝的无效名称与无效能力包子项，
 * 不改动真实团队。输出只含键、种类、计数与错误码，不打印能力内容、命令、URL、环境变量或请求头。
 */
internal class ProbesCapabilities(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    private val none = "\"spike-none\""

    fun run() {
        stateShape()
        record("capability-approve", "{\"name\":$none}")
        record("capability-delete", "{\"name\":$none}")
        record("bot-capability", "{\"botId\":$none,\"name\":$none,\"attached\":false,\"mode\":\"always\"}")
        // 名称校验在写入前（capabilities.js itemInput → validateCapabilityName）
        record("capability-save-invalid-name", "capability-save", "{\"name\":\"Spike_Invalid\",\"kind\":\"skill\",\"content\":\"\"}")
        // 能力包子项校验也在写入前（itemInput 的 pack 分支先于 items[name] = item）
        record("capability-save-invalid-pack", "capability-save", "{\"name\":\"spike-none-pack\",\"kind\":\"pack\",\"items\":[$none]}")
    }

    private fun stateShape() {
        val res = post("state", "{}")
        val v = parseOk("caps-state", res) as? Json.Value.Object ?: return
        val lib = v.members["capabilities"]
        if (lib !is Json.Value.Array) {
            fail("caps-library", "state.capabilities 不是数组（${lib?.javaClass?.simpleName ?: "缺失"}）")
            return
        }
        val items = lib.items.mapNotNull { it as? Json.Value.Object }
        val kinds = items.groupingBy { str(it, "kind") ?: "?" }.eachCount()
        val transports = items.filter { str(it, "kind") == "mcp" }.groupingBy { str(it, "transport") ?: "?" }.eachCount()
        val keySets = items.map { it.members.keys.sorted().joinToString(",") }.toSet()
        val unapproved = items.count { str(it, "transport") == "stdio" && (it.members["approved"] as? Json.Value.BoolValue)?.value != true }
        val byBot = items.count { it.members["createdBy"] is Json.Value.StringValue }
        pass("caps-library", "state.capabilities=[${items.size}] kind=$kinds transport=$transports 未批准stdio=$unapproved Bot提议=$byBot；键集=${keySets.joinToString(" | ")}")

        val bots = (v.members["bots"] as? Json.Value.Array)?.items?.mapNotNull { it as? Json.Value.Object }.orEmpty()
        val withCaps = bots.count { it.members["capabilities"] is Json.Value.Array }
        val withPacks = bots.count { it.members["packs"] is Json.Value.Array }
        val attached = bots.sumOf { ((it.members["capabilities"] as? Json.Value.Array)?.items?.size ?: 0) }
        val catalog = bots.sumOf { ((it.members["packs"] as? Json.Value.Array)?.items?.size ?: 0) }
        info("caps-bots", "bots=${bots.size} 带 capabilities 数组=$withCaps 带 packs 数组=$withPacks 常驻挂载=$attached 按需挂载=$catalog")

        val statuses = v.members["capabilityStatuses"]
        if (statuses is Json.Value.Object) {
            val states = statuses.members.values.mapNotNull { it as? Json.Value.Object }
                .flatMap { it.members.values }.mapNotNull { it as? Json.Value.Object }
            val dist = states.groupingBy { str(it, "state") ?: "?" }.eachCount()
            val entryKeys = states.map { it.members.keys.sorted().joinToString(",") }.toSet()
            pass("caps-statuses", "capabilityStatuses 为对象：botId 键=${statuses.members.size} 状态分布=$dist 条目键=${entryKeys.joinToString(" | ")}")
        } else {
            fail("caps-statuses", "capabilityStatuses 不是对象（${statuses?.javaClass?.simpleName ?: "缺失"}）")
        }
    }

    private fun str(o: Json.Value.Object, k: String) = (o.members[k] as? Json.Value.StringValue)?.value

    private fun record(endpoint: String, payload: String) = record(endpoint, endpoint, payload)

    private fun record(label: String, endpoint: String, payload: String) {
        val res = try {
            post(endpoint, payload)
        } catch (e: Exception) {
            fail("caps-$label", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val root = Json.parse(res.bodyText()) as? Json.Value.Object
        val ok = (root?.members?.get("ok") as? Json.Value.BoolValue)?.value
        val err = root?.members?.get("error") as? Json.Value.Object
        val code = (err?.members?.get("code") as? Json.Value.StringValue)?.value
        val message = (err?.members?.get("message") as? Json.Value.StringValue)?.value
        info("caps-$label", "POST $endpoint → HTTP ${res.status} ok=$ok error.code=$code message=$message")
    }
}
