package work.keepwriting.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 定时任务（dsbot-schedules）：schedules 应答解码、时间规则与下次运行文案（网页端 timingText/nextText）、可暂停判定
 *（canPauseTask）、按 Bot 与群聊分组（byChat）、新建校验与 when 构造（TaskForm 与上游 selectorOf）、schedule-* 请求体。
 * 复刻 ds-bot 0.3.10 的 src/host/schedules.js 与 client.js（tech/dsbot-api 的 schedule-endpoints）。时间按注入的 now 与 zone 计算。
 */
object Schedules {
    const val TITLE_MAX = 120
    const val EVERY_MAX_MINUTES = 7 * 24 * 60

    data class Task(
        val id: String,
        val title: String,
        val prompt: String,
        val kind: String,
        val scheduledAt: String?,
        val everySeconds: Long?,
        val time: String?,
        val timeZone: String?,
        val weekdays: List<Int>,
        val expression: String?,
        val status: String,
        val state: String?,
        val sessionId: String?,
        val botId: String?,
        val roomId: String?,
    )

    data class View(val available: Boolean, val canPause: Boolean, val tasks: List<Task>)

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.num(k: String) = (members[k] as? Json.Value.NumberValue)?.raw?.toDoubleOrNull()?.toLong()

    fun decode(value: Json.Value?): View? {
        val o = value as? Json.Value.Object ?: return null
        val available = (o.members["available"] as? Json.Value.BoolValue)?.value == true
        val tasks = ((o.members["tasks"] as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { v ->
            val t = v as? Json.Value.Object ?: return@mapNotNull null
            val id = t.str("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Task(
                id = id, title = t.str("title") ?: "", prompt = t.str("prompt") ?: "", kind = t.str("kind") ?: "",
                scheduledAt = t.str("scheduledAt"), everySeconds = t.num("everySeconds"), time = t.str("time"), timeZone = t.str("timeZone"),
                weekdays = ((t.members["weekdays"] as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { (it as? Json.Value.NumberValue)?.raw?.toDoubleOrNull()?.toInt() },
                expression = t.str("expression"), status = t.str("status") ?: "", state = t.str("state"),
                sessionId = t.str("sessionId"), botId = t.str("botId"), roomId = t.str("roomId"),
            )
        }
        return View(available, (o.members["canPause"] as? Json.Value.BoolValue)?.value == true, tasks)
    }

    // ---- 文案 ----

    private fun epoch(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** 时刻：HH:mm，秒非零时 HH:mm:ss（网页端 clock2）。 */
    fun clock(time: String?): String {
        val m = Regex("^(\\d\\d:\\d\\d)(?::(\\d\\d))?").find(time.orEmpty()) ?: return time.orEmpty().take(5)
        val sec = m.groupValues[2]
        return if (sec.isEmpty() || sec == "00") m.groupValues[1] else "${m.groupValues[1]}:$sec"
    }

    /** 月日与时分（不同年份带年；网页端 dayAndTime）。 */
    fun dayAndTime(ms: Long, now: Long, zone: ZoneId): String {
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
        val sameYear = at.year == LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone).year
        val zh = I18n.lang != ChatBars.Lang.EN
        val pattern = when {
            zh && sameYear -> "M月d日 HH:mm"
            zh -> "yyyy年M月d日 HH:mm"
            sameYear -> "MMM d, HH:mm"
            else -> "MMM d, yyyy, HH:mm"
        }
        return at.format(DateTimeFormatter.ofPattern(pattern, if (zh) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH))
    }

    private val ZH_DAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val EN_DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** 星期几（1=周一 … 7=周日）。 */
    fun dayName(day: Int): String = (if (I18n.lang == ChatBars.Lang.EN) EN_DAYS else ZH_DAYS).getOrElse(day - 1) { "?" }

    fun weekdayNames(days: List<Int>): String = days.joinToString(if (I18n.lang == ChatBars.Lang.EN) ", " else "、") { dayName(it) }

    fun everyText(seconds: Long): String = when {
        seconds <= 0L -> ""
        seconds % 86400 == 0L -> if (seconds == 86400L) I18n.tr("每天", "Every day") else I18n.tr("每 ${seconds / 86400} 天", "Every ${seconds / 86400} days")
        seconds % 3600 == 0L -> if (seconds == 3600L) I18n.tr("每小时", "Every hour") else I18n.tr("每 ${seconds / 3600} 小时", "Every ${seconds / 3600} hours")
        else -> {
            val m = Math.round(seconds / 60.0)
            if (m <= 1L) I18n.tr("每分钟", "Every minute") else I18n.tr("每 $m 分钟", "Every $m minutes")
        }
    }

    /** 时间规则（网页端 timingText）；任务时区与手机不同时注明。 */
    fun timingText(t: Task, now: Long, zone: ZoneId): String {
        val tz = if (!t.timeZone.isNullOrEmpty() && t.timeZone != zone.id) "（${t.timeZone}）".let { if (I18n.lang == ChatBars.Lang.EN) " (${t.timeZone})" else it } else ""
        val c = clock(t.time)
        return when (t.kind) {
            "after", "at" -> epoch(t.scheduledAt)?.let { I18n.tr("一次，${dayAndTime(it, now, zone)}", "Once, ${dayAndTime(it, now, zone)}") } ?: I18n.tr("一次", "Once")
            "every" -> t.everySeconds?.let(::everyText) ?: ""
            "daily" -> I18n.tr("每天 $c", "Every day at $c") + tz
            "weekly" -> {
                val days = t.weekdays.distinct().sorted()
                when (days.joinToString(",")) {
                    "1,2,3,4,5" -> I18n.tr("每个工作日 $c", "Every weekday at $c")
                    "6,7" -> I18n.tr("每个周末 $c", "Every weekend at $c")
                    "1,2,3,4,5,6,7" -> I18n.tr("每天 $c", "Every day at $c")
                    else -> I18n.tr("每${weekdayNames(days)} $c", "Every ${weekdayNames(days)} at $c")
                } + tz
            }
            "cron" -> I18n.tr("按计划 ${t.expression.orEmpty()}", "On the schedule ${t.expression.orEmpty()}") + tz
            "rrule" -> I18n.tr("自定义计划", "Custom schedule")
            else -> ""
        }
    }

    /** 是否已逾期（显式 overdue 或无 state 字段时 active 且已过预定时刻）。 */
    fun isOverdue(t: Task, now: Long): Boolean {
        val at = epoch(t.scheduledAt)
        return t.state == "overdue" || (t.state == null && t.status == "active" && at != null && at <= now)
    }

    /** 下次运行（网页端 nextText）。 */
    fun nextText(t: Task, now: Long, zone: ZoneId): String {
        val at = epoch(t.scheduledAt)
        return when {
            isOverdue(t, now) && at != null -> I18n.tr("已逾期：${dayAndTime(at, now, zone)}", "Overdue: ${dayAndTime(at, now, zone)}")
            t.status == "paused" -> I18n.tr("已暂停", "Paused")
            t.status == "active" && at != null -> I18n.tr("下次：${dayAndTime(at, now, zone)}", "Next: ${dayAndTime(at, now, zone)}")
            else -> I18n.tr("已结束", "Finished")
        }
    }

    /** 到点的一次性任务（暂停会让它永不运行）。 */
    fun lateOnce(t: Task, now: Long): Boolean = (t.kind == "at" || t.kind == "after") && (epoch(t.scheduledAt)?.let { it <= now } ?: false)

    /** 可暂停或恢复（网页端 canPauseTask，另需远端 canPause）。 */
    fun canPause(view: View, t: Task, now: Long): Boolean = view.canPause && (t.status == "active" || t.status == "paused") && !lateOnce(t, now)

    // ---- 分组 ----

    data class Group(val ownerId: String, val name: String, val isRoom: Boolean, val tasks: List<Task>)

    /** 按 state.bots 再 state.rooms 的顺序分组（网页端 byChat）；[onlyOwner] 非空时只留该对象。既有 botId 又有 roomId 的任务归入该 Bot，行内另注明所在群聊。 */
    fun groups(state: Json.Value.Object?, view: View, onlyOwner: String? = null): List<Group> {
        fun named(key: String) = ((state?.members?.get(key) as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { it as? Json.Value.Object }
            .mapNotNull { o -> o.str("id")?.let { it to (o.str("name") ?: it) } }
        val bots = named("bots").map { (id, name) -> Group(id, name, false, view.tasks.filter { it.botId == id }) }
        val rooms = named("rooms").map { (id, name) -> Group(id, name, true, view.tasks.filter { it.botId == null && it.roomId == id }) }
        return (bots + rooms).filter { it.tasks.isNotEmpty() && (onlyOwner == null || it.ownerId == onlyOwner) }
    }

    /** 按 state.rooms 解析群聊名称（用于 Bot 分组内带 roomId 的任务注明所在群聊）。 */
    fun roomName(state: Json.Value.Object?, roomId: String?): String? {
        if (roomId.isNullOrEmpty()) return null
        val rooms = (state?.members?.get("rooms") as? Json.Value.Array)?.items ?: return null
        return rooms.asSequence().mapNotNull { it as? Json.Value.Object }
            .firstOrNull { it.str("id") == roomId }?.str("name")
    }

    /** 某 Bot 进行中的任务数（Bot 详情的「定时任务」行）。 */
    fun activeCount(view: View?, botId: String): Int = view?.tasks?.count { it.botId == botId && it.status == "active" } ?: 0

    // ---- 新建 ----

    sealed interface When {
        /** 一次：本地日期时间（按 zone 解释）。 */
        data class Once(val at: LocalDateTime) : When
        data class Daily(val hour: Int, val minute: Int) : When
        data class Weekly(val hour: Int, val minute: Int, val weekdays: Set<Int>) : When
        /** 重复：每隔 [count] 个 [unitMinutes] 分钟（分钟 1、小时 60、天 1440）。 */
        data class Every(val count: Int, val unitMinutes: Int) : When
    }

    /** 本地预校验（网页端 TaskForm 与上游 selectorOf）；通过时 null。 */
    fun createProblem(target: String?, title: String, prompt: String, whenValue: When, now: Long, zone: ZoneId): String? = when {
        target.isNullOrBlank() -> I18n.tr("选择发送给谁", "Choose who it goes to")
        title.isBlank() -> I18n.tr("给任务起个名字", "Give the task a name")
        title.trim().length > TITLE_MAX -> I18n.tr("名称不超过 $TITLE_MAX 字", "Keep the name under $TITLE_MAX characters")
        prompt.isBlank() -> I18n.tr("写下 Bot 要做的事", "Say what the Bot should do")
        whenValue is When.Once && whenValue.at.atZone(zone).toInstant().toEpochMilli() <= now -> I18n.tr("选择将来的时间", "Pick a time in the future")
        whenValue is When.Daily && (whenValue.hour !in 0..23 || whenValue.minute !in 0..59) -> I18n.tr("时刻无效", "Invalid time")
        whenValue is When.Weekly && (whenValue.hour !in 0..23 || whenValue.minute !in 0..59) -> I18n.tr("时刻无效", "Invalid time")
        whenValue is When.Weekly && (whenValue.weekdays.isEmpty() || whenValue.weekdays.any { it !in 1..7 }) -> I18n.tr("至少选一天", "Pick at least one day")
        whenValue is When.Every && (whenValue.count < 1 || whenValue.unitMinutes < 1 || whenValue.count.toLong() * whenValue.unitMinutes !in 1L..EVERY_MAX_MINUTES) -> I18n.tr("重复间隔为 1 分钟到 7 天", "Repeat every 1 minute to 7 days")
        else -> null
    }

    private fun hm(h: Int, m: Int) = String.format(Locale.ROOT, "%02d:%02d", h, m)

    /** when 参数（上游 selectorOf 的输入）。 */
    fun whenJson(w: When, zone: ZoneId): String = when (w) {
        is When.Once -> "{\"kind\":\"at\",\"at\":" + JsonString.of(w.at.atZone(zone).toInstant().toString()) + "}"
        is When.Daily -> "{\"kind\":\"daily\",\"time\":\"" + hm(w.hour, w.minute) + "\",\"timeZone\":" + JsonString.of(zone.id) + "}"
        is When.Weekly -> "{\"kind\":\"weekly\",\"time\":\"" + hm(w.hour, w.minute) + "\",\"timeZone\":" + JsonString.of(zone.id) +
            ",\"weekdays\":[" + w.weekdays.sorted().joinToString(",") + "]}"
        is When.Every -> "{\"kind\":\"every\",\"minutes\":" + (w.count.toLong() * w.unitMinutes) + "}"
    }

    /** 请求体（schedules.js endpoints 的参数，源码核对）。 */
    object Requests {
        private fun q(s: String) = JsonString.of(s)
        fun list() = "{}"
        fun create(target: String, title: String, prompt: String, w: When, zone: ZoneId) =
            "{\"target\":" + q(target) + ",\"title\":" + q(title.trim()) + ",\"prompt\":" + q(prompt.trim()) + ",\"when\":" + whenJson(w, zone) + "}"
        fun pause(t: Task, active: Boolean) = "{\"sessionId\":" + q(t.sessionId.orEmpty()) + ",\"id\":" + q(t.id) + ",\"active\":" + active + "}"
        fun delete(t: Task) = "{\"sessionId\":" + q(t.sessionId.orEmpty()) + ",\"id\":" + q(t.id) + "}"
    }
}
