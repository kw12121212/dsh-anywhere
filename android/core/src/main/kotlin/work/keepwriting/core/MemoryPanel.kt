package work.keepwriting.core

/**
 * 记忆面板（dsbot-memory）：memory-view / memory-history / memory-ask 应答解码、分组与排序、条目长度校验、移动目标
 * 与各端点请求体。形态见 tech/dsbot-api 的 memory-endpoints-0310（view 与 history 按真实应答实测，写端点源码核对）。
 * 记忆正文只用于显示，不进日志。
 */
object MemoryPanel {
    const val ENTRY_MAX_DEFAULT = 300
    const val ASK_MAX = 2000

    /** 应答要等模型写完才返回的端点（上游 memoryAsk 单次模型调用上限 120 秒、失败会重试与换模型）：需长读超时。 */
    val SLOW_ENDPOINTS: Set<String> = setOf("memory-ask")

    /** 自动整理条目的动作（上游 memory-tidy.js 写 merged/moved/retired）。 */
    fun tidyOpLabel(op: String): String = when (op) {
        "merged" -> I18n.tr("合并", "Merged")
        "moved" -> I18n.tr("移动", "Moved")
        "retired" -> I18n.tr("淘汰", "Retired")
        else -> op
    }

    /** 面板对象：Bot（botId）或群（roomId）。 */
    sealed interface Owner {
        val id: String
        data class Bot(override val id: String) : Owner
        data class Room(override val id: String) : Owner
    }

    data class Entry(
        val scope: String,
        val topic: String?,
        val text: String,
        val id: String?,
        val added: String?,
        val by: String?,
        val until: String?,
        val pinned: Boolean,
        val expired: Boolean,
    )

    data class Place(val key: String, val kind: String, val name: String)

    data class Tidy(val scope: String, val batch: String, val at: String?, val merged: Int, val moved: Int, val retired: Int, val items: List<Pair<String, String>>)

    data class View(
        val entries: List<Entry>,
        val entryLimit: Int,
        val summary: String?,
        val summarizing: Boolean,
        val tidy: Tidy?,
        /** 面板自己的记忆键（scope → key，如 own → bot:…、team → team、group → group:…）。 */
        val keys: Map<String, String>,
        val places: List<Place>,
        val error: String?,
    )

    data class Group(val scope: String, val entries: List<Entry>)

    data class HistoryRow(val id: String, val time: String?, val op: String, val scope: String, val text: String, val by: String?, val restored: Boolean)

    data class History(val rows: List<HistoryRow>, val more: String?)

    data class Change(val op: String, val scope: String, val text: String, val before: String?, val ok: Boolean, val error: String?)

    data class AskResult(val reply: String, val changes: List<Change>, val view: View?)

    // ---- 解码 ----

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.bool(k: String) = (members[k] as? Json.Value.BoolValue)?.value == true
    private fun Json.Value.Object.int(k: String) = (members[k] as? Json.Value.NumberValue)?.raw?.toDoubleOrNull()?.toInt()
    private fun Json.Value.Object.arr(k: String) = (members[k] as? Json.Value.Array)?.items.orEmpty()
    private fun Json.Value.Object.obj(k: String) = members[k] as? Json.Value.Object

    fun decodeView(value: Json.Value?): View? {
        val o = value as? Json.Value.Object ?: return null
        if (o.members["entries"] !is Json.Value.Array) return null
        val entries = o.arr("entries").mapNotNull { e ->
            val eo = e as? Json.Value.Object ?: return@mapNotNull null
            val text = eo.str("text") ?: return@mapNotNull null
            Entry(eo.str("scope") ?: "own", eo.str("topic"), text, eo.str("id"), eo.str("added"), eo.str("by"), eo.str("until"), eo.bool("pinned"), eo.bool("expired"))
        }
        val tidy = o.obj("tidy")?.let { t ->
            val batch = t.str("batch") ?: return@let null
            Tidy(
                t.str("scope") ?: "own", batch, t.str("at"), t.int("merged") ?: 0, t.int("moved") ?: 0, t.int("retired") ?: 0,
                t.arr("items").mapNotNull { (it as? Json.Value.Object)?.let { i -> (i.str("op") ?: "") to (i.str("text") ?: "") } },
            )
        }
        val keys = o.obj("keys")?.members.orEmpty().mapNotNull { (k, v) -> (v as? Json.Value.StringValue)?.value?.let { k to it } }.toMap()
        val places = o.arr("places").mapNotNull { p ->
            val po = p as? Json.Value.Object ?: return@mapNotNull null
            val key = po.str("key") ?: return@mapNotNull null
            Place(key, po.str("kind") ?: "bot", po.str("name") ?: key)
        }
        return View(
            entries = entries,
            entryLimit = o.obj("limits")?.int("entry") ?: ENTRY_MAX_DEFAULT,
            summary = o.obj("summary")?.str("text"),
            summarizing = o.bool("summarizing"),
            tidy = tidy,
            keys = keys,
            places = places,
            error = o.str("error"),
        )
    }

    /** 分组：Bot 为 own → team，群为 group；组内置顶在前、其余保持服务端顺序；空组不出现。 */
    fun groups(view: View): List<Group> {
        val order = listOf("own", "team", "group")
        val scopes = (order + view.entries.map { it.scope }.distinct()).distinct()
        return scopes.mapNotNull { s ->
            val list = view.entries.filter { it.scope == s }
            if (list.isEmpty()) null else Group(s, list.filter { it.pinned } + list.filter { !it.pinned })
        }
    }

