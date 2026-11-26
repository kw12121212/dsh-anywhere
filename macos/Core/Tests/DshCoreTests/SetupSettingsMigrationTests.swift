import Foundation
import XCTest
@testable import DshCore

final class SetupSettingsMigrationTests: XCTestCase {
    private func makeTempDir(_ prefix: String = "dsh-setup-settings-") -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("\(prefix)\(UUID().uuidString)")
    }

    func testDiagnosticsEndpointValidationAndStoreRoundTrip() throws {
        // 合法 https 端点规范化
        XCTAssertEqual(
            "https://gamepc.example.ts.net:18790/",
            DiagnosticsEndpointConfig.validate("  HTTPS://GamePC.Example.TS.NET:18790  ")
        )
        XCTAssertEqual("https://a.example.test/", DiagnosticsEndpointConfig.validate("https://a.example.test"))
        XCTAssertEqual("https://a.example.test/", DiagnosticsEndpointConfig.validate("https://a.example.test:443/"))
        XCTAssertEqual("https://a.example.test/p", DiagnosticsEndpointConfig.validate("https://a.example.test/p#frag"))
        XCTAssertEqual("https://[::1]:8443/diag", DiagnosticsEndpointConfig.validate("https://[::1]:8443/diag"))

        // 非法端点拒绝
        for bad in [
            "",
            "   ",
            "http://a.example.test/",
            "ftp://a.example.test/",
            "https://a.example.test/p?q=1",
            "https://user:pass@a.example.test/",
            "https://a.example.test/%zz",
            "not a url",
            "/diag",
        ] {
            XCTAssertNil(DiagnosticsEndpointConfig.validate(bad), "应拒绝诊断端点: \(bad)")
        }

        let dir = makeTempDir("dsh-diag-store-")
        defer { try? FileManager.default.removeItem(at: dir) }

        let store = DiagnosticsEndpointStore(directory: dir)
        XCTAssertFalse(store.exists())
        XCTAssertEqual(.missing, store.load())

        let saved = try store.save("https://diag.example.ts.net:18790")
        XCTAssertEqual("https://diag.example.ts.net:18790/", saved)
        XCTAssertTrue(store.exists())
        XCTAssertEqual(.ok("https://diag.example.ts.net:18790/"), store.load())

        let fileURL = store.fileURL
        let rawText = try String(contentsOf: fileURL, encoding: .utf8)
        XCTAssertEqual("{\n  \"diagnosticsEndpoint\": \"https://diag.example.ts.net:18790/\"\n}\n", rawText)

        let attrs = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        let perms = (attrs[.posixPermissions] as? NSNumber)?.intValue
        XCTAssertEqual(0o600, (perms ?? 0) & 0o777, "diagnostics.json 权限应为 0600")

        let leftovers = try FileManager.default.contentsOfDirectory(atPath: dir.path)
            .filter { $0.hasPrefix(DiagnosticsEndpointConfig.tmpPrefix) }
        XCTAssertTrue(leftovers.isEmpty, "不得残留临时文件: \(leftovers)")

        // 写入非法地址抛错且不破坏既有配置
        XCTAssertThrowsError(try store.save("http://insecure.test/"))
        XCTAssertEqual(.ok("https://diag.example.ts.net:18790/"), store.load())

        // 损坏文件按 invalid 处理
        try Data("{not-json".utf8).write(to: fileURL)
        XCTAssertEqual(.invalid, store.load())

        store.clear()
        XCTAssertFalse(store.exists())
        XCTAssertEqual(.missing, store.load())
    }

    func testElectronConfigMigratorReadOnlyPriorityAndGuards() throws {
        let root = makeTempDir("dsh-migrate-")
        let fm = FileManager.default
        let primaryElectron = root.appendingPathComponent("DSH Anywhere", isDirectory: true)
        let fallbackElectron = root.appendingPathComponent("dsh-anywhere", isDirectory: true)
        let nativeDir = root.appendingPathComponent("DSH Anywhere Native", isDirectory: true)

        defer {
            try? fm.setAttributes([.posixPermissions: NSNumber(value: 0o755)], ofItemAtPath: primaryElectron.path)
            try? fm.removeItem(at: root)
        }

        try fm.createDirectory(at: primaryElectron, withIntermediateDirectories: true)
        try fm.createDirectory(at: fallbackElectron, withIntermediateDirectories: true)

        let primaryConfigData = Data("{\n  \"harnessURL\": \"https://dsh.example.test/?token=old_tok_999\"\n}\n".utf8)
        let primaryDiagData = Data("{\n  \"diagnosticsEndpoint\": \"https://diag.example.ts.net:18790\"\n}\n".utf8)
        let cookiesData = Data("SQLite format 3\0mock_cookie_bytes".utf8)

        let primaryConfigUrl = primaryElectron.appendingPathComponent("config.json")
        let primaryDiagUrl = primaryElectron.appendingPathComponent("diagnostics.json")
        let primaryCookiesUrl = primaryElectron.appendingPathComponent("Cookies")
        try primaryConfigData.write(to: primaryConfigUrl)
        try primaryDiagData.write(to: primaryDiagUrl)
        try cookiesData.write(to: primaryCookiesUrl)

        // 备用候选目录有不同配置，验证主目录优先
        try Data("{\n  \"harnessURL\": \"https://fallback.example.test/\"\n}\n".utf8)
            .write(to: fallbackElectron.appendingPathComponent("config.json"))

        // 将源目录和文件设为只读，验证迁移全程零写入
        try fm.setAttributes([.posixPermissions: NSNumber(value: 0o444)], ofItemAtPath: primaryConfigUrl.path)
        try fm.setAttributes([.posixPermissions: NSNumber(value: 0o444)], ofItemAtPath: primaryDiagUrl.path)
        try fm.setAttributes([.posixPermissions: NSNumber(value: 0o444)], ofItemAtPath: primaryCookiesUrl.path)
        try fm.setAttributes([.posixPermissions: NSNumber(value: 0o555)], ofItemAtPath: primaryElectron.path)

        let candidates = ElectronConfigMigrator.defaultElectronDirectories(applicationSupportDirectory: root)
        let res = ElectronConfigMigrator.migrateIfNeeded(nativeDirectory: nativeDir, electronDirectories: candidates)

        XCTAssertEqual("https://dsh.example.test/", res.migratedHarnessUrl)
        XCTAssertEqual("https://diag.example.ts.net:18790/", res.migratedDiagnosticsEndpoint)
        XCTAssertTrue(res.didMigrateHarnessUrl)
        XCTAssertTrue(res.didMigrateAnything)

        // 原生目录落盘干净地址与诊断端点，且无 cookie 文件
        XCTAssertEqual(.ok("https://dsh.example.test/"), HarnessConfigStore(directory: nativeDir).load())
        XCTAssertEqual(.ok("https://diag.example.ts.net:18790/"), DiagnosticsEndpointStore(directory: nativeDir).load())
        XCTAssertFalse(fm.fileExists(atPath: nativeDir.appendingPathComponent(SessionCookieStore.fileName).path))
        XCTAssertFalse(fm.fileExists(atPath: nativeDir.appendingPathComponent("Cookies").path))

        // Electron 源文件字节保持绝对不变
        XCTAssertEqual(primaryConfigData, try Data(contentsOf: primaryConfigUrl))
        XCTAssertEqual(primaryDiagData, try Data(contentsOf: primaryDiagUrl))
        XCTAssertEqual(cookiesData, try Data(contentsOf: primaryCookiesUrl))

        // 若用户随后清除原生 diagnostics.json，再次运行迁移不得回灌旧 Electron 端点
        DiagnosticsEndpointStore(directory: nativeDir).clear()
        let secondRun = ElectronConfigMigrator.migrateIfNeeded(nativeDirectory: nativeDir, electronDirectories: candidates)
        XCTAssertEqual(ElectronConfigMigrator.MigrationResult(), secondRun)
        XCTAssertEqual(.missing, DiagnosticsEndpointStore(directory: nativeDir).load())
    }

    func testElectronConfigMigratorFallbackAndCorruptGuards() throws {
        let root = makeTempDir("dsh-migrate-guard-")
        defer { try? FileManager.default.removeItem(at: root) }
        let fm = FileManager.default

        let primary = root.appendingPathComponent("DSH Anywhere", isDirectory: true)
        let fallback = root.appendingPathComponent("dsh-anywhere", isDirectory: true)
        try fm.createDirectory(at: primary, withIntermediateDirectories: true)
        try fm.createDirectory(at: fallback, withIntermediateDirectories: true)

        // 主目录配置损坏、诊断端点非法 -> 回退到第二个候选目录
        try Data("{bad".utf8).write(to: primary.appendingPathComponent("config.json"))
        try Data("{\"diagnosticsEndpoint\":\"http://insecure.test/\"}".utf8)
            .write(to: primary.appendingPathComponent("diagnostics.json"))
        try Data("{\"harnessURL\":\"https://fallback.example.test/?token=old\"}".utf8)
            .write(to: fallback.appendingPathComponent("config.json"))
        try Data("{corrupt-diag".utf8).write(to: fallback.appendingPathComponent("diagnostics.json"))

        let native1 = root.appendingPathComponent("Native1", isDirectory: true)
        let res1 = ElectronConfigMigrator.migrateIfNeeded(
            nativeDirectory: native1,
            electronDirectories: [primary, fallback]
        )
        XCTAssertEqual("https://fallback.example.test/", res1.migratedHarnessUrl)
        XCTAssertNil(res1.migratedDiagnosticsEndpoint)
        XCTAssertEqual(.ok("https://fallback.example.test/"), HarnessConfigStore(directory: native1).load())
        XCTAssertFalse(DiagnosticsEndpointStore(directory: native1).exists())

        // 原生已有损坏 config.json 时绝不触发迁移覆盖
        let nativeCorrupt = root.appendingPathComponent("NativeCorrupt", isDirectory: true)
        try fm.createDirectory(at: nativeCorrupt, withIntermediateDirectories: true)
        let corruptRaw = Data("{\"harnessURL\":\"ftp://bad.example.test/\"}".utf8)
        try corruptRaw.write(to: nativeCorrupt.appendingPathComponent(HarnessConfig.fileName))

        let res2 = ElectronConfigMigrator.migrateIfNeeded(
            nativeDirectory: nativeCorrupt,
            electronDirectories: [fallback]
        )
        XCTAssertEqual(ElectronConfigMigrator.MigrationResult(), res2)
        XCTAssertEqual(corruptRaw, try Data(contentsOf: nativeCorrupt.appendingPathComponent(HarnessConfig.fileName)))
    }

    func testControllerElectronMigrationReauthAndSetupWriteFailure() async throws {
        let root = makeTempDir("dsh-ctrl-migrate-")
        defer { try? FileManager.default.removeItem(at: root) }
        let fm = FileManager.default

        let electronDir = root.appendingPathComponent("DSH Anywhere", isDirectory: true)
        let nativeDir = root.appendingPathComponent("DSH Anywhere Native", isDirectory: true)
        try fm.createDirectory(at: electronDir, withIntermediateDirectories: true)
        try Data("{\"harnessURL\":\"http://127.0.0.1:13080/?token=stale_tok\"}".utf8)
            .write(to: electronDir.appendingPathComponent("config.json"))
        try Data("{\"diagnosticsEndpoint\":\"https://diag.example.ts.net:18790/\"}".utf8)
            .write(to: electronDir.appendingPathComponent("diagnostics.json"))

        let stateJson = try TestFixtures.dsbotText("state-full.json")
        let updateJson = try TestFixtures.dsbotText("update-check.json")
        let helloJson = try TestFixtures.dsbotText("hello.json")

        let probe = StubHttpTransport()
        probe.defaultHandler = { _ in HttpResponse(statusCode: 200, body: Data("ok".utf8)) }

        let api = StubHttpTransport()
        api.handler = { req in
            if req.method == "GET" && req.url == "http://127.0.0.1:13080/?token=fresh_tok" {
                return HttpResponse(
                    statusCode: 200,
                    headers: [("Set-Cookie", "dsh-auth-m=v1.migrated; Path=/; HttpOnly; SameSite=Strict")],
                    body: Data("ok".utf8)
                )
            }
            let body = String(decoding: req.body ?? Data(), as: UTF8.self)
            if body.contains("update-check") { return HttpResponse(statusCode: 200, body: Data(updateJson.utf8)) }
            if body.contains("hello") { return HttpResponse(statusCode: 200, body: Data(helloJson.utf8)) }
            return HttpResponse(statusCode: 200, body: Data(stateJson.utf8))
        }

        let controller = MacosSessionController(
            configStore: HarnessConfigStore(directory: nativeDir),
            cookieStore: SessionCookieStore(directory: nativeDir),
            probeTransport: probe,
            apiTransport: api,
            sseStream: HoldingSseStream(),
            electronConfigDirectories: [electronDir]
        )

        await controller.start()
        try await waitUntil {
            await controller.currentSnapshot().screen == .authExpired
        }
        var snap = await controller.currentSnapshot()
        XCTAssertEqual("http://127.0.0.1:13080/", snap.cleanUrl)
        XCTAssertTrue(snap.migratedFromElectron)
        XCTAssertTrue(snap.migratedDiagnosticsEndpoint)

        // 用户粘贴新带 token 地址，无需重启即恢复在线且清除迁移提示标志
        await controller.submitUrl("http://127.0.0.1:13080/?token=fresh_tok")
        try await waitUntil {
            let s = await controller.currentSnapshot()
            return s.screen == .connected && s.status == .online
        }
        snap = await controller.currentSnapshot()
        XCTAssertFalse(snap.migratedFromElectron)
        await controller.stop()

        // 原生 config.json 损坏时进入 .setup(invalidConfig: true) 且不迁移
        let corruptNativeDir = root.appendingPathComponent("CorruptNative", isDirectory: true)
        try fm.createDirectory(at: corruptNativeDir, withIntermediateDirectories: true)
        try Data("not-json".utf8).write(to: corruptNativeDir.appendingPathComponent("config.json"))
        let corruptCtrl = MacosSessionController(
            configStore: HarnessConfigStore(directory: corruptNativeDir),
            cookieStore: SessionCookieStore(directory: corruptNativeDir),
            probeTransport: probe,
            apiTransport: api,
            sseStream: HoldingSseStream(),
            electronConfigDirectories: [electronDir]
        )
        await corruptCtrl.start()
        let corruptSnap = await corruptCtrl.currentSnapshot()
        XCTAssertEqual(.setup(invalidConfig: true), corruptSnap.screen)
        XCTAssertFalse(corruptSnap.migratedFromElectron)
        await corruptCtrl.stop()

        // 首次设置保存目录不可写时停留在 setup，且错误信息不含 token
        let fileAsDir = root.appendingPathComponent("blocked-file")
        try Data("blocker".utf8).write(to: fileAsDir)
        let blockedCtrl = MacosSessionController(
            configStore: HarnessConfigStore(directory: fileAsDir),
            cookieStore: SessionCookieStore(directory: fileAsDir),
            probeTransport: probe,
            apiTransport: api,
            sseStream: HoldingSseStream()
        )
        await blockedCtrl.start()
        await blockedCtrl.submitUrl("http://127.0.0.1:13080/?token=tok_secret_write")
        let blockedSnap = await blockedCtrl.currentSnapshot()
        XCTAssertEqual(.setup(invalidConfig: false), blockedSnap.screen)
        XCTAssertNotNil(blockedSnap.formError)
        XCTAssertFalse(blockedSnap.formError?.contains("tok_secret_write") ?? false)
        await blockedCtrl.stop()
    }

    func testControllerSaveSettingsUrlPaths() async throws {
        let root = makeTempDir("dsh-save-settings-")
        defer { try? FileManager.default.removeItem(at: root) }

        let nativeDir = root.appendingPathComponent("Native", isDirectory: true)
        let configStore = HarnessConfigStore(directory: nativeDir)
        _ = try configStore.save("https://a.example.test/")
        let cookieStore = SessionCookieStore(directory: nativeDir)
        try cookieStore.replaceCookies([SessionCookies.Cookie(name: "dsh-auth-a", value: "v1.a")], forUrl: "https://a.example.test/")

        let stateJson = try TestFixtures.dsbotText("state-full.json")
        let updateJson = try TestFixtures.dsbotText("update-check.json")
        let helloJson = try TestFixtures.dsbotText("hello.json")

        let probe = StubHttpTransport()
        probe.handler = { req in
            if req.url == "http://127.0.0.1:13081/" {
                throw TransportError(.refused)
            }
            return HttpResponse(statusCode: 200, body: Data("ok".utf8))
        }

        let api = StubHttpTransport()
        api.handler = { req in
            if req.method == "GET" && req.url == "http://127.0.0.1:13080/?token=def456" {
                return HttpResponse(
                    statusCode: 200,
                    headers: [("Set-Cookie", "dsh-auth-b=v1.b; Path=/; HttpOnly; SameSite=Strict")],
                    body: Data("ok".utf8)
                )
            }
            if req.method == "GET" && req.url == "http://127.0.0.1:13080/?token=bad_tok" {
                return HttpResponse(statusCode: 401, body: Data("rejected".utf8))
            }
            let body = String(decoding: req.body ?? Data(), as: UTF8.self)
            if body.contains("update-check") { return HttpResponse(statusCode: 200, body: Data(updateJson.utf8)) }
            if body.contains("hello") { return HttpResponse(statusCode: 200, body: Data(helloJson.utf8)) }
            return HttpResponse(statusCode: 200, body: Data(stateJson.utf8))
        }

        let controller = MacosSessionController(
            configStore: configStore,
            cookieStore: cookieStore,
            probeTransport: probe,
            apiTransport: api,
            sseStream: HoldingSseStream()
        )
        await controller.start()
        try await waitUntil {
            let s = await controller.currentSnapshot()
            return s.screen == .connected && s.status == .online
        }

        // 1. 在线且地址未改不带 token -> .unchanged，不发新请求
        let probesBeforeUnchanged = probe.requests.count
        let apiBeforeUnchanged = api.requests.count
        let unchangedRes = await controller.saveSettingsUrl("https://a.example.test/")
        XCTAssertEqual(.unchanged, unchangedRes)
        XCTAssertEqual(probesBeforeUnchanged, probe.requests.count)
        XCTAssertEqual(apiBeforeUnchanged, api.requests.count)

        // 2. 非法地址 -> .failed 且不含 token，原配置不变
        let invalidRes = await controller.saveSettingsUrl("ftp://bad.test/?token=tok_invalid")
        if case .failed(let msg) = invalidRes {
            XCTAssertFalse(msg.contains("tok_invalid"))
        } else {
            XCTFail("期望非法地址返回 .failed，实际为 \(invalidRes)")
        }
        XCTAssertEqual(.ok("https://a.example.test/"), configStore.load())

        // 3. 探测失败 -> .failed 且不含 token，原配置与在线连接不受影响
        let refusedRes = await controller.saveSettingsUrl("http://127.0.0.1:13081/?token=tok_refused")
        if case .failed(let msg) = refusedRes {
            XCTAssertFalse(msg.contains("tok_refused"))
            XCTAssertTrue(msg.contains("连接被拒绝"))
        } else {
            XCTFail("期望不可达地址返回 .failed，实际为 \(refusedRes)")
        }
        XCTAssertEqual(.ok("https://a.example.test/"), configStore.load())
        let stillOnline = await controller.currentSnapshot()
        XCTAssertEqual(.connected, stillOnline.screen)
        XCTAssertEqual(.online, stillOnline.status)

        // 4. 建会话被拒 -> .failed 且不含 token，原配置不变
        let rejectedRes = await controller.saveSettingsUrl("http://127.0.0.1:13080/?token=bad_tok")
        if case .failed(let msg) = rejectedRes {
            XCTAssertFalse(msg.contains("bad_tok"))
        } else {
            XCTFail("期望被拒 token 返回 .failed，实际为 \(rejectedRes)")
        }
        XCTAssertEqual(.ok("https://a.example.test/"), configStore.load())

        // 5. 修改为新可达带 token 地址 -> .saved，原子保存干净地址，无需重启切换至新源在线
        let savedRes = await controller.saveSettingsUrl("http://127.0.0.1:13080/?token=def456")
        XCTAssertEqual(.saved, savedRes)
        try await waitUntil {
            let s = await controller.currentSnapshot()
            return s.screen == .connected && s.status == .online && s.cleanUrl == "http://127.0.0.1:13080/"
        }
        XCTAssertEqual(.ok("http://127.0.0.1:13080/"), configStore.load())
        await controller.stop()

        // 6. 非在线状态（如错误页）下不改地址直接保存 -> 触发重连并返回 .saved
        let offlineDir = root.appendingPathComponent("Offline", isDirectory: true)
        let offlineConfig = HarnessConfigStore(directory: offlineDir)
        _ = try offlineConfig.save("http://127.0.0.1:13080/")
        let offlineCookies = SessionCookieStore(directory: offlineDir)
        try offlineCookies.replaceCookies([SessionCookies.Cookie(name: "dsh-auth-o", value: "v1.o")], forUrl: "http://127.0.0.1:13080/")

        let retryProbe = StubHttpTransport()
        retryProbe.enqueue { _ in throw TransportError(.refused) }
        retryProbe.enqueue { _ in HttpResponse(statusCode: 200, body: Data("ok".utf8)) }

        let offlineCtrl = MacosSessionController(
            configStore: offlineConfig,
            cookieStore: offlineCookies,
            probeTransport: retryProbe,
            apiTransport: api,
            sseStream: HoldingSseStream()
        )
        await offlineCtrl.start()
        try await waitUntil {
            await offlineCtrl.currentSnapshot().screen == .error(.refused)
        }
        let reconnectRes = await offlineCtrl.saveSettingsUrl("http://127.0.0.1:13080/")
        XCTAssertEqual(.saved, reconnectRes)
        try await waitUntil {
            let s = await offlineCtrl.currentSnapshot()
            return s.screen == .connected && s.status == .online
        }
        await offlineCtrl.stop()

        // 7. 设置保存写入失败（以普通文件充当目录）-> .failed 且不含 token
        let blockedFile = root.appendingPathComponent("settings-blocked")
        try Data("x".utf8).write(to: blockedFile)
        let unwritableCtrl = MacosSessionController(
            configStore: HarnessConfigStore(directory: blockedFile),
            cookieStore: SessionCookieStore(directory: blockedFile),
            probeTransport: probe,
            apiTransport: api,
            sseStream: HoldingSseStream()
        )
        let writeFailRes = await unwritableCtrl.saveSettingsUrl("http://127.0.0.1:13080/?token=tok_perm")
        if case .failed(let msg) = writeFailRes {
            XCTAssertFalse(msg.contains("tok_perm"))
        } else {
            XCTFail("期望写入失败返回 .failed，实际为 \(writeFailRes)")
        }
    }

    private func waitUntil(timeoutSeconds: TimeInterval = 3.0, _ predicate: @escaping () async -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            if await predicate() { return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("等待条件超时")
    }
}

