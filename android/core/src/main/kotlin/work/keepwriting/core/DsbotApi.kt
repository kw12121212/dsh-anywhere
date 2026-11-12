package work.keepwriting.core

/**
 * DS-Bot API 信封与结果解码（dsbot-api-client，契约来自 tech/dsbot-api 与
 * dsbot-api-spike 实测）：POST /api/bot 的请求体是 {"endpoint":…,"payload":…}，
 * 响应是 {"ok":true,"value":…} 或 {"ok":false,"error":{code,message,details}}。
 * 本对象只做纯构造与纯解码——传输（拨号、TLS、cookie、Host）由调用方注入，
 * core 不依赖 android.*，也不把凭据带进任何日志语义。
 */
object DsbotApi {
    /** 业务/传输错误到用户可读原因的映射（dsbot-api 标准登记的错误码族）。 */
    enum class Reason { BAD_INPUT, REFUSED, STALE, NOT_FOUND, READ_ONLY, FEATURE_OFF, SERVER_ERROR, NETWORK, AUTH_EXPIRED, FORBIDDEN_HOST }

    sealed interface Result {
        /** ok:true 的 value 原始节点（可能是任何 JSON 形态，由调用方按端点解码）。 */
        data class Ok(val value: Json.Value) : Result
        /** ok:false 的 bot 前缀错误（bot/invalid 等）。 */
        data class BotError(val code: String, val message: String) : Result
        /** 非 200 或非 JSON 等 HTTP/协议层失败。 */
        data class Failure(val status: Int?, val reason: Reason) : Result
    }

    /** 构造请求信封 JSON（payloadJson 必须是合法 JSON 文本或空串表示 null）。 */
    fun buildRequest(endpoint: String, payloadJson: String = "{}"): String =
        "{\"endpoint\":" + JsonString.of(endpoint) + ",\"payload\":" + (payloadJson.ifEmpty { "null" }) + "}"

    /**
     * 基准地址 + 子路径的统一拼接（tech/dsbot-api 的 exact-path-construction）：
     * 基准先去尾部斜杠再拼子路径——dsh-url/HarnessUrl.normalize 的根路径带尾斜杠，
     * 直接拼出的双斜杠路径会被 dsh 前缀路由静默失配、落到 SPA 兜底（POST→405）。
     * 所有 /api 子路径消费方一律经此构造，禁止手写字符串拼接。
     */
    fun url(base: String, path: String): String = base.trimEnd('/') + path

    /**
     * 解码响应：200 且合法 JSON 走 ok/fail 信封；401 按会话失效、403 按 Host/
     * Origin 拒绝映射（spike 实测边界），其余归 HTTP 失败。contentType 为 null 时
     * 仍尝试按 JSON 解析（dsh 总是 application/json，宽容一点）。
     */
    fun parseResponse(status: Int, bodyText: String): Result {
        if (status == 401) return Result.Failure(401, Reason.AUTH_EXPIRED)
        if (status == 403) return Result.Failure(403, Reason.FORBIDDEN_HOST)
        if (status != 200) return Result.Failure(status, Reason.NETWORK)
        val root = Json.parse(bodyText) ?: return Result.Failure(200, Reason.SERVER_ERROR)
        val obj = root as? Json.Value.Object ?: return Result.Failure(200, Reason.SERVER_ERROR)
        val ok = obj.members["ok"]
        if (ok is Json.Value.BoolValue && ok.value) {
            return Result.Ok(obj.members["value"] ?: Json.Value.NullValue)
        }
        val err = obj.members["error"] as? Json.Value.Object
        if (err != null) {
            val code = (err.members["code"] as? Json.Value.StringValue)?.value ?: "bot/error"
            val message = (err.members["message"] as? Json.Value.StringValue)?.value ?: ""
            return Result.BotError(code, message)
        }
        return Result.Failure(200, Reason.SERVER_ERROR)
    }

    /** bot 前缀错误码 → 用户可读原因（未登记的 bot 码归 REFUSED——dsh 上游语义即拒绝类）。 */
    fun reasonOf(code: String): Reason = when (code) {
        "bot/invalid" -> Reason.BAD_INPUT
        "bot/stale" -> Reason.STALE
        "bot/not-found" -> Reason.NOT_FOUND
        "bot/read-only" -> Reason.READ_ONLY
        "bot/browser-off" -> Reason.FEATURE_OFF
        "bot/internal" -> Reason.SERVER_ERROR
        else -> Reason.REFUSED
    }
}

/** JSON 字符串字面量构造（转义与 core Json 的解析接受集对齐；App 侧拼请求统一用它）。 */
object JsonString {
    fun of(s: String): String {
        val out = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c.code < 0x20 -> out.append("\\u%04x".format(c.code))
            else -> out.append(c)
        }
        return out.append('"').toString()
    }
}
