package work.keepwriting.core

/**
 * 群聊设置（dsbot-group-manage）：群资料解码、可加入成员的候选与搜索、与服务端一致的本地校验、各写端点请求体。
 * 端点形态见 tech/dsbot-api 的 room-manage-endpoints-0310（群字段实测；写端点按 ds-bot 0.3.10 roster.js 源码核对）。
 * 角色权限（用户、Main Bot、群管理员）由服务端执行，这里的校验只用于提前提示。
 */
object GroupManage {
    const val MIN_MEMBERS = 2
    const val MAX_MEMBERS = 12
    const val NAME_MAX = 60
    const val NOTICE_MAX = 2000

    /** 回复模式：管理员优先（默认）/ 全员轮流。 */
    val MODES = listOf("admin", "everyone")

    data class Room(
        val id: String,
        val name: String,
        /** 用户起过名（false 时群名按成员自动生成）。 */
        val named: Boolean,
        val members: List<String>,
        val admin: String?,
        val mode: String,
        val notice: String,
        val permission: String?,
        val workDir: String?,
        val pinned: Boolean,
        val hidden: Boolean,
    )

    data class Candidate(val id: String, val name: String, val isMain: Boolean, val isPrivate: Boolean)

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.bool(k: String) = (members[k] as? Json.Value.BoolValue)?.value == true
    private fun Json.Value.Object.arr(k: String) = (members[k] as? Json.Value.Array)?.items.orEmpty()

    fun roomOf(state: Json.Value.Object?, id: String): Room? {
        val o = state?.arr("rooms")?.mapNotNull { it as? Json.Value.Object }?.firstOrNull { it.str("id") == id } ?: return null
        return Room(
            id = id,
            name = o.str("name") ?: id,
            named = o.bool("named"),
            members = o.arr("members").mapNotNull { (it as? Json.Value.StringValue)?.value },
            admin = o.str("admin"),
            // 原样保留；显示与选择一律经 shownMode（上游只在 mode=="admin" 且管理员在团队里时才是管理员优先）
            mode = o.str("mode") ?: "",
            notice = o.str("notice") ?: "",
            permission = o.str("permission"),
            workDir = o.str("workDir"),
            pinned = o.bool("pinned"),
            hidden = o.bool("hidden"),
        )
    }

    /** 团队里的全部 Bot（成员名单与选择用），保持服务端顺序。 */
    fun bots(state: Json.Value.Object?): List<Candidate> {
        val mains = state?.arr("mainBotIds")?.mapNotNull { (it as? Json.Value.StringValue)?.value }.orEmpty()
        return state?.arr("bots").orEmpty().mapNotNull { b ->
            val o = b as? Json.Value.Object ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            Candidate(id, o.str("name") ?: id, id in mains, o.bool("private"))
        }
    }

    /** 可选成员：私有 Bot 不能加入群；按名称搜索（不区分大小写，空查询返回全部）。 */
    fun candidates(state: Json.Value.Object?, query: String): List<Candidate> {
        val q = query.trim().lowercase()
        return bots(state).filter { !it.isPrivate && (q.isEmpty() || it.name.lowercase().contains(q)) }
    }

    enum class Problem { TOO_FEW, TOO_MANY, ADMIN_NOT_MEMBER, NOTICE_TOO_LONG }

    /** 成员与管理员的本地校验（与上游 checkMembers / planRoomChange 一致）。 */
    fun memberProblem(members: List<String>, admin: String?): Problem? = when {
        members.distinct().size < MIN_MEMBERS -> Problem.TOO_FEW
        members.distinct().size > MAX_MEMBERS -> Problem.TOO_MANY
        admin != null && admin !in members -> Problem.ADMIN_NOT_MEMBER
        else -> null
    }

    fun noticeProblem(notice: String): Problem? = if (notice.trim().length > NOTICE_MAX) Problem.NOTICE_TOO_LONG else null

    fun problemText(p: Problem): String = when (p) {
        Problem.TOO_FEW -> I18n.tr("群聊至少需要 2 个 Bot", "A group chat needs at least two Bots")
        Problem.TOO_MANY -> I18n.tr("群聊最多 12 个 Bot", "A group chat holds at most 12 Bots")
        Problem.ADMIN_NOT_MEMBER -> I18n.tr("管理员必须是群成员", "The admin must be a member of the group")
        Problem.NOTICE_TOO_LONG -> I18n.tr("公告最多 2000 字", "A notice holds at most 2000 characters")
    }

    /**
     * 实际生效、网页端显示的回复模式：只有 mode 为 admin 且管理员仍是团队里的 Bot 时才是管理员优先，否则全员轮流
     * （网页端 shownMode、服务端 groups.js/tools.js 同此判定；把管理员设为无或移出群时上游不改 mode）。
     */
    fun shownMode(room: Room, bots: List<Candidate>): String =
        if (room.mode == "admin" && room.admin != null && bots.any { it.id == room.admin }) "admin" else "everyone"

    fun modeLabel(mode: String): String = when (mode) {
        "everyone" -> I18n.tr("全员轮流", "Everyone in turn")
        else -> I18n.tr("管理员优先", "Admin first")
    }

    fun modeHint(mode: String): String = when (mode) {
        "everyone" -> I18n.tr("每条普通消息由每位成员依次回复。", "Every member replies in turn to each plain message.")
        else -> I18n.tr("普通消息先交给管理员处理。", "Plain messages go to the admin first.")
    }

    object Requests {
        private fun q(s: String) = JsonString.of(s)
        private fun ids(list: List<String>) = list.distinct().joinToString(",", "[", "]") { q(it) }

        /** 新建群：与网页端一致，成员为 [管理员, ...其他]、admin=管理员、greet:true（管理员打招呼）。 */
        fun createRoom(admin: String, others: List<String>, name: String): String {
            val members = listOf(admin) + others.filter { it != admin }
            return "{\"members\":" + ids(members) + ",\"admin\":" + q(admin) + ",\"greet\":true" +
                (if (name.isNotBlank()) ",\"name\":" + q(name.trim()) else "") + "}"
        }

        fun name(id: String, name: String) = "{\"id\":" + q(id) + ",\"name\":" + q(name.trim()) + "}"

        fun notice(id: String, notice: String) = "{\"id\":" + q(id) + ",\"notice\":" + q(notice.trim()) + "}"

        /** 成员全量替换（一次提交，只做一次人数校验）。 */
        fun members(id: String, members: List<String>) = "{\"id\":" + q(id) + ",\"members\":" + ids(members) + "}"

        fun admin(id: String, admin: String?) = "{\"id\":" + q(id) + ",\"admin\":" + (admin?.let(::q) ?: "null") + "}"

        fun mode(id: String, mode: String) = "{\"id\":" + q(id) + ",\"mode\":" + q(mode) + "}"

        /** 统一权限预设；null = 各 Bot 用自己的权限。 */
        fun permission(id: String, preset: String?) = "{\"id\":" + q(id) + ",\"permission\":" + (preset?.let(::q) ?: "null") + "}"

        fun delete(id: String) = "{\"id\":" + q(id) + "}"
    }

    /** create-room 应答里的新群 id。 */
    fun createdId(value: Json.Value?): String? = ((value as? Json.Value.Object)?.members?.get("id") as? Json.Value.StringValue)?.value
}
