import Foundation

/// 界面语言枚举与线程安全文案切换（与 `I18n.kt` 及 `ChatBars.Lang` 对齐）。
public enum I18n: Sendable {
    public enum Lang: Equatable, Sendable {
        case zh
        case en
    }

    private final class State: @unchecked Sendable {
        let lock = NSLock()
        var fixed: Lang = .zh
        var source: (@Sendable () -> Lang)?
    }

    private static let state = State()

    public static var source: (@Sendable () -> Lang)? {
        get {
            state.lock.lock()
            defer { state.lock.unlock() }
            return state.source
        }
        set {
            state.lock.lock()
            defer { state.lock.unlock() }
            state.source = newValue
        }
    }

    public static var lang: Lang {
        get {
            state.lock.lock()
            let src = state.source
            let fixed = state.fixed
            state.lock.unlock()
            return src?() ?? fixed
        }
        set {
            state.lock.lock()
            state.fixed = newValue
            state.lock.unlock()
        }
    }

    public static func tr(_ zh: String, _ en: String) -> String {
        lang == .en ? en : zh
    }

    /// 截断到 `max` 个 UTF-16 单元（含省略号 `"…"`），不切开代理对。
    public static func clip(_ s: String, maxUTF16 max: Int) -> String {
        guard max > 0 else { return "…" }
        let units = Array(s.utf16)
        guard units.count > max else { return s }
        var end = max - 1
        if end > 0 && UTF16.isLeadSurrogate(units[end - 1]) {
            end -= 1
        }
        let prefix = String(decoding: units[0..<end], as: UTF16.self)
        return prefix + "…"
    }
}
