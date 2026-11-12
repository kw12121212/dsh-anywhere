package work.keepwriting.core

/**
 * feed 订阅与断线补齐的纯状态机（dsbot-api-client，契约来自 ds-bot feed.js 与
 * spike 实测）：订阅即收 hello{revision}；revision（团队保存）与 live（工作状态）
 * 事件都要求重新取 state——revision/live 与本地一致时以 since+live 增量询问，
 * 服务端 unchanged 则零负载；message/stream 事件只是当前聊天的增量，不触发取
 * state。断线由调用方告知，重连节奏用指数退避（上限 60s，成功即归零）。
 * 写操作（send 等）失败不自动重试——由调用方自行决定，状态机不代发。
 *
 * 纯逻辑、时钟与动作全部外置：App 侧把 SseParser 的帧喂进来，取 state 的动作
 * 经 [Listener] 回调，JUnit 用假时钟覆盖重连与补齐路径。
 */
class FeedSync(
    private val listener: Listener,
    private val backoff: (Int) -> Long = ::defaultBackoffMs,
) {
    interface Listener {
        /** 需要重新取 state：localRevision 为 null 表示全量；否则 since+live 增量。 */
        fun fetchState(localRevision: String?, liveKey: String?)
    }

    /** 本地已同步到的 revision 与最近一次应答的 liveKey（null 表示尚无）。 */
    var localRevision: String? = null
        private set
    var liveKey: String? = null
        private set

    private var backoffAttempt = 0
    private var connected = false

    /** 清空本地同步态（下次 hello 必然不同 → 触发全量取 state；刷新/会话重建用）。 */
    fun reset() {
        localRevision = null
        liveKey = null
    }

    /** 订阅建立（收到 hello 前也算已连接：hello 只是第一帧）。 */
    fun onConnected() {
        connected = true
        backoffAttempt = 0
    }

    /** 断线：返回建议的下次重连延迟（指数退避），成功连接后归零。 */
    fun onDisconnected(): Long {
        connected = false
        val delay = backoff(backoffAttempt)
        backoffAttempt += 1
        return delay
    }

    fun isConnected(): Boolean = connected

    /** 收到一帧 feed 事件（dataJson 为原始 JSON 文本；hello/revision 里读不出
     *  revision 时按「本地可能落后」保守触发一次增量取，服务端 unchanged 兜底）。 */
    fun onEvent(event: String, dataJson: String) {
        when (event) {
            "hello" -> {
                // 首次订阅：与本地 revision 不同（或本地没有）即需要取 state
                val remote = revisionOf(dataJson)
                if (remote != localRevision) listener.fetchState(localRevision, liveKey)
            }
            "revision" -> {
                val remote = revisionOf(dataJson)
                if (remote != null && remote == localRevision) return // 重连后重复通知
                listener.fetchState(localRevision, liveKey)
            }
            "live" -> listener.fetchState(localRevision, liveKey)
            // message/stream：当前聊天的增量，由聊天层处理
        }
    }

    /** state 应答回填：全量给 revision，增量给 delta（unchanged 时 liveKey 也可能翻新）。 */
    fun onStateFetched(state: DsbotModels.State) {
        localRevision = state.revision
        if (state.liveKey != null) liveKey = state.liveKey
    }

    fun onStateFetched(delta: DsbotModels.StateDelta) {
        if (delta.revision != null) localRevision = delta.revision
        if (delta.liveKey != null) liveKey = delta.liveKey
    }

    private fun revisionOf(dataJson: String): String? =
        (Json.parse(dataJson) as? Json.Value.Object)?.let {
            (it.members["revision"] as? Json.Value.NumberValue)?.raw
                ?: (it.members["revision"] as? Json.Value.StringValue)?.value
        }

    companion object {
        /** 指数退避：1s、2s、4s … 封顶 60s（无抖动，确定性便于测试；App 可自行叠加）。 */
        fun defaultBackoffMs(attempt: Int): Long =
            minOf(1_000L shl attempt.coerceIn(0, 16), 60_000L)
    }
}
