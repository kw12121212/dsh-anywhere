package work.keepwriting.core

/**
 * 原生骨架的连接状态机（dsbot-native-shell）：聚合可达性、原生 gate、feed 订阅
 * 与前后台信号，输出连接状态条的状态与需要执行的动作。纯逻辑（pure-logic-in-core），
 * 线程、socket 与 UI 全部外置——App 侧把探测结果与 feed 帧喂进来，按 [Action] 执行。
 *
 * 状态语义（android-dsbot 的 connection-status-bar）：
 *  - Connecting：探测/建会话/feed 首次同步中
 *  - Online：feed 订阅在役且 state 已同步（hello 后无差异也算同步）
 *  - Reconnecting：feed 断开，按 [FeedSync] 的指数退避等待重连
 *  - Offline：harness 不可达（错误页接管；重试动作回 Connecting）
 *  - AuthExpired / ForbiddenHost：原生 gate 判定（重新认证页/提示页接管）
 */
class DsbotConnection(
    private val listener: Listener,
    private val feedSync: FeedSync = FeedSync(listener),
) {
    enum class Status { CONNECTING, ONLINE, RECONNECTING, OFFLINE, AUTH_EXPIRED, FORBIDDEN_HOST }

    /** App 需要执行的动作（状态机的输出边）。 */
    interface Listener : FeedSync.Listener {
        /** 状态条状态变化。 */
        fun onStatus(status: Status)
        /** 需要（重新）走可达性探测与 gate（启动、重试、回前台）。 */
        fun probeAndGate()
        /** 需要建立/重建 feed 订阅（连接成功、回前台、退避到点）。 */
        fun connectFeed()
    }

    var status: Status = Status.CONNECTING
        private set
    private var inForeground = true
    private var feedWanted = false

    private fun set(next: Status) {
        if (status != next) {
            status = next
            listener.onStatus(next)
        }
    }

    // ---- 输入：可达性与 gate ----

    /** 启动/重试/回前台的连接流程开始：清空同步态，新连接周期内 hello 必触发全量取。 */
    fun beginConnect() {
        feedSync.reset()
        set(Status.CONNECTING)
        listener.probeAndGate()
    }

    /** 探测不可达（离线错误页接管）。 */
    fun onUnreachable() {
        feedWanted = false
        set(Status.OFFLINE)
    }

    /** gate 判定。OK 才进入 feed 订阅；其余分流到对应页面。 */
    fun onGate(gate: NativeGate) {
        when (gate) {
            NativeGate.OK -> {
                feedWanted = true
                set(Status.CONNECTING)
                listener.connectFeed()
            }
            NativeGate.AUTH_EXPIRED -> {
                feedWanted = false
                set(Status.AUTH_EXPIRED)
            }
            NativeGate.FORBIDDEN_HOST -> {
                feedWanted = false
                set(Status.FORBIDDEN_HOST)
            }
        }
    }

    /** gate 的三值形态（与 App 侧 NativeSession.Gate 对齐，core 不依赖 App）。 */
    enum class NativeGate { OK, AUTH_EXPIRED, FORBIDDEN_HOST }

    // ---- 输入：feed 订阅 ----

    /** feed 连接建立：只重置退避；ONLINE 由首个事件或 state 应答确认（不闪现）。 */
    fun onFeedConnected() {
        feedSync.onConnected()
    }

    /** feed 断开：前台按退避重连，后台静默等待回前台。 */
    fun onFeedDisconnected(): Long {
        val delay = feedSync.onDisconnected()
        if (feedWanted) {
            if (inForeground) set(Status.RECONNECTING) else set(Status.CONNECTING)
        }
        return delay
    }

    /** 退避到点（App 定时器触发）：前台且仍需要 feed 才重连。 */
    fun onBackoffElapsed() {
        if (feedWanted && inForeground) listener.connectFeed()
    }

    /** feed 事件（SseParser 帧转发）；同步完成（state 取回）后确认 Online。 */
    fun onFeedEvent(event: String, dataJson: String) {
        feedSync.onEvent(event, dataJson)
        if (status != Status.ONLINE && feedWanted) set(Status.ONLINE)
    }

    /** state 应答回填（全量或增量）——同步完成的标志。 */
    fun onStateFetched(state: DsbotModels.State) {
        feedSync.onStateFetched(state)
        if (feedWanted) set(Status.ONLINE)
    }

    fun onStateFetched(delta: DsbotModels.StateDelta) {
        feedSync.onStateFetched(delta)
        if (feedWanted) set(Status.ONLINE)
    }

    // ---- 输入：前后台 ----

    /** 退到后台：断开 feed（状态回 Connecting 等回前台，不打扰）。 */
    fun onBackground() {
        inForeground = false
        if (feedWanted) set(Status.CONNECTING)
    }

    /** 回到前台：重新走探测与 gate（会话可能已失效），再订阅。 */
    fun onForeground() {
        if (inForeground) return
        inForeground = true
        if (feedWanted) beginConnect()
    }

    /** 会话重建成功（设置页保存新地址后）：重新走连接流程。 */
    fun onSessionReestablished() {
        beginConnect()
    }

    /** 测试可见：同步锚点（FeedSync 委托）。 */
    fun localRevisionForTest(): String? = feedSync.localRevision

    fun liveKeyForTest(): String? = feedSync.liveKey
}
