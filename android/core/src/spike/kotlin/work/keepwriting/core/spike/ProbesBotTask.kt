package work.keepwriting.core.spike

import work.keepwriting.core.Json
import work.keepwriting.core.JsonString

/**
 * 后台任务详情探针组（dsbot-bot-task-detail）：只读登记 bot-task-read 与 bot-task-send 的错误形态，
 * 若 state.bots[].tasks 中已存在真实任务则只读查看 bot-task-read（默认与 steps=true）的应答键、
 * 条目 kind 分布、steps 工具键集与 cursor 形态（不打印任何文本、名称或标题）；
 * 遵守 spike-write-probes-non-mutating：bot-task-send 只用不存在的 Bot/任务 id，绝不向真实任务发送消息。
 */
internal class ProbesBotTask(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    fun run(stateVal: Json.Value?, firstBotId: String?) {
        record("bt-read-unknown-bot", "bot-task-read", """{"botId":"spike-none","taskId":"spike-none-task"}""")
        record("bt-send-unknown-bot", "bot-task-send", """{"botId":"spike-none","taskId":"spike-none-task","text":"probe"}""")
        if (firstBotId != null) {
            val bid = JsonString.of(firstBotId)
            record("bt-read-unknown-task", "bot-task-read", """{"botId":$bid,"taskId":"spike-none-task"}""")
            record("bt-send-unknown-task", "bot-task-send", """{"botId":$bid,"taskId":"spike-none-task","text":"probe"}""")
        }
        val sample = findExistingTask(stateVal)
        if (sample == null) {
            info("bt-read-existing", "当前 state.bots[].tasks 无已有任务样本（按源码核对形态实现）")
            return
        }
        val (botId, taskId, statusCount) = sample
        info("bt-state-tasks", "state 中发现任务：状态分布=$statusCount（取首条只读查形态，不回显 id/标题）")
        val bid = JsonString.of(botId)
        val tid = JsonString.of(taskId)
        val cursor = probeRead("bt-read-plain", """{"botId":$bid,"taskId":$tid}""")
        probeRead("bt-read-steps", """{"botId":$bid,"taskId":$tid,"steps":true}""")
        if (cursor != null) {
            probeRead("bt-read-earlier", """{"botId":$bid,"taskId":$tid,"cursor":${Json.encode(cursor)},"steps":true}""")
        }
    }

    private fun findExistingTask(stateVal: Json.Value?): Triple<String, String, Map<String, Int>>? {
        val bots = ((stateVal as? Json.Value.Object)?.members?.get("bots") as? Json.Value.Array)?.items ?: return null
        val statuses = mutableMapOf<String, Int>()
        var firstPair: Pair<String, String>? = null
        for (b in bots) {
            val bo = b as? Json.Value.Object ?: continue
            val bid = (bo.members["id"] as? Json.Value.StringValue)?.value ?: continue
            val tasks = (bo.members["tasks"] as? Json.Value.Array)?.items ?: continue
            for (t in tasks) {
                val to = t as? Json.Value.Object ?: continue
                val tid = (to.members["id"] as? Json.Value.StringValue)?.value ?: continue
                val st = (to.members["status"] as? Json.Value.StringValue)?.value ?: "unknown"
                statuses[st] = (statuses[st] ?: 0) + 1
                if (firstPair == null) firstPair = bid to tid
            }
        }
        val pair = firstPair ?: return null
        return Triple(pair.first, pair.second, statuses)
    }

    private fun probeRead(check: String, payload: String): Json.Value? {
        val res = try {
            post("bot-task-read", payload)
        } catch (e: Exception) {
            fail(check, "请求异常 ${e.javaClass.simpleName}")
            return null
        }
        val v = parseOk(check, res) as? Json.Value.Object ?: return null
        if (check == "bt-read-steps") dumpJson("bot-task-read-steps", res)
        val items = (v.members["items"] as? Json.Value.Array)?.items.orEmpty().mapNotNull { it as? Json.Value.Object }
        val kinds = items.mapNotNull { (it.members["kind"] as? Json.Value.StringValue)?.value }.groupingBy { it }.eachCount()
        val itemKeys = items.groupBy { (it.members["kind"] as? Json.Value.StringValue)?.value ?: "?" }
            .mapValues { (_, list) -> list.flatMap { it.members.keys }.toSortedSet() }
        val stepToolKeys = items.filter { (it.members["kind"] as? Json.Value.StringValue)?.value == "steps" }
            .flatMap { (it.members["tools"] as? Json.Value.Array)?.items.orEmpty() }
            .mapNotNull { it as? Json.Value.Object }
            .flatMap { it.members.keys }.toSortedSet()
        val cursorRaw = v.members["cursor"]
        val cursorDesc = when (cursorRaw) {
            null -> "absent"
            is Json.Value.NullValue -> "null"
            is Json.Value.Object -> "obj(${cursorRaw.members.keys.sorted()})"
            else -> cursorRaw.javaClass.simpleName
        }
        pass(
            check,
            "顶层键=${v.members.keys.sorted()} 条数=${items.size} kind分布=$kinds 各kind键集=$itemKeys steps工具键=$stepToolKeys cursor=$cursorDesc",
        )
        return cursorRaw?.takeUnless { it is Json.Value.NullValue }
    }

    private fun record(label: String, endpoint: String, payload: String) {
        val res = try {
            post(endpoint, payload)
        } catch (e: Exception) {
            fail(label, "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val root = Json.parse(res.bodyText()) as? Json.Value.Object
        val err = root?.members?.get("error") as? Json.Value.Object
        info(
            label,
            "POST $endpoint → HTTP ${res.status} ok=${(root?.members?.get("ok") as? Json.Value.BoolValue)?.value} " +
                "error.code=${(err?.members?.get("code") as? Json.Value.StringValue)?.value} " +
                "message=${(err?.members?.get("message") as? Json.Value.StringValue)?.value}",
        )
    }
}
