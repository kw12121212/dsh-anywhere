package work.keepwriting.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.regex.Pattern

/**
 * 群内搜索（dsbot-group-search）：group-search {roomId, query, before?, offset?} 的请求构造、应答解码、
 * 加载更多游标与去重合并，以及结果行的片段截取、关键词高亮区间、发言者与时间文案。
 * 端点形态见 tech/dsbot-api group-search-endpoint-0310（FINDINGS 第 42 节实测）；交互语义与常量照搬
 * DS-Bot 0.3.10 client.js src/client/group-search.js（MIN_QUERY、SNIPPET_CHARS、termsOf、marked、
 * snippetOf、authorOf、loadMore）。
 */
object GroupSearch {
    /** 网页端 MIN_QUERY：去首尾空白后不足 2 个字符不发请求。 */
    const val MIN_QUERY = 2

    /** 网页端 DEBOUNCE_MS：输入停顿 220ms 后才发请求。 */
    const val DEBOUNCE_MS = 220L

    /** 网页端 SNIPPET_CHARS：结果行片段长度。 */
    const val SNIPPET_CHARS = 180

    /** 片段在首个命中词之前保留的字符数（网页端 snippetOf 的 60）。 */
    private const val SNIPPET_LEAD = 60

    /** 一行消息：命中本身或其前后邻居（上游 lineOf）。 */
    data class Line(
        val seq: Long?,
        val time: Long?,
        val text: String,
        /** botId 键存在（含 null）即为成员消息；缺键为用户自己的消息（网页端 line.botId === undefined）。 */
        val fromMember: Boolean,
        val botId: String?,
        val name: String?,
    )

    data class Hit(val line: Line, val before: Line?, val after: Line?)

    data class Page(val results: List<Hit>, val hasMore: Boolean)

    /** 加载更多的分页参数：最旧命中 seq 为整数时用 before，否则用 offset=已有条数（网页端 loadMore）。 */
    sealed interface Cursor {
        data class Before(val seq: Long) : Cursor
        data class Offset(val count: Int) : Cursor
    }

    // ---- 查询 ----

    /** JS 的 \s 字符集（含 NBSP、全角空格与 BOM），与网页端 split(/\s+/) 与 trim() 同界。 */
    private const val JS_SPACE = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private val SPACES = Regex("[$JS_SPACE]+")
    private val EDGES = Regex("^[$JS_SPACE]+|[$JS_SPACE]+$")

    /** 发送给服务端的查询文本：去首尾空白（网页端 query.trim()）。 */
    fun queryText(raw: String): String = raw.replace(EDGES, "")

    /** 去首尾空白后是否够发起搜索。 */
    fun searchable(raw: String): Boolean = queryText(raw).length >= MIN_QUERY

    /** 高亮用的查询词：小写、按空白切分、去重（网页端 termsOf）。 */
    fun termsOf(raw: String): List<String> =
        raw.lowercase(Locale.ROOT).split(SPACES).filter { it.isNotEmpty() }.distinct()

    /** group-search 请求体；[cursor] 为 null 时是首屏。 */
    fun searchRequest(roomId: String, query: String, cursor: Cursor? = null): String {
        val head = "\"roomId\":${JsonString.of(roomId)},\"query\":${JsonString.of(queryText(query))}"
        return when (cursor) {
            null -> "{$head}"
            is Cursor.Before -> "{$head,\"before\":${cursor.seq}}"
            is Cursor.Offset -> "{$head,\"offset\":${cursor.count}}"
        }
    }

    // ---- 应答 ----

    fun decode(value: Json.Value?): Page {
        val obj = value as? Json.Value.Object ?: return Page(emptyList(), false)
        val results = (obj.members["results"] as? Json.Value.Array)?.items.orEmpty().mapNotNull { item ->
            val o = item as? Json.Value.Object ?: return@mapNotNull null
            val line = lineOf(o) ?: return@mapNotNull null
            Hit(line, (o.members["before"] as? Json.Value.Object)?.let(::lineOf), (o.members["after"] as? Json.Value.Object)?.let(::lineOf))
        }.let(::distinctBySeq) // 畸形应答页内重复 seq 只留首条：列表以 seq 为键，重复会让界面崩溃
        // 上游 hasMore 只在确有更早命中时为 true；其他形态一律视为没有更多
        return Page(results, (obj.members["hasMore"] as? Json.Value.BoolValue)?.value == true)
    }

