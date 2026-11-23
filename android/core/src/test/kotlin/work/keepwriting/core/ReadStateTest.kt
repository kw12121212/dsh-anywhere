package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** dsbot-read-state：本机已读状态只前移、合并、未读判定、序列化与主机标识。 */
class ReadStateTest {
    private val h = "https://pca.example:443"

    @Test
    fun markOnlyMovesForward() {
        val s = ReadState.EMPTY.mark(h, "b1", 100)
        assertEquals(100, s.seenAt(h, "b1"))
        assertSame(s, s.mark(h, "b1", 50)) // 不后退
        assertSame(s, s.mark(h, "b1", 100)) // 相等不变
        assertSame(s, s.mark(h, "b1", 0)) // 0 与负数忽略
        assertSame(s, s.mark(h, "b1", -5))
        assertSame(s, s.mark("", "b1", 500))
        assertEquals(200, s.mark(h, "b1", 200).seenAt(h, "b1"))
        assertEquals(0, s.seenAt(h, "other"))
        assertEquals(0, s.seenAt("http://other:80", "b1")) // 按主机分开
    }

    @Test
    fun mergeTakesMaxPerEntry() {
        val a = ReadState.EMPTY.mark(h, "b1", 100).mark(h, "b2", 300)
        val b = ReadState.EMPTY.mark(h, "b1", 200).mark(h, "b2", 50).mark("http://x:80", "r", 7)
        val m = a.merge(b)
        assertEquals(200, m.seenAt(h, "b1"))
        assertEquals(300, m.seenAt(h, "b2"))
        assertEquals(7, m.seenAt("http://x:80", "r"))
    }

    @Test
    fun unreadNeedsNewerActivityAndNotOpen() {
        val s = ReadState.EMPTY.mark(h, "b1", 100)
        assertTrue(s.isUnread(h, "b1", 101, open = false))
        assertFalse(s.isUnread(h, "b1", 100, open = false)) // 边界：相等即已读
        assertFalse(s.isUnread(h, "b1", 101, open = true)) // 当前打开的聊天不显示
        assertFalse(s.isUnread(h, "b1", null, open = false)) // 无 lastAt 不显示
        assertFalse(s.isUnread(h, "b1", 0, open = false))
        assertTrue(s.isUnread(h, "never", 1, open = false)) // 从未读过且有活动
    }

    @Test
    fun seedOnlyForUnknownHost() {
        val seeded = ReadState.EMPTY.seed(h, mapOf("b1" to 100L, "b2" to null))
        assertEquals(100, seeded.seenAt(h, "b1"))
        assertFalse(seeded.isUnread(h, "b1", 100, open = false)) // 首次见到的现有活动不算未读
        assertTrue(seeded.isUnread(h, "b1", 101, open = false)) // 之后的新活动照常未读
        assertSame(seeded, seeded.seed(h, mapOf("b1" to 500L, "b3" to 9L))) // 已有记录的主机不再打底
        assertSame(ReadState.EMPTY, ReadState.EMPTY.seed(h, mapOf("b2" to null))) // 没有 lastAt 时保持未知、下次再打底
        assertTrue(seeded.knows(h))
        assertFalse(seeded.knows("http://x:80"))
    }

    @Test
    fun readAtUsesNewestOnlyForTheSameChat() {
        assertEquals(300, ReadState.readAt("b1", 100, "b1" to 300L))
        assertEquals(100, ReadState.readAt("b1", 100, "b2" to 900L)) // 上一个聊天的时间不记到新聊天
        assertEquals(100, ReadState.readAt("b1", 100, "b1" to 50L))
        assertEquals(0, ReadState.readAt("b1", null, null))
        assertEquals(70, ReadState.readAt("b1", null, "b1" to 70L))
    }

    @Test
    fun encodeDecodeRoundTripAndTolerance() {
        val s = ReadState.EMPTY.mark(h, "b\"1", 1700000000123).mark("http://x:80", "r", 5)
        assertEquals(s, ReadState.decode(s.encode()))
        assertEquals(ReadState.EMPTY, ReadState.decode(null))
        assertEquals(ReadState.EMPTY, ReadState.decode("not json"))
        assertEquals(ReadState.EMPTY, ReadState.decode("[1,2]"))
        assertEquals(ReadState.EMPTY, ReadState.decode("""{"v":1,"hosts":[]}"""))
        val partial = ReadState.decode("""{"v":1,"hosts":{"$h":{"ok":10,"zero":0,"neg":-1,"str":"x","frac":1.5},"bad":3}}""")
        assertEquals(10, partial.seenAt(h, "ok"))
        assertEquals(0, partial.seenAt(h, "zero"))
        assertEquals(0, partial.seenAt(h, "neg"))
        assertEquals(0, partial.seenAt(h, "str"))
        assertEquals(0, partial.seenAt(h, "frac"))
    }

    @Test
    fun hostKeyDropsPathQueryAndToken() {
        assertEquals("https://pca.example:443", ReadState.hostKey("https://PCA.example/?token=abc"))
        assertEquals("http://127.0.0.1:13090", ReadState.hostKey("http://127.0.0.1:13090/x?token=t"))
        assertEquals("http://h:80", ReadState.hostKey("http://h"))
        assertNull(ReadState.hostKey(null))
        assertNull(ReadState.hostKey("not a url"))
        assertNull(ReadState.hostKey("ftp://h/x"))
    }
}
