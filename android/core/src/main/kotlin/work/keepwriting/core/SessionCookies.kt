package work.keepwriting.core

/**
 * dsh 会话 cookie 的解析与构造（dsbot-api-client）：原生建会话要收集 Set-Cookie
 * 行里的 dsh-auth-*（spike 实测：HttpOnly、SameSite=Strict、Path=/、Max-Age 30 天、
 * 值内嵌颁发 authority），升级迁移要从 CookieManager.getCookie 的请求头形态里取出
 * 同一批 cookie。纯字符串处理，cookie 值只在这里经过、不落任何日志。
 */
object SessionCookies {
    data class Cookie(val name: String, val value: String, val attrs: Map<String, String>)

    /** 是否 dsh 会话 cookie（名字前缀 dsh-auth-，值 v1 开头的 JWT 形态由服务端约束）。 */
    fun isAuthCookie(name: String): Boolean = name.startsWith("dsh-auth-")

    /** 解析一行 Set-Cookie：name=value 后跟属性段（大小写不敏感键）。 */
    fun parseSetCookie(line: String): Cookie? {
        val parts = line.split(';')
        val first = parts.firstOrNull()?.trim() ?: return null
        val eq = first.indexOf('=')
        if (eq <= 0) return null
        val name = first.substring(0, eq).trim()
        val value = first.substring(eq + 1).trim()
        if (name.isEmpty() || value.isEmpty()) return null
        val attrs = mutableMapOf<String, String>()
        for (part in parts.drop(1)) {
            val k = part.substringBefore('=').trim().lowercase()
            val v = part.substringAfter('=', "").trim()
            if (k.isNotEmpty()) attrs[k] = v
        }
        return Cookie(name, value, attrs)
    }

    /** 解析 Cookie 请求头（name=value; name2=value2）为列表，忽略空段与无值段。 */
    fun parseHeader(header: String): List<Cookie> =
        header.split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty()) null else Cookie(name, value, emptyMap())
        }

    /** 取出其中的 dsh-auth-* 会话 cookie。 */
    fun authCookiesOf(cookies: List<Cookie>): List<Cookie> = cookies.filter { isAuthCookie(it.name) }

    /** 由 cookie 列表构造 Cookie 请求头值。 */
    fun headerOf(cookies: List<Cookie>): String = cookies.joinToString("; ") { "${it.name}=${it.value}" }

    /** 序列化到原生存储（name\tvalue 一行一个；只存会话 cookie，属性可由行为约定重建）。 */
    fun serialize(cookies: List<Cookie>): String =
        authCookiesOf(cookies).joinToString("\n") { "${it.name}\t${it.value}" }

    /** 反序列化；任何行形态异常都跳过该行（按没有该 cookie 处理）。 */
    fun deserialize(text: String): List<Cookie> =
        text.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val name = line.substring(0, tab)
            val value = line.substring(tab + 1)
            if (isAuthCookie(name) && value.isNotEmpty()) Cookie(name, value, emptyMap()) else null
        }.toList()
}
