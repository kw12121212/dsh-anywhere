package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ChatBars（dsbot-chat-bars-0310）：样本为 0.3.10 源码核对形态（resources/dsbot-source）。 */
class ChatBarsTest {
    private fun sample(): Json.Value.Object {
        val text = ChatBarsTest::class.java.getResource("/dsbot-source/chat-bars-0310.json")!!.readText()
        return Json.parse(text) as Json.Value.Object
    }

    private fun obj(text: String) = Json.parse(text) as Json.Value.Object

    /** 真实 0.3.10 夹具（dsbot/，脱敏副本）：响应信封 {ok,value} 时取 value。 */
    private fun real(name: String): Json.Value.Object {
        val text = ChatBarsTest::class.java.getResource("/dsbot/$name")!!.readText()
        val o = Json.parse(text) as Json.Value.Object
        return (o.members["value"] as? Json.Value.Object) ?: o
    }

    @Test
    fun tasksPrivateOnlyActiveSortedLikeWeb() {
        // task-3 已结束、task-4 带 roomId（群任务）不在私聊；等你审批排最前，其余按 updatedAt 倒序
        val tasks = ChatBars.tasks(sample(), "bot-a")
        assertEquals(listOf("task-2", "task-1"), tasks.map { it.id })
        assertEquals("waiting_approval", tasks[0].status)
        assertEquals("整理周报", tasks[1].title)
        assertTrue(tasks[1].running)
    }

    @Test
    fun tasksSortByUpdatedDescending() {
        val st = obj(
            """{"bots":[{"id":"b","tasks":[
              {"id":"old","status":"running","updatedAt":10},
              {"id":"new","status":"running","updatedAt":30},
              {"id":"mid","status":"waiting_work","startedAt":20},
              {"id":"appr","status":"waiting_approval","updatedAt":1}]}]}""",
        )
        assertEquals(listOf("appr", "new", "mid", "old"), ChatBars.tasks(st, "b").map { it.id })
    }

    @Test
    fun realBaselineStateDerivesNothing() {
        // 真实 0.3.10 全量 state（computerLive/work 为空、无 tasks/blocked/goal）：所有派生为空，页面不变
        val full = real("state-full.json")
        val bots = (full.members["bots"] as Json.Value.Array).items.map { ((it as Json.Value.Object).members["id"] as Json.Value.StringValue).value }
        val rooms = (full.members["rooms"] as Json.Value.Array).items.map { it as Json.Value.Object }
        assertTrue(bots.isNotEmpty())
        for (id in bots) {
            assertEquals(emptyList(), ChatBars.tasks(full, id))
            assertEquals(emptyList(), ChatBars.computerAsks(full, botId = id))
            assertFalse(ChatBars.computerBusy(full, id))
            assertNull(ChatBars.repair(full, id))
            assertNull(ChatBars.botWork(full, id))
        }
        for (r in rooms) {
            val rid = (r.members["id"] as Json.Value.StringValue).value
            assertNull(ChatBars.goal(r))
            assertEquals(emptyList(), ChatBars.computerAsks(full, roomId = rid))
            assertEquals(emptyList(), ChatBars.roomWork(full, rid))
        }
    }

    @Test
    fun liveFieldsReadableAfterMergeDelta() {
        // 设计依赖：增量 unchanged+live 应答经 mergeDelta 合并后，computerLive/work 仍在合并对象顶层
        val state = DsbotModels.decodeState(real("state-full.json"))!!
        val delta = DsbotModels.decodeStateDelta(real("state-unchanged-live.json"))!!
        val merged = DsbotModels.mergeDelta(state, delta).raw
        assertTrue(merged.members["computerLive"] is Json.Value.Object)
        assertTrue(merged.members["work"] is Json.Value.Object)
        // 用合成 live 覆盖验证读取路径：work.bots / computerLive.asks 合并后可读
        val liveDelta = DsbotModels.decodeStateDelta(
            obj(
                """{"revision":1,"unchanged":true,"liveKey":"k2",
                  "work":{"bots":{"x":{"tool":"bash"}},"rooms":{}},
                  "computerLive":{"bots":{"x":{"state":"bot"}},"asks":{"a":{"id":"a","botId":"x","appName":"Notes","at":1}}}}""",
            ),
        )!!
        val merged2 = DsbotModels.mergeDelta(state, liveDelta).raw
        assertEquals("运行命令", WorkPhrase.phrase(ChatBars.botWork(merged2, "x")))
        assertTrue(ChatBars.computerBusy(merged2, "x"))
        assertEquals(listOf("a"), ChatBars.computerAsks(merged2, botId = "x").map { it.id })
    }

