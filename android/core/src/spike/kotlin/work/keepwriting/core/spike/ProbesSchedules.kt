package work.keepwriting.core.spike

import work.keepwriting.core.Json

/**
 * 定时任务探针组（dsbot-schedules）：只读登记 schedules 应答的键、available、canPause 与任务形态分布
 *（不打印标题、正文与会话 id）；写端点只以不存在的对象或任务 id 取错误形态，不改动用户真实任务。
 */
internal class ProbesSchedules(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    private val none = "\"spike-none\""

    fun run() {
        list()
        record("create-unknown", "schedule-create", "{\"target\":$none,\"title\":\"spike\",\"prompt\":\"spike\",\"when\":{\"kind\":\"every\",\"minutes\":60}}")
        record("create-empty-title", "schedule-create", "{\"target\":$none,\"title\":\"\",\"prompt\":\"spike\",\"when\":{\"kind\":\"every\",\"minutes\":60}}")
        record("pause-unknown", "schedule-pause", "{\"sessionId\":$none,\"id\":$none,\"active\":false}")
        record("delete-unknown", "schedule-delete", "{\"sessionId\":$none,\"id\":$none}")
    }

    private fun list() {
        val res = try {
            post("schedules", "{}")
        } catch (e: Exception) {
            fail("sc-list", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val v = parseOk("sc-list", res) as? Json.Value.Object ?: return
        val tasks = (v.members["tasks"] as? Json.Value.Array)?.items?.mapNotNull { it as? Json.Value.Object }.orEmpty()
        val kinds = tasks.mapNotNull { (it.members["kind"] as? Json.Value.StringValue)?.value }.groupingBy { it }.eachCount()
        val statuses = tasks.mapNotNull { (it.members["status"] as? Json.Value.StringValue)?.value }.groupingBy { it }.eachCount()
        val states = tasks.mapNotNull { (it.members["state"] as? Json.Value.StringValue)?.value }.groupingBy { it }.eachCount()
        val owners = tasks.groupingBy { t ->
            when {
                t.members["botId"] is Json.Value.StringValue -> "bot"
                t.members["roomId"] is Json.Value.StringValue -> "room"
                else -> "other"
            }
        }.eachCount()
        val keySets = tasks.map { it.members.keys.sorted().joinToString(",") }.toSet()
        pass(
            "sc-list",
            "键=${v.members.keys.sorted()} available=${(v.members["available"] as? Json.Value.BoolValue)?.value} " +
                "canPause=${(v.members["canPause"] as? Json.Value.BoolValue)?.value} tasks=${tasks.size} " +
                "kind=$kinds status=$statuses state=$states owner=$owners task键集=$keySets",
        )
    }

    private fun record(label: String, endpoint: String, payload: String) {
        val res = try {
            post(endpoint, payload)
        } catch (e: Exception) {
            fail("sc-$label", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val root = Json.parse(res.bodyText()) as? Json.Value.Object
        val err = root?.members?.get("error") as? Json.Value.Object
        info(
            "sc-$label",
            "POST $endpoint → HTTP ${res.status} ok=${(root?.members?.get("ok") as? Json.Value.BoolValue)?.value} " +
                "error.code=${(err?.members?.get("code") as? Json.Value.StringValue)?.value} " +
                "message=${(err?.members?.get("message") as? Json.Value.StringValue)?.value}",
        )
    }
}
