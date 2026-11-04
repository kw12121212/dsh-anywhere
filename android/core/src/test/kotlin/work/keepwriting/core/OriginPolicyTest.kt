package work.keepwriting.core

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 导航范围分类与明文放行策略的表驱动测试（与桌面 navigation-policy 行为对齐）。 */
class OriginPolicyTest {
    private val harness = "http://10.0.2.2:13080/"

    @Test
    fun classifyOpenTable() {
        val cases = listOf(
            Triple(harness, "http://10.0.2.2:13080/sessions/1", OriginPolicy.OpenAction.STAY),
            Triple(harness, "http://10.0.2.2:13080/", OriginPolicy.OpenAction.STAY),
            // 不同端口：外部。
            Triple(harness, "http://10.0.2.2:13081/", OriginPolicy.OpenAction.EXTERNAL),
            // http↔https：外部。
            Triple(harness, "https://10.0.2.2:13080/", OriginPolicy.OpenAction.EXTERNAL),
            Triple(harness, "https://example.org/docs", OriginPolicy.OpenAction.EXTERNAL),
            // 默认端口归一：80 == 缺省。
            Triple("http://dsh.example.test/", "http://dsh.example.test:80/x", OriginPolicy.OpenAction.STAY),
            Triple("https://dsh.example.test:443/", "https://dsh.example.test/x", OriginPolicy.OpenAction.STAY),
            // 大小写不敏感。
            Triple("http://DSH.Example.Test/", "http://dsh.example.test:80/x", OriginPolicy.OpenAction.STAY),
            // 非 web 协议与坏输入：忽略。
            Triple(harness, "file:///etc/passwd", OriginPolicy.OpenAction.IGNORE),
            Triple(harness, "javascript:alert(1)", OriginPolicy.OpenAction.IGNORE),
            Triple(harness, "ftp://dsh.example.test/", OriginPolicy.OpenAction.IGNORE),
            Triple(harness, "/relative/path", OriginPolicy.OpenAction.IGNORE),
            Triple(harness, "not a url", OriginPolicy.OpenAction.IGNORE),
        )
        for ((idx, case) in cases.withIndex()) {
            assertEquals(case.third, OriginPolicy.classifyOpen(case.first, case.second), "用例 #$idx ${case.second}")
        }
    }

    @Test
    fun sameOriginRejectsUnparseable() {
        assertTrue(!OriginPolicy.sameOrigin(harness, "::"))
        assertTrue(!OriginPolicy.sameOrigin("", harness))
        // WHATWG 把空主机的 http URL 视为解析错误 → IGNORE，不抛 NPE。
        assertTrue(!OriginPolicy.sameOrigin(harness, "http://"))
        assertEquals(OriginPolicy.OpenAction.IGNORE, OriginPolicy.classifyOpen(harness, "http://"))
    }
}

class CleartextPolicyTest {
    @Test
    fun httpOnlyAllowedForHarnessOrigin() {
        val harness = "http://10.0.2.2:13080/"
        // harness 源的 http 放行（主页面与同源子资源）。
        assertEquals(true, CleartextPolicy.isAllowed(harness, "http://10.0.2.2:13080/"))
        assertEquals(true, CleartextPolicy.isAllowed(harness, "http://10.0.2.2:13080/assets/app.js"))
        // 跨主机 http 拒绝。
        assertEquals(false, CleartextPolicy.isAllowed(harness, "http://other.example.test/pixel.js"))
        assertEquals(false, CleartextPolicy.isAllowed(harness, "http://10.0.2.2:13081/x"))
        // https 任意主机放行。
        assertEquals(true, CleartextPolicy.isAllowed(harness, "https://any.example.net/lib.js"))
        // harness 为 https 时：一切 http 都拒绝。
        val httpsHarness = "https://dsh.example.test/"
        assertEquals(false, CleartextPolicy.isAllowed(httpsHarness, "http://dsh.example.test/x"))
        assertEquals(false, CleartextPolicy.isAllowed(httpsHarness, "http://any.test/"))
        assertEquals(true, CleartextPolicy.isAllowed(httpsHarness, "https://dsh.example.test/x"))
    }
}

class ProbeFailureTest {
    @Test
    fun classifyTable() {
        assertEquals(ProbeFailure.Reason.DNS, ProbeFailure.classify(UnknownHostException("dsh.example.invalid")))
        assertEquals(ProbeFailure.Reason.REFUSED, ProbeFailure.classify(ConnectException("refused")))
        assertEquals(ProbeFailure.Reason.TIMEOUT, ProbeFailure.classify(SocketTimeoutException("timeout")))
        assertEquals(ProbeFailure.Reason.TLS, ProbeFailure.classify(SSLException("bad cert")))
        assertEquals(ProbeFailure.Reason.OTHER, ProbeFailure.classify(IllegalStateException("boom")))
        // 异常链：原因在 cause 里也能分类。
        assertEquals(
            ProbeFailure.Reason.DNS,
            ProbeFailure.classify(IllegalStateException("wrap", UnknownHostException("nx")))
        )
    }
}
