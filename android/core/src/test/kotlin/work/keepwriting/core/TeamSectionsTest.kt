package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dsbot-sidebar-sections：分组解码、名称校验（同服务端 cleanSectionName）、拖动后的顺序与请求体字面值。 */
class TeamSectionsTest {
    private fun obj(t: String) = Json.parse(t) as Json.Value.Object

    private fun <T> inChinese(block: () -> T): T {
        val before = I18n.lang
        I18n.lang = ChatBars.Lang.ZH
        try {
            return block()
        } finally {
            I18n.lang = before
        }
    }

    @Test
    fun decode() {
        val s = TeamSections.decode(obj("""{"sections":[{"id":"a","name":"研发","collapsed":true,"color":"blue"},{"id":"b"},{"name":"无 id"},{"id":"a","name":"重复"},"x"]}"""))
        assertEquals(listOf("a", "b"), s.map { it.id })
        assertEquals(TeamSections.Section("a", "研发", true, "blue"), s[0])
        assertFalse(s[1].collapsed)
        assertNull(s[1].color)
        assertTrue(TeamSections.decode(obj("{}")).isEmpty())
        assertTrue(TeamSections.decode(null).isEmpty())
    }

    @Test
    fun names() = inChinese {
        val secs = listOf(TeamSections.Section("a", "Design", false, null), TeamSections.Section("b", "研发", false, null))
        assertEquals("研发 组", TeamSections.clean("  「研发   组」 "))
        assertEquals(32, TeamSections.clean("x".repeat(40)).length)
        assertEquals("请输入分组名称", TeamSections.nameProblem(secs, "   "))
        assertEquals("请输入分组名称", TeamSections.nameProblem(secs, "「」"))
        // 大小写与首尾空白归一后重名
        assertEquals("已有同名分组「design」", TeamSections.nameProblem(secs, " design "))
        assertNull(TeamSections.nameProblem(secs, "design", selfId = "a")) // 改自己的名字（只改大小写）
        assertNull(TeamSections.nameProblem(secs, "运维"))
    }

    @Test
    fun reorder() {
        val ids = listOf("a", "b", "c")
        assertEquals(listOf("c", "a", "b"), TeamSections.move(ids, 2, 0))
        assertEquals(listOf("b", "a", "c"), TeamSections.move(ids, 0, 1))
        assertEquals(ids, TeamSections.move(ids, 0, 5))
        assertEquals(ids, TeamSections.move(ids, 1, 1))
        assertTrue(TeamSections.isPermutation(ids, listOf("c", "a", "b")))
        assertFalse(TeamSections.isPermutation(ids, listOf("c", "a", "a")))
        assertFalse(TeamSections.isPermutation(ids, listOf("c", "a")))
        assertFalse(TeamSections.isPermutation(ids, listOf("c", "a", "d")))
        // 行高 100：位移 -160 约两行 → 从 2 到 0；不足半行不动；越界夹住；行高未知不动
        assertEquals(0, TeamSections.targetIndex(2, -160f, 100f, 3))
        assertEquals(1, TeamSections.targetIndex(1, 40f, 100f, 3))
        assertEquals(2, TeamSections.targetIndex(1, 60f, 100f, 3))
        assertEquals(2, TeamSections.targetIndex(0, 900f, 100f, 3))
        assertEquals(1, TeamSections.targetIndex(1, 50f, 0f, 3))
    }

    @Test
    fun requests() {
        val r = TeamSections.Requests
        assertEquals("""{"name":"研发 组"}""", r.create(" 研发  组 "))
        assertEquals("{}", r.create("  "))
        assertEquals("""{"id":"a","name":"新名"}""", r.rename("a", "新名"))
        assertEquals("""{"id":"a","collapsed":true}""", r.collapse("a", true))
        assertEquals("""{"ids":["c","a","b"]}""", r.order(listOf("c", "a", "b")))
        assertEquals("""{"ids":[]}""", r.order(emptyList()))
        assertEquals("""{"id":"a"}""", r.delete("a"))
        assertEquals("""{"id":"b1","sectionId":"a"}""", r.moveEntry("b1", "a"))
        assertEquals("""{"id":"b1","sectionId":null}""", r.moveEntry("b1", null))
    }
}
