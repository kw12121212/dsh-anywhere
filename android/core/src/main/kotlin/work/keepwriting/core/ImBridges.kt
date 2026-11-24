package work.keepwriting.core

/**
 * IM 接入（dsbot-im-bridges）：state.im（{connections[], platforms[]}）与 live imStatus 的解码、内置平台目录
 *（字段、选项、密钥标记、扫码登录、开通步骤与链接）、状态合成与文案、聊天名称与绑定目标、添加校验、扫码登录应答与
 * im-* 请求体。复刻 ds-bot 0.3.10 的 src/host/im/index.js、src/shared/im-platforms.js 与 client.js（ImPage、AddView、
 * QrLogin、DetailView、GUIDES）（tech/dsbot-api 的 im-endpoints-0310）。入口以 state.im 字段存在门控。
 * 密钥字段的值只进请求体：不写日志与诊断上报；视图只含密钥字段名。
 */
object ImBridges {
    /** 服务端要等平台应答的端点：添加/更新时实时校验账号，扫码等待单次长轮询最长约 35 秒。 */
    val SLOW_ENDPOINTS: Set<String> = setOf("im-add", "im-update", "im-retry", "im-login-start", "im-login-wait")

    data class Field(val key: String, val zh: String, val en: String, val secret: Boolean = false, val options: List<Pair<String, String>> = emptyList(), val hint: String? = null) {
        val label: String get() = I18n.tr(zh, en)
    }

    data class Platform(
        val id: String,
        val zh: String,
        val en: String,
        val fields: List<Field>,
        val qrLogin: Boolean = false,
        /** 开通步骤（中、英）。 */
        val steps: List<Pair<String, String>> = emptyList(),
        /** 开发者后台链接与站点名。 */
        val link: Pair<String, String>? = null,
        /** 飞书 Lark 版的链接（domain=lark 时）。 */
        val larkLink: Pair<String, String>? = null,
    ) {
        val name: String get() = I18n.tr(zh, en)
    }

    /** 0.3.10 平台目录（im-platforms.js 顺序；步骤与网页端 GUIDES 及其 ZH 词典同句）。 */
    val PLATFORMS: List<Platform> = listOf(
        Platform(
            "telegram", "Telegram", "Telegram", listOf(Field("token", "Bot token", "Bot token", secret = true)),
            steps = listOf(
                "在 Telegram 里打开 @BotFather，发送 /newbot。" to "In Telegram, open @BotFather and send /newbot.",
                "给机器人起名字和用户名。" to "Give the bot a name and a username.",
                "把 BotFather 发来的 token 粘贴到下面。" to "Paste the token that BotFather sends.",
            ),
            link = "https://t.me/BotFather" to "BotFather",
        ),
        Platform(
            "weixin", "微信", "WeChat", emptyList(), qrLogin = true,
            steps = listOf(
                "选好由哪个 Bot 回复。" to "Choose the Bot that answers.",
                "用手机微信扫码并确认。" to "Scan the code with WeChat on your phone and confirm.",
            ),
        ),
        Platform(
            "feishu", "飞书 / Lark", "Feishu / Lark",
            listOf(
                Field("domain", "版本", "Edition", options = listOf("feishu" to "Feishu", "lark" to "Lark")),
                Field("appId", "App ID", "App ID"),
                Field("appSecret", "App Secret", "App Secret", secret = true),
            ),
            steps = listOf(
                "在开发者后台创建企业自建应用，添加「机器人」能力。" to "Create a custom app in the developer console and add the bot capability.",
                "开通读取和发送消息的权限。" to "Grant the permissions to read and send messages.",
                "从「凭证与基础信息」复制 App ID 和 App Secret 粘贴到下面，点连接。" to "Paste the App ID and App Secret from Credentials, then connect.",
                "在「事件与回调」里选择长连接接收事件，添加「接收消息」事件，然后发布版本。" to "Under Events & callbacks, receive events through a long connection, add the receive message event, and publish a version.",
            ),
            link = "https://open.feishu.cn/app" to "open.feishu.cn",
            larkLink = "https://open.larksuite.com/app" to "open.larksuite.com",
        ),
        Platform(
            "dingtalk", "钉钉", "DingTalk",
            listOf(Field("clientId", "Client ID", "Client ID"), Field("clientSecret", "Client Secret", "Client Secret", secret = true)),
            steps = listOf(
                "在开发者后台创建应用，添加机器人。" to "Create an app in the developer console and add a robot.",
                "消息接收模式选 Stream 模式，然后发布。" to "Set the robot to receive messages in Stream mode, then publish it.",
                "把 Client ID 和 Client Secret 粘贴到下面。" to "Paste the Client ID and Client Secret.",
            ),
            link = "https://open-dev.dingtalk.com/" to "open-dev.dingtalk.com",
        ),
        Platform(
            "wecom", "企业微信", "WeCom",
            listOf(Field("botId", "Bot ID", "Bot ID"), Field("secret", "Secret", "Secret", secret = true)),
            steps = listOf(
                "在企业微信里创建智能机器人，选 API 模式、长连接。" to "In WeCom, create an intelligent robot in API mode with a long connection.",
                "把 Bot ID 和 Secret 粘贴到下面。" to "Paste the Bot ID and Secret.",
            ),
            link = "https://work.weixin.qq.com/" to "work.weixin.qq.com",
        ),
        Platform(
            "qq", "QQ", "QQ",
            listOf(Field("appId", "App ID", "App ID"), Field("appSecret", "App Secret", "App Secret", secret = true)),
            steps = listOf(
                "在 QQ 开放平台创建机器人。" to "Create a bot on the QQ Open Platform.",
                "从开发设置里复制 App ID 和 App Secret 粘贴到下面。" to "Paste the App ID and App Secret from the development settings.",
                "机器人上线前，先把你的 QQ 号加进沙箱。" to "Until the bot is published, add your QQ account to its sandbox.",
            ),
            link = "https://q.qq.com/qqbot/" to "q.qq.com",
        ),
        Platform(
            "discord", "Discord", "Discord", listOf(Field("token", "Bot token", "Bot token", secret = true)),
            steps = listOf(
                "在 Developer Portal 创建应用，打开 Bot 页。" to "Create an application in the Developer Portal and open Bot.",
                "打开 Message Content Intent，再重置并复制 token。" to "Turn on Message Content Intent, then reset and copy the token.",
                "用 bot 权限范围把机器人邀请进你的服务器。" to "Invite the bot to your server with the bot scope.",
            ),
            link = "https://discord.com/developers/applications" to "Developer Portal",
        ),
        Platform(
            "slack", "Slack", "Slack",
            listOf(
                Field("appToken", "App-Level Token", "App-Level Token", secret = true, hint = "xapp-"),
                Field("botToken", "Bot Token", "Bot Token", secret = true, hint = "xoxb-"),
            ),
            steps = listOf(
                "创建应用，打开 Socket Mode。" to "Create an app and turn on Socket Mode.",
                "生成带 connections:write 的 App-Level Token。" to "Make an app-level token with connections:write.",
                "添加 app_mentions:read、chat:write、im:history、files:read 权限，订阅 app_mention 和 message.im 事件。" to "Add the scopes app_mentions:read, chat:write, im:history and files:read, and subscribe to app_mention and message.im.",
                "把应用安装到工作区，粘贴两个 token。" to "Install the app to the workspace and paste both tokens.",
            ),
            link = "https://api.slack.com/apps" to "api.slack.com",
        ),
    )

