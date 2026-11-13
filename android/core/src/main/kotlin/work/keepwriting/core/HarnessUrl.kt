package work.keepwriting.core

import java.net.URI
import java.net.URLDecoder

/**
 * harness URL 校验、token 剥离与脱敏：与桌面端 src/main/url-validation.ts 行为一致
 * （harness-url-config 主规格平台无关，两端由 testdata/harness-url-vectors.json 的
 * 共享测试向量锁定，无差异项）。错误以类别表达，界面文案由 :app 映射。
 */
object HarnessUrl {
    /** 与桌面 URL_ERRORS 一一对应的错误类别。 */
    enum class ErrorCategory { EMPTY, MALFORMED, NOT_ABSOLUTE, UNSUPPORTED_SCHEME }

    class ValidationError(val category: ErrorCategory) : Exception(category.name)

    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
    private val INVALID_PERCENT = Regex("%(?![0-9a-fA-F]{2})")
    private const val TOKEN_PARAM = "token"

    /**
     * 校验用户输入并返回去掉 token 参数的干净地址；首尾空白被忽略，只接受带主机名的
     * http/https 绝对地址。序列化镜像 WHATWG URL.href 对向量用例的形态：scheme 与
     * ASCII 主机小写、默认端口 80/443 去除、空路径补 "/"、查询按原序原编码重建、
     * 片段原样。
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw ValidationError(ErrorCategory.EMPTY)
        val scheme = SCHEME.find(trimmed)?.groupValues?.get(1)?.lowercase()
            ?: throw ValidationError(ErrorCategory.NOT_ABSOLUTE)
        if (scheme != "http" && scheme != "https") throw ValidationError(ErrorCategory.UNSUPPORTED_SCHEME)
        // java.net.URI 与 WHATWG 一样拒绝非法的百分号转义（如 %zz）。
        if (INVALID_PERCENT.containsMatchIn(trimmed)) throw ValidationError(ErrorCategory.MALFORMED)
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            throw ValidationError(ErrorCategory.MALFORMED)
        }
        // WHATWG 对特殊 scheme（http/https）要求非空主机：空 authority（"http://"）或
        // 无主机名都视为解析失败（桌面端经 URL 构造抛错落到 malformed）。
        val host = uri.host
        if (host.isNullOrEmpty()) throw ValidationError(ErrorCategory.MALFORMED)
        val port = uri.port
        val authority = buildString {
            if (uri.rawUserInfo != null) append(uri.rawUserInfo).append('@')
            append(host.lowercase())
            if (port >= 0 && !(scheme == "http" && port == 80) && !(scheme == "https" && port == 443)) {
                append(':').append(port)
            }
        }
        val path = uri.rawPath.ifEmpty { "/" }
        val query = stripToken(uri.rawQuery)
        val fragment = uri.rawFragment
        return buildString {
            append(scheme).append("://").append(authority).append(path)
            if (query != null) append('?').append(query)
            if (fragment != null) append('#').append(fragment)
        }
    }

    /** 校验并返回错误类别；地址合法（可被 normalize 接受）返回 null。 */
    fun errorCategory(raw: String): ErrorCategory? = try {
        normalize(raw)
        null
    } catch (e: ValidationError) {
        e.category
    }

    /** 判断地址的查询参数中是否含有 token（用于历史清理判定；解析失败返回 false）。 */
    fun hasToken(raw: String): Boolean {
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return false
        }
        val query = uri.rawQuery ?: return false
        return query.split('&').any { it.isNotEmpty() && isTokenPart(it) }
    }

    /** 日志文本：去掉 token 参数并隐藏密码；无法解析时不回显原文。 */
    fun redact(raw: String): String {
        val uri = try {
            URI(raw.trim())
        } catch (_: Exception) {
            return "<无法解析的地址>"
        }
        val host = uri.host ?: return "<无法解析的地址>"
        val scheme = uri.scheme?.lowercase() ?: return "<无法解析的地址>"
        val port = uri.port
        val userInfo = uri.rawUserInfo?.split(":", limit = 2)?.let { (user, pass) ->
            if (pass != null) "$user:xxxxx" else user
        }
        val authority = buildString {
            if (userInfo != null) append(userInfo).append('@')
            append(host.lowercase())
            if (port >= 0 && !(scheme == "http" && port == 80) && !(scheme == "https" && port == 443)) {
                append(':').append(port)
            }
        }
        val path = uri.rawPath.ifEmpty { "/" }
        val query = stripToken(uri.rawQuery)
        val fragment = uri.rawFragment
        return buildString {
            append(scheme).append("://").append(authority).append(path)
            if (query != null) append('?').append(query)
            if (fragment != null) append('#').append(fragment)
        }
    }

    /** 删除所有名为 token（按解码后的键名，区分大小写）的查询参数，其余参数保持
     *  原有顺序与编码，空段一并去掉；只按 & 分隔。全部剥空时查询整体消失
     *  （与 WHATWG 把 search 置空后不再输出 '?' 一致）。 */
    private fun stripToken(rawQuery: String?): String? {
        if (rawQuery == null) return null
        val kept = rawQuery.split('&').filter { it.isNotEmpty() && !isTokenPart(it) }
        return kept.joinToString("&").ifEmpty { null }
    }

    private fun isTokenPart(part: String): Boolean {
        val key = part.substringBefore('=')
        val decoded = try {
            // (String, Charset) 重载是 Java 10 / Android API 33+，低版本真机直接
            // NoSuchMethodError（1.2.0 在 API 31 设备粘贴带 ?token 的地址即崩）；
            // 用 (String, String) 老重载（API 1+），语义对 UTF-8 完全一致。
            URLDecoder.decode(key.replace("+", " "), "UTF-8")
        } catch (_: Exception) {
            return false
        }
        return decoded == TOKEN_PARAM
    }
}
