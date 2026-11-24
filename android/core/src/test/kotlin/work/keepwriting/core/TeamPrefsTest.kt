package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dsbot-prefs-theme：真实 state 的 prefs 解码与缺省、主题配色与深色强调色映射、请求体字面值。 */
class TeamPrefsTest {
    private val real = (Json.parse(checkNotNull(TeamPrefsTest::class.java.getResource("/dsbot/state-full.json")).readText(Charsets.UTF_8)) as Json.Value.Object).members["value"] as Json.Value.Object
    private fun obj(t: String) = Json.parse(t) as Json.Value.Object

    @Test
    fun realPrefs() {
        // 实测：{theme:"paper", width:90, defaultBotModel}；其余取缺省
        val p = assertNotNull(TeamPrefs.decode(real))
        assertEquals("paper", p.theme)
        assertNull(p.accent)
        assertEquals("bubble", p.layout)
        assertEquals("normal", p.motion)
        assertTrue(p.enterToSend)
        assertEquals("dsh-opencode-go/deepseek-v4.1-flash", p.defaultBotModel)
        assertFalse(p.flat || p.quiet)
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
    }
}
