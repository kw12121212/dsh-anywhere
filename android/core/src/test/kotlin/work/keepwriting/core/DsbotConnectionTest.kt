package work.keepwriting.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DsbotConnection 状态机测试（dsbot-native-shell）：状态迁移与动作输出按
 * android-dsbot connection-status-bar 的场景驱动。
 */
class DsbotConnectionTest {
    private class Recorder : DsbotConnection.Listener, FeedSync.Listener {
        val statuses = mutableListOf<DsbotConnection.Status>()
        val actions = mutableListOf<String>()
        val fetches = mutableListOf<Pair<String?, String?>>()
        override fun onStatus(status: DsbotConnection.Status) {
            statuses.add(status)
        }

        override fun probeAndGate() {
            actions.add("probe")
        }

        override fun connectFeed() {
            actions.add("feed")
        }

        override fun fetchState(localRevision: String?, liveKey: String?) {
            fetches.add(localRevision to liveKey)
        }
    }

    private fun machine(r: Recorder): DsbotConnection = DsbotConnection(r, FeedSync(r))

    @Test
    fun `连接成功到在线与断线重连`() {
        val r = Recorder()
        val m = machine(r)
        m.beginConnect()
        assertEquals(listOf("probe"), r.actions)
        assertEquals(DsbotConnection.Status.CONNECTING, m.status)
        m.onGate(DsbotConnection.NativeGate.OK)
        assertEquals("feed", r.actions[1])
        m.onFeedConnected()
        // 订阅在役但 ONLINE 由首帧确认（不闪现）
        assertEquals(DsbotConnection.Status.CONNECTING, m.status)
        m.onFeedEvent("hello", """{"revision":1694}""")
        assertEquals(DsbotConnection.Status.ONLINE, m.status)
        assertEquals(listOf<Pair<String?, String?>>(null to null), r.fetches)
        m.onStateFetched(checkNotNull(DsbotModels.decodeState(stateValue())))
        assertEquals(DsbotConnection.Status.ONLINE, m.status)
        // 断线 → 重连（前台），退避到点重订阅
        val delay = m.onFeedDisconnected()
        assertEquals(DsbotConnection.Status.RECONNECTING, m.status)
        assertEquals(1_000L, delay)
        m.onBackoffElapsed()
        assertEquals(listOf("probe", "feed", "feed"), r.actions)
        m.onFeedConnected()
        // 重连后 hello 与本地一致：零负载直接回在线
        m.onFeedEvent("hello", """{"revision":1694}""")
        assertEquals(1, r.fetches.size)
        assertEquals(DsbotConnection.Status.ONLINE, m.status)
    }

    @Test
    fun `不可达与 gate 分流`() {
        val r = Recorder()
        val m = machine(r)
        m.beginConnect()
        m.onUnreachable()
        assertEquals(DsbotConnection.Status.OFFLINE, m.status)
        // 错误页重试 → 重新探测
        m.beginConnect()
        m.onGate(DsbotConnection.NativeGate.AUTH_EXPIRED)
        assertEquals(DsbotConnection.Status.AUTH_EXPIRED, m.status)
        m.onSessionReestablished()
        m.onGate(DsbotConnection.NativeGate.FORBIDDEN_HOST)
        assertEquals(DsbotConnection.Status.FORBIDDEN_HOST, m.status)
        // 分流后不再订阅 feed
        assertEquals(listOf("probe", "probe", "probe"), r.actions)
    }

    @Test
    fun `后台断开静默、回前台重走连接`() {
        val r = Recorder()
        val m = machine(r)
        m.beginConnect()
        m.onGate(DsbotConnection.NativeGate.OK)
        m.onFeedConnected()
        m.onFeedEvent("hello", """{"revision":1694}""")
        assertEquals(DsbotConnection.Status.ONLINE, m.status)
        m.onBackground()
        assertEquals(DsbotConnection.Status.CONNECTING, m.status)
        // 后台断线不进入重连态
        m.onFeedDisconnected()
        assertEquals(DsbotConnection.Status.CONNECTING, m.status)
        // 回前台重新探测与 gate（会话可能已失效），仍 OK 则重订阅
        m.onForeground()
        assertEquals(listOf("probe", "feed", "probe"), r.actions)
        m.onGate(DsbotConnection.NativeGate.OK)
        m.onFeedConnected()
        m.onFeedEvent("hello", """{"revision":1694}""")
        assertEquals(DsbotConnection.Status.ONLINE, m.status)
    }

    private fun deltaValue(): Json.Value {
        val res = DsbotConnectionTest::class.java.getResource("/dsbot/state-unchanged-live.json")
        val root = checkNotNull(Json.parse(checkNotNull(res).readText(Charsets.UTF_8)))
        return (root as Json.Value.Object).members.getValue("value")
    }

    @Test
    fun `OFFLINE 重试恢复环回到在线`() {
        val r = Recorder()
        val m = machine(r)
        m.beginConnect()
        m.onUnreachable()
        assertEquals(DsbotConnection.Status.OFFLINE, m.status)
        m.beginConnect() // 错误页重试：reset 后 hello 必触发全量
        m.onGate(DsbotConnection.NativeGate.OK)
        m.onFeedEvent("hello", """{"revision":1694}""")
        assertEquals(listOf<Pair<String?, String?>>(null to null), r.fetches)
        m.onStateFetched(checkNotNull(DsbotModels.decodeStateDelta(deltaValue())))
        assertEquals(DsbotConnection.Status.ONLINE, m.status)
    }

    @Test
    fun `无会话意愿时断开与后台不改状态`() {
        val r = Recorder()
        val m = machine(r)
        m.beginConnect()
        m.onGate(DsbotConnection.NativeGate.AUTH_EXPIRED)
        val before = m.status
        m.onFeedDisconnected()
        m.onBackground()
        assertEquals(before, m.status)
    }

    @Test
    fun `前台幂等不重复探测`() {
        val r = Recorder()
        val m = machine(r)
        m.onForeground() // 已在前台：无动作
        assertEquals(0, r.actions.size)
    }

    @Test
    fun `增量应答回填不丢 liveKey`() {
        val r = Recorder()
        val m = machine(r)
        m.onFeedEvent("hello", """{"revision":1694}""")
        // 全量回填 revision，增量回填 liveKey（判别修复后增量夹具不再是全量形态）
        m.onStateFetched(checkNotNull(DsbotModels.decodeState(stateValue())))
        m.onStateFetched(checkNotNull(DsbotModels.decodeStateDelta(deltaValue())))
        assertEquals("1694", m.localRevisionForTest())
        assertTrue(m.liveKeyForTest() != null)
    }


    private fun stateValue(): Json.Value {
        val res = DsbotConnectionTest::class.java.getResource("/dsbot/state-full.json")
        val root = checkNotNull(Json.parse(checkNotNull(res).readText(Charsets.UTF_8)))
        return (root as Json.Value.Object).members.getValue("value")
    }
}
