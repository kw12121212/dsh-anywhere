package work.keepwriting.core

/**
 * dsh 会话失效判定与 token 历史检查：与桌面端 session.ts 行为一致
 * （桌面 session-auth 主规格的 Android 对应物，判定逻辑两端同构）。
 */
object SessionAuth {
    /** dsh web 在会话失效时以 401 text/plain 返回的固定正文（与桌面逐字一致）。 */
    const val EXPIRED_MARKER = "dsh web authentication required; reopen the URL printed by dsh web."

    /**
     * 判断一次响应是否是 dsh 的会话失效页：401、text/plain 且正文 trim 后等于固定文本。
     * contentType 匹配容许带 charset 参数（如 "text/plain; charset=utf-8"）。
     */
    fun isSessionExpired(status: Int, contentType: String?, body: String?): Boolean {
        if (status != 401) return false
        if (body == null) return false
        val baseType = contentType?.substringBefore(';')?.trim()?.lowercase()
        return baseType == "text/plain" && body.trim() == EXPIRED_MARKER
    }

    /** 导航历史中是否存在含 token 查询参数且不是当前条目的地址（清历史的判定）。 */
    fun hasTokenInHistory(urls: List<String>, currentIndex: Int): Boolean {
        return urls.withIndex().any { (index, url) -> index != currentIndex && HarnessUrl.hasToken(url) }
    }
}
