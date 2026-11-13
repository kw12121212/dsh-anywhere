package work.keepwriting.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TeamHome 组装器测试（dsbot-team-home）：以 spike 夹具的真实 state 结构驱动，
 * 覆盖分组/置顶/排序、标记、状态、搜索、需要你、未知字段降级与空团队。
 */
class TeamHomeTest {
    private fun stateValue(): Json.Value {
        val res = TeamHomeTest::class.java.getResource("/dsbot/state-full.json")
        return (checkNotNull(Json.parse(checkNotNull(res).readText(Charsets.UTF_8))) as Json.Value.Object)
            .members.getValue("value")
    }

    @Test
    fun `夹具 state 组装出条目与摘要`() {
        val team = TeamHome.assemble(checkNotNull(DsbotModels.decodeState(stateValue())))
        assertTrue(team.sections.isNotEmpty())
        val entries = team.sections.flatMap { it.entries }
        assertTrue(entries.isNotEmpty())
        // 夹具的 bots 条目名称已脱敏为占位，但形态字段完整：颜色、摘要等可判存在性
        assertTrue(entries.all { it.name.isNotEmpty() })
        assertTrue(entries.any { it.kind == TeamHome.Entry.Kind.BOT })
    }

    @Test
    fun `置顶优先且其余按名称稳定排序`() {
        val stateJson = """
            {"revision":1,"liveKey":"k","mainBotIds":["b2"],"bots":[
              {"id":"b1","name":"Zeta","color":"blue","sessionId":"s1"},
              {"id":"b2","name":"Alpha","color":"red","sessionId":"s2","pinned":true},
              {"id":"b3","name":"Mid","color":"green","sessionId":"s3"}],
             "rooms":[{"id":"r1","name":"Room","members":["b1"],"sessionId":"s4"}],
             "sections":[]}
        """.trimIndent()
        val team = TeamHome.assemble(checkNotNull(DsbotModels.decodeState(checkNotNull(Json.parse(stateJson)))))
        val names = team.sections.flatMap { it.entries }.map { it.name }
        assertEquals(listOf("Alpha", "Mid", "Room", "Zeta"), names)
        val alpha = team.sections.flatMap { it.entries }.first { it.name == "Alpha" }
        assertTrue(alpha.pinned && alpha.isMain && alpha.sessionId == "s2")
    }

    @Test
    fun `最近活动优先：lastAt 降序，缺失排最后，置顶仍最前`() {
        val stateJson = """
            {"revision":1,"bots":[
              {"id":"b1","name":"旧Bot","sessionId":"s1","lastAt":100},
              {"id":"b2","name":"新Bot","sessionId":"s2","lastAt":300},
              {"id":"b3","name":"无时间Bot","sessionId":"s3"},
              {"id":"b4","name":"置顶Bot","sessionId":"s4","pinned":true,"lastAt":50}],
             "rooms":[{"id":"r1","name":"新群","members":["b1"],"sessionId":"s5","lastAt":200}],
             "sections":[]}
        """.trimIndent()
        val team = TeamHome.assemble(checkNotNull(DsbotModels.decodeState(checkNotNull(Json.parse(stateJson)))))
        val names = team.sections.flatMap { it.entries }.map { it.name }
        // 置顶最前 → lastAt 降序（300/200/100）→ 缺失 lastAt 的按名称兜底排最后
        assertEquals(listOf("置顶Bot", "新Bot", "新群", "旧Bot", "无时间Bot"), names)
    }

    @Test
    fun `分组归属按 sections 的 itemIds`() {
        val stateJson = """
            {"revision":1,"bots":[
              {"id":"b1","name":"A","sessionId":"s1"},
              {"id":"b2","name":"B","sessionId":"s2"}],
             "rooms":[],"sections":[{"id":"sec1","name":"工作","itemIds":["b1"]}]}
        """.trimIndent()
        val team = TeamHome.assemble(checkNotNull(DsbotModels.decodeState(checkNotNull(Json.parse(stateJson)))))
        assertEquals(2, team.sections.size)
        assertEquals("工作", team.sections[0].name)
        assertEquals(listOf("A"), team.sections[0].entries.map { it.name })
        assertEquals(listOf("B"), team.sections[1].entries.map { it.name })
    }

    @Test
    fun `工作状态来自 live 的 work bots 且群聊聚合成员`() {
        val stateJson = """
            {"revision":1,"bots":[
              {"id":"b1","name":"A","sessionId":"s1"},
              {"id":"b2","name":"B","sessionId":"s2"}],
             "rooms":[{"id":"r1","name":"R","members":["b1","b2"],"sessionId":"s3"}],
             "sections":[],
             "work":{"bots":{"b2":{"turn":3}}}}
        """.trimIndent()
        val team = TeamHome.assemble(checkNotNull(DsbotModels.decodeState(checkNotNull(Json.parse(stateJson)))))
        val byName = team.sections.flatMap { it.entries }.associateBy { it.name }
        assertTrue(byName.getValue("B").working)
        assertTrue(!byName.getValue("A").working)
        assertTrue(byName.getValue("R").working)
    }