    @Test
    fun tasksAbsentOrMalformedAreEmpty() {
        assertEquals(emptyList(), ChatBars.tasks(sample(), "bot-c"))
        assertEquals(emptyList(), ChatBars.tasks(sample(), "nobody"))
        assertEquals(emptyList(), ChatBars.tasks(null, "bot-a"))
        val bad = obj("""{"bots":[{"id":"x","tasks":"oops"},{"id":"y","tasks":[1,{"title":"no id","status":"running"},{"id":"z","status":7}]}]}""")
        assertEquals(emptyList(), ChatBars.tasks(bad, "x"))
        assertEquals(emptyList(), ChatBars.tasks(bad, "y"))
    }

    @Test
    fun tasksCapAndTitleFallbackAndClip() {
        val many = (1..30).joinToString(",") { """{"id":"t$it","status":"running"}""" }
        val long = "长".repeat(200)
        val st = obj("""{"bots":[{"id":"b","tasks":[$many]},{"id":"c","tasks":[{"id":"t","title":"$long","status":"running"}]}]}""")
        val tasks = ChatBars.tasks(st, "b")
        assertEquals(ChatBars.LIST_MAX, tasks.size)
        assertEquals("t1", tasks[0].title) // 无标题回落 id
        val clipped = ChatBars.tasks(st, "c")[0].title
        assertEquals(ChatBars.TITLE_MAX, clipped.length)
        assertTrue(clipped.endsWith("…"))
    }

    @Test
    fun computerAsksPrivateFilterAndOrder() {
        val asks = ChatBars.computerAsks(sample(), botId = "bot-a")
        assertEquals(listOf("ask-1", "ask-2"), asks.map { it.id }) // 按 at 升序，群请求不在私聊
        assertEquals("Notes", asks[0].appName)
        assertNull(asks[0].roomId)
        assertEquals(emptyList(), ChatBars.computerAsks(sample(), botId = "bot-b"))
    }

    @Test
    fun computerAsksRoomFilter() {
        val asks = ChatBars.computerAsks(sample(), roomId = "room-1")
        assertEquals(listOf("ask-3"), asks.map { it.id })
        assertEquals("bot-b", asks[0].botId)
        assertEquals(emptyList(), ChatBars.computerAsks(sample(), roomId = "room-2"))
    }

    @Test
    fun computerAbsentOrMalformed() {
        assertEquals(emptyList(), ChatBars.computerAsks(obj("""{"bots":[]}"""), botId = "a"))
        assertEquals(emptyList(), ChatBars.computerAsks(obj("""{"computerLive":{"asks":[1,2]}}"""), botId = "a"))
        assertEquals(
            emptyList(),
            ChatBars.computerAsks(obj("""{"computerLive":{"asks":{"x":{"botId":"a"},"y":"bad"}}}"""), botId = "a"),
        )
        assertFalse(ChatBars.computerBusy(obj("""{"computerLive":{"bots":{"a":"bad"}}}"""), "a"))
    }

    @Test
    fun computerBusyStates() {
        assertTrue(ChatBars.computerBusy(sample(), "bot-a")) // waiting
        assertFalse(ChatBars.computerBusy(sample(), "bot-c")) // idle
        assertFalse(ChatBars.computerBusy(sample(), "bot-b")) // 无条目
        assertTrue(ChatBars.computerBusy(obj("""{"computerLive":{"bots":{"a":{"state":"bot"}}}}"""), "a"))
    }

    @Test
    fun repairMissingKeyUsesBotNameAndNoTopUp() {
        val r = assertNotNull(ChatBars.repair(sample(), "bot-a"))
        assertEquals("MISSING_CREDENTIAL", r.code)
        assertEquals("合成甲 使用的模型还没有 API key", r.title)
        assertTrue(r.guidance.contains("网页端"))
        assertFalse(r.topUp)
    }

