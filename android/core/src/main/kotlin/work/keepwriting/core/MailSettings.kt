package work.keepwriting.core

/**
 * 邮箱（dsbot-connectors-mail）：connectors.mail 视图（账号、Google 待登录、发送规则）解码，服务商预设与开通步骤，
 * 添加账号校验，state.mailRequests 邮件发送卡与 mail-* 请求体。复刻 ds-bot 0.3.10 的 connectors/mail/index.js
 *（mailView、endpoints、applyRules、mail_send）、mail/presets.js 与 client.js（MailForm、MailRules、MailSendCard）。
 * 授权码与应用密码只出现在请求体里，不写日志与诊断上报。
 */
object MailSettings {
    /** 邮箱的一行简介（网页端 mailbox.about）。 */
    val ABOUT: String get() = I18n.tr("QQ、163、126、Gmail、iCloud 等 IMAP 邮箱", "QQ, 163, 126, Gmail, iCloud, and other IMAP mail")

    data class Account(val id: String, val address: String, val provider: String, val auth: String, val error: String?)

    /** Google 登录进行中（浏览器授权地址；address 为空时由 Google 账号决定）。 */
    data class GooglePending(val url: String, val address: String?)

    data class Mail(
        val accounts: List<Account>,
        /** 服务端配置了 Google 客户端（可用「用 Google 登录」）。 */
        val google: Boolean,
        val allowedRecipients: List<String>,
        val noDraftFor: List<String>,
        val pending: List<GooglePending>,
    )

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.strings(k: String) =
        ((members[k] as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { (it as? Json.Value.StringValue)?.value }
    private fun Json.Value.Object.objects(k: String) =
        ((members[k] as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { it as? Json.Value.Object }

    fun decode(o: Json.Value.Object?): Mail = Mail(
        accounts = o?.objects("accounts").orEmpty().mapNotNull { a ->
            val id = a.str("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val address = a.str("address")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Account(id, address, a.str("provider") ?: "other", a.str("auth") ?: "password", a.str("error")?.takeIf { it.isNotEmpty() })
        },
        google = (o?.members?.get("google") as? Json.Value.BoolValue)?.value == true,
        allowedRecipients = o?.strings("allowedRecipients").orEmpty(),
        noDraftFor = o?.strings("noDraftFor").orEmpty(),
        pending = o?.objects("pending").orEmpty().mapNotNull { p ->
            val url = p.str("url")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            GooglePending(url, p.str("address")?.takeIf { it.isNotEmpty() })
        },
    )

    /** 邮箱行的状态文案（网页端 mailbox.account）。 */
    fun summary(mail: Mail): String = when {
        mail.accounts.size == 1 -> I18n.tr("已连接：", "Connected as ") + mail.accounts[0].address
        mail.accounts.size > 1 -> I18n.tr("${mail.accounts.size} 个账号", "${mail.accounts.size} accounts")
        mail.pending.isNotEmpty() -> I18n.tr("等待登录…", "Waiting for sign-in…")
        else -> ABOUT
    }

    // ---- 服务商（presets.js 与网页端 MAIL_GUIDES） ----

    data class Guide(val host: String, val open: String, val help: String, val label: String, val steps: List<String>)

    /** 服务商 id 与显示名（网页端 PROVIDERS 顺序）。 */
    val PROVIDERS: List<Pair<String, String>> get() = listOf(
        "qq" to "QQ", "163" to "163", "126" to "126", "gmail" to "Gmail", "icloud" to "iCloud", "other" to I18n.tr("其他", "Other"),
    )

    /** 按地址域名猜服务商（网页端 guessProvider）；猜不出为 null。 */
    fun guessProvider(address: String): String? {
        val domain = address.substringAfter('@', "").lowercase()
        return when (domain) {
            "qq.com", "foxmail.com" -> "qq"
            "163.com" -> "163"
            "126.com" -> "126"
            "gmail.com", "googlemail.com" -> "gmail"
            "icloud.com", "me.com", "mac.com" -> "icloud"
            else -> null
        }
    }

    /** 当前选中的服务商：显式选择优先，其次按地址猜，默认 QQ（网页端 chosen）。 */
    fun chosenProvider(picked: String?, address: String): String = picked?.takeIf { it.isNotEmpty() } ?: guessProvider(address) ?: "qq"

    private const val NETEASE_HELP = "https://help.mail.163.com/faqDetail.do?code=d7a5dc8471cd0c0e8b4b8f4f8e49998b374173cfe9171305fa1ce630d7f67ac2a5feb28b66796d3b"

    private fun authCode() = I18n.tr("授权码", "Authorization code")
    private fun appPassword() = I18n.tr("应用专用密码", "App password")

    private fun netease(host: String) = Guide(
        host, "https://$host", NETEASE_HELP, authCode(),
        listOf(
            I18n.tr("在 $host 登录，进入 设置 → POP3/SMTP/IMAP。", "Sign in at $host and open Settings → POP3/SMTP/IMAP."),
            I18n.tr("开启 IMAP/SMTP 服务，按提示发短信验证。", "Turn on IMAP/SMTP and send the text message it asks for."),
            I18n.tr("把 16 位授权码粘贴到下面。授权码只显示一次。", "Paste the 16-character code below. It is shown only once."),
        ),
    )

    /** 开通 IMAP/SMTP 与生成授权码的步骤；「其他」无。 */
    fun guide(provider: String): Guide? = when (provider) {
        "qq" -> Guide(
            "mail.qq.com", "https://mail.qq.com", "https://help.mail.qq.com/detail/106/985", authCode(),
            listOf(
                I18n.tr("在 mail.qq.com 登录，点右上角头像，进入 设置 → 账号与安全 → 安全设置。", "Sign in at mail.qq.com, click your avatar at the top right, and open Settings → Account and Security → Security Settings."),
                I18n.tr("开启 POP3/IMAP/SMTP 服务，再点「生成授权码」，按提示验证身份。", "Turn on the POP3/IMAP/SMTP service, then click Generate authorization code and verify yourself."),
                I18n.tr("把 16 位授权码粘贴到下面。改了 QQ 密码后授权码会失效。", "Paste the 16-letter code below. Changing your QQ password cancels it."),
            ),
        )
        "163" -> netease("mail.163.com")
        "126" -> netease("mail.126.com")
        "gmail" -> Guide(
            "myaccount.google.com", "https://myaccount.google.com/apppasswords", "https://support.google.com/accounts/answer/185833", appPassword(),
            listOf(
                I18n.tr("先给 Google 账号开启两步验证。", "Turn on 2-Step Verification for your Google Account."),
                I18n.tr("打开「应用专用密码」，新建一个，把 16 位密码粘贴到下面。", "Open App passwords, create one, and paste the 16-character password below."),
                I18n.tr("工作和学校账号不能用应用专用密码。", "Work and school accounts cannot use app passwords."),
            ),
        )
        "icloud" -> Guide(
            "account.apple.com", "https://account.apple.com", "https://support.apple.com/102654", appPassword(),
            listOf(
                I18n.tr("Apple 账户要先开启双重认证。", "Your Apple Account needs two-factor authentication."),
                I18n.tr("在 account.apple.com 登录，进入「登录与安全」→「App 专用密码」。", "Sign in at account.apple.com and open Sign-In and Security → App-Specific Passwords."),
                I18n.tr("生成一个密码，粘贴到下面。", "Generate a password and paste it below."),
            ),
        )
        else -> null
    }

    /** 密码框标签：服务商的授权码或应用专用密码，「其他」为密码或应用专用密码。 */
    fun passwordLabel(provider: String): String = guide(provider)?.label ?: I18n.tr("密码或应用专用密码", "Password or app password")

    private val ADDRESS = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun validAddress(address: String): Boolean = ADDRESS.matches(address.trim())

    /** 添加账号的本地预校验（服务端同规则）；通过时 null。 */
    fun addProblem(address: String, password: String, provider: String, imapHost: String, smtpHost: String): String? = when {
        !validAddress(address) -> I18n.tr("填写邮箱地址", "Enter an email address")
        password.isBlank() -> I18n.tr("填写授权码或应用专用密码", "Enter the authorization code or app password")
        provider == "other" && (imapHost.isBlank() || smtpHost.isBlank()) -> I18n.tr("填写 IMAP 与 SMTP 主机", "Enter the IMAP and SMTP hosts")
        else -> null
    }

    // ---- 发送规则 ----

    /** 一条规则：允许直接发送的收件人或 Bot。 */
    sealed interface RuleRow {
        data class Recipient(val address: String) : RuleRow
        data class Bot(val botId: String, val name: String) : RuleRow
    }

    /** 规则列表（网页端 MailRules）：收件人在前，Bot 名称取 state.bots，找不到时显示 id。 */
    fun rules(mail: Mail, state: Json.Value.Object?): List<RuleRow> {
        val bots = ((state?.members?.get("bots") as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { it as? Json.Value.Object }
        return mail.allowedRecipients.map { RuleRow.Recipient(it) } +
            mail.noDraftFor.map { id -> RuleRow.Bot(id, bots.firstOrNull { it.str("id") == id }?.str("name") ?: id) }
    }

    // ---- 邮件发送卡（state.mailRequests） ----

    data class Attachment(val name: String, val size: Long?)

    data class SendRequest(
        val id: String,
        val sessionId: String?,
        val account: String?,
        val to: String,
        val cc: String,
        val bcc: String,
        val subject: String,
        val body: String,
        val attachments: List<Attachment>,
        val status: String?,
    )

    fun decodeSend(value: Json.Value?): SendRequest? {
        val o = value as? Json.Value.Object ?: return null
        val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return null
        val details = o.objects("attachmentDetails").mapNotNull { a ->
            val name = a.str("name")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Attachment(name, (a.members["size"] as? Json.Value.NumberValue)?.raw?.toDoubleOrNull()?.toLong())
        }
        return SendRequest(
            id = id, sessionId = o.str("sessionId"), account = o.str("account"),
            to = o.str("to").orEmpty(), cc = o.str("cc").orEmpty(), bcc = o.str("bcc").orEmpty(),
            subject = o.str("subject").orEmpty(), body = o.str("body").orEmpty(),
            attachments = details.ifEmpty { o.strings("attachments").map { Attachment(it, null) } },
            status = o.str("status"),
        )
    }

    /** state.mailRequests[botId] 中待处理、且属于当前会话（有 sessionId 时）的发送卡。 */
    fun pendingSends(state: Json.Value.Object?, botId: String, sessionIds: Set<String>): List<SendRequest> =
        (((state?.members?.get("mailRequests") as? Json.Value.Object)?.members?.get(botId) as? Json.Value.Array)?.items ?: emptyList())
            .mapNotNull(::decodeSend)
            .filter { it.status == "pending" && (it.sessionId == null || it.sessionId in sessionIds) }
            .take(ChatBars.LIST_MAX)

    /** 发件地址：按账号 id 查 connectors.mail.accounts，找不到时显示账号 id（网页端 mailFrom）。 */
    fun fromAddress(state: Json.Value.Object?, request: SendRequest): String {
        val mail = decode((state?.members?.get("connectors") as? Json.Value.Object)?.members?.get("mail") as? Json.Value.Object)
        return mail.accounts.firstOrNull { it.id == request.account }?.address ?: request.account.orEmpty()
    }

    /** 人类可读大小（网页端 humanSize）。 */
    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> "${Math.round(bytes / 1024.0)} KB"
        else -> "$bytes B"
    }

    fun attachmentsText(request: SendRequest): String? = request.attachments.takeIf { it.isNotEmpty() }?.joinToString(", ") { a ->
        a.size?.let { "${a.name} (${humanSize(it)})" } ?: a.name
    }

    /** 发送卡上的规则（网页端 RULE_LABELS），随作答一起发送。 */
    enum class Rule(val wire: String) {
        REQUIRE_APPROVAL("require-approval"), ALLOW_RECIPIENT("allow-recipient"), NO_DRAFT("no-draft");

        val label: String get() = when (this) {
            REQUIRE_APPROVAL -> I18n.tr("需要审批", "Needs approval")
            ALLOW_RECIPIENT -> I18n.tr("允许这些收件人", "Allow recipient")
            NO_DRAFT -> I18n.tr("允许此 Bot 直接发送", "Allow this Bot to send directly")
        }
    }

    data class Fields(val to: String, val cc: String, val bcc: String, val subject: String, val body: String)

    /** 发送卡可发送：收件人非空（服务端在 to 为空时保留原收件人，界面直接要求填写）。 */
    fun canSend(fields: Fields): Boolean = fields.to.isNotBlank()

    /** 请求体（mail/index.js endpoints 的参数，源码核对）。 */
    object Requests {
        private fun q(s: String) = JsonString.of(s)
        fun add(address: String, password: String, provider: String, imapHost: String, smtpHost: String) =
            "{\"address\":" + q(address.trim()) + ",\"password\":" + q(password) + ",\"provider\":" + q(provider) +
                ",\"imapHost\":" + q(imapHost.trim()) + ",\"smtpHost\":" + q(smtpHost.trim()) + "}"
        fun addGoogle(address: String) = "{\"address\":" + q(address.trim()) + ",\"google\":true}"
        fun remove(id: String) = "{\"id\":" + q(id) + "}"
        fun removeRecipient(address: String) = "{\"removeRecipient\":" + q(address) + "}"
        fun botDirectOff(botId: String) = "{\"botId\":" + q(botId) + ",\"noDraft\":false}"
        fun signInCode(address: String?, input: String) =
            "{" + (address?.let { "\"address\":" + q(it) + "," } ?: "") + "\"input\":" + q(input.trim()) + "}"
        /** 发送带编辑后的字段与规则；放弃只带规则（网页端 MailSendCard.answer）。 */
        fun sendAnswer(requestId: String, send: Boolean, fields: Fields?, rule: Rule): String {
            val f = if (send && fields != null) {
                ",\"fields\":{\"to\":" + q(fields.to.trim()) + ",\"cc\":" + q(fields.cc.trim()) + ",\"bcc\":" + q(fields.bcc.trim()) +
                    ",\"subject\":" + q(fields.subject.trim()) + ",\"body\":" + q(fields.body) + "}"
            } else ""
            return "{\"requestId\":" + q(requestId) + ",\"choice\":" + q(if (send) "send" else "cancel") + f + ",\"rule\":" + q(rule.wire) + "}"
        }
    }
}
