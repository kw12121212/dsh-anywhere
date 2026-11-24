package work.keepwriting.core.spike

import work.keepwriting.core.Json

/**
 * 本地 Agent 探针组（dsbot-local-agents）：只读登记 local-agents 应答的键、计数与登录状态分布（不打印路径与名称以外的
 * 机器信息，路径只记是否存在）；写端点只取服务端在任何写入前就拒绝的错误形态（不存在的 id、无效命令）；
 * 不调用会在电脑上启动 CLI 的 local-agent-models，不改动真实本地 Agent。
 */
internal class ProbesLocalAgents(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    fun run() {
        list()
        record("local-agent-update", "{\"id\":\"spike-none\",\"name\":\"x\"}")
        record("local-agent-remove", "{\"id\":\"spike-none\"}")
        // validateCustom 在 store().custom.push 之前拒绝含空白的命令
        record("local-agent-add-invalid", "local-agent-add", "{\"name\":\"spike\",\"command\":\"spike invalid\",\"args\":[]}")
    }

    private fun list() {
        val res = try {
            post("local-agents", "{}")
        } catch (e: Exception) {
            fail("la-list", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val v = parseOk("la-list", res) as? Json.Value.Object ?: return
        val detected = (v.members["detected"] as? Json.Value.Array)?.items?.mapNotNull { it as? Json.Value.Object }.orEmpty()
        val custom = (v.members["custom"] as? Json.Value.Array)?.items?.mapNotNull { it as? Json.Value.Object }.orEmpty()
        val signed = detected.groupingBy { (it.members["signedIn"] as? Json.Value.BoolValue)?.value?.toString() ?: "null" }.eachCount()
        val routes = detected.mapNotNull { (it.members["route"] as? Json.Value.StringValue)?.value }
        pass(
            "la-list",
            "键=${v.members.keys.sorted()} available=${(v.members["available"] as? Json.Value.BoolValue)?.value} detected=${detected.size} routes=$routes signedIn=$signed " +
                "detected 键集=${detected.map { it.members.keys.sorted().joinToString(",") }.toSet()} custom=${custom.size} custom 键集=${custom.map { it.members.keys.sorted().joinToString(",") }.toSet()}",
        )
    }

    private fun record(endpoint: String, payload: String) = record(endpoint, endpoint, payload)

    private fun record(label: String, endpoint: String, payload: String) {
        val res = try {
            post(endpoint, payload)
        } catch (e: Exception) {
            fail("la-$label", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val root = Json.parse(res.bodyText()) as? Json.Value.Object
        val ok = (root?.members?.get("ok") as? Json.Value.BoolValue)?.value
        val err = root?.members?.get("error") as? Json.Value.Object
        info("la-$label", "POST $endpoint → HTTP ${res.status} ok=$ok error.code=${(err?.members?.get("code") as? Json.Value.StringValue)?.value} message=${(err?.members?.get("message") as? Json.Value.StringValue)?.value}")
    }
}
