package work.keepwriting.core

/**
 * 发送箱（dsbot-outbox-retry）：按聊天保存尚未被服务端确认的本地消息行（发送中 / 未发送），
 * 服务端出现对应用户行后移除。只学上游手机端 outbox 的行为；只在内存、不自动重发。
 * 非线程安全：由调用方的单线程 executor 收敛。
 */
class Outbox {
    /** SENDING：请求在途；SENT：请求成功、等服务端用户行出现；FAILED：请求失败、等用户重试或删除。 */
    enum class State { SENDING, SENT, FAILED }

    /**
     * 本地行：[text] 为实际提交的文本（对账用），[display] 为显示文本（含附件名），[receipts] 为附件
     * 回执（重试时原样重发），[window] 为发送时该聊天已知的最大顺序值（私聊为 seq，群聊为服务端 time）——
     * 只有顺序值更大的服务端行才能确认它。
     */
    data class Entry(
        val id: Long,
        val chatKey: String,
        val text: String,
        val display: String,
        val receipts: List<String>,
        val state: State,
        val window: Long,
    )

    /** 服务端用户行：order 为顺序值（私聊 seq、群聊服务端 time），为空时无法判断先后，不参与对账。 */
    data class UserRow(val order: Long?, val text: String)

    private val byChat = LinkedHashMap<String, MutableList<Entry>>()
    private var nextId = 1L

    fun add(chatKey: String, text: String, display: String, receipts: List<String>, window: Long): Entry {
        val e = Entry(nextId++, chatKey, text, display, receipts, State.SENDING, window)
        byChat.getOrPut(chatKey) { mutableListOf() }.add(e)
        return e
    }

    fun entries(chatKey: String): List<Entry> = byChat[chatKey]?.toList() ?: emptyList()

    fun get(id: Long): Entry? = byChat.values.asSequence().flatten().firstOrNull { it.id == id }

    /** 改状态（重试置回 SENDING、失败置 FAILED）；条目已被对账移除时返回 null。 */
    fun setState(id: Long, state: State): Entry? {
        for (list in byChat.values) {
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) {
                val updated = list[i].copy(state = state)
                list[i] = updated
                return updated
            }
        }
        return null
    }

    fun remove(id: Long): Boolean {
        for ((key, list) in byChat) {
            if (list.removeAll { it.id == id }) {
                if (list.isEmpty()) byChat.remove(key)
                return true
            }
        }
        return false
    }

    /**
     * 对账：为本地行在 [userRows] 中找第一条顺序值非空、大于其窗口、未被占用、规范化文本相同的服务端行，
     * 找到即移除该本地行；分两轮——先让发送中/已发送的行按发送顺序匹配，剩余的服务端行再给未发送的行
     * （未发送但实际已送达的也要消掉，免得重试重复；又不能让一条真失败的行抢走后来同文本发送的确认）。
     * 返回被移除的 id。
     */
    fun reconcile(chatKey: String, userRows: List<UserRow>): List<Long> {
        val list = byChat[chatKey] ?: return emptyList()
        val used = HashSet<Int>()
        val removed = mutableListOf<Long>()
        val ordered = list.filter { it.state != State.FAILED } + list.filter { it.state == State.FAILED }
        for (e in ordered) {
            val want = normalize(e.text)
            val hit = userRows.indices.firstOrNull { i ->
                val order = userRows[i].order
                i !in used && order != null && order > e.window && normalize(userRows[i].text) == want
            } ?: continue
            used.add(hit)
            list.remove(e)
            removed.add(e.id)
        }
        if (list.isEmpty()) byChat.remove(chatKey)
        return removed
    }

    companion object {
        private val SPACES = Regex("\\s+")

        /** 去空白比较：折叠连续空白、去首尾。 */
        fun normalize(text: String): String = text.replace(SPACES, " ").trim()
    }
}

/** 照片压缩的尺寸换算（dsbot-outbox-retry）：长边超过上限时等比缩小，否则不处理（不放大）。 */
object ImageSizing {
    const val MAX_EDGE = 2048

    /** 缩小后的 (宽, 高)；不需要缩小或尺寸无效时返回 null。 */
    fun target(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int>? {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return null
        val long = maxOf(width, height)
        if (long <= maxEdge) return null
        val scale = maxEdge.toDouble() / long
        val w = maxOf(1, Math.round(width * scale).toInt())
        val h = maxOf(1, Math.round(height * scale).toInt())
        return if (width >= height) maxEdge to h else w to maxEdge
    }

    /** 解码时的 2 的幂次采样率：在不低于目标尺寸的前提下尽量降低内存。 */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        var sample = 1
        val long = maxOf(width, height)
        while (long / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }
}
