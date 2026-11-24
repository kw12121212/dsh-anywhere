package work.keepwriting.core.spike

import work.keepwriting.core.Json

/**
 * IM 接入探针组（dsbot-im-bridges）：只读登记 state.im 与 live imStatus 的形态；写端点只取服务端在任何写入前就拒绝的
 * 错误形态（不存在的连接 id、不可用的平台、没有扫码登录的平台），不改动真实 IM 连接。输出只含键、种类、计数与错误原文，
 * 不打印账号名、配对账号、聊天标题或任何凭据。
 */
internal class ProbesIm(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    private val none = "\"spike-none\""

    fun run() {
        stateShape()
        // im/index.js：connections()[id] 不存在即拒绝
        record("im-update", "{\"id\":$none,\"enabled\":false}")
        record("im-remove", "{\"id\":$none}")
        record("im-pair", "{\"id\":$none,\"senderId\":$none,\"allow\":false}")
        record("im-unpair", "{\"id\":$none,\"senderId\":$none}")
        record("im-chat", "{\"id\":$none,\"chatKey\":$none,\"remove\":true}")
        record("im-retry", "{\"id\":$none}")
        // 平台不可用、平台没有扫码登录：在校验账号与创建连接之前拒绝
        record("im-add-unknown-platform", "im-add", "{\"platform\":$none,\"botId\":$none,\"fields\":{}}")
        record("im-login-start-no-qr", "im-login-start", "{\"platform\":\"telegram\",\"botId\":$none}")
        record("im-login-wait-no-qr", "im-login-wait", "{\"platform\":\"telegram\",\"botId\":$none,\"loginId\":$none}")
    }

    private fun stateShape() {
        val res = post("state", "{}")
        val v = parseOk("im-state", res) as? Json.Value.Object ?: return
        val im = v.members["im"]
        if (im !is Json.Value.Object) {
            fail("im-view", "state.im 不是对象（${im?.javaClass?.simpleName ?: "缺失"}）")
            return
        }
        val platforms = (im.members["platforms"] as? Json.Value.Array)?.items?.mapNotNull { (it as? Json.Value.StringValue)?.value }
        val conns = (im.members["connections"] as? Json.Value.Array)?.items?.mapNotNull { it as? Json.Value.Object }.orEmpty()
        val keySets = conns.map { it.members.keys.sorted().joinToString(",") }.toSet()
        val byPlatform = conns.groupingBy { (it.members["platform"] as? Json.Value.StringValue)?.value ?: "?" }.eachCount()
        pass("im-view", "state.im 键=${im.members.keys.sorted()} platforms=$platforms 连接=${conns.size} 平台分布=$byPlatform 键集=${keySets.joinToString(" | ")}")
        val status = v.members["imStatus"]
        if (status is Json.Value.Object) {
            val entries = status.members.values.mapNotNull { it as? Json.Value.Object }
            val dist = entries.groupingBy { (it.members["state"] as? Json.Value.StringValue)?.value ?: "?" }.eachCount()
            pass("im-status", "imStatus 为对象：连接键=${status.members.size} 状态=$dist 条目键=${entries.map { it.members.keys.sorted().joinToString(",") }.toSet()}")
        } else {
            fail("im-status", "imStatus 不是对象（${status?.javaClass?.simpleName ?: "缺失"}）")
        }
    }

    private fun record(endpoint: String, payload: String) = record(endpoint, endpoint, payload)

    private fun record(label: String, endpoint: String, payload: String) {
        val res = try {
            post(endpoint, payload)
        } catch (e: Exception) {
            fail("im-$label", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val root = Json.parse(res.bodyText()) as? Json.Value.Object
        val ok = (root?.members?.get("ok") as? Json.Value.BoolValue)?.value
        val err = root?.members?.get("error") as? Json.Value.Object
        val code = (err?.members?.get("code") as? Json.Value.StringValue)?.value
        val message = (err?.members?.get("message") as? Json.Value.StringValue)?.value
        info("im-$label", "POST $endpoint → HTTP ${res.status} ok=$ok error.code=$code message=$message")
    }
}