    @Test
    fun repairQuotaOfficialShowsTopUpWithStatus() {
        val r = assertNotNull(ChatBars.repair(sample(), "bot-b"))
        assertEquals("DeepSeek 余额或额度不足（402）", r.title)
        assertTrue(r.topUp)
        assertEquals("https://platform.deepseek.com/top_up", ChatBars.TOP_UP_URL)
    }

    @Test
    fun repairTopUpOnlyForOfficialQuotaOrAccountQuota() {
        assertFalse(ChatBars.repairOf("QUOTA", "openai", null, "B").topUp)
        assertTrue(ChatBars.repairOf("ACCOUNT_QUOTA", "deepseek-account", null, "B").topUp)
        assertFalse(ChatBars.repairOf("AUTH", "deepseek-official", 401, "B").topUp)
    }

    @Test
    fun repairCodesHaveReadableTexts() {
        assertEquals("openai 未接受当前 API key（401）", ChatBars.repairOf("AUTH", "openai", 401, "B").title)
        assertEquals("模型服务 未接受当前 API key", ChatBars.repairOf("AUTH", null, null, "B").title)
        assertEquals("API key 格式不对", ChatBars.repairOf("INVALID_CREDENTIAL", null, null, "B").title)
        for (c in listOf("ACCOUNT_SIGN_IN_REQUIRED", "ACCOUNT_SIGNED_OUT", "ACCOUNT_TOKEN_INVALID")) {
            assertEquals("DeepSeek 账号登录已失效", ChatBars.repairOf(c, "deepseek-account", null, "B").title)
        }
        assertEquals("本地 Agent 异常退出", ChatBars.repairOf("LOCAL_AGENT_EXIT", null, null, "B").title)
        assertEquals("找不到本地 Agent", ChatBars.repairOf("LOCAL_AGENT_MISSING", null, null, "B").title)
        assertEquals("B 上一轮运行失败（WEIRD）", ChatBars.repairOf("WEIRD", null, null, "B").title)
        assertEquals("B's model has no API key yet", ChatBars.repairOf("MISSING_CREDENTIAL", null, null, "B", ChatBars.Lang.EN).title)
        assertEquals("DeepSeek is out of balance or quota (402)", ChatBars.repairOf("QUOTA", "deepseek-official", 402, "B", ChatBars.Lang.EN).title)
    }

    @Test
    fun repairAbsentOrMalformedIsNull() {
        assertNull(ChatBars.repair(sample(), "bot-c"))
        assertNull(ChatBars.repair(obj("""{"bots":[{"id":"a","blocked":"x"}]}"""), "a"))
        assertNull(ChatBars.repair(obj("""{"bots":[{"id":"a","blocked":{"code":""}}]}"""), "a"))
        assertNull(ChatBars.repair(obj("""{"bots":"oops"}"""), "a"))
    }

    @Test
    fun goalActiveDecodesStagesAndProgress() {
        val room = (sample().members["rooms"] as Json.Value.Array).items[0] as Json.Value.Object
        val g = assertNotNull(ChatBars.goal(room))
        assertEquals("发布新版本", g.title)
        assertEquals("active", g.status)
        assertEquals(4, g.stages.size)
        assertEquals(1, g.current)
        assertEquals("实现", g.stages[g.current].title)
        assertEquals(1, g.passed)
        assertEquals("进行中", ChatBars.goalStatusLabel(g.status))
    }

    @Test
    fun goalHiddenWhenCompleteOrAbsent() {
        val room2 = (sample().members["rooms"] as Json.Value.Array).items[1] as Json.Value.Object
        assertNull(ChatBars.goal(room2))
        assertNull(ChatBars.goal(obj("""{"id":"r"}""")))
        assertNull(ChatBars.goal(obj("""{"goal":{"status":"stopped","stages":[]}}""")))
        assertNull(ChatBars.goal(obj("""{"goal":{"title":"no status"}}""")))
        assertNull(ChatBars.goal(null))
    }

    @Test
    fun goalDraftingWithoutStagesAndBadCurrent() {
        val d = assertNotNull(ChatBars.goal(obj("""{"goal":{"status":"drafting","title":"草稿","stages":[],"current":-1}}""")))
        assertEquals(0, d.stages.size)
        assertEquals(-1, d.current)
        val bad = assertNotNull(ChatBars.goal(obj("""{"goal":{"status":"paused","title":"t","stages":[{"title":"a"},5],"current":9}}""")))
        assertEquals(1, bad.stages.size)
        assertEquals("pending", bad.stages[0].status)
        assertEquals(-1, bad.current)
        val many = (1..40).joinToString(",") { """{"title":"s$it","status":"pending"}""" }
        assertEquals(ChatBars.STAGES_MAX, ChatBars.goal(obj("""{"goal":{"status":"active","stages":[$many],"current":0}}"""))!!.stages.size)
    }

