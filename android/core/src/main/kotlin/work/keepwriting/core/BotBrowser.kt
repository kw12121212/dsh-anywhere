package work.keepwriting.core

import java.util.Base64

/**
 * Bot 浏览器（dsbot-bot-browser）：解析 state.browser 与 live browserHolds 的持有状态、
 * browser-frame 应答与错误码降级映射、前台/可见/单飞/过期丢弃轮询调度器，以及控制端点请求体。
 * 端点形态见 tech/dsbot-api 的 browser-endpoints-0310（DS-Bot 0.3.10 channel.js / browser.js /
 * browser-plugins.js / browser/mount.js / browser/session.js 源码核对）。
 * 手机端只查看快照与控制持有状态，不调用 browser-wait / browser-result，不将快照写入日志或磁盘。
 */
object BotBrowser {
    /** 前台可见时的画面快照轮询间隔（约 2 秒）。 */
    const val POLL_INTERVAL_MS = 2_000L

    /** 快照解码的最大边长（像素），避免大图在移动端 OOM。 */
    const val MAX_IMAGE_EDGE = 2048

    /** 单帧图像字节上限（10 MiB），防止异常载荷耗尽内存。 */
    const val MAX_FRAME_BYTES = 10 * 1024 * 1024

    /** Bot 浏览器的持有状态。 */
    enum class HoldState {
        BOT_USING,
        WAITING_HANDOFF,
        USER_TAKEN_OVER,
        STOPPED,
    }

    /** 单个 Bot 的浏览器持有详情（来自 live browserHolds[botId]）。 */
    data class HoldInfo(
        val state: HoldState,
        val by: String?,
        val reason: String?,
        val note: String?,
        val callId: String?,
        val sessionId: String?,
    )

    /** 浏览器页控制按钮可用性与宿主降级提示状态。 */
    data class Controls(
        val canTakeover: Boolean,
        val canGiveBack: Boolean,
        val canStop: Boolean,
        val desktopOnlyHint: Boolean,
    )

    /** 一帧浏览器画面快照（仅存内存；toString 不输出 URL、标题或图像字节以防日志泄露）。 */
    class Frame(
        val mime: String,
        val bytes: ByteArray,
        val url: String,
        val title: String,
    ) {
        override fun toString(): String = "Frame(mime=$mime, bytes=${bytes.size})"
    }

    /** browser-frame 应答的业务分类。 */
    sealed interface FrameOutcome {
        data class Ready(val frame: Frame) : FrameOutcome
        data object HostOff : FrameOutcome
        data object NoOpenBrowser : FrameOutcome
        data class Error(val message: String) : FrameOutcome
    }

    /** browser-takeover / browser-give-back / browser-stop 的结果分类。 */
    sealed interface ActionOutcome {
        data object Ok : ActionOutcome
        data object DesktopOnly : ActionOutcome
        data class Error(val message: String) : ActionOutcome
    }

    /** 宿主是否具备桌面浏览器通道（state.browser；null 表示字段缺失）。 */
    fun hostHasDesktopChannel(state: Json.Value.Object?): Boolean? =
        (state?.members?.get("browser") as? Json.Value.BoolValue)?.value

    /** 从 state（合并后的 live browserHolds）派生该 Bot 的持有状态。 */
    fun holdOf(state: Json.Value.Object?, botId: String): HoldInfo {
        val holds = state?.members?.get("browserHolds") as? Json.Value.Object
        val hold = holds?.members?.get(botId) as? Json.Value.Object
            ?: return HoldInfo(HoldState.BOT_USING, null, null, null, null, null)
        val by = hold.str("by")
        val reason = hold.str("reason")
        val note = hold.str("note")?.takeIf { it.isNotBlank() }
        val callId = hold.str("callId")?.takeIf { it.isNotBlank() }
        val sessionId = hold.str("sessionId")?.takeIf { it.isNotBlank() }
        val holdState = when {
            by == "bot" -> HoldState.WAITING_HANDOFF
            by == "user" && reason == "stopped" -> HoldState.STOPPED
            by == "user" -> HoldState.USER_TAKEN_OVER
            else -> HoldState.BOT_USING
        }
        return HoldInfo(holdState, by, reason, note, callId, sessionId)
    }

