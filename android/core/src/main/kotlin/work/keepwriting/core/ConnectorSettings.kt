package work.keepwriting.core

/**
 * 连接器（dsbot-connectors-mail）：live connectors 视图（{connectors[], mail}）的解码、状态文案、按鉴权类型与状态派生的
 * 可用动作、已连接与类别分组、搜索、添加表单校验、导入扫描结果与各 connector-* 请求体。
 * 字段与动作复刻 ds-bot 0.3.10 的 src/host/connectors/index.js（rowFor、endpoints）与 client.js（ConnectorRow、
 * ConnectorsPage）（tech/dsbot-api 的 connector-endpoints-0310）。入口以 state.connectors 字段存在门控（hello 不声明能力名）。
 * key、token、Client Secret 只出现在请求体里：不写日志与诊断上报；视图本身不含任何凭据值。
 */
object ConnectorSettings {
    const val OFF = "off"
    const val CONNECTING = "connecting"
    const val READY = "ready"
    const val ERROR = "error"
    const val NEEDS_SIGN_IN = "needs-sign-in"
    const val NEEDS_CLIENT = "needs-client"

    /** 邮箱在类别分组里的位置（网页端 ConnectorsPage 的 mailbox.category）。 */
    const val MAIL_CATEGORY = "work"

    /**
     * 服务端可能久等才应答的端点：登录验证（IMAP/SMTP 超时 60–120 秒）、发信、OAuth 发现与令牌校验、导入。
     * 走长读超时，免得 30 秒误报失败而服务端照样执行。
     */
    val SLOW_ENDPOINTS: Set<String> = setOf(
        "connector-connect", "connector-sign-in-code", "connector-client", "connector-retry",
        "connector-add", "connector-import", "connector-import-scan",
        "mail-account-add", "mail-send-answer", "mail-sign-in-code",
    )

    data class Auth(
        /** none | oauth | key | custom。 */
        val kind: String,
        /** OAuth 服务是否也接受粘贴 token（GitHub）。 */
        val token: Boolean,
        /** 内置的公共客户端 id（GitHub 设备码）；空为无。 */
        val clientId: String?,
        val deviceFlow: Boolean,
        /** key 类服务获取 key 的地址。 */
        val keyUrl: String?,
        /** 需要用户自带客户端的服务（Slack）：kind 与应用设置地址。 */
        val clientSetupKind: String?,
        val clientSetupAppUrl: String?,
    )

    sealed interface Pending {
        /** 浏览器登录：授权地址（回调落在 DS-Bot 所在电脑的 127.0.0.1）。 */
        data class Browser(val url: String) : Pending
        /** 设备码登录：用户码与验证地址。 */
        data class Device(val userCode: String, val verificationUri: String) : Pending
    }

    data class Row(
        val id: String,
        val name: String,
        val category: String,
        val about: String,
        val auth: Auth,
        val state: String,
        val error: String?,
        val account: String?,
        /** 只读开关的当前值；null 表示此行不提供只读开关（未连接或非远程服务）。 */
        val readOnly: Boolean?,
        val offFor: List<String>,
        val tools: Int,
        val custom: Boolean,
        val pending: Pending?,
        val redirectUri: String?,
    ) {
        /** 已开启（网页端 ConnectorRow 的 on）：除未连接与等待客户端外的状态。 */
        val on: Boolean get() = state != OFF && state != NEEDS_CLIENT
    }