    @Test
    fun goalAllStagesPassedHasNoCurrent() {
        val g = assertNotNull(
            ChatBars.goal(obj("""{"goal":{"status":"active","title":"t","current":2,"stages":[{"title":"a","status":"passed"},{"title":"b","status":"passed"}]}}""")),
        )
        assertEquals(-1, g.current)
        assertEquals(2, g.passed)
        // 超大 current 不回绕成合法下标
        assertEquals(-1, ChatBars.goal(obj("""{"goal":{"status":"active","current":4294967296,"stages":[{"title":"a"}]}}"""))!!.current)
    }

    @Test
    fun asksWithoutAtSortLast() {
        val st = obj("""{"computerLive":{"asks":{"n":{"id":"n","botId":"b","appName":"A"},"t":{"id":"t","botId":"b","appName":"B","at":5}}}}""")
        assertEquals(listOf("t", "n"), ChatBars.computerAsks(st, botId = "b").map { it.id })
    }

    @Test
    fun repairGuidanceVariants() {
        assertTrue(ChatBars.repairOf("QUOTA", "openai", null, "B").guidance.contains("改用其他模型"))
        assertEquals("充值后点「重试」。", ChatBars.repairOf("ACCOUNT_QUOTA", null, null, "B").guidance)
        assertTrue(ChatBars.repairOf("MISSING_CREDENTIAL", null, null, "B").guidance.contains("自动消失"))
        assertTrue(ChatBars.repairOf("ACCOUNT_SIGNED_OUT", null, null, "B").guidance.contains("重新登录"))
    }

    @Test
    fun actionErrorsAreReadable() {
        assertEquals("操作失败：任务、请求或 Bot 已不存在", ChatBars.actionError("bot/not-found"))
        assertEquals("操作失败：网络不可用，请稍后重试", ChatBars.actionError("network"))
        assertEquals("操作失败：网络不可用，请稍后重试", ChatBars.actionError(null))
        assertTrue(ChatBars.actionError("bot/read-only").contains("只读"))
        assertTrue(ChatBars.actionError("bot/stale").contains("刷新"))
        assertEquals("操作失败（bot/weird）", ChatBars.actionError("bot/weird"))
        assertEquals("Failed (x)", ChatBars.actionError("x", ChatBars.Lang.EN))
    }

    @Test
    fun clipKeepsSurrogatePairs() {
        val s = "a".repeat(8) + "😀" + "b".repeat(10)
        val c = ChatBars.clip(s, 10) // 第 9、10 个单元是 emoji 的代理对：不切开，整体舍去
        assertEquals("a".repeat(8) + "…", c)
        assertEquals("abc", ChatBars.clip("abc", 10))
    }

    @Test
    fun workEntriesForBotAndRoom() {
        val w = assertNotNull(ChatBars.botWork(sample(), "bot-a"))
        assertEquals("联网搜索 · example.com", WorkPhrase.phrase(w))
        assertNull(ChatBars.botWork(sample(), "bot-c"))
        val room = ChatBars.roomWork(sample(), "room-1")
        assertEquals(listOf("bot-b"), room.map { it.first })
        assertEquals("正在跑测试", WorkPhrase.phrase(room[0].second))
        assertEquals(emptyList(), ChatBars.roomWork(sample(), "room-2"))
        assertEquals(emptyList(), ChatBars.roomWork(obj("""{"work":{"rooms":{"r":"bad"}}}"""), "r"))
    }

    @Test
    fun taskStatusLabels() {
        assertEquals("运行中", ChatBars.taskStatusLabel("running"))
        assertEquals("等你审批", ChatBars.taskStatusLabel("waiting_approval"))
        assertEquals("等待交接", ChatBars.taskStatusLabel("waiting_work"))
        assertEquals("Running", ChatBars.taskStatusLabel("running", ChatBars.Lang.EN))
    }
}
