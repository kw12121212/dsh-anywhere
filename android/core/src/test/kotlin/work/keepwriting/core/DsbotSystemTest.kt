package work.keepwriting.core

import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 关于 DS Bot 与诊断纯逻辑测试（dsbot-system-about）：覆盖 update-check 视图解码（含真实脱敏夹具 update-check.json）、
 * DS-Bot 行与 dsh 行全部状态文案与按钮分支（对齐上游 botHint/dshHint/showsDshRow）、基准比对、安装中轮询判定、
 * repo-stars 与紧凑显示、diagnostics 报告与条目解码、条目副标题与全部请求体字面值。
 */
class DsbotSystemTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = 1_795_361_700_000L

    @AfterTest
    fun tearDown() {
        I18n.lang = ChatBars.Lang.ZH
    }

    private fun parse(json: String) = Json.parse(json)!!

    private fun bot(
        phase: String = "idle", source: String = "registry", available: Boolean = false, enabled: Boolean = true,
        latest: String? = "0.3.10", checkedAt: Long = now - 30_000, installed: String? = null, error: String? = null,
    ) = DsbotSystem.BotUpdate("0.3.10", latest, source, null, null, enabled, checkedAt, phase, installed, error, available)

    private fun dsh(
        current: String? = "0.2.2-rc.3", verified: String? = null, kind: String = "npm", blockedBy: String? = null,
        phase: String = "idle", available: Boolean = false, installed: String? = null, error: String? = null,
    ) = DsbotSystem.DshUpdate(current, verified, kind, blockedBy, phase, installed, error, available)

    @Test
    fun decodesRealUpdateCheckFixture() {
        val text = javaClass.getResource("/dsbot/update-check.json")!!.readText()
        val value = (Json.parse(text) as Json.Value.Object).members["value"]
        val u = DsbotSystem.decodeUpdate(value)
        assertNotNull(u)
        assertEquals("0.3.10", u.bot.current)
        assertEquals("registry", u.bot.source)
        assertEquals("https://github.com/Fei2411/DS-Bot", u.bot.repoUrl)
        assertEquals("https://github.com/Fei2411/DS-Bot/releases", u.bot.notesUrl)
        assertEquals(1795361615075L, u.bot.checkedAt)
        assertFalse(u.bot.available)
        assertEquals("0.2.2-rc.3", u.dsh?.current)
        assertTrue(DsbotSystem.showsDsh(u.dsh))
        assertEquals(DsbotSystem.Baseline.MATCHED, DsbotSystem.baseline(u))
        assertFalse(DsbotSystem.installing(u))
    }

    @Test
    fun decodeDegradesMissingAndMalformedFields() {
        assertNull(DsbotSystem.decodeUpdate(parse("[]")))
        assertNull(DsbotSystem.decodeUpdate(null))
        val u = DsbotSystem.decodeUpdate(parse("""{"current":"0.3.10","repoUrl":"javascript:alert(1)","notesUrl":"","enabled":"yes","dsh":null}"""))!!
        assertNull(u.bot.repoUrl, "非 http(s) 链接丢弃")
        assertNull(u.bot.notesUrl)
        assertTrue(u.bot.enabled, "enabled 只在明确 false 时为关闭")
        assertEquals("unknown", u.bot.source)
        assertEquals("idle", u.bot.phase)
        assertEquals(0L, u.bot.checkedAt)
        assertNull(u.dsh)
        assertFalse(DsbotSystem.showsDsh(u.dsh))
        assertEquals(DsbotSystem.Baseline.UNKNOWN, DsbotSystem.baseline(u))
        assertEquals(DsbotSystem.Baseline.UNKNOWN, DsbotSystem.baseline(null))
        // dsh 子视图缺 kind 时不当作 npm：不提供在电脑上 npm 更新（上游 showsDshRow 只认 kind=npm）
        val noKind = DsbotSystem.decodeDsh(parse("""{"current":"0.2.2","verified":"0.2.3","available":true}"""))!!
        assertEquals("unknown", noKind.kind)
        assertEquals(DsbotSystem.Button.NONE, DsbotSystem.dshRow(noKind).button)
        assertFalse(DsbotSystem.isWebLink("intent://scan#Intent;scheme=zxing;end"))
        assertFalse(DsbotSystem.isWebLink("file:///etc/hosts"))
        assertTrue(DsbotSystem.isWebLink("https://github.com/Fei2411/DS-Bot"))
        assertNull(DsbotSystem.decodeDsh(parse("\"x\"")))
    }

    @Test
    fun baselineMismatchWhenEitherVersionDiffers() {
        val u = DsbotSystem.Update(bot().copy(current = "0.4.0"), dsh())
        assertEquals(DsbotSystem.Baseline.MISMATCHED, DsbotSystem.baseline(u))
        assertEquals(DsbotSystem.Baseline.MISMATCHED, DsbotSystem.baseline(DsbotSystem.Update(bot(), dsh(current = "0.2.3"))))
        assertTrue(DsbotSystem.baselineText(DsbotSystem.Baseline.MISMATCHED).contains("不一致"))
        assertTrue(DsbotSystem.baselineText(DsbotSystem.Baseline.MATCHED).contains("一致"))
        assertTrue(DsbotSystem.baselineText(DsbotSystem.Baseline.UNKNOWN).contains("无法判断"))
    }

    @Test
    fun botRowStatesAndButtons() {
        fun row(b: DsbotSystem.BotUpdate) = DsbotSystem.botRow(b, now, zone)
        assertEquals(DsbotSystem.Row("正在更新 DS-Bot…", DsbotSystem.Button.INSTALLING), row(bot(phase = "installing", available = true)))
        assertEquals(DsbotSystem.Row("重启 DSH 后开始使用 DS-Bot 0.3.11。", DsbotSystem.Button.NONE), row(bot(phase = "installed", installed = "0.3.11")))
        assertEquals("重启 DSH 后开始使用 DS-Bot 0.3.11。", row(bot(phase = "installed", latest = "0.3.11")).hint, "installed 缺失时用 latest")
        assertEquals(DsbotSystem.Row("DS-Bot 0.3.10 已安装。", DsbotSystem.Button.NONE), row(bot(phase = "updated", installed = "0.3.10")))
        assertEquals(DsbotSystem.Row("更新未完成：npm exited 1", DsbotSystem.Button.RETRY), row(bot(phase = "failed", error = "npm exited 1", available = true)))
        assertEquals(DsbotSystem.Button.CHECK, row(bot(phase = "failed", error = "x", available = false)).button, "失败且已无可用更新时回到检查")
        assertEquals(DsbotSystem.Row("DS-Bot 0.3.11 可更新", DsbotSystem.Button.INSTALL), row(bot(latest = "0.3.11", available = true)))
        assertEquals(DsbotSystem.Row("随 DS Bot 桌面应用更新。", DsbotSystem.Button.NONE), row(bot(source = "app")))
        assertEquals(DsbotSystem.Row("更新检查已关闭。", DsbotSystem.Button.CHECK_DISABLED), row(bot(enabled = false)))
        assertEquals(DsbotSystem.Row("从本地目录安装，请在那里更新。", DsbotSystem.Button.CHECK), row(bot(source = "path", latest = null)))
        assertEquals(DsbotSystem.Row(null, DsbotSystem.Button.CHECK), row(bot(checkedAt = 0)))
        assertEquals("此安装方式不提供更新。", row(bot(source = "tarball", latest = null)).hint)
        assertEquals("已是最新 · 检查于 刚刚", row(bot()).hint)
        assertEquals("无法检查更新。 · 检查于 5 分钟前", row(bot(latest = null, checkedAt = now - 5 * 60_000)).hint)
        // 服务端时钟比手机快几秒：刚检查完仍显示「刚刚」而不是绝对日期（模拟器验收发现）
        assertEquals("已是最新 · 检查于 刚刚", row(bot(checkedAt = now + 4_000)).hint)
        assertEquals("刚刚", DsbotSystem.timeText(now + 90_000, now, zone))
    }

    @Test
    fun botRowEnglish() {
        I18n.lang = ChatBars.Lang.EN
        assertEquals("Up to date · Checked just now", DsbotSystem.botRow(bot(), now, zone).hint)
        assertEquals("DS Bot 0.3.11 is available", DsbotSystem.botRow(bot(latest = "0.3.11", available = true), now, zone).hint)
    }

    @Test
    fun dshRowStatesAndButtons() {
        assertEquals(DsbotSystem.Row("版本 0.2.2-rc.3", DsbotSystem.Button.NONE), DsbotSystem.dshRow(dsh()))
        assertEquals(
            DsbotSystem.Row("DSH 0.2.3 可更新", DsbotSystem.Button.INSTALL),
            DsbotSystem.dshRow(dsh(verified = "0.2.3", available = true)),
        )
        assertEquals(
            DsbotSystem.Row("请先把 DS-Bot 更新到 0.3.12。", DsbotSystem.Button.NONE),
            DsbotSystem.dshRow(dsh(verified = "0.2.3", blockedBy = "0.3.12", available = false)),
        )
        assertEquals(
            DsbotSystem.Row("DSH 0.2.3 随桌面应用更新（当前 0.2.2-rc.3）", DsbotSystem.Button.NONE),
            DsbotSystem.dshRow(dsh(verified = "0.2.3", kind = "app", available = true)),
        )
        assertEquals(DsbotSystem.Row("正在更新 DSH…", DsbotSystem.Button.INSTALLING), DsbotSystem.dshRow(dsh(verified = "0.2.3", phase = "installing")))
        assertEquals(
            DsbotSystem.Row("重启 DSH 后开始使用 DSH 0.2.3。", DsbotSystem.Button.NONE),
            DsbotSystem.dshRow(dsh(verified = "0.2.3", phase = "installed", installed = "0.2.3")),
        )
        assertEquals(
            DsbotSystem.Row("更新未完成：npm exited 1", DsbotSystem.Button.RETRY),
            DsbotSystem.dshRow(dsh(verified = "0.2.3", phase = "failed", error = "npm exited 1")),
        )
        // 已验证版本与当前相同（含 v 前缀与构建元数据）时不提供更新
        assertEquals(DsbotSystem.Button.NONE, DsbotSystem.dshRow(dsh(current = "0.2.3", verified = "v0.2.3+build", available = true)).button)
        // 有新版本但不可用且无阻塞（如 phase=idle、available=false）：不显示按钮
        assertEquals(DsbotSystem.Button.NONE, DsbotSystem.dshRow(dsh(verified = "0.2.3", available = false)).button)
        assertFalse(DsbotSystem.showsDsh(dsh(current = null)))
        // 随桌面应用更新但已是已验证版本：只显示版本
        assertEquals(DsbotSystem.Row("版本 0.2.3", DsbotSystem.Button.NONE), DsbotSystem.dshRow(dsh(current = "0.2.3", verified = "0.2.3", kind = "app")))
    }

    @Test
    fun installingPollsWhileEitherInstalls() {
        assertTrue(DsbotSystem.installing(DsbotSystem.Update(bot(phase = "installing"), null)))
        assertTrue(DsbotSystem.installing(DsbotSystem.Update(bot(), dsh(phase = "installing"))))
        assertFalse(DsbotSystem.installing(DsbotSystem.Update(bot(phase = "installed"), dsh(phase = "failed"))))
        assertFalse(DsbotSystem.installing(null))
        assertEquals(2_000L, DsbotSystem.POLL_MS)
    }

    @Test
    fun starsDecodeAndCompactText() {
        assertEquals(1234, DsbotSystem.stars(parse("1234")))
        assertNull(DsbotSystem.stars(parse("null")))
        assertNull(DsbotSystem.stars(parse("-3")))
        assertNull(DsbotSystem.stars(parse("\"12\"")))
        assertEquals("987", DsbotSystem.starText(987))
        assertEquals("1234", DsbotSystem.starText(1234))
        assertEquals("1.2万", DsbotSystem.starText(12_345))
        assertEquals("3万", DsbotSystem.starText(30_000))
        I18n.lang = ChatBars.Lang.EN
        assertEquals("987", DsbotSystem.starText(987))
        assertEquals("1.2K", DsbotSystem.starText(1234))
        assertEquals("12K", DsbotSystem.starText(12_000))
        assertEquals("1.5M", DsbotSystem.starText(1_500_000))
    }

    @Test
    fun decodesDiagnosticsReport() {
        val r = DsbotSystem.decodeReport(
            parse(
                """{"facts":{"botVersion":"0.3.10","dshVersion":"0.2.2-rc.3","os":"linux 6.6 x64","node":"v24.1.0","team":"3 Bots, 1 Main, 1 group chat","readOnly":"yes, another dsh holds the team"},
                "entries":[{"time":${now - 30_000},"level":"error","source":"turn","text":"429 rate limited","bot":"Writer","where":"group chat","code":"rate_limit","status":429},
                {"time":${now - 120_000},"level":"warn","source":"host","text":"update check failed"},
                {"level":"error","text":"no time"},{"time":1,"source":"host"}],
                "total":7,"markdown":"| | |","issuesUrl":"https://github.com/Fei2411/DS-Bot/issues/new"}""",
            ),
        )!!
        assertEquals(7, r.total)
        assertEquals(2, r.entries.size, "缺 time 或 text 的条目丢弃")
        val first = r.entries[0]
        assertEquals("Writer", first.bot)
        assertEquals("rate_limit 429", DsbotSystem.codeTag(first))
        assertNull(DsbotSystem.codeTag(r.entries[1]))
        assertEquals("warn", r.entries[1].level)
        assertEquals("刚刚 · Writer · group chat · 模型调用", DsbotSystem.entryMeta(first, now, zone))
        assertEquals("2 分钟前 · DS-Bot", DsbotSystem.entryMeta(r.entries[1], now, zone))
        val facts = DsbotSystem.factLines(r.facts)
        assertEquals(listOf("DS-Bot", "DSH", "系统", "Node", "团队", "只读"), facts.map { it.first })
        assertEquals("linux 6.6 x64", facts[2].second)
        assertEquals("浏览器", DsbotSystem.sourceLabel("browser"))
        // 模型调用条目：已知失败码显示友好文案（上游 failureLabel），未知码显示原文；其他来源始终原文
        assertEquals("429 rate limited", DsbotSystem.entrySummary(first), "rate_limit 不是上游已知码")
        assertEquals("对模型的请求过多", DsbotSystem.entrySummary(first.copy(code = "RATE_LIMIT")))
        assertEquals("update check failed", DsbotSystem.entrySummary(r.entries[1].copy(code = "RATE_LIMIT")))
        assertEquals("API key 被拒绝", DsbotSystem.entrySummary(first.copy(code = "AUTH", text = "")))
        assertEquals("TIMEOUT_X", DsbotSystem.entrySummary(first.copy(code = "TIMEOUT_X", text = "")))
        I18n.lang = ChatBars.Lang.EN
        assertEquals("The model took too long to answer", DsbotSystem.entrySummary(first.copy(code = "TIMEOUT")))
        I18n.lang = ChatBars.Lang.ZH
    }

    @Test
    fun diagnosticsReportDegrades() {
        assertNull(DsbotSystem.decodeReport(parse("true")))
        val empty = DsbotSystem.decodeReport(parse("{}"))!!
        assertEquals(0, empty.total)
        assertTrue(empty.entries.isEmpty())
        assertTrue(DsbotSystem.factLines(empty.facts).isEmpty())
        // total 小于实际条目数时不低于条目数
        val r = DsbotSystem.decodeReport(parse("""{"entries":[{"time":1,"text":"a"}],"total":0}"""))!!
        assertEquals(1, r.total)
        assertEquals("line 1\n    at x", DsbotSystem.logText(parse("\"line 1\\n    at x\"")))
        assertEquals("", DsbotSystem.logText(parse("\"\"")))
        assertNull(DsbotSystem.logText(parse("{}")))
    }

    @Test
    fun requestBodies() {
        val q = DsbotSystem.Requests
        assertEquals("""{"fresh":true}""", q.check(true))
        assertEquals("""{"fresh":false}""", q.check(false))
        assertEquals("{}", q.install())
        assertEquals("{}", q.dshInstall())
        assertEquals("{}", q.stars())
        assertEquals("{}", q.log())
        assertEquals("{}", q.clear())
        assertEquals("""{"client":{"surface":"android","language":"zh"}}""", q.diagnostics(ChatBars.Lang.ZH))
        assertEquals("""{"client":{"surface":"android","language":"en"}}""", q.diagnostics(ChatBars.Lang.EN))
        assertNotNull(Json.parse(q.diagnostics(ChatBars.Lang.EN)))
        assertEquals(setOf("update-check", "repo-stars"), DsbotSystem.SLOW_ENDPOINTS)
    }
}