    /**
     * 计算控制按钮可用性：
     * - HostOff（未启用插件也无桌面通道）时全部禁用；
     * - dsh Web 宿主（state.browser=false 或曾因 takeover/give-back 收到 bot/browser-off）时
     *   接管与交还置灰并显示「需在桌面应用中接管或交还」，停止仍可用；
     * - 桌面宿主时按持有状态启用：非用户持有可接管，已有持有（交接/接管/已停止）可交还，停止始终可用。
     */
    fun controlsOf(
        hold: HoldInfo,
        hostDesktopChannel: Boolean?,
        takeoverUnsupported: Boolean,
        frameOutcome: FrameOutcome?,
    ): Controls {
        if (frameOutcome is FrameOutcome.HostOff) {
            return Controls(canTakeover = false, canGiveBack = false, canStop = false, desktopOnlyHint = false)
        }
        val desktopOnly = (hostDesktopChannel == false) || takeoverUnsupported
        if (desktopOnly) {
            return Controls(canTakeover = false, canGiveBack = false, canStop = true, desktopOnlyHint = true)
        }
        val canTakeover = hold.state == HoldState.BOT_USING || hold.state == HoldState.WAITING_HANDOFF
        val canGiveBack = hold.state != HoldState.BOT_USING
        return Controls(canTakeover = canTakeover, canGiveBack = canGiveBack, canStop = true, desktopOnlyHint = false)
    }

    /** 解码 browser-frame 应答及错误码降级映射。 */
    fun decodeFrameResult(result: DsbotApi.Result): FrameOutcome = when (result) {
        is DsbotApi.Result.Ok -> {
            val obj = result.value as? Json.Value.Object
            val data = obj?.str("data")?.trim().orEmpty()
            val maxEncodedLen = (MAX_FRAME_BYTES / 3L + 2L) * 4L
            if (obj == null || data.isEmpty()) {
                FrameOutcome.Error(I18n.tr("无法解析浏览器画面", "Could not decode browser frame"))
            } else if (data.length.toLong() > maxEncodedLen) {
                FrameOutcome.Error(I18n.tr("浏览器画面数据过大", "Browser frame data is too large"))
            } else {
                val bytes = runCatching { Base64.getDecoder().decode(data) }.getOrNull()
                if (bytes == null || bytes.isEmpty() || bytes.size > MAX_FRAME_BYTES) {
                    FrameOutcome.Error(I18n.tr("浏览器画面数据无效", "Invalid browser frame data"))
                } else {
                    val mime = obj.str("mime")?.takeIf { it.isNotBlank() } ?: "image/jpeg"
                    val url = obj.str("url").orEmpty()
                    val title = obj.str("title").orEmpty()
                    FrameOutcome.Ready(Frame(mime, bytes, url, title))
                }
            }
        }
        is DsbotApi.Result.BotError -> when (result.code) {
            "bot/browser-off" -> FrameOutcome.HostOff
            "bot/not-found" -> FrameOutcome.NoOpenBrowser
            else -> FrameOutcome.Error(BotManage.errorText(result.code, result.message))
        }
        is DsbotApi.Result.Failure -> when (result.reason) {
            DsbotApi.Reason.AUTH_EXPIRED -> FrameOutcome.Error(I18n.tr("会话已过期，请重新连接", "Session expired. Reconnect"))
            else -> FrameOutcome.Error(I18n.tr("获取画面失败，请检查网络连接", "Could not fetch frame. Check your connection"))
        }
    }

    /** 解码控制端点结果：takeover / give-back 收到 bot/browser-off 时转为桌面宿主降级而非报错。 */
    fun decodeControlResult(endpoint: String, result: DsbotApi.Result): ActionOutcome = when (result) {
        is DsbotApi.Result.Ok -> ActionOutcome.Ok
        is DsbotApi.Result.BotError -> {
            if (result.code == "bot/browser-off" && (endpoint == "browser-takeover" || endpoint == "browser-give-back")) {
                ActionOutcome.DesktopOnly
            } else {
                ActionOutcome.Error(BotManage.errorText(result.code, result.message))
            }
        }
        is DsbotApi.Result.Failure -> ActionOutcome.Error(BotManage.errorText(null, null))
    }

