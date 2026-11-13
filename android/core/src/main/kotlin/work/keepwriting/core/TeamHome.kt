package work.keepwriting.core

/**
 * 团队页组装器（dsbot-team-home）：state 全量视图到团队页渲染树的纯函数组装。
 * 字段缺失一律降级（未知/不显示），网页端 0.3.2 是对等基准。
 */
object TeamHome {
    data class Entry(
        val id: String,
        val kind: Kind,
        val name: String,
        val color: String?,
        val avatar: String?,
        val role: String?,
        val isMain: Boolean,
        val isPrivate: Boolean,
        val working: Boolean,
        val lastSummary: String?,
        val unread: Int?,
        val pinned: Boolean,
        val sessionId: String?,
        /** 最近活动时间（state 的 lastAt，epoch 毫秒；0.3.6 起提供，缺失为 null）。 */
        val lastAt: Long? = null,
        /** Bot 的分段 id 列表（旧分段切换入口用）；房间为空。 */
        val parts: List<String> = emptyList(),
        /** 房间成员 Bot id 列表（群聊提及/停止成员名册）；Bot 条目为空。 */
        val members: List<String> = emptyList(),
    ) {
        enum class Kind { BOT, ROOM }
    }

    data class Section(val id: String?, val name: String, val entries: List<Entry>)

    data class PendingItem(
        val kind: Kind,
        val sessionId: String?,
        val botId: String?,
        val summary: String?,
    ) {
        enum class Kind { APPROVAL, QUESTION, VIEW }
    }

    data class Team(
        val sections: List<Section>,
        val pending: List<PendingItem>,
        val isEmpty: Boolean,
    )

    fun assemble(state: DsbotModels.State): Team {
        val workingBots = workingBotIds(state)
        val botEntries = state.bots.map { bot -> botEntry(bot, state, workingBots) }
        val roomEntries = state.rooms.map { room -> roomEntry(room, workingBots) }
        val allEntries = botEntries + roomEntries
        val grouped = LinkedHashMap<String?, MutableList<Entry>>()
        val sectionsRaw = state.raw.members["sections"] as? Json.Value.Array
        val sectionOf = entrySectionMap(sectionsRaw, allEntries)
        // 置顶优先 → 最近活动（lastAt）降序（最近有对话的排前面，缺失 lastAt 的排最后）
        // → 名称稳定兜底。
        val sorted = allEntries.sortedWith(
            compareBy<Entry> { !it.pinned }
                .thenByDescending { it.lastAt ?: Long.MIN_VALUE }
                .thenBy { it.name },
        )
        for (e in sorted) {
            grouped.getOrPut(sectionOf[e.id]) { mutableListOf() }.add(e)
        }
        val named = mutableListOf<Section>()
        if (sectionsRaw != null) {
            for (sec in sectionsRaw.items) {
                val obj = sec as? Json.Value.Object ?: continue
                val id = str(obj, "id") ?: continue
                val name = str(obj, "name") ?: "未命名分组"
                named.add(Section(id, name, grouped.remove(id) ?: emptyList()))
            }
        }
        val rest = grouped.entries.map { (id, list) -> Section(id, defaultSectionName(id), list) }
        return Team(named + rest, pendingOf(state), allEntries.isEmpty())
    }

    fun search(team: Team, query: String): Team {
        val q = query.trim()
        if (q.isEmpty()) return team
        val sections = team.sections.mapNotNull { sec ->
            val kept = sec.entries.filter { e ->
                e.name.contains(q, true) ||
                    (e.lastSummary?.contains(q, true) == true) ||
                    (e.role?.contains(q, true) == true)
            }
            if (kept.isEmpty()) null else sec.copy(entries = kept)
        }
        return team.copy(sections = sections)
    }

    private fun botEntry(bot: DsbotModels.Bot, state: DsbotModels.State, working: Set<String>): Entry {
        val private = (bot.raw.members["private"] as? Json.Value.BoolValue)?.value ?: false
        return Entry(
            id = bot.id,
            kind = Entry.Kind.BOT,
            name = str(bot.raw, "name") ?: bot.id,
            color = str(bot.raw, "color"),
            avatar = avatarOf(bot.raw),
            role = str(bot.raw, "role"),
            isMain = state.mainBotIds.contains(bot.id),
            isPrivate = private,
            working = working.contains(bot.id),
            lastSummary = str(bot.raw, "preview"),
            unread = int(bot.raw, "unread"),
            pinned = bool(bot.raw, "pinned"),
            sessionId = bot.sessionId,
            lastAt = long(bot.raw, "lastAt"),
            parts = bot.parts,
        )
    }

