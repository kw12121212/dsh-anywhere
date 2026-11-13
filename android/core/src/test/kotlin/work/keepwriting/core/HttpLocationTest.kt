package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** HttpLocation 契约：真实 dsh /api/file 只给查询串 Location（"?path=…&v=…"），其余按 RFC 3986。 */
class HttpLocationTest {
    private val base = "http://100.104.191.49:13080/api/file?path=%2Fhome%2Fwx766%2Fdemo.mp4"

    @Test
    fun `查询串 Location 换掉原查询`() {
        assertEquals(
            "http://100.104.191.49:13080/api/file?path=%2Fhome%2Fwx766%2Fdemo.mp4&v=abc123",
            HttpLocation.resolve(base, "?path=%2Fhome%2Fwx766%2Fdemo.mp4&v=abc123"),
        )
    }

    @Test
    fun `绝对地址原样返回`() {
        assertEquals("https://other.example/x", HttpLocation.resolve(base, "https://other.example/x"))
        assertEquals("HTTP://CASE.example/x", HttpLocation.resolve(base, "HTTP://CASE.example/x"))
    }

    @Test
    fun `协议相对按基准 scheme 补全`() {
        assertEquals("http://other.example:9/x", HttpLocation.resolve(base, "//other.example:9/x"))
    }

    @Test
    fun `根相对路径替换整个路径`() {
        assertEquals("http://100.104.191.49:13080/api/next?a=1", HttpLocation.resolve(base, "/api/next?a=1"))
    }

    @Test
    fun `相对路径按基准目录归并`() {
        assertEquals(
            "http://100.104.191.49:13080/api/ver",
            HttpLocation.resolve(base, "ver"),
        )
        assertEquals(
            "http://100.104.191.49:13080/ver",
            HttpLocation.resolve(base, "../ver"),
        )
    }

    @Test
    fun `基准无路径时相对路径落在根`() {
        assertEquals("http://h:1/v", HttpLocation.resolve("http://h:1", "v"))
        assertEquals("http://h:1/v", HttpLocation.resolve("http://h:1/", "v"))
    }

    @Test
    fun `空或基准无 scheme 返回 null`() {
        assertNull(HttpLocation.resolve(base, ""))
        assertNull(HttpLocation.resolve(base, "   "))
        assertNull(HttpLocation.resolve("/api/file?x=1", "/y"))
    }
}
