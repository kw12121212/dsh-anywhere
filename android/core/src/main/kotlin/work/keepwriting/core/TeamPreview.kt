package work.keepwriting.core

/**
 * 团队页条目预览（dsbot-read-state）：按上游手机端的优先级挑一行显示——
 * Bot：待回答的提问或卡片 → 待审批（工具审批、密钥/连接器请求、学习提案）→ 正在做的工作 →
 * 在群里工作 → 清洗后的末条摘要 → 角色；群：待回答 → 待审批 → 成员工作 → 摘要。
 * 字段按 DS-Bot 0.3.10 state 形态读取，缺失一律降级到下一档。
 */
object TeamPreview {
    enum class Kind { ANSWER, APPROVAL, WORK, GROUP_WORK, SUMMARY, ROLE }

    data class Preview(val kind: Kind, val text: String) {
        /** 等你处理的两档以强调色显示。 */
        val waiting: Boolean get() = kind == Kind.ANSWER || kind == Kind.APPROVAL
    }

    fun waitingAnswer(): String = I18n.tr("等你回答", "Waiting for your answer")

    fun waitingApproval(): String = I18n.tr("等你审批", "Waiting for your approval")

    fun ofBot(bot: DsbotModels.Bot, state: DsbotModels.State): Preview? {
        val raw = state.raw
        val part = bot.sessionId ?: bot.id
        if (has(raw, "questions", part) || has(raw, "views", part)) return Preview(Kind.ANSWER, waitingAnswer())
        val approval = approvals(raw).any { str(it, "botId") == bot.id && str(it, "roomId") == null } ||
            pendingRequest(raw, "secretRequests", bot.id) ||
            pendingRequest(raw, "connectorRequests", bot.id) ||
            ((obj(raw, "learning")?.members?.get(bot.id) as? Json.Value.Array)?.items?.isNotEmpty() == true)
        if (approval) return Preview(Kind.APPROVAL, waitingApproval())
        val work = obj(raw, "work")
        (obj(work, "bots")?.members?.get(bot.id) as? Json.Value.Object)?.let {
            return Preview(Kind.WORK, WorkPhrase.phrase(it))
        }
        val groups = obj(work, "rooms")?.members.orEmpty().flatMap { (roomId, list) ->
            (list as? Json.Value.Array)?.items.orEmpty()
                .mapNotNull { it as? Json.Value.Object }
                .filter { str(it, "botId") == bot.id }
                .map { roomId to it }
        }
        if (groups.isNotEmpty()) {
            val (roomId, doing) = groups.first()
            val name = state.rooms.firstOrNull { it.id == roomId }?.let { str(it.raw, "name") }
                ?: I18n.tr("群聊", "a group chat")
            return Preview(Kind.GROUP_WORK, more("$name · ${WorkPhrase.phrase(doing)}", groups.size - 1))
        }
        clean(str(bot.raw, "preview"))?.let { return Preview(Kind.SUMMARY, it) }
        return str(bot.raw, "role")?.let { Preview(Kind.ROLE, it) }
    }

    fun ofRoom(room: DsbotModels.Room, state: DsbotModels.State): Preview? {
        val raw = state.raw
        val sessions = buildSet {
            add(room.sessionId ?: room.id)
            (room.raw.members["parts"] as? Json.Value.Array)?.items?.forEach { (it as? Json.Value.StringValue)?.value?.let(::add) }
        }
        if (sessions.any { has(raw, "questions", it) || has(raw, "views", it) }) return Preview(Kind.ANSWER, waitingAnswer())
        if (approvals(raw).any { str(it, "roomId") == room.id }) return Preview(Kind.APPROVAL, waitingApproval())
        val entries = (obj(obj(raw, "work"), "rooms")?.members?.get(room.id) as? Json.Value.Array)?.items.orEmpty()
            .mapNotNull { it as? Json.Value.Object }
        if (entries.isNotEmpty()) {
            val first = entries.first()
            val who = str(first, "botId")?.let { id -> state.bots.firstOrNull { it.id == id }?.let { str(it.raw, "name") } ?: id }
            val phrase = WorkPhrase.phrase(first)
            return Preview(Kind.GROUP_WORK, more(if (who != null) "$who · $phrase" else phrase, entries.size - 1))
        }
        return clean(str(room.raw, "preview"))?.let { Preview(Kind.SUMMARY, it) }
    }

