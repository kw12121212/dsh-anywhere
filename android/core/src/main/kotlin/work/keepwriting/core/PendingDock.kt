package work.keepwriting.core

/**
 * 待处理停靠区（dsbot-pending-dock）：把当前聊天等待用户处理的事项汇集成一个有序列表，供输入框上方的
 * 手风琴使用。DS-Bot 0.3.10 的待处理项都在 state 里（源码核对，tech/dsbot-api 的 dock-fields-0310）：
 * questions/views 按会话 id、live work.approvals、secretRequests/connectorRequests/mailRequests 按 botId、live learning、
 * live browserHolds。聊天条目里的 kind=answer 是用户已经给出的回复记录，不是待处理项。
 */
object PendingDock {
    /** 优先级：学习/审批 → 提问 → 密钥/连接器/邮件 → 交接 → 卡片。 */
    enum class Kind(val rank: Int, private val zh: String, private val en: String) {
        LEARNING(0, "学习审批", "Learning"),
        APPROVAL(0, "审批", "Approval"),
        QUESTION(1, "提问", "Question"),
        SECRET(2, "密钥请求", "Key request"),
        CONNECTOR(2, "连接器请求", "Connector"),
        MAIL(2, "邮件发送", "Mail"),
        HANDOFF(3, "浏览器交接", "Browser handoff"),
        VIEW(4, "卡片", "Card"),
        ;

        /** 类型标签（按当前界面语言）。 */
        val label: String get() = I18n.tr(zh, en)
    }

    /** 停靠区一项的数据（按类型）。 */
    sealed interface Payload {
        data class Question(
            val sessionId: String,
            val id: String,
            val question: String,
            val detail: String?,
            val options: List<Option>,
            val multiSelect: Boolean,
            val allowCustom: Boolean,
        ) : Payload {
            data class Option(val label: String, val description: String?)
        }
        data class Approval(val id: String, val tool: String, val reason: String?, val taskTitle: String?) : Payload
        data class Secret(val id: String, val name: String, val purpose: String?, val kind: String?) : Payload
        /** show_view 卡片；[raw] 是 state.views 的原始项（dsbot-rich-render 原生绘制用 ViewCard.decode）。 */
        data class View(val sessionId: String, val id: String, val title: String?, val raw: Json.Value.Object? = null) : Payload
        data class Handoff(val botId: String, val callId: String, val note: String?) : Payload
        data class Learning(val proposal: LearningProposal) : Payload
        data class Connector(val request: ConnectorRequest) : Payload
        /** 邮件发送确认（state.mailRequests，dsbot-connectors-mail）。 */
        data class Mail(val request: MailSettings.SendRequest) : Payload
    }

    data class Item(val id: String, val kind: Kind, val title: String, val payload: Payload)

    /**
     * 从 state 汇集。[sessionIds]：当前聊天的会话 id（私聊为 Bot 当前分段；群聊为群会话与成员的群内会话，
     * 即 rooms[].sessionId 与 parts）；[botId] 非空时为私聊（另汇集该 Bot 的密钥/连接器请求、邮件发送卡、学习审批与浏览器交接），
     * [roomId] 非空时为群聊（审批按 roomId 归属）。[answered] 中的 id 一律排除（作答成功、等待 state 刷新的过渡期）。
     */
    fun collect(
        state: Json.Value.Object?,
        sessionIds: Set<String>,
        botId: String?,
        roomId: String?,
        answered: Set<String>,
    ): List<Item> {
        if (state == null) return emptyList()
        val out = mutableListOf<Item>()
        if (botId != null) {
            for (p in learningOf(state, botId)) out.add(Item(p.id, Kind.LEARNING, p.name ?: p.path ?: I18n.tr("学习审批", "Learning approval"), Payload.Learning(p)))
        }
        for (a in approvalsOf(state, botId, roomId)) out.add(Item(a.id, Kind.APPROVAL, a.taskTitle?.let { "$it · ${a.tool}" } ?: a.tool.ifEmpty { I18n.tr("工具审批", "Tool approval") }, a))
        for (q in questionsOf(state, sessionIds)) out.add(Item(q.id, Kind.QUESTION, q.question.ifEmpty { I18n.tr("提问", "Question") }, q))
        if (botId != null) {
            for (r in secretsOf(state, botId, sessionIds)) out.add(Item(r.id, Kind.SECRET, r.name, r))
            for (c in connectorsOf(state, botId, sessionIds)) out.add(Item(c.id, Kind.CONNECTOR, c.name ?: c.connectorId ?: I18n.tr("连接器", "Connector"), Payload.Connector(c)))
            for (m in MailSettings.pendingSends(state, botId, sessionIds)) {
                out.add(Item(m.id, Kind.MAIL, m.subject.takeIf { it.isNotBlank() }?.let { ChatBars.clip(it, ChatBars.TITLE_MAX) } ?: I18n.tr("邮件", "Mail"), Payload.Mail(m)))
            }
            // 同一聊天可先后发起多次交接：id 含 callId，作答过的那次不会挡住下一次
            handoffOf(state, botId)?.let { out.add(Item("handoff:$botId:${it.callId}", Kind.HANDOFF, it.note ?: I18n.tr("浏览器交接", "Browser handoff"), it)) }
        }
        for (v in viewsOf(state, sessionIds)) out.add(Item(v.id, Kind.VIEW, v.title ?: I18n.tr("卡片", "Card"), v))
        return out.filter { it.id !in answered }.sortedBy { it.kind.rank } // 稳定排序：同档保持上面的来源顺序
    }

