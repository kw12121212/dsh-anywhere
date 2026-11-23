package work.keepwriting.core

/**
 * 聊天流畅度（dsbot-chat-cache）的纯逻辑：内存 LRU 聊天缓存、滚动位置记忆、读取单飞、
 * 行身份复用与「回到最新 / N 条新消息」按钮状态。只学上游手机端 chat-cache 的行为，
 * 全部只在内存（聊天内容不落盘）。非线程安全：由调用方的单线程 executor 收敛。
 */

/** 最近 [capacity] 个聊天的 LRU 缓存；键见 [key]。 */
class ChatCache<T>(private val capacity: Int = CAPACITY) {
    /** 缓存条目：当前已显示的条目、更早记录游标、会话 id、附带文本（群公告等）。 */
    data class Entry<T>(
        val items: List<T>,
        val cursor: Json.Value?,
        val sessionId: String?,
        val extra: String? = null,
    )

    private val map = object : LinkedHashMap<String, Entry<T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<T>>?): Boolean = size > capacity
    }

    operator fun get(key: String): Entry<T>? = map[key]

    fun put(key: String, entry: Entry<T>) {
        map[key] = entry
    }

    /** 分段切换等使分页历史失效时丢弃该键。 */
    fun invalidate(key: String) {
        map.remove(key)
    }

    val size: Int get() = map.size

    /** 按最近访问从旧到新的键（测试与诊断用）。 */
    fun keys(): List<String> = map.keys.toList()

    companion object {
        const val CAPACITY = 16

        /** 「主机:类型:id」——不同 harness 主机的同名 id 互不串用。 */
        fun key(host: String?, kind: String, id: String): String = "${host ?: ""}:$kind:$id"
    }
}

/** 最近 [capacity] 个聊天的滚动位置（首个可见项下标与像素偏移）。 */
class ScrollMemory(private val capacity: Int = CAPACITY) {
    data class Position(val index: Int, val offset: Int)

    private val map = object : LinkedHashMap<String, Position>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Position>?): Boolean = size > capacity
    }

    operator fun get(key: String): Position? = map[key]

    fun save(key: String, index: Int, offset: Int) {
        if (index < 0 || offset < 0) return
        map[key] = Position(index, offset)
    }

    fun forget(key: String) {
        map.remove(key)
    }

    val size: Int get() = map.size

    companion object {
        const val CAPACITY = 50
    }
}

/**
 * 读取单飞：同一目标同时只跑一次读取，在途期间再触发的刷新最多排队一次；
 * 换目标立即以新代号开始，旧读取的结果到达时按代号判过期丢弃。
 */
class ReadFlight {
    sealed interface Begin {
        /** 立即发起读取，结果回来时以 [gen] 调 [finish]。 */
        data class Start(val gen: Int) : Begin
        /** 同目标在途：已记一次排队，完成后会要求重跑。 */
        data object Queued : Begin
        /** 同目标在途且已排队：忽略。 */
        data object Ignored : Begin
    }

    /** stale：结果已被更新的读取取代（丢弃）；rerun：有排队且目标未变，应再读一次。 */
    data class Finish(val stale: Boolean, val rerun: Boolean)

    private var gen = 0
    private var target: String? = null
    private var inFlight = false
    private var queued = false

    val current: String? get() = target

    fun begin(id: String): Begin {
        if (inFlight && target == id) {
            if (queued) return Begin.Ignored
            queued = true
            return Begin.Queued
        }
        target = id
        inFlight = true
        queued = false
        return Begin.Start(++gen)
    }

    /** 分页历史失效（分段切换）：在途读取的结果一律作废，下一次 begin 立即开始。 */
    fun reset() {
        gen++
        inFlight = false
        queued = false
    }

    fun finish(gen: Int): Finish {
        if (gen != this.gen) return Finish(stale = true, rerun = false)
        inFlight = false
        val rerun = queued
        queued = false
        return Finish(stale = false, rerun = rerun)
    }
}

/** 行身份复用：刷新后的新条目尽量沿用旧行（相同内容），减少重组与列表跳动。 */
object RowIdentity {
    /**
     * 返回与 [new] 等长的数组：每项为复用的 [old] 下标，-1 表示新条目。按内容（equals）
     * 匹配；同内容多条时按出现顺序一一对应，每个旧下标最多复用一次。
     */
    fun <T> match(old: List<T>, new: List<T>): IntArray {
        val pool = HashMap<T, ArrayDeque<Int>>()
        old.forEachIndexed { i, item -> pool.getOrPut(item) { ArrayDeque() }.addLast(i) }
        return IntArray(new.size) { j -> pool[new[j]]?.removeFirstOrNull() ?: -1 }
    }
}

/** 「回到最新 / N 条新消息」按钮状态：离底距离超过阈值为 away；away 期间新到条目累加。 */
class JumpButton(private val thresholdPx: Float) {
    var away: Boolean = false
        private set
    var newCount: Int = 0
        private set

