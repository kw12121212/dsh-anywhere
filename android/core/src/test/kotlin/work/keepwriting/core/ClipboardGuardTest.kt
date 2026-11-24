package work.keepwriting.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** dsbot-connectors-mail：敏感剪贴板的清理判断（离开后回来或兜底到点、按摘要、读不到不下结论）。 */
class ClipboardGuardTest {
    @Test
    fun `clears on return after leaving when clipboard still holds our copy`() {
        val g = ClipboardGuard()
        g.onCopy("d1")
        g.onLeave()
        assertTrue(g.onFocus("d1"))
        assertFalse(g.pending)
    }

    @Test
    fun `focus regained inside the app does not clear`() {
        val g = ClipboardGuard()
        g.onCopy("d1")
        // 关闭对话框、拉下通知栏：没有 onStop
        assertFalse(g.onFocus("d1"))
        assertTrue(g.pending)
    }

    @Test
    fun `background timer that cannot read keeps waiting for return`() {
        val g = ClipboardGuard()
        g.onCopy("d1")
        g.onLeave()
        // Android 10+ 后台读不到剪贴板：保留摘要（评审发现：此前会把它当作已清空而放弃）
        assertFalse(g.onTimer(null))
        assertTrue(g.pending)
        assertTrue(g.onFocus("d1"))
    }

    @Test
    fun `timer in foreground clears our copy`() {
        val g = ClipboardGuard()
        g.onCopy("d1")
        assertTrue(g.onTimer("d1"))
        assertFalse(g.pending)
    }

    @Test
    fun `user copied something else - never clear it`() {
        val g = ClipboardGuard()
        g.onCopy("d1")
        g.onLeave()
        assertFalse(g.onFocus("other"))
        assertFalse(g.pending)
        g.onLeave()
        assertFalse(g.onFocus("d1")) // 已放弃，不再管
    }

    @Test
    fun `unreadable on focus keeps waiting and new copy resets leave flag`() {
        val g = ClipboardGuard()
        g.onCopy("d1")
        g.onLeave()
        assertFalse(g.onFocus(null))
        assertTrue(g.pending)
        g.onCopy("d2") // 重新复制：须再次离开才清
        assertFalse(g.onFocus("d2"))
        g.onLeave()
        assertTrue(g.onFocus("d2"))
    }

    @Test
    fun `leave without copy is ignored`() {
        val g = ClipboardGuard()
        g.onLeave()
        g.onCopy("d1")
        assertFalse(g.onFocus("d1"))
    }
}
