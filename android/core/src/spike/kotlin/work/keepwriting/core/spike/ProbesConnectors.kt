package work.keepwriting.core.spike

import work.keepwriting.core.Json

/**
 * 连接器与邮箱探针组（dsbot-connectors-mail）：只读登记 state 中 live connectors、connectorRequests、mailRequests 的形态，
 * 导入扫描只记数量与来源；写端点只取服务端在任何写入前就拒绝的错误形态（不存在的 id、无效名称或地址、空输入），
 * 不改动真实连接器、邮箱与规则（mail-rule-set 即使空补丁也会保存，不探）。输出只含键、种类、计数与错误原文，
 * 不打印连接器账号、邮箱地址、收件人、邮件内容、授权地址或任何凭据。
 */
internal class ProbesConnectors(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    private val none = "\"spike-none\""

    fun run() {
        stateShape()
        importScan()
        // connectors/index.js known() 在任何写入前抛 No such connector
        record("connector-retry", "{\"id\":$none}")
        record("connector-sign-in-code", "{\"id\":$none,\"input\":\"x\"}")
        record("connector-set", "{\"id\":$none,\"readOnly\":true}")
        record("connector-remove", "{\"id\":$none}")
        // connector-add 的名称与地址校验在 customId/saveState 之前
        record("connector-add-no-name", "connector-add", "{\"name\":\"\"}")
        record("connector-add-http", "connector-add", "{\"name\":\"spike\",\"url\":\"http://example.com/mcp\"}")
        record("connector-request-cancel", "{\"requestId\":$none}")
        // mail/index.js：找不到账号/卡片、地址格式不对、没有等待中的 Google 登录或空输入，均在写入前拒绝
        record("mail-account-remove", "{\"id\":$none}")
        record("mail-send-answer", "{\"requestId\":$none,\"choice\":\"cancel\"}")
        record("mail-account-add-bad-address", "mail-account-add", "{\"address\":\"spike-not-an-address\",\"password\":\"x\"}")
        record("mail-sign-in-code-empty", "mail-sign-in-code", "{\"input\":\"\"}")
    }

    private fun stateShape() {
        val res = post("state", "{}")
        val v = parseOk("conn-state", res) as? Json.Value.Object ?: return
        val c = v.members["connectors"]
        if (c !is Json.Value.Object) {
            fail("conn-view", "state.connectors 不是对象（${c?.javaClass?.simpleName ?: "缺失"}）")
            return
        }
        val rows = (c.members["connectors"] as? Json.Value.Array)?.items?.mapNotNull { it as? Json.Value.Object }.orEmpty()
        val states = rows.groupingBy { str(it, "state") ?: "?" }.eachCount()
        val auths = rows.groupingBy { (it.members["auth"] as? Json.Value.Object)?.let { a -> str(a, "kind") } ?: "?" }.eachCount()
        val categories = rows.groupingBy { str(it, "category") ?: "?" }.eachCount()
        val keySets = rows.map { it.members.keys.sorted().joinToString(",") }.toSet()
        val pending = rows.mapNotNull { (it.members["pending"] as? Json.Value.Object)?.let { p -> str(p, "kind") } }
        val customs = rows.count { (it.members["custom"] as? Json.Value.BoolValue)?.value == true }
        val withReadOnly = rows.count { it.members["readOnly"] is Json.Value.BoolValue }
        pass("conn-view", "state.connectors 键=${c.members.keys.sorted()} 行=${rows.size} 状态=$states 鉴权=$auths 类别=$categories 自加=$customs 带readOnly=$withReadOnly pending=$pending；键集=${keySets.joinToString(" | ")}")
        val mail = c.members["mail"] as? Json.Value.Object
        if (mail == null) {
            fail("conn-mail", "connectors.mail 缺失")
        } else {
            val accounts = (mail.members["accounts"] as? Json.Value.Array)?.items?.mapNotNull { it as? Json.Value.Object }.orEmpty()
            val accountKeys = accounts.map { it.members.keys.sorted().joinToString(",") }.toSet()
            val google = (mail.members["google"] as? Json.Value.BoolValue)?.value
            val allowed = (mail.members["allowedRecipients"] as? Json.Value.Array)?.items?.size
            val noDraft = (mail.members["noDraftFor"] as? Json.Value.Array)?.items?.size
            pass("conn-mail", "mail 键=${mail.members.keys.sorted()} 账号=${accounts.size} 账号键集=${accountKeys.joinToString(" | ")} google=$google 允许收件人=$allowed 直接发送Bot=$noDraft")
        }
        requests(v, "connectorRequests")
        requests(v, "mailRequests")
    }

    private fun requests(v: Json.Value.Object, key: String) {
        val r = v.members[key]
        if (r !is Json.Value.Object) {
            fail("conn-$key", "state.$key 不是对象（${r?.javaClass?.simpleName ?: "缺失"}）")
            return
        }
        val entries = r.members.values.flatMap { (it as? Json.Value.Array)?.items.orEmpty() }.mapNotNull { it as? Json.Value.Object }
        val statuses = entries.groupingBy { str(it, "status") ?: "?" }.eachCount()
        val keySets = entries.map { it.members.keys.sorted().joinToString(",") }.toSet()
        pass("conn-$key", "state.$key 为对象：botId 键=${r.members.size} 条目=${entries.size} 状态=$statuses 键集=${keySets.joinToString(" | ")}")
    }

    private fun importScan() {
        val res = try {
            post("connector-import-scan", "{}")
        } catch (e: Exception) {
            fail("conn-import-scan", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val v = parseOk("conn-import-scan", res) ?: return
        val items = (v as? Json.Value.Array)?.items?.mapNotNull { it as? Json.Value.Object }
        if (items == null) {
            fail("conn-import-scan", "应答不是数组（${v.javaClass.simpleName}）")
            return
        }
        val sources = items.groupingBy { str(it, "source") ?: "?" }.eachCount()
        val keySets = items.map { it.members.keys.sorted().joinToString(",") }.toSet()
        pass("conn-import-scan", "数组 ${items.size} 项 来源=$sources 键集=${keySets.joinToString(" | ")}")
    }

    private fun str(o: Json.Value.Object, k: String) = (o.members[k] as? Json.Value.StringValue)?.value

    private fun record(endpoint: String, payload: String) = record(endpoint, endpoint, payload)

    private fun record(label: String, endpoint: String, payload: String) {
        val res = try {
            post(endpoint, payload)
        } catch (e: Exception) {
            fail("conn-$label", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val root = Json.parse(res.bodyText()) as? Json.Value.Object
        val ok = (root?.members?.get("ok") as? Json.Value.BoolValue)?.value
        val err = root?.members?.get("error") as? Json.Value.Object
        val code = (err?.members?.get("code") as? Json.Value.StringValue)?.value
        val message = (err?.members?.get("message") as? Json.Value.StringValue)?.value
        info("conn-$label", "POST $endpoint → HTTP ${res.status} ok=$ok error.code=$code message=$message")
    }
}