    /** 默认展开项：第一项；空列表为 -1。 */
    fun expandedIndex(items: List<Item>): Int = if (items.isEmpty()) -1 else 0

    /** live learning[botId] 的待审提案。 */
    fun learningOf(state: Json.Value.Object?, botId: String): List<LearningProposal> =
        state.obj("learning").arr(botId).mapNotNull(LearningProposal::decode).take(ChatBars.LIST_MAX)

    /** state connectorRequests[botId] 中待处理、且属于当前会话（有 sessionId 时）的请求。 */
    fun connectorsOf(state: Json.Value.Object?, botId: String, sessionIds: Set<String>): List<ConnectorRequest> =
        state.obj("connectorRequests").arr(botId).mapNotNull(ConnectorRequest::decode)
            .filter { it.status == "pending" && (it.sessionId == null || it.sessionId in sessionIds) }
            .take(ChatBars.LIST_MAX)

    /** live work.approvals：私聊取该 Bot 不在群里的、群聊取 roomId 匹配的；answerable=false 的不计（无法在此作答）。 */
    fun approvalsOf(state: Json.Value.Object?, botId: String?, roomId: String?): List<Payload.Approval> =
        state.obj("work").arr("approvals").mapNotNull { v ->
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            val id = o.s("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if ((o.members["answerable"] as? Json.Value.BoolValue)?.value == false) return@mapNotNull null
            val belongs = if (roomId != null) o.s("roomId") == roomId else (botId != null && o.s("botId") == botId && o.s("roomId") == null)
            if (!belongs) return@mapNotNull null
            Payload.Approval(id, o.s("tool").orEmpty(), o.s("reason")?.takeIf { it.isNotBlank() }, o.s("taskTitle"))
        }.take(ChatBars.LIST_MAX)

    /** state questions{sessionId → 提问}：只取当前聊天会话的。 */
    fun questionsOf(state: Json.Value.Object?, sessionIds: Set<String>): List<Payload.Question> =
        state.obj("questions")?.members.orEmpty().mapNotNull { (sid, v) ->
            if (sid !in sessionIds) return@mapNotNull null
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            val id = o.s("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val options = o.arr("options").mapNotNull { opt ->
                val oo = opt as? Json.Value.Object ?: return@mapNotNull null
                val label = oo.s("label")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Payload.Question.Option(label, oo.s("description"))
            }.take(ChatBars.LIST_MAX)
            Payload.Question(
                sessionId = sid, id = id, question = ChatBars.clip(o.s("question").orEmpty(), 300), detail = o.s("detail"),
                options = options, multiSelect = o.bool("multiSelect"), allowCustom = o.bool("allowCustom"),
            )
        }

    /** state views{sessionId → 卡片}：只取当前聊天会话的。 */
    fun viewsOf(state: Json.Value.Object?, sessionIds: Set<String>): List<Payload.View> =
        state.obj("views")?.members.orEmpty().mapNotNull { (sid, v) ->
            if (sid !in sessionIds) return@mapNotNull null
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            val id = o.s("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Payload.View(sid, id, o.s("title")?.takeIf { it.isNotBlank() }?.let { ChatBars.clip(it, ChatBars.TITLE_MAX) }, o)
        }

    /** state secretRequests[botId] 中待处理、且属于当前会话（有 sessionId 时）的请求。 */
    fun secretsOf(state: Json.Value.Object?, botId: String, sessionIds: Set<String>): List<Payload.Secret> =
        state.obj("secretRequests").arr(botId).mapNotNull { v ->
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            val id = o.s("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (o.s("status") != "pending") return@mapNotNull null
            val sid = o.s("sessionId")
            if (sid != null && sid !in sessionIds) return@mapNotNull null
            Payload.Secret(id, o.s("name") ?: I18n.tr("密钥", "Key"), o.s("purpose")?.takeIf { it.isNotBlank() }, o.s("kind"))
        }.take(ChatBars.LIST_MAX)

    /** live browserHolds[botId]：Bot 发起的交接（带 callId）才需要用户处理。 */
    fun handoffOf(state: Json.Value.Object?, botId: String): Payload.Handoff? {
        val hold = state.obj("browserHolds").obj(botId) ?: return null
        val callId = hold.s("callId")?.takeIf { it.isNotEmpty() } ?: return null
        return Payload.Handoff(botId, callId, (hold.s("note") ?: hold.s("reason"))?.takeIf { it.isNotBlank() }?.let { ChatBars.clip(it, ChatBars.TITLE_MAX) })
    }

    /** 作答请求体（DS-Bot 0.3.10 channel.js 的参数，源码核对）。 */
    object Requests {
        fun approval(id: String, allow: Boolean) = "{\"id\":${JsonString.of(id)},\"choice\":\"${if (allow) "allow" else "reject"}\"}"

        fun answerQuestion(sessionId: String, questionId: String, selected: List<String>, custom: String? = null): String {
            val sel = selected.joinToString(",") { JsonString.of(it) }
            val cus = custom?.trim()?.takeIf { it.isNotEmpty() }?.let { ",\"custom\":${JsonString.of(it)}" } ?: ""
            return "{\"sessionId\":${JsonString.of(sessionId)},\"questionId\":${JsonString.of(questionId)},\"answer\":{\"selected\":[$sel]$cus}}"
        }

        fun dismissQuestion(sessionId: String) = "{\"sessionId\":${JsonString.of(sessionId)}}"
        fun dismissView(sessionId: String) = "{\"sessionId\":${JsonString.of(sessionId)}}"
        fun secretSet(requestId: String, value: String) = "{\"requestId\":${JsonString.of(requestId)},\"value\":${JsonString.of(value)}}"
        fun secretAllow(requestId: String) = "{\"requestId\":${JsonString.of(requestId)}}"
        fun secretCancel(requestId: String) = "{\"requestId\":${JsonString.of(requestId)}}"
        fun learning(id: String, approve: Boolean) = "{\"id\":${JsonString.of(id)},\"approve\":$approve}"
        fun connectorCancel(requestId: String) = "{\"requestId\":${JsonString.of(requestId)}}"
        /** 带 callId：上游据此判过期（交接已换成新的一次时回 bot/stale），不会误交还新的那次。 */
        fun giveBack(botId: String, callId: String, done: Boolean) =
            "{\"botId\":${JsonString.of(botId)},\"callId\":${JsonString.of(callId)},\"outcome\":\"${if (done) "done" else "skip"}\"}"
        fun btwDelete(sessionId: String, cardId: String) = "{\"sessionId\":${JsonString.of(sessionId)},\"cardId\":${JsonString.of(cardId)}}"
    }
}

/** 连接器请求（connectors/index.js request_connector，源码核对）。 */
data class ConnectorRequest(
    val id: String,
    val connectorId: String?,
    val name: String?,
    val purpose: String?,
    val status: String?,
    val sessionId: String?,
) {
    companion object {
        fun decode(value: Json.Value?): ConnectorRequest? {
            val o = value as? Json.Value.Object ?: return null
            val id = o.s("id")?.takeIf { it.isNotEmpty() } ?: return null
            return ConnectorRequest(id, o.s("connectorId"), o.s("name"), o.s("purpose"), o.s("status"), o.s("sessionId"))
        }
    }
}

/**
 * 学习提案（learning.js reviewSkill / reviewAgents，源码核对）：kind=skill 带 name/description/content，
 * 更新已有技能时另带 previous.content；kind=agents 带 path 与 edits[{op, old, text, why}]。
 */
data class LearningProposal(
    val id: String,
    val kind: String?,
    val action: String?,
    val name: String?,
    val description: String?,
    val why: String?,
    val content: String?,
    val previousContent: String?,
    val path: String?,
    val edits: List<Edit>,
) {
    data class Edit(val op: String?, val old: String?, val text: String?, val why: String?)

    /** 要显示的差异行：技能更新对比原内容、新建技能全部新增、AGENTS.md 逐条删除/新增。 */
    fun lines(): List<LineDiff.Line> = when {
        kind == "agents" -> edits.flatMap { e ->
            LineDiff.split(e.old).map { LineDiff.Line(LineDiff.Op.DEL, it) } +
                LineDiff.split(e.text).map { LineDiff.Line(LineDiff.Op.ADD, it) }
        }
        previousContent != null -> LineDiff.diff(previousContent, content.orEmpty())
        else -> LineDiff.split(content).map { LineDiff.Line(LineDiff.Op.ADD, it) }
    }

    companion object {
        fun decode(value: Json.Value?): LearningProposal? {
            val o = value as? Json.Value.Object ?: return null
            val id = o.s("id")?.takeIf { it.isNotEmpty() } ?: return null
            val previous = o.members["previous"] as? Json.Value.Object
            val edits = (o.members["edits"] as? Json.Value.Array)?.items?.mapNotNull { e ->
                val eo = e as? Json.Value.Object ?: return@mapNotNull null
                Edit(eo.s("op"), eo.s("old"), eo.s("text"), eo.s("why"))
            }?.take(ChatBars.LIST_MAX) ?: emptyList()
            return LearningProposal(
                id = id, kind = o.s("kind"), action = o.s("action"), name = o.s("name"),
                description = o.s("description"), why = o.s("why"), content = o.s("content"),
                previousContent = previous?.s("content"), path = o.s("path"), edits = edits,
            )
        }
    }
}

/** 逐行差异（dsbot-pending-dock 学习审批）：LCS 求最长公共行序列，其余为删除/新增。 */
object LineDiff {
    enum class Op { SAME, ADD, DEL }

    data class Line(val op: Op, val text: String)

    /** 折叠结果：shown 为显示的前若干行，hidden 为折叠掉的行数。 */
    data class Folded(val shown: List<Line>, val hidden: Int)

    /** 超过该行数时折叠。 */
    const val FOLD_AT = 8

    /** 规模上限：超过时按「全部删除 + 全部新增」降级，避免 O(n·m) 爆内存。 */
    private const val MAX_LINES = 400

    /** 按行切分；结尾换行不产生空行（previous.content 常以换行结尾而新内容被上游 trim）。 */
    fun split(text: String?): List<String> =
        if (text.isNullOrEmpty()) emptyList() else text.replace("\r\n", "\n").removeSuffix("\n").split('\n')

    fun diff(old: String, new: String): List<Line> {
        val a = split(old)
        val b = split(new)
        if (a.size > MAX_LINES || b.size > MAX_LINES) {
            return a.map { Line(Op.DEL, it) } + b.map { Line(Op.ADD, it) }
        }
        // lcs[i][j] = a[i..] 与 b[j..] 的最长公共子序列长度
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) {
            for (j in b.size - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val out = mutableListOf<Line>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { out.add(Line(Op.SAME, a[i])); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { out.add(Line(Op.DEL, a[i])); i++ }
                else -> { out.add(Line(Op.ADD, b[j])); j++ }
            }
        }
        while (i < a.size) out.add(Line(Op.DEL, a[i++]))
        while (j < b.size) out.add(Line(Op.ADD, b[j++]))
        return out
    }

    fun fold(lines: List<Line>, max: Int = FOLD_AT): Folded =
        if (lines.size <= max) Folded(lines, 0) else Folded(lines.take(max), lines.size - max)
}

private fun Json.Value.Object.s(key: String): String? = (members[key] as? Json.Value.StringValue)?.value
private fun Json.Value.Object.bool(key: String): Boolean = (members[key] as? Json.Value.BoolValue)?.value ?: false
private fun Json.Value.Object?.obj(key: String): Json.Value.Object? = this?.members?.get(key) as? Json.Value.Object
private fun Json.Value.Object?.arr(key: String): List<Json.Value> = (this?.members?.get(key) as? Json.Value.Array)?.items ?: emptyList()
