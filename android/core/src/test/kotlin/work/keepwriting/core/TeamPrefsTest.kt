package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dsbot-prefs-theme / dsbot-general-advanced：真实 state 的 prefs 解码与缺省、主题配色、高级项校验与请求体字面值。 */
class TeamPrefsTest {
    private val real = (Json.parse(checkNotNull(TeamPrefsTest::class.java.getResource("/dsbot/state-full.json")).readText(Charsets.UTF_8)) as Json.Value.Object).members["value"] as Json.Value.Object
    private fun obj(t: String) = Json.parse(t) as Json.Value.Object

    @Test
    fun realPrefs() {
        // 实测：{theme:"paper", width:90, defaultBotModel}；其余（含高级项）取缺省
        val p = assertNotNull(TeamPrefs.decode(real))
        assertEquals("paper", p.theme)
        assertNull(p.accent)
        assertEquals("bubble", p.layout)
        assertEquals("normal", p.motion)
        assertTrue(p.enterToSend)
        assertEquals("dsh-opencode-go/deepseek-v4.1-flash", p.defaultBotModel)
        assertFalse(p.flat || p.quiet)
        assertTrue(p.memoryAutoRecall)
        assertEquals(TeamPrefs.DEFAULT_MEMORY_LIMITS, p.memoryLimits)
        assertEquals(12_000, p.memoryLimits.of(TeamPrefs.MemoryScope.GROUP))
        assertEquals(8_000, p.memoryLimits.of(TeamPrefs.MemoryScope.BOT))
        assertEquals(4_000, p.memoryLimits.of(TeamPrefs.MemoryScope.TEAM))
        assertEquals(5, p.backgroundAfterSec)
        assertFalse(p.hasCustomAdvanced)
    }

    @Test
    fun defaultsAndOddValues() {
        assertNull(TeamPrefs.decode(null))
        assertEquals(TeamPrefs.DEFAULT, TeamPrefs.decode(obj("{}")))
        val p = TeamPrefs.decode(obj("""{"prefs":{"theme":"aurora-x","accent":"red","layout":"flat","motion":"quiet","enterToSend":false}}"""))!!
        assertEquals("aurora-x", p.theme) // 未知主题标识原样保留
        assertNull(p.accent) // 非 #rrggbb 视为主题默认
        assertTrue(p.flat && p.quiet)
        assertFalse(p.enterToSend)
        assertEquals("normal", TeamPrefs.decode(obj("""{"prefs":{"motion":"wild","layout":"grid"}}"""))!!.motion)
    }

    @Test
    fun palettes() {
        val paper = TeamPrefs.DEFAULT.copy(theme = "paper")
        assertEquals(0xFAF9F5, TeamPrefs.palette(paper, dark = false).bg)
        assertEquals(0x1F1E1B, TeamPrefs.palette(paper, dark = true).bg)
        assertEquals(0xD97757, TeamPrefs.palette(paper, dark = false).accent)
        // 未知主题回退 deepseek
        assertEquals(0x4D6BFE, TeamPrefs.palette(TeamPrefs.DEFAULT.copy(theme = "nope"), dark = false).accent)
        // 强调色：浅色用首色，深色映射为网页端 darkAccent 的第二色；自定义颜色两种明暗原样
        val rose = paper.copy(accent = "#EC2F6B")
        assertEquals(0xEC2F6B, TeamPrefs.palette(rose, dark = false).accent)
        assertEquals(0xFF5A7A, TeamPrefs.palette(rose, dark = true).accent)
        val custom = paper.copy(accent = "#123456")
        assertEquals(0x123456, TeamPrefs.palette(custom, dark = true).accent)
        assertEquals(listOf("deepseek", "mono", "paper", "moonlight"), TeamPrefs.THEME_IDS)
        assertEquals("Moonlight", TeamPrefs.themeLabel("moonlight"))
        assertEquals("x", TeamPrefs.themeLabel("x"))
        // 深色映射与网页端 darkAccent 一样严格相等：取色器的小写值算自定义色，深色下原样
        assertEquals(0x4D6BFE, TeamPrefs.palette(paper.copy(accent = "#4d6bfe"), dark = true).accent)
        assertEquals(0x6A84FF, TeamPrefs.palette(paper.copy(accent = "#4D6BFE"), dark = true).accent)
    }

