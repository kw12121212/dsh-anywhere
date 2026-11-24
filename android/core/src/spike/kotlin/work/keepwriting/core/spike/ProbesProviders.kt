package work.keepwriting.core.spike

import work.keepwriting.core.Json

/**
 * 模型服务商探针组（dsbot-models-providers）：只读登记 providers 应答的键、计数、key 状态分布与 revision 类型
 *（视图本身不含 key 值）；写端点只取服务端在任何写入前就拒绝的错误形态（不存在的服务商、带引号的 key），
 * 不改动用户真实的服务商、模型与 key；不调用会请求外部端点的 provider-models。
 */
internal class ProbesProviders(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    private val none = "\"spike-none\""

    fun run() {
        list()
        // setProviderKey 先 checkKey（带引号即拒），再 writableRef
        record("provider-key-quoted", "provider-key", "{\"provider\":$none,\"value\":\"'x'\"}")
        record("provider-key-unknown", "provider-key", "{\"provider\":$none,\"value\":\"sk-spike\"}")
        record("provider-add-unknown", "provider-add", "{\"provider\":$none}")
        record("provider-models-save-unknown", "provider-models-save", "{\"provider\":$none,\"models\":[{\"id\":\"x\"}]}")
        record("provider-remove-unknown", "provider-remove", "{\"provider\":$none}")
    }

    private fun list() {
        val res = try {
            post("providers", "{}")
        } catch (e: Exception) {
            fail("pv-list", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val v = parseOk("pv-list", res) as? Json.Value.Object ?: return
        val providers = (v.members["providers"] as? Json.Value.Array)?.items?.mapNotNull { it as? Json.Value.Object }.orEmpty()
        val keys = providers.groupingBy { p ->
            when (val k = p.members["key"]) {
                is Json.Value.Object -> "configured=${(k.members["configured"] as? Json.Value.BoolValue)?.value},writable=${(k.members["writable"] as? Json.Value.BoolValue)?.value}"
                Json.Value.NullValue -> "null"
                else -> "absent"
            }
        }.eachCount()
        val flags = providers.map { p -> listOf("refreshable", "removable", "custom").filter { (p.members[it] as? Json.Value.BoolValue)?.value == true } }.flatten().groupingBy { it }.eachCount()
        val models = providers.sumOf { (it.members["models"] as? Json.Value.Array)?.items?.size ?: 0 }
        val declared = providers.sumOf { (it.members["declaredModels"] as? Json.Value.Array)?.items?.size ?: 0 }
        val errors = providers.count { it.members["error"] is Json.Value.StringValue }
        pass(
            "pv-list",
            "键=${v.members.keys.sorted()} available=${(v.members["available"] as? Json.Value.BoolValue)?.value} providers=${providers.size} key=$keys flags=$flags models=$models declared=$declared 出错=$errors " +
                "addable=${(v.members["addable"] as? Json.Value.Array)?.items?.size} protocols=${(v.members["protocols"] as? Json.Value.Array)?.items?.mapNotNull { (it as? Json.Value.StringValue)?.value }} " +
                "revision 类型=${v.members["revision"]?.javaClass?.simpleName} provider 键集=${providers.map { it.members.keys.sorted().joinToString(",") }.toSet()}",
        )
    }

    private fun record(label: String, endpoint: String, payload: String) {
        val res = try {
            post(endpoint, payload)
        } catch (e: Exception) {
            fail("pv-$label", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val root = Json.parse(res.bodyText()) as? Json.Value.Object
        val err = root?.members?.get("error") as? Json.Value.Object
        info("pv-$label", "POST $endpoint → HTTP ${res.status} ok=${(root?.members?.get("ok") as? Json.Value.BoolValue)?.value} error.code=${(err?.members?.get("code") as? Json.Value.StringValue)?.value} message=${(err?.members?.get("message") as? Json.Value.StringValue)?.value}")
    }
}
