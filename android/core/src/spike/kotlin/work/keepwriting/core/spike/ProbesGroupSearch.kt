package work.keepwriting.core.spike

import work.keepwriting.core.Json
import work.keepwriting.core.JsonString

/**
 * 群内搜索探针组（dsbot-group-search）：只读核对 group-search {roomId, query, before?, offset?}
 * （上游 READS 内的只读端点）的应答形态——命中、无命中、空白查询、未知群错误与 before/offset 分页。
 * 只记键集、计数、类型与错误形态，不打印群名、成员名、查询词以外的正文；命中首页经
 * Sanitize.json 脱敏写出供夹具使用。探针查询词只用常见单字符（"e"、"的"）与不可能命中的
 * 合成串，不从群消息正文取词；遵守 spike-write-probes-non-mutating。
 */
internal class ProbesGroupSearch(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    fun run(stateVal: Json.Value?) {
        record("gs-unknown-room", """{"roomId":"spike-none","query":"e"}""")
        val roomIds = roomIdsOf(stateVal)
        if (roomIds.isEmpty()) {
            info("gs-rooms", "state.rooms 为空，跳过真实群探针（按源码核对形态）")
            return
        }
        info("gs-rooms", "state.rooms 共 ${roomIds.size} 个群（不回显 id 与名称）")
        val first = JsonString.of(roomIds.first())
        record("gs-blank-query", """{"roomId":$first,"query":"   "}""")
        record("gs-no-hit", """{"roomId":$first,"query":"zzqxjv-spike-none"}""")
        // 选命中最多的群与常见单字符查询，尽量触发 hasMore 分页
        var best: Triple<String, String, Json.Value.Object>? = null
        var bestCount = -1
        roomIds.forEachIndexed { index, id ->
            for (q in listOf("e", "的")) {
                val payload = """{"roomId":${JsonString.of(id)},"query":${JsonString.of(q)}}"""
                val v = page("gs-hit-$index-${if (q == "e") "latin" else "cjk"}", payload) ?: continue
                val count = resultsOf(v).size + if (hasMore(v)) 1000 else 0
                if (count > bestCount) {
                    bestCount = count
                    best = Triple(id, q, v)
                }
            }
        }
        val (roomId, query, firstPage) = best ?: return
        dumpJson("group-search-page1", post("group-search", """{"roomId":${JsonString.of(roomId)},"query":${JsonString.of(query)}}"""))
        val results = resultsOf(firstPage)
        val seqs = results.mapNotNull { (it.members["seq"] as? Json.Value.NumberValue)?.raw?.toLongOrNull() }
        info(
            "gs-order",
            "首页 ${results.size} 条，seq 全为整数=${seqs.size == results.size}，按新到旧（seq 递减）=${seqs.zipWithNext().all { (a, b) -> a > b }}",
        )
        if (!hasMore(firstPage)) {
            info("gs-paging", "命中最多的查询 hasMore=false，未能实测分页（按源码核对形态）")
            return
        }
        val oldest = seqs.lastOrNull() ?: return
        val base = """"roomId":${JsonString.of(roomId)},"query":${JsonString.of(query)}"""
        val byBefore = page("gs-page2-before", "{$base,\"before\":$oldest}")
        val byOffset = page("gs-page2-offset", "{$base,\"offset\":${results.size}}")
        if (byBefore != null && byOffset != null) {
            val a = resultsOf(byBefore).map { it.members["seq"].toString() }
            val b = resultsOf(byOffset).map { it.members["seq"].toString() }
            val overlap = a.intersect(seqs.map { it.toString() }.toSet()).size
            info("gs-paging", "before 页与 offset 页 seq 序列相同=${a == b}，before 页与首页重叠=$overlap，before 页 seq 全小于 $oldest=${resultsOf(byBefore).all { ((it.members["seq"] as? Json.Value.NumberValue)?.raw?.toLongOrNull() ?: Long.MAX_VALUE) < oldest }}")
        }
    }

    private fun roomIdsOf(stateVal: Json.Value?): List<String> {
        val rooms = ((stateVal as? Json.Value.Object)?.members?.get("rooms") as? Json.Value.Array)?.items ?: return emptyList()
        return rooms.mapNotNull { ((it as? Json.Value.Object)?.members?.get("id") as? Json.Value.StringValue)?.value }
    }

    private fun resultsOf(v: Json.Value.Object): List<Json.Value.Object> =
        (v.members["results"] as? Json.Value.Array)?.items.orEmpty().mapNotNull { it as? Json.Value.Object }

    private fun hasMore(v: Json.Value.Object): Boolean = (v.members["hasMore"] as? Json.Value.BoolValue)?.value == true

    private fun page(check: String, payload: String): Json.Value.Object? {
        val res = try {
            post("group-search", payload)
        } catch (e: Exception) {
            fail(check, "请求异常 ${e.javaClass.simpleName}")
            return null
        }
        val v = parseOk(check, res) as? Json.Value.Object ?: return null
        val results = resultsOf(v)
        fun keysOf(key: String) = results.mapNotNull { it.members[key] as? Json.Value.Object }.flatMap { it.members.keys }.toSortedSet()
        pass(
            check,
            "顶层键=${v.members.keys.sorted()} results=${results.size} hasMore=${typeOf(v.members["hasMore"])} " +
                "命中键集=${results.flatMap { it.members.keys }.toSortedSet()} before键集=${keysOf("before")} after键集=${keysOf("after")} " +
                "带botId=${results.count { it.members.containsKey("botId") }} 带name=${results.count { it.members.containsKey("name") }} " +
                "time类型=${results.map { typeOf(it.members["time"]) }.toSet()}",
        )
        return v
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

    private fun record(label: String, payload: String) {
        val res = try {
            post("group-search", payload)
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
            "POST group-search → HTTP ${res.status} ok=${(root?.members?.get("ok") as? Json.Value.BoolValue)?.value} " +
                "value=$shape error.code=${(err?.members?.get("code") as? Json.Value.StringValue)?.value} " +
                "message=${(err?.members?.get("message") as? Json.Value.StringValue)?.value}",
        )
    }
}
