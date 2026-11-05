package work.keepwriting.core

import java.net.URI

/**
 * 首次设置页与设置页「先验证再保存」的编排（android-setup-verify）：格式校验 →
 * 隧道步骤 → 对干净地址的可达性探测，任一步失败即返回对应结果、不再往下走。
 * 隧道连接与探测以函数注入（生产为 TunnelManager.connectAndAwait 与经分流拨号的
 * DialProbe.probe），这里只做判定，结果到界面文案的映射留在 :app。
 */
object SetupCheck {
    /** 验证阶段（界面据此显示「正在连接 Tailscale」「正在检查地址」）。 */
    enum class Stage { TUNNEL, PROBE }

    sealed interface Result {
        /** 全部通过：可以保存，cleanUrl 为去掉 token 的干净地址。 */
        data class Ok(val cleanUrl: String) : Result
        data class InvalidUrl(val category: HarnessUrl.ErrorCategory) : Result

        /** tailnet 地址、未填 key，而隧道未配置或需要 key：提示填写 auth key。 */
        data object MissingKey : Result

        /** 用 key 连接或凭节点状态重连后仍未连接（需要 key 或失败）。 */
        data class TunnelFailed(val state: TunnelStateMachine.State) : Result
        data class Unreachable(val reason: ProbeFailure.Reason) : Result
    }

    /** 主页面连接出错时提示去设置检查的项目。 */
    enum class Hint { CHECK_TAILSCALE, CHECK_ADDRESS }

    /**
     * @param keyInput 用户填写的 auth key（设置页传空串：隧道由 Tailscale 卡片管理）。
     * @param connectTunnel 以 key 连接（null 表示凭已有节点状态重连）并返回最终状态。
     * @param probe 对干净地址做可达性探测。
     */
    fun verify(
        rawUrl: String,
        keyInput: String,
        tunnelState: TunnelStateMachine.State,
        connectTunnel: (key: String?) -> TunnelStateMachine.State,
        probe: (cleanUrl: String) -> DialProbe.ProbeResult,
        onStage: (Stage) -> Unit = {},
    ): Result {
        val clean = try {
            HarnessUrl.normalize(rawUrl)
        } catch (e: HarnessUrl.ValidationError) {
            return Result.InvalidUrl(e.category)
        }
        val key = keyInput.trim()
        val tailnet = isTailnetUrl(clean)
        val connected = TunnelStateMachine.State.CONNECTED
        if (tunnelState != connected) {
            val connectWith: String? = when {
                key.isNotEmpty() -> key
                // 隧道可选：非 tailnet 地址不填 key 时不触碰隧道。
                !tailnet -> return probeStep(clean, probe, onStage)
                tunnelState == TunnelStateMachine.State.NOT_CONFIGURED ||
                    tunnelState == TunnelStateMachine.State.NEEDS_KEY -> return Result.MissingKey
                // 连接中或失败：凭已有节点状态重连。
                else -> null
            }
            onStage(Stage.TUNNEL)
            val after = connectTunnel(connectWith)
            // 重连时发现既无 key 也无节点状态（已被清除）：按缺 key 提示。
            if (after == TunnelStateMachine.State.NOT_CONFIGURED) return Result.MissingKey
            if (after != connected) return Result.TunnelFailed(after)
        }
        return probeStep(clean, probe, onStage)
    }

    /** 干净地址的主机是否为 tailnet 目标（解析失败视为否）。 */
    fun isTailnetUrl(cleanUrl: String): Boolean {
        val host = try {
            URI(cleanUrl).host
        } catch (_: Exception) {
            null
        } ?: return false
        return TailnetRouting.isTailnetTarget(host)
    }

    fun errorHint(cleanUrl: String): Hint =
        if (isTailnetUrl(cleanUrl)) Hint.CHECK_TAILSCALE else Hint.CHECK_ADDRESS

    private fun probeStep(
        clean: String,
        probe: (String) -> DialProbe.ProbeResult,
        onStage: (Stage) -> Unit,
    ): Result {
        onStage(Stage.PROBE)
        return when (val r = probe(clean)) {
            DialProbe.ProbeResult.Reachable -> Result.Ok(clean)
            is DialProbe.ProbeResult.Unreachable -> Result.Unreachable(r.reason)
        }
    }
}