    /** 平台定义；未知 id 以 id 作名称、无字段（网页端 platformOf 的回退）。 */
    fun platformOf(id: String): Platform = PLATFORMS.firstOrNull { it.id == id } ?: Platform(id, id, id, emptyList())

    /** 某平台当前要显示的后台链接（飞书按版本选飞书或 Lark）。 */
    fun linkOf(platform: Platform, domain: String?): Pair<String, String>? =
        if (platform.id == "feishu" && domain == "lark") platform.larkLink else platform.link

    // ---- state ----

    data class Paired(val id: String, val name: String)
    data class Request(val id: String, val name: String, val chatKey: String?, val chatKind: String?)
    data class Target(val botId: String?, val roomId: String?)
    data class Chat(val key: String, val kind: String, val title: String?, val target: Target?)

    data class Conn(
        val id: String,
        val platform: String,
        val accountName: String?,
        val botId: String?,
        val settings: Map<String, String>,
        val enabled: Boolean,
        val paired: List<Paired>,
        val requests: List<Request>,
        val chats: List<Chat>,
    )

    data class Status(val state: String, val error: String?)

    data class View(val connections: List<Conn>, val platforms: List<String>)

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.obj(k: String) = members[k] as? Json.Value.Object
    private fun Json.Value.Object.objects(k: String) = ((members[k] as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { it as? Json.Value.Object }

    /** 远端是否提供 IM 接入（state 含 im 对象）。 */
    fun has(state: Json.Value.Object?): Boolean = state?.members?.get("im") is Json.Value.Object

    fun view(state: Json.Value.Object?): View? {
        val im = state?.obj("im") ?: return null
        val platforms = ((im.members["platforms"] as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { (it as? Json.Value.StringValue)?.value }
        return View(im.objects("connections").mapNotNull(::decodeConn), platforms)
    }

    fun conn(state: Json.Value.Object?, id: String): Conn? = view(state)?.connections?.firstOrNull { it.id == id }

    fun decodeConn(o: Json.Value.Object): Conn? {
        val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return null
        val platform = o.str("platform") ?: return null
        val chats = o.obj("chats")?.members.orEmpty().mapNotNull { (key, v) ->
            val c = v as? Json.Value.Object ?: return@mapNotNull null
            val t = c.obj("target")
            Chat(key, c.str("kind") ?: "direct", c.str("title")?.takeIf { it.isNotEmpty() }, t?.let { Target(it.str("botId"), it.str("roomId")) }?.takeIf { it.botId != null || it.roomId != null })
        }
        return Conn(
            id = id,
            platform = platform,
            accountName = o.obj("account")?.str("name")?.takeIf { it.isNotEmpty() },
            botId = o.str("botId"),
            settings = o.obj("settings")?.members.orEmpty().mapNotNull { (k, v) -> (v as? Json.Value.StringValue)?.value?.let { k to it } }.toMap(),
            enabled = (o.members["enabled"] as? Json.Value.BoolValue)?.value != false,
            paired = o.objects("paired").mapNotNull { p -> p.str("id")?.let { Paired(it, p.str("name") ?: it) } },
            requests = o.objects("requests").mapNotNull { r -> r.str("id")?.let { Request(it, r.str("name") ?: it, r.str("chatKey"), r.str("chatKind")) } },
            chats = chats,
        )
    }

    /** live imStatus[connId]。 */
    fun statusOf(state: Json.Value.Object?, connId: String): Status? =
        state?.obj("imStatus")?.obj(connId)?.let { Status(it.str("state") ?: "", it.str("error")?.takeIf { e -> e.isNotEmpty() }) }

    /** 显示状态（网页端 stateOf）：停用 → off；ready/error/retrying/connecting 原样；其余视为连接中。 */
    fun stateOf(conn: Conn, status: Status?): String = when {
        !conn.enabled -> "off"
        status?.state in setOf("ready", "error", "retrying", "connecting") -> status!!.state
        else -> "connecting"
    }

    fun stateText(conn: Conn, status: Status?): String = when (stateOf(conn, status)) {
        "off" -> I18n.tr("已停用", "Disabled")
        "ready" -> I18n.tr("已连接", "Connected")
        "retrying" -> I18n.tr("正在重试…", "Retrying…")
        "error" -> I18n.tr("无法连接：", "Could not connect: ") + (status?.error ?: "")
        else -> I18n.tr("连接中…", "Connecting…")
    }

    /** IM 机器人的显示名：账号名，否则平台名；账号名就是平台英文名（如微信的 "WeChat"）时按界面语言显示平台名。 */
    fun title(conn: Conn): String {
        val platform = platformOf(conn.platform)
        return conn.accountName?.takeIf { it != platform.en } ?: platform.name
    }

    /** 扫码链接可在其他应用中打开（http/https）；否则只能复制或扫码。 */
    fun openable(qr: String): Boolean = qr.startsWith("https://", ignoreCase = true) || qr.startsWith("http://", ignoreCase = true)

    /** 聊天名称（网页端 chatName）：标题 → 已配对账号名 → 「私聊」。 */
    fun chatName(conn: Conn, chat: Chat): String =
        chat.title ?: conn.paired.firstOrNull { it.id == chat.key }?.name ?: I18n.tr("私聊", "Direct message")

    /** 可添加的平台：远端声明且本 App 内置了表单定义的，按远端列出的顺序（同网页端）。 */
    fun addablePlatforms(view: View): List<Platform> =
        view.platforms.mapNotNull { id -> PLATFORMS.firstOrNull { it.id == id } }

    /** 远端声明、但本 App 没有表单定义的平台 id（只显示、不可添加，需更新 App）。 */
    fun unknownPlatforms(view: View): List<String> = view.platforms.filter { id -> PLATFORMS.none { it.id == id } }

    /** 等待允许的请求（全部 IM 机器人）：(连接, 请求)。 */
    fun waiting(view: View): List<Pair<Conn, Request>> = view.connections.flatMap { c -> c.requests.map { c to it } }

    /** 可选的对话对象：state.bots 与 state.rooms 的 (id, 名称)，按 state 顺序。 */
    fun bots(state: Json.Value.Object?): List<Pair<String, String>> = named(state, "bots")
    fun rooms(state: Json.Value.Object?): List<Pair<String, String>> = named(state, "rooms")
    private fun named(state: Json.Value.Object?, key: String) =
        ((state?.members?.get(key) as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { it as? Json.Value.Object }
            .mapNotNull { o -> o.str("id")?.let { it to (o.str("name") ?: it) } }

    /** 目标名称（Bot 或群聊名），找不到为 null。 */
    fun targetName(state: Json.Value.Object?, target: Target?): String? {
        if (target == null) return null
        val list = (if (target.botId != null) state?.members?.get("bots") else state?.members?.get("rooms")) as? Json.Value.Array
        val id = target.botId ?: target.roomId
        return list?.items?.mapNotNull { it as? Json.Value.Object }?.firstOrNull { it.str("id") == id }?.str("name")
    }

    // ---- 添加 ----

    /** 表单值：选项字段缺省取第一个选项（网页端 cleanFields）。 */
    fun formValues(platform: Platform, typed: Map<String, String>): Map<String, String> =
        platform.fields.associate { f ->
            f.key to if (f.options.isNotEmpty()) (typed[f.key]?.takeIf { v -> f.options.any { it.first == v } } ?: f.options.first().first) else typed[f.key].orEmpty()
        }

    /** 添加的本地预校验：须选 Bot、非选项字段不能为空；扫码平台不经此表单。通过时 null。 */
    fun addProblem(platform: Platform, botId: String?, typed: Map<String, String>): String? = when {
        botId.isNullOrEmpty() -> I18n.tr("先选择对话的 Bot", "Choose a Bot first")
        else -> platform.fields.firstOrNull { it.options.isEmpty() && typed[it.key].isNullOrBlank() }?.let { I18n.tr("填写 ${it.label}", "${it.label} is required") }
    }

    // ---- 扫码登录 ----

    data class LoginStart(val loginId: String, val qr: String, val expiresAt: Long?)

    fun loginStart(value: Json.Value?): LoginStart? {
        val o = value as? Json.Value.Object ?: return null
        val loginId = o.str("loginId")?.takeIf { it.isNotEmpty() } ?: return null
        val qr = o.str("qr")?.takeIf { it.isNotEmpty() } ?: return null
        return LoginStart(loginId, qr, (o.members["expiresAt"] as? Json.Value.NumberValue)?.raw?.toDoubleOrNull()?.toLong())
    }

    /** im-login-wait 的状态：waiting | scanned | expired | done；未知按 waiting。 */
    fun loginStatus(value: Json.Value?): String =
        ((value as? Json.Value.Object)?.str("status")).let { if (it in setOf("waiting", "scanned", "expired", "done")) it!! else "waiting" }

    fun loginCaption(status: String, hasCode: Boolean): String = when (status) {
        "scanned" -> I18n.tr("已扫码，请在手机上确认", "Scanned — confirm on the phone")
        "expired" -> I18n.tr("二维码已过期", "Code expired")
        else -> if (hasCode) I18n.tr("用微信扫码", "Scan with WeChat") else I18n.tr("正在获取二维码…", "Getting a code…")
    }

    /** 请求体（im/index.js endpoints 的参数，源码核对）。 */
    object Requests {
        private fun q(s: String) = JsonString.of(s)
        private fun id(id: String) = "{\"id\":" + q(id) + "}"
        fun add(platform: String, botId: String, fields: Map<String, String>) =
            "{\"platform\":" + q(platform) + ",\"botId\":" + q(botId) + ",\"fields\":{" +
                fields.entries.joinToString(",") { q(it.key) + ":" + q(it.value.trim()) } + "}}"
        fun setEnabled(id: String, enabled: Boolean) = "{\"id\":" + q(id) + ",\"enabled\":" + enabled + "}"
        fun setBot(id: String, botId: String) = "{\"id\":" + q(id) + ",\"botId\":" + q(botId) + "}"
        fun remove(id: String) = id(id)
        fun retry(id: String) = id(id)
        fun pair(id: String, senderId: String, allow: Boolean) = "{\"id\":" + q(id) + ",\"senderId\":" + q(senderId) + ",\"allow\":" + allow + "}"
        fun unpair(id: String, senderId: String) = "{\"id\":" + q(id) + ",\"senderId\":" + q(senderId) + "}"
        /** target=null 解除绑定（JSON null）。 */
        fun bindChat(id: String, chatKey: String, target: Target?): String {
            val t = when {
                target == null -> "null"
                target.botId != null -> "{\"botId\":" + q(target.botId) + "}"
                else -> "{\"roomId\":" + q(target.roomId.orEmpty()) + "}"
            }
            return "{\"id\":" + q(id) + ",\"chatKey\":" + q(chatKey) + ",\"target\":" + t + "}"
        }
        fun removeChat(id: String, chatKey: String) = "{\"id\":" + q(id) + ",\"chatKey\":" + q(chatKey) + ",\"remove\":true}"
        fun loginStart(platform: String, botId: String) = "{\"platform\":" + q(platform) + ",\"botId\":" + q(botId) + "}"
        fun loginWait(platform: String, botId: String, loginId: String) =
            "{\"platform\":" + q(platform) + ",\"botId\":" + q(botId) + ",\"loginId\":" + q(loginId) + "}"
    }
}