    private fun lineOf(o: Json.Value.Object): Line? {
        val text = (o.members["text"] as? Json.Value.StringValue)?.value ?: return null
        return Line(
            seq = integerOf(o.members["seq"]),
            time = (o.members["time"] as? Json.Value.NumberValue)?.raw?.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong(),
            text = text,
            fromMember = o.members.containsKey("botId"),
            botId = (o.members["botId"] as? Json.Value.StringValue)?.value,
            name = (o.members["name"] as? Json.Value.StringValue)?.value,
        )
    }

    /** 只接受整数 seq（网页端 Number.isInteger）；小数、科学计数之外的非整数视为缺失。 */
    private fun integerOf(value: Json.Value?): Long? {
        val raw = (value as? Json.Value.NumberValue)?.raw ?: return null
        raw.toLongOrNull()?.let { return it }
        val d = raw.toDoubleOrNull() ?: return null
        return if (d.isFinite() && d == Math.floor(d) && Math.abs(d) <= 9.007199254740991E15) d.toLong() else null
    }

    /** 加载更多的游标：已有结果最后一条（最旧）seq 为整数用 before，否则 offset=已有条数。 */
    fun nextCursor(results: List<Hit>): Cursor =
        results.lastOrNull()?.line?.seq?.let { Cursor.Before(it) } ?: Cursor.Offset(results.size)

    /** 追加下一页：按 seq 去重（无 seq 的命中总是保留），与网页端 loadMore 一致。 */
    fun merge(previous: Page, incoming: Page): Page =
        Page(distinctBySeq(previous.results + incoming.results), incoming.hasMore)

    private fun distinctBySeq(hits: List<Hit>): List<Hit> {
        val seen = HashSet<Long>()
        return hits.filter { h -> h.line.seq.let { it == null || seen.add(it) } }
    }

    // ---- 页面状态（dsbot-group-search 评审 M2：可回归拦截的显示判定） ----

    /** 某个查询（去首尾空白后的文本）的首屏/累积结果。 */
    data class Answer(val query: String, val page: Page)

    /** 某个查询的失败文案（首屏或加载更多）。 */
    data class Failure(val query: String, val text: String)

    /** 搜索页当前应显示的状态。 */
    sealed interface Display {
        data object Prompt : Display
        data object Searching : Display
        data class Failed(val text: String) : Display
        data object Empty : Display
        data class Results(val hits: List<Hit>, val count: String, val canLoadMore: Boolean, val moreError: String?) : Display
    }

    /**
     * 显示判定：不足 2 个字符为输入提示；当前查询的首屏失败显示原因；结果只在属于当前查询时显示
     * （修改关键词后旧结果与旧错误都不再显示，等新应答期间为「搜索中」）；加载更多失败只在页脚显示且保留已有结果。
     */
    fun display(raw: String, answer: Answer?, failure: Failure?, moreFailure: Failure?): Display {
        val text = queryText(raw)
        if (text.length < MIN_QUERY) return Display.Prompt
        if (failure?.query == text) return Display.Failed(failure.text)
        val current = answer?.takeIf { it.query == text } ?: return Display.Searching
        if (current.page.results.isEmpty()) return Display.Empty
        return Display.Results(
            current.page.results,
            countLabel(current.page.results.size),
            current.page.hasMore,
            moreFailure?.takeIf { it.query == text }?.text,
        )
    }

    /** 加载更多的游标：结果属于当前查询、还有更早命中且不在加载中时才有，否则 null（网页端 loadMore 守卫）。 */
    fun loadMoreCursor(raw: String, answer: Answer?, paging: Boolean): Cursor? {
        val current = answer ?: return null
        if (paging || current.query != queryText(raw) || !current.page.hasMore) return null
        return nextCursor(current.page.results)
    }

    /** 搜索页文案（网页端 GroupSearch 的中英文案）。 */
    object Copy {
        val title get() = I18n.tr("搜索这个群", "Search this group")
        fun placeholder(roomName: String?) =
            if (roomName.isNullOrBlank()) I18n.tr("搜索这个群的消息", "Search this group’s messages")
            else I18n.tr("在 $roomName 里搜索消息", "Search messages in $roomName")
        val promptLabel get() = I18n.tr("搜索这个群的消息", "Search this group’s messages")
        val promptHint get() = I18n.tr("至少输入两个字", "Type at least two characters")
        val searching get() = I18n.tr("搜索中…", "Searching…")
        val noResults get() = I18n.tr("没有找到消息", "No messages found")
        val noResultsHint get() = I18n.tr("换个词试试", "Try a different word")
        val loadMore get() = I18n.tr("加载更多", "Load more")
        val backToResults get() = I18n.tr("返回结果", "Back to results")
        val around get() = I18n.tr("这条消息的前后", "Around this message")
        val clear get() = I18n.tr("清除搜索", "Clear search")
    }

