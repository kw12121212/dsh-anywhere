import Foundation
import XCTest
@testable import DshCore

final class DsbotClientAndSyncTests: XCTestCase {
    // MARK: - DsbotApi & Transport

    func testUrlAndRequestBuilding() {
        XCTAssertEqual("https://h.test:13080/api/bot", DsbotApi.url(base: "https://h.test:13080/", path: "/api/bot"))
        XCTAssertEqual("https://h.test:13080/api/bot", DsbotApi.url(base: "https://h.test:13080", path: "/api/bot"))
        XCTAssertEqual(#"{"endpoint":"state","payload":{"since":1690}}"#, DsbotApi.buildRequest(endpoint: "state", payloadJson: #"{"since":1690}"#))
        XCTAssertEqual(#"{"endpoint":"update-check","payload":null}"#, DsbotApi.buildRequest(endpoint: "update-check", payloadJson: ""))
    }

    func testResponseParsingAndErrorMapping() throws {
        let okRes = DsbotApi.parseResponse(status: 200, bodyText: try TestFixtures.dsbotText("state-unchanged.json"))
        guard case .ok(let value) = okRes else {
            return XCTFail("期待 ok")
        }
        XCTAssertEqual(true, value.objectValue?.bool("unchanged"))

        let errRes = DsbotApi.parseResponse(status: 200, bodyText: try TestFixtures.dsbotText("error-envelope.json"))
        XCTAssertEqual(.botError(code: "bot/invalid", message: "Nothing to send"), errRes)
        XCTAssertEqual(.badInput, DsbotApi.reason(of: "bot/invalid"))
        XCTAssertEqual(.stale, DsbotApi.reason(of: "bot/stale"))
        XCTAssertEqual(.notFound, DsbotApi.reason(of: "bot/not-found"))
        XCTAssertEqual(.readOnly, DsbotApi.reason(of: "bot/read-only"))
        XCTAssertEqual(.featureOff, DsbotApi.reason(of: "bot/browser-off"))
        XCTAssertEqual(.serverError, DsbotApi.reason(of: "bot/internal"))
        XCTAssertEqual(.refused, DsbotApi.reason(of: "bot/forbidden"))
        XCTAssertEqual(.refused, DsbotApi.reason(of: "bot/unavailable"))

        XCTAssertEqual(.failure(status: 401, reason: .authExpired), DsbotApi.parseResponse(status: 401, bodyText: "unauthorized"))
        XCTAssertEqual(.failure(status: 403, reason: .forbiddenHost), DsbotApi.parseResponse(status: 403, bodyText: "forbidden"))
        XCTAssertEqual(.failure(status: 502, reason: .network), DsbotApi.parseResponse(status: 502, bodyText: "bad gateway"))
        XCTAssertEqual(.failure(status: 200, reason: .serverError), DsbotApi.parseResponse(status: 200, bodyText: "not json"))
    }

    func testTransportClientDetectsSpaFallbackAndInjectsHeaders() async throws {
        final class MockTransport: HttpTransport, @unchecked Sendable {
            var lastRequest: HttpRequest?
            var response: HttpResponse
            init(_ response: HttpResponse) { self.response = response }
            func send(_ request: HttpRequest) async throws -> HttpResponse {
                lastRequest = request
                return response
            }
        }

        let spaMock = MockTransport(HttpResponse(statusCode: 405, headers: [("Allow", "GET, HEAD")], body: Data()))
        let spaClient = DsbotTransportClient(baseUrl: "https://h.test/", transport: spaMock, defaultHeaders: [("Host", "h.test")])
        let spaRes = await spaClient.postBot(endpoint: "state")
        XCTAssertEqual(.failure(status: 405, reason: .serverError), spaRes)
        XCTAssertEqual("https://h.test/api/bot", spaMock.lastRequest?.url)
        let reqHeaders = spaMock.lastRequest?.headers ?? []
        XCTAssertTrue(reqHeaders.contains { $0.name == "Host" && $0.value == "h.test" })
        XCTAssertTrue(reqHeaders.contains { $0.name == "Content-Type" && $0.value == "application/json" })
        XCTAssertTrue(reqHeaders.contains { $0.name == "Accept" && $0.value == "application/json" })

        struct FailingTransport: HttpTransport {
            struct Boom: Error {}
            func send(_ request: HttpRequest) async throws -> HttpResponse { throw Boom() }
        }
        let failClient = DsbotTransportClient(baseUrl: "https://h.test/", transport: FailingTransport())
        let failRes = await failClient.postBot(endpoint: "state")
        XCTAssertEqual(.failure(status: nil, reason: .network), failRes)

        let okMock = MockTransport(HttpResponse(
            statusCode: 200,
            headers: [],
            body: Data((try TestFixtures.dsbotText("hello.json")).utf8)
        ))
        let okClient = DsbotTransportClient(baseUrl: "https://h.test/", transport: okMock)
        let okRes = await okClient.postBot(endpoint: "hello", payloadJson: DsbotHello.requestPayload)
        if case .ok(let val) = okRes {
            XCTAssertNotNil(DsbotHello.decode(val))
        } else {
            XCTFail("期待 ok")
        }
    }

    func testSseStreamProtocolInjectionFeedsSseParser() async throws {
        struct MockSseStream: SseStream {
            let chunks: [String]
            func open(url: String, headers: [(name: String, value: String)]) async throws -> AsyncThrowingStream<String, Error> {
                AsyncThrowingStream { continuation in
                    for chunk in chunks {
                        continuation.yield(chunk)
                    }
                    continuation.finish()
                }
            }
        }

        let sseFixture = try TestFixtures.dsbotText("feed-stream.sse")
        let midpoint = sseFixture.index(sseFixture.startIndex, offsetBy: sseFixture.count / 2)
        let stream = MockSseStream(chunks: [
            "event: hello\r\n: keepal",
            "ive\r\nda",
            "ta: {\"revision\":42}\n\n",
            String(sseFixture[..<midpoint]),
            String(sseFixture[midpoint...]),
        ])

        let parser = SseParser()
        var frames: [SseParser.Frame] = []
        for try await chunk in try await stream.open(url: DsbotApi.url(base: "https://h.test/", path: "/api/bot/feed"), headers: []) {
            frames.append(contentsOf: parser.feed(chunk))
        }
        frames.append(contentsOf: parser.finish())

        XCTAssertEqual(["hello", "hello", "message", "stream"], frames.map(\.event))
        XCTAssertEqual("{\"revision\":42}", frames[0].data)
    }

    // MARK: - SseParser

    func testSseParserWithFixturesAndChunks() throws {
        let parser = SseParser()
        let frames = parser.feed(try TestFixtures.dsbotText("feed-stream.sse")) + parser.finish()
        XCTAssertEqual(["hello", "message", "stream"], frames.map(\.event))
        for frame in frames {
            XCTAssertNotNil(Json.parse(frame.data))
        }

        let inc = SseParser()
        XCTAssertTrue(inc.feed("event: hello\r\n: keepal").isEmpty)
        XCTAssertTrue(inc.feed("ive\r\nda").isEmpty)
        let f2 = inc.feed("ta: {\"revision\":7}\n\n")
        XCTAssertEqual([SseParser.Frame(event: "hello", data: #"{"revision":7}"#)], f2)
        XCTAssertTrue(inc.finish().isEmpty)

        let multi = SseParser()
        XCTAssertEqual([SseParser.Frame(event: "message", data: "a\nb")], multi.feed("data: a\ndata: b\n\n"))
    }

    // MARK: - DsbotModels

    func testStateAndDeltaDecodingAndMerge() throws {
        let full = try XCTUnwrap(DsbotModels.decodeState(try TestFixtures.dsbotValue("state-full.json")))
        XCTAssertEqual("1694", full.revision)
        XCTAssertNotNil(full.liveKey)
        XCTAssertFalse(full.bots.isEmpty)
        XCTAssertFalse(full.rooms.isEmpty)
        XCTAssertGreaterThan(full.raw.keys.count, 30)

        let mutated = try XCTUnwrap(Json.parse(#"{"revision":"42","bots":[{"id":"b1","sessionId":123},{"no-id":true}],"rooms":[],"future":{"x":1}}"#))
        let mutatedState = try XCTUnwrap(DsbotModels.decodeState(mutated))
        XCTAssertEqual(1, mutatedState.bots.count)
        XCTAssertNil(mutatedState.bots[0].sessionId)
        XCTAssertTrue(mutatedState.raw.contains("future"))

        XCTAssertNil(DsbotModels.decodeState(try TestFixtures.dsbotValue("state-unchanged.json")))
        XCTAssertNil(DsbotModels.decodeState(try TestFixtures.dsbotValue("state-unchanged-live.json")))

        let deltaLive = try XCTUnwrap(DsbotModels.decodeStateDelta(try TestFixtures.dsbotValue("state-unchanged-live.json")))
        XCTAssertTrue(deltaLive.unchanged && deltaLive.hasLiveFields)
        let merged = DsbotModels.mergeDelta(state: full, delta: deltaLive)
        XCTAssertEqual(full.bots.count, merged.bots.count)
        XCTAssertTrue(merged.raw.contains("work"))

        let deltaPure = try XCTUnwrap(DsbotModels.decodeStateDelta(try TestFixtures.dsbotValue("state-unchanged.json")))
        XCTAssertTrue(deltaPure.unchanged && !deltaPure.hasLiveFields)
        XCTAssertEqual(full, DsbotModels.mergeDelta(state: full, delta: deltaPure))

        XCTAssertEqual(#"{"since":1694,"live":"lk","models":"mk"}"#, DsbotModels.statePayload(since: "1694", live: "lk", models: "mk"))
        XCTAssertEqual("{}", DsbotModels.statePayload(since: nil, live: nil, models: nil))
    }

    // MARK: - DsbotHello & DsbotVersions

    func testHelloCapabilitiesAndVersionBanner() throws {
        let helloOk = DsbotApi.parseResponse(status: 200, bodyText: try TestFixtures.dsbotText("hello.json"))
        let caps = Capabilities.from(helloOk)
        XCTAssertEqual(.declared, caps.mode)
        XCTAssertTrue(caps.has("notices"))
        XCTAssertTrue(caps.has("markdown-image"))
        XCTAssertFalse(caps.has("spike-undeclared-capability"))
        XCTAssertFalse(caps.versionMismatch)
        XCTAssertNotNil(caps.boot)

        let unknownEndpoint = DsbotApi.parseResponse(status: 200, bodyText: try TestFixtures.dsbotText("error-unknown-endpoint.json"))
        XCTAssertEqual(.legacy, Capabilities.from(unknownEndpoint))

        let unsupported = DsbotApi.parseResponse(status: 200, bodyText: try TestFixtures.dsbotText("hello-api-unsupported.json"))
        let incompat = Capabilities.from(unsupported)
        XCTAssertEqual(.incompatible, incompat.mode)
        XCTAssertTrue(incompat.versionMismatch)
        XCTAssertFalse(incompat.has("notices"))

        let remote = DsbotVersions.fromUpdateCheck(try TestFixtures.dsbotValue("update-check.json"))
        XCTAssertEqual("0.3.10", remote.dsBot)
        XCTAssertEqual("0.2.2-rc.3", remote.dsh)
        XCTAssertEqual(.matched, DsbotVersions.againstBaseline(remote))
        XCTAssertEqual(.mismatched, DsbotVersions.againstBaseline(.init(dsBot: "0.3.6", dsh: "0.2.2-rc.3")))
        XCTAssertEqual(.unknown, DsbotVersions.againstBaseline(.init(dsBot: nil, dsh: "0.2.2")))
        XCTAssertNil(DsbotVersions.banner(remote: remote, capabilities: caps))
        XCTAssertEqual(.init(dsBot: "?", dsh: "?"), DsbotVersions.banner(remote: nil, capabilities: incompat))

        XCTAssertGreaterThan(DsbotVersions.compare("0.2.2", "0.2.2-rc.1"), 0)
        XCTAssertGreaterThan(DsbotVersions.compare("0.2.2-rc.2", "0.2.2-rc.1"), 0)
        XCTAssertEqual(0, DsbotVersions.compare("v1.0.0+build", "1.0.0"))

        XCTAssertTrue(caps.shouldReread(.init(revision: "1", api: 1, boot: "other-boot", seq: 0)))
        XCTAssertTrue(caps.shouldReread(.init(revision: "1694", api: nil, boot: nil, seq: nil)))
        XCTAssertFalse(Capabilities.legacy.shouldReread(.init(revision: "1694", api: nil, boot: nil, seq: nil)))
    }

    // MARK: - FeedSync & DsbotConnection

    func testFeedSyncAndDsbotConnectionStateMachine() throws {
        final class Recorder: DsbotConnectionListener {
            var statuses: [DsbotConnection.Status] = []
            var actions: [String] = []
            var fetches: [(String?, String?)] = []
            var hellos: [DsbotHello.FeedHello] = []

            func onStatus(_ status: DsbotConnection.Status) { statuses.append(status) }
            func probeAndGate() { actions.append("probe") }
            func connectFeed() { actions.append("feed") }
            func fetchState(localRevision: String?, liveKey: String?) { fetches.append((localRevision, liveKey)) }
            func onFeedHello(_ hello: DsbotHello.FeedHello) { hellos.append(hello) }
        }

        let r = Recorder()
        let conn = DsbotConnection(listener: r)
        conn.beginConnect()
        XCTAssertEqual(["probe"], r.actions)
        XCTAssertEqual(.connecting, conn.status)

        conn.onGate(.ok)
        XCTAssertEqual(["probe", "feed"], r.actions)
        conn.onFeedConnected()
        XCTAssertEqual(.connecting, conn.status)

        conn.onFeedEvent(event: "hello", dataJson: #"{"revision":1694,"api":1,"boot":"b1","seq":2}"#)
        XCTAssertEqual(.online, conn.status)
        XCTAssertEqual(1, r.fetches.count)
        XCTAssertNil(r.fetches[0].0)
        XCTAssertEqual(1, r.hellos.count)

        let full = try XCTUnwrap(DsbotModels.decodeState(try TestFixtures.dsbotValue("state-full.json")))
        conn.onStateFetched(full)
        XCTAssertEqual("1694", conn.localRevisionForTest)

        let delay = conn.onFeedDisconnected()
        XCTAssertEqual(1_000, delay)
        XCTAssertEqual(.reconnecting, conn.status)
        XCTAssertEqual(2_000, conn.onFeedDisconnected())
        XCTAssertEqual(4_000, conn.onFeedDisconnected())
        XCTAssertEqual(60_000, FeedSync.defaultBackoffMs(attempt: 10))
        conn.onBackoffElapsed()
        XCTAssertEqual(["probe", "feed", "feed"], r.actions)

        conn.onFeedConnected()
        XCTAssertEqual(1_000, conn.onFeedDisconnected())
        conn.onFeedConnected()
        conn.onFeedEvent(event: "hello", dataJson: #"{"revision":1694}"#)
        XCTAssertEqual(1, r.fetches.count)
        XCTAssertEqual(.online, conn.status)

        // 重复 revision 不触发，新 revision 与 live 触发
        conn.onFeedEvent(event: "revision", dataJson: #"{"revision":1694}"#)
        XCTAssertEqual(1, r.fetches.count)
        conn.onFeedEvent(event: "revision", dataJson: #"{"revision":1695}"#)
        conn.onFeedEvent(event: "live", dataJson: "{}")
        XCTAssertEqual(3, r.fetches.count)

        conn.onBackground()
        XCTAssertEqual(.connecting, conn.status)
        _ = conn.onFeedDisconnected()
        XCTAssertEqual(.connecting, conn.status)
        conn.onForeground()
        XCTAssertEqual("probe", r.actions.last)

        conn.onUnreachable()
        XCTAssertEqual(.offline, conn.status)
        conn.onGate(.authExpired)
        XCTAssertEqual(.authExpired, conn.status)
        conn.onGate(.forbiddenHost)
        XCTAssertEqual(.forbiddenHost, conn.status)
    }

    // MARK: - 夹具脱敏校验

    func testSharedFixturesAreValidAndSanitized() throws {
        let contentKeys: Set<String> = [
            "text", "preview", "instructions", "brief", "notice", "question", "answer", "answers",
            "selected",
            "note", "content", "summary", "description", "goal", "draft", "kickoff",
            "label", "caption", "transcript", "role", "why", "after", "old", "purpose", "detail",
            "url", "endpoint", "baseurl", "name", "title",
            "data", "dataurl", "audiobase64",
            "progress", "result",
        ]

        func assertRedacted(_ value: JsonValue, inContent: Bool, path: String) {
            switch value {
            case .object(let obj):
                for (k, v) in obj.entries {
                    assertRedacted(v, inContent: inContent || contentKeys.contains(k.lowercased()), path: "\(path).\(k)")
                }
            case .array(let arr):
                for (i, v) in arr.enumerated() {
                    assertRedacted(v, inContent: inContent, path: "\(path)[\(i)]")
                }
            case .string(let s):
                if inContent {
                    XCTAssertTrue(s.hasPrefix("<redacted:"), "\(path) 未脱敏: \(s.prefix(24))")
                }
            default:
                break
            }
        }

        let jsonFixtures = [
            "state-full.json", "state-unchanged.json", "state-unchanged-live.json",
            "chat-page.json", "update-check.json", "error-envelope.json",
            "hello.json", "hello-api-unsupported.json", "notices.json", "notices-reset.json",
            "image-preview.json", "avatar.json", "error-unknown-endpoint.json",
            "markdown-image-forbidden.json", "file-text-unavailable.json", "file-download-invalid-offset.json",
            "bot-task-read-steps.json", "deepseek-services.json", "room-chat-rounds.json",
            "group-search-page1.json", "rooms-board-empty.json",
            "room-todo-unknown-room.json", "room-todo-refused.json",
            "room-todo-clear-empty.json", "task-cancel-refused.json", "task-note-unknown-room.json",
        ]

        for name in jsonFixtures {
            let text = try TestFixtures.dsbotText(name)
            XCTAssertFalse(text.contains("dsh-auth-"), "\(name) 含 cookie 形态")
            XCTAssertFalse(text.contains("token="), "\(name) 含 token 查询形态")
            assertRedacted(try TestFixtures.dsbotJson(name), inContent: false, path: name)
        }

        // 合成文件名的夹具确认合法 JSON 且无凭据
        let chatItemFilesText = try TestFixtures.dsbotText("chat-item-files.json")
        XCTAssertFalse(chatItemFilesText.contains("dsh-auth-"))
        XCTAssertFalse(chatItemFilesText.contains("token="))
        _ = try TestFixtures.dsbotJson("chat-item-files.json")

        let err401 = try TestFixtures.dsbotText("error-401-body.txt")
        XCTAssertFalse(err401.contains("dsh-auth-"))
        XCTAssertFalse(err401.contains("token="))

        for sseName in ["feed-stream.sse", "feed-stream-0310.sse"] {
            let sseText = try TestFixtures.dsbotText(sseName)
            XCTAssertFalse(sseText.contains("dsh-auth-"))
            XCTAssertFalse(sseText.contains("token="))
            let parser = SseParser()
            let frames = parser.feed(sseText) + parser.finish()
            XCTAssertFalse(frames.isEmpty, "\(sseName) 应解析出至少一帧")
            for (idx, frame) in frames.enumerated() {
                let dataJson = try XCTUnwrap(Json.parse(frame.data), "\(sseName) frame #\(idx) data 不是合法 JSON")
                assertRedacted(dataJson, inContent: false, path: "\(sseName)[\(idx)]")
            }
        }
    }
}
