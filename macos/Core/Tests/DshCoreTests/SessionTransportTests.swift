import Foundation
import XCTest
@testable import DshCore

final class SessionTransportTests: XCTestCase {
    private func makeTempDir(_ prefix: String = "dsh-session-test-") -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("\(prefix)\(UUID().uuidString)")
    }

    func testCanonicalOriginAndHostHeader() {
        XCTAssertEqual("http://127.0.0.1:13080", HarnessUrl.canonicalOrigin("http://127.0.0.1:13080/path?token=secret"))
        XCTAssertEqual("http://dsh.example.test:80", HarnessUrl.canonicalOrigin("HTTP://DSH.EXAMPLE.TEST/"))
        XCTAssertEqual("https://gamepc.tail1.ts.net:443", HarnessUrl.canonicalOrigin("https://gamepc.tail1.ts.net:443/chat"))
        XCTAssertEqual("http://[::1]:13080", HarnessUrl.canonicalOrigin("http://[::1]:13080/"))
        XCTAssertNil(HarnessUrl.canonicalOrigin("ftp://dsh.example.test/"))
        XCTAssertNil(HarnessUrl.canonicalOrigin("not-a-url"))

        XCTAssertEqual("127.0.0.1:13080", HarnessUrl.hostHeader("http://127.0.0.1:13080/"))
        XCTAssertEqual("dsh.example.test", HarnessUrl.hostHeader("http://dsh.example.test:80/"))
        XCTAssertEqual("gamepc.tail1.ts.net", HarnessUrl.hostHeader("https://gamepc.tail1.ts.net:443/"))
        XCTAssertEqual("gamepc.tail1.ts.net:8443", HarnessUrl.hostHeader("https://gamepc.tail1.ts.net:8443/"))
        XCTAssertEqual("[::1]:13080", HarnessUrl.hostHeader("http://[::1]:13080/"))
    }

    func testHttpLocationRfc3986Resolution() {
        XCTAssertEqual(
            "https://h.test/api/file?path=%2Ftmp%2Fa.png&v=2",
            HttpLocation.resolve(base: "https://h.test/api/file?path=%2Ftmp%2Fa.png", location: "?path=%2Ftmp%2Fa.png&v=2")
        )
        XCTAssertEqual(
            "http://127.0.0.1:13080/app/home",
            HttpLocation.resolve(base: "http://127.0.0.1:13080/connect?token=abc", location: "/app/home")
        )
        XCTAssertEqual(
            "https://h.test/a/c/page",
            HttpLocation.resolve(base: "https://h.test/a/b/old", location: "../c/page")
        )
        XCTAssertEqual(
            "https://other.test/next",
            HttpLocation.resolve(base: "https://h.test/", location: "//other.test/next")
        )
        XCTAssertEqual(
            "https://h.test/chat#bottom",
            HttpLocation.resolve(base: "https://h.test/chat#top", location: "#bottom")
        )
        XCTAssertNil(HttpLocation.resolve(base: "https://h.test/", location: "javascript:alert(1)"))
        XCTAssertNil(HttpLocation.resolve(base: "https://h.test/", location: "ftp://other.test/"))
        XCTAssertNil(HttpLocation.resolve(base: "https://h.test/", location: "   "))
    }

    func testSessionCookieStore0600PermissionsOriginIsolationAndRedaction() throws {
        let tmpDir = makeTempDir()
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let combined = "theme=dark; Path=/; Expires=Wed, 21 Oct 2026 07:28:00 GMT, dsh-auth-main=v1.secret_cookie_val; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict"
        let split = SessionCookies.splitSetCookieHeader(combined)
        XCTAssertEqual(2, split.count)
        let parsed = split.compactMap(SessionCookies.parseSetCookie)
        XCTAssertEqual(["theme", "dsh-auth-main"], parsed.map(\.name))

        let store = SessionCookieStore(directory: tmpDir)
        try store.replaceCookies(parsed, forUrl: "http://127.0.0.1:13080/?token=abc")

        let stored = store.cookies(forUrl: "http://127.0.0.1:13080/")
        XCTAssertEqual([SessionCookies.Cookie(name: "dsh-auth-main", value: "v1.secret_cookie_val")], stored)
        XCTAssertTrue(store.cookies(forUrl: "http://127.0.0.1:13081/").isEmpty, "不同端口不得共享 cookie")
        XCTAssertTrue(store.cookies(forUrl: "https://127.0.0.1:13080/").isEmpty, "不同协议不得共享 cookie")

        let cookieFile = tmpDir.appendingPathComponent(SessionCookieStore.fileName)
        let attrs = try FileManager.default.attributesOfItem(atPath: cookieFile.path)
        let perms = (attrs[.posixPermissions] as? NSNumber)?.intValue
        XCTAssertEqual(0o600, (perms ?? 0) & 0o777, "cookie 文件权限必须为 0600")

        let leftovers = try FileManager.default.contentsOfDirectory(atPath: tmpDir.path)
            .filter { $0.hasPrefix(SessionCookieStore.tmpPrefix) }
        XCTAssertTrue(leftovers.isEmpty, "不得残留临时文件")

        // 脱敏输出检查
        XCTAssertFalse(String(describing: stored[0]).contains("secret_cookie_val"))
        XCTAssertFalse(String(reflecting: stored[0]).contains("secret_cookie_val"))
        XCTAssertFalse(String(describing: store).contains("secret_cookie_val"))
        XCTAssertFalse(String(reflecting: store).contains("secret_cookie_val"))

        // 模拟应用重启：新建实例重新加载
        let reloaded = SessionCookieStore(directory: tmpDir)
        reloaded.load()
        XCTAssertEqual("dsh-auth-main=v1.secret_cookie_val", reloaded.cookieHeader(forUrl: "http://127.0.0.1:13080/"))
        XCTAssertTrue(reloaded.hasAuthCookies(forUrl: "http://127.0.0.1:13080/"))
    }

    func testSessionEstablishSingleUseSameOriginRedirectAndCrossOriginBlock() async throws {
        let tmpDir = makeTempDir()
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let cookieStore = SessionCookieStore(directory: tmpDir)
        let mock = ScriptedHttpTransport()
        mock.enqueue { req in
            XCTAssertEqual("http://127.0.0.1:13080/connect?token=tok_once", req.url)
            XCTAssertEqual("127.0.0.1:13080", req.header("Host"))
            return HttpResponse(
                statusCode: 303,
                headers: [
                    ("Set-Cookie", "dsh-auth-1=v1.fresh; Path=/; HttpOnly; SameSite=Strict"),
                    ("Location", "/"),
                ],
                body: Data()
            )
        }
        mock.enqueue { req in
            XCTAssertEqual("http://127.0.0.1:13080/", req.url)
            XCTAssertEqual("dsh-auth-1=v1.fresh", req.header("Cookie"))
            return HttpResponse(statusCode: 200, headers: [], body: Data("ok".utf8))
        }

        let establisher = SessionEstablisher(transport: mock, cookieStore: cookieStore)
        let res = await establisher.establish(tokenUrl: "http://127.0.0.1:13080/connect?token=tok_once")
        XCTAssertEqual(.ok, res)
        XCTAssertEqual(2, mock.requests.count)
        XCTAssertEqual("dsh-auth-1=v1.fresh", cookieStore.cookieHeader(forUrl: "http://127.0.0.1:13080/"))

        // 跨源重定向不得跟随，且不能将既有陈旧 cookie 误判为新 token 成功
        let crossMock = ScriptedHttpTransport()
        crossMock.enqueue { _ in
            HttpResponse(
                statusCode: 302,
                headers: [("Location", "https://evil.example.test/steal")],
                body: Data()
            )
        }
        let crossRes = await SessionEstablisher(transport: crossMock, cookieStore: cookieStore)
            .establish(tokenUrl: "http://127.0.0.1:13080/?token=bad_tok")
        XCTAssertEqual(.rejected(status: 302), crossRes)
        XCTAssertEqual(1, crossMock.requests.count, "跨源重定向后不得发出第二个请求")
        XCTAssertEqual("dsh-auth-1=v1.fresh", cookieStore.cookieHeader(forUrl: "http://127.0.0.1:13080/"))
    }

    func testOriginScopedTransportStripsCrossSiteHeadersAndBlocksCrossOrigin() async throws {
        let tmpDir = makeTempDir()
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let cookieStore = SessionCookieStore(directory: tmpDir)
        try cookieStore.replaceCookies([SessionCookies.Cookie(name: "dsh-auth-x", value: "v1.xyz")], forUrl: "https://h.ts.net/")

        let mock = ScriptedHttpTransport()
        mock.enqueue { _ in HttpResponse(statusCode: 200, headers: [], body: Data()) }
        let scoped = OriginScopedHttpTransport(baseUrl: "https://h.ts.net/", transport: mock, cookieStore: cookieStore)

        _ = try await scoped.send(HttpRequest(
            method: "POST",
            url: "https://h.ts.net/api/bot",
            headers: [
                ("Origin", "https://attacker.test"),
                ("Referer", "https://attacker.test/page"),
                ("Sec-Fetch-Site", "cross-site"),
                ("Sec-Fetch-Mode", "cors"),
                ("Host", "spoofed.test"),
                ("Content-Type", "application/json"),
            ]
        ))

        let sent = try XCTUnwrap(mock.requests.first)
        XCTAssertEqual("h.ts.net", sent.header("Host"))
        XCTAssertEqual("dsh-auth-x=v1.xyz", sent.header("Cookie"))
        XCTAssertNil(sent.header("Origin"))
        XCTAssertNil(sent.header("Referer"))
        XCTAssertNil(sent.header("Sec-Fetch-Site"))
        XCTAssertNil(sent.header("Sec-Fetch-Mode"))
        XCTAssertEqual("application/json", sent.header("Content-Type"))

        do {
            _ = try await scoped.send(HttpRequest(method: "GET", url: "https://other.ts.net/api/bot"))
            XCTFail("跨源请求必须被拒绝")
        } catch let err as OriginScopeError {
            XCTAssertEqual(.crossOriginBlocked, err)
        }
    }

    func testNetErrorClassifierAndTailnetHints() {
        XCTAssertEqual(.dns, NetErrorClassifier.classify(domain: "NSURLErrorDomain", code: -1003))
        XCTAssertEqual(.dns, NetErrorClassifier.classify(domain: "NSURLErrorDomain", code: -1006))
        XCTAssertEqual(.refused, NetErrorClassifier.classify(domain: "NSURLErrorDomain", code: -1004))
        XCTAssertEqual(.timeout, NetErrorClassifier.classify(domain: "NSURLErrorDomain", code: -1001))
        XCTAssertEqual(.tls, NetErrorClassifier.classify(domain: "NSURLErrorDomain", code: -1202))
        XCTAssertEqual(.refused, NetErrorClassifier.classify(domain: "NSPOSIXErrorDomain", code: 61))
        XCTAssertEqual(.refused, NetErrorClassifier.classify(domain: "NSPOSIXErrorDomain", code: 111))
        XCTAssertEqual(.timeout, NetErrorClassifier.classify(domain: "NSPOSIXErrorDomain", code: 60))
        XCTAssertEqual(.timeout, NetErrorClassifier.classify(domain: "NSPOSIXErrorDomain", code: 110))

        let wrapped = NSError(
            domain: "WrapperDomain",
            code: 1,
            userInfo: [NSUnderlyingErrorKey: NSError(domain: "NSURLErrorDomain", code: -1004)]
        )
        XCTAssertEqual(.refused, NetErrorClassifier.classify(wrapped))

        XCTAssertEqual("请确认系统 Tailscale 已连接", ConnectionCopy.probeHint(forCleanUrl: "https://box.tail1.ts.net/"))
        XCTAssertEqual("请确认系统 Tailscale 已连接", ConnectionCopy.probeHint(forCleanUrl: "http://100.64.0.5:13080/"))
        XCTAssertEqual("请确认系统 Tailscale 已连接", ConnectionCopy.probeHint(forCleanUrl: "http://gamepc:13080/"))
        XCTAssertEqual("请检查地址是否正确并确认 DSH 主机上的服务在运行", ConnectionCopy.probeHint(forCleanUrl: "http://127.0.0.1:13080/"))
    }

    func testControllerFirstTokenEstablishRestartAndNoTokenLeak() async throws {
        let tmpDir = makeTempDir()
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let configStore = HarnessConfigStore(directory: tmpDir)
        let cookieStore = SessionCookieStore(directory: tmpDir)
        let stateJson = try TestFixtures.dsbotText("state-full.json")
        let updateJson = try TestFixtures.dsbotText("update-check.json")
        let helloJson = try TestFixtures.dsbotText("hello.json")

        let probeMock = ScriptedHttpTransport()
        probeMock.defaultHandler = { req in
            XCTAssertFalse(req.url.contains("tok_secret_999"), "探测只能发向干净地址")
            return HttpResponse(statusCode: 401, headers: [], body: Data())
        }

        let apiMock = ScriptedHttpTransport()
        apiMock.handler = { req in
            if req.method == "GET" && req.url == "http://127.0.0.1:13080/?token=tok_secret_999" {
                return HttpResponse(
                    statusCode: 303,
                    headers: [
                        ("Set-Cookie", "dsh-auth-s=v1.persisted; Path=/; HttpOnly; SameSite=Strict"),
                        ("Location", "/"),
                    ],
                    body: Data()
                )
            }
            if req.method == "GET" && req.url == "http://127.0.0.1:13080/" {
                return HttpResponse(statusCode: 200, headers: [], body: Data("ok".utf8))
            }
            if req.method == "POST" && req.url == "http://127.0.0.1:13080/api/bot" {
                XCTAssertEqual("dsh-auth-s=v1.persisted", req.header("Cookie"))
                XCTAssertEqual("127.0.0.1:13080", req.header("Host"))
                let body = String(decoding: req.body ?? Data(), as: UTF8.self)
                if body.contains("\"endpoint\":\"update-check\"") {
                    return HttpResponse(statusCode: 200, headers: [], body: Data(updateJson.utf8))
                }
                if body.contains("\"endpoint\":\"hello\"") {
                    return HttpResponse(statusCode: 200, headers: [], body: Data(helloJson.utf8))
                }
                if body.contains("\"endpoint\":\"state\"") {
                    return HttpResponse(statusCode: 200, headers: [], body: Data(stateJson.utf8))
                }
            }
            return HttpResponse(statusCode: 404, headers: [], body: Data())
        }

        let sseMock = ScriptedSseStream()
        let controller = MacosSessionController(
            configStore: configStore,
            cookieStore: cookieStore,
            probeTransport: probeMock,
            apiTransport: apiMock,
            sseStream: sseMock
        )

        await controller.start()
        var snap = await controller.currentSnapshot()
        XCTAssertEqual(.setup(invalidConfig: false), snap.screen)

        // 非法输入不得回显 token
        await controller.submitUrl("ftp://127.0.0.1:13080/?token=tok_secret_999")
        snap = await controller.currentSnapshot()
        XCTAssertFalse(snap.formError?.contains("tok_secret_999") ?? false)

        // 提交合法带 token 地址：只建一次会话、干净地址落盘、进入在线
        await controller.submitUrl("  http://127.0.0.1:13080/?token=tok_secret_999  ")
        try await waitUntil {
            let s = await controller.currentSnapshot()
            return s.status == .online && s.screen == .connected
        }
        snap = await controller.currentSnapshot()
        XCTAssertEqual("http://127.0.0.1:13080/", snap.cleanUrl)
        XCTAssertEqual("1694", snap.stateRevision)
        XCTAssertGreaterThan(snap.botCount, 0)
        XCTAssertNil(snap.versionBanner)
        await controller.stop()

        let tokenHits = apiMock.requests.filter { $0.url.contains("tok_secret_999") }
        XCTAssertEqual(1, tokenHits.count, "带 token 地址只能请求一次")

        let configRaw = try String(contentsOf: tmpDir.appendingPathComponent("config.json"), encoding: .utf8)
        XCTAssertEqual("{\n  \"harnessURL\": \"http://127.0.0.1:13080/\"\n}\n", configRaw)
        XCTAssertFalse(configRaw.contains("tok_secret_999"))

        // 模拟退出并重启：新控制器与新 cookieStore 实例无需再次输入地址直接在线
        let restartedProbe = ScriptedHttpTransport()
        restartedProbe.defaultHandler = { req in
            XCTAssertEqual("dsh-auth-s=v1.persisted", req.header("Cookie"))
            return HttpResponse(statusCode: 200, headers: [], body: Data("ok".utf8))
        }
        let restartedController = MacosSessionController(
            configStore: HarnessConfigStore(directory: tmpDir),
            cookieStore: SessionCookieStore(directory: tmpDir),
            probeTransport: restartedProbe,
            apiTransport: apiMock,
            sseStream: ScriptedSseStream()
        )
        await restartedController.start()
        try await waitUntil {
            let s = await restartedController.currentSnapshot()
            return s.status == .online && s.screen == .connected
        }
        await restartedController.stop()
    }

    func testController401RetryOnceMissingCookieReauthAnd403() async throws {
        let tmpDir = makeTempDir()
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let configStore = HarnessConfigStore(directory: tmpDir)
        _ = try configStore.save("http://127.0.0.1:13080/")
        let cookieStore = SessionCookieStore(directory: tmpDir)

        // 1. 缺失 cookie -> 直接进入重新认证页
        let okProbe = ScriptedHttpTransport()
        okProbe.defaultHandler = { _ in HttpResponse(statusCode: 200, headers: [], body: Data()) }
        let apiMock = ScriptedHttpTransport()
        let controller1 = MacosSessionController(
            configStore: configStore,
            cookieStore: cookieStore,
            probeTransport: okProbe,
            apiTransport: apiMock,
            sseStream: ScriptedSseStream()
        )
        await controller1.start()
        try await waitUntil {
            await controller1.currentSnapshot().screen == .authExpired
        }
        await controller1.stop()

        // 2. 有 cookie 但门禁连续 401（先自动重试一次，共两次 GET）-> 进入重新认证页，粘贴新地址无重启恢复
        try cookieStore.replaceCookies([SessionCookies.Cookie(name: "dsh-auth-old", value: "v1.old")], forUrl: "http://127.0.0.1:13080/")
        let gate401Probe = ScriptedHttpTransport()
        gate401Probe.defaultHandler = { _ in
            HttpResponse(statusCode: 401, headers: [("Content-Type", "text/plain")], body: Data("expired".utf8))
        }
        let stateJson = try TestFixtures.dsbotText("state-full.json")
        let updateJson = try TestFixtures.dsbotText("update-check.json")
        let helloJson = try TestFixtures.dsbotText("hello.json")
        let reauthApi = ScriptedHttpTransport()
        reauthApi.handler = { req in
            if req.method == "GET" && req.url == "http://127.0.0.1:13080/?token=new_tok" {
                return HttpResponse(
                    statusCode: 200,
                    headers: [("Set-Cookie", "dsh-auth-new=v1.new; Path=/; HttpOnly; SameSite=Strict")],
                    body: Data("ok".utf8)
                )
            }
            let body = String(decoding: req.body ?? Data(), as: UTF8.self)
            if body.contains("update-check") { return HttpResponse(statusCode: 200, body: Data(updateJson.utf8)) }
            if body.contains("hello") { return HttpResponse(statusCode: 200, body: Data(helloJson.utf8)) }
            return HttpResponse(statusCode: 200, body: Data(stateJson.utf8))
        }

        let controller2 = MacosSessionController(
            configStore: configStore,
            cookieStore: cookieStore,
            probeTransport: gate401Probe,
            apiTransport: reauthApi,
            sseStream: ScriptedSseStream()
        )
        await controller2.start()
        try await waitUntil {
            await controller2.currentSnapshot().screen == .authExpired
        }
        XCTAssertEqual(2, gate401Probe.requests.count, "401 门禁应自动重试一次")

        await controller2.submitUrl("http://127.0.0.1:13080/?token=new_tok")
        try await waitUntil {
            let s = await controller2.currentSnapshot()
            return s.screen == .connected && s.status == .online
        }
        await controller2.stop()

        // 3. 门禁返回 403 -> 进入 forbiddenHost
        let probe403 = ScriptedHttpTransport()
        probe403.defaultHandler = { _ in HttpResponse(statusCode: 403, body: Data("forbidden".utf8)) }
        let controller3 = MacosSessionController(
            configStore: configStore,
            cookieStore: cookieStore,
            probeTransport: probe403,
            apiTransport: reauthApi,
            sseStream: ScriptedSseStream()
        )
        await controller3.start()
        try await waitUntil {
            await controller3.currentSnapshot().screen == .forbiddenHost
        }
        await controller3.stop()

        // 4. 在线期间 feed 401 -> 立即切换 authExpired
        let sse401 = FailingSseStream(error: FeedStreamError.authExpired)
        let controller4 = MacosSessionController(
            configStore: configStore,
            cookieStore: cookieStore,
            probeTransport: okProbe,
            apiTransport: reauthApi,
            sseStream: sse401
        )
        await controller4.start()
        try await waitUntil {
            await controller4.currentSnapshot().screen == .authExpired
        }
        await controller4.stop()

        // 5. submitUrl 提交不带 token 的干净地址且无 cookie 时，远端返回 403 应进入 forbiddenHost 而非 authExpired；
        //    提交带 token 地址返回 403 时应给出 trusted-host 提示。
        let clean403Dir = makeTempDir("dsh-403-submit-")
        defer { try? FileManager.default.removeItem(at: clean403Dir) }
        let clean403Api = ScriptedHttpTransport()
        clean403Api.defaultHandler = { _ in HttpResponse(statusCode: 403, body: Data("forbidden".utf8)) }
        let controller5 = MacosSessionController(
            configStore: HarnessConfigStore(directory: clean403Dir),
            cookieStore: SessionCookieStore(directory: clean403Dir),
            probeTransport: probe403,
            apiTransport: clean403Api,
            sseStream: ScriptedSseStream()
        )
        await controller5.submitUrl("http://127.0.0.1:13080/?token=tok_403")
        let snap5Token = await controller5.currentSnapshot()
        XCTAssertTrue(snap5Token.formError?.contains("trusted-host") ?? false)

        await controller5.submitUrl("http://127.0.0.1:13080/")
        let snap5Clean = await controller5.currentSnapshot()
        XCTAssertEqual(.forbiddenHost, snap5Clean.screen)
        await controller5.stop()
    }

    func testControllerErrorPageRetryRecovery() async throws {
        let tmpDir = makeTempDir()
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let configStore = HarnessConfigStore(directory: tmpDir)
        _ = try configStore.save("https://gamepc.tail1.ts.net:13080/")
        let cookieStore = SessionCookieStore(directory: tmpDir)
        try cookieStore.replaceCookies([SessionCookies.Cookie(name: "dsh-auth-t", value: "v1.t")], forUrl: "https://gamepc.tail1.ts.net:13080/")

        let probe = ScriptedHttpTransport()
        probe.enqueue { _ in throw TransportError(.dns) }
        probe.enqueue { _ in HttpResponse(statusCode: 200, body: Data("ok".utf8)) }

        let stateJson = try TestFixtures.dsbotText("state-full.json")
        let updateJson = try TestFixtures.dsbotText("update-check.json")
        let helloJson = try TestFixtures.dsbotText("hello.json")
        let api = ScriptedHttpTransport()
        api.handler = { req in
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
            sseStream: ScriptedSseStream()
        )
        await controller.start()
        try await waitUntil {
            await controller.currentSnapshot().screen == .error(.dns)
        }
        let errSnap = await controller.currentSnapshot()
        XCTAssertEqual("https://gamepc.tail1.ts.net:13080/", errSnap.cleanUrl)
        XCTAssertEqual("请确认系统 Tailscale 已连接", errSnap.errorHint)

        await controller.retry()
        try await waitUntil {
            let s = await controller.currentSnapshot()
            return s.screen == .connected && s.status == .online
        }
        await controller.stop()
    }

    func testControllerFeedBackoffReconnectAndSleepWakeCatchup() async throws {
        let tmpDir = makeTempDir()
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let configStore = HarnessConfigStore(directory: tmpDir)
        _ = try configStore.save("http://127.0.0.1:13080/")
        let cookieStore = SessionCookieStore(directory: tmpDir)
        try cookieStore.replaceCookies([SessionCookies.Cookie(name: "dsh-auth-k", value: "v1.k")], forUrl: "http://127.0.0.1:13080/")

        let stateFullJson = try TestFixtures.dsbotText("state-full.json")
        let stateDeltaJson = try TestFixtures.dsbotText("state-unchanged-live.json")
        let updateJson = try TestFixtures.dsbotText("update-check.json")
        let helloJson = try TestFixtures.dsbotText("hello.json")

        let probe = ScriptedHttpTransport()
        probe.defaultHandler = { _ in HttpResponse(statusCode: 200, body: Data()) }

        let recordedPayloads = LockedBox<[String]>([])
        let api = ScriptedHttpTransport()
        api.handler = { req in
            let body = String(decoding: req.body ?? Data(), as: UTF8.self)
            if body.contains("update-check") { return HttpResponse(statusCode: 200, body: Data(updateJson.utf8)) }
            if body.contains("hello") { return HttpResponse(statusCode: 200, body: Data(helloJson.utf8)) }
            recordedPayloads.withLock { $0.append(body) }
            if body.contains("\"since\":1694") {
                return HttpResponse(statusCode: 200, body: Data(stateDeltaJson.utf8))
            }
            return HttpResponse(statusCode: 200, body: Data(stateFullJson.utf8))
        }

        let delays = LockedBox<[Int64]>([])
        let sse = MultiAttemptSseStream(attempts: [
            // 第 1 次：hello 触发全量 state (revision=1694) 后正常断流 -> 退避 1000ms
            .chunks(["event: hello\ndata: {\"revision\":1694}\n\n"]),
            // 第 2 次：直接抛网络错 -> 退避 2000ms
            .error(TransportError(.timeout)),
            // 第 3 次：重连成功并保持挂起（hello 触发携带 since:1694 的增量补齐）
            .openAndHold(["event: hello\ndata: {\"revision\":1695}\n\n"]),
            // 第 4 次（唤醒后）：携带 since:1694 补齐
            .openAndHold(["event: hello\ndata: {\"revision\":1696}\n\n"]),
        ])

        let controller = MacosSessionController(
            configStore: configStore,
            cookieStore: cookieStore,
            probeTransport: probe,
            apiTransport: api,
            sseStream: sse,
            sleepMs: { ms in
                delays.withLock { $0.append(ms) }
                try await Task.sleep(nanoseconds: 5_000_000)
            }
        )

        await controller.start()
        try await waitUntil {
            let s = await controller.currentSnapshot()
            return sse.openCount >= 3 && s.status == .online
        }

        XCTAssertEqual([1_000, 2_000], delays.value)
        let payloadsBeforeSleep = recordedPayloads.value
        XCTAssertTrue(payloadsBeforeSleep.contains { $0.contains("\"since\":1694") }, "重连后应携带 since:1694 增量补齐")

        // 应用失活（休眠/隐藏）断开 SSE，但保留同步游标
        await controller.setActive(false)
        let paused = await controller.currentSnapshot()
        XCTAssertEqual(.disconnected, paused.status)
        XCTAssertEqual("1694", paused.stateRevision)

        // 恢复活跃：重新探测并携带原 since:1694 增量补齐
        await controller.setActive(true)
        try await waitUntil {
            let s = await controller.currentSnapshot()
            return sse.openCount >= 4 && s.status == .online
        }
        await controller.stop()
    }

    func testControllerVersionBannerAndDismiss() async throws {
        let tmpDir = makeTempDir()
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let configStore = HarnessConfigStore(directory: tmpDir)
        _ = try configStore.save("http://127.0.0.1:13080/")
        let cookieStore = SessionCookieStore(directory: tmpDir)
        try cookieStore.replaceCookies([SessionCookies.Cookie(name: "dsh-auth-v", value: "v1.v")], forUrl: "http://127.0.0.1:13080/")

        let probe = ScriptedHttpTransport()
        probe.defaultHandler = { _ in HttpResponse(statusCode: 200, body: Data()) }

        let stateJson = try TestFixtures.dsbotText("state-full.json")
        let unsupportedHello = try TestFixtures.dsbotText("hello-api-unsupported.json")
        let mismatchedUpdate = #"{"ok":true,"value":{"current":"0.4.0","dsh":{"current":"0.2.2-rc.3"}}}"#

        let api = ScriptedHttpTransport()
        api.handler = { req in
            let body = String(decoding: req.body ?? Data(), as: UTF8.self)
            if body.contains("update-check") { return HttpResponse(statusCode: 200, body: Data(mismatchedUpdate.utf8)) }
            if body.contains("hello") { return HttpResponse(statusCode: 200, body: Data(unsupportedHello.utf8)) }
            return HttpResponse(statusCode: 200, body: Data(stateJson.utf8))
        }

        let controller = MacosSessionController(
            configStore: configStore,
            cookieStore: cookieStore,
            probeTransport: probe,
            apiTransport: api,
            sseStream: ScriptedSseStream()
        )
        await controller.start()
        try await waitUntil {
            let s = await controller.currentSnapshot()
            return s.status == .online && s.versionBanner != nil
        }
        var snap = await controller.currentSnapshot()
        XCTAssertEqual(DsbotVersions.Banner(dsBot: "0.4.0", dsh: "0.2.2-rc.3"), snap.versionBanner)
        XCTAssertFalse(snap.versionBannerDismissed)

        await controller.dismissVersionBanner()
        snap = await controller.currentSnapshot()
        XCTAssertTrue(snap.versionBannerDismissed)
        await controller.stop()
    }

    // MARK: - 测试辅助

    private func waitUntil(timeoutSeconds: TimeInterval = 3.0, _ predicate: @escaping () async -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            if await predicate() { return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("等待条件超时")
    }
}