    /** 与网页端 stripHeader 同一组来源头；服务端预览已折叠空白、没有「首行」，故头只到第一个 ']'。 */
    private val HEADER = Regex(
        "^\\[(Message from|Reply from|Group chat|Group post from|Bot team setup|Handoff · part|Secret card)[^\\]]*\\]\\s*",
    )
    private val HANDOFF = Regex("^\\[Browser handoff\\]\\s*(.*?)\\s*Answer:\\s*(\\S+)\\s*$")
    private val QUESTION = Regex("^\\[Answer to question card\\]\\s*(.*?)\\s*Answer:\\s*(.*)$")
    private val VIEW = Regex("^\\[Answer to view card\\]\\s*(.*?)\\s*Answer:\\s*(.*)$")
    private val GROUP_LINE = Regex("^([^：\\[\\]]{1,40})：(.*)$")

    /** 多条回复记录（服务端以空行连接、预览里已折叠成空格）在每条包装开头处切开，逐条清洗。 */
    private val RECORD_START = Regex("(?=\\[(?:Browser handoff|Answer to question card|Answer to view card)\\])")

    /**
     * 末条摘要清洗：折叠空白；去来源包装头（[Message from …] 等）；把服务端回复记录的英文包装
     * （0.3.10 parts.js replyText 与 deep-view viewReplyText）转为可读文本；群预览「名字：正文」只清洗正文。
     * 空结果为 null。
     */
    fun clean(text: String?): String? {
        val flat = text?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        GROUP_LINE.matchEntire(flat)?.let { m ->
            // 「名字：正文」只清洗正文；正文清洗为空（如以全角冒号结尾的普通文本）时保留原文
            val body = cleanRecords(m.groupValues[2]) ?: return flat
            return "${m.groupValues[1]}：$body"
        }
        return cleanRecords(flat) ?: flat
    }

    private fun cleanRecords(flat: String): String? {
        val text = flat.replaceFirst(HEADER, "").trim()
        val records = text.split(RECORD_START).map { it.trim() }.filter { it.isNotEmpty() }
        if (records.size <= 1) return cleanBody(text)
        return records.mapNotNull(::cleanBody).joinToString(I18n.tr("；", "; ")).takeIf { it.isNotEmpty() }
    }

    private fun cleanBody(text: String): String? {
        HANDOFF.matchEntire(text)?.let { m ->
            val outcome = when (m.groupValues[2].lowercase()) {
                "done" -> I18n.tr("（已完成）", " (done)")
                "skip", "skipped" -> I18n.tr("（已跳过）", " (skipped)")
                else -> ""
            }
            return I18n.tr("浏览器交接：", "Browser handoff: ") + m.groupValues[1] + outcome
        }
        QUESTION.matchEntire(text)?.let { m ->
            val answer = m.groupValues[2].let { if (it == "(skipped)") I18n.tr("已跳过", "skipped") else it }
            return I18n.tr("已回答「${m.groupValues[1]}」：$answer", "Answered \"${m.groupValues[1]}\": $answer")
        }
        VIEW.matchEntire(text)?.let { m ->
            val title = m.groupValues[1]
            return if (title.isEmpty()) I18n.tr("已回复卡片", "Replied to a card")
            else I18n.tr("已回复卡片「$title」", "Replied to card \"$title\"")
        }
        return text.takeIf { it.isNotEmpty() }
    }

    private fun more(text: String, extra: Int): String = if (extra > 0) "$text +$extra" else text

    private fun has(raw: Json.Value.Object, key: String, sessionId: String): Boolean =
        obj(raw, key)?.members?.get(sessionId) is Json.Value.Object

    private fun approvals(raw: Json.Value.Object): List<Json.Value.Object> =
        (obj(raw, "work")?.members?.get("approvals") as? Json.Value.Array)?.items.orEmpty()
            .mapNotNull { it as? Json.Value.Object }
            .filter { (it.members["answerable"] as? Json.Value.BoolValue)?.value != false }

    private fun pendingRequest(raw: Json.Value.Object, key: String, botId: String): Boolean =
        (obj(raw, key)?.members?.get(botId) as? Json.Value.Array)?.items.orEmpty()
            .any { (it as? Json.Value.Object)?.let { o -> (str(o, "status") ?: "pending") == "pending" } == true }

    private fun obj(o: Json.Value.Object?, key: String): Json.Value.Object? = o?.members?.get(key) as? Json.Value.Object

    private fun str(o: Json.Value.Object, key: String): String? =
        (o.members[key] as? Json.Value.StringValue)?.value?.takeIf { it.isNotBlank() }
}
