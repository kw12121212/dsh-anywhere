package work.keepwriting.core.spike

import work.keepwriting.core.Json
import work.keepwriting.core.JsonString
import java.time.ZoneId

/**
 * 用量探针组（dsbot-usage）：只读登记 usage 与 usage-summary 的应答键、行数、owners/models/kinds 计数、
 * 小时键与 now 形态、scanning 与应答字节数（不打印名称、模型 id 与 token 数值）；
 * 另以不存在的 Bot 取 usage-summary 错误形态、以非法时区验证服务端回退。
 * dump 打开时额外写出结构脱敏的夹具候选 usage-view.json（名称与键替换为序号，数值替换为合成值，只留最近 48 行）。
 */
internal class ProbesUsage(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    private val zone = ZoneId.systemDefault().id
    private val hourKey = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}$")

    fun run(firstBotId: String?) {
        view("us-view", "{\"timeZone\":\"$zone\"}", fixture = true)
        view("us-view-bad-zone", "{\"timeZone\":\"Not/AZone\"}", fixture = false)
        if (firstBotId != null) summary("us-summary", "{\"botId\":${JsonString.of(firstBotId)},\"timeZone\":\"$zone\"}")
        record("us-summary-unknown", "usage-summary", "{\"botId\":\"spike-none\"}")
    }

    private fun view(check: String, payload: String, fixture: Boolean) {
        val res = try {
            post("usage", payload)
        } catch (e: Exception) {
            fail(check, "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val bytes = res.bodyText().toByteArray().size
        val v = parseOk(check, res) as? Json.Value.Object ?: return
        fun arr(k: String) = (v.members[k] as? Json.Value.Array)?.items.orEmpty()
        val owners = arr("owners").mapNotNull { it as? Json.Value.Object }
        val models = arr("models").mapNotNull { it as? Json.Value.Object }
        val kinds = arr("kinds").mapNotNull { (it as? Json.Value.StringValue)?.value }
        val rows = arr("rows").mapNotNull { it as? Json.Value.Array }
        val ownerKinds = owners.mapNotNull { (it.members["kind"] as? Json.Value.StringValue)?.value }.groupingBy { it }.eachCount()
        val deleted = owners.count { (it.members["deleted"] as? Json.Value.BoolValue)?.value == true }
        val ownerKeySets = owners.map { it.members.keys.sorted().joinToString(",") }.toSet()
        val modelKeySets = models.map { it.members.keys.sorted().joinToString(",") }.toSet()
        val tupleSizes = rows.map { it.items.size }.groupingBy { it }.eachCount()
        val hours = rows.mapNotNull { (it.items.firstOrNull() as? Json.Value.StringValue)?.value }
        val badHours = hours.count { !hourKey.matches(it) }
        val sorted = hours.zipWithNext().all { (a, b) -> a <= b }
        val now = (v.members["now"] as? Json.Value.StringValue)?.value
        pass(
            check,
            "键=${v.members.keys.sorted()} 字节=$bytes rows=${rows.size} 元组长度=$tupleSizes 小时键非法=$badHours 升序=$sorted " +
                "最早月=${hours.minOrNull()?.take(7)} now形态=${now?.let { hourKey.matches(it) }} scanning=${(v.members["scanning"] as? Json.Value.BoolValue)?.value} " +
                "owners=${owners.size} ownerKind=$ownerKinds deleted=$deleted owner键集=$ownerKeySets models=${models.size} model键集=$modelKeySets kinds=$kinds",
        )
        if (fixture) dump("usage-view.json", fixtureOf(owners, models, kinds, rows, now))
    }

    /** 结构脱敏的夹具候选：名称与键替换为序号，token 数值替换为合成值，只留最近 48 行。 */
    private fun fixtureOf(
        owners: List<Json.Value.Object>,
        models: List<Json.Value.Object>,
        kinds: List<String>,
        rows: List<Json.Value.Array>,
        now: String?,
    ): String {
        fun s(o: Json.Value.Object, k: String) = (o.members[k] as? Json.Value.StringValue)?.value
        val ownerJson = owners.mapIndexed { i, o ->
            val kind = s(o, "kind") ?: "other"
            val key = when (kind) {
                "other" -> "other"
                "room" -> "room:room-${i + 1}"
                "agent" -> "agent:agent-${i + 1}"
                else -> "bot-${i + 1}"
            }
            buildString {
                append("{\"key\":").append(JsonString.of(key)).append(",\"kind\":").append(JsonString.of(kind))
                if (kind != "other") {
                    append(",\"id\":").append(JsonString.of(key.substringAfter(':'))).append(",\"name\":").append(JsonString.of("Owner ${i + 1}"))
                }
                if ((o.members["deleted"] as? Json.Value.BoolValue)?.value == true) append(",\"deleted\":true")
                append('}')
            }
        }
        val modelJson = models.mapIndexed { i, o ->
            "{\"key\":\"provider-${i + 1}/model-${i + 1}\",\"provider\":\"provider-${i + 1}\",\"model\":\"model-${i + 1}\",\"name\":\"Model ${i + 1}\"" +
                (if (o.members.containsKey("providerName")) ",\"providerName\":\"Provider ${i + 1}\"" else "") + "}"
        }
        val rowJson = rows.takeLast(48).mapIndexed { i, r ->
            val h = (r.items.getOrNull(0) as? Json.Value.StringValue)?.value ?: ""
            val idx = (1..3).map { (r.items.getOrNull(it) as? Json.Value.NumberValue)?.raw ?: "0" }
            val n = (i % 7) + 1
            "[${JsonString.of(h)},${idx.joinToString(",")},${n * 1000},${n * 4000},${n * 100},${n * 200},$n]"
        }
        return "{\"ok\":true,\"value\":{\"owners\":[${ownerJson.joinToString(",")}],\"models\":[${modelJson.joinToString(",")}]," +
            "\"kinds\":[${kinds.joinToString(",") { JsonString.of(it) }}],\"rows\":[\n${rowJson.joinToString(",\n")}\n]," +
            "\"now\":${JsonString.of(now ?: "")},\"scanning\":false}}"
    }

    private fun summary(check: String, payload: String) {
        val res = try {
            post("usage-summary", payload)
        } catch (e: Exception) {
            fail(check, "请求异常 ${e.javaClass.simpleName}")
            return
        }
        val v = parseOk(check, res) as? Json.Value.Object ?: return
        fun keys(k: String) = (v.members[k] as? Json.Value.Object)?.members?.keys?.sorted()
        val models = (v.members["models"] as? Json.Value.Array)?.items.orEmpty().mapNotNull { it as? Json.Value.Object }
        pass(
            check,
            "键=${v.members.keys.sorted()} day键=${keys("day")} week键=${keys("week")} month键=${keys("month")} " +
                "models=${models.size} model键集=${models.map { it.members.keys.sorted().joinToString(",") }.toSet()} " +
                "scanning=${(v.members["scanning"] as? Json.Value.BoolValue)?.value}",
        )
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
