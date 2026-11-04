package work.keepwriting.core

/**
 * 隧道状态机（android-tstunnel 的判定核心）：五态与转移判定以纯函数表达，
 * :app 的 TunnelManager 只做装配与线程纪律。判定语义来自 milestone 约定：
 * 无 key 且无持久状态→未配置且不调用 Up（Up 无 key 无状态会永久阻塞）；
 * 有持久状态无 key→凭状态重连；Up 失败按错误文本分类为需要 key 或失败。
 */
object TunnelStateMachine {
    enum class State { NOT_CONFIGURED, CONNECTING, CONNECTED, NEEDS_KEY, FAILED }

    /** 启动决策：返回目标状态与是否应发起连接（false 时完全不触碰 tsnet）。 */
    data class StartDecision(val state: State, val shouldStart: Boolean)

    /**
     * 判定是否启动隧道：无 key 且无持久节点状态→NOT_CONFIGURED 且不启动
     * （绝不进入 Up：无 key 无状态时 tsnet.Up 永久阻塞，参考项目真机实测）；
     * 有 key→注册连接；仅凭持久状态→免 key 重连。
     */
    fun decideStart(hasAuthKey: Boolean, hasPersistedState: Boolean): StartDecision {
        return when {
            !hasAuthKey && !hasPersistedState -> StartDecision(State.NOT_CONFIGURED, shouldStart = false)
            else -> StartDecision(State.CONNECTING, shouldStart = true)
        }
    }

    /**
     * Up 失败分类：错误文本含 needs-login / 节点被删除 / key 无效或过期 / 拒绝类
     * →NEEDS_KEY（重新输入 key 可恢复）；其余 →FAILED（附原因）。
     */
    fun classifyUpFailure(errorText: String): State {
        val lower = errorText.lowercase()
        val needsKey = listOf(
            "needs-login", "needslogin",
            // 真机实测 tsnet 报 "invalid key: unable to validate API key"（无 auth 一词）。
            "invalid key", "invalid auth", "expired", "already used",
            "node no longer exists", "not found", "unauthorized", "denied",
        ).any { lower.contains(it) }
        return if (needsKey) State.NEEDS_KEY else State.FAILED
    }

    /** 清除（key 与节点状态删除后）的落点。 */
    fun cleared(): State = State.NOT_CONFIGURED

    /**
     * Up 超时（上游 tsnet v1.94.1 对纯 NeedsLogin 挂起不报错，只能吃满期限）后，
     * 读 ipnstate.Status 的 BackendState 补分类：NeedsLogin/NeedsMachineAuth → 需要 key。
     */
    fun needsKeyFromStatusJson(statusJson: String): Boolean {
        return statusJson.contains("\"BackendState\":\"NeedsLogin\"") ||
            statusJson.contains("\"BackendState\":\"NeedsMachineAuth\"")
    }
}
