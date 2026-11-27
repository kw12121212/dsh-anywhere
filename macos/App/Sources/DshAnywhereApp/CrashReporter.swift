import DshCore
import Foundation

/// 崩溃捕获装配（diagnostics-reporting 规格 / design 决策 crash-capture-signal-and-exception）：
/// `NSSetUncaughtExceptionHandler` 捕获 NSException，`signal` 注册 SIGABRT/SIGSEGV/SIGBUS/
/// SIGILL/SIGFPE/SIGTRAP 兜底 Swift 运行时崩溃；处理路径只经 `DiagnosticsRuntime.reportCrashSync`
/// 同步原子落盘（未配置端点时零行为、不发任何网络请求），随后恢复系统默认处置并 re-raise。
/// C 函数指针无法捕获上下文，处理器经本单例取运行时引用；`crashReportWritten` 防止
/// 「未捕获异常先写、abort 再触发信号处理器」的双写。信号上下文调用 Foundation 并非严格
/// async-signal-safe（design.yaml 已记录风险）：写失败静默放弃该份报告，不阻断终止。
enum CrashReporter {
    private nonisolated(unsafe) static var runtime: DiagnosticsRuntime?
    private nonisolated(unsafe) static var crashReportWritten = false

    /// 安装崩溃处理器；运行时引用与报告骨架在安装前已解析完毕（崩溃路径零额外装配）。
    static func install(runtime: DiagnosticsRuntime) {
        self.runtime = runtime
        // 预热脱敏正则（DiagText 的静态正则为惰性初始化）：避免首次报告恰好发生在
        // 信号处理器内时才触发 NSRegularExpression 编译（信号上下文 malloc 有挂起风险）。
        _ = DiagText.sanitize("")
        NSSetUncaughtExceptionHandler(dshUncaughtExceptionHandler)
        for sig in [SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGTRAP] {
            signal(sig, dshSignalHandler)
        }
    }

    static func writeCrashReport(thread: String, message: String, stack: String) {
        guard !crashReportWritten else { return }
        crashReportWritten = true
        runtime?.reportCrashSync(thread: thread, message: message, stack: stack)
    }
}

private func dshUncaughtExceptionHandler(_ exception: NSException) {
    CrashReporter.writeCrashReport(
        thread: Thread.isMainThread ? "main" : "unknown",
        message: "\(exception.name.rawValue): \(exception.reason ?? "")",
        stack: exception.callStackSymbols.joined(separator: "\n")
    )
    // 处理器返回后运行时仍按默认行为终止进程（随后 abort 触发的信号处理器被 crashReportWritten 拦截）。
}

private func dshSignalHandler(_ sig: Int32) {
    signal(sig, SIG_DFL)
    CrashReporter.writeCrashReport(
        thread: Thread.isMainThread ? "main" : "unknown",
        message: "致命信号 \(signalName(sig))(\(sig))",
        stack: Thread.callStackSymbols.joined(separator: "\n")
    )
    raise(sig)
}

private func signalName(_ sig: Int32) -> String {
    switch sig {
    case SIGABRT: return "SIGABRT"
    case SIGSEGV: return "SIGSEGV"
    case SIGBUS: return "SIGBUS"
    case SIGILL: return "SIGILL"
    case SIGFPE: return "SIGFPE"
    case SIGTRAP: return "SIGTRAP"
    default: return "SIG\(sig)"
    }
}
