import Foundation
import XCTest
@testable import DshCore

/// 诊断上报纯逻辑与运行时（diagnostics-reporting 规格）：脱敏、截断、编解码、
/// 队列文件语义、批量组包、去重与运行时零行为/崩溃/发送路径。
final class DiagnosticsTests: XCTestCase {
    private func makeTempDir(_ prefix: String) -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("\(prefix)\(UUID().uuidString)")
    }

    /// 可注入的时间源（Swift 6 下捕获局部 var 的闭包不满足 Sendable，经锁保护装箱）。
    private final class TickBox: @unchecked Sendable {
        let lock = NSLock()
        var value: Int64

        init(_ value: Int64) {
            self.value = value
        }

        func get() -> Int64 {
            lock.withLock { value }
        }

        func advance(_ inc: Int64) {
            lock.withLock { value += inc }
        }
    }

    private func makeReport(
        id: String = "r1",
        kind: String = "error",
        millis: Int64 = 1_700_000_000_000,
        message: String = "连接被拒绝",
        thread: String = "main"
    ) -> DiagReport {
        DiagReport(
            id: id,
            kind: kind,
            occurredAt: diagTimestamp(epochMillis: millis),
            appVersionName: "1.2.3",
            appVersionCode: 0,
            device: "Mac",
            osVersion: "macOS 14.0",
            thread: thread,
            message: message,
            stackTrace: "0 dshcore 0x1"
        )
    }

    // MARK: - 脱敏（token/凭据绝不离开设备；非敏感上下文保留）

    func testSanitizeUrlTokenAndBareToken() {
        let url = DiagText.sanitize("连接 https://dsh.example.test/?token=abc123 失败")
        XCTAssertFalse(url.contains("abc123"), "URL 中 token 值不得出现")
        XCTAssertTrue(url.contains("https://dsh.example.test/"), "脱敏后应保留干净地址")

        XCTAssertEqual("token=***", DiagText.sanitize("token=abc123"))
        XCTAssertEqual("next=1&token=***", DiagText.sanitize("next=1&token=abc123"))

        // 无法解析的地址不回显原文
        let bad = DiagText.sanitize("https://%zz%zz/?token=abc123")
        XCTAssertFalse(bad.contains("abc123"))
        XCTAssertFalse(bad.contains("%zz"))
    }

    func testSanitizeJsonSecretFields() {
        let json = DiagText.sanitize(#"{"secret":"s3cr3tVal","name":"keepName","apiKey":"sk-123"}"#)
        XCTAssertFalse(json.contains("s3cr3tVal"))
        XCTAssertFalse(json.contains("sk-123"))
        XCTAssertTrue(json.contains(#""secret":"***""#))
        XCTAssertTrue(json.contains(#""apiKey":"***""#))
        XCTAssertTrue(json.contains(#""name":"keepName""#), "非敏感字段名保留")
    }

    func testSanitizeSecretSetValue() {
        let payload = DiagText.sanitize(#"{"op":"secret-set","name":"myKey","value":"vTopSecret"}"#)
        XCTAssertFalse(payload.contains("vTopSecret"))
        XCTAssertTrue(payload.contains("***"))
        XCTAssertTrue(payload.contains(#""name":"myKey""#), "密钥名保留，仅脱敏 value")
    }

    func testSanitizeKeyValueAndEnvForms() {
        // 无引号、双引号与单引号包裹的值
        XCTAssertEqual("passphrase=***", DiagText.sanitize("passphrase=myPass"))
        XCTAssertEqual("passphrase=***", DiagText.sanitize(#"passphrase="my pass""#))
        XCTAssertEqual("passphrase=***", DiagText.sanitize("passphrase='my pass'"))
        // 环境变量形态
        XCTAssertEqual("DSH_SECRET_FOO=***", DiagText.sanitize("DSH_SECRET_FOO=bar123"))
        // cookie 形态
        XCTAssertEqual("dsh-auth=***", DiagText.sanitize("dsh-auth=xyzcookie"))
        // 键名保留
        XCTAssertTrue(DiagText.sanitize("DSH_SECRET_FOO=bar123").contains("DSH_SECRET_FOO"))
    }

    func testSanitizeAuthHeaderAndMcpToken() {
        XCTAssertEqual("Authorization: Bearer ***", DiagText.sanitize("Authorization: Bearer eyJhbGciOiJIUzI1"))
        XCTAssertEqual("Authorization: Basic ***", DiagText.sanitize("Authorization: Basic dXNlcjpwYXNz"))
        let mcp = DiagText.sanitize("客户端令牌 dsb_AbCdEfGh1234 已过期")
        XCTAssertEqual("客户端令牌 dsb_*** 已过期", mcp)
        // 普通词不受 dsb_ 规则影响
        XCTAssertEqual("正常文本", DiagText.sanitize("正常文本"))
    }

    func testSanitizeBreadcrumbTruncates() {
        let long = String(repeating: "a", count: 2_000)
        let line = DiagText.breadcrumb(long)
        XCTAssertLessThanOrEqual(Array(line.utf8).count, DiagLimits.maxBreadcrumbBytes)
    }

    // MARK: - 截断与时间戳

    func testTruncateBytesMultibyteSafe() {
        // 每个汉字 3 字节：截到 5 字节回退到 3 字节边界；截到 6 字节恰为两个字符
        XCTAssertEqual("你", DiagText.truncateBytes("你好世界", maxBytes: 5))
        XCTAssertEqual("你好", DiagText.truncateBytes("你好世界", maxBytes: 6))
        XCTAssertEqual("你好世界", DiagText.truncateBytes("你好世界", maxBytes: 100))
        XCTAssertEqual("", DiagText.truncateBytes("你", maxBytes: 0))
    }

    func testDiagTimestampFormat() {
        XCTAssertEqual("1970-01-01T00:00:00.000Z", diagTimestamp(epochMillis: 0))
        XCTAssertEqual("2023-11-14T22:13:20.123Z", diagTimestamp(epochMillis: 1_700_000_000_123))
        XCTAssertEqual("2000-02-29T12:00:00.999Z", diagTimestamp(epochMillis: 951_825_600_999))
    }

    // MARK: - 报告编解码

    func testEncodeDecodeRoundTripAndPlatform() {
        let report = makeReport(id: "id-7", kind: "crash", millis: 1_700_000_001_234, message: "msg \"引号\"\n换行", thread: "com.apple.main-thread")
        let json = DiagJson.encodeReport(report)
        XCTAssertTrue(json.contains(#""platform":"dsh_desktop""#), "原生 macOS 复用 dsh_desktop 枚举")
        XCTAssertEqual(report, DiagJson.decodeReport(json))

        let batch = DiagJson.encodeBatch([report, makeReport(id: "r2")])
        XCTAssertTrue(batch.hasPrefix(#"{"reports":[{"id""#))
        XCTAssertTrue(batch.hasSuffix("}]}"))
    }

    func testDecodeReportRejectsMalformedInput() {
        // 语法错误
        XCTAssertNil(DiagJson.decodeReport("{not json"), "语法错误应返回 nil")
        // 缺字段：去掉 thread
        let full = DiagJson.encodeReport(makeReport())
        let noThread = full.replacingOccurrences(of: #""thread":"main","#, with: "")
        XCTAssertNil(DiagJson.decodeReport(noThread), "缺 thread 字段应返回 nil")
        // 类型错误：breadcrumbs 混入数字
        let badBreadcrumbs = full.replacingOccurrences(of: #"[]"#, with: "[1]")
        XCTAssertNil(DiagJson.decodeReport(badBreadcrumbs), "breadcrumbs 非字符串应返回 nil")
        // app_version_code 非数字
        let badCode = full.replacingOccurrences(of: #""app_version_code":0"#, with: #""app_version_code":"x""#)
        XCTAssertNil(DiagJson.decodeReport(badCode), "app_version_code 非数字应返回 nil")
        // 非 JSON 对象
        XCTAssertNil(DiagJson.decodeReport(#""text""#))
    }

    // MARK: - 有界待发队列

    func testQueueAtomicAddLoadRemoveAndPermissions() throws {
        let dir = makeTempDir("dsh-diag-queue-")
        defer { try? FileManager.default.removeItem(at: dir) }
        let tick = TickBox(1_700_000_000_000)
        let queue = DiagnosticsQueue(directory: dir, nowMillis: { tick.get() })

        queue.add(makeReport(id: "a"))
        tick.advance(5)
        queue.add(makeReport(id: "b"))

        let loaded = queue.load()
        XCTAssertEqual(["a", "b"], loaded.map(\.report.id), "按文件名字典序（时间序）返回")
        XCTAssertEqual("0000001700000000000-a.json", loaded[0].name, "文件名补零到 19 位")

        let fileURL = dir.appendingPathComponent(loaded[0].name)
        let attrs = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        XCTAssertEqual(0o600, (attrs[.posixPermissions] as? NSNumber)?.int32Value ?? 0, "队列文件权限应为 0600")
        let leftovers = try FileManager.default.contentsOfDirectory(atPath: dir.path)
            .filter { $0.hasSuffix(".tmp") }
        XCTAssertTrue(leftovers.isEmpty, "不得残留临时文件: \(leftovers)")

        queue.remove([loaded[0].name])
        XCTAssertEqual(["b"], queue.load().map(\.report.id))
        XCTAssertEqual(1, queue.load().count)

        queue.clear()
        XCTAssertTrue(queue.load().isEmpty, "clear 后队列为空")
    }

    func testQueueEvictsOldestBeyondCapacity() {
        let dir = makeTempDir("dsh-diag-queue-cap-")
        defer { try? FileManager.default.removeItem(at: dir) }
        let tick = TickBox(1_700_000_000_000)
        let queue = DiagnosticsQueue(directory: dir, capacity: 2, nowMillis: { tick.get() })

        for id in ["a", "b", "c"] {
            queue.add(makeReport(id: id))
            tick.advance(7)
        }
        XCTAssertEqual(["b", "c"], queue.load().map(\.report.id), "超限丢最旧")
    }

    func testQueueDropsCorruptFilesOnLoad() throws {
        let dir = makeTempDir("dsh-diag-queue-corrupt-")
        defer { try? FileManager.default.removeItem(at: dir) }
        let tick = TickBox(1_700_000_000_000)
        let queue = DiagnosticsQueue(directory: dir, nowMillis: { tick.get() })
        queue.add(makeReport(id: "good"))
        tick.advance(3)
        queue.add(makeReport(id: "bad"))

        // 把第二份写成损坏内容
        let names = try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix(".json") }.sorted()
        try Data("半份文件".utf8).write(to: dir.appendingPathComponent(names[1]))
        let alsoBroken = dir.appendingPathComponent("0001700000000999-broken.json")
        try Data("not-json".utf8).write(to: alsoBroken)

        XCTAssertEqual(["good"], queue.load().map(\.report.id), "损坏文件按没有报告处理")
        XCTAssertFalse(FileManager.default.fileExists(atPath: alsoBroken.path), "损坏文件读取时被删除")
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.appendingPathComponent(names[1]).path))
    }

    // MARK: - 批量组包与去重

    func testBatchingSplitsByCountBytesAndKeepsOrder() {
        var items: [DiagnosticsQueue.Named] = []
        for i in 0..<45 {
            items.append(DiagnosticsQueue.Named(name: "n\(i)", report: makeReport(id: "r\(i)")))
        }
        let batches = DiagBatching.batches(items)
        XCTAssertEqual(3, batches.count)
        XCTAssertEqual(20, batches[0].count)
        XCTAssertEqual(20, batches[1].count)
        XCTAssertEqual(5, batches[2].count)
        XCTAssertEqual("r0", batches[0][0].report.id, "保持时间序")

        // 字节数切批：两份各约 600 KiB 的报告不能同批
        let big1 = DiagnosticsQueue.Named(name: "big1", report: makeReport(id: "big1", message: String(repeating: "m", count: 600 * 1_024)))
        let big2 = DiagnosticsQueue.Named(name: "big2", report: makeReport(id: "big2", message: String(repeating: "n", count: 600 * 1_024)))
        let bigBatches = DiagBatching.batches([big1, big2])
        XCTAssertEqual(2, bigBatches.count, "超过 1 MiB 上限分批")
    }

    func testErrorDedupOncePerKey() {
        let dedup = DiagErrorDedup()
        XCTAssertFalse(dedup.seenBefore("load:refused"))
        XCTAssertTrue(dedup.seenBefore("load:refused"), "同键第二次返回已见")
        XCTAssertFalse(dedup.seenBefore("load:dns"), "不同键各自计数")
        dedup.reset()
        XCTAssertFalse(dedup.seenBefore("load:refused"), "reset 后重新计数")
    }

    // MARK: - 运行时

    private final class SendBox: @unchecked Sendable {
        let lock = NSLock()
        var calls: [(target: String, body: String)] = []
        var outcome: DiagSendOutcome = .delivered

        func record(_ target: String, _ body: String) -> DiagSendOutcome {
            lock.withLock {
                calls.append((target, body))
                return outcome
            }
        }
    }

    private func makeRuntime(
        endpoint: String?,
        queueDir: URL,
        sendBox: SendBox,
        nowMillis: @escaping @Sendable () -> Int64 = { 1_700_000_000_000 }
    ) -> DiagnosticsRuntime {
        DiagnosticsRuntime(
            deps: DiagnosticsRuntimeDeps(
                queue: DiagnosticsQueue(directory: queueDir, nowMillis: nowMillis),
                loadEndpoint: { endpoint },
                send: { target, body in sendBox.record(target, body) },
                appVersion: "1.2.3",
                device: "Mac",
                osVersion: "macOS 14.0",
                nowMillis: nowMillis,
                newId: { "fixed-id" }
            )
        )
    }

    func testUnconfiguredEndpointIsZeroBehavior() async {
        let dir = makeTempDir("dsh-diag-rt-zero-")
        defer { try? FileManager.default.removeItem(at: dir) }
        let sendBox = SendBox()
        let runtime = makeRuntime(endpoint: nil, queueDir: dir, sendBox: sendBox)

        runtime.reportCrashSync(thread: "main", message: "FatalError", stack: "stack token=abc123")
        await runtime.recordLoadError(reason: "refused", redactedUrl: "https://dsh.example.test/")
        await runtime.flush()

        XCTAssertTrue(sendBox.calls.isEmpty, "未配置端点不得发送")
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.path), "未配置端点不得创建队列目录或文件")
    }

    func testCrashSyncPersistsWithoutNetworkSend() async throws {
        let dir = makeTempDir("dsh-diag-rt-crash-")
        defer { try? FileManager.default.removeItem(at: dir) }
        let sendBox = SendBox()
        let runtime = makeRuntime(endpoint: "https://diag.example.ts.net:18790/", queueDir: dir, sendBox: sendBox)

        runtime.reportCrashSync(thread: "main", message: "NSException: 崩溃 token=abc123", stack: "0 app 0x1\n1 app 0x2")

        let queued = DiagnosticsQueue(directory: dir, nowMillis: { 0 }).load()
        XCTAssertEqual(1, queued.count, "崩溃报告同步落盘")
        XCTAssertEqual("crash", queued[0].report.kind)
        XCTAssertEqual("main", queued[0].report.thread)
        XCTAssertFalse(queued[0].report.message.contains("abc123"), "崩溃消息经脱敏")
        XCTAssertTrue(sendBox.calls.isEmpty, "崩溃路径不做网络发送")
    }

    func testRecordLoadErrorDedupsAndFlushes() async {
        let dir = makeTempDir("dsh-diag-rt-error-")
        defer { try? FileManager.default.removeItem(at: dir) }
        let sendBox = SendBox()
        let runtime = makeRuntime(endpoint: "https://diag.example.ts.net:18790", queueDir: dir, sendBox: sendBox)

        await runtime.recordLoadError(reason: "refused", redactedUrl: "https://dsh.example.test/")
        await runtime.recordLoadError(reason: "refused", redactedUrl: "https://dsh.example.test/")
        await runtime.recordLoadError(reason: "dns", redactedUrl: "https://dsh.example.test/")

        XCTAssertEqual(2, sendBox.calls.count, "同会话同原因只报一次，不同原因各一次")
        for call in sendBox.calls {
            XCTAssertTrue(call.target.hasSuffix("/api/diagnostics/reports"), "上报打到端点的 reports 路径: \(call.target)")
            XCTAssertEqual("https://diag.example.ts.net:18790/api/diagnostics/reports", call.target)
            XCTAssertTrue(call.body.contains("harness 加载失败"), "错误态消息含原因")
        }
        XCTAssertTrue(DiagnosticsQueue(directory: dir, nowMillis: { 0 }).load().isEmpty, "送达后队列排空")
    }

    func testFlushFailedRetainsQueueAndRejectedDropsBatch() async {
        let dir = makeTempDir("dsh-diag-rt-fail-")
        defer { try? FileManager.default.removeItem(at: dir) }
        let sendBox = SendBox()
        // 预置待发报告（模拟上次会话遗留）
        let queue = DiagnosticsQueue(directory: dir, nowMillis: { 1_700_000_000_000 })
        queue.add(makeReport(id: "old", kind: "crash"))

        // 网络失败：保留队列
        sendBox.outcome = .failed
        await makeRuntime(endpoint: "https://diag.example.ts.net/", queueDir: dir, sendBox: sendBox).flush()
        XCTAssertEqual(1, sendBox.calls.count)
        XCTAssertEqual(1, DiagnosticsQueue(directory: dir, nowMillis: { 0 }).load().count, "失败静默保留待发")

        // 4xx：整批丢弃不再重试
        sendBox.outcome = .rejected
        sendBox.calls.removeAll()
        await makeRuntime(endpoint: "https://diag.example.ts.net/", queueDir: dir, sendBox: sendBox).flush()
        XCTAssertEqual(1, sendBox.calls.count)
        XCTAssertTrue(DiagnosticsQueue(directory: dir, nowMillis: { 0 }).load().isEmpty, "4xx 丢弃该批")
    }

    func testFlushDrainsUntilEmpty() async {
        let dir = makeTempDir("dsh-diag-rt-drain-")
        defer { try? FileManager.default.removeItem(at: dir) }
        let sendBox = SendBox()
        let tick = TickBox(1_700_000_000_000)
        let queue = DiagnosticsQueue(directory: dir, nowMillis: { tick.get() })
        for i in 0..<45 {
            queue.add(makeReport(id: "r\(i)"))
            tick.advance(1)
        }

        let runtime = makeRuntime(endpoint: "https://diag.example.ts.net/", queueDir: dir, sendBox: sendBox, nowMillis: { tick.get() })
        await runtime.flush()

        XCTAssertEqual(3, sendBox.calls.count, "45 份按 ≤20 分 3 批")
        let totalReports = sendBox.calls.reduce(0) { count, call in
            count + call.body.components(separatedBy: #""id":"#).count - 1
        }
        XCTAssertEqual(45, totalReports)
        XCTAssertTrue(DiagnosticsQueue(directory: dir, nowMillis: { 0 }).load().isEmpty, "排空后队列清空")
    }

    func testReportTargetTrimsTrailingSlashes() async {
        let dir = makeTempDir("dsh-diag-rt-target-")
        defer { try? FileManager.default.removeItem(at: dir) }
        let sendBox = SendBox()
        let runtime = makeRuntime(endpoint: "https://diag.example.ts.net:18790///", queueDir: dir, sendBox: sendBox)
        runtime.reportCrashSync(thread: "main", message: "x", stack: "")
        sendBox.outcome = .failed
        await runtime.flush()
        // flush 失败保留；只验证目标拼接待发送调用
        XCTAssertEqual(1, sendBox.calls.count)
        XCTAssertEqual("https://diag.example.ts.net:18790/api/diagnostics/reports", sendBox.calls[0].target)
    }
}
