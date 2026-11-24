package work.keepwriting.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64

class BotBrowserTest {
    @BeforeEach
    fun resetLang() {
        I18n.source = null
        I18n.lang = ChatBars.Lang.ZH
    }

    @Test
    fun `state browser 布尔与 live browserHolds 四种持有状态派生`() {
        val state = Json.parse(
            """
            {
              "browser": true,
              "browserHolds": {
                "bot-handoff": { "by": "bot", "reason": "login", "note": "请登录供应商后台", "callId": "call-1", "sessionId": "s-1" },
                "bot-user": { "by": "user", "reason": "takeover", "note": "The user is controlling this browser." },
                "bot-stopped": { "by": "user", "reason": "stopped", "note": "The user is controlling this browser." }
              }
            }
            """.trimIndent(),
        ) as Json.Value.Object

        assertEquals(true, BotBrowser.hostHasDesktopChannel(state))

        val using = BotBrowser.holdOf(state, "bot-free")
        assertEquals(BotBrowser.HoldState.BOT_USING, using.state)
        assertNull(using.note)

        val handoff = BotBrowser.holdOf(state, "bot-handoff")
        assertEquals(BotBrowser.HoldState.WAITING_HANDOFF, handoff.state)
        assertEquals("请登录供应商后台", handoff.note)
        assertEquals("call-1", handoff.callId)
        assertEquals("s-1", handoff.sessionId)

        val user = BotBrowser.holdOf(state, "bot-user")
        assertEquals(BotBrowser.HoldState.USER_TAKEN_OVER, user.state)

        val stopped = BotBrowser.holdOf(state, "bot-stopped")
        assertEquals(BotBrowser.HoldState.STOPPED, stopped.state)
    }

    @Test
    fun `state browser 为 false 或运行期收到 browser-off 时降级接管与交还，缺键返回 null`() {
        assertNull(BotBrowser.hostHasDesktopChannel(null))
        val noKeyState = Json.parse("""{"browserHolds":{}}""") as Json.Value.Object
        assertNull(BotBrowser.hostHasDesktopChannel(noKeyState))

        val webState = Json.parse("""{"browser":false,"browserHolds":{}}""") as Json.Value.Object
        assertEquals(false, BotBrowser.hostHasDesktopChannel(webState))
        val hold = BotBrowser.holdOf(webState, "bot-1")

        // dsh Web 启用插件看到画面或未打开浏览器时：接管/交还置灰并提示，停止仍可用
        val cReady = BotBrowser.controlsOf(hold, hostDesktopChannel = false, takeoverUnsupported = false, frameOutcome = BotBrowser.FrameOutcome.NoOpenBrowser)
        assertFalse(cReady.canTakeover)
        assertFalse(cReady.canGiveBack)
        assertTrue(cReady.canStop)
        assertTrue(cReady.desktopOnlyHint)

        // 双重守卫第二分支：state.browser 缺失（null）但运行期 takeover/give-back 曾回 bot/browser-off
        val cFallback = BotBrowser.controlsOf(hold, hostDesktopChannel = null, takeoverUnsupported = true, frameOutcome = BotBrowser.FrameOutcome.NoOpenBrowser)
        assertFalse(cFallback.canTakeover)
        assertFalse(cFallback.canGiveBack)
        assertTrue(cFallback.canStop)
        assertTrue(cFallback.desktopOnlyHint)

        // 未启用插件（HostOff）时全部控制禁用且不重复显示桌面提示
        val cOff = BotBrowser.controlsOf(hold, hostDesktopChannel = false, takeoverUnsupported = false, frameOutcome = BotBrowser.FrameOutcome.HostOff)
        assertFalse(cOff.canTakeover)
        assertFalse(cOff.canGiveBack)
        assertFalse(cOff.canStop)
        assertFalse(cOff.desktopOnlyHint)
    }

    @Test
    fun `桌面宿主下控制按钮随持有状态切换`() {
        val cUsing = BotBrowser.controlsOf(
            BotBrowser.HoldInfo(BotBrowser.HoldState.BOT_USING, null, null, null, null, null),
            hostDesktopChannel = true, takeoverUnsupported = false, frameOutcome = null,
        )
        assertTrue(cUsing.canTakeover)
        assertFalse(cUsing.canGiveBack)
        assertTrue(cUsing.canStop)
        assertFalse(cUsing.desktopOnlyHint)

        val cHandoff = BotBrowser.controlsOf(
            BotBrowser.HoldInfo(BotBrowser.HoldState.WAITING_HANDOFF, "bot", "login", "登录", "c1", "s1"),
            hostDesktopChannel = true, takeoverUnsupported = false, frameOutcome = null,
        )
        assertTrue(cHandoff.canTakeover)
        assertTrue(cHandoff.canGiveBack)
        assertTrue(cHandoff.canStop)

        val cTaken = BotBrowser.controlsOf(
            BotBrowser.HoldInfo(BotBrowser.HoldState.USER_TAKEN_OVER, "user", "takeover", null, null, null),
            hostDesktopChannel = true, takeoverUnsupported = false, frameOutcome = null,
        )
        assertFalse(cTaken.canTakeover)
        assertTrue(cTaken.canGiveBack)
        assertTrue(cTaken.canStop)

        val cStopped = BotBrowser.controlsOf(
            BotBrowser.HoldInfo(BotBrowser.HoldState.STOPPED, "user", "stopped", null, null, null),
            hostDesktopChannel = true, takeoverUnsupported = false, frameOutcome = null,
        )
        assertFalse(cStopped.canTakeover)
        assertTrue(cStopped.canGiveBack)
        assertTrue(cStopped.canStop)
    }

