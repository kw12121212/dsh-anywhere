package work.keepwriting.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.keepwriting.core.MailSettings.Rule

class MailSettingsTest {
    @BeforeEach
    fun resetLang() {
        I18n.source = null
        I18n.lang = ChatBars.Lang.ZH
    }

    /** 0.3.10 mailView 形态（mail/index.js mailView）与 state.mailRequests（mail_send）。 */
    private val state = Json.parse(
        """
        {
          "bots": [ { "id": "b1", "name": "小助手" } ],
          "connectors": { "connectors": [], "mail": {
            "accounts": [
              { "id": "m1", "address": "a@qq.com", "provider": "qq", "auth": "password", "state": "ready" },
              { "id": "g1", "address": "me@gmail.com", "provider": "gmail", "auth": "google", "state": "error", "error": "Sign in again" },
              { "address": "no-id@x.com" }
            ],
            "google": true,
            "allowedRecipients": ["boss@x.com"],
            "noDraftFor": ["b1", "gone"],
            "pending": [ { "kind": "browser", "url": "https://accounts.google.com/o/oauth2/auth?state=z", "address": "me@gmail.com" }, { "kind": "browser" } ]
          } },
          "mailRequests": { "b1": [
            { "id": "r1", "sessionId": "s1", "account": "m1", "to": "x@y.com", "cc": "c@y.com", "bcc": "", "subject": "Hi", "body": "Body\nline",
              "attachments": ["a.txt", "b.bin"], "attachmentDetails": [ { "name": "a.txt", "size": 500 }, { "name": "b.bin" } ], "status": "pending" },
            { "id": "r2", "sessionId": "s1", "account": "zz", "to": "x@y.com", "subject": "old", "attachments": ["c.txt"], "status": "sent" }
          ] }
        }
        """.trimIndent(),
    ) as Json.Value.Object

    private val mail get() = ConnectorSettings.view(state)!!.mail

    @Test
    fun `decodes accounts pending and rules`() {
        assertEquals(listOf("m1", "g1"), mail.accounts.map { it.id })
        assertEquals("Sign in again", mail.accounts[1].error)
        assertEquals("google", mail.accounts[1].auth)
        assertTrue(mail.google)
        assertEquals(listOf(MailSettings.GooglePending("https://accounts.google.com/o/oauth2/auth?state=z", "me@gmail.com")), mail.pending)
        assertEquals(
            listOf(MailSettings.RuleRow.Recipient("boss@x.com"), MailSettings.RuleRow.Bot("b1", "小助手"), MailSettings.RuleRow.Bot("gone", "gone")),
            MailSettings.rules(mail, state),
        )
        val empty = MailSettings.decode(null)
        assertTrue(empty.accounts.isEmpty() && !empty.google && empty.pending.isEmpty())
    }

    @Test
    fun `summary text`() {
        assertEquals("2 个账号", MailSettings.summary(mail))
        assertEquals("已连接：a@qq.com", MailSettings.summary(mail.copy(accounts = mail.accounts.take(1))))
        assertEquals("等待登录…", MailSettings.summary(mail.copy(accounts = emptyList())))
        assertEquals(MailSettings.ABOUT, MailSettings.summary(MailSettings.decode(null)))
    }

    @Test
    fun `provider guessing and guides`() {
        assertEquals("qq", MailSettings.guessProvider("a@foxmail.com"))
        assertEquals("163", MailSettings.guessProvider("a@163.com"))
        assertEquals("126", MailSettings.guessProvider("A@126.COM"))
        assertEquals("gmail", MailSettings.guessProvider("a@googlemail.com"))
        assertEquals("icloud", MailSettings.guessProvider("a@me.com"))
        assertNull(MailSettings.guessProvider("a@corp.example"))
        assertNull(MailSettings.guessProvider("no-at"))
        assertEquals("qq", MailSettings.chosenProvider(null, "a@corp.example"))
        assertEquals("gmail", MailSettings.chosenProvider("", "a@gmail.com"))
        assertEquals("other", MailSettings.chosenProvider("other", "a@gmail.com"))
        assertEquals(listOf("qq", "163", "126", "gmail", "icloud", "other"), MailSettings.PROVIDERS.map { it.first })
        assertEquals("授权码", MailSettings.passwordLabel("qq"))
        assertEquals("应用专用密码", MailSettings.passwordLabel("icloud"))
        assertEquals("密码或应用专用密码", MailSettings.passwordLabel("other"))
        assertEquals("mail.126.com", MailSettings.guide("126")!!.host)
        assertTrue(MailSettings.guide("126")!!.steps[0].contains("mail.126.com"))
        assertEquals(3, MailSettings.guide("gmail")!!.steps.size)
        assertNull(MailSettings.guide("other"))
    }