    @Test
    fun `搜索按名称与摘要过滤且清空恢复`() {
        val stateJson = """
            {"revision":1,"bots":[
              {"id":"b1","name":"财务研究","preview":"关于市场数据的分析","sessionId":"s1"},
              {"id":"b2","name":"运维","preview":"磁盘告警处理","sessionId":"s2"}],
             "rooms":[],"sections":[]}
        """.trimIndent()
        val team = TeamHome.assemble(checkNotNull(DsbotModels.decodeState(checkNotNull(Json.parse(stateJson)))))
        val hitName = TeamHome.search(team, "财务")
        assertEquals(listOf("财务研究"), hitName.sections.flatMap { it.entries }.map { it.name })
        val hitSummary = TeamHome.search(team, "磁盘")
        assertEquals(listOf("运维"), hitSummary.sections.flatMap { it.entries }.map { it.name })
        assertEquals(team.sections.flatMap { it.entries }.size, TeamHome.search(team, "  ").sections.flatMap { it.entries }.size)
        assertTrue(TeamHome.search(team, "不存在词").sections.all { it.entries.isEmpty() } ||
            TeamHome.search(team, "不存在词").sections.isEmpty())
    }

    @Test
    fun `待办按 botId 与 sessionId 落到对应对话行`() {
        val stateJson = """
            {"revision":1,
             "bots":[{"id":"b1","name":"A","sessionId":"s1"},
                     {"id":"b2","name":"B","sessionId":"s2"}],
             "rooms":[{"id":"sr","name":"群","members":["b1"],"sessionId":"sr"}],"sections":[],
             "work":{"approvals":[{"sessionId":"s1","botId":"b1","title":"部署审批"}]},
             "questions":{"sr":{"question":"选哪个方案"}},
             "views":{"s2":{"title":"确认卡片"}}}
        """.trimIndent()
        val team = TeamHome.assemble(checkNotNull(DsbotModels.decodeState(checkNotNull(Json.parse(stateJson)))))
        fun entry(id: String) = team.sections.flatMap { it.entries }.first { it.id == id }
        // 审批带 botId → Bot 行；提问挂在房间会话 → 群聊行；提问/卡片无 botId 时靠 sessionId 命中 Bot 行
        assertEquals(listOf(TeamHome.PendingItem.Kind.APPROVAL), entry("b1").pendingKinds)
        assertEquals(listOf(TeamHome.PendingItem.Kind.QUESTION), entry("sr").pendingKinds)
        assertEquals(listOf(TeamHome.PendingItem.Kind.VIEW), entry("b2").pendingKinds)
    }

    @Test
    fun `同一行多类待办按审批提问卡片稳定排序`() {
        val stateJson = """
            {"revision":1,"bots":[{"id":"b1","name":"A","sessionId":"s1"}],"rooms":[],"sections":[],
             "work":{"approvals":[{"sessionId":"s1","botId":"b1","title":"部署审批"}]},
             "questions":{"s1":{"question":"选哪个方案"}},
             "views":{"s1":{"title":"确认卡片"}}}
        """.trimIndent()
        val team = TeamHome.assemble(checkNotNull(DsbotModels.decodeState(checkNotNull(Json.parse(stateJson)))))
        val kinds = team.sections.flatMap { it.entries }.single().pendingKinds
        assertEquals(
            listOf(
                TeamHome.PendingItem.Kind.APPROVAL,
                TeamHome.PendingItem.Kind.QUESTION,
                TeamHome.PendingItem.Kind.VIEW,
            ),
            kinds,
        )
    }

    @Test
    fun `未知与缺失字段降级不阻塞`() {
        val stateJson = """
            {"revision":1,"bots":[{"id":"b1"}],"rooms":[],"sections":[{"no-id":true}]}
        """.trimIndent()
        val team = TeamHome.assemble(checkNotNull(DsbotModels.decodeState(checkNotNull(Json.parse(stateJson)))))
        val entry = team.sections.flatMap { it.entries }.single()
        assertEquals("b1", entry.name) // 名称缺失降级为 id
        assertNull(entry.color)
        assertNull(entry.lastSummary)
        assertNull(entry.unread)
        assertTrue(!entry.working && !entry.pinned && !entry.isMain)
    }

    @Test
    fun `空团队标记`() {
        val stateJson = """{"revision":1,"bots":[],"rooms":[],"sections":[]}"""
        val team = TeamHome.assemble(checkNotNull(DsbotModels.decodeState(checkNotNull(Json.parse(stateJson)))))
        assertTrue(team.isEmpty)
    }
}