    data class View(val rows: List<Row>, val mail: MailSettings.Mail)

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.bool(k: String) = (members[k] as? Json.Value.BoolValue)?.value == true
    private fun Json.Value.Object.obj(k: String) = members[k] as? Json.Value.Object
    private fun Json.Value.Object.strings(k: String) =
        ((members[k] as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { (it as? Json.Value.StringValue)?.value }

    /** 远端是否提供连接器（state 含 connectors 对象）；旧版远端没有此字段时隐藏连接器入口。 */
    fun has(state: Json.Value.Object?): Boolean = state?.members?.get("connectors") is Json.Value.Object

    /** state 中的连接器视图；没有时 null。 */
    fun view(state: Json.Value.Object?): View? = decodeView(state?.members?.get("connectors"))

    /** 解码 connectorsView（state.connectors 或写端点应答；connector-import 的 {view, created} 取 view）。 */
    fun decodeView(value: Json.Value?): View? {
        val o = value as? Json.Value.Object ?: return null
        (o.obj("view"))?.let { if (o.members["created"] != null) return decodeView(it) }
        val rows = ((o.members["connectors"] as? Json.Value.Array)?.items ?: emptyList())
            .mapNotNull(::decodeRow)
            .filter { it.id != "mail" }
        return View(rows, MailSettings.decode(o.obj("mail")))
    }

    fun decodeRow(value: Json.Value): Row? {
        val o = value as? Json.Value.Object ?: return null
        val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return null
        val a = o.obj("auth")
        val client = a?.obj("client")
        val setup = a?.obj("clientSetup")
        val auth = Auth(
            kind = a?.str("kind") ?: "none",
            token = a?.bool("token") == true,
            clientId = client?.str("id")?.takeIf { it.isNotEmpty() },
            deviceFlow = client?.bool("deviceFlow") == true,
            keyUrl = a?.str("keyUrl")?.takeIf { it.isNotEmpty() },
            clientSetupKind = setup?.str("kind"),
            clientSetupAppUrl = setup?.str("appUrl")?.takeIf { it.isNotEmpty() },
        )
        val p = o.obj("pending")
        val pending = when (p?.str("kind")) {
            "browser" -> p.str("url")?.takeIf { it.isNotEmpty() }?.let { Pending.Browser(it) }
            "device" -> {
                val code = p.str("userCode")?.takeIf { it.isNotEmpty() }
                val uri = p.str("verificationUri")?.takeIf { it.isNotEmpty() }
                if (code != null && uri != null) Pending.Device(code, uri) else null
            }
            else -> null
        }
        return Row(
            id = id,
            name = o.str("name")?.takeIf { it.isNotEmpty() } ?: id,
            category = o.str("category") ?: "custom",
            about = o.str("about") ?: "",
            auth = auth,
            state = o.str("state") ?: OFF,
            error = o.str("error")?.takeIf { it.isNotEmpty() },
            account = o.str("account")?.takeIf { it.isNotEmpty() },
            readOnly = (o.members["readOnly"] as? Json.Value.BoolValue)?.value,
            offFor = o.strings("offFor"),
            tools = (o.members["tools"] as? Json.Value.NumberValue)?.raw?.toDoubleOrNull()?.toInt() ?: 0,
            custom = o.bool("custom"),
            pending = pending,
            redirectUri = o.str("redirectUri")?.takeIf { it.isNotEmpty() },
        )
    }

    fun row(state: Json.Value.Object?, id: String): Row? = view(state)?.rows?.firstOrNull { it.id == id }

    // ---- 文案 ----

    /** 一行状态（网页端 statusText2）；未连接时为简介。 */
    fun statusText(row: Row): String = when (row.state) {
        CONNECTING -> if (row.pending != null) I18n.tr("等待登录…", "Waiting for sign-in…") else I18n.tr("连接中…", "Connecting…")
        NEEDS_SIGN_IN -> I18n.tr("需要重新登录", "Sign in again")
        NEEDS_CLIENT -> I18n.tr("这个服务需要应用客户端 ID", "This service needs an app client ID")
        ERROR -> I18n.tr("无法连接：", "Could not connect: ") + (row.error ?: "")
        READY -> {
            val who = row.account?.let { I18n.tr("已连接：$it", "Connected as $it") } ?: I18n.tr("已连接", "Connected")
            if (row.tools > 0) who + " · " + I18n.tr("${row.tools} 个工具", "${row.tools} tools") else who
        }
        else -> aboutText(row.about)
    }

    /** 目录中英文简介的中文（网页端 ZH 词典同句）；未收录的原样显示。 */
    fun aboutText(about: String): String = if (I18n.lang == ChatBars.Lang.EN) about else ABOUT_ZH[about] ?: about

    private val ABOUT_ZH = mapOf(
        "Repositories, issues, and pull requests" to "仓库、issue 和 PR",
        "Pages, databases, and wiki content" to "页面、数据库和 Wiki 内容",
        "Issues, projects, and cycles" to "Issue、项目和迭代",
        "Jira issues and Confluence pages" to "Jira 工单和 Confluence 页面",
        "Errors, traces, and releases" to "错误、调用链和发布",
        "Payments, invoices, and customers" to "支付、发票和客户",
        "Orders, payments, and invoices" to "订单、支付和发票",
        "Payments, orders, and catalog" to "支付、订单和商品目录",
        "Workers, DNS, and account resources" to "Workers、DNS 和账户资源",
        "Projects, deployments, and logs" to "项目、部署和日志",
        "Databases, tables, and project settings" to "数据库、数据表和项目设置",
        "Postgres databases and branches" to "Postgres 数据库和分支",
        "Design files, components, and comments" to "设计文件、组件和评论",
        "Designs, folders, and exports" to "设计稿、文件夹和导出",
        "Conversations, contacts, and articles" to "会话、联系人和文章",
        "Boards, items, and workflows" to "看板、条目和工作流",
        "Sites, pages, and CMS content" to "站点、页面和 CMS 内容",
        "Sites, pages, and site content" to "站点、页面和站点内容",
        "Sites, deploys, and forms" to "站点、部署和表单",
        "Bases, tables, and records" to "Bases、表和记录",
        "Files and folders" to "文件和文件夹",
        "Zaps and app actions" to "Zap 和应用操作",
        "Tasks, lists, and docs" to "任务、清单和文档",
        "Tasks and projects" to "任务和项目",
        "Boards and whiteboard items" to "白板和白板条目",
        "CRM records, lists, and notes" to "CRM 记录、列表和备注",
        "Collections, requests, and workspaces" to "集合、请求和工作区",
        "Tasks, projects, and workspaces" to "任务、项目和工作区",
        "Files, folders, and metadata" to "文件、文件夹和元数据",
        "Contacts, deals, and tickets" to "联系人、交易和工单",
        "Channels and messages" to "频道和消息",
        "Models, datasets, and Spaces" to "模型、数据集和 Space",
        "Web search for agents" to "面向 Agent 的网络搜索",
        "Documentation for public code repositories" to "公开代码仓库的文档",
        "Library documentation and code examples" to "库文档和代码示例",
        "Microsoft product documentation" to "微软产品文档",
        "Cloudflare product documentation" to "Cloudflare 产品文档",
        "Maps, places, and routes in China" to "中国地图、地点和路线",
    )

    /** 类别顺序与名称（网页端 CATEGORIES，另加自加服务器）。 */
    val CATEGORIES: List<String> = listOf("dev", "docs", "work", "design", "data", "commerce", "search", "custom")

    fun categoryLabel(category: String): String = when (category) {
        "dev" -> I18n.tr("开发", "Developer")
        "docs" -> I18n.tr("文档与文件", "Docs & files")
        "work" -> I18n.tr("工作", "Work")
        "design" -> I18n.tr("设计", "Design")
        "data" -> I18n.tr("数据", "Data")
        "commerce" -> I18n.tr("商务", "Commerce")
        "search" -> I18n.tr("搜索", "Search")
        else -> I18n.tr("其他 MCP 服务器", "Other MCP server")
    }

    // ---- 动作（网页端 ConnectorRow） ----

    /** 「连接」的具体做法：直接连接、填 key、填 token、浏览器/设备码登录。 */
    enum class ConnectKind { DIRECT, KEY, TOKEN, SIGN_IN }

    enum class Action { CONNECT, USE_TOKEN, SIGN_IN_AGAIN, RETRY, DISCONNECT, REMOVE }

    /** 未连接时点「连接」：none 直连；key 填 key；oauth 有内置客户端或不接受 token 时登录，否则填 token；custom 无（自加命令/地址加入即连接）。 */
    fun connectKind(row: Row): ConnectKind? = when (row.auth.kind) {
        "none" -> ConnectKind.DIRECT
        "key" -> ConnectKind.KEY
        "oauth" -> if (row.auth.clientId != null || !row.auth.token) ConnectKind.SIGN_IN else ConnectKind.TOKEN
        else -> null
    }

    /** 行上的按钮。等待登录或等待客户端时为空（只显示登录面板/客户端表单）；表单展开时由界面自行隐藏。 */
    fun actions(row: Row): List<Action> = when {
        row.pending != null || row.state == NEEDS_CLIENT -> emptyList()
        row.state == NEEDS_SIGN_IN -> listOf(Action.SIGN_IN_AGAIN, Action.DISCONNECT)
        row.state == ERROR -> listOf(Action.RETRY, if (row.custom) Action.REMOVE else Action.DISCONNECT)
        row.on -> listOf(if (row.custom) Action.REMOVE else Action.DISCONNECT)
        else -> buildList {
            if (row.auth.kind == "oauth" && row.auth.token && row.auth.clientId != null) add(Action.USE_TOKEN)
            if (connectKind(row) != null) add(Action.CONNECT)
            if (row.custom) add(Action.REMOVE)
        }
    }

    /** 等待客户端（Slack 等）且没有进行中的登录时显示客户端表单。 */
    fun showClientForm(row: Row): Boolean = row.state == NEEDS_CLIENT && row.pending == null

    /** 已连接的远程服务显示只读开关。 */
    fun showReadOnly(row: Row): Boolean = row.readOnly != null && row.on && row.state == READY

    /** 设备码登录页上可「改用 token」（服务同时接受 token 时）。 */
    fun deviceAllowsToken(row: Row): Boolean = row.auth.token

    /** 去掉协议头显示地址（网页端 verificationUri.replace(/^https?:\/\//, "")）。 */
    fun hostOf(uri: String): String = uri.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")

    // ---- 分组与搜索（网页端 ConnectorsPage） ----

    /** 列表上的一项：连接器或邮箱。 */
    sealed interface Entry {
        val key: String
        data class Connector(val row: Row) : Entry { override val key get() = row.id }
        data class Mailbox(val mail: MailSettings.Mail) : Entry { override val key get() = "mail" }
    }

    data class Section(val key: String, val title: String, val entries: List<Entry>)

    /** 是否算「已连接」：已开启或有进行中的登录（网页端 connected）。 */
    fun isConnected(row: Row): Boolean = row.state != OFF || row.pending != null

    fun mailboxConnected(mail: MailSettings.Mail): Boolean = mail.accounts.isNotEmpty() || mail.pending.isNotEmpty()

    /** 名称或简介（含中文简介）包含查询词（不区分大小写）。 */
    fun matches(query: String, vararg texts: String): Boolean {
        val q = query.trim().lowercase()
        return q.isEmpty() || texts.joinToString(" ").lowercase().contains(q)
    }

    /**
     * 列表分组：无查询时「已连接」在前（邮箱有账号或待登录时在其中、排第一），其余未连接的按类别分组（邮箱未连接时归「工作」）；
     * 有查询时只返回一组匹配结果。空组不返回。
     */
    fun sections(view: View, query: String): List<Section> {
        val mailEntry = Entry.Mailbox(view.mail)
        val mailName = I18n.tr("邮箱", "Mailbox")
        val mailAbout = MailSettings.ABOUT
        if (query.isNotBlank()) {
            val found = buildList {
                if (matches(query, mailName, "Mailbox", mailAbout)) add(mailEntry)
                for (r in view.rows) if (matches(query, r.name, r.about, aboutText(r.about))) add(Entry.Connector(r))
            }
            return if (found.isEmpty()) emptyList() else listOf(Section("search", I18n.tr("搜索结果", "Results"), found))
        }
        val out = mutableListOf<Section>()
        val connected = buildList {
            if (mailboxConnected(view.mail)) add(mailEntry)
            for (r in view.rows) if (isConnected(r)) add(Entry.Connector(r))
        }
        if (connected.isNotEmpty()) out.add(Section("connected", I18n.tr("已连接", "Connected"), connected))
        for (c in CATEGORIES) {
            val entries = buildList {
                if (c == MAIL_CATEGORY && !mailboxConnected(view.mail)) add(mailEntry)
                for (r in view.rows) if (!isConnected(r) && (r.category == c || (c == "custom" && r.category !in CATEGORIES))) add(Entry.Connector(r))
            }
            if (entries.isNotEmpty()) out.add(Section(c, categoryLabel(c), entries))
        }
        return out
    }

    /** Bot 详情「连接器」分组可开关的连接器：已连接（ready）的（邮箱不在内：mailView 不返回其 offFor）。 */
    fun botRows(view: View?): List<Row> = view?.rows.orEmpty().filter { it.state == READY }

    /** 某连接器对 Bot 打开/关闭后的 offFor（网页端 BotConnectors）。 */
    fun offForToggled(row: Row, botId: String, on: Boolean): List<String> =
        if (on) row.offFor.filter { it != botId } else (row.offFor.filter { it != botId } + botId)

    // ---- 添加其他 MCP 服务器 ----

    enum class AddKind { URL, COMMAND }

    private val LOCAL_HTTP = Regex("^http://(localhost|127\\.)", RegexOption.IGNORE_CASE)
    private val HTTPS = Regex("^https://", RegexOption.IGNORE_CASE)

    /** 本地预校验（服务端 connector-add 同规则）；通过时 null。 */
    fun addProblem(kind: AddKind, name: String, url: String, command: String): String? = when {
        name.isBlank() -> I18n.tr("填写名称", "Name the connector")
        kind == AddKind.URL && url.isBlank() -> I18n.tr("填写服务器地址", "Enter the server address")
        kind == AddKind.URL && !HTTPS.containsMatchIn(url.trim()) && !LOCAL_HTTP.containsMatchIn(url.trim()) ->
            I18n.tr("连接器地址必须以 https:// 开头", "Connector addresses must start with https://")
        kind == AddKind.COMMAND && command.isBlank() -> I18n.tr("填写命令", "Enter a command")
        else -> null
    }

    /** 导入扫描到的一项（只显示名称、来源与地址或命令，不含 env/headers 的值）。 */
    data class ImportItem(val source: String, val name: String, val url: String?, val command: String?, val args: List<String>) {
        val key: String get() = "$source:$name"
        val target: String get() = url ?: listOfNotNull(command).plus(args).joinToString(" ")
    }

    /** connector-import-scan 应答：数组（或 {items:[]}）。 */
    fun importItems(value: Json.Value?): List<ImportItem> {
        val list = (value as? Json.Value.Array)?.items
            ?: ((value as? Json.Value.Object)?.members?.get("items") as? Json.Value.Array)?.items
            ?: emptyList()
        return list.mapNotNull { v ->
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            val source = o.str("source") ?: return@mapNotNull null
            val name = o.str("name")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ImportItem(source, name, o.str("url"), o.str("command"), o.strings("args"))
        }
    }

    fun sourceLabel(source: String): String = when (source) {
        "claude" -> "Claude Desktop"
        "cursor" -> "Cursor"
        "vscode" -> "VS Code"
        else -> source
    }

    /** 请求体（connectors/index.js endpoints 的参数，源码核对）。 */
    object Requests {
        private fun q(s: String) = JsonString.of(s)
        private fun id(id: String) = "{\"id\":" + q(id) + "}"
        fun connect(id: String) = id(id)
        fun connectKey(id: String, key: String) = "{\"id\":" + q(id) + ",\"key\":" + q(key.trim()) + "}"
        fun connectToken(id: String, token: String) = "{\"id\":" + q(id) + ",\"token\":" + q(token.trim()) + "}"
        fun disconnect(id: String) = id(id)
        fun remove(id: String) = id(id)
        fun retry(id: String) = id(id)
        fun cancel(id: String) = id(id)
        fun signInCode(id: String, input: String) = "{\"id\":" + q(id) + ",\"input\":" + q(input.trim()) + "}"
        fun client(id: String, clientId: String, clientSecret: String?): String {
            val secret = clientSecret?.trim()?.takeIf { it.isNotEmpty() }?.let { ",\"clientSecret\":" + q(it) } ?: ""
            return "{\"id\":" + q(id) + ",\"clientId\":" + q(clientId.trim()) + secret + "}"
        }
        fun setReadOnly(id: String, readOnly: Boolean) = "{\"id\":" + q(id) + ",\"readOnly\":" + readOnly + "}"
        fun setOffFor(id: String, offFor: List<String>) = "{\"id\":" + q(id) + ",\"offFor\":[" + offFor.joinToString(",") { q(it) } + "]}"
        fun addUrl(name: String, url: String, oauth: Boolean) =
            "{\"name\":" + q(name.trim()) + ",\"url\":" + q(url.trim()) + (if (oauth) ",\"oauth\":true" else "") + "}"
        fun addCommand(name: String, command: String) = "{\"name\":" + q(name.trim()) + ",\"command\":" + q(command.trim()) + "}"
        fun importScan() = "{}"
        fun import(items: List<ImportItem>) =
            "{\"items\":[" + items.joinToString(",") { "{\"source\":" + q(it.source) + ",\"name\":" + q(it.name) + "}" } + "]}"
    }
}
