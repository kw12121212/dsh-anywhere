package work.keepwriting.core.spike

import work.keepwriting.core.Json

/**
 * 密钥设置与落盘加密探针组（dsbot-secrets-settings）：
 * 只读登记 state.secrets、state.secretStore 与 secret-store 应答键集和模式分布
 * （不打印任何密钥名、用途、值或口令）；写端点只以非法参数或不存在的键取错误形态，绝不改动真实密钥或加密状态。
 */
internal class ProbesSecrets(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    private val missing = "\"SPIKE_NONEXISTENT_SECRET_999\""

    fun run() {
        inspectState()
        store()
        record("set-short-value", "secret-set", "{\"name\":$missing,\"value\":\"12\"}")
        record("set-bad-name", "secret-set", "{\"name\":\"123-bad\",\"value\":\"abcd\"}")
        record("update-missing", "secret-update", "{\"name\":$missing,\"purpose\":\"spike\"}")
        record("delete-missing", "secret-delete", "{\"name\":$missing}")
        record("unlock-short", "secret-unlock", "{\"passphrase\":\"short\"}")
        record("encrypt-short", "secret-encrypt", "{\"passphrase\":\"short\"}")
        record("decrypt-short", "secret-decrypt", "{\"passphrase\":\"short\"}")
    }

    private fun inspectState() {
        val res = try {
            post("state", "{}")
        } catch (e: Exception) {
            fail("sec-state", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val v = parseOk("sec-state", res) as? Json.Value.Object ?: return
        val list = (v.members["secrets"] as? Json.Value.Array)?.items?.mapNotNull { it as? Json.Value.Object }
        val keySets = list?.map { it.members.keys.sorted().joinToString(",") }?.toSet()
        val hasValueKey = list?.any { it.members.containsKey("value") } == true
        val store = v.members["secretStore"] as? Json.Value.Object
        val storeKeys = store?.members?.keys?.sorted()
        val mode = (store?.members?.get("mode") as? Json.Value.StringValue)?.value
        val locked = (store?.members?.get("locked") as? Json.Value.BoolValue)?.value
        val readOnly = v.members["readOnly"]?.takeUnless { it is Json.Value.NullValue } != null
        if (hasValueKey) {
            fail("sec-state", "state.secrets 条目意外包含 value 键")
            return
        }
        pass(
            "sec-state",
            "secrets数=${list?.size} 条目键集=$keySets secretStore键=$storeKeys mode=$mode locked=$locked readOnly=$readOnly",
        )
    }

    private fun store() {
        val res = try {
            post("secret-store", "{}")
        } catch (e: Exception) {
            fail("sec-store", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val v = parseOk("sec-store", res) as? Json.Value.Object ?: return
        val mode = (v.members["mode"] as? Json.Value.StringValue)?.value
        val locked = (v.members["locked"] as? Json.Value.BoolValue)?.value
        val source = (v.members["source"] as? Json.Value.StringValue)?.value
        val reason = (v.members["reason"] as? Json.Value.StringValue)?.value
        pass("sec-store", "键=${v.members.keys.sorted()} mode=$mode locked=$locked source=$source reason=$reason")
    }

    private fun record(label: String, endpoint: String, payload: String) {
        val res = try {
            post(endpoint, payload)
        } catch (e: Exception) {
            fail("sec-$label", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val root = Json.parse(res.bodyText()) as? Json.Value.Object
        val err = root?.members?.get("error") as? Json.Value.Object
        info(
            "sec-$label",
            "POST $endpoint → HTTP ${res.status} ok=${(root?.members?.get("ok") as? Json.Value.BoolValue)?.value} " +
                "error.code=${(err?.members?.get("code") as? Json.Value.StringValue)?.value} " +
                "message=${(err?.members?.get("message") as? Json.Value.StringValue)?.value}",
        )
    }
}
