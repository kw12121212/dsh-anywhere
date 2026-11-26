import Foundation

/// 探测失败原因分类（与桌面 `classifyNetError` 及 `ProbeFailure.kt` 对齐）。
public enum ProbeFailureReason: String, Equatable, Sendable, CaseIterable {
    case dns
    case refused
    case timeout
    case tls
    case other
}

public enum ProbeResult: Equatable, Sendable {
    case reachable
    case unreachable(ProbeFailureReason)
}

/// macOS 首次设置与设置页「先验证再保存」编排：
/// 格式校验与 token 剥离 → 注入式可达性探测（不内置 Tailscale；tailnet 主机仅用于出错提示）。
public enum SetupCheck: Sendable {
    public enum Stage: Equatable, Sendable {
        case probe
    }

    public enum Hint: Equatable, Sendable {
        case checkTailscale
        case checkAddress
    }

    public enum Result: Equatable, Sendable {
        case ok(cleanUrl: String)
        case invalidUrl(HarnessUrl.ErrorCategory)
        case unreachable(ProbeFailureReason)
    }

    public static func isTailnetUrl(_ cleanUrl: String) -> Bool {
        guard let host = HarnessUrl.extractHost(cleanUrl) else { return false }
        return HarnessUrl.isTailnetHost(host)
    }

    public static func errorHint(_ cleanUrl: String) -> Hint {
        isTailnetUrl(cleanUrl) ? .checkTailscale : .checkAddress
    }

    public static func verify(
        rawUrl: String,
        probe: (String) -> ProbeResult,
        onStage: (Stage) -> Void = { _ in }
    ) -> Result {
        let clean: String
        do {
            clean = try HarnessUrl.normalize(rawUrl)
        } catch let err as HarnessUrl.ValidationError {
            return .invalidUrl(err.category)
        } catch {
            return .invalidUrl(.malformed)
        }
        onStage(.probe)
        switch probe(clean) {
        case .reachable:
            return .ok(cleanUrl: clean)
        case .unreachable(let reason):
            return .unreachable(reason)
        }
    }
}
