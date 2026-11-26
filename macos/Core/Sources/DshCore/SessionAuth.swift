import Foundation

/// dsh 会话失效判定与导航历史 token 检查（与桌面 `session.ts` 及 `SessionAuth.kt` 对齐）。
public enum SessionAuth: Sendable {
    public static let expiredMarker = "dsh web authentication required; reopen the URL printed by dsh web."

    /// 判断响应是否为 dsh 会话失效页：401、`text/plain`（允许带 charset 参数）且正文 trim 后等于固定标记。
    public static func isSessionExpired(status: Int, contentType: String?, body: String?) -> Bool {
        guard status == 401, let body else { return false }
        let baseType = contentType?
            .split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false)
            .first?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        return baseType == "text/plain" && body.trimmingCharacters(in: .whitespacesAndNewlines) == expiredMarker
    }

    /// 导航历史中是否存在含 `token` 参数且非当前条目的地址。
    public static func hasTokenInHistory(urls: [String], currentIndex: Int) -> Bool {
        for (index, url) in urls.enumerated() where index != currentIndex {
            if HarnessUrl.hasToken(url) { return true }
        }
        return false
    }
}
