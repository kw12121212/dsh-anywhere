package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dsbot-group-manage：真实 state 群字段、候选与搜索、与上游一致的本地校验、请求体字面值。 */
class GroupManageTest {
    private val real = (Json.parse(checkNotNull(GroupManageTest::class.java.getResource("/dsbot/state-full.json")).readText(Charsets.UTF_8)) as Json.Value.Object).members["value"] as Json.Value.Object
    private fun obj(t: String) = Json.parse(t) as Json.Value.Object

    @Test
    fun realRooms() {
        val rooms = (real.members["rooms"] as Json.Value.Array).items.map { ((it as Json.Value.Object).members["id"] as Json.Value.StringValue).value }
        val r = assertNotNull(GroupManage.roomOf(real, rooms[0]))
        assertEquals(5, r.members.size)
        assertEquals("admin", r.mode)
        assertTrue(r.named)
        assertNotNull(r.admin)
        assertTrue(r.admin in r.members)
        assertNull(r.permission)
        assertEquals(7, GroupManage.roomOf(real, rooms[1])!!.members.size)
        assertNull(GroupManage.roomOf(real, "nope"))
        assertEquals((real.members["bots"] as Json.Value.Array).items.size, GroupManage.bots(real).size)
        assertEquals(1, GroupManage.bots(real).count { it.isMain })
    }

    @Test
    fun fieldsAndDefaults() {
        val s = obj(
            """{"mainBotIds":["a"],"bots":[{"id":"a","name":"Alice"},{"id":"b","name":"bob"},{"id":"p","name":"Priv","private":true},{"name":"缺 id"}],
              "rooms":[{"id":"r","name":"项目群","members":["a","b"],"admin":null,"mode":"everyone","notice":"周五发布","permission":"read-only","workDir":"/w","pinned":true,"hidden":true},
                       {"id":"x","mode":"weird"}]}""",
        )
        val r = GroupManage.roomOf(s, "r")!!
        assertNull(r.admin)
        assertEquals("everyone", r.mode)
        assertEquals("周五发布", r.notice)
        assertEquals("read-only", r.permission)
        assertTrue(r.pinned && r.hidden && !r.named)
        // 模式原样保留，显示经 shownMode：未知模式按全员轮流（上游只认 mode=="admin"）
        val x = GroupManage.roomOf(s, "x")!!
        assertEquals("weird", x.mode)
        assertEquals("everyone", GroupManage.shownMode(x, GroupManage.bots(s)))
        assertEquals("x", x.name)
        assertTrue(x.members.isEmpty())
        // 候选：私有 Bot 不可加入；搜索不区分大小写
        assertEquals(listOf("a", "b"), GroupManage.candidates(s, "").map { it.id })
        assertEquals(listOf("b"), GroupManage.candidates(s, " BO ").map { it.id })
        assertTrue(GroupManage.candidates(s, "priv").isEmpty())
    }

    @Test
    fun shownModeMatchesWeb() {
        val s = obj(
            """{"bots":[{"id":"a","name":"A"},{"id":"b","name":"B"}],
              "rooms":[{"id":"lead","members":["a","b"],"admin":"a","mode":"admin"},
                       {"id":"noadmin","members":["a","b"],"admin":null,"mode":"admin"},
                       {"id":"gone","members":["a","b"],"admin":"deleted-bot","mode":"admin"},
                       {"id":"turns","members":["a","b"],"admin":"a","mode":"everyone"},
                       {"id":"missing","members":["a","b"],"admin":"a"}]}""",
        )
        val bots = GroupManage.bots(s)
        fun shown(id: String) = GroupManage.shownMode(GroupManage.roomOf(s, id)!!, bots)
        assertEquals("admin", shown("lead"))
        // 管理员为无、或管理员已不在团队：网页端与服务端都按全员轮流
        assertEquals("everyone", shown("noadmin"))
        assertEquals("everyone", shown("gone"))
        assertEquals("everyone", shown("turns"))
        assertEquals("everyone", shown("missing"))
    }

    @Test
    fun localChecks() {
        assertEquals(GroupManage.Problem.TOO_FEW, GroupManage.memberProblem(listOf("a"), null))
        assertEquals(GroupManage.Problem.TOO_FEW, GroupManage.memberProblem(listOf("a", "a"), null))
        assertEquals(GroupManage.Problem.TOO_MANY, GroupManage.memberProblem((1..13).map { "b$it" }, null))
        assertNull(GroupManage.memberProblem((1..12).map { "b$it" }, "b1"))
        assertEquals(GroupManage.Problem.ADMIN_NOT_MEMBER, GroupManage.memberProblem(listOf("a", "b"), "c"))
        assertNull(GroupManage.memberProblem(listOf("a", "b"), null))
        assertNull(GroupManage.noticeProblem("x".padEnd(2000, 'x')))
        assertEquals(GroupManage.Problem.NOTICE_TOO_LONG, GroupManage.noticeProblem("x".padEnd(2001, 'x')))
        // 首尾空白不计（上游先 trim）
        assertNull(GroupManage.noticeProblem("  " + "x".padEnd(2000, 'x') + "  "))
        for (p in GroupManage.Problem.values()) assertTrue(GroupManage.problemText(p).isNotEmpty())
        assertFalse(GroupManage.modeLabel("admin") == GroupManage.modeLabel("everyone"))
        assertTrue(GroupManage.modeHint("everyone").isNotEmpty())
    }

    @Test
    fun requestBodies() {
        val R = GroupManage.Requests
        assertEquals("""{"members":["a","b","c"],"admin":"a","greet":true,"name":"发布组"}""", R.createRoom("a", listOf("b", "a", "c"), " 发布组 "))
        assertEquals("""{"members":["a","b"],"admin":"a","greet":true}""", R.createRoom("a", listOf("b"), "  "))
        assertEquals("""{"id":"r","name":""}""", R.name("r", "  "))
        assertEquals("""{"id":"r","notice":"第一行\n第二行"}""", R.notice("r", "第一行\n第二行 "))
        assertEquals("""{"id":"r","members":["a","b"]}""", R.members("r", listOf("a", "b", "a")))
        assertEquals("""{"id":"r","admin":null}""", R.admin("r", null))
        assertEquals("""{"id":"r","admin":"b"}""", R.admin("r", "b"))
        assertEquals("""{"id":"r","mode":"everyone"}""", R.mode("r", "everyone"))
        assertEquals("""{"id":"r","permission":null}""", R.permission("r", null))
        assertEquals("""{"id":"r","permission":"read-only"}""", R.permission("r", "read-only"))
        assertEquals("""{"id":"r"}""", R.delete("r"))
        assertEquals("r9", GroupManage.createdId(obj("""{"id":"r9","name":"x"}""")))
        assertNull(GroupManage.createdId(null))
    }
}
