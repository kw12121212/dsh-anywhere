package work.keepwriting.core

/**
 * 0.3.10 聊天条（dsbot-chat-bars-0310）：从 state 全量/合并视图（含 live 字段——
 * state 应答是 {...view, liveKey, ...live}，增量 live 经 DsbotModels.mergeDelta 合并进同一对象）
 * 派生后台任务条、电脑操作请求、模型失败修复卡与群目标进度。
 *
 * 字段形态为 DS-Bot 0.3.10 源码核对（tech/dsbot-api 的 chat-bars-fields-0310）：
 * 这些条目不在 hello 能力名单内，按字段存在显隐——缺失、类型不符或畸形一律返回空/null，
 * 绝不抛异常（Bot 与远端数据不可信）。
 */
object ChatBars {
    const val TITLE_MAX = 80
    const val LIST_MAX = 20
    const val STAGES_MAX = 20

    /** 网页端 turn-error.js 同款公开充值页（不含任何用户凭据或 harness 地址）。 */
    const val TOP_UP_URL = "https://platform.deepseek.com/top_up"

    enum class Lang { ZH, EN }

    data class Task(val id: String, val title: String, val status: String, val running: Boolean)

    data class ComputerAsk(
        val id: String,
        val botId: String,
        val sessionId: String?,
        val roomId: String?,
        val appName: String,
    )

    /** 修复卡：title 为可读原因，guidance 为修复指引；topUp 时显示「去充值」（TOP_UP_URL）。 */
    /** [fixInProviders]：修复手段（填写或更换 API key）在 App 的「模型与服务商」页（dsbot-models-providers）。 */
    data class Repair(val code: String, val title: String, val guidance: String, val topUp: Boolean, val fixInProviders: Boolean = false)

    data class Stage(val title: String, val status: String)

    /** 群目标：current 为进行中阶段下标（无则 -1），passed 为已通过阶段数。 */
    data class Goal(
        val title: String,
        val status: String,
        val stages: List<Stage>,
        val current: Int,
        val passed: Int,
    )

    /** 活动中的任务状态（ds-bot threads.js ACTIVE_TASK_STATUSES）。 */
    private val ACTIVE_TASKS = setOf("running", "waiting_work", "waiting_approval")

    /** 只有进行中的目标显示目标条；complete/stopped 不显示。 */
    private val SHOWN_GOALS = setOf("drafting", "active", "paused")

    /** Bot 正在操作电脑的控制态（computer/index.js 的 control state）。 */
    private val COMPUTER_BUSY = setOf("bot", "waiting")

    // ---- 后台任务 ----

    /**
     * 私聊任务条：只取不带 roomId 的活动任务（群里接的任务属于群，对齐网页端 tasksForChat），
     * 排序同网页端 taskSort——等你审批在前，其余按 updatedAt（缺省 startedAt）倒序。
     */
    fun tasks(state: Json.Value.Object?, botId: String): List<Task> {
        val list = botOf(state, botId)?.arr("tasks") ?: return emptyList()
        return list.mapNotNull { v ->
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val status = o.str("status") ?: return@mapNotNull null
            if (status !in ACTIVE_TASKS) return@mapNotNull null
            if (o.members["roomId"] is Json.Value.StringValue) return@mapNotNull null
            val updated = o.num("updatedAt") ?: o.num("startedAt") ?: 0L
            Triple(if (status == "waiting_approval") 0 else 1, updated,
                Task(id, clip(o.str("title")?.trim().orEmpty().ifEmpty { id }, TITLE_MAX), status, o.bool("running")))
        }.sortedWith(compareBy<Triple<Int, Long, Task>> { it.first }.thenByDescending { it.second })
            .map { it.third }.take(LIST_MAX)
    }

    // ---- 电脑操作 ----

    /**
     * 待作答的电脑操作请求：roomId 非空时取该群的请求；否则取 botId 的私聊请求（无 roomId）。
     * 按 at 升序（先来的在前）。
     */
    fun computerAsks(state: Json.Value.Object?, botId: String? = null, roomId: String? = null): List<ComputerAsk> {
        val asks = computerLive(state)?.obj("asks") ?: return emptyList()
        return asks.members.values.mapNotNull { v ->
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val owner = o.str("botId") ?: return@mapNotNull null
            val askRoom = o.str("roomId")
            val matches = if (roomId != null) askRoom == roomId else (owner == botId && askRoom == null)
            if (!matches) return@mapNotNull null
            val at = o.num("at") ?: Long.MAX_VALUE
            at to ComputerAsk(id, owner, o.str("sessionId"), askRoom, clip(o.str("appName")?.trim().orEmpty().ifEmpty { "?" }, TITLE_MAX))
        }.sortedBy { it.first }.map { it.second }.take(LIST_MAX)
    }