    @Test
    fun everyThemeMatchesWeb() {
        // 网页端 themes.js 的 bg、bubble、card、text、user、userText、accent（浅色、深色）逐值锁定
        fun pal(vararg h: Int) = TeamPrefs.Palette(h[0], h[1], h[2], h[3], h[4], h[5], h[6])
        val expected = mapOf(
            "deepseek" to (pal(0xfcfcfc, 0xeeeeee, 0xffffff, 0x141414, 0x070707, 0xfcfcfc, 0x4d6bfe) to pal(0x141414, 0x262626, 0x1f1f1f, 0xf2f2f2, 0xf2f2f2, 0x141414, 0x5b7bff)),
            "mono" to (pal(0xffffff, 0xf0f0f0, 0xffffff, 0x0d0d0d, 0x0d0d0d, 0xffffff, 0x0d0d0d) to pal(0x0f0f0f, 0x242424, 0x1c1c1c, 0xf4f4f4, 0xf4f4f4, 0x0f0f0f, 0xf4f4f4)),
            "paper" to (pal(0xfaf9f5, 0xebe7dc, 0xffffff, 0x1f1e1b, 0x2b2a26, 0xfaf9f5, 0xd97757) to pal(0x1f1e1b, 0x34322d, 0x2a2925, 0xf3f0e8, 0xf3f0e8, 0x1f1e1b, 0xe08a6d)),
            "moonlight" to (pal(0xf7f9fc, 0xe5ebf5, 0xffffff, 0x121826, 0x1e3a8a, 0xffffff, 0x1e86ff) to pal(0x0b1020, 0x1a2340, 0x141c33, 0xe8edf7, 0x3b6fd8, 0xffffff, 0x4f9dff)),
        )
        for ((id, modes) in expected) {
            val p = TeamPrefs.DEFAULT.copy(theme = id)
            assertEquals(modes.first, TeamPrefs.palette(p, dark = false), "$id light")
            assertEquals(modes.second, TeamPrefs.palette(p, dark = true), "$id dark")
        }
        // ACCENT_INKS 的首色与深色第二色
        assertEquals(
            listOf("#4D6BFE" to "#6A84FF", "#141416" to "#F1F2F4", "#2D2D2D" to "#D6D6DA", "#EC2F6B" to "#FF5A7A", "#0AA8D6" to "#2EE6E0", "#FF6900" to "#FF8A2E", "#082DFF" to "#5C78FF"),
            TeamPrefs.ACCENTS.map { it.second to it.third },
        )
    }

    @Test
    fun accentInkFollowsWeb() {
        // 网页端 accentInk：(L+0.05)^2 > 1.05*(L(#141414)+0.05) 用深色墨，否则白色
        assertEquals(TeamPrefs.DARK_INK, TeamPrefs.inkOnAccent(0xD97757)) // Paper 默认：白字对比度只有约 3:1
        assertEquals(TeamPrefs.DARK_INK, TeamPrefs.inkOnAccent(0x0AA8D6))
        assertEquals(TeamPrefs.DARK_INK, TeamPrefs.inkOnAccent(0xF1F2F4))
        assertEquals(0xFFFFFF, TeamPrefs.inkOnAccent(0x4D6BFE))
        assertEquals(0xFFFFFF, TeamPrefs.inkOnAccent(0x141416))
    }

    @Test
    fun accentNames() {
        assertEquals("rose", TeamPrefs.presetAccent("#EC2F6B"))
        assertNull(TeamPrefs.presetAccent("#ec2f6b")) // 自定义（严格相等）
        assertNull(TeamPrefs.presetAccent(null))
        val before = I18n.lang
        I18n.lang = ChatBars.Lang.ZH
        try {
            assertEquals("玫红", TeamPrefs.accentLabel("rose"))
            assertEquals("安静", TeamPrefs.motionLabel("quiet"))
            assertEquals("平铺", TeamPrefs.layoutLabel("flat"))
            assertEquals("气泡", TeamPrefs.layoutLabel("bubble"))
        } finally {
            I18n.lang = before
        }
    }

