import Foundation

/// 诊断上报的纯逻辑（diagnostics-reporting 规格）：报告模型、脱敏、截断、JSON 编解码
/// 与时间戳，与 pca POST /api/diagnostics/reports 的封闭字段集和上限对齐
/// （消息 4 KiB、堆栈 64 KiB、日志 ≤200 行且单行 ≤1 KiB、每批 ≤20 份、请求体 ≤1 MiB）。
/// 语义与 android/core 的 DiagReport/DiagText/DiagJson 及桌面 src/main/diagnostics.ts 一致；
/// 脱敏是安全关键：token 查询参数的值绝不出现在任何输出里（security-secrets/no-input-echo-errors）。
public enum DiagKind: String, Sendable {
    case crash
    case anr
    case error
}

/// 上报平台标识：pca 侧专用枚举。原生 macOS 与 Electron 桌面端同用 `dsh_desktop`
/// （2026-11-27 用户确认：复用枚举，pca 零改动；指纹含 message 与 stack 仍能区分两端崩溃）。
public enum DiagPlatform: Sendable {
    public static let dshDesktop = "dsh_desktop"
}

/// 服务端入库前的截断上限（超出截断而不拒绝）。
public enum DiagLimits: Sendable {
    public static let maxMessageBytes = 4 << 10
    public static let maxStackBytes = 64 << 10
    public static let maxBreadcrumbLines = 200
    public static let maxBreadcrumbBytes = 1 << 10
    public static let maxBatchReports = 20
    public static let maxBatchBytes = 1 << 20
    public static let defaultQueueCapacity = 50
}

/// 一份待上报的诊断报告；字段集与 pca Report 的封闭集合一致。
public struct DiagReport: Equatable, Sendable {
    public let id: String
    public let kind: String
    public let occurredAt: String
    public let appVersionName: String
    public let appVersionCode: Int64
    public let device: String
    public let osVersion: String
    public let thread: String
    public let message: String
    public let stackTrace: String
    public let breadcrumbs: [String]

    public init(
        id: String,
        kind: String,
        occurredAt: String,
        appVersionName: String,
        appVersionCode: Int64 = 0,
        device: String,
        osVersion: String,
        thread: String,
        message: String,
        stackTrace: String,
        breadcrumbs: [String] = []
    ) {
        self.id = id
        self.kind = kind
        self.occurredAt = occurredAt
        self.appVersionName = appVersionName
        self.appVersionCode = appVersionCode
        self.device = device
        self.osVersion = osVersion
        self.thread = thread
        self.message = message
        self.stackTrace = stackTrace
        self.breadcrumbs = breadcrumbs
    }
}

/// 报告文本的脱敏与截断（与 android/core DiagText 同清单）。
public enum DiagText: Sendable {
    /// 按字节截断（UTF-8），回退到字符边界，绝不产生半个字符或半个代理对。
    public static func truncateBytes(_ text: String, maxBytes: Int) -> String {
        let bytes = Array(text.utf8)
        if bytes.count <= maxBytes { return text }
        var end = maxBytes
        while end > 0 && (bytes[end] & 0xC0) == 0x80 {
            end -= 1
        }
        return String(decoding: bytes[0..<end], as: UTF8.self)
    }

