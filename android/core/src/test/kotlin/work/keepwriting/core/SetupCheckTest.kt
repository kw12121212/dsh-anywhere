package work.keepwriting.core

import work.keepwriting.core.DialProbe.ProbeResult
import work.keepwriting.core.SetupCheck.Result
import work.keepwriting.core.SetupCheck.Stage
import work.keepwriting.core.TunnelStateMachine.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 首次设置/设置页验证编排：隧道步骤判定、失败归属与探测输入（假隧道与假探测）。 */
class SetupCheckTest {
    /** 记录调用的假依赖：connectTunnel 返回预设状态，probe 返回预设结果。 */
    private class Fakes(
        val connectResult: State = State.CONNECTED,
        val probeResult: ProbeResult = ProbeResult.Reachable,
    ) {
        val connectCalls = mutableListOf<String?>()
        val probeCalls = mutableListOf<String>()
        val stages = mutableListOf<Stage>()

        fun run(rawUrl: String, key: String, state: State): Result = SetupCheck.verify(
            rawUrl, key, state,
            connectTunnel = { k -> connectCalls += k; connectResult },
            probe = { u -> probeCalls += u; probeResult },
            onStage = { stages += it },
        )
    }

    @Test
    fun nonTailnetWithoutKeyNeverTouchesTunnel() {
        for (state in State.entries) {
            val f = Fakes()
            val r = f.run("http://10.0.2.2:13080/", "", state)
            assertEquals(Result.Ok("http://10.0.2.2:13080/"), r, "state=$state")
            assertTrue(f.connectCalls.isEmpty(), "非 tailnet 留空 key 不得连接隧道（state=$state）")
            assertEquals(listOf(Stage.PROBE), f.stages)
        }
    }

    @Test
    fun tailnetWithoutKeyNeedsKeyWhenUnconfiguredOrRejected() {
        for (state in listOf(State.NOT_CONFIGURED, State.NEEDS_KEY)) {
            val f = Fakes()
            assertEquals(Result.MissingKey, f.run("http://gamepc:13080/", "  ", state), "state=$state")
            assertTrue(f.connectCalls.isEmpty())
            assertTrue(f.probeCalls.isEmpty(), "缺 key 时不得探测地址")
        }
    }

    @Test
    fun keyConnectsBeforeProbe() {
        val f = Fakes()
        val r = f.run("http://gamepc:13080/?token=abc123", " tskey-auth-x ", State.NOT_CONFIGURED)
        assertEquals(Result.Ok("http://gamepc:13080/"), r)
        assertEquals(listOf<String?>("tskey-auth-x"), f.connectCalls)
        assertEquals(listOf(Stage.TUNNEL, Stage.PROBE), f.stages)
    }

    @Test
    fun keyFailureStopsBeforeProbeEvenForNonTailnetAddress() {
        for (result in listOf(State.NEEDS_KEY, State.FAILED)) {
            for (url in listOf("http://gamepc:13080/", "https://dsh.example.test/")) {
                val f = Fakes(connectResult = result)
                assertEquals(Result.TunnelFailed(result), f.run(url, "bad-key", State.NOT_CONFIGURED), "$url $result")
                assertTrue(f.probeCalls.isEmpty(), "隧道失败不得探测地址（$url）")
            }
        }
    }

    @Test
    fun tailnetReconnectsFromPersistedStateWithoutKey() {
        for (state in listOf(State.CONNECTING, State.FAILED)) {
            val ok = Fakes()
            assertEquals(Result.Ok("http://100.101.102.103:13080/"), ok.run("http://100.101.102.103:13080/", "", state))
            assertEquals(listOf<String?>(null), ok.connectCalls, "无 key 重连传 null（state=$state）")

            val still = Fakes(connectResult = State.FAILED)
            assertEquals(Result.TunnelFailed(State.FAILED), still.run("http://gamepc:13080/", "", state))
            assertTrue(still.probeCalls.isEmpty())
        }
    }

