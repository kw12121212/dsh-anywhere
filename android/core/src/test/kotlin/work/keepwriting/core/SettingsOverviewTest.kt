package work.keepwriting.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZoneId

/**
 * Settings 概览页纯逻辑单元测试（dsbot-settings-overview）：
 * 覆盖真实脱敏夹具（state-full.json、usage-view.json、update-check.json）及全部派生规则——
 * Attention 告警优先级、Team 槽位截断与 modelLine、6 个汇总磁贴、分组列表与状态徽标、
 * 不支持字段与 bot/not-found 逐项隐藏、畸形输入容错以及中英文切换。
 */
class SettingsOverviewTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = 1_795_361_700_000L

    @BeforeEach
    fun setUp() {
        I18n.source = null
        I18n.lang = ChatBars.Lang.ZH
    }

    @AfterEach
    fun tearDown() {
        I18n.source = null
        I18n.lang = ChatBars.Lang.ZH
    }

    private fun obj(json: String): Json.Value.Object = Json.parse(json) as Json.Value.Object

    private fun fixture(name: String): Json.Value {
        val text = checkNotNull(SettingsOverviewTest::class.java.getResource("/dsbot/$name")).readText(Charsets.UTF_8)
        return (Json.parse(text) as Json.Value.Object).members["value"]!!
    }

    @Test
    fun `真实夹具 state-full、usage-view 与 update-check 派生结果正确`() {
        val state = fixture("state-full.json") as Json.Value.Object
        val usage = Usage.decode(fixture("usage-view.json"))!!
        val update = DsbotSystem.decodeUpdate(fixture("update-check.json"))!!
        val snap = SettingsOverview.derive(
            SettingsOverview.Inputs(
                state = state,
                usage = usage,
                update = update,
                nowMs = now,
                zone = zone,
            ),
        )
        assertTrue(snap.team.botCount > 6)
        assertEquals(4, snap.team.shownBots.size)
        assertEquals(snap.team.botCount - 4, snap.team.moreCount)
        assertTrue(snap.team.shownBots.first().isMain)
        assertNotNull(snap.connectTile)
        assertNotNull(snap.toolsTile)
        assertNotNull(snap.usageTile)
        assertEquals("1M", snap.usageTile!!.totalText)
        assertEquals("今天 673K", snap.usageTile!!.todayText)
        assertEquals(7, snap.usageTile!!.barPercents.size)
        assertTrue(snap.usageTile!!.barPercents.contains(100))
        assertTrue(snap.usageTile!!.barPercents.all { it in 8..100 })
        assertEquals("Paper", snap.appearanceTile.themeLabel)
        val aboutRow = snap.groups.flatMap { it.rows }.first { it.id == "about" }
        assertEquals("0.3.10", aboutRow.badge)
        assertEquals(SettingsOverview.RowTone.NORMAL, aboutRow.tone)
        // state-full.json 不含 mcp，mcp 行不出现
        assertFalse(snap.groups.flatMap { it.rows }.any { it.id == "mcp" })
    }

    @Test
    fun `Attention 按缺失服务商 key 优先、其次添加服务商、其次盲查缺 key`() {
        val state = obj(
            """
            {
              "bots":[
                {"id":"b1","name":"Alpha","model":"deepseek/deepseek-chat"},
                {"id":"b2","name":"Beta","model":"openai/gpt-5"}
              ],
              "models":[
                {"ref":"deepseek/deepseek-chat","id":"deepseek-chat","name":"DeepSeek Chat","provider":"deepseek","providerName":"DeepSeek","needsKey":true},
                {"ref":"openai/gpt-5","id":"gpt-5","name":"GPT-5","provider":"openai","providerName":"OpenAI","needsKey":false}
              ]
            }
            """.trimIndent(),
        )
        val providers = ModelProviders.View(
            available = true,
            providers = listOf(
                ModelProviders.Provider(
                    id = "deepseek", name = "DeepSeek",
                    models = listOf(ModelProviders.ModelRef("deepseek-chat", "deepseek/deepseek-chat", "DeepSeek Chat")),
                    declaredModels = emptyList(), refreshable = false,
                    key = ModelProviders.Key(configured = false, writable = true),
                    removable = false, custom = false, error = null,
                ),
                ModelProviders.Provider(
                    id = "anthropic", name = "Anthropic",
                    models = listOf(ModelProviders.ModelRef("claude", "anthropic/claude", "Claude")),
                    declaredModels = emptyList(), refreshable = false,
                    key = ModelProviders.Key(configured = false, writable = true),
                    removable = false, custom = false, error = null,
                ),
            ),
            addable = emptyList(), protocols = emptyList(), revision = null,
        )
        val snap1 = SettingsOverview.derive(SettingsOverview.Inputs(state, providers = providers, providersLoaded = true))
        // Anthropic 没有 Bot 在用，不提醒；只提醒 DeepSeek
        assertEquals(1, snap1.alerts.size)
        assertEquals("DeepSeek 需要 API key", snap1.alerts[0].title)
        assertEquals("Alpha 在用", snap1.alerts[0].meta)
        assertEquals(SettingsOverview.NavTarget.ModelProviders, snap1.alerts[0].target)

        // 分支 2：无可用非本地模型且 providers 可用
        val emptyState = obj("""{"bots":[{"id":"b1","name":"Alpha"}],"models":[]}""")
        val snap2 = SettingsOverview.derive(
            SettingsOverview.Inputs(
                emptyState,
                providers = ModelProviders.View(true, emptyList(), emptyList(), emptyList(), null),
                providersLoaded = true,
            ),
        )
        assertEquals(1, snap2.alerts.size)
        assertEquals("add-provider", snap2.alerts[0].id)
        assertEquals(SettingsOverview.NavTarget.ModelProviders, snap2.alerts[0].target)

        // 分支 3：有可用模型，providers 已加载但没有未配置 key 的服务商，仍有 Bot 用 needsKey 模型 → 跳 BotModels
        val blindProviders = ModelProviders.View(true, emptyList(), emptyList(), emptyList(), null)
        val snap3 = SettingsOverview.derive(SettingsOverview.Inputs(state, providers = blindProviders, providersLoaded = true))
        assertEquals(1, snap3.alerts.size)
        assertEquals("needs-key-blind", snap3.alerts[0].id)
        assertEquals(SettingsOverview.NavTarget.BotModels, snap3.alerts[0].target)

        // providers 尚未加载完成或瞬态失败（providers == null, providersLoaded == false）时，既不误弹 add-provider 也不弹 needs-key-blind
        val snapLoadingEmpty = SettingsOverview.derive(SettingsOverview.Inputs(emptyState))
        assertTrue(snapLoadingEmpty.alerts.isEmpty())
        val snapLoadingNeedsKey = SettingsOverview.derive(SettingsOverview.Inputs(state))
        assertTrue(snapLoadingNeedsKey.alerts.isEmpty())

        // providers 不可用时不弹 add-provider
        val snap4 = SettingsOverview.derive(SettingsOverview.Inputs(emptyState, providersUnsupported = true))
        assertTrue(snap4.alerts.isEmpty())
    }

    @Test
    fun `Team 槽位在 5 个以内全显、6 个及以上截断到 4 个并解析 modelLine`() {
        val five = obj(
            """
            {
              "mainBotIds":["b1"],
              "bots":[
                {"id":"b1","name":"One","color":"blue","model":"local-codex-cli/default"},
                {"id":"b2","name":"Two","model":"missing/m1"},
                {"id":"b3","name":"Three"},
                {"id":"b4","name":"Four","appliedModel":"ds/chat"},
                {"id":"b5","name":"Five"}
              ],
              "rooms":[{"id":"r1"},{"id":"r2"}],
              "models":[
                {"ref":"local-codex-cli/default","id":"default","name":"default","provider":"local-codex-cli","providerName":"Codex CLI","needsKey":false},
                {"ref":"ds/chat","id":"chat","name":"DS Chat","provider":"ds","providerName":"DS","needsKey":true}
              ]
            }
            """.trimIndent(),
        )
        val s5 = SettingsOverview.derive(SettingsOverview.Inputs(five))
        assertEquals(5, s5.team.botCount)
        assertEquals(2, s5.team.roomCount)
        assertEquals(5, s5.team.shownBots.size)
        assertEquals(0, s5.team.moreCount)
        assertEquals("CLI 默认", s5.team.shownBots[0].modelLine)
        assertTrue(s5.team.shownBots[0].isMain)
        assertEquals("m1（不可用）", s5.team.shownBots[1].modelLine)
        assertEquals("未选择模型", s5.team.shownBots[2].modelLine)
        assertEquals("DS Chat", s5.team.shownBots[3].modelLine)
        assertTrue(s5.team.shownBots[3].needsKey)

        val six = obj(
            """
            {
              "bots":[
                {"id":"b1","name":"1"},{"id":"b2","name":"2"},{"id":"b3","name":"3"},
                {"id":"b4","name":"4"},{"id":"b5","name":"5"},{"id":"b6","name":"6"}
              ]
            }
            """.trimIndent(),
        )
        val s6 = SettingsOverview.derive(SettingsOverview.Inputs(six))
        assertEquals(4, s6.team.shownBots.size)
        assertEquals(2, s6.team.moreCount)
    }

    @Test
    fun `Models、Connect、Tools、Usage、Schedules 与 Appearance 磁贴计数芯片与目标正确`() {
        val state = obj(
            """
            {
              "bots":[
                {"id":"b1","name":"A","model":"local-claude-code/default"},
                {"id":"b2","name":"B","model":"p1/m1"},
                {"id":"b3","name":"C","model":"k1/m1"}
              ],
              "models":[
                {"ref":"p1/m1","id":"m1","name":"M1","provider":"p1","providerName":"P1","needsKey":false},
                {"ref":"p2/m2","id":"m2","name":"M2","provider":"p2","providerName":"P2","needsKey":false},
                {"ref":"p3/m3","id":"m3","name":"M3","provider":"p3","providerName":"P3","needsKey":false},
                {"ref":"p4/m4","id":"m4","name":"M4","provider":"p4","providerName":"P4","needsKey":false},
                {"ref":"k1/m1","id":"m1","name":"K1","provider":"k1","providerName":"Keyless","needsKey":true},
                {"ref":"local-claude-code/default","id":"default","name":"Claude","provider":"local-claude-code","providerName":"Claude","needsKey":false}
              ],
              "connectors":{
                "connectors":[
                  {"id":"github","name":"GitHub","state":"ready"},
                  {"id":"notion","name":"Notion","state":"connecting"},
                  {"id":"slack","name":"Slack","state":"off"},
                  {"id":"jira","name":"Jira","state":"ready"}
                ],
                "mail":{"accounts":[{"id":"m1","address":"a@b.c","provider":"qq","state":"ready"}]}
              },
              "im":{"connections":[
                {"id":"c1","platform":"telegram","enabled":true},
                {"id":"c2","platform":"telegram","enabled":true},
                {"id":"c3","platform":"feishu","enabled":true},
                {"id":"c4","platform":"discord","enabled":true}
              ],"platforms":[]},
              "phone":{"on":true,"status":"online","phones":[{"id":"p1","connected":true}]},
              "capabilities":[{"name":"s1","kind":"skill"},{"name":"m1","kind":"mcp"}],
              "browser":true,
              "computer":{"enabled":true,"provider":{"state":"ready"},"macos":false,"bots":{}},
              "secrets":[{"name":"API_KEY","purpose":""}],
              "prefs":{"theme":"moonlight","layout":"flat","effects":"reduced"}
            }
            """.trimIndent(),
        )
        val providers = ModelProviders.View(
            available = true,
            providers = listOf(
                ModelProviders.Provider(
                    "k1", "Keyless", listOf(ModelProviders.ModelRef("m1", "k1/m1", "K1")),
                    emptyList(), false, ModelProviders.Key(false, true), false, false, null,
                ),
            ),
            addable = emptyList(), protocols = emptyList(), revision = null,
        )
        val schedules = Schedules.View(
            available = true, canPause = true,
            tasks = listOf(
                Schedules.Task("t1", "Later", "", "at", "2026-11-27T10:00:00Z", null, null, null, emptyList(), null, "active", null, null, null, null),
                Schedules.Task("t2", "Soon", "", "at", "2026-11-27T08:00:00Z", null, null, null, emptyList(), null, "active", null, null, null, null),
                Schedules.Task("t3", "Paused", "", "at", "2026-11-26T08:00:00Z", null, null, null, emptyList(), null, "paused", null, null, null, null),
            ),
        )
        val snap = SettingsOverview.derive(
            SettingsOverview.Inputs(state = state, providers = providers, providersLoaded = true, schedules = schedules, nowMs = now, zone = zone),
        )

        // Models tile: 4 usable non-local models, max 3 normal provider chips + 1 keyless WARN + Local 1
        assertEquals(4, snap.modelsTile.usableCount)
        assertEquals(listOf("P1", "P2", "P3", "Keyless", "本地 1"), snap.modelsTile.chips.map { it.label })
        assertEquals(SettingsOverview.ChipTone.WARN, snap.modelsTile.chips[3].tone)
        assertEquals(SettingsOverview.NavTarget.ModelProviders, snap.modelsTile.target)

        // Connect tile: 1 mail + 3 active connectors + 4 IM + 1 phoneOnline = 9
        val conn = snap.connectTile!!
        assertEquals(9, conn.connectedCount)
        assertEquals(listOf("手机", "Telegram", "飞书 / Lark", "GitHub", "Notion", "邮箱"), conn.chips.map { it.label })
        assertEquals(SettingsOverview.ChipTone.OK, conn.chips[0].tone)
        assertEquals(SettingsOverview.NavTarget.Connectors, conn.target)

        // Tools tile
        val tools = snap.toolsTile!!
        assertEquals(2, tools.capabilityCount)
        assertEquals(listOf("浏览器", "电脑操控", "密钥 1"), tools.chips.map { it.label })
        assertEquals(SettingsOverview.NavTarget.Capabilities, tools.target)

        // Schedules tile
        val sched = snap.schedulesTile!!
        assertEquals(2, sched.activeCount)
        assertTrue(sched.nextHint!!.startsWith("下次："))

        // Appearance tile
        assertEquals("Moonlight", snap.appearanceTile.themeLabel)
        assertEquals("平铺 · 减弱", snap.appearanceTile.meta)

        // Usage placeholder before loaded
        assertEquals("–", snap.usageTile!!.totalText)
    }

    @Test
    fun `旧远端缺字段与按需端点 bot_not_found 隐藏对应磁贴与分组行`() {
        val legacyState = obj(
            """
            {
              "bots":[{"id":"b1","name":"Main"}],
              "mainBotIds":["b1"],
              "browser":false
            }
            """.trimIndent(),
        )
        val snap = SettingsOverview.derive(
            SettingsOverview.Inputs(
                state = legacyState,
                providersUnsupported = true,
                usageUnsupported = true,
                schedulesUnsupported = true,
            ),
        )
        assertNull(snap.connectTile)
        assertNull(snap.toolsTile)
        assertNull(snap.usageTile)
        assertNull(snap.schedulesTile)
        assertEquals(SettingsOverview.NavTarget.BotModels, snap.modelsTile.target)
        val rowIds = snap.groups.flatMap { it.rows }.map { it.id }
        assertFalse("model-providers" in rowIds)
        assertFalse("schedules" in rowIds)
        assertFalse("usage" in rowIds)
        assertFalse("connectors" in rowIds)
        assertFalse("im" in rowIds)
        assertFalse("mcp" in rowIds)
        assertFalse("computer" in rowIds)
        assertFalse("capabilities" in rowIds)
        assertFalse("secrets" in rowIds)
        assertTrue(listOf("team-prefs", "team-workspace", "sections", "bot-models", "local-agents", "deepseek-services", "about", "diagnostics").all { it in rowIds })
        assertTrue(SettingsOverview.isUnsupported(DsbotApi.Result.BotError("bot/not-found", "Unknown endpoint")))
        assertFalse(SettingsOverview.isUnsupported(DsbotApi.Result.Failure(null, DsbotApi.Reason.NETWORK)))
    }

    @Test
    fun `分组列表状态徽标覆盖 capabilityStatuses、imStatus、mcp、secretStore、privateArchives 与 dsh 更新`() {
        val state = obj(
            """
            {
              "bots":[{"id":"b1","name":"B1"}],
              "privateArchives":[{"id":"p1","name":"Old"}],
              "sections":[{"id":"s1","name":"研发"}],
              "connectors":{"connectors":[{"id":"gh","name":"GH","state":"error","error":"boom"}],"mail":{"accounts":[]}},
              "im":{"connections":[{"id":"im1","platform":"discord","enabled":true}],"platforms":[]},
              "imStatus":{"im1":{"state":"error","error":"socket failed"}},
              "mcp":{"on":true,"port":8797,"status":"on","clients":[]},
              "computer":{"enabled":true,"provider":{"state":"ready"},"bots":{}},
              "capabilities":[{"name":"c1","kind":"mcp","transport":"stdio","approved":false}],
              "capabilityStatuses":{"b1":{"c1":{"state":"error","error":"dead"}}},
              "secrets":[{"name":"S1"}],
              "secretStore":{"mode":"encrypted","locked":true}
            }
            """.trimIndent(),
        )
        val update = DsbotSystem.Update(
            bot = DsbotSystem.BotUpdate("0.3.10", "0.3.10", "registry", null, null, true, 0L, "idle", null, null, available = false),
            dsh = DsbotSystem.DshUpdate("0.2.2", "0.2.3", "npm", null, "idle", null, null, available = true),
        )
        val snap = SettingsOverview.derive(SettingsOverview.Inputs(state = state, update = update))
        val rows = snap.groups.flatMap { it.rows }.associateBy { it.id }

        assertEquals("1", rows["sections"]?.badge)
        assertEquals("1", rows["private-archives"]?.badge)
        assertEquals(SettingsOverview.RowTone.ERROR, rows["connectors"]?.tone)
        assertEquals(SettingsOverview.RowTone.ERROR, rows["im"]?.tone)
        assertEquals("已开启", rows["mcp"]?.badge)
        assertEquals(SettingsOverview.RowTone.OK, rows["mcp"]?.tone)
        assertEquals("已开启", rows["computer"]?.badge)
        assertEquals("1 项 · 出错", rows["capabilities"]?.badge)
        assertEquals(SettingsOverview.RowTone.ERROR, rows["capabilities"]?.tone)
        assertEquals("已锁定", rows["secrets"]?.badge)
        assertEquals(SettingsOverview.RowTone.WARN, rows["secrets"]?.tone)
        assertEquals("有更新", rows["about"]?.badge)
        assertEquals(SettingsOverview.RowTone.WARN, rows["about"]?.tone)
    }

    @Test
    fun `英文语言切换与畸形输入不崩溃`() {
        val malformed = obj(
            """
            {
              "bots":"oops",
              "models":[123, {"ref":""}, {"ref":"x/y","name":null}],
              "phone":"bad",
              "mcp":{"on":true,"status":"off","error":"Port 8797 in use","clients":[]},
              "prefs":{"theme":"unknown","layout":"bubble","effects":"off"}
            }
            """.trimIndent(),
        )
        I18n.lang = ChatBars.Lang.EN
        val snap = SettingsOverview.derive(SettingsOverview.Inputs(state = malformed))
        assertEquals(0, snap.team.botCount)
        assertEquals("None", snap.appearanceTile.meta.substringAfter(" · "))
        val mcpRow = snap.groups.flatMap { it.rows }.first { it.id == "mcp" }
        assertEquals("Error", mcpRow.badge)
        assertEquals(SettingsOverview.RowTone.ERROR, mcpRow.tone)

        // 空 state 不抛异常
        val empty = SettingsOverview.derive(SettingsOverview.Inputs(state = null))
        assertEquals(0, empty.team.botCount)
        assertTrue(empty.groups.isNotEmpty())
    }
}
