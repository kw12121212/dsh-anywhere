package work.keepwriting.core

import java.net.URI

/**
 * 远程页面导航与打开请求的分类策略：与桌面端 navigation-policy.ts 行为一致
 * （桌面主规格 navigation-scope 的 Android 对应物）。同源=协议+主机+端口
 * （默认端口 80/443 归一，协议与主机大小写不敏感）。
 */
object OriginPolicy {
    /** 打开动作：留在 WebView / 交给系统浏览器 / 忽略。 */
    enum class OpenAction { STAY, EXTERNAL, IGNORE }

    /** 解析为 http/https 绝对地址；解析失败、非 web 协议或无主机名（如 "http://"，
     *  WHATWG 视为解析错误）返回 null。 */
    fun parseWebUrl(raw: String): URI? {
        return try {
            val uri = URI(raw.trim())
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") null
            else if (uri.host.isNullOrEmpty()) null
            else uri
        } catch (_: Exception) {
            null
        }
    }

    /** 判断两个地址是否同源（协议、主机、端口；默认端口归一）。 */
    fun sameOrigin(a: String, b: String): Boolean {
        val left = parseWebUrl(a) ?: return false
        val right = parseWebUrl(b) ?: return false
        return effectivePort(left) == effectivePort(right) &&
            left.scheme!!.lowercase() == right.scheme!!.lowercase() &&
            left.host!!.lowercase() == right.host!!.lowercase()
    }

    /** 决定远程页面发起的导航或打开请求如何处理。 */
    fun classifyOpen(harnessUrl: String, target: String): OpenAction {
        if (parseWebUrl(target) == null) return OpenAction.IGNORE
        return if (sameOrigin(harnessUrl, target)) OpenAction.STAY else OpenAction.EXTERNAL
    }

    private fun effectivePort(uri: URI): Int {
        val port = uri.port
        if (port >= 0) return port
        return when (uri.scheme?.lowercase()) {
            "https" -> 443
            else -> 80
        }
    }
}