    @Test
    fun requestBodies() {
        val R = TeamPrefs.Requests
        assertEquals("""{"theme":"moonlight"}""", R.theme("moonlight"))
        assertEquals("""{"accent":"#EC2F6B"}""", R.accent("#EC2F6B"))
        assertEquals("""{"accent":null}""", R.accent(null))
        assertEquals("""{"layout":"flat"}""", R.layout("flat"))
        assertEquals("""{"motion":"quiet"}""", R.motion("quiet"))
        assertEquals("""{"enterToSend":false}""", R.enterToSend(false))
        assertEquals("""{"defaultBotModel":null}""", R.defaultBotModel(null))
        assertEquals("""{"defaultBotModel":"glm/x"}""", R.defaultBotModel("glm/x"))
        assertEquals("""{"memoryAutoRecall":false}""", R.memoryAutoRecall(false))
        assertEquals("""{"memoryAutoRecall":true}""", R.memoryAutoRecall(true))
        assertEquals("""{"memoryLimits":{"group":16000}}""", R.memoryLimit(TeamPrefs.MemoryScope.GROUP, 16_000))
        assertEquals("""{"memoryLimits":{"bot":1000}}""", R.memoryLimit(TeamPrefs.MemoryScope.BOT, 1_000))
        assertEquals("""{"memoryLimits":{"team":200000}}""", R.memoryLimit(TeamPrefs.MemoryScope.TEAM, 200_000))
        assertEquals("""{"backgroundAfterSec":0}""", R.backgroundAfterSec(0))
        assertEquals("""{"backgroundAfterSec":15}""", R.backgroundAfterSec(15))
        assertEquals("""{"backgroundAfterSec":600}""", R.backgroundAfterSec(600))
        assertEquals("""{"backgroundAfterSec":null}""", R.backgroundAfterSec(null))
        assertEquals(
            """{"memoryAutoRecall":true,"memoryLimits":null,"backgroundAfterSec":null}""",
            R.resetAdvanced(),
        )
        // 防御性拦截越界构造（避免触发服务端 memoryLimits 空过滤静默清空）
        assertFailsWith<IllegalArgumentException> { R.memoryLimit(TeamPrefs.MemoryScope.GROUP, 999) }
        assertFailsWith<IllegalArgumentException> { R.memoryLimit(TeamPrefs.MemoryScope.TEAM, 200_001) }
        assertFailsWith<IllegalArgumentException> { R.backgroundAfterSec(-1) }
        assertFailsWith<IllegalArgumentException> { R.backgroundAfterSec(601) }
    }

    @Test
    fun advancedDecodeAndDegrade() {
        val custom = TeamPrefs.decode(
            obj("""{"prefs":{"memoryAutoRecall":false,"memoryLimits":{"group":16000,"bot":1000,"team":200000},"backgroundAfterSec":0}}"""),
        )!!
        assertFalse(custom.memoryAutoRecall)
        assertEquals(TeamPrefs.MemoryLimits(group = 16_000, bot = 1_000, team = 200_000), custom.memoryLimits)
        assertFalse(custom.memoryLimits.isDefault)
        assertEquals(0, custom.backgroundAfterSec)
        assertTrue(custom.hasCustomAdvanced)

        // 单项自定义也触发 hasCustomAdvanced，科学计数法/整值浮点按整数解析
        val singleLimit = TeamPrefs.decode(obj("""{"prefs":{"memoryLimits":{"bot":1e4}}}"""))!!
        assertTrue(singleLimit.memoryAutoRecall)
        assertEquals(TeamPrefs.MemoryLimits(group = 12_000, bot = 10_000, team = 4_000), singleLimit.memoryLimits)
        assertEquals(5, singleLimit.backgroundAfterSec)
        assertTrue(singleLimit.hasCustomAdvanced)

        val maxBg = TeamPrefs.decode(obj("""{"prefs":{"backgroundAfterSec":600}}"""))!!
        assertEquals(600, maxBg.backgroundAfterSec)
        assertTrue(maxBg.hasCustomAdvanced)

        val recallOnly = TeamPrefs.decode(obj("""{"prefs":{"memoryAutoRecall":false}}"""))!!
        assertFalse(recallOnly.memoryAutoRecall)
        assertTrue(recallOnly.hasCustomAdvanced)

        // 越界、小数、错误类型均降级为默认值且不崩溃
        val bad = TeamPrefs.decode(
            obj("""{"prefs":{"memoryAutoRecall":"false","memoryLimits":{"group":999,"bot":200001,"team":4000.5},"backgroundAfterSec":601}}"""),
        )!!
        assertTrue(bad.memoryAutoRecall)
        assertEquals(TeamPrefs.DEFAULT_MEMORY_LIMITS, bad.memoryLimits)
        assertEquals(5, bad.backgroundAfterSec)
        assertFalse(bad.hasCustomAdvanced)

        val bad2 = TeamPrefs.decode(
            obj("""{"prefs":{"memoryLimits":"not-an-object","backgroundAfterSec":-1}}"""),
        )!!
        assertEquals(TeamPrefs.DEFAULT_MEMORY_LIMITS, bad2.memoryLimits)
        assertEquals(5, bad2.backgroundAfterSec)

        val badDecimal = TeamPrefs.decode(obj("""{"prefs":{"backgroundAfterSec":1.5}}"""))!!
        assertEquals(5, badDecimal.backgroundAfterSec)
    }

