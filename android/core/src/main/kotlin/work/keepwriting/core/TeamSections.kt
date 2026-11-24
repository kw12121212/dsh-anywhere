package work.keepwriting.core

/**
 * 团队侧栏分组（dsbot-sidebar-sections，DS-Bot 0.3.10 roster.js）：state.sections 为 [{id, name, collapsed?, color?}]，
 * 顺序即侧栏顺序；条目归属记在 bots/rooms 各自的 sectionId。本对象负责分组解码、名称本地校验（与服务端
 * cleanSectionName 同规则）、拖动后的新顺序计算与校验，以及 section-save/section-order/section-delete/set-flags 的请求体。
 */
object TeamSections {
    data class Section(val id: String, val name: String, val collapsed: Boolean, val color: String?)

    /** 服务端 SECTION_NAME_MAX。 */
    const val NAME_MAX = 32

    fun decode(state: Json.Value.Object?): List<Section> {
        val arr = state?.members?.get("sections") as? Json.Value.Array ?: return emptyList()
        val seen = HashSet<String>()
        return arr.items.mapNotNull { v ->
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            val id = (o.members["id"] as? Json.Value.StringValue)?.value?.takeIf { it.isNotEmpty() && seen.add(it) } ?: return@mapNotNull null
            Section(
                id = id,
                name = (o.members["name"] as? Json.Value.StringValue)?.value ?: I18n.tr("未命名分组", "Untitled section"),
                collapsed = (o.members["collapsed"] as? Json.Value.BoolValue)?.value == true,
                color = (o.members["color"] as? Json.Value.StringValue)?.value,
            )
        }
    }

    /** 服务端存的名称形态：去首尾空白与引号、合并空白、截断到 32 字（unquote + cleanSectionName）。 */
    fun clean(name: String): String {
        val unquoted = name.trim().replace(Regex("^[\"'“”「『《]+|[\"'“”」』》]+$"), "").trim()
        val collapsed = unquoted.replace(Regex("\\s+"), " ")
        return if (collapsed.length > NAME_MAX) collapsed.substring(0, NAME_MAX) else collapsed
    }

    /** 名称的本地问题（与服务端拒绝条件一致）；无问题为 null。selfId 为正在改名的分组。 */
    fun nameProblem(sections: List<Section>, name: String, selfId: String? = null): String? {
        val next = clean(name)
        if (next.isEmpty()) return I18n.tr("请输入分组名称", "Enter a section name")
        val key = next.trim().lowercase()
        if (sections.any { it.id != selfId && it.name.trim().lowercase() == key }) {
            return I18n.tr("已有同名分组「$next」", "Another section is already called \"$next\"")
        }
        return null
    }

    /** 把 from 位置的分组移到 to 位置后的 id 列表（越界时原样返回）。 */
    fun move(ids: List<String>, from: Int, to: Int): List<String> {
        if (from !in ids.indices || to !in ids.indices || from == to) return ids
        val next = ids.toMutableList()
        val moved = next.removeAt(from)
        next.add(to, moved)
        return next
    }

    /** 新顺序是否只是原分组的重排（无重复、无遗漏、无多余）。 */
    fun isPermutation(current: List<String>, next: List<String>): Boolean =
        next.size == current.size && next.toSet().size == next.size && next.toSet() == current.toSet()

    /** 按拖动位移计算目标位置：rowHeight 为实测行高（像素），offset 为相对起点的纵向位移。 */
    fun targetIndex(from: Int, offset: Float, rowHeight: Float, count: Int): Int {
        if (count <= 0 || rowHeight <= 0f) return from
        val shift = Math.round(offset / rowHeight)
        return (from + shift).coerceIn(0, count - 1)
    }

    object Requests {
        private fun q(s: String) = JsonString.of(s)

        /** 新建：名称清洗后为空时不带 name（服务端取「New section」并加序号）。 */
        fun create(name: String): String = clean(name).let { if (it.isEmpty()) "{}" else """{"name":${q(it)}}""" }

        fun rename(id: String, name: String): String = """{"id":${q(id)},"name":${q(clean(name))}}"""

        fun collapse(id: String, collapsed: Boolean): String = """{"id":${q(id)},"collapsed":$collapsed}"""

        fun order(ids: List<String>): String = ids.joinToString(",", prefix = """{"ids":[""", postfix = "]}") { q(it) }

        fun delete(id: String): String = """{"id":${q(id)}}"""

        /** 把条目移入分组（sectionId 为 null 时移出）：set-flags 只改这一个条目。 */
        fun moveEntry(entryId: String, sectionId: String?): String =
            """{"id":${q(entryId)},"sectionId":${sectionId?.let { q(it) } ?: "null"}}"""
    }
}
