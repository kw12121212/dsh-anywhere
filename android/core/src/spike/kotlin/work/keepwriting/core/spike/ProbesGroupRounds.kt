package work.keepwriting.core.spike

import work.keepwriting.core.Json
import work.keepwriting.core.JsonString

/**
 * 群轮次探针组（dsbot-group-rounds）：只读核对手机端 room-chat 对群轮次的呈现，以及
 * group-rows、group-relay-replies、changes-check 三个 READS 端点的应答形态。
 * 只记键集、计数、类型与错误形态，不打印群名、成员名与正文；room-chat 首页经
 * Sanitize.json 脱敏写出（每个群一份）供夹具使用。group-relay-replies 只用不含省略标记的预览，
 * 上游在匹配标记前即返回 null，不打开群账本；遵守 spike-write-probes-non-mutating。
 */
internal class ProbesGroupRounds(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    fun run(stateVal: Json.Value?) {
        record("gr-rows-unknown-room", "group-rows", """{"roomId":"spike-none","from":1}""")
        record("gr-relay-unknown", "group-relay-replies", """{"sessionId":"spike-none","preview":"probe"}""")
        record("gr-changes-unknown", "changes-check", """{"sessionId":"spike-none","seq":1}""")
        val roomIds = roomIdsOf(stateVal)
        if (roomIds.isEmpty()) {
            info("gr-rooms", "state.rooms 为空，跳过真实群探针（按源码核对形态）")
            return
        }
        info("gr-rooms", "state.rooms 共 ${roomIds.size} 个群（不回显 id 与名称）")
        val first = JsonString.of(roomIds.first())
        record("gr-rows-bad-from", "group-rows", """{"roomId":$first,"from":"x"}""")
        record("gr-relay-no-marker", "group-relay-replies", """{"sessionId":$first,"preview":"probe"}""")
        roomIds.forEachIndexed { index, id -> probeRoomChat(index, JsonString.of(id)) }
        probeRows("gr-rows-first-round", """{"roomId":$first,"from":1,"to":1}""")
    }

    private fun roomIdsOf(stateVal: Json.Value?): List<String> {
        val rooms = ((stateVal as? Json.Value.Object)?.members?.get("rooms") as? Json.Value.Array)?.items ?: return emptyList()
        return rooms.mapNotNull { ((it as? Json.Value.Object)?.members?.get("id") as? Json.Value.StringValue)?.value }
    }

    private fun probeRoomChat(index: Int, roomJson: String) {
        val check = "gr-room-chat-$index"
        val res = try {
            post("room-chat", """{"roomId":$roomJson}""")
        } catch (e: Exception) {
            fail(check, "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val v = parseOk(check, res) as? Json.Value.Object ?: return
        dumpJson("room-chat-rounds-$index", res)
        val items = (v.members["items"] as? Json.Value.Array)?.items.orEmpty().mapNotNull { it as? Json.Value.Object }
        fun kindOf(o: Json.Value.Object) = (o.members["kind"] as? Json.Value.StringValue)?.value ?: "?"
        val kinds = items.groupingBy { kindOf(it) }.eachCount()
        val keys = items.groupBy { kindOf(it) }.mapValues { (_, list) -> list.flatMap { it.members.keys }.toSortedSet() }
        val events = items.filter { kindOf(it) == "event" }
            .mapNotNull { (it.members["event"] as? Json.Value.StringValue)?.value }.groupingBy { it }.eachCount()
        val roundIds = items.count { ((it.members["id"] as? Json.Value.StringValue)?.value ?: "").contains(':') }
        val live = items.count { (it.members["live"] as? Json.Value.BoolValue)?.value == true }
        val withDuration = items.count { it.members["duration"] is Json.Value.NumberValue }
        pass(
            check,
            "顶层键=${v.members.keys.sorted()} running=${typeOf(v.members["running"])} speaking=${typeOf(v.members["speaking"])} " +
                "条数=${items.size} kind分布=$kinds event分布=$events 各kind键集=$keys 轮次行(id含:)=$roundIds live=$live duration=$withDuration",
        )
    }

    private fun probeRows(check: String, payload: String) {
        val res = try {
            post("group-rows", payload)
        } catch (e: Exception) {
            fail(check, "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val v = parseOk(check, res) as? Json.Value.Object ?: return
        val rows = (v.members["rows"] as? Json.Value.Array)?.items.orEmpty().mapNotNull { it as? Json.Value.Object }
        pass(
            check,
            "顶层键=${v.members.keys.sorted()} rows=${rows.size} 行键集=${rows.flatMap { it.members.keys }.toSortedSet()} " +
                "open=${typeOf(v.members["open"])} active=${typeOf(v.members["active"])} speaking=${typeOf(v.members["speaking"])}",
        )
    }

    private fun typeOf(value: Json.Value?): String = when (value) {
        null -> "absent"
        is Json.Value.NullValue -> "null"
        is Json.Value.BoolValue -> "bool(${value.value})"
        is Json.Value.StringValue -> "string"
        is Json.Value.NumberValue -> "number"
        is Json.Value.Array -> "array(${value.items.size})"
        is Json.Value.Object -> "object"
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
        val value = root?.members?.get("value")
        val shape = (value as? Json.Value.Object)?.let { o -> o.members.mapValues { typeOf(it.value) }.toSortedMap().toString() } ?: typeOf(value)
        info(
            label,
            "POST $endpoint → HTTP ${res.status} ok=${(root?.members?.get("ok") as? Json.Value.BoolValue)?.value} " +
                "value=$shape error.code=${(err?.members?.get("code") as? Json.Value.StringValue)?.value} " +
                "message=${(err?.members?.get("message") as? Json.Value.StringValue)?.value}",
        )
    }
}
