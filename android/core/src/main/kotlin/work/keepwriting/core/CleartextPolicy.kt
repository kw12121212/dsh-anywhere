package work.keepwriting.core

/**
 * 明文 http 放行策略（里程碑 intake 问题 cleartext-http 的运行时收紧）：
 * 只放行与已保存 harness 地址同源的 http 请求；https 与其他协议不受本策略约束。
 * WebView 的 shouldInterceptRequest 与 App 侧探测共用同一判定。
 */
object CleartextPolicy {
    /** http 请求仅当与 harness 源同源时放行；非 http（https 等）一律放行。 */
    fun isAllowed(harnessUrl: String, requestUrl: String): Boolean {
        val request = OriginPolicy.parseWebUrl(requestUrl) ?: return true
        if (request.scheme?.lowercase() != "http") return true
        return OriginPolicy.sameOrigin(harnessUrl, requestUrl)
    }
}