    @Test
    fun advancedNumberValidationAndLabels() {
        assertEquals(TeamPrefs.NumberParse.Valid(1_000), TeamPrefs.parseMemoryLimit("1000"))
        assertEquals(TeamPrefs.NumberParse.Valid(16_000), TeamPrefs.parseMemoryLimit(" 16000 "))
        assertEquals(TeamPrefs.NumberParse.Valid(200_000), TeamPrefs.parseMemoryLimit("200000"))
        for (bad in listOf("", "   ", "0", "999", "200001", "-1000", "+1000", "12000.5", "12000.0", "1e4", "abc", "999999999999")) {
            assertIs<TeamPrefs.NumberParse.Invalid>(TeamPrefs.parseMemoryLimit(bad), "memoryLimit should reject '$bad'")
        }

        assertEquals(TeamPrefs.NumberParse.Valid(0), TeamPrefs.parseBackgroundAfterSec("0"))
        assertEquals(TeamPrefs.NumberParse.Valid(15), TeamPrefs.parseBackgroundAfterSec(" 15 "))
        assertEquals(TeamPrefs.NumberParse.Valid(600), TeamPrefs.parseBackgroundAfterSec("600"))
        for (bad in listOf("", "   ", "-1", "601", "+5", "1.5", "5.0", "1e2", "abc", "999999999999")) {
            assertIs<TeamPrefs.NumberParse.Invalid>(TeamPrefs.parseBackgroundAfterSec(bad), "backgroundAfterSec should reject '$bad'")
        }

        val before = I18n.lang
        try {
            I18n.lang = ChatBars.Lang.ZH
            assertEquals("群记忆容量", TeamPrefs.memoryScopeLabel(TeamPrefs.MemoryScope.GROUP))
            assertEquals("Bot 记忆容量", TeamPrefs.memoryScopeLabel(TeamPrefs.MemoryScope.BOT))
            assertEquals("团队记忆容量", TeamPrefs.memoryScopeLabel(TeamPrefs.MemoryScope.TEAM))
            assertTrue((TeamPrefs.parseMemoryLimit("500") as TeamPrefs.NumberParse.Invalid).message.contains("1000"))
            assertTrue((TeamPrefs.parseBackgroundAfterSec("601") as TeamPrefs.NumberParse.Invalid).message.contains("600"))

            I18n.lang = ChatBars.Lang.EN
            assertEquals("Group memory size", TeamPrefs.memoryScopeLabel(TeamPrefs.MemoryScope.GROUP))
            assertEquals("Bot memory size", TeamPrefs.memoryScopeLabel(TeamPrefs.MemoryScope.BOT))
            assertEquals("Team memory size", TeamPrefs.memoryScopeLabel(TeamPrefs.MemoryScope.TEAM))
            assertTrue((TeamPrefs.parseMemoryLimit("500") as TeamPrefs.NumberParse.Invalid).message.contains("200000"))
            assertTrue((TeamPrefs.parseBackgroundAfterSec("601") as TeamPrefs.NumberParse.Invalid).message.contains("600"))
        } finally {
            I18n.lang = before
        }
    }
}