    // ---- 界面文案（随 I18n.lang）----

    fun holdLabel(state: HoldState): String = when (state) {
        HoldState.BOT_USING -> I18n.tr("Bot 使用中", "Bot is using it")
        HoldState.WAITING_HANDOFF -> I18n.tr("等待用户交接", "Waiting for handoff")
        HoldState.USER_TAKEN_OVER -> I18n.tr("用户已接管", "You control this browser")
        HoldState.STOPPED -> I18n.tr("已停止", "Stopped")
    }

    fun hostOffTitle(): String = I18n.tr("此宿主未启用 Bot 浏览器", "Bot browser is not enabled on this host")

    fun hostOffDetail(): String = I18n.tr(
        "请在服务端的 DS-Bot 团队配置中启用浏览器插件（browserPlugin：refs、script 或 task）。",
        "Enable a browser plugin (browserPlugin: refs, script, or task) in the DS-Bot team config on the server.",
    )

    fun noOpenBrowserText(): String = I18n.tr("这个 Bot 还没有打开浏览器", "This Bot has no open browser")

    fun desktopOnlyHintText(): String = I18n.tr("需在桌面应用中接管或交还", "Take over and give back require the desktop app")

    /** 请求体构造（面板交还不带 outcome 与 callId；手机端不提供 browser-wait / browser-result）。 */
    object Requests {
        fun frame(botId: String): String = "{\"botId\":${JsonString.of(botId)}}"
        fun takeover(botId: String): String = "{\"botId\":${JsonString.of(botId)}}"
        fun giveBack(botId: String): String = "{\"botId\":${JsonString.of(botId)}}"
        fun stop(botId: String): String = "{\"botId\":${JsonString.of(botId)}}"
    }

    /**
     * 画面轮询调度器（纯 JVM 状态机）：
     * - 仅在页面可见、App 在前台且在线时允许发起请求；
     * - 单飞：已有在途请求时 [tryStart] 返回 null；
     * - 过期丢弃：离开页面、进入后台、切换 Bot 或手动重试都会推进代际，旧请求回包被 [onResponse] 返回 null 丢弃；
     * - 收到 [FrameOutcome.HostOff] 后停止自动轮询，直到 [retry] 或切换目标。
     */
    class PollController {
        data class Ticket(val token: Long, val epoch: Long, val botId: String)

        private var targetBotId: String? = null
        private var active = false
        private var hostOff = false
        private var epoch = 0L
        private var nextToken = 0L
        private var inFlightToken: Long? = null

        val isActive: Boolean get() = active
        val isInFlight: Boolean get() = inFlightToken != null
        val isHostOff: Boolean get() = hostOff

        fun setTarget(botId: String) {
            if (targetBotId == botId) return
            targetBotId = botId
            hostOff = false
            epoch++
            inFlightToken = null
        }

        fun setActive(visible: Boolean, foreground: Boolean, online: Boolean) {
            val next = visible && foreground && online
            if (next == active) return
            active = next
            if (!active) {
                epoch++
                inFlightToken = null
            }
        }

        fun shouldPoll(): Boolean = active && !hostOff && targetBotId != null && inFlightToken == null

        fun tryStart(): Ticket? {
            if (!shouldPoll()) return null
            val botId = targetBotId ?: return null
            val ticket = Ticket(token = ++nextToken, epoch = epoch, botId = botId)
            inFlightToken = ticket.token
            return ticket
        }

        /** 结算一次应答；若已离开页面、进入后台、切换 Bot 或被重试作废，返回 null 丢弃。 */
        fun onResponse(ticket: Ticket, outcome: FrameOutcome): FrameOutcome? {
            if (inFlightToken == ticket.token) inFlightToken = null
            if (!active || ticket.epoch != epoch || ticket.botId != targetBotId) return null
            if (outcome is FrameOutcome.HostOff) hostOff = true
            return outcome
        }

        /** 清除 HostOff 状态并作废任何在途请求，立即尝试发起一次新请求。 */
        fun retry(): Ticket? {
            hostOff = false
            epoch++
            inFlightToken = null
            return tryStart()
        }
    }

    private fun Json.Value.Object.str(key: String): String? =
        (members[key] as? Json.Value.StringValue)?.value
}