    /**
     * 列表行的稳定键：上游条目 id 只在同一记忆范围内唯一（无 meta.id 时取正文 sha256 前 4 位，搬移保留原 id），
     * 同一页并列 own 与 team 两组时必须带范围与组内位置，否则 Compose 键重复崩溃。
     */
    fun rowKey(scope: String, index: Int, e: Entry): String = "e:$scope:$index:${e.id ?: e.text.hashCode()}"

    fun scopeLabel(scope: String): String = when (scope) {
        "own" -> I18n.tr("它自己的记忆", "Its own memory")
        "team" -> I18n.tr("团队共享记忆", "Team memory")
        "group" -> I18n.tr("群记忆", "Group memory")
        else -> scope
    }

    /** 新增/编辑条目的本地校验（上游 entryProblem 的长度部分；其余如疑似密钥由服务端判定）。 */
    fun entryProblem(text: String, limit: Int): String? = when {
        text.isBlank() -> I18n.tr("请输入内容", "Enter some text")
        text.trim().length > limit -> I18n.tr("一条记忆最多 $limit 字", "A memory entry holds at most $limit characters")
        else -> null
    }

    /** 条目可移动到的记忆：其他 Bot/群的记忆与团队记忆，排除条目当前所在的那个。 */
    fun moveTargets(view: View, scope: String): List<Place> {
        val here = view.keys[scope]
        val team = Place("team", "team", I18n.tr("团队共享记忆", "Team memory"))
        return (view.places + team).filter { it.key != here }.distinctBy { it.key }
    }

    fun decodeHistory(value: Json.Value?): History {
        val o = value as? Json.Value.Object ?: return History(emptyList(), null)
        val rows = o.arr("rows").mapNotNull { r ->
            val ro = r as? Json.Value.Object ?: return@mapNotNull null
            val id = ro.str("id") ?: return@mapNotNull null
            HistoryRow(id, ro.str("time"), ro.str("op") ?: "", ro.str("scope") ?: "own", ro.str("text") ?: "", ro.str("by"), ro.bool("restored"))
        }
        return History(rows, o.str("more"))
    }

    fun opLabel(op: String): String = when (op) {
        "add" -> I18n.tr("新增", "Added")
        "remove" -> I18n.tr("删除", "Removed")
        "replace", "edit" -> I18n.tr("修改", "Changed")
        else -> op
    }

    fun decodeAsk(value: Json.Value?): AskResult {
        val o = value as? Json.Value.Object ?: return AskResult("", emptyList(), null)
        val changes = o.arr("changes").mapNotNull { c ->
            val co = c as? Json.Value.Object ?: return@mapNotNull null
            Change(co.str("op") ?: "", co.str("scope") ?: "own", co.str("text") ?: "", co.str("before"), co.bool("ok"), co.str("error"))
        }
        return AskResult(o.str("reply") ?: "", changes, decodeView(o.members["view"]))
    }

    // ---- 请求体 ----

    object Requests {
        private fun q(s: String) = JsonString.of(s)
        private fun owner(o: Owner) = when (o) {
            is Owner.Bot -> "\"botId\":" + q(o.id)
            is Owner.Room -> "\"roomId\":" + q(o.id)
        }
        private fun at(e: Entry) = ",\"scope\":" + q(e.scope) + ",\"topic\":" + (e.topic?.let(::q) ?: "null") + ",\"text\":" + q(e.text)

        fun view(o: Owner) = "{" + owner(o) + "}"
        fun ask(o: Owner, text: String) = "{" + owner(o) + ",\"text\":" + q(text.trim().take(ASK_MAX)) + "}"
        fun pin(o: Owner, e: Entry, pin: Boolean) = "{" + owner(o) + at(e) + ",\"pin\":" + pin + "}"
        fun forget(o: Owner, e: Entry) = "{" + owner(o) + at(e) + "}"
        fun edit(o: Owner, e: Entry, next: String) = "{" + owner(o) + at(e) + ",\"next\":" + q(next.trim()) + "}"
        fun add(o: Owner, scope: String, text: String) = "{" + owner(o) + ",\"scope\":" + q(scope) + ",\"text\":" + q(text.trim()) + "}"
        fun move(o: Owner, e: Entry, to: String) = "{" + owner(o) + at(e) + ",\"to\":" + q(to) + "}"
        /** 团队条目：always=true 设为所有 Bot 总是读到，false 放回主题。 */
        fun layer(o: Owner, e: Entry, always: Boolean) =
            "{" + owner(o) + ",\"topic\":" + (e.topic?.let(::q) ?: "null") + ",\"text\":" + q(e.text) + ",\"always\":" + always + "}"
        fun history(o: Owner, before: String?) = "{" + owner(o) + (before?.let { ",\"before\":" + q(it) } ?: "") + "}"
        fun restore(o: Owner, id: String) = "{" + owner(o) + ",\"id\":" + q(id) + "}"
        fun tidyUndo(o: Owner, t: Tidy) = "{" + owner(o) + ",\"scope\":" + q(t.scope) + ",\"batch\":" + q(t.batch) + "}"
    }

    /** 团队是否开启记忆（state.memory 不为 false）。 */
    fun enabled(state: Json.Value.Object?): Boolean = (state?.members?.get("memory") as? Json.Value.BoolValue)?.value != false
}