    /** 滚动或布局变化后喂入当前离底距离；回到底部附近即清零。 */
    fun onDistance(distanceBelowPx: Float) {
        val far = distanceBelowPx > thresholdPx
        if (jumping) {
            if (far) return
            jumping = false
        }
        away = far
        if (!away) newCount = 0
    }

    /** 列表末尾新增 [n] 条（实时消息或刷新）；不在 away 状态时不计数。 */
    fun onAppended(n: Int) {
        if (away && n > 0) newCount += n
    }

    /** 点按按钮回到最新：滚动动画途中距离仍大于阈值，到达底部前不再判为 away（避免按钮闪回）。 */
    fun onJumped() {
        away = false
        newCount = 0
        jumping = true
    }

    /** 跳转动画结束或被打断：恢复正常的离底判断。 */
    fun onJumpEnded() {
        jumping = false
    }

    private var jumping = false

    /** 按钮文案；不显示时为 null。 */
    fun label(): String? = when {
        !away -> null
        newCount > 0 -> I18n.tr("$newCount 条新消息", "$newCount new messages")
        else -> I18n.tr("回到最新", "Jump to latest")
    }
}

object ScrollDistance {
    /**
     * 估算离列表底部的像素距离：最后可见项底部超出视口的部分，加上其后未布局的项数乘平均项高。
     * 列表为空或已见末项且末项完全在视口内时为 0。
     */
    fun belowPx(
        totalItems: Int,
        lastVisibleIndex: Int,
        lastVisibleBottomPx: Int,
        viewportEndPx: Int,
        averageItemPx: Float,
    ): Float {
        if (totalItems <= 0 || lastVisibleIndex < 0) return 0f
        val overflow = (lastVisibleBottomPx - viewportEndPx).coerceAtLeast(0)
        val after = (totalItems - 1 - lastVisibleIndex).coerceAtLeast(0)
        return overflow + after * averageItemPx.coerceAtLeast(0f)
    }
}

/**
 * 后台刷新的合并决策（dsbot-chat-cache）：服务端首页是最新 N 条的窗口，新消息会让窗口前移。
 * 当前列表 = 更早记录 + 旧首页 + 实时追加；新首页的开头若能在当前列表中找到（之后连续
 * [MIN_RUN] 条一致，或一直一致到当前列表末尾），说明两者有重叠、中间无缺口：保留重叠点之前
 * 的条目与原游标，尾部换成新首页。找不到重叠（窗口已整体越过、分段换了）或被要求强制重置时，
 * 整体换成新首页并改用新游标（宁可让用户重新上翻，也不显示有缺口的历史）。
 */
object ChatRefresh {
    const val MIN_RUN = 3

    /** items：合并后的列表；keepCursor：保留原更早记录游标（否则用新首页游标）；changed：列表有变化。 */
    data class Result<T>(val items: List<T>, val keepCursor: Boolean, val changed: Boolean)

    /**
     * [isLocal]：本地尚未落到服务端的条目（乐观发送、流式定格且无序号）；被替换掉的尾部里这类条目若在
     * 新首页中找不到对应（[matches]），合并后重新追加到末尾——读取与发送并发时自己刚发的消息不会暂时消失。
     * 分段切换的强制重置不保留本地条目。
     */
    fun <T> merge(
        current: List<T>,
        fresh: List<T>,
        forceReset: Boolean = false,
        isLocal: (T) -> Boolean = { false },
        matches: (T, T) -> Boolean = { a, b -> a == b },
    ): Result<T> {
        if (forceReset) return Result(fresh, keepCursor = false, changed = fresh != current)
        if (current.isEmpty()) return Result(fresh, keepCursor = false, changed = fresh.isNotEmpty())
        val k = if (fresh.isEmpty()) -1 else overlapStart(current, fresh)
        val base = if (k < 0) fresh else current.subList(0, k) + fresh
        val dropped = if (k < 0) current else current.subList(k, current.size)
        val keptLocal = dropped.filter { item -> isLocal(item) && fresh.none { matches(item, it) } }
        val merged = base + keptLocal
        return Result(merged, keepCursor = k >= 0, changed = merged != current)
    }

    /**
     * 新首页开头在当前列表中的重叠起点，无则 -1。合格条件：从该位置起与新首页连续一致至少 [MIN_RUN] 条，
     * 或新首页本身不足 [MIN_RUN] 条且全部一致到当前列表末尾。多个合格位置取连续一致最长的（相同取靠后），
     * 避免内容相同的条目（如相同工具卡）在末尾造成伪重叠而重复或漏掉消息。
     */
    internal fun <T> overlapStart(current: List<T>, fresh: List<T>): Int {
        var best = -1
        var bestRun = 0
        for (k in current.indices) {
            if (current[k] != fresh[0]) continue
            val available = minOf(current.size - k, fresh.size)
            var run = 0
            while (run < available && current[k + run] == fresh[run]) run++
            val ok = run >= MIN_RUN || (fresh.size < MIN_RUN && run == available)
            if (ok && run >= bestRun) {
                best = k
                bestRun = run
            }
        }
        return best
    }
}