    @Test
    fun `add validation mirrors mail-account-add`() {
        assertEquals("填写邮箱地址", MailSettings.addProblem("bad", "p", "qq", "", ""))
        assertEquals("填写邮箱地址", MailSettings.addProblem("a@b", "p", "qq", "", ""))
        assertEquals("填写授权码或应用专用密码", MailSettings.addProblem("a@qq.com", " ", "qq", "", ""))
        assertEquals("填写 IMAP 与 SMTP 主机", MailSettings.addProblem("a@corp.com", "p", "other", "imap.corp.com", ""))
        assertNull(MailSettings.addProblem(" a@qq.com ", "p", "qq", "", ""))
        assertNull(MailSettings.addProblem("a@corp.com", "p", "other", "imap.corp.com", "smtp.corp.com"))
    }

    @Test
    fun `send requests decode with attachments and from address`() {
        val pending = MailSettings.pendingSends(state, "b1", setOf("s1"))
        assertEquals(listOf("r1"), pending.map { it.id })
        val r = pending[0]
        assertEquals("Body\nline", r.body)
        assertEquals("a.txt (500 B), b.bin", MailSettings.attachmentsText(r))
        assertEquals("a@qq.com", MailSettings.fromAddress(state, r))
        val sent = MailSettings.decodeSend((((state.members["mailRequests"] as Json.Value.Object).members["b1"]) as Json.Value.Array).items[1])!!
        assertEquals("zz", MailSettings.fromAddress(state, sent)) // 账号已删：显示账号 id
        assertEquals(listOf(MailSettings.Attachment("c.txt", null)), sent.attachments) // 无 attachmentDetails 时用名称
        assertTrue(MailSettings.pendingSends(state, "b1", setOf("other")).isEmpty())
        assertTrue(MailSettings.pendingSends(state, "nobody", setOf("s1")).isEmpty())
        assertNull(MailSettings.attachmentsText(sent.copy(attachments = emptyList())))
    }

    @Test
    fun `human size like web`() {
        assertEquals("999 B", MailSettings.humanSize(999))
        assertEquals("2 KB", MailSettings.humanSize(2048))
        assertEquals("1.5 MB", MailSettings.humanSize(1572864))
    }

    @Test
    fun `rules and send guard`() {
        assertEquals("require-approval", Rule.REQUIRE_APPROVAL.wire)
        assertEquals("允许此 Bot 直接发送", Rule.NO_DRAFT.label)
        assertFalse(MailSettings.canSend(MailSettings.Fields(" ", "", "", "s", "b")))
        assertTrue(MailSettings.canSend(MailSettings.Fields("a@b.com", "", "", "", "")))
    }

    @Test
    fun `request bodies match upstream endpoints`() {
        val R = MailSettings.Requests
        assertEquals(
            """{"address":"a@qq.com","password":"p w","provider":"qq","imapHost":"","smtpHost":""}""",
            R.add(" a@qq.com ", "p w", "qq", "", ""),
        )
        assertEquals("""{"address":"","google":true}""", R.addGoogle(""))
        assertEquals("""{"id":"m1"}""", R.remove("m1"))
        assertEquals("""{"removeRecipient":"boss@x.com"}""", R.removeRecipient("boss@x.com"))
        assertEquals("""{"botId":"b1","noDraft":false}""", R.botDirectOff("b1"))
        assertEquals("""{"address":"me@gmail.com","input":"http://127.0.0.1:1/callback?code=c"}""", R.signInCode("me@gmail.com", " http://127.0.0.1:1/callback?code=c "))
        assertEquals("""{"input":"code"}""", R.signInCode(null, "code"))
        val f = MailSettings.Fields(" x@y.com ", "", "", " Hi ", "Body\n")
        assertEquals(
            """{"requestId":"r1","choice":"send","fields":{"to":"x@y.com","cc":"","bcc":"","subject":"Hi","body":"Body\n"},"rule":"allow-recipient"}""",
            R.sendAnswer("r1", true, f, Rule.ALLOW_RECIPIENT),
        )
        assertEquals("""{"requestId":"r1","choice":"cancel","rule":"require-approval"}""", R.sendAnswer("r1", false, f, Rule.REQUIRE_APPROVAL))
    }
}
