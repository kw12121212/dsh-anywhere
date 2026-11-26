package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 电脑操控设置纯逻辑测试（dsbot-computer-settings）：
 * 覆盖真实脱敏夹具 state-full.json 的 state.computer（Linux dsh Web 宿主，关闭）、最小视图 {enabled:false}、
 * 各 provider 状态与宿主支持判定、级别缺省与未知值、Bot 行合并与回落、应用授权、system-status 两种权限形态、
 * 写端点返回值解码与全部请求体字面值。
 */
class ComputerSettingsTest {
    private fun obj(json: String) = Json.parse(json) as Json.Value.Object

    private fun state(computer: String, bots: String = """[{"id":"b1","name":"Alice"},{"id":"b2","name":"Bob"}]""") =
        obj("""{"bots":$bots,"computer":$computer}""")

    @Test
    fun decodesRealStateFixtureOnDshWebHost() {
        val text = javaClass.getResource("/dsbot/state-full.json")!!.readText()
        val root = (Json.parse(text) as Json.Value.Object).members["value"] as Json.Value.Object
        assertTrue(ComputerSettings.has(root))
        val view = ComputerSettings.view(root)
        assertNotNull(view)
        assertFalse(view.enabled)
        assertFalse(view.minimal)
        assertFalse(view.macos)
        assertEquals(ComputerSettings.ProviderState.OFF, view.provider.state)
        // 关闭状态下无从判断宿主类型：照常显示开关，不判为不支持
        assertFalse(view.hostUnsupported)
        assertFalse(view.showPermissions)
        val rows = ComputerSettings.botRows(root, view)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.level == ComputerSettings.Level.BACKGROUND && it.apps.isEmpty() })
        // 名册中每个 Bot 都有级别（fixture 的 computer.bots 覆盖全部 Bot）
        assertEquals(rows.size, rows.count { view.bots.containsKey(it.id) })
    }

    @Test
    fun missingComputerFieldHidesEntry() {
        val root = obj("""{"bots":[]}""")
        assertFalse(ComputerSettings.has(root))
        assertNull(ComputerSettings.view(root))
        assertFalse(ComputerSettings.has(null))
        assertFalse(ComputerSettings.has(obj("""{"computer":true}""")))
    }

    @Test
    fun minimalViewIsUnsupportedHost() {
        val view = ComputerSettings.view(state("""{"enabled":false}"""))!!
        assertTrue(view.minimal)
        assertTrue(view.hostUnsupported)
        assertEquals(ComputerSettings.ProviderState.OFF, view.provider.state)
        assertTrue(view.bots.isEmpty())
    }

    @Test
    fun providerAbsentDefaultsLikeWebClient() {
        // 有 bots 无 provider：开启时按加载中、关闭时按 off（client.js 回落）
        val on = ComputerSettings.decode(obj("""{"enabled":true,"bots":{}}"""))
        assertFalse(on.minimal)
        assertEquals(ComputerSettings.ProviderState.LOADING, on.provider.state)
        assertTrue(on.provider.works)
        // 加载中尚未就绪：级别区照样提示 Bot 暂无电脑工具
        assertFalse(on.provider.ready)
        val off = ComputerSettings.decode(obj("""{"enabled":false,"bots":{}}"""))
        assertEquals(ComputerSettings.ProviderState.OFF, off.provider.state)
        assertFalse(off.hostUnsupported)
    }

    @Test
    fun providerStatesDriveRetryWorksAndSupport() {
        fun v(enabled: Boolean, provider: String) = ComputerSettings.decode(obj("""{"enabled":$enabled,"provider":$provider,"macos":false,"bots":{}}"""))
        val ready = v(true, """{"state":"ready","name":"dsh-cua"}""")
        assertTrue(ready.provider.works)
        assertTrue(ready.provider.ready)
        assertFalse(ready.provider.retryable)
        assertEquals("dsh-cua", ready.provider.name)
        assertFalse(ready.hostUnsupported)

        val error = v(true, """{"state":"error","message":"boom"}""")
        assertFalse(error.provider.works)
        assertTrue(error.provider.retryable)
        assertEquals("boom", error.provider.message)
        // 普通加载错误属可重试的组件错误，不判为宿主不支持
        assertFalse(error.provider.cannotLoad)
        assertFalse(error.hostUnsupported)

        // 宿主无包加载器（上游 bot/no-loader 落为 error + 固定文案）→ 此宿主不支持，仍可重试
        val noLoader = v(true, """{"state":"error","message":"This DSH cannot load packages."}""")
        assertTrue(noLoader.provider.cannotLoad)
        assertTrue(noLoader.hostUnsupported)
        assertTrue(noLoader.provider.retryable)
        // 只认整串相等：带前后缀的近似文案仍按普通错误
        assertFalse(v(true, """{"state":"error","message":"Error: This DSH cannot load packages. (retry)"}""").hostUnsupported)
        // 关闭状态下不判不支持
        assertFalse(v(false, """{"state":"error","message":"This DSH cannot load packages."}""").hostUnsupported)

        val occupied = v(true, """{"state":"occupied","name":"other"}""")
        assertTrue(occupied.provider.retryable)
        assertFalse(occupied.hostUnsupported)

        val missing = v(true, """{"state":"missing","message":"@deepseek-ai/dsh-computer-use is not installed in this DSH.","install":{"packages":["a","b"],"command":"pnpm add a b"}}""")
        assertTrue(missing.provider.retryable)
        assertTrue(missing.hostUnsupported)
        assertEquals("pnpm add a b", missing.provider.installCommand)

        val unsupported = v(true, """{"state":"unsupported"}""")
        assertTrue(unsupported.hostUnsupported)
        assertFalse(unsupported.provider.retryable)
        // 关闭后 provider 状态回到 off（setEnabled(false)），但若残留 unsupported 仍判不支持
        assertTrue(v(false, """{"state":"unsupported"}""").hostUnsupported)
        // 关闭状态下 missing 不判不支持（只在开启后得出）
        assertFalse(v(false, """{"state":"missing"}""").hostUnsupported)

        val unknown = v(true, """{"state":"weird"}""")
        assertEquals(ComputerSettings.ProviderState.ERROR, unknown.provider.state)
        // 空白字段视为缺失
        val blank = v(true, """{"state":"error","message":"  ","name":"","install":{"command":""}}""")
        assertNull(blank.provider.message)
        assertNull(blank.provider.name)
        assertNull(blank.provider.installCommand)
    }

    @Test
    fun permissionsShownOnlyOnReadyMacos() {
        fun v(enabled: Boolean, state: String, macos: Boolean) =
            ComputerSettings.decode(obj("""{"enabled":$enabled,"provider":{"state":"$state"},"macos":$macos,"bots":{}}"""))
        assertTrue(v(true, "ready", true).showPermissions)
        assertFalse(v(true, "ready", false).showPermissions)
        assertFalse(v(true, "loading", true).showPermissions)
        assertFalse(v(false, "ready", true).showPermissions)
    }

    @Test
    fun levelsDefaultToBackgroundAndUnknownFallsBack() {
        assertEquals(listOf("off", "view", "background", "foreground"), ComputerSettings.Level.entries.map { it.wire })
        assertEquals(ComputerSettings.Level.BACKGROUND, ComputerSettings.Level.of(null))
        assertEquals(ComputerSettings.Level.BACKGROUND, ComputerSettings.Level.of("admin"))
        assertEquals(ComputerSettings.Level.FOREGROUND, ComputerSettings.Level.of("foreground"))
        assertEquals(ComputerSettings.Level.OFF, ComputerSettings.Level.of("off"))
    }

    @Test
    fun botRowsMergeViewWithRosterAndFallBackToOwnField() {
        val root = state(
            """{"enabled":true,"provider":{"state":"ready"},"macos":true,"bots":{
              "b1":{"level":"view","apps":{"com.apple.Safari":{"name":"Safari","id":"x","grantedAt":1},"notes":{"grantedAt":2}," ":{"name":"blank"}}},
              "ghost":{"level":"off","apps":{}},
              "bad":"nope"}}""",
            bots = """[{"id":"b1","name":"Alice"},{"id":"b2","name":"","computer":{"level":"foreground","apps":{"term":{"name":"Terminal"}}}},{"name":"no-id"},{"id":"b3"}]""",
        )
        val view = ComputerSettings.view(root)!!
        // 非对象条目跳过
        assertEquals(setOf("b1", "ghost"), view.bots.keys)
        val rows = ComputerSettings.botRows(root, view)
        assertEquals(listOf("b1", "b2", "b3"), rows.map { it.id })
        val alice = rows[0]
        assertEquals("Alice", alice.name)
        assertEquals(ComputerSettings.Level.VIEW, alice.level)
        // 保持服务端顺序；无名称显示 appKey；空白 key 跳过
        assertEquals(listOf("com.apple.Safari" to "Safari", "notes" to "notes"), alice.apps.map { it.key to it.name })
        // computer.bots 缺失时回落 Bot 自身 computer 字段；名称为空时显示 id
        val b2 = rows[1]
        assertEquals("b2", b2.name)
        assertEquals(ComputerSettings.Level.FOREGROUND, b2.level)
        assertEquals(listOf("term"), b2.apps.map { it.key })
        val b3 = rows[2]
        assertEquals(ComputerSettings.Level.BACKGROUND, b3.level)
        assertTrue(b3.apps.isEmpty())

        assertEquals(alice, ComputerSettings.botRow(root, "b1"))
        assertNull(ComputerSettings.botRow(root, "ghost"))
        assertTrue(ComputerSettings.botRows(null, null).isEmpty())
    }

    @Test
    fun malformedComputerFieldsDegrade() {
        val view = ComputerSettings.decode(obj("""{"enabled":"yes","provider":"ready","macos":1,"bots":[]}"""))
        assertFalse(view.enabled)
        assertFalse(view.macos)
        // provider 非对象且 bots 非对象 → 视为最小视图
        assertTrue(view.minimal)
        assertTrue(view.bots.isEmpty())
    }

    @Test
    fun decodesWriteResults() {
        assertNull(ComputerSettings.decodeValue(Json.Value.BoolValue(true)))
        assertNull(ComputerSettings.decodeValue(null))
        assertNull(ComputerSettings.decodeValue(obj("""{"other":1}""")))
        val v = ComputerSettings.decodeValue(obj("""{"enabled":true,"provider":{"state":"loading"},"macos":false,"bots":{}}""" ))
        assertNotNull(v)
        assertTrue(v.enabled)
        assertEquals(ComputerSettings.ProviderState.LOADING, v.provider.state)
    }

    @Test
    fun permissionsDecodeBothShapesAndNull() {
        assertNull(ComputerSettings.permissions(null))
        assertNull(ComputerSettings.permissions(Json.Value.NullValue))
        assertNull(ComputerSettings.permissions(Json.Value.BoolValue(false)))
        val objShape = ComputerSettings.permissions(obj("""{"accessibility":{"state":"granted"},"screenRecording":{"state":"denied"}}"""))!!
        assertEquals(listOf(ComputerSettings.Permission("accessibility", true), ComputerSettings.Permission("screenRecording", false)), objShape)
        val boolShape = ComputerSettings.permissions(obj("""{"accessibility":false,"screenRecording":true}"""))!!
        assertEquals(listOf(false, true), boolShape.map { it.granted })
        val stringShape = ComputerSettings.permissions(obj("""{"accessibility":"granted","screenRecording":{"state":true}}"""))!!
        assertEquals(listOf(true, true), stringShape.map { it.granted })
        // 缺项视为未允许
        assertEquals(listOf(false, false), ComputerSettings.permissions(obj("{}"))!!.map { it.granted })
    }

    @Test
    fun readOnlyReason() {
        assertEquals("Held by another host", ComputerSettings.readOnlyReason(obj("""{"readOnly":"Held by another host"}""")))
        assertNull(ComputerSettings.readOnlyReason(obj("""{"readOnly":null}""")))
        assertNull(ComputerSettings.readOnlyReason(obj("""{"readOnly":" "}""")))
        assertNull(ComputerSettings.readOnlyReason(null))
    }

    @Test
    fun requestBodies() {
        val r = ComputerSettings.Requests
        assertEquals("""{"enabled":true}""", r.setEnabled(true))
        assertEquals("""{"enabled":false}""", r.setEnabled(false))
        assertEquals("""{"botId":"b\"1","level":"view"}""", r.setLevel("b\"1", ComputerSettings.Level.VIEW))
        assertEquals("""{"botId":"b1","appKey":"com.apple.Safari"}""", r.revokeApp("b1", "com.apple.Safari"))
        assertEquals("{}", r.providerRetry())
        assertEquals("{}", r.systemStatus())
        assertEquals("""{"permission":"screenRecording"}""", r.permission("screenRecording"))
        assertEquals("""{"permission":"accessibility"}""", r.permission("bogus"))
        assertEquals("computer-set-enabled", ComputerSettings.SET_ENABLED)
        assertEquals("computer-system-status", ComputerSettings.SYSTEM_STATUS)
    }
}
