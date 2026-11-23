package work.keepwriting.core

/**
 * 本机已读状态（dsbot-read-state）：按 harness 主机分开保存每个 Bot/群的最后已读时间（epoch 毫秒，
 * 取服务端时间——条目 lastAt 与聊天条目 time）。不可变值：mark/merge 返回新实例。
 *
 * 只前移：非正数与不大于已有值的标记不改变状态；解码忽略非正数与非数字，整体非法回空
 * （state-persistence 的容错读取）。网页端已读在浏览器本地，不与手机同步。
 */
class ReadState private constructor(private val hosts: Map<String, Map<String, Long>>) {
    /** 某主机下某条目的已读时间；未标记为 0。 */
    fun seenAt(host: String, id: String): Long = hosts[host]?.get(id) ?: 0L

    /** 标记已读到 [at]；只前移。 */
    fun mark(host: String, id: String, at: Long): ReadState {
        if (at <= 0 || host.isEmpty() || id.isEmpty() || at <= seenAt(host, id)) return this
        val entries = (hosts[host] ?: emptyMap()) + (id to at)
        return ReadState(hosts + (host to entries))
    }

    /** 该主机是否已有任何已读记录。 */
    fun knows(host: String): Boolean = hosts[host]?.isNotEmpty() == true

    /**
     * 首次在某主机上看到团队时以当前最近活动时间打底（该主机已有记录则不变）：升级到本版本或换 harness
     * 后不至于整页都是未读点，之后的新活动照常显示未读。
     */
    fun seed(host: String, lastAts: Map<String, Long?>): ReadState {
        if (host.isEmpty() || knows(host)) return this
        var out = this
        for ((id, at) in lastAts) if (at != null) out = out.mark(host, id, at)
        return out
    }

    /** 逐项取较大的已读时间。 */
    fun merge(other: ReadState): ReadState {
        var out = this
        for ((host, entries) in other.hosts) for ((id, at) in entries) out = out.mark(host, id, at)
        return out
    }

    /** 未读 = 不是当前打开的聊天，且最近活动时间有效并晚于已读时间。 */
    fun isUnread(host: String, id: String, lastAt: Long?, open: Boolean): Boolean =
        !open && lastAt != null && lastAt > 0 && lastAt > seenAt(host, id)

    fun encode(): String = buildString {
        append("{\"v\":1,\"hosts\":{")
        hosts.entries.forEachIndexed { i, (host, entries) ->
            if (i > 0) append(',')
            append(JsonString.of(host)).append(":{")
            entries.entries.forEachIndexed { j, (id, at) ->
                if (j > 0) append(',')
                append(JsonString.of(id)).append(':').append(at)
            }
            append('}')
        }
        append("}}")
    }

    override fun equals(other: Any?): Boolean = other is ReadState && other.hosts == hosts

    override fun hashCode(): Int = hosts.hashCode()

    companion object {
        val EMPTY = ReadState(emptyMap())

        /**
         * 打开聊天时应标记的已读时间：max(条目 lastAt, 控制器读到的最新条目时间)；控制器的 newest 只在
         * 属于 [targetId] 时采用（切换聊天时不把上一个聊天的时间记过来）。都没有为 0（不标记）。
         */
        fun readAt(targetId: String, lastAt: Long?, newest: Pair<String, Long>?): Long =
            maxOf(lastAt ?: 0L, newest?.takeIf { it.first == targetId }?.second ?: 0L)

        /** 解码持久化文本；缺失、损坏或形态不符一律为空，单个非法值跳过。 */
        fun decode(text: String?): ReadState {
            if (text.isNullOrBlank()) return EMPTY
            val root = try {
                Json.parse(text) as? Json.Value.Object
            } catch (_: Exception) {
                null
            } ?: return EMPTY
            val hostsObj = root.members["hosts"] as? Json.Value.Object ?: return EMPTY
            var out = EMPTY
            for ((host, value) in hostsObj.members) {
                val entries = value as? Json.Value.Object ?: continue
                for ((id, at) in entries.members) {
                    val ms = (at as? Json.Value.NumberValue)?.raw?.toLongOrNull() ?: continue
                    out = out.mark(host, id, ms)
                }
            }
            return out
        }

        /**
         * 主机标识：harness 地址的 scheme://host:port（无显式端口时补默认端口），不含路径、查询与 token，
         * 同一主机不同 token/路径共用已读；无法解析时为 null（不标记、不显示未读）。
         */
        fun hostKey(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val uri = try {
                java.net.URI(url.trim())
            } catch (_: Exception) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val port = if (uri.port > 0) uri.port else when (scheme) {
                "https" -> 443
                "http" -> 80
                else -> return null
            }
            return "$scheme://$host:$port"
        }
    }
}