    fun computerBusy(state: Json.Value.Object?, botId: String): Boolean {
        val entry = computerLive(state)?.obj("bots")?.obj(botId) ?: return false
        return entry.str("state") in COMPUTER_BUSY
    }

    private fun computerLive(state: Json.Value.Object?): Json.Value.Object? = state?.obj("computerLive")

    // ---- 模型失败修复卡 ----

    fun repair(state: Json.Value.Object?, botId: String, lang: Lang = I18n.lang): Repair? {
        val bot = botOf(state, botId) ?: return null
        val blocked = bot.obj("blocked") ?: return null
        val code = blocked.str("code")?.takeIf { it.isNotEmpty() } ?: return null
        val provider = blocked.str("provider")
        val status = blocked.num("status")
        val name = clip(bot.str("name") ?: botId, TITLE_MAX)
        return repairOf(code, provider, status, name, lang)
    }

    /** blocked 代码到文案（对齐网页端 failureText/fixesFor；密钥内容从不出现）。 */
    fun repairOf(code: String, provider: String?, status: Long?, botName: String, lang: Lang = I18n.lang): Repair {
        val zh = lang == Lang.ZH
        val p = providerName(provider, lang)
        val st = if (status != null) "（$status）" else ""
        val stEn = if (status != null) " ($status)" else ""
        val topUp = (code == "QUOTA" && provider == "deepseek-official") || code == "ACCOUNT_QUOTA"
        val keyFix = if (zh) "请在「模型与服务商」页为该服务商填写或更换 API key（或在 Bot 详情改用其他模型）；修好后此卡会自动消失，未消失可点「重试」。"
        else "Add or replace this provider's API key on the Models & providers page (or choose another model in the Bot's details); this card clears itself once fixed, otherwise tap Retry."
        val signInFix = if (zh) "请在电脑上的网页端重新登录 DeepSeek 账号（或改用其他模型）；登录后此卡会自动消失，未消失可点「重试」。"
        else "Sign in to the DeepSeek account again in the web app on your computer (or choose another model); this card clears itself once signed in, otherwise tap Retry."
        val localFix = if (zh) "请在运行 dsh 的电脑上检查该本地 Agent，处理后点「重试」。"
        else "Check this local agent on the computer running dsh, then tap Retry."
        val (title, guidance) = when (code) {
            "MISSING_CREDENTIAL" -> (if (zh) "$botName 使用的模型还没有 API key" else "$botName's model has no API key yet") to keyFix
            "INVALID_CREDENTIAL" -> (if (zh) "API key 格式不对" else "This API key is not in the right format") to keyFix
            "AUTH" -> (if (zh) "$p 未接受当前 API key$st" else "$p did not accept the current API key$stEn") to keyFix
            "QUOTA" -> (if (zh) "$p 余额或额度不足$st" else "$p is out of balance or quota$stEn") to
                if (topUp) {
                    if (zh) "充值后点「重试」。" else "Top up, then tap Retry."
                } else {
                    if (zh) "请到该服务商充值，或在电脑上的网页端改用其他模型，然后点「重试」。"
                    else "Top up with this provider, or choose another model in the web app, then tap Retry."
                }
            "ACCOUNT_QUOTA" -> (if (zh) "DeepSeek 账号余额不足" else "The DeepSeek account balance is too low") to
                (if (zh) "充值后点「重试」。" else "Top up, then tap Retry.")
            "ACCOUNT_SIGN_IN_REQUIRED", "ACCOUNT_SIGNED_OUT", "ACCOUNT_TOKEN_INVALID" ->
                (if (zh) "DeepSeek 账号登录已失效" else "The DeepSeek account sign-in has expired") to signInFix
            "LOCAL_AGENT_MISSING" -> (if (zh) "找不到本地 Agent" else "The local agent was not found") to localFix
            "LOCAL_AGENT_SIGNIN" -> (if (zh) "本地 Agent 未登录" else "The local agent is not signed in") to localFix
            "LOCAL_AGENT_QUOTA" -> (if (zh) "本地 Agent 额度不足" else "The local agent is out of quota") to localFix
            "LOCAL_AGENT_EXIT" -> (if (zh) "本地 Agent 异常退出" else "The local agent exited unexpectedly") to localFix
            else -> (if (zh) "$botName 上一轮运行失败（$code）" else "$botName's last turn failed ($code)") to
                (if (zh) "请在电脑上的网页端查看原因并处理，然后点「重试」。"
                else "Check the cause in the web app on your computer, then tap Retry.")
        }
        return Repair(code, title, guidance, topUp, fixInProviders = code in KEY_CODES)
    }

    /** 修复手段是填写或更换 API key 的失败代码（可直达「模型与服务商」页）。 */
    private val KEY_CODES = setOf("MISSING_CREDENTIAL", "INVALID_CREDENTIAL", "AUTH")

