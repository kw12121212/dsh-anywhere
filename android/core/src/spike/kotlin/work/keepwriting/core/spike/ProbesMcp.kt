package work.keepwriting.core.spike

import work.keepwriting.core.Json

/**
 * MCP 访问探针组（dsbot-mcp-access）：
 * 只读登记 state 中 live mcp 视图的键集、开关、端口、status、error 是否为空与客户端条目键集/数量（不打印客户端名称、id 或范围）；
 * 写端点只取在写入 mcp.json 之前就被拒的错误形态：mcp-set 非法端口（不带 on，端口校验先于写入）、
 * mcp-client-add 空名称（名称校验先于生成令牌与写入）、mcp-client-update / mcp-client-remove 不存在的 id（clientOf 先于写入）。
 * 严格遵守 spike-write-probes-non-mutating：不发出任何会写 mcp.json、开关或改端口、生成令牌的请求。
 */
internal class ProbesMcp(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    fun run(stateVal: Json.Value?) {
        inspectState(stateVal)
        record("set-bad-port", "mcp-set", """{"port":80}""")
        record("add-empty-name", "mcp-client-add", """{"name":"   "}""")
        record("update-unknown", "mcp-client-update", """{"id":"spike-none","name":"spike"}""")
        record("remove-unknown", "mcp-client-remove", """{"id":"spike-none"}""")
    }

    private fun inspectState(stateVal: Json.Value?) {
        val m = (stateVal as? Json.Value.Object)?.members?.get("mcp") as? Json.Value.Object
        if (m == null) {
            info("mcp-state", "state 不含 mcp 对象")
            return
        }
        val clients = (m.members["clients"] as? Json.Value.Array)?.items.orEmpty()
        val clientKeys = clients.mapNotNull { (it as? Json.Value.Object)?.members?.keys?.sorted()?.joinToString(",") }.toSet()
        val hasTokenField = clients.any { (it as? Json.Value.Object)?.members?.keys?.any { k -> k == "token" || k == "hash" } == true }
        pass(
            "mcp-state",
            "mcp 键=${m.members.keys.sorted()} on=${(m.members["on"] as? Json.Value.BoolValue)?.value} " +
                "port=${(m.members["port"] as? Json.Value.NumberValue)?.raw} status=${(m.members["status"] as? Json.Value.StringValue)?.value} " +
                "error=${if (m.members["error"] is Json.Value.StringValue) "非空" else Json.encode(m.members["error"] ?: Json.Value.NullValue)} " +
                "url主机=${(m.members["url"] as? Json.Value.StringValue)?.value?.substringAfter("://")?.substringBefore(':')} " +
                "clients=${clients.size} 条目键集=$clientKeys 条目含token/hash=$hasTokenField",
        )
    }

    /** 记录应答形态（不打印 value 内容，只打印键集）。 */
    private fun record(label: String, endpoint: String, payload: String) {
        val res = try {
            post(endpoint, payload)
        } catch (e: Exception) {
            fail("mcp-$label", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val root = Json.parse(res.bodyText()) as? Json.Value.Object
        val err = root?.members?.get("error") as? Json.Value.Object
        val value = root?.members?.get("value")
        val ok = (root?.members?.get("ok") as? Json.Value.BoolValue)?.value
        val line = "POST $endpoint → HTTP ${res.status} ok=$ok " +
            "value=${value?.let { if (it is Json.Value.Object) "对象键=${it.members.keys.sorted()}" else it.javaClass.simpleName }} " +
            "error.code=${(err?.members?.get("code") as? Json.Value.StringValue)?.value} " +
            "message=${(err?.members?.get("message") as? Json.Value.StringValue)?.value}"
        // 这些探针都必须在写入前被拒；ok=true 说明上游行为变了，标 FAIL 以便人工核查
        if (ok == true) fail("mcp-$label", "意外成功（可能已写入 mcp.json，需人工核查）：$line") else info("mcp-$label", line)
    }
}
