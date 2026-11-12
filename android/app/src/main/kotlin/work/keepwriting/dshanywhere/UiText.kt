package work.keepwriting.dshanywhere

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import work.keepwriting.core.ProbeFailure
import work.keepwriting.core.TunnelStateMachine

/**
 * 共享 UI 文案映射（dsbot-native-shell 从原 MainWebScreen 迁出，设置页/首次设置页
 * 与原生外壳共用）：隧道状态与探测失败原因的本地化。
 */
@Composable
internal fun tunnelStateText(state: TunnelStateMachine.State): String = when (state) {
    TunnelStateMachine.State.NOT_CONFIGURED -> stringResource(R.string.tunnel_state_not_configured)
    TunnelStateMachine.State.CONNECTING -> stringResource(R.string.tunnel_state_connecting)
    TunnelStateMachine.State.CONNECTED -> stringResource(R.string.tunnel_state_connected)
    TunnelStateMachine.State.NEEDS_KEY -> stringResource(R.string.tunnel_state_needs_key)
    TunnelStateMachine.State.FAILED -> stringResource(R.string.tunnel_state_failed)
}

internal fun reasonMessage(reason: ProbeFailure.Reason): Int = when (reason) {
    ProbeFailure.Reason.DNS -> R.string.error_reason_dns
    ProbeFailure.Reason.REFUSED -> R.string.error_reason_refused
    ProbeFailure.Reason.TIMEOUT -> R.string.error_reason_timeout
    ProbeFailure.Reason.TLS -> R.string.error_reason_tls
    else -> R.string.error_reason_other
}
