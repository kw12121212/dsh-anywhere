package work.keepwriting.core

import java.util.Locale

/**
 * Bot 后台任务详情纯逻辑（dsbot-bot-task-detail）：
 * 对应 DS-Bot 0.3.10 的 bot-task-read {botId, taskId, cursor?, steps?} 与
 * bot-task-send {botId, taskId, text}（契约登记在 tech/dsbot-api 的 bot-task-detail-endpoints-0310）。
 *
 * 复用 DsbotModels.decodeChatPage 与 ChatRender.renderItem/extras，额外解码 taskView 专有的
 * task-brief、steps（每轮工具步骤与耗时）与 divider，并提供与网页端 TaskReaderView 一致的
 * 分页去重合并（按 (sessionId, seq) 与 steps 轮次）。
 */
object BotTaskDetail {
    /** 活动任务详情页前台可见时的轮询间隔（对齐网页端 TaskReaderView 的 2500ms）。 */
    const val POLL_INTERVAL_MS = 2_500L

    /** 单条 steps 卡片最多保留的工具步骤数（防畸形超长载荷）。 */
    const val MAX_TOOLS_PER_STEPS = 200

    /** 步骤卡片里的单个工具调用。 */
    data class StepTool(val name: String, val label: String?, val ok: Boolean?) {
        fun displayName(): String = label?.trim()?.takeIf { it.isNotEmpty() } ?: name.trim().ifEmpty { "tool" }
    }

    /** 详情页渲染条目：复用 ChatRender.Item，外加任务视图专有条目。 */
    sealed interface Entry {
        data class Chat(val item: ChatRender.Item) : Entry
        data class TaskBrief(val fromName: String?, val text: String) : Entry
        data class Steps(val turn: Long?, val tools: List<StepTool>, val durationMs: Long?) : Entry
        data class Divider(val time: Long?) : Entry
    }

    /** 带去重定位信息的记录行。 */
    data class Record(
        val sessionId: String?,
        val seq: Long?,
        val turn: Long?,
        val entry: Entry,
    ) {
        /** 对齐网页端 TaskReaderView：有整数 seq 时以 "sessionId:seq" 去重，否则返回 null。 */
        fun dedupKey(fallbackSessionId: String?): String? =
            seq?.let { "${sessionId ?: fallbackSessionId ?: ""}:$it" }
    }

    /** bot-task-read 单页解码结果。 */
    data class Page(
        val sessionId: String?,
        val records: List<Record>,
        val cursor: Json.Value?,
        val exhausted: Boolean,
    )

    object Requests {
        /** bot-task-read 请求体：cursor 为 null/JSON null 时省略，steps 仅在为 true 时携带。 */
        fun read(botId: String, taskId: String, cursor: Json.Value? = null, steps: Boolean = false): String {
            val parts = buildList {
                add("\"botId\":${JsonString.of(botId)}")
                add("\"taskId\":${JsonString.of(taskId)}")
                if (cursor != null && cursor !is Json.Value.NullValue) add("\"cursor\":${Json.encode(cursor)}")
                if (steps) add("\"steps\":true")
            }
            return "{${parts.joinToString(",")}}"
        }

        /** bot-task-send 请求体：trim 后为空返回 null（禁止发出空消息）。 */
        fun send(botId: String, taskId: String, text: String): String? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            return "{\"botId\":${JsonString.of(botId)},\"taskId\":${JsonString.of(taskId)},\"text\":${JsonString.of(trimmed)}}"
        }