    @Test
    fun keyTakesPriorityOverReconnectInEveryUnconnectedState() {
        for (state in listOf(State.NOT_CONFIGURED, State.CONNECTING, State.NEEDS_KEY, State.FAILED)) {
            val f = Fakes()
            assertEquals(Result.Ok("http://gamepc:13080/"), f.run("http://gamepc:13080/", "tskey-new", state), "state=$state")
            assertEquals(listOf<String?>("tskey-new"), f.connectCalls, "填了 key 必须以 key 连接而不是重连（state=$state）")
        }
    }

    @Test
    fun reconnectEndingUnconfiguredIsMissingKey() {
        val f = Fakes(connectResult = State.NOT_CONFIGURED)
        assertEquals(Result.MissingKey, f.run("http://gamepc:13080/", "", State.FAILED))
        assertTrue(f.probeCalls.isEmpty())
    }

    @Test
    fun connectedTunnelSkipsConnectAndIgnoresKey() {
        val f = Fakes()
        assertEquals(Result.Ok("https://gamepc.tail1.ts.net:13080/"), f.run("https://gamepc.tail1.ts.net:13080/", "tskey", State.CONNECTED))
        assertTrue(f.connectCalls.isEmpty())
        assertEquals(listOf(Stage.PROBE), f.stages)
    }

    @Test
    fun unreachableReasonPassesThrough() {
        for (reason in ProbeFailure.Reason.entries) {
            val f = Fakes(probeResult = ProbeResult.Unreachable(reason))
            assertEquals(Result.Unreachable(reason), f.run("http://10.0.2.2:13999/", "", State.NOT_CONFIGURED))
        }
    }

    @Test
    fun probeSeesOnlyCleanUrl() {
        val f = Fakes()
        f.run("  http://127.0.0.1:13080/?token=abc123  ", "", State.NOT_CONFIGURED)
        assertEquals(listOf("http://127.0.0.1:13080/"), f.probeCalls)
        assertTrue(f.probeCalls.none { it.contains("abc123") })
    }

    @Test
    fun invalidUrlStopsBeforeAnySideEffect() {
        val cases = mapOf(
            "" to HarnessUrl.ErrorCategory.EMPTY,
            "ftp://dsh.example.test/" to HarnessUrl.ErrorCategory.UNSUPPORTED_SCHEME,
            "dsh.example.test" to HarnessUrl.ErrorCategory.NOT_ABSOLUTE,
        )
        for ((raw, category) in cases) {
            val f = Fakes()
            assertEquals(Result.InvalidUrl(category), f.run(raw, "tskey", State.NOT_CONFIGURED), raw)
            assertTrue(f.connectCalls.isEmpty() && f.probeCalls.isEmpty() && f.stages.isEmpty(), raw)
        }
    }

    @Test
    fun errorHintFollowsTailnetTarget() {
        assertEquals(SetupCheck.Hint.CHECK_TAILSCALE, SetupCheck.errorHint("http://gamepc:13080/"))
        assertEquals(SetupCheck.Hint.CHECK_TAILSCALE, SetupCheck.errorHint("https://gamepc.tail1.ts.net:13080/"))
        assertEquals(SetupCheck.Hint.CHECK_TAILSCALE, SetupCheck.errorHint("http://100.64.0.1/"))
        assertEquals(SetupCheck.Hint.CHECK_ADDRESS, SetupCheck.errorHint("http://10.0.2.2:13080/"))
        assertEquals(SetupCheck.Hint.CHECK_ADDRESS, SetupCheck.errorHint("https://dsh.example.test/"))
        assertEquals(SetupCheck.Hint.CHECK_ADDRESS, SetupCheck.errorHint("http://localhost:13080/"))
        assertEquals(SetupCheck.Hint.CHECK_ADDRESS, SetupCheck.errorHint("not a url"))
    }
}
