package work.keepwriting.core

/**
 * 团队的 DSH Agent（dsbot-member-agents）：上游每个团队至多一个，是普通 dsh 会话而不是 Bot（ds-bot 0.3.10 agents.js）。
 * 解码 state.agents 与 live work.agents，校验会话标题，生成各端点请求体。端点形态见 tech/dsbot-api 的 dsh-agent-endpoints-0310。
 */
object DshAgents {
    const val TITLE_MAX = 80
    const val CAPABILITY = "agents"

    data class Agent(val id: String, val name: String, val sessions: List<String>, val createdAt: Long?) {
        /** 最新会话（agent-chat 读的就是它）；全部归档后为 null。 */
        val currentSession: String? get() = sessions.lastOrNull()
    }

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value

    /** state.agents（数组；也兼容对象映射）；缺 id 的条目跳过。 */
    fun agents(state: Json.Value.Object?): List<Agent> {
        val raw = state?.members?.get("agents")
        val list = when (raw) {
            is Json.Value.Array -> raw.items
            is Json.Value.Object -> raw.members.values.toList()
            else -> emptyList()
        }
        return list.mapNotNull { a ->
            val o = a as? Json.Value.Object ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            Agent(
                id = id,
                name = o.str("name") ?: "DSH Agent",
                sessions = (o.members["sessions"] as? Json.Value.Array)?.items?.mapNotNull { (it as? Json.Value.StringValue)?.value }.orEmpty(),
                createdAt = (o.members["createdAt"] as? Json.Value.NumberValue)?.raw?.toLongOrNull(),
            )
        }
    }

    fun agentOf(state: Json.Value.Object?, id: String): Agent? = agents(state).firstOrNull { it.id == id }

    /** live work.agents 里有该 Agent 即在工作中。 */
    fun running(state: Json.Value.Object?, id: String): Boolean {
        val work = state?.members?.get("work") as? Json.Value.Object ?: return false
        val agents = work.members["agents"] as? Json.Value.Object ?: return false
        return agents.members[id] is Json.Value.Object
    }

    /** 能否添加：远端声明 agents 能力且团队还没有 DSH Agent。 */
    fun canAdd(capabilities: Capabilities, state: Json.Value.Object?): Boolean =
        capabilities.has(CAPABILITY) && state != null && agents(state).isEmpty()

    /** 会话标题校验：trim 后不能为空；超过 80 字由服务端截断，这里提示。 */
    fun titleProblem(title: String): String? = when {
        title.isBlank() -> I18n.tr("请输入会话标题", "Enter a session title")
        title.trim().length > TITLE_MAX -> I18n.tr("标题最多 80 字", "A title holds at most 80 characters")
        else -> null
    }

    object Requests {
        private fun q(s: String) = JsonString.of(s)
        fun add() = "{}"
        /** agent-chat；[cursorJson] 为上一页应答里原样透传的 cursor（JSON 文本），加载更早时带上。 */
        fun chat(agentId: String, cursorJson: String? = null) =
            "{\"agentId\":" + q(agentId) + (if (cursorJson != null) ",\"cursor\":$cursorJson" else "") + "}"
        fun newSession(agentId: String) = "{\"agentId\":" + q(agentId) + "}"
        fun archive(agentId: String, sessionId: String) = "{\"agentId\":" + q(agentId) + ",\"sessionId\":" + q(sessionId) + "}"
        fun rename(agentId: String, sessionId: String, title: String) =
            "{\"agentId\":" + q(agentId) + ",\"sessionId\":" + q(sessionId) + ",\"title\":" + q(title.trim()) + "}"
        fun remove(agentId: String) = "{\"agentId\":" + q(agentId) + "}"
        fun stop(agentId: String) = "{\"agentId\":" + q(agentId) + "}"
    }
}