        /** bot-stop-task 请求体。 */
        fun stop(botId: String, taskId: String): String =
            "{\"botId\":${JsonString.of(botId)},\"taskId\":${JsonString.of(taskId)}}"
    }

    /** 解码 bot-task-read 的 value 对象；缺失或畸形字段安全降级，JSON null cursor 视为已到最早。 */
    fun decodePage(value: Json.Value): Page {
        val rawPage = DsbotModels.decodeChatPage(value)
        val out = mutableListOf<Record>()
        for (raw in rawPage.items) {
            val obj = raw.raw as? Json.Value.Object ?: continue
            val itemSid = obj.str("sessionId") ?: rawPage.sessionId
            when (raw.kind) {
                "task-brief" -> {
                    val text = raw.text?.takeIf { it.isNotEmpty() } ?: continue
                    out.add(Record(itemSid, raw.seq, raw.turn, Entry.TaskBrief(obj.str("name"), text)))
                }
                "steps" -> {
                    val tools = decodeTools(obj)
                    if (tools.isNotEmpty()) {
                        val ms = obj.num("ms")?.takeIf { it > 0L }
                        out.add(Record(itemSid, raw.seq, raw.turn, Entry.Steps(raw.turn, tools, ms)))
                    }
                }
                "divider" -> {
                    out.add(Record(itemSid, raw.seq, raw.turn, Entry.Divider(raw.time)))
                }
                else -> {
                    ChatRender.renderItem(raw)?.let { out.add(Record(itemSid, raw.seq, raw.turn, Entry.Chat(it))) }
                    for (extra in ChatRender.extras(raw)) {
                        out.add(Record(itemSid, raw.seq, raw.turn, Entry.Chat(extra)))
                    }
                }
            }
        }
        return Page(
            sessionId = rawPage.sessionId,
            records = out,
            cursor = rawPage.cursor,
            exhausted = rawPage.cursor == null,
        )
    }

    /**
     * 合并已加载的更早页与最新页（对齐网页端 TaskReaderView）：
     * - 最新页中已出现的 (sessionId, seq) 从更早页剔除，避免轮询刷新后重复
     * - steps 条目不带 seq，按 (sessionId, turn) 与最新页去重，避免跨分段同 turn 误删
     */
    fun mergePages(older: List<Record>, latest: List<Record>, pageSessionId: String?): List<Record> {
        if (older.isEmpty()) return latest
        if (latest.isEmpty()) return older
        val latestSeqs = latest.mapNotNull { it.dedupKey(pageSessionId) }.toHashSet()
        val latestStepKeys = latest.mapNotNull { r ->
            if (r.entry is Entry.Steps && r.turn != null) "${r.sessionId ?: pageSessionId ?: ""}:${r.turn}" else null
        }.toHashSet()
        val uniqueOlder = older.filter { r ->
            val key = r.dedupKey(pageSessionId)
            val seqDup = key != null && key in latestSeqs
            val stepKey = if (r.entry is Entry.Steps && r.turn != null) "${r.sessionId ?: pageSessionId ?: ""}:${r.turn}" else null
            val stepDup = stepKey != null && stepKey in latestStepKeys
            !seqDup && !stepDup
        }
        return uniqueOlder + latest
    }

    /** 前插新取回的更早页，对已存在的更早页去重。 */
    fun prependEarlier(newEarlier: List<Record>, existingOlder: List<Record>, pageSessionId: String?): List<Record> =
        mergePages(newEarlier, existingOlder, pageSessionId)

    /** 列表中是否已有 Bot 文本回复（无回复且仍在活动态时底部可显示状态指示）。 */
    fun hasBotReply(records: List<Record>): Boolean =
        records.any { (it.entry as? Entry.Chat)?.item.let { item -> item is ChatRender.Item.Message && !item.fromUser } }

    /**
     * 已运行时长文案：先按手机当前时间封顶，防止服务端时钟略快出现负数或异常。
     */
    fun formatElapsed(startedAt: Long, nowMs: Long, lang: ChatBars.Lang = I18n.lang): String {
        val elapsedSec = ((nowMs - minOf(startedAt, nowMs)) / 1000L).coerceAtLeast(0L)
        val zh = lang == ChatBars.Lang.ZH
        if (elapsedSec < 60L) return if (zh) "${elapsedSec}秒" else "${elapsedSec}s"
        val min = elapsedSec / 60L
        val sec = elapsedSec % 60L
        if (min < 60L) return if (zh) "${min}分${sec}秒" else "${min}m ${sec}s"
        val hr = min / 60L
        val remMin = min % 60L
        return if (zh) "${hr}时${remMin}分" else "${hr}h ${remMin}m"
    }

    /**
     * 结束时间相对文案（dsbot-bot-task-detail）：先按手机当前时间封顶，避免服务端时钟略快导致异常。
     */
    fun formatEndedTime(endedAt: Long, nowMs: Long, lang: ChatBars.Lang = I18n.lang): String {
        val diffSec = ((nowMs - minOf(endedAt, nowMs)) / 1000L).coerceAtLeast(0L)
        val zh = lang == ChatBars.Lang.ZH
        if (diffSec < 60L) return if (zh) "刚刚结束" else "Ended just now"
        val min = diffSec / 60L
        if (min < 60L) return if (zh) "${min}分钟前结束" else "Ended ${min}m ago"
        val hr = min / 60L
        if (hr < 24L) return if (zh) "${hr}小时前结束" else "Ended ${hr}h ago"
        val days = hr / 24L
        return if (zh) "${days}天前结束" else "Ended ${days}d ago"
    }

    /** 思考/步骤耗时文案（毫秒）。 */
    fun formatDurationMs(ms: Long, lang: ChatBars.Lang = I18n.lang): String {
        val clamped = ms.coerceAtLeast(0L)
        val zh = lang == ChatBars.Lang.ZH
        if (clamped < 1000L) return if (zh) "${clamped}毫秒" else "${clamped}ms"
        val sec = clamped / 1000.0
        val fmt = String.format(Locale.US, if (sec < 10.0) "%.1f" else "%.0f", sec)
        return if (zh) "${fmt}秒" else "${fmt}s"
    }

    /** 读取失败（bot-task-read）的可读提示。 */
    fun readError(code: String?, message: String?, lang: ChatBars.Lang = I18n.lang): String {
        val zh = lang == ChatBars.Lang.ZH
        return when (code) {
            null, "network" -> if (zh) "读取任务记录失败：网络不可用，请重试" else "Could not load task log: network unavailable, try again"
            "bot/not-found" -> when {
                message?.contains("Unknown endpoint", ignoreCase = true) == true ->
                    if (zh) "此远端版本不支持查看后台任务详情" else "This host does not support background task details"
                message?.contains("Unknown Bot", ignoreCase = true) == true ->
                    if (zh) "该 Bot 已不存在" else "This Bot no longer exists"
                else ->
                    if (zh) "该任务已不存在或此远端不支持查看任务详情" else "This task no longer exists or is unsupported on this host"
            }
            "bot/invalid" -> if (zh) "读取任务记录失败：请求参数无效" else "Could not load task log: invalid request"
            "bot/forbidden", "bot/refused" -> if (zh) "读取任务记录失败：远端拒绝访问" else "Could not load task log: access refused"
            else -> if (zh) "读取任务记录失败（$code）" else "Could not load task log ($code)"
        }
    }

    /** 发送追加消息失败（bot-task-send）的可读提示。 */
    fun sendError(code: String?, message: String?, lang: ChatBars.Lang = I18n.lang): String {
        val zh = lang == ChatBars.Lang.ZH
        return when (code) {
            null, "network" -> if (zh) "发送失败：网络不可用，草稿已保留" else "Send failed: network unavailable, draft kept"
            "bot/not-found" -> if (zh) "发送失败：任务或 Bot 已不存在" else "Send failed: task or Bot no longer exists"
            "bot/read-only" -> if (zh) "发送失败：远端当前只读" else "Send failed: host is read-only"
            "bot/invalid" -> {
                if (message?.contains("cannot receive", ignoreCase = true) == true) {
                    if (zh) "发送失败：任务已结束，无法继续发送消息" else "Send failed: task has ended and cannot receive messages"
                } else {
                    if (zh) "发送失败：任务已不可接收消息或内容无效" else "Send failed: task cannot receive messages or input is invalid"
                }
            }
            else -> ChatBars.actionError(code, lang)
        }
    }

    private fun decodeTools(obj: Json.Value.Object): List<StepTool> {
        val arr = (obj.members["tools"] as? Json.Value.Array)?.items ?: return emptyList()
        return arr.mapNotNull { item ->
            val o = item as? Json.Value.Object ?: return@mapNotNull null
            val name = o.str("name") ?: o.str("label") ?: return@mapNotNull null
            val label = o.str("label")
            val ok = (o.members["ok"] as? Json.Value.BoolValue)?.value
            StepTool(name = name, label = label, ok = ok)
        }.take(MAX_TOOLS_PER_STEPS)
    }

    private fun Json.Value.Object.str(key: String): String? =
        (members[key] as? Json.Value.StringValue)?.value?.takeIf { it.isNotEmpty() }

    private fun Json.Value.Object.num(key: String): Long? =
        (members[key] as? Json.Value.NumberValue)?.raw?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
}