    @Test
    fun `browser-frame 应答解码与错误码降级映射且 toString 不含敏感页面信息`() {
        val rawBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02)
        val b64 = Base64.getEncoder().encodeToString(rawBytes)
        val okVal = Json.parse("""{"mime":"image/jpeg","data":"$b64","url":"https://bank.example/secret?sid=xyz","title":"Private Dashboard"}""")!!
        val ready = BotBrowser.decodeFrameResult(DsbotApi.Result.Ok(okVal)) as BotBrowser.FrameOutcome.Ready
        assertEquals("image/jpeg", ready.frame.mime)
        assertArrayEquals(rawBytes, ready.frame.bytes)
        assertEquals("https://bank.example/secret?sid=xyz", ready.frame.url)
        assertEquals("Private Dashboard", ready.frame.title)
        // toString 不得泄露 URL、标题或 base64
        val str = ready.frame.toString()
        assertFalse(str.contains("bank.example"))
        assertFalse(str.contains("Private Dashboard"))
        assertFalse(str.contains(b64))

        assertEquals(
            BotBrowser.FrameOutcome.HostOff,
            BotBrowser.decodeFrameResult(DsbotApi.Result.BotError("bot/browser-off", "Bot browsers are off on this host")),
        )
        assertEquals(
            BotBrowser.FrameOutcome.NoOpenBrowser,
            BotBrowser.decodeFrameResult(DsbotApi.Result.BotError("bot/not-found", "This Bot has no open browser")),
        )
        val internalErr = BotBrowser.decodeFrameResult(DsbotApi.Result.BotError("bot/internal", "secret stack trace"))
        assertTrue(internalErr is BotBrowser.FrameOutcome.Error)
        assertFalse((internalErr as BotBrowser.FrameOutcome.Error).message.contains("stack trace"))
        assertTrue(BotBrowser.decodeFrameResult(DsbotApi.Result.Failure(null, DsbotApi.Reason.NETWORK)) is BotBrowser.FrameOutcome.Error)
        // 畸形 base64、空数据或超出 MAX_FRAME_BYTES 上限不崩溃且被拒绝
        val badB64 = Json.parse("""{"mime":"image/jpeg","data":"!!!not-base64!!!","url":"https://a","title":"A"}""")!!
        assertTrue(BotBrowser.decodeFrameResult(DsbotApi.Result.Ok(badB64)) is BotBrowser.FrameOutcome.Error)
        val emptyData = Json.parse("""{"mime":"image/jpeg","data":""}""")!!
        assertTrue(BotBrowser.decodeFrameResult(DsbotApi.Result.Ok(emptyData)) is BotBrowser.FrameOutcome.Error)
        val hugeData = Json.Value.Object(mapOf("mime" to Json.Value.StringValue("image/jpeg"), "data" to Json.Value.StringValue("A".repeat((BotBrowser.MAX_FRAME_BYTES / 3 + 4) * 4))))
        assertTrue(BotBrowser.decodeFrameResult(DsbotApi.Result.Ok(hugeData)) is BotBrowser.FrameOutcome.Error)
    }

    @Test
    fun `控制端点结果解码：takeover 与 give-back 遇 bot_browser-off 转桌面降级，stop 与其他错误正常归类`() {
        assertEquals(BotBrowser.ActionOutcome.Ok, BotBrowser.decodeControlResult("browser-stop", DsbotApi.Result.Ok(Json.Value.BoolValue(true))))
        assertEquals(
            BotBrowser.ActionOutcome.DesktopOnly,
            BotBrowser.decodeControlResult("browser-takeover", DsbotApi.Result.BotError("bot/browser-off", "Bot browsers are off on this host")),
        )
        assertEquals(
            BotBrowser.ActionOutcome.DesktopOnly,
            BotBrowser.decodeControlResult("browser-give-back", DsbotApi.Result.BotError("bot/browser-off", "Bot browsers are off on this host")),
        )
        val forbidden = BotBrowser.decodeControlResult("browser-give-back", DsbotApi.Result.BotError("bot/forbidden", "This browser is not held"))
        assertTrue(forbidden is BotBrowser.ActionOutcome.Error)
        assertEquals("This browser is not held", (forbidden as BotBrowser.ActionOutcome.Error).message)
    }

    @Test
    fun `请求体字面值：面板交还不带 outcome 与 callId`() {
        assertEquals("""{"botId":"bot-1"}""", BotBrowser.Requests.frame("bot-1"))
        assertEquals("""{"botId":"bot-1"}""", BotBrowser.Requests.takeover("bot-1"))
        assertEquals("""{"botId":"bot-1"}""", BotBrowser.Requests.giveBack("bot-1"))
        assertEquals("""{"botId":"bot-1"}""", BotBrowser.Requests.stop("bot-1"))
    }

    @Test
    fun `轮询调度器：前台可见才启动、单飞防重、离开或后台立即停止并丢弃过期结果`() {
        val poller = BotBrowser.PollController()
        poller.setTarget("bot-1")

        // 未激活（后台或不可见或离线）不发请求
        poller.setActive(visible = true, foreground = false, online = true)
        assertFalse(poller.shouldPoll())
        assertNull(poller.tryStart())

        poller.setActive(visible = false, foreground = true, online = true)
        assertNull(poller.tryStart())

        poller.setActive(visible = true, foreground = true, online = false)
        assertNull(poller.tryStart())

        // 前台可见且在线：启动第 1 次
        poller.setActive(visible = true, foreground = true, online = true)
        val t1 = poller.tryStart()
        assertNotNull(t1)
        assertTrue(poller.isInFlight)
        // 单飞：在途期间不再发第 2 次
        assertNull(poller.tryStart())

        // 进入后台：立即作废在途请求；旧回包被丢弃
        poller.setActive(visible = true, foreground = false, online = true)
        assertFalse(poller.isInFlight)
        assertNull(poller.onResponse(t1!!, BotBrowser.FrameOutcome.NoOpenBrowser))

        // 回到前台：可以再次发起，且之前旧票据依然被丢弃
        poller.setActive(visible = true, foreground = true, online = true)
        val t2 = poller.tryStart()
        assertNotNull(t2)
        assertNull(poller.onResponse(t1, BotBrowser.FrameOutcome.NoOpenBrowser))
        assertTrue(poller.isInFlight) // 旧票据不应清掉 t2 的在途标记
        assertEquals(BotBrowser.FrameOutcome.NoOpenBrowser, poller.onResponse(t2!!, BotBrowser.FrameOutcome.NoOpenBrowser))
        assertFalse(poller.isInFlight)
        // NoOpenBrowser 不停止自动轮询
        assertTrue(poller.shouldPoll())
    }

    @Test
    fun `轮询调度器：HostOff 停止自动轮询，切换 Bot 丢弃旧 Bot 延迟回包，手动重试恢复`() {
        val poller = BotBrowser.PollController()
        poller.setTarget("bot-1")
        poller.setActive(visible = true, foreground = true, online = true)

        val t1 = poller.tryStart()!!
        val res = poller.onResponse(t1, BotBrowser.FrameOutcome.HostOff)
        assertEquals(BotBrowser.FrameOutcome.HostOff, res)
        assertTrue(poller.isHostOff)
        assertFalse(poller.shouldPoll())
        assertNull(poller.tryStart())

        // 手动重试恢复轮询
        val tRetry = poller.retry()
        assertNotNull(tRetry)
        assertFalse(poller.isHostOff)

        // 切换 Bot 后旧 Bot 的在途回包丢弃
        poller.setTarget("bot-2")
        assertNull(poller.onResponse(tRetry!!, BotBrowser.FrameOutcome.NoOpenBrowser))
        val tBot2 = poller.tryStart()
        assertNotNull(tBot2)
        assertEquals("bot-2", tBot2!!.botId)
    }

    @Test
    fun `中英文界面文案随 I18n 切换`() {
        assertEquals("此宿主未启用 Bot 浏览器", BotBrowser.hostOffTitle())
        assertEquals("这个 Bot 还没有打开浏览器", BotBrowser.noOpenBrowserText())
        assertEquals("需在桌面应用中接管或交还", BotBrowser.desktopOnlyHintText())
        assertEquals("Bot 使用中", BotBrowser.holdLabel(BotBrowser.HoldState.BOT_USING))
        assertEquals("等待用户交接", BotBrowser.holdLabel(BotBrowser.HoldState.WAITING_HANDOFF))
        assertEquals("用户已接管", BotBrowser.holdLabel(BotBrowser.HoldState.USER_TAKEN_OVER))
        assertEquals("已停止", BotBrowser.holdLabel(BotBrowser.HoldState.STOPPED))

        I18n.lang = ChatBars.Lang.EN
        assertEquals("Bot browser is not enabled on this host", BotBrowser.hostOffTitle())
        assertEquals("This Bot has no open browser", BotBrowser.noOpenBrowserText())
        assertEquals("Take over and give back require the desktop app", BotBrowser.desktopOnlyHintText())
        assertEquals("Stopped", BotBrowser.holdLabel(BotBrowser.HoldState.STOPPED))
    }
}
