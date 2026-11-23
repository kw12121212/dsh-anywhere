package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** dsbot-phone-prefs：外观/语言偏好解析与界面文案语言切换。 */
class PhonePrefsTest {
    private fun <T> inEnglish(block: () -> T): T {
        val before = I18n.lang
        I18n.lang = ChatBars.Lang.EN
        try {
            return block()
        } finally {
            I18n.lang = before
        }
    }

    @Test
    fun parseWithFallbackToSystem() {
        assertEquals(PhonePrefs.Appearance.DARK, PhonePrefs.appearance("dark"))
        assertEquals(PhonePrefs.Appearance.LIGHT, PhonePrefs.appearance("light"))
        assertEquals(PhonePrefs.Appearance.SYSTEM, PhonePrefs.appearance(null))
        assertEquals(PhonePrefs.Appearance.SYSTEM, PhonePrefs.appearance("neon"))
        assertEquals(PhonePrefs.Language.EN, PhonePrefs.language("en"))
        assertEquals(PhonePrefs.Language.ZH, PhonePrefs.language("zh"))
        assertEquals(PhonePrefs.Language.SYSTEM, PhonePrefs.language("fr"))
        assertEquals(PhonePrefs.Language.SYSTEM, PhonePrefs.language(""))
        // 存储值往返
        for (a in PhonePrefs.Appearance.entries) assertEquals(a, PhonePrefs.appearance(a.stored))
        for (l in PhonePrefs.Language.entries) assertEquals(l, PhonePrefs.language(l.stored))
    }

    @Test
    fun resolveLanguageFromSystem() {
        assertEquals(ChatBars.Lang.ZH, PhonePrefs.resolve(PhonePrefs.Language.SYSTEM, "zh-CN"))
        assertEquals(ChatBars.Lang.ZH, PhonePrefs.resolve(PhonePrefs.Language.SYSTEM, "zh-Hant-TW"))
        assertEquals(ChatBars.Lang.EN, PhonePrefs.resolve(PhonePrefs.Language.SYSTEM, "en-US"))
        assertEquals(ChatBars.Lang.EN, PhonePrefs.resolve(PhonePrefs.Language.SYSTEM, "ja"))
        assertEquals(ChatBars.Lang.EN, PhonePrefs.resolve(PhonePrefs.Language.SYSTEM, null))
        assertEquals(ChatBars.Lang.ZH, PhonePrefs.resolve(PhonePrefs.Language.ZH, "en-US"))
        assertEquals(ChatBars.Lang.EN, PhonePrefs.resolve(PhonePrefs.Language.EN, "zh-CN"))
    }

    @Test
    fun darkResolution() {
        assertTrue(PhonePrefs.isDark(PhonePrefs.Appearance.SYSTEM, systemDark = true))
        assertFalse(PhonePrefs.isDark(PhonePrefs.Appearance.SYSTEM, systemDark = false))
        assertTrue(PhonePrefs.isDark(PhonePrefs.Appearance.DARK, systemDark = false))
        assertFalse(PhonePrefs.isDark(PhonePrefs.Appearance.LIGHT, systemDark = true))
    }

    @Test
    fun coreUiTextFollowsLanguage() {
        assertEquals("思考中", WorkPhrase.phrase(null)) // 默认中文
        inEnglish {
            assertEquals("Thinking about it", WorkPhrase.phrase(null))
            assertEquals("Searching online", WorkPhrase.toolLabel("web_search"))
            assertEquals("Failed: the network is unavailable, try again later", ChatBars.actionError("network"))
            assertEquals("Running", ChatBars.taskStatusLabel("running"))
            assertEquals("Question", PendingDock.Kind.QUESTION.label)
            assertEquals(
                "B · question: Which one?",
                Notices.banner(Notices.Notice(1, null, "b", "B", "question", "s", null, null, "Which one?")),
            )
            val j = JumpButton(1f)
            j.onDistance(10f)
            assertEquals("Jump to latest", j.label())
            j.onAppended(2)
            assertEquals("2 new messages", j.label())
            assertTrue(Cards.READ_ONLY_HINT.startsWith("Handle pending"))
            assertEquals("Approval", (Cards.asReadOnly(Cards.Card.Approval("a", null, null)) as Cards.Card.ReadOnly).title)
        }
        assertEquals("提问", PendingDock.Kind.QUESTION.label) // 恢复中文
    }

    @Test
    fun assembledAndStatsTextInEnglish() {
        val state = checkNotNull(
            DsbotModels.decodeState(checkNotNull(Json.parse("""{"revision":1,"bots":[{"id":"b1","name":"A","sessionId":"s1"}],"rooms":[],"sections":[]}"""))),
        )
        assertEquals("未分组", TeamHome.assemble(state).sections.single().name)
        inEnglish {
            // 语言切换后连接对象按新语言重组装（NativeShellConnection.relocalize）即得英文缺省分组名
            assertEquals("Ungrouped", TeamHome.assemble(state).sections.single().name)
            assertEquals(
                "Session 3 turns · 10 steps",
                BotStatsText.runStats(Json.parse("""{"sessionStats":{"turns":3,"steps":10}}""")!!),
            )
            assertEquals(
                "Tokens: input 194K",
                BotStatsText.runStats(Json.parse("""{"tokenUsage":{"uncachedInputTokens":194239}}""")!!),
            )
            val answer = DsbotModels.decodeChatPage(
                Json.parse("""{"sessionId":"s","items":[{"kind":"answer","questions":[{"id":"q","question":"Q?","options":[]}],"answers":[]}]}""") as Json.Value.Object,
            ).items.first()
            assertEquals("Answered: Q?", (Cards.fromChatItem(answer) as Cards.Card.ReadOnly).title)
        }
    }

    @Test
    fun injectedSourceDrivesLanguage() {
        // App 注入可观察的语言来源；有来源时以来源为准，撤销后回到设定值
        var current = ChatBars.Lang.EN
        I18n.source = { current }
        try {
            assertEquals("en", I18n.tr("中", "en"))
            assertEquals("Thinking about it", WorkPhrase.phrase(null))
            current = ChatBars.Lang.ZH
            assertEquals("中", I18n.tr("中", "en"))
        } finally {
            I18n.source = null
        }
        assertEquals(ChatBars.Lang.ZH, I18n.lang)
    }

    @Test
    fun trPicksByLanguage() {
        assertEquals("中", I18n.tr("中", "en"))
        inEnglish { assertEquals("en", I18n.tr("中", "en")) }
    }
}