    private fun roomEntry(room: DsbotModels.Room, working: Set<String>): Entry = Entry(
        id = room.id,
        kind = Entry.Kind.ROOM,
        name = str(room.raw, "name") ?: room.id,
        color = str(room.raw, "color"),
        avatar = avatarOf(room.raw),
        role = null,
        isMain = false,
        isPrivate = false,
        working = room.members.any { working.contains(it) },
        members = room.members,
        lastSummary = str(room.raw, "preview"),
        unread = int(room.raw, "unread"),
        pinned = bool(room.raw, "pinned"),
        sessionId = room.sessionId,
        lastAt = long(room.raw, "lastAt"),
    )

    private fun entrySectionMap(sections: Json.Value.Array?, entries: List<Entry>): Map<String, String?> {
        val map = LinkedHashMap<String, String?>()
        for (e in entries) map[e.id] = null
        sections?.items?.forEach { sec ->
            val obj = sec as? Json.Value.Object ?: return@forEach
            val id = str(obj, "id") ?: return@forEach
            val items = obj.members["itemIds"] as? Json.Value.Array ?: return@forEach
            items.items.forEach { item ->
                val itemId = (item as? Json.Value.StringValue)?.value ?: return@forEach
                map[itemId] = id
            }
        }
        return map
    }

    private fun defaultSectionName(id: String?): String = if (id == null) "未分组" else "未命名分组"

    private fun workingBotIds(state: DsbotModels.State): Set<String> {
        val work = state.raw.members["work"] as? Json.Value.Object ?: return emptySet()
        val bots = work.members["bots"] as? Json.Value.Object ?: return emptySet()
        return bots.members.keys.toSet()
    }

    private fun avatarOf(raw: Json.Value.Object): String? {
        val look = raw.members["look"] as? Json.Value.Object ?: return null
        return str(look, "image")
    }

    private fun pendingOf(state: DsbotModels.State): List<PendingItem> {
        val out = mutableListOf<PendingItem>()
        val work = state.raw.members["work"] as? Json.Value.Object
        val approvals = work?.members?.get("approvals") as? Json.Value.Array
        approvals?.items?.forEach { a ->
            val obj = a as? Json.Value.Object ?: return@forEach
            out.add(
                PendingItem(
                    kind = PendingItem.Kind.APPROVAL,
                    sessionId = str(obj, "sessionId"),
                    botId = str(obj, "botId"),
                    summary = str(obj, "title") ?: str(obj, "text") ?: "待审批",
                ),
            )
        }
        val questions = state.raw.members["questions"] as? Json.Value.Object
        questions?.members?.forEach { (sessionId, q) ->
            val obj = q as? Json.Value.Object ?: return@forEach
            out.add(
                PendingItem(
                    kind = PendingItem.Kind.QUESTION,
                    sessionId = sessionId,
                    botId = str(obj, "botId"),
                    summary = str(obj, "question") ?: "待回答",
                ),
            )
        }
        val views = state.raw.members["views"] as? Json.Value.Object
        views?.members?.forEach { (sessionId, v) ->
            val obj = v as? Json.Value.Object ?: return@forEach
            out.add(
                PendingItem(
                    kind = PendingItem.Kind.VIEW,
                    sessionId = sessionId,
                    botId = str(obj, "botId"),
                    summary = str(obj, "title") ?: "待处理卡片",
                ),
            )
        }
        return out
    }

    private fun str(obj: Json.Value.Object, key: String): String? =
        (obj.members[key] as? Json.Value.StringValue)?.value?.takeIf { it.isNotEmpty() }

    private fun int(obj: Json.Value.Object, key: String): Int? =
        (obj.members[key] as? Json.Value.NumberValue)?.raw?.toIntOrNull()

    private fun long(obj: Json.Value.Object, key: String): Long? =
        (obj.members[key] as? Json.Value.NumberValue)?.raw?.toLongOrNull()

    private fun bool(obj: Json.Value.Object, key: String): Boolean =
        (obj.members[key] as? Json.Value.BoolValue)?.value ?: false
}