    private fun providerName(provider: String?, lang: Lang): String = when (provider) {
        null, "" -> if (lang == Lang.ZH) "模型服务" else "The model service"
        "deepseek-official" -> "DeepSeek"
        "deepseek-account" -> if (lang == Lang.ZH) "DeepSeek 账号" else "DeepSeek account"
        else -> clip(provider, TITLE_MAX)
    }

    // ---- 群目标 ----

    fun goal(room: Json.Value.Object?): Goal? {
        val g = room?.obj("goal") ?: return null
        val status = g.str("status") ?: return null
        if (status !in SHOWN_GOALS) return null
        val stages = (g.arr("stages") ?: emptyList()).mapNotNull { v ->
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            Stage(clip(o.str("title")?.trim().orEmpty(), TITLE_MAX), o.str("status") ?: "pending")
        }.take(STAGES_MAX)
        val cur = g.num("current") ?: -1L
        val current = if (cur >= 0 && cur < stages.size) cur.toInt() else -1
        val title = clip(g.str("title")?.trim().orEmpty(), TITLE_MAX)
        return Goal(title, status, stages, current, stages.count { it.status == "passed" })
    }

    fun goalStatusLabel(status: String, lang: Lang = I18n.lang): String = when (status) {
        "drafting" -> if (lang == Lang.ZH) "起草中" else "Drafting"
        "active" -> if (lang == Lang.ZH) "进行中" else "In progress"
        "paused" -> if (lang == Lang.ZH) "已暂停" else "Paused"
        else -> status
    }

    fun taskStatusLabel(status: String, lang: Lang = I18n.lang): String = when (status) {
        "running" -> if (lang == Lang.ZH) "运行中" else "Running"
        "waiting_approval" -> if (lang == Lang.ZH) "等你审批" else "Waiting for approval"
        "waiting_work" -> if (lang == Lang.ZH) "等待交接" else "Waiting for hand-off"
        else -> status
    }

    // ---- 写操作失败提示 ----

    /** 聊天条写操作（停止任务、电脑作答/停止、重试）失败的可读提示；network 为网络失败。 */
    fun actionError(code: String?, lang: Lang = I18n.lang): String {
        val zh = lang == Lang.ZH
        return when (code) {
            null, "network" -> if (zh) "操作失败：网络不可用，请稍后重试" else "Failed: the network is unavailable, try again later"
            "bot/not-found" -> if (zh) "操作失败：任务、请求或 Bot 已不存在" else "Failed: the task, request or Bot no longer exists"
            "bot/stale" -> if (zh) "操作失败：状态已变化，请刷新后再试" else "Failed: the state changed, refresh and try again"
            "bot/read-only" -> if (zh) "操作失败：远端当前只读（另一个 dsh 正在使用该团队）" else "Failed: the team is read-only on this host"
            "bot/invalid" -> if (zh) "操作失败：请求参数无效" else "Failed: the request was invalid"
            "bot/forbidden", "bot/refused" -> if (zh) "操作失败：远端拒绝了该操作" else "Failed: the host refused this action"
            "bot/unavailable" -> if (zh) "操作失败：该功能在远端不可用" else "Failed: this feature is unavailable on the host"
            else -> if (zh) "操作失败（$code）" else "Failed ($code)"
        }
    }

    // ---- 工作详情（live 的 work） ----

    /** 私聊：live work.bots[botId]（{since, tool?, said?, note?, target?, roomId?}）。 */
    fun botWork(state: Json.Value.Object?, botId: String): Json.Value.Object? =
        state?.obj("work")?.obj("bots")?.obj(botId)

    /** 群聊：live work.rooms[roomId] 的成员工作（botId → 工作详情），保持服务端顺序。 */
    fun roomWork(state: Json.Value.Object?, roomId: String): List<Pair<String, Json.Value.Object>> {
        val list = state?.obj("work")?.obj("rooms")?.arr(roomId) ?: return emptyList()
        return list.mapNotNull { v ->
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            val id = o.str("botId") ?: return@mapNotNull null
            id to o
        }.take(LIST_MAX)
    }

    // ---- 小工具 ----

    private fun botOf(state: Json.Value.Object?, botId: String): Json.Value.Object? =
        state?.arr("bots")?.firstOrNull { (it as? Json.Value.Object)?.str("id") == botId } as? Json.Value.Object

    /** 截断到 max 个 UTF-16 单元（含省略号），不切开代理对。 */
    internal fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = max - 1
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end) + "…"
    }

    private fun Json.Value.Object.str(key: String): String? = (members[key] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.obj(key: String): Json.Value.Object? = members[key] as? Json.Value.Object
    private fun Json.Value.Object.arr(key: String): List<Json.Value>? = (members[key] as? Json.Value.Array)?.items
    private fun Json.Value.Object.bool(key: String): Boolean = (members[key] as? Json.Value.BoolValue)?.value ?: false
    private fun Json.Value.Object.num(key: String): Long? =
        (members[key] as? Json.Value.NumberValue)?.raw?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
}
