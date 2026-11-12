package work.keepwriting.core.spike

import work.keepwriting.core.Json

/**
 * spike 输出脱敏（dsbot-api-spike）：凡是会离开进程内存的文本——stdout 报告、
 * dump 文件、后续测试夹具——都先经这里替换凭据与私人内容。键名、结构与类型
 * 原样保留，值替换为带长度信息的占位符，让 dump 仍是可用的协议取证现场。
 *
 * 不脱敏的原文（token、cookie 值、对话内容）只允许存在于内存变量与发往目标
 * 的请求字节里。
 */
object Sanitize {
    /** 凭据类键：值整体替换（大小写不敏感，含前缀匹配 cookie 类字段名）。 */
    private val CREDENTIAL_KEYS = setOf(
        "token", "cookie", "set-cookie", "authorization", "apikey", "api_key",
        "secret", "password", "passwd", "key", "credential", "auth",
    )

    /** 会话内容类键：Bot 指令与角色、聊天文本、记忆笔记、提问回答、私有端点地址等用户私有内容。
     *  answers/selected：问答卡的作答数组与其元素（评审修复：单复数变体必漏）。 */
    private val CONTENT_KEYS = setOf(
        "text", "preview", "instructions", "brief", "notice", "question", "answer", "answers",
        "selected",
        "note", "content", "summary", "description", "goal", "draft", "kickoff",
        "label", "caption", "transcript", "role", "why", "after", "old", "purpose", "detail",
        "url", "endpoint", "baseurl", "name", "title",
    )

    private const val PLACEHOLDER = "<redacted"

    /** JSON 文档脱敏：解析失败时退化为整体截断（保守起见不输出原文）。 */
    fun json(text: String): String {
        val value = Json.parse(text) ?: return "$PLACEHOLDER:unparseable ${text.length} chars>"
        return serialize(redact(value, inContent = false))
    }

    /**
     * 递归替换：命中凭据/内容键后进入「内容区」，区域内所有字符串叶节点（含
     * 嵌套对象与字符串数组的每个元素）都替换为占位符——卡片类内容（question 的
     * options/selected 等）不是平铺字符串，按键逐个登记必漏。结构与类型原样保留。
     */
    private fun redact(value: Json.Value, inContent: Boolean): Json.Value = when (value) {
        is Json.Value.Object -> Json.Value.Object(
            value.members.mapValues { (key, v) ->
                val k = key.lowercase()
                when {
                    k in CREDENTIAL_KEYS -> redact(v, inContent = true)
                    k in CONTENT_KEYS -> redact(v, inContent = true)
                    else -> redact(v, inContent)
                }
            }
        )
        is Json.Value.Array -> Json.Value.Array(value.items.map { redact(it, inContent) })
        is Json.Value.StringValue ->
            if (inContent) Json.Value.StringValue("$PLACEHOLDER:content ${value.value.length} chars>") else value
        else -> value
    }

    /** Set-Cookie 行脱敏：保留 cookie 名与全部属性，值替换。 */
    fun setCookieLine(line: String): String {
        val parts = line.split(';')
        if (parts.isEmpty()) return "$PLACEHOLDER:empty>"
        val nameValue = parts[0].trim()
        val eq = nameValue.indexOf('=')
        val name = if (eq > 0) nameValue.substring(0, eq) else nameValue
        val rest = parts.drop(1).joinToString(";")
        return "$name=$PLACEHOLDER:cookie>" + if (rest.isEmpty()) "" else ";$rest"
    }

    /** Cookie 请求头脱敏：dsh-auth-xxx=<redacted:cookie> 形态。 */
    fun cookieHeader(header: String): String =
        header.split(';').joinToString("; ") { part ->
            val eq = part.indexOf('=')
            if (eq > 0) "${part.substring(0, eq).trim()}=$PLACEHOLDER:cookie>" else part.trim()
        }

    /** URL 脱敏：复用 HarnessUrl.redact 的语义（去 token 参数）；解析失败给固定占位。 */
    fun url(raw: String): String = work.keepwriting.core.HarnessUrl.redact(raw)

    /** 纯文本正文脱敏（非 JSON 响应等）：按行做键值级替换太脆，直接给长度占位。 */
    fun plain(text: String, kind: String = "content"): String =
        if (text.length <= 24) text else "$PLACEHOLDER:$kind ${text.length} chars>"

    /** 序列化回 JSON 文本（保持对象键序与原始数字形态）。 */
    fun serialize(value: Json.Value): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Json.Value, out: StringBuilder) {
        when (value) {
            is Json.Value.Object -> {
                out.append('{')
                value.members.forEach { (k, v) ->
                    if (out.length > 1 && out[out.length - 1] != '{') out.append(',')
                    out.append(quote(k)).append(':')
                    write(v, out)
                }
                out.append('}')
            }
            is Json.Value.Array -> {
                out.append('[')
                value.items.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(v, out)
                }
                out.append(']')
            }
            is Json.Value.StringValue -> out.append(quote(value.value))
            is Json.Value.NumberValue -> out.append(value.raw)
            is Json.Value.BoolValue -> out.append(if (value.value) "true" else "false")
            Json.Value.NullValue -> out.append("null")
        }
    }

    private fun quote(s: String): String {
        val out = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c == '\b' -> out.append("\\b")
            c.code < 0x20 -> out.append("\\u%04x".format(c.code))
            else -> out.append(c)
        }
        return out.append('"').toString()
    }
}
