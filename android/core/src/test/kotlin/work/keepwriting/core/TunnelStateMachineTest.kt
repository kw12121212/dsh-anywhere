package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 隧道状态机判定的表驱动测试（随回归命令执行，不依赖 Go/AAR）。 */
class TunnelStateMachineTest {
    @Test
    fun decideStartTable() {
        // 无 key 无状态：未配置且不启动（绝不调 Up）。
        val none = TunnelStateMachine.decideStart(hasAuthKey = false, hasPersistedState = false)
        assertEquals(TunnelStateMachine.State.NOT_CONFIGURED, none.state)
        assertFalse(none.shouldStart, "无 key 无状态不得触碰 tsnet（Up 会永久阻塞）")
        // 有 key（无论状态）：注册连接。
        for (persisted in listOf(false, true)) {
            val d = TunnelStateMachine.decideStart(hasAuthKey = true, hasPersistedState = persisted)
            assertEquals(TunnelStateMachine.State.CONNECTING, d.state, "hasKey=true persisted=$persisted")
            assertTrue(d.shouldStart)
        }
        // 仅凭持久状态：免 key 重连。
        val reconnect = TunnelStateMachine.decideStart(hasAuthKey = false, hasPersistedState = true)
        assertEquals(TunnelStateMachine.State.CONNECTING, reconnect.state)
        assertTrue(reconnect.shouldStart)
    }

    @Test
    fun classifyUpFailureTable() {
        val cases = listOf(
            // 需要 key：登录类、key 失效类、节点被删类、拒绝类。
            "tsnet: Up: needs-login" to TunnelStateMachine.State.NEEDS_KEY,
            "tsnet.Up: backend: invalid key: unable to validate API key" to TunnelStateMachine.State.NEEDS_KEY,
            "invalid auth key" to TunnelStateMachine.State.NEEDS_KEY,
            "auth key has expired" to TunnelStateMachine.State.NEEDS_KEY,
            "auth key already used" to TunnelStateMachine.State.NEEDS_KEY,
            "node no longer exists in tailnet" to TunnelStateMachine.State.NEEDS_KEY,
            "request unauthorized" to TunnelStateMachine.State.NEEDS_KEY,
            "access denied by ACL" to TunnelStateMachine.State.NEEDS_KEY,
            // 失败：网络与超时等其他错误。
            "dial tcp: i/o timeout" to TunnelStateMachine.State.FAILED,
            "context deadline exceeded" to TunnelStateMachine.State.FAILED,
            "connection refused" to TunnelStateMachine.State.FAILED,
            "" to TunnelStateMachine.State.FAILED,
        )
        for ((idx, case) in cases.withIndex()) {
            assertEquals(case.second, TunnelStateMachine.classifyUpFailure(case.first), "用例 #$idx: ${case.first}")
        }
    }

    @Test
    fun clearedGoesToNotConfigured() {
        assertEquals(TunnelStateMachine.State.NOT_CONFIGURED, TunnelStateMachine.cleared())
    }

    @Test
    fun needsKeyFromStatusJsonTable() {
        // Up 超时后的 backend 状态补分类：NeedsLogin/NeedsMachineAuth → 需要 key。
        assertTrue(TunnelStateMachine.needsKeyFromStatusJson("{\"BackendState\":\"NeedsLogin\",\"Version\":\"1.94.1\"}"))
        assertTrue(TunnelStateMachine.needsKeyFromStatusJson("{\"BackendState\":\"NeedsMachineAuth\"}"))
        assertFalse(TunnelStateMachine.needsKeyFromStatusJson("{\"BackendState\":\"Running\"}"))
        assertFalse(TunnelStateMachine.needsKeyFromStatusJson("{\"BackendState\":\"Starting\"}"))
        assertFalse(TunnelStateMachine.needsKeyFromStatusJson("{}"))
    }
}