    // ---- 呈现 ----

    /**
     * 结果行片段（网页端 snippetOf）：不超过 [SNIPPET_CHARS] 原样返回；否则从首个命中词前
     * [SNIPPET_LEAD] 个字符起截 [SNIPPET_CHARS] 个字符，被截断的一侧加「…」。
     */
    fun snippetOf(text: String, terms: List<String>): String {
        if (text.length <= SNIPPET_CHARS) return text
        val lower = text.lowercase(Locale.ROOT)
        val at = terms.fold(-1) { best, term ->
            val found = lower.indexOf(term)
            if (found >= 0 && (best < 0 || found < best)) found else best
        }
        val from = maxOf(0, (if (at < 0) 0 else at) - SNIPPET_LEAD)
        val to = minOf(text.length, from + SNIPPET_CHARS)
        return (if (from > 0) "…" else "") + text.substring(from, to) + (if (from + SNIPPET_CHARS < text.length) "…" else "")
    }

    /**
     * 关键词高亮区间（网页端 marked：查询词按原顺序组成不区分大小写的交替正则，在原文上逐段匹配）。
     * 返回按位置递增、互不重叠的 [start, end) 区间。
     */
    fun highlights(text: String, terms: List<String>): List<IntRange> {
        if (terms.isEmpty() || text.isEmpty()) return emptyList()
        val pattern = Pattern.compile(terms.joinToString("|") { Pattern.quote(it) }, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        val m = pattern.matcher(text)
        val out = mutableListOf<IntRange>()
        while (m.find()) {
            if (m.end() > m.start()) out.add(m.start() until m.end())
        }
        return out
    }

    /** 发言者（网页端 authorOf）：自己的消息为「你」；成员以名册当前名称为先、条目 name 为后备，都缺为「某个 Bot」。 */
    fun authorOf(line: Line, roster: Map<String, RoomChat.Speaker> = emptyMap()): String =
        if (!line.fromMember) I18n.tr("你", "You")
        else line.botId?.let { roster[it]?.name } ?: line.name ?: I18n.tr("某个 Bot", "a Bot")

    /** 成员颜色：名册为先（命中行不带 color）。 */
    fun colorOf(line: Line, roster: Map<String, RoomChat.Speaker> = emptyMap()): String? =
        line.botId?.let { roster[it]?.color }

    /**
     * 搜索失败文案：服务端原因优先（网页端 hostText(error.message)，已知的「Unknown group chat」本地化为
     * 「找不到这个群聊」）；远端无此端点时提示版本不支持；网络失败给可读提示。
     */
    fun errorText(code: String?, message: String?): String = when {
        code == null || code == "network" -> I18n.tr("搜索失败：网络不可用，请重试", "Search failed: network unavailable, try again")
        message?.contains("Unknown endpoint", ignoreCase = true) == true -> I18n.tr("此远端版本不支持群内搜索", "This host does not support group search")
        message == "Unknown group chat" -> I18n.tr("找不到这个群聊", "Unknown group chat")
        !message.isNullOrBlank() -> message
        else -> I18n.tr("搜索失败（$code）", "Search failed ($code)")
    }

    /** 命中计数（网页端 "1 match" / "{count} matches"）。 */
    fun countLabel(count: Int): String =
        if (count == 1) I18n.tr("1 条结果", "1 match") else I18n.tr("$count 条结果", "$count matches")

    /**
     * 时间（网页端 clockLabel）：今天/昨天 + 时刻，其余为带星期的月日（不同年份带年）+ 时刻。
     * 中文 24 小时制，英文 12 小时制。
     */
    fun timeLabel(ms: Long, now: Long, zone: ZoneId): String {
        val zh = I18n.lang != ChatBars.Lang.EN
        val at = Instant.ofEpochMilli(ms).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val clock = at.format(DateTimeFormatter.ofPattern(if (zh) "HH:mm" else "h:mm a", if (zh) Locale.SIMPLIFIED_CHINESE else Locale.US))
        val days = ChronoUnit.DAYS.between(at.toLocalDate(), today)
        if (days == 0L) return I18n.tr("今天 $clock", "Today $clock")
        if (days == 1L) return I18n.tr("昨天 $clock", "Yesterday $clock")
        val sameYear = at.year == today.year
        val pattern = when {
            zh && sameYear -> "M月d日EEE"
            zh -> "yyyy年M月d日EEE"
            sameYear -> "EEE, MMM d"
            else -> "EEE, MMM d, yyyy"
        }
        return at.format(DateTimeFormatter.ofPattern(pattern, if (zh) Locale.SIMPLIFIED_CHINESE else Locale.US)) + " " + clock
    }
}
