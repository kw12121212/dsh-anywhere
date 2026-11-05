package work.keepwriting.core

import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 诊断上报的纯逻辑（diagnostics-reporting 规格）：报告模型、脱敏、截断、JSON 编解码、
 * 端点校验与时间戳格式，与 pca POST /api/diagnostics/reports 的封闭字段集和上限对齐
 * （消息 4 KiB、堆栈 64 KiB、日志 ≤200 行且单行 ≤1 KiB、每批 ≤20 份、请求体 ≤1 MiB）。
 * 脱敏是安全关键：token 查询参数的值绝不出现在任何输出里（no-input-echo-errors）。
 */

/** 报告类型（pca kind 枚举中 dsh 客户端用到的子集）。 */
object DiagKinds {
    const val CRASH = "crash"
    const val ANR = "anr"
    const val ERROR = "error"
}

/** 上报平台标识：pca 侧专用枚举，与本项目自家客户端区分归组。 */
const val DIAG_PLATFORM_DSH_ANDROID = "dsh_android"

/** 服务端入库前的截断上限（超出截断而不拒绝）。 */
object DiagLimits {
    const val MAX_MESSAGE_BYTES = 4 shl 10
    const val MAX_STACK_BYTES = 64 shl 10
    const val MAX_BREADCRUMB_LINES = 200
    const val MAX_BREADCRUMB_BYTES = 1 shl 10
    const val MAX_BATCH_REPORTS = 20
    const val MAX_BATCH_BYTES = 1 shl 20
    const val DEFAULT_QUEUE_CAPACITY = 50
}

/** 一份待上报的诊断报告；字段集与 pca Report 的封闭集合一致。 */
data class DiagReport(
    val id: String,
    val kind: String,
    val occurredAt: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val device: String,
    val osVersion: String,
    val thread: String,
    val message: String,
    val stackTrace: String,
    val breadcrumbs: List<String> = emptyList(),
)

/** 把任意文本修整为可放进报告的形态：截断到字节上限，不切多字节字符。 */
object DiagText {
    /** 按字节截断（UTF-8），回退到字符边界，绝不产生半个字符或半个代理对。 */
    fun truncateBytes(text: String, maxBytes: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private val URL_TOKEN = Regex("""https?://[^\s"'<>]+""")
    private val TOKEN_VALUE = Regex("""token=[^&\s"'<>]*""")

    /**
     * 报告文本脱敏：URL 形态的片段经 [HarnessUrl.redact]（剥 token 参数、隐藏密码、
     * 无法解析时不回显原文），残余的 token=值 一律把值替换为 ***——两条都做，
     * 保证 token 查询参数的值绝不离开设备。
     */
    fun sanitize(text: String): String {
        val redacted = URL_TOKEN.replace(text) { HarnessUrl.redact(it.value) }
        return TOKEN_VALUE.replace(redacted, "token=***")
    }

    /** breadcrumb 行：先脱敏再按行上限截断。 */
    fun breadcrumb(line: String): String =
        truncateBytes(sanitize(line), DiagLimits.MAX_BREADCRUMB_BYTES)
}

/** 报告的 JSON 编解码（core 不引第三方库，编码器手写、解码复用 [Json]）。 */
object DiagJson {
    fun encodeReport(r: DiagReport): String = buildString {
        append('{')
        appendField("id", r.id)
        appendField("kind", r.kind)
        appendField("platform", DIAG_PLATFORM_DSH_ANDROID)
        appendField("occurred_at", r.occurredAt)
        appendField("app_version_name", r.appVersionName)
        appendField("app_version_code", r.appVersionCode)
        appendField("device", r.device)
        appendField("os_version", r.osVersion)
        appendField("thread", r.thread)
        appendField("message", r.message)
        appendField("stack_trace", r.stackTrace)
        append(",\"breadcrumbs\":[")
        r.breadcrumbs.forEachIndexed { i, line ->
            if (i > 0) append(',')
            append(quote(line))
        }
        append("]}")
    }

