import Foundation

/// SSE 流异常（由 `macos/App` 或测试传输注入抛出）。
public enum FeedStreamError: Error, Equatable, Sendable {
    case authExpired
    case forbiddenHost
    case httpStatus(Int)
    case invalidResponse
}

/// 携带已分类失败原因的传输错误（不回显原始 URL 或 token）。
public struct TransportError: Error, Equatable, Sendable {
    public let reason: ProbeFailureReason

    public init(_ reason: ProbeFailureReason) {
        self.reason = reason
    }
}

/// 基于 domain 与错误码的纯 Foundation 网络错误分类器（不依赖 `FoundationNetworking`）。
public enum NetErrorClassifier: Sendable {
    private static let urlErrorDomain = "NSURLErrorDomain"
    private static let posixErrorDomain = "NSPOSIXErrorDomain"
    private static let cfNetworkErrorDomain = "kCFErrorDomainCFNetwork"

    public static func classify(domain: String, code: Int) -> ProbeFailureReason {
        switch domain {
        case urlErrorDomain:
            switch code {
            case -1003, -1006:
                return .dns
            case -1004:
                return .refused
            case -1001:
                return .timeout
            case -1200, -1201, -1202, -1203, -1204, -1205, -1206:
                return .tls
            default:
                return .other
            }
        case posixErrorDomain:
            switch code {
            case 61, 111: // ECONNREFUSED (macOS / Linux)
                return .refused
            case 60, 110: // ETIMEDOUT (macOS / Linux)
                return .timeout
            default:
                return .other
            }
        case cfNetworkErrorDomain:
            switch code {
            case -72000, 1, 2:
                return .dns
            default:
                return .other
            }
        default:
            return .other
        }
    }

    public static func classify(_ error: Error) -> ProbeFailureReason {
        classify(error, depth: 0)
    }

    private static func classify(_ error: Error, depth: Int) -> ProbeFailureReason {
        if let transportErr = error as? TransportError {
            return transportErr.reason
        }
        let nsError = error as NSError
        let reason = classify(domain: nsError.domain, code: nsError.code)
        if reason != .other || depth >= 4 {
            return reason
        }
        if let underlying = nsError.userInfo[NSUnderlyingErrorKey] as? Error {
            return classify(underlying, depth: depth + 1)
        }
        return .other
    }
}

/// 本地交互界面统一中文提示文案（不回显原始 URL 或 token）。
public enum ConnectionCopy: Sendable {
    public static func urlErrorText(_ category: HarnessUrl.ErrorCategory) -> String {
        switch category {
        case .empty:
            return "请输入 harness 地址"
        case .notAbsolute:
            return "请输入完整的 http:// 或 https:// 地址"
        case .unsupportedScheme:
            return "只支持 http:// 或 https:// 地址"
        case .malformed:
            return "地址格式不正确，请检查主机名与端口"
        }
    }

    public static func probeReasonTitle(_ reason: ProbeFailureReason) -> String {
        switch reason {
        case .dns:
            return "无法解析主机名"
        case .refused:
            return "连接被拒绝"
        case .timeout:
            return "连接超时"
        case .tls:
            return "安全连接（TLS）失败"
        case .other:
            return "连接失败"
        }
    }

    public static func probeHint(forCleanUrl cleanUrl: String) -> String {
        switch SetupCheck.errorHint(cleanUrl) {
        case .checkTailscale:
            return "请确认系统 Tailscale 已连接"
        case .checkAddress:
            return "请检查地址是否正确并确认 DSH 主机上的服务在运行"
        }
    }
}