private final class LockedBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: T
    init(_ value: T) { self.storage = value }
    var value: T { lock.withLock { storage } }
    func withLock<R>(_ body: (inout T) -> R) -> R {
        lock.lock()
        defer { lock.unlock() }
        return body(&storage)
    }
}

private final class ScriptedHttpTransport: HttpTransport, @unchecked Sendable {
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

private final class ScriptedSseStream: SseStream, @unchecked Sendable {
    func open(url: String, headers: [(name: String, value: String)]) async throws -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            continuation.yield("event: hello\ndata: {\"revision\":1694,\"api\":1}\n\n")
            // 保持挂起直到任务取消
        }
    }
}

private final class FailingSseStream: SseStream, @unchecked Sendable {
    let error: Error
    init(error: Error) { self.error = error }
    func open(url: String, headers: [(name: String, value: String)]) async throws -> AsyncThrowingStream<String, Error> {
        throw error
    }
}

private final class MultiAttemptSseStream: SseStream, @unchecked Sendable {
    enum Behavior: Sendable {
        case chunks([String])
        case openAndHold([String])
        case error(Error)
    }

    private let lock = NSLock()
    private var attempts: [Behavior]
    private var count: Int = 0

    init(attempts: [Behavior]) {
        self.attempts = attempts
    }

    var openCount: Int { lock.withLock { count } }

    func open(url: String, headers: [(name: String, value: String)]) async throws -> AsyncThrowingStream<String, Error> {
        let behavior: Behavior = lock.withLock {
            count += 1
            return attempts.isEmpty ? .openAndHold([]) : attempts.removeFirst()
        }
        switch behavior {
        case .error(let err):
            throw err
        case .chunks(let lines):
            return AsyncThrowingStream { cont in
                for line in lines { cont.yield(line) }
                cont.finish()
            }
        case .openAndHold(let lines):
            return AsyncThrowingStream { cont in
                for line in lines { cont.yield(line) }
            }
        }
    }
}