    fun encodeBatch(reports: List<DiagReport>): String = buildString {
        append("{\"reports\":[")
        reports.forEachIndexed { i, r ->
            if (i > 0) append(',')
            append(encodeReport(r))
        }
        append("]}")
    }

    fun encodedSize(r: DiagReport): Int = encodeReport(r).toByteArray(Charsets.UTF_8).size

    /** 解析队列文件里的一份报告；任何字段缺失、类型不符或语法错误返回 null（读取端丢弃）。 */
    fun decodeReport(text: String): DiagReport? {
        val root = Json.parse(text) as? Json.Value.Object ?: return null
        fun str(key: String): String? = (root.members[key] as? Json.Value.StringValue)?.value
        fun num(key: String): Long? = (root.members[key] as? Json.Value.NumberValue)?.raw?.toLongOrNull()
        return DiagReport(
            id = str("id") ?: return null,
            kind = str("kind") ?: return null,
            occurredAt = str("occurred_at") ?: return null,
            appVersionName = str("app_version_name") ?: return null,
            appVersionCode = num("app_version_code") ?: return null,
            device = str("device") ?: return null,
            osVersion = str("os_version") ?: return null,
            thread = str("thread") ?: return null,
            message = str("message") ?: return null,
            stackTrace = str("stack_trace") ?: return null,
            breadcrumbs = (root.members["breadcrumbs"] as? Json.Value.Array)?.items
                ?.map { (it as? Json.Value.StringValue)?.value ?: return null }
                ?: emptyList(),
        )
    }

    private fun StringBuilder.appendField(name: String, value: String) {
        if (length > 1) append(',')
        append(quote(name)).append(':').append(quote(value))
    }

    private fun StringBuilder.appendField(name: String, value: Long) {
        if (length > 1) append(',')
        append(quote(name)).append(':').append(value.toString())
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '' -> append("\\f")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}

/** 诊断上报端点校验：仅接受带主机名的 https 绝对地址，规范化（默认端口去除、空路径补 /）。
 *  拒绝带 query 的端点——拼 API 路径时 query 会产生畸形 URL。 */
object DiagEndpoint {
    /** 合法返回规范化端点；非法返回 null。 */
    fun validate(raw: String): String? {
        val trimmed = raw.trim()
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https") return null
        val host = uri.host ?: return null
        if (host.isEmpty()) return null
        if (uri.rawQuery != null) return null
        val port = uri.port
        val authority = StringBuilder(host.lowercase())
        if (port >= 0 && port != 443) authority.append(':').append(port)
        val path = uri.rawPath.ifEmpty { "/" }
        return "https://$authority$path"
    }
}

/**
 * ANR 看门狗判定（纯逻辑）：每轮先记录主线程心跳观察值，再向主线程投递新心跳，
 * 宽限期后观察值未前进（新心跳没有执行）即主线程无响应。
 * 警戒：检查点与阈值的间隔不得恰好相等（post 后固定 sleep 阈值再比较），否则边界必然误报
 * ——首版实现因此在模拟器上每周期一份误报（diagnostics-reporting 设备验收发现）。
 */
object AnrDetection {
    const val DEFAULT_INTERVAL_MS = 2_000L
    const val DEFAULT_GRACE_MS = 8_000L

    /** post 前的心跳观察值与宽限期后的当前值相等 → 心跳未执行 → 主线程无响应。 */
    fun isUnresponsive(tickObservedBeforePost: Long, tickAtCheck: Long): Boolean =
        tickAtCheck == tickObservedBeforePost
}

private val RFC3339 = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** epoch 毫秒 → RFC3339 UTC 字符串（pca occurred_at 的要求格式）。 */
fun diagTimestamp(epochMillis: Long): String = RFC3339.format(Instant.ofEpochMilli(epochMillis))
