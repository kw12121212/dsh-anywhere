package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 会话失效判定与 token 历史检查的表驱动测试（与桌面 session.ts 同构）。 */
class SessionAuthTest {
    @Test
    fun isSessionExpiredTable() {
        val marker = SessionAuth.EXPIRED_MARKER
        // 命中：401 + text/plain（含 charset）+ 正文（trim 后）等于 marker。
        assertTrue(SessionAuth.isSessionExpired(401, "text/plain", marker))
        assertTrue(SessionAuth.isSessionExpired(401, "text/plain; charset=utf-8", "  $marker\n"))
        // 不命中：其他状态码 / 其他 contentType / 正文不符 / 正文为空。
        assertFalse(SessionAuth.isSessionExpired(200, "text/plain", marker))
        assertFalse(SessionAuth.isSessionExpired(401, "text/html", "<html>$marker</html>"))
        assertFalse(SessionAuth.isSessionExpired(401, "text/plain", "unauthorized"))
        assertFalse(SessionAuth.isSessionExpired(401, "text/plain", null))
        assertFalse(SessionAuth.isSessionExpired(401, null, marker))
    }

    @Test
    fun markerMatchesDesktopExactly() {
        // 与桌面 session.ts 的 EXPIRED_MARKER 逐字一致（漂移会破坏两端失效判定）。
        assertEquals("dsh web authentication required; reopen the URL printed by dsh web.", SessionAuth.EXPIRED_MARKER)
    }

    @Test
    fun hasTokenInHistoryTable() {
        val history = listOf(
            "http://10.0.2.2:13081/?token=abc123",
            "http://10.0.2.2:13081/",
            "http://10.0.2.2:13081/sessions/1?token=def456",
        )
        // 当前在干净地址（index 1）：两条 token 历史存在 → 需要清历史。
        assertTrue(SessionAuth.hasTokenInHistory(history, currentIndex = 1))
        // 当前就在带 token 地址（index 0）：非当前 token 条目（index 2）仍在 → true。
        assertTrue(SessionAuth.hasTokenInHistory(history, currentIndex = 0))
        // 无 token 历史。
        assertFalse(SessionAuth.hasTokenInHistory(listOf("http://10.0.2.2:13081/", "http://10.0.2.2:13081/x"), 0))
        // 唯一带 token 的条目就是当前条目 → 不清（加载中尚未重定向完成）。
        assertFalse(SessionAuth.hasTokenInHistory(listOf("http://10.0.2.2:13081/?token=abc123"), 0))
    }

    @Test
    fun hasTokenParsing() {
        assertTrue(HarnessUrl.hasToken("https://dsh.example.test/?token=abc"))
        assertTrue(HarnessUrl.hasToken("https://dsh.example.test/?lang=zh&token=abc"))
        assertFalse(HarnessUrl.hasToken("https://dsh.example.test/?lang=zh"))
        assertFalse(HarnessUrl.hasToken("https://dsh.example.test/"))
        assertFalse(HarnessUrl.hasToken("::"))
    }
}
