package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dsbot-member-agents：state.agents 与 work.agents 解码、当前会话、能否添加、标题校验与请求体。 */
class DshAgentsTest {
    private fun text(name: String) = checkNotNull(DshAgentsTest::class.java.getResource("/dsbot/$name")).readText(Charsets.UTF_8)
    private val real = (Json.parse(text("state-full.json")) as Json.Value.Object).members["value"] as Json.Value.Object
    private val declared = Capabilities.from(DsbotApi.parseResponse(200, text("hello.json")))
    private fun obj(t: String) = Json.parse(t) as Json.Value.Object

    @Test
    fun realTeamHasNoAgentAndMayAdd() {
        assertTrue(declared.has("agents"))
        assertTrue(DshAgents.agents(real).isEmpty())
        assertTrue(DshAgents.canAdd(declared, real))
        // 没有 agents 能力（旧版远端）或 state 未到：不能添加
        assertFalse(DshAgents.canAdd(Capabilities.LEGACY, real))
        assertFalse(DshAgents.canAdd(declared, null))
    }

    @Test
    fun decodeAgentsAndRunning() {
        val s = obj(
            """{"agents":[{"id":"ag","name":"DSH Agent","sessions":["s1","s2"],"createdAt":1795000000000},{"name":"缺 id"}],
              "work":{"agents":{"ag":{"since":1}}}}""",
        )
        val a = assertNotNull(DshAgents.agentOf(s, "ag"))
        assertEquals("s2", a.currentSession)
        assertEquals(1795000000000, a.createdAt)
        assertEquals(1, DshAgents.agents(s).size)
        assertTrue(DshAgents.running(s, "ag"))
        assertFalse(DshAgents.running(s, "other"))
        // 已有 Agent：不能再加
        assertFalse(DshAgents.canAdd(declared, s))
        // 全部会话归档：没有当前会话
        assertNull(DshAgents.agentOf(obj("""{"agents":[{"id":"x","sessions":[]}]}"""), "x")!!.currentSession)
        // 对象映射形态也接受；缺 name 时用默认名
        assertEquals("DSH Agent", DshAgents.agentOf(obj("""{"agents":{"k":{"id":"y"}}}"""), "y")!!.name)
        assertFalse(DshAgents.running(obj("""{"work":{}}"""), "ag"))
        assertNull(DshAgents.agentOf(null, "ag"))
    }

    @Test
    fun titleRules() {
        assertNotNull(DshAgents.titleProblem("  "))
        assertNotNull(DshAgents.titleProblem("x".padEnd(81, 'x')))
        assertNull(DshAgents.titleProblem("  " + "x".padEnd(80, 'x') + "  "))
        assertNull(DshAgents.titleProblem("周报"))
    }

    @Test
    fun requestBodies() {
        val R = DshAgents.Requests
        assertEquals("{}", R.add())
        assertEquals("""{"agentId":"ag"}""", R.chat("ag"))
        // 加载更早：cursor 原样透传（上游对象形态 {part, beforeSeq}）
        assertEquals("""{"agentId":"ag","cursor":{"part":0,"beforeSeq":61}}""", R.chat("ag", Json.encode(Json.parse("""{"part":0,"beforeSeq":61}""")!!)))
        assertNotNull(Json.parse(R.chat("ag", "{\"part\":0}")))
        assertEquals("""{"agentId":"ag"}""", R.newSession("ag"))
        assertEquals("""{"agentId":"ag","sessionId":"s2"}""", R.archive("ag", "s2"))
        assertEquals("""{"agentId":"ag","sessionId":"s2","title":"周报 \"一\""}""", R.rename("ag", "s2", " 周报 \"一\" "))
        assertEquals("""{"agentId":"ag"}""", R.remove("ag"))
        assertEquals("""{"agentId":"ag"}""", R.stop("ag"))
    }
}
