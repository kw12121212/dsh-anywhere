package work.keepwriting.dshanywhere

import android.content.Context
import android.webkit.CookieManager
import work.keepwriting.core.HttpSocket
import work.keepwriting.core.SessionCookies
import java.io.File
import java.net.URI

/**
 * 原生会话管理（dsbot-api-client）：会话建立、cookie 持久化与 WebView 迁移取代
 * WebView 承担会话职责——带 token 地址只经原生 HTTP 请求一次（跟随重定向并携带
 * 过程中签发的 cookie，spike 实测 303+Set-Cookie 链路），dsh-auth cookie 存 App
 * 私有存储（原子写入，强杀保留）并单向同步到 WebView CookieManager 供主页面使用；
 * 原生存储是唯一事实来源。cookie 值不进日志与诊断上报。
 */
class NativeSession(context: Context, private val dial: (String, Int) -> java.net.Socket) {
    sealed interface EstablishResult {
        data object Ok : EstablishResult
        /** 网络/TLS/URL 解析层失败（不携带原始异常——其 message 可能含带 token 的 URL）。 */
        data object NetworkError : EstablishResult
        /** 终态未取得 dsh-auth 会话（token 无效、链路走完没有 cookie 等），按会话失效分流。 */
        data class Rejected(val status: Int) : EstablishResult
    }

    private companion object {
        /** 可跟随的重定向码（304 Not Modified 与 305/306 不是导航重定向）。 */
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }

    private val appContext = context.applicationContext
    private val storeFile = File(appContext.filesDir, "native-session-cookies")
    private val http = HttpSocket(dial)
    private var cookies: MutableList<SessionCookies.Cookie> = mutableListOf()

    @Volatile
    private var migrated = false

    /** 进程内加载（AppGraph.load 时调用）：读取持久化 cookie。 */
    fun load() {
        cookies = runCatching {
            SessionCookies.deserialize(storeFile.takeIf { it.isFile }?.readText(Charsets.UTF_8) ?: "")
        }.getOrDefault(mutableListOf()).toMutableList()
    }

    private fun persist() {
        val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
        runCatching {
            tmp.writeText(SessionCookies.serialize(cookies), Charsets.UTF_8)
            if (!tmp.renameTo(storeFile)) {
                storeFile.delete()
                tmp.renameTo(storeFile)
            }
        }
    }

    private fun cookieHeader(): String? =
        SessionCookies.headerOf(SessionCookies.authCookiesOf(cookies)).ifEmpty { null }


    /**
     * 原生建立会话：GET 带 token 地址（只一次），跟随 ≤5 次重定向并带上已收 cookie；
     * 任何一步收到 dsh-auth 即成功。成功后持久化并同步 CookieManager。
     * 重定向只在目标与 token 地址同 authority（scheme://host[:port]）时跟随并携带
     * cookie——dsh-auth cookie 绑定 host:port（tech/dsbot-api auth-cookie-and-host），
     * 不得发往第三方源（跨源 Location 当终态处理）。
     */
    fun establish(tokenUrl: String): EstablishResult {
        val origin = originOf(tokenUrl)
        var url = tokenUrl
        var redirects = 0
        return try {
            while (true) {
                val res = http.request("GET", url, listOfNotNull(cookieHeader()?.let { "Cookie" to it }))
                res.headers.filter { it.first.equals("Set-Cookie", ignoreCase = true) }.forEach { (_, v) ->
                    SessionCookies.parseSetCookie(v)?.let { c ->
                        if (SessionCookies.isAuthCookie(c.name)) {
                            cookies.removeAll { it.name == c.name }
                            cookies.add(c)
                        }
                    }
                }
                if (res.status in REDIRECTS && redirects < 5) {
                    val loc = res.header("Location")
                    val next = if (!loc.isNullOrEmpty()) resolveAgainst(url, loc) else null
                    if (next != null && originOf(next) == origin) {
                        url = next
                        redirects++
                        continue
                    }
                }
                return if (SessionCookies.authCookiesOf(cookies).isNotEmpty()) {
                    persist()
                    syncToWebView(origin)
                    EstablishResult.Ok
                } else {
                    // 链路走完仍没有 dsh-auth（token 无效或跨源重定向截断）：按会话未建立分流
                    EstablishResult.Rejected(res.status)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            EstablishResult.Ok
        } catch (e: Exception) {
            EstablishResult.NetworkError
        }
    }

    /**
     * 升级迁移（每源一次）：原生存储为空且 CookieManager 有该源的 dsh-auth cookie
     * （HttpOnly 可读，Chromium include_http_only=true）时迁入并回写同步。
     * 返回迁移后的会话状态（迁移不到不算错误，由 401 流程接管）。
     */
    fun migrateFromWebViewIfNeeded(origin: String): Boolean {
        if (migrated) return SessionCookies.authCookiesOf(cookies).isNotEmpty()
        migrated = true
        if (SessionCookies.authCookiesOf(cookies).isNotEmpty()) return true
        val header = runCatching { CookieManager.getInstance().getCookie(origin) }.getOrNull() ?: return false
        val migratedCookies = SessionCookies.authCookiesOf(SessionCookies.parseHeader(header))
        if (migratedCookies.isEmpty()) return false
        cookies.addAll(migratedCookies)
        persist()
        return true
    }


    /** feed 订阅：带 dsh-auth cookie 的 SSE 订阅（stop/onFrame 语义同 HttpSocket.feed）。 */
    fun feed(url: String, stop: () -> Boolean, onFrame: (String, String) -> Unit): Int =
        http.feed(url, listOfNotNull(cookieHeader()?.let { "Cookie" to it }), stop, onFrame)

    /** 供后续原生界面的 API 调用入口（本变更先由核心逻辑测试覆盖，界面在后续变更接入）。 */
    fun apiRequest(method: String, url: String, contentType: String?, body: ByteArray?): HttpSocket.Response {
        val headers = buildList {
            cookieHeader()?.let { add("Cookie" to it) }
            contentType?.let { add("Content-Type" to it) }
        }
        return http.request(method, url, headers, body)
    }

    /** 把原生存储的会话 cookie 同步到 WebView CookieManager（单向：原生为事实来源）。 */
    private fun syncToWebView(origin: String) {
        runCatching {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            for (c in SessionCookies.authCookiesOf(cookies)) {
                val attrs = buildString {
                    append("; Path=/")
                    append("; HttpOnly")
                    append("; SameSite=Strict")
                }
                manager.setCookie(origin, "${c.name}=${c.value}$attrs")
            }
            manager.flush()
        }
    }

    private fun originOf(url: String): String = try {
        val u = URI(url)
        "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
    } catch (_: Exception) {
        url
    }

    private fun resolveAgainst(base: String, location: String): String {
        val loc = location.trim()
        if (loc.startsWith("http://") || loc.startsWith("https://")) return loc
        return base.trimEnd('/') + if (loc.startsWith("/")) loc else "/$loc"
    }
}