    // 正则清单以 android/core DiagText.sanitize 为基准移植（模板替换保留前缀分组）。
    private static let urlToken = regex(#"https?://[^\s"'<>]+"#)
    private static let tokenValue = regex(#"token=[^&\s"'<>]*"#)
    private static let jsonSecretField = regex(
        #"(?i)("(?:passphrase|secret|secretValue|secret_value|password|apiKey|api_key|clientSecret|client_secret|accessToken|access_token|refreshToken|refresh_token|token|authorization|cookie|audioBase64|audio_base64)"\s*:\s*)"(?:[^"\\]|\\.)*""#
    )
    // secret-set 载荷中的 value 字段（保留密钥名 name，仅脱敏 value）。
    private static let jsonSecretSetValue = regex(
        #"(?i)("secret-set"[\s\S]*?"value"\s*:\s*)"(?:[^"\\]|\\.)*""#
    )
    // 键值形态的敏感字段（支持无引号、双引号与单引号包裹的值，如 passphrase="my pass"、DSH_SECRET_FOO='bar'、dsh-auth=xxx）。
    private static let kvSecretValue = regex(
        #"(?i)\b((?:DSH_SECRET_[A-Z0-9_]+|passphrase|secret|password|api_key|apiKey|clientSecret|client_secret|dsh-auth|audioBase64|audio_base64)\s*[=:]\s*)(?:"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|[^\s&"',;}\]]+)"#
    )
    // Authorization: Bearer / Basic 头。
    private static let authHeader = regex(
        #"(?i)\b(Authorization\s*:\s*(?:Bearer|Basic)\s+)[^\s"',;}\]]+"#
    )
    // DS-Bot MCP 外部客户端令牌（dsb_ + base64url，dsbot-mcp-access）：不论出现在何种上下文都整段替换。
    private static let mcpClientToken = regex(#"(?<![A-Za-z0-9])dsb_[A-Za-z0-9_-]{8,}"#)

    /// 报告文本脱敏：URL 形态的片段经 `HarnessUrl.redact`（剥 token 参数、隐藏密码、
    /// 无法解析时不回显原文），残余的 token= 值、JSON 密钥/口令字段、secret-set 的 value、
    /// 环境变量与键值形态的敏感字段一律把值替换为 ***，保证凭据、密钥与口令绝不离开设备。
    public static func sanitize(_ text: String) -> String {
        let redactedUrls = replace(urlToken, in: text) { HarnessUrl.redact($0) }
        // JSON 字符串值替换为带引号的 "***"（保持合法 JSON）；键值/头形态替换为裸 ***。
        let noJsonSecrets = replaceGroup1(jsonSecretField, in: redactedUrls, suffix: "\"***\"")
        let noSecretSetValue = replaceGroup1(jsonSecretSetValue, in: noJsonSecrets, suffix: "\"***\"")
        let noAuthHeader = replaceGroup1(authHeader, in: noSecretSetValue, suffix: "***")
        let noKvSecrets = replaceGroup1(kvSecretValue, in: noAuthHeader, suffix: "***")
        let noMcpTokens = replaceAll(mcpClientToken, in: noKvSecrets, with: "dsb_***")
        return replaceAll(tokenValue, in: noMcpTokens, with: "token=***")
    }

    /// breadcrumb 行：先脱敏再按行上限截断。
    public static func breadcrumb(_ line: String) -> String {
        truncateBytes(sanitize(line), maxBytes: DiagLimits.maxBreadcrumbBytes)
    }

    // MARK: - 正则替换辅助

    private static func regex(_ pattern: String) -> NSRegularExpression {
        // 模式在编译期即固定，构造失败属编程错误，直接 fatalError 暴露。
        guard let compiled = try? NSRegularExpression(pattern: pattern) else {
            fatalError("DiagText 正则无法编译: \(pattern)")
        }
        return compiled
    }

    /// 逐个命中做自定义替换（用于 URL 走 HarnessUrl.redact）。
    private static func replace(
        _ regex: NSRegularExpression,
        in text: String,
        with transform: (String) -> String
    ) -> String {
        let range = NSRange(text.startIndex..., in: text)
        let matches = regex.matches(in: text, range: range)
        guard !matches.isEmpty else { return text }
        var out = ""
        out.reserveCapacity(text.utf8.count)
        var cursor = text.startIndex
        for match in matches {
            guard let matchRange = Range(match.range, in: text) else { continue }
            out += text[cursor..<matchRange.lowerBound]
            out += transform(String(text[matchRange]))
            cursor = matchRange.upperBound
        }
        out += text[cursor...]
        return out
    }

    /// 命中处保留第 1 分组、以固定后缀替换其余部分（键值保留键名脱敏值）。
    private static func replaceGroup1(
        _ regex: NSRegularExpression,
        in text: String,
        suffix: String
    ) -> String {
        let range = NSRange(text.startIndex..., in: text)
        return regex.stringByReplacingMatches(
            in: text,
            options: [],
            range: range,
            withTemplate: "$1" + suffix
        )
    }

    private static func replaceAll(
        _ regex: NSRegularExpression,
        in text: String,
        with replacement: String
    ) -> String {
        let range = NSRange(text.startIndex..., in: text)
        return regex.stringByReplacingMatches(
            in: text,
            options: [],
            range: range,
            withTemplate: replacement
        )
    }
}

/// 报告的 JSON 编解码（编码器手写、解码复用内置 `Json`，不用 JSONSerialization）。
public enum DiagJson: Sendable {
    public static func encodeReport(_ r: DiagReport) -> String {
        var fields: [String] = []
        fields.append("\"id\":\(JsonString.of(r.id))")
        fields.append("\"kind\":\(JsonString.of(r.kind))")
        fields.append("\"platform\":\(JsonString.of(DiagPlatform.dshDesktop))")
        fields.append("\"occurred_at\":\(JsonString.of(r.occurredAt))")
        fields.append("\"app_version_name\":\(JsonString.of(r.appVersionName))")
        fields.append("\"app_version_code\":\(r.appVersionCode)")
        fields.append("\"device\":\(JsonString.of(r.device))")
        fields.append("\"os_version\":\(JsonString.of(r.osVersion))")
        fields.append("\"thread\":\(JsonString.of(r.thread))")
        fields.append("\"message\":\(JsonString.of(r.message))")
        fields.append("\"stack_trace\":\(JsonString.of(r.stackTrace))")
        fields.append("\"breadcrumbs\":[\(r.breadcrumbs.map(JsonString.of).joined(separator: ","))]")
        return "{\(fields.joined(separator: ","))}"
    }

    public static func encodeBatch(_ reports: [DiagReport]) -> String {
        "{\"reports\":[\(reports.map(encodeReport).joined(separator: ","))]}"
    }

    public static func encodedSize(_ r: DiagReport) -> Int {
        // 与其余字节口径一致：按 UTF-8 字节数计。
        Array(encodeReport(r).utf8).count
    }

    /// 解析队列文件里的一份报告；任何字段缺失、类型不符或语法错误返回 nil（读取端丢弃）。
    public static func decodeReport(_ text: String) -> DiagReport? {
        guard let root = Json.parse(text)?.objectValue else { return nil }
        guard let id = root.string("id"),
              let kind = root.string("kind"),
              let occurredAt = root.string("occurred_at"),
              let appVersionName = root.string("app_version_name"),
              let device = root.string("device"),
              let osVersion = root.string("os_version"),
              let thread = root.string("thread"),
              let message = root.string("message"),
              let stackTrace = root.string("stack_trace")
        else { return nil }
        guard let codeRaw = root["app_version_code"]?.numberRaw, let appVersionCode = Int64(codeRaw) else {
            return nil
        }
        var breadcrumbs: [String] = []
        if let rawBreadcrumbs = root["breadcrumbs"] {
            guard let items = rawBreadcrumbs.arrayValue else { return nil }
            for item in items {
                guard let line = item.stringValue else { return nil }
                breadcrumbs.append(line)
            }
        }
        return DiagReport(
            id: id,
            kind: kind,
            occurredAt: occurredAt,
            appVersionName: appVersionName,
            appVersionCode: appVersionCode,
            device: device,
            osVersion: osVersion,
            thread: thread,
            message: message,
            stackTrace: stackTrace,
            breadcrumbs: breadcrumbs
        )
    }
}

/// epoch 毫秒 → RFC3339 UTC 字符串（pca occurred_at 的要求格式）。
/// 纯整数算法（Howard Hinnant 的 civil_from_days），不依赖 DateFormatter 的 locale 行为。
public func diagTimestamp(epochMillis: Int64) -> String {
    let seconds = floorDiv(epochMillis, 1000)
    let millis = epochMillis - seconds * 1000
    let days = floorDiv(seconds, 86_400)
    let secsOfDay = seconds - days * 86_400

    let hour = secsOfDay / 3_600
    let minute = (secsOfDay % 3_600) / 60
    let second = secsOfDay % 60

    // 自 1970-01-01 起的天数 → 公历年月日。
    let z = days + 719_468
    let era = floorDiv(z, 146_097)
    let doe = z - era * 146_097 // [0, 146096]
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365 // [0, 399]
    let y = yoe + era * 400
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
    let mp = (5 * doy + 2) / 153 // [0, 11]
    let day = doy - (153 * mp + 2) / 5 + 1 // [1, 31]
    let month = mp < 10 ? mp + 3 : mp - 9 // [1, 12]
    let year = month <= 2 ? y + 1 : y

    func pad(_ value: Int64, _ width: Int) -> String {
        let sign = value < 0 ? "-" : ""
        let digits = String(abs(value))
        return sign + String(repeating: "0", count: max(0, width - digits.count)) + digits
    }

    return "\(pad(year, 4))-\(pad(month, 2))-\(pad(day, 2))"
        + "T\(pad(hour, 2)):\(pad(minute, 2)):\(pad(second, 2)).\(pad(millis, 3))Z"
}

/// 向负无穷取整的除法（Swift `/` 向零截断，历法算法需要 floor 语义）。
private func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
    let q = a / b
    let r = a % b
    return (r != 0 && (r < 0) != (b < 0)) ? q - 1 : q
}