private final class StubHttpTransport: HttpTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var queue: [@Sendable (HttpRequest) async throws -> HttpResponse] = []
    private var recorded: [HttpRequest] = []
    var handler: (@Sendable (HttpRequest) async throws -> HttpResponse)?
    var defaultHandler: (@Sendable (HttpRequest) async throws -> HttpResponse)?

    var requests: [HttpRequest] { lock.withLock { recorded } }

    func enqueue(_ fn: @escaping @Sendable (HttpRequest) async throws -> HttpResponse) {
        lock.withLock { queue.append(fn) }
    }

    func send(_ request: HttpRequest) async throws -> HttpResponse {
        let step: (@Sendable (HttpRequest) async throws -> HttpResponse)? = lock.withLock {
            recorded.append(request)
            if !queue.isEmpty {
                return queue.removeFirst()
            }
            return handler ?? defaultHandler
        }
        guard let step else {
            return HttpResponse(statusCode: 500, body: Data("no mock".utf8))
        }
        return try await step(request)
    }
}

private final class HoldingSseStream: SseStream, @unchecked Sendable {
    func open(url: String, headers: [(name: String, value: String)]) async throws -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            continuation.yield("event: hello\ndata: {\"revision\":1694,\"api\":1}\n\n")
        }
    }
}
