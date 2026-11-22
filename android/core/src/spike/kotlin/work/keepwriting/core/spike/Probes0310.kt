package work.keepwriting.core.spike

import work.keepwriting.core.Json

/**
 * DS-Bot 0.3.10 探针组（dsbot-baseline-0310）：经本项目的 HTTP 路径（POST /api/bot
 * 信封）逐项登记上游手机端用到的新端点。读端点取真实形态；写端点一律用不存在的
 * id 或无效参数只取错误形态——不对真实团队做任何改动，也不发出真实消息。
 *
 * 每项输出一行 INFO/PASS：HTTP 状态、ok、错误码与 value 的顶层键，dump 开启时
 * 落脱敏副本（夹具来源）。hello 与 notices 的形态是后续变更的契约，做结构断言。
 */
internal class Probes0310(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    /** 不存在的 id：写端点只取错误形态。 */
    private val none = "\"spike-none\""

    fun run(botId: String?, botSession: String?, roomId: String?, allBotIds: List<String> = emptyList()) {
        val boot = hello()
        notices(boot)
        stateAvatarRefs()
        if (botId != null) record("avatar", "{\"botId\":${q(botId)}}")
        if (botId != null) record("usage-summary", "{\"botId\":${q(botId)}}")
        if (botSession != null) mediaProbes(botId, botSession, allBotIds, roomId)
        if (roomId != null) record("room-chat-0310", "room-chat", "{\"roomId\":${q(roomId)}}")

        // 写端点：只取错误形态（不存在的 id / 无效参数）
        record("bot-stop-task", "{\"botId\":$none,\"taskId\":$none}")
        record("computer-app-answer", "{\"id\":$none,\"answer\":\"deny\"}")
        record("computer-stop", "{\"botId\":$none}")
        record("learning-answer", "{\"id\":$none,\"answer\":\"skip\"}")
        record("connector-request-cancel", "{\"id\":$none}")
        record("agent-chat", "{\"agentId\":$none}")
        record("stop-agent", "{\"agentId\":$none}")
        record("memory-add", "{\"botId\":$none,\"scope\":\"bot\",\"topic\":\"spike\",\"text\":\"spike\"}")
        record("memory-edit", "{\"botId\":$none,\"scope\":\"bot\",\"topic\":\"spike\",\"text\":\"spike\",\"next\":\"spike\"}")
        record("schedule-pause", "{\"id\":$none,\"paused\":true}")
        record("stage-image-invalid", "stage-image", "{\"mediaType\":\"text/plain\",\"data\":\"\"}")
        record("send-images-invalid", "send", "{\"sessionId\":$none,\"images\":[$none]}")
        record("unknown-endpoint", "spike-unknown-endpoint", "{}")
    }

    /** hello：协商 api=1，断言能力名单与 notices 游标字段；再以不支持的 api 取协商失败形态。 */
    private fun hello(): String? {
        val res = post("hello", "{\"api\":[1]}")
        dumpJson("20-hello", res)
        val v = parseOk("hello", res) as? Json.Value.Object ?: return null
        val api = (v.members["api"] as? Json.Value.NumberValue)?.raw
        val caps = (v.members["capabilities"] as? Json.Value.Array)?.items
            ?.mapNotNull { (it as? Json.Value.StringValue)?.value }
        val boot = (v.members["boot"] as? Json.Value.StringValue)?.value
        val seq = (v.members["seq"] as? Json.Value.NumberValue)?.raw
        val plugin = ((v.members["plugin"] as? Json.Value.Object)?.members?.get("version") as? Json.Value.StringValue)?.value
        if (api == "1" && caps != null && boot != null && seq != null) {
            pass("hello", "api=$api apiMin=${raw(v, "apiMin")} apiMax=${raw(v, "apiMax")} plugin=$plugin seq=$seq " +
                "readOnly=${raw(v, "readOnly")} feed=${raw(v, "feed")}；键=${v.members.keys.joinToString(",")}")
            info("hello-capabilities", caps.joinToString(","))
        } else {
            fail("hello", "结构不全：api=$api caps=${caps?.size} boot=${boot != null} seq=$seq")
        }
        val future = post("hello", "{\"api\":[99]}")
        dumpJson("20-hello-api99", future)
        val fv = parseOk("hello-api99", future) as? Json.Value.Object
        info("hello-api99", "客户端只提供 api=99 → api=${fv?.let { raw(it, "api") }}（协商失败的形态）")
        return boot
    }

    /** notices：同 boot 从 0 取保留的提醒；陌生 boot 必须 reset=true。 */
    private fun notices(boot: String?) {
        if (boot != null) {
            val res = post("notices", "{\"boot\":${q(boot)},\"since\":0}")
            dumpJson("21-notices", res)
            val v = parseOk("notices", res) as? Json.Value.Object
            val list = v?.members?.get("notices") as? Json.Value.Array
            if (v != null && list != null && v.members["reset"] is Json.Value.BoolValue) {
                val kinds = list.items.mapNotNull { ((it as? Json.Value.Object)?.members?.get("kind") as? Json.Value.StringValue)?.value }
                    .groupingBy { it }.eachCount()
                val keys = list.items.flatMap { (it as? Json.Value.Object)?.members?.keys ?: emptySet() }.toSortedSet()
                pass("notices", "reset=${raw(v, "reset")} seq=${raw(v, "seq")} 条数=${list.items.size} kind=$kinds 条目键=$keys")
            } else {
                fail("notices", "结构不全（键=${v?.members?.keys?.joinToString(",")}）")
            }
        }
        val stale = post("notices", "{\"boot\":\"spike-stale-boot\",\"since\":5}")
        dumpJson("21-notices-stale-boot", stale)
        val sv = parseOk("notices-stale-boot", stale) as? Json.Value.Object
        val reset = (sv?.members?.get("reset") as? Json.Value.BoolValue)?.value
        if (reset == true) pass("notices-stale-boot", "陌生 boot → reset=true（客户端须重取整体状态）")
        else fail("notices-stale-boot", "陌生 boot 期望 reset=true，实际 $reset")
    }

    /** state avatars:'ref'：头像图片换成 imageRef 引用。 */
    private fun stateAvatarRefs() {
        val res = post("state", "{\"avatars\":\"ref\"}")
        dumpJson("22-state-avatars-ref", res)
        val v = parseOk("state-avatars-ref", res) as? Json.Value.Object ?: return
        val bots = (v.members["bots"] as? Json.Value.Array)?.items.orEmpty()
        val avatarKeys = bots.mapNotNull { ((it as? Json.Value.Object)?.members?.get("avatar") as? Json.Value.Object)?.members?.keys }
            .flatten().toSortedSet()
        info("state-avatars-ref", "bots=${bots.size} avatar 键=$avatarKeys（image 字段应被 imageRef 取代）")
    }

    /** 媒体：从聊天条目里找第一个附件引用取真实形态，找不到时用不存在的 id 取错误形态。 */
    private fun mediaProbes(botId: String?, fallbackSession: String, allBotIds: List<String>, roomId: String?) {
        // 逐个 Bot 私聊首页与群聊首页找附件样本（附件归属的 sessionId 取自所在页）
        var image: Pair<String, String>? = null
        var file: Pair<String, String>? = null
        val pages = (listOfNotNull(botId) + allBotIds).distinct().map { "chat" to "{\"botId\":${q(it)}}" } +
            listOfNotNull(roomId?.let { "room-chat" to "{\"roomId\":${q(it)}}" })
        for ((ep, payload) in pages) {
            if (image != null && file != null) break
            val page = parseQuiet(post(ep, payload)) as? Json.Value.Object ?: continue
            val sid = (page.members["sessionId"] as? Json.Value.StringValue)?.value
                ?: (page.members["roomId"] as? Json.Value.StringValue)?.value ?: continue
            if (image == null) findAttachment(page, "image")?.let { image = sid to it }
            if (file == null) findAttachment(page, "file")?.let { file = sid to it }
        }
        info("media-samples", "扫描 ${pages.size} 个聊天首页：image 附件=${image != null} file 附件=${file != null}")
        val session = file?.first ?: image?.first ?: fallbackSession

        val imageSession = image?.first ?: fallbackSession
        record("image-preview", "image", "{\"sessionId\":${q(imageSession)},\"attachmentId\":${q(image?.second ?: "spike-none")},\"preview\":true}")
        record("file-text", "{\"file\":{\"attachmentId\":${q(file?.second ?: "spike-none")}}}")
        record("file-download-chunk", "file-download",
            "{\"sessionId\":${q(session)},\"attachmentId\":${q(file?.second ?: image?.second ?: "spike-none")},\"offset\":0}")
        record("file-download-bad-offset", "file-download", "{\"sessionId\":${q(session)},\"attachmentId\":$none,\"offset\":-1}")
        record("markdown-image-missing", "markdown-image", "{\"sessionId\":${q(session)},\"path\":\"/spike-none.png\"}")
        record("markdown-image-scheme", "markdown-image", "{\"sessionId\":${q(session)},\"path\":\"https://example.invalid/a.png\"}")
    }

    private fun findAttachment(value: Json.Value?, kind: String): String? {
        when (value) {
            is Json.Value.Object -> {
                // 0.3.10 用户条目以 imageRefs[] = {attachmentId, mime, name} 引用图片；旧形态是 image 对象
                val att = value.members[kind] as? Json.Value.Object
                    ?: (value.members["${kind}Refs"] as? Json.Value.Array)?.items?.firstOrNull() as? Json.Value.Object
                val id = (att?.members?.get("attachmentId") as? Json.Value.StringValue)?.value
                if (id != null) return id
                for (v in value.members.values) findAttachment(v, kind)?.let { return it }
            }
            is Json.Value.Array -> for (v in value.items) findAttachment(v, kind)?.let { return it }
            else -> {}
        }
        return null
    }

    private fun record(endpoint: String, payload: String) = record(endpoint, endpoint, payload)

    /** 登记一项：状态、ok、错误码或 value 形态（顶层键/类型）。 */
    private fun record(label: String, endpoint: String, payload: String) {
        val res = try {
            post(endpoint, payload)
        } catch (e: Exception) {
            fail("probe-$label", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        dumpJson("23-$label", res)
        val root = Json.parse(res.bodyText()) as? Json.Value.Object
        val ok = (root?.members?.get("ok") as? Json.Value.BoolValue)?.value
        val shape = when {
            root == null -> "非 JSON 响应"
            ok == true -> "value=" + shapeOf(root.members["value"])
            else -> {
                val err = root.members["error"] as? Json.Value.Object
                "error.code=${(err?.members?.get("code") as? Json.Value.StringValue)?.value}"
            }
        }
        info("probe-$label", "POST $endpoint → HTTP ${res.status} ok=$ok $shape")
    }

    private fun shapeOf(v: Json.Value?): String = when (v) {
        null -> "缺失"
        is Json.Value.Object -> "{" + v.members.keys.joinToString(",") + "}"
        is Json.Value.Array -> "[${v.items.size}]"
        is Json.Value.StringValue -> "string"
        is Json.Value.NumberValue -> "number"
        is Json.Value.BoolValue -> v.value.toString()
        Json.Value.NullValue -> "null"
    }

    private fun parseQuiet(res: SpikeHttp.Response): Json.Value? =
        ((Json.parse(res.bodyText()) as? Json.Value.Object)?.members?.get("value"))

    private fun raw(v: Json.Value.Object, key: String): String = when (val x = v.members[key]) {
        is Json.Value.NumberValue -> x.raw
        is Json.Value.StringValue -> x.value
        is Json.Value.BoolValue -> x.value.toString()
        Json.Value.NullValue -> "null"
        null -> "缺失"
        else -> shapeOf(x)
    }

    private fun q(s: String): String = work.keepwriting.core.JsonString.of(s)
}
