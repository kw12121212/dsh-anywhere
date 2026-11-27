import Foundation

/// 单次发送结果：送达（2xx）、拒绝（4xx，整批丢弃不再重试）或失败（保留重试）。
public enum DiagSendOutcome: Equatable, Sendable {
    case delivered
    case rejected
    case failed
}

/// 诊断运行时依赖：队列与端点读取经注入的目录型存储实现，发送经注入闭包——
/// DshCore 不直接发起网络 IO（macos-client/dshcore-foundation-json-and-concurrency）。
public struct DiagnosticsRuntimeDeps: Sendable {
    public let queue: DiagnosticsQueue
    /// 读取已配置端点；未配置返回 nil（全部采集路径据此保持零行为）。
    public let loadEndpoint: @Sendable () -> String?
    /// POST 一批报告到目标地址；网络失败映射为 .failed（由装配层 catch）。
    public let send: @Sendable (_ target: String, _ body: String) async -> DiagSendOutcome
    public let appVersion: String
    public let device: String
    public let osVersion: String
    /// 可注入的时间源与报告 id 源（测试用）。
    public let nowMillis: @Sendable () -> Int64
    public let newId: @Sendable () -> String

    public init(
        queue: DiagnosticsQueue,
        loadEndpoint: @escaping @Sendable () -> String?,
        send: @escaping @Sendable (_ target: String, _ body: String) async -> DiagSendOutcome,
        appVersion: String,
        device: String,
        osVersion: String,
        nowMillis: @escaping @Sendable () -> Int64 = { Int64(Date.now.timeIntervalSince1970 * 1000) },
        newId: @escaping @Sendable () -> String = { UUID().uuidString.lowercased() }
    ) {
        self.queue = queue
        self.loadEndpoint = loadEndpoint
        self.send = send
        self.appVersion = appVersion
        self.device = device
        self.osVersion = osVersion
        self.nowMillis = nowMillis
        self.newId = newId
    }
}

/// 诊断上报运行时（diagnostics-reporting 规格，与桌面 diagnostics-runtime 同形语义）：
/// 崩溃类捕获在进程终止前同步落盘、下次启动补报；错误态同会话去重后即时尝试发送；
/// 发送失败静默保留在队列，4xx 丢弃该批；发送与重试绝不阻塞主流程。
public actor DiagnosticsRuntime {
    private let deps: DiagnosticsRuntimeDeps
    private let dedup = DiagErrorDedup()
    private var roundTask: Task<Void, Never>?

    public init(deps: DiagnosticsRuntimeDeps) {
        self.deps = deps
    }

    /// 崩溃类报告：同步原子落盘（进程即将终止，不做网络发送）。
    /// nonisolated：崩溃处理器可在任意执行上下文直接调用，不经过 actor 队列。
    public nonisolated func reportCrashSync(thread: String, message: String, stack: String) {
        guard deps.loadEndpoint() != nil else { return }
        deps.queue.add(build(kind: DiagKind.crash, thread: thread, message: message, stack: stack))
    }

    /// 加载失败错误态（同会话同原因至多一次），入队并触发一轮发送。
    public func recordLoadError(reason: String, redactedUrl: String) async {
        guard deps.loadEndpoint() != nil else { return }
        if dedup.seenBefore("load:\(reason)") {
            return
        }
        deps.queue.add(
            build(kind: DiagKind.error, thread: "main", message: "harness 加载失败（\(reason)）：\(redactedUrl)", stack: "")
        )
        await flush()
    }

    /// 一轮发送：分批发送待发队列直到排空或失败；已在役的一轮直接复用其结果。
    public func flush() async {
        if let existing = roundTask {
            await existing.value
            return
        }
        let task = Task { await self.runRound() }
        roundTask = task
        await task.value
        // actor 串行化下只有本轮创建者会走到这里；其余并发 flush 都在上方复用本轮结果。
        roundTask = nil
    }

    private func runRound() async {
        while true {
            guard let endpoint = deps.loadEndpoint() else { return }
            let batches = DiagBatching.batches(deps.queue.load())
            guard !batches.isEmpty else { return }
            let target = Self.reportsTarget(endpoint)
            for batch in batches {
                let body = DiagJson.encodeBatch(batch.map(\.report))
                let outcome = await deps.send(target, body)
                switch outcome {
                case .delivered, .rejected:
                    deps.queue.remove(batch.map(\.name))
                case .failed:
                    return
                }
            }
        }
    }

    /// 端点去掉结尾多余的 `/` 后拼上报路径。
    private static func reportsTarget(_ endpoint: String) -> String {
        var trimmed = endpoint
        while trimmed.hasSuffix("/") {
            trimmed.removeLast()
        }
        return trimmed + "/api/diagnostics/reports"
    }

    private nonisolated func build(kind: DiagKind, thread: String, message: String, stack: String) -> DiagReport {
        DiagReport(
            id: deps.newId(),
            kind: kind.rawValue,
            occurredAt: diagTimestamp(epochMillis: deps.nowMillis()),
            appVersionName: deps.appVersion,
            device: deps.device,
            osVersion: deps.osVersion,
            thread: thread,
            message: DiagText.truncateBytes(DiagText.sanitize(message), maxBytes: DiagLimits.maxMessageBytes),
            stackTrace: DiagText.truncateBytes(DiagText.sanitize(stack), maxBytes: DiagLimits.maxStackBytes),
            breadcrumbs: []
        )
    }
}
