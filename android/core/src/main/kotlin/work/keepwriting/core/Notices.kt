package work.keepwriting.core

/**
 * 应用内提醒（dsbot-notices-banner）：DS-Bot 0.3.10 的 feed notice 事件与 notices {boot, since}
 * 补齐应答的解码、横幅文案，以及 (boot, seq) 游标。形态以 dsbot-baseline-0310 的真实夹具为准
 * （notices.json、notices-reset.json、feed-stream-0310.sse）。提醒文本只用于界面，不进日志。
 */
object Notices {
    /** 计划变更约定的白名单；kind=view 与未知类型忽略。 */
    val KINDS = setOf("question", "approval", "message", "group", "done")

    const val SUMMARY_MAX = 80

    data class Notice(
        val seq: Long,
        val time: Long?,
        val botId: String,
        val name: String?,
        val kind: String,
        val sessionId: String?,
        val roomId: String?,
        val title: String?,
        val text: String?,
    )

    /** notices 应答：reset=true 表示陌生 boot 或缓冲已越过 since，须重取整体状态。 */
    data class Batch(val boot: String?, val seq: Long?, val reset: Boolean, val notices: List<Notice>)

    /** 解码单条提醒（feed notice 的 data 或 notices[] 的元素）；缺 seq/botId/kind 返回 null。 */
    fun decode(value: Json.Value?): Notice? {
        val o = value as? Json.Value.Object ?: return null
        val seq = o.long("seq") ?: return null
        val botId = o.str("botId")?.takeIf { it.isNotEmpty() } ?: return null
        val kind = o.str("kind") ?: return null
        return Notice(seq, o.long("time"), botId, o.str("name"), kind, o.str("sessionId"), o.str("roomId"), o.str("title"), o.str("text"))
    }

    fun decodeBatch(value: Json.Value?): Batch? {
        val o = value as? Json.Value.Object ?: return null
        val list = (o.members["notices"] as? Json.Value.Array)?.items ?: return null
        return Batch(
            boot = o.str("boot"),
            seq = o.long("seq"),
            reset = (o.members["reset"] as? Json.Value.BoolValue)?.value ?: false,
            notices = list.mapNotNull(::decode),
        )
    }

    /** 是否显示为横幅（白名单内）。 */
    fun shown(n: Notice): Boolean = n.kind in KINDS

    /**
     * 横幅文案：「[群名 · ]名称 · 类型：摘要」。上游 mobile.js 中 title 只在群场景出现、值为群名
     * （roomLineAdded / approvalWaiting 的 room.name），摘要一律取 text（群消息正文、审批的工具名、
     * 提问卡文本）；text 为空（如群任务完成）时只显示来源与类型。
     */
    fun banner(n: Notice): String {
        val label = when (n.kind) {
            "question" -> I18n.tr("提问", "question")
            "approval" -> I18n.tr("等你审批", "needs approval")
            "message" -> I18n.tr("回复", "reply")
            "group" -> I18n.tr("群消息", "group message")
            "done" -> I18n.tr("任务完成", "task done")
            else -> n.kind
        }
        val name = n.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Bot"
        val room = n.title?.trim()?.takeIf { it.isNotEmpty() && !n.roomId.isNullOrEmpty() }
        val source = if (room != null) "${ChatBars.clip(room, 24)} · $name" else name
        val summary = n.text.orEmpty().replace(Regex("\\s+"), " ").trim()
        val head = "$source · $label"
        return if (summary.isEmpty()) head else head + I18n.tr("：", ": ") + ChatBars.clip(summary, SUMMARY_MAX)
    }

    /**
     * 从一批新提醒（按到达顺序）中选出要显示的那一条：最后一条在白名单内、且目标不是当前打开聊天的提醒；
     * 都不符合时返回 null。实时与补齐共用，避免「最新一条恰是当前聊天」时把其他人在等的提醒一并压掉。
     */
    fun pickBanner(fresh: List<Notice>, currentTarget: String?): Notice? =
        fresh.lastOrNull { shown(it) && target(it) != currentTarget }

    /** 点按横幅进入的聊天：群提醒进群（roomId），否则进该 Bot 私聊。 */
    fun target(n: Notice): String = n.roomId?.takeIf { it.isNotEmpty() } ?: n.botId

    private fun Json.Value.Object.str(key: String): String? = (members[key] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.long(key: String): Long? =
        (members[key] as? Json.Value.NumberValue)?.raw?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
}

/**
 * (boot, seq) 游标：首次连接与远端重启时直接采用 hello 的游标（不补弹历史），同一 boot 内断线重连
 * 经 notices 补齐；实时 notice 的 seq 不大于已见值即丢弃（乱序/重复）。非线程安全，由 executor 收敛。
 */
class NoticeCursor {
    sealed interface HelloAction {
        /** 无需动作（首次采用游标、无新提醒、远端不带游标）。 */
        data object None : HelloAction
        /** 同一 boot 有新提醒：以 notices {boot, since} 补齐。 */
        data class Fill(val boot: String, val since: Long) : HelloAction
        /** 远端重启或回退：已采用新游标，应重取整体状态，不补弹旧提醒。 */
        data object Reset : HelloAction
    }

    /** onBatch 结果：refetch=true 时应重取整体状态；fresh 为新接收（按 seq 升序）的提醒。 */
    data class BatchResult(val refetch: Boolean, val fresh: List<Notices.Notice>)

    var boot: String? = null
        private set
    var seq: Long = 0
        private set

    fun onHello(helloBoot: String?, helloSeq: Long?): HelloAction {
        if (helloBoot == null) {
            val had = boot != null
            boot = null
            seq = 0
            return if (had) HelloAction.Reset else HelloAction.None
        }
        val current = boot
        if (current == null) {
            boot = helloBoot
            seq = helloSeq ?: 0
            return HelloAction.None
        }
        if (current != helloBoot) {
            boot = helloBoot
            seq = helloSeq ?: 0
            return HelloAction.Reset
        }
        return if (helloSeq != null && helloSeq > seq) HelloAction.Fill(current, seq) else HelloAction.None
    }

    /** 实时提醒：seq 大于已见值才接收并推进游标；未建立游标（未收到 hello）时也接收。 */
    fun accept(n: Notices.Notice): Boolean {
        if (n.seq <= seq) return false
        seq = n.seq
        return true
    }

    fun onBatch(batch: Notices.Batch): BatchResult {
        if (batch.reset || batch.boot == null || batch.boot != boot) {
            boot = batch.boot
            seq = batch.seq ?: 0
            return BatchResult(refetch = true, fresh = emptyList())
        }
        val fresh = batch.notices.sortedBy { it.seq }.filter { accept(it) }
        batch.seq?.let { if (it > seq) seq = it }
        return BatchResult(refetch = false, fresh = fresh)
    }
}
