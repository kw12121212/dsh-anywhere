import Foundation
import XCTest
@testable import DshCore

/// macos-team-sidebar：ReadState / NativePrefs / Notices / NoticeCursor 的 Kotlin 对等用例
/// 与真实夹具覆盖，以及控制器把团队列表与提醒横幅接入快照的编排。
final class TeamSidebarNoticesTests: XCTestCase {
    private func makeTempDir(_ prefix: String = "dsh-team-sidebar-test-") -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(prefix + UUID().uuidString)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func waitUntil(timeoutSeconds: TimeInterval = 3.0, _ predicate: @escaping () async -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            if await predicate() { return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("等待条件超时")
    }

    // MARK: - ReadState（与 ReadStateTest.kt 同型）

    private let host = "http://a.test:80"

    private func n(_ seq: Int64, _ kind: String = "message", bot: String = "b", room: String? = nil) ->
        Notices.Notice {
        Notices.Notice(seq: seq, time: nil, botId: bot, name: "名", kind: kind, sessionId: "s", roomId: room, title: nil, text: "文本")
    }

    func testReadStateMarkOnlyMovesForward() {
        var s = ReadState.empty.mark(host, "b1", 100)
        XCTAssertEqual(100, s.seenAt(host, id: "b1"))
        XCTAssertEqual(s, s.mark(host, "b1", 100)) // 相等不前移
        XCTAssertEqual(s, s.mark(host, "b1", 50)) // 只前移
        XCTAssertEqual(s, s.mark(host, "b1", -1)) // 非正数忽略
        XCTAssertEqual(s, s.mark("", "b1", 200)) // 空主机忽略
        XCTAssertEqual(s, s.mark(host, "", 200)) // 空条目忽略
        XCTAssertEqual(200, s.mark(host, "b1", 200).seenAt(host, id: "b1"))
        XCTAssertEqual(0, s.seenAt(host, id: "other"))
        XCTAssertEqual(0, s.seenAt("http://other:80", id: "b1")) // 按主机分开
    }

    func testReadStateMergeTakesMaxPerEntry() {
        let a = ReadState.empty.mark(host, "b1", 100).mark(host, "b2", 300)
        let b = ReadState.empty.mark(host, "b1", 200).mark("http://x:80", "r", 7)
        let m = a.merge(b)
        XCTAssertEqual(200, m.seenAt(host, id: "b1"))
        XCTAssertEqual(300, m.seenAt(host, id: "b2"))
        XCTAssertEqual(7, m.seenAt("http://x:80", id: "r"))
    }

    func testReadStateUnreadNeedsNewerActivityAndNotOpen() {
        let s = ReadState.empty.mark(host, "b1", 100)
        XCTAssertTrue(s.isUnread(host, id: "b1", lastAt: 101, open: false))
        XCTAssertFalse(s.isUnread(host, id: "b1", lastAt: 100, open: false)) // 边界：相等即已读
        XCTAssertFalse(s.isUnread(host, id: "b1", lastAt: 101, open: true)) // 当前打开的聊天不显示
        XCTAssertFalse(s.isUnread(host, id: "b1", lastAt: nil, open: false)) // 无 lastAt 不显示
        XCTAssertFalse(s.isUnread(host, id: "b1", lastAt: 0, open: false))
        XCTAssertTrue(s.isUnread(host, id: "never", lastAt: 1, open: false)) // 从未读过且有活动
    }

    func testReadStateSeedOnlyForUnknownHost() {
        let s = ReadState.empty
        let seeded = s.seed(host, lastAts: ["b1": 100, "b2": nil])
        XCTAssertEqual(100, seeded.seenAt(host, id: "b1"))
        XCTAssertFalse(seeded.isUnread(host, id: "b1", lastAt: 100, open: false)) // 首次见到的现有活动不算未读
        XCTAssertTrue(seeded.isUnread(host, id: "b1", lastAt: 101, open: false)) // 之后的新活动照常未读
        XCTAssertTrue(seeded.knows(host))
        XCTAssertFalse(seeded.knows("http://x:80"))
        // 已知主机不再打底
        let reseeded = seeded.seed(host, lastAts: ["b9": 500])
        XCTAssertEqual(0, reseeded.seenAt(host, id: "b9"))
    }

    func testReadStateReadAtUsesNewestOnlyForTheSameChat() {
        XCTAssertEqual(300, ReadState.readAt(targetId: "b1", lastAt: 100, newest: ("b1", 300)))
        XCTAssertEqual(100, ReadState.readAt(targetId: "b1", lastAt: 100, newest: ("b2", 900))) // 上一个聊天的时间不记到新聊天
        XCTAssertEqual(100, ReadState.readAt(targetId: "b1", lastAt: 100, newest: ("b1", 50)))
        XCTAssertEqual(0, ReadState.readAt(targetId: "b1", lastAt: nil, newest: nil))
        XCTAssertEqual(70, ReadState.readAt(targetId: "b1", lastAt: nil, newest: ("b1", 70)))
    }

    func testReadStateEncodeDecodeRoundTripAndTolerance() {
        let s = ReadState.empty
            .mark("http://a.test:80", "b1", 10)
            .mark("http://a.test:80", "b2", 20)
            .mark("http://x:80", "r", 7)
        XCTAssertEqual(s, ReadState.decode(s.encode()))
        XCTAssertEqual(ReadState.empty, ReadState.decode(nil))
        XCTAssertEqual(ReadState.empty, ReadState.decode(""))
        XCTAssertEqual(ReadState.empty, ReadState.decode("not json"))
        XCTAssertEqual(ReadState.empty, ReadState.decode("[1,2]"))
        XCTAssertEqual(ReadState.empty, ReadState.decode(#"{"v":1,"hosts":[]}"#))
        // 单个非法值跳过（非正数与非数字），其余保留
        let partial = ReadState.decode(#"{"v":1,"hosts":{"http://a.test:80":{"ok":10,"zero":0,"neg":-5,"bad":"x"}}}"#)
        XCTAssertEqual(10, partial.seenAt("http://a.test:80", id: "ok"))
        XCTAssertEqual(0, partial.seenAt("http://a.test:80", id: "zero"))
        XCTAssertEqual(0, partial.seenAt("http://a.test:80", id: "neg"))
        XCTAssertEqual(0, partial.seenAt("http://a.test:80", id: "bad"))
        // 小数字面量跳过（与 Kotlin 版一致：只收整数）
        XCTAssertEqual(0, ReadState.decode(#"{"v":1,"hosts":{"h":{"d":9.4}}}"#).seenAt("h", id: "d"))
    }

    func testReadStateHostKey() {
        XCTAssertEqual("https://h.test:8443", ReadState.hostKey("https://H.Test:8443/path?token=z"))
        XCTAssertEqual("http://h.test:80", ReadState.hostKey("http://h.test"))
        XCTAssertEqual("http://h.test:1234", ReadState.hostKey("http://h.test:1234/a/b?token=z#f"))
        XCTAssertNil(ReadState.hostKey("ftp://h.test"))
        XCTAssertNil(ReadState.hostKey("not a url"))
        XCTAssertNil(ReadState.hostKey(nil))
    }

    func testReadStateStoreRoundTripAndTolerance() throws {
        let dir = makeTempDir()
        let store = ReadStateStore(directory: dir)
        XCTAssertEqual(ReadState.empty, store.load()) // 无文件
        try store.save(ReadState.empty.mark("http://a.test:80", "b1", 42))
        XCTAssertEqual(42, store.load().seenAt("http://a.test:80", id: "b1"))
        let perms = try FileManager.default.attributesOfItem(atPath: store.fileURL.path)[.posixPermissions] as? Int
        XCTAssertEqual(0o600, perms)
        // 损坏内容回空（不影响其他配置）
        try Data("corrupt".utf8).write(to: store.fileURL)
        XCTAssertEqual(ReadState.empty, store.load())
        // 覆盖写
        try store.save(ReadState.empty.mark("http://a.test:80", "b1", 100))
        XCTAssertEqual(100, store.load().seenAt("http://a.test:80", id: "b1"))
        // 不留临时残留
        let leftovers = try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasPrefix(".read-state.json.") }
        XCTAssertTrue(leftovers.isEmpty)
    }

    // MARK: - NativePrefs

    func testNativePrefsDecodeDefaultsAndRoundTrip() throws {
        XCTAssertEqual(NativePrefs(), NativePrefs.decode(nil))
        XCTAssertEqual(NativePrefs(), NativePrefs.decode("corrupt"))
        XCTAssertEqual(NativePrefs(), NativePrefs.decode("{}")) // 缺键取默认（提醒开）
        XCTAssertEqual(false, NativePrefs.decode(#"{"noticeBannerEnabled":false}"#).noticeBannerEnabled)
        XCTAssertEqual(true, NativePrefs.decode(#"{"noticeBannerEnabled":true,"future":"x"}"#).noticeBannerEnabled)
        let prefs = NativePrefs(noticeBannerEnabled: false)
        let dir = makeTempDir()
        let store = NativePrefsStore(directory: dir)
        XCTAssertNil(store.load())
        try store.save(prefs)
        XCTAssertEqual(prefs, store.load())
    }

    // MARK: - Notices（真实夹具 + 源码核对形态）

    private func value(_ name: String) throws -> JsonValue {
        let root = try XCTUnwrap(try TestFixtures.dsbotJson(name).objectValue)
        return root["value"] ?? .object(root)
    }

    func testNoticesDecodesRealBatchAndReset() throws {
        let b = try XCTUnwrap(Notices.decodeBatch(value("notices.json")))
        XCTAssertEqual("237ee0c9-8b6b-4495-b63d-95bd2c05da59", b.boot)
        XCTAssertEqual(4, b.seq)
        XCTAssertFalse(b.reset)
        XCTAssertFalse(b.notices.isEmpty)
        XCTAssertEqual(1, b.notices[0].seq)
        XCTAssertEqual("message", b.notices[0].kind)
        XCTAssertTrue(b.notices[0].botId.hasPrefix("session-"))
        XCTAssertTrue(b.notices.allSatisfy { $0.seq <= 4 })

        let r = try XCTUnwrap(Notices.decodeBatch(value("notices-reset.json")))
        XCTAssertTrue(r.reset)
    }

    func testNoticesDecodesRealFeedNoticeEvent() throws {
        let lines = try TestFixtures.dsbotText("feed-stream-0310.sse")
            .components(separatedBy: .newlines)
        let i = try XCTUnwrap(lines.firstIndex(of: "event: notice"))
        let data = try XCTUnwrap(lines[i + 1].hasPrefix("data: ") ? String(lines[i + 1].dropFirst(6)) : nil)
        let notice = try XCTUnwrap(Notices.decode(Json.parse(data)))
        XCTAssertEqual(5, notice.seq)
        XCTAssertEqual("message", notice.kind)
        XCTAssertTrue(Notices.shown(notice))
        XCTAssertEqual(notice.botId, Notices.target(notice))
    }

    func testNoticesDecodeRejectsMissingFields() {
        func obj(_ text: String) -> JsonValue? { Json.parse(text) }
        XCTAssertNil(Notices.decode(obj(#"{"botId":"b","kind":"message"}"#)))
        XCTAssertNil(Notices.decode(obj(#"{"seq":1,"kind":"message"}"#)))
        XCTAssertNil(Notices.decode(obj(#"{"seq":1,"botId":"","kind":"message"}"#)))
        XCTAssertNil(Notices.decode(obj(#"{"seq":1,"botId":"b"}"#)))
        XCTAssertNil(Notices.decode(obj("[1]")))
        XCTAssertNil(Notices.decodeBatch(obj(#"{"boot":"x"}"#)))
        // 批量里个别坏条目跳过
        let batch = Notices.decodeBatch(obj(#"{"boot":"x","seq":2,"notices":[{"seq":2,"botId":"b","kind":"done"},{"bad":1}]}"#))
        XCTAssertEqual(1, batch?.notices.count)
    }

    func testNoticesKindWhitelist() {
        for k in ["question", "approval", "message", "group", "done"] {
            XCTAssertTrue(Notices.shown(n(1, k)))
        }
        XCTAssertFalse(Notices.shown(n(1, "view")))
        XCTAssertFalse(Notices.shown(n(1, "weird")))
    }

    func testNoticesBannerTextAndTarget() {
        // 私聊提问：无 title，摘要取 text
        let q = Notices.Notice(seq: 1, time: nil, botId: "bot-b", name: "研究员", kind: "question", sessionId: "s", roomId: nil, title: nil, text: "要选哪个方案？")
        XCTAssertEqual("研究员 · 提问：要选哪个方案？", Notices.banner(q))
        let m = Notices.Notice(seq: 2, time: nil, botId: "bot-b", name: nil, kind: "message", sessionId: "s", roomId: nil, title: nil, text: "第一行\n第二行   第三行")
        XCTAssertEqual("Bot · 回复：第一行 第二行 第三行", Notices.banner(m))
        let long = Notices.Notice(seq: 3, time: nil, botId: "b", name: "B", kind: "message", sessionId: nil, roomId: nil, title: nil, text: String(repeating: "字", count: 200))
        XCTAssertEqual("B · 回复：".count + Notices.summaryMax, Notices.banner(long).utf16.count)
        XCTAssertEqual("B · 等你审批", Notices.banner(Notices.Notice(seq: 4, time: nil, botId: "b", name: "B", kind: "approval", sessionId: nil, roomId: nil, title: nil, text: nil)))
        XCTAssertEqual("room-1", Notices.target(n(5, "group", room: "room-1")))
        XCTAssertEqual("b", Notices.target(n(6, "group", room: "")))
    }

    func testNoticesGroupFormsSourceFromTitle() {
        // 上游 roomLineAdded：{kind:group, roomId, title: room.name, text: 消息正文}（源码核对形态）
        let group = Notices.Notice(seq: 7, time: nil, botId: "bot-r", name: "研究员", kind: "group", sessionId: nil, roomId: "room-1", title: "项目群", text: "第二阶段已完成")
        XCTAssertEqual("项目群 · 研究员 · 群消息：第二阶段已完成", Notices.banner(group))
        // 群任务完成：text 为空，只显示来源与类型
        let done = Notices.Notice(seq: 8, time: nil, botId: "bot-r", name: "研究员", kind: "done", sessionId: nil, roomId: "room-1", title: "项目群", text: "")
        XCTAssertEqual("项目群 · 研究员 · 任务完成", Notices.banner(done))
        // 群内审批：approvalWaiting 的 text 是工具名
        let approval = Notices.Notice(seq: 9, time: nil, botId: "bot-r", name: "研究员", kind: "approval", sessionId: "s", roomId: "room-1", title: "项目群", text: "bash")
        XCTAssertEqual("项目群 · 研究员 · 等你审批：bash", Notices.banner(approval))
        // 没有 roomId 时 title 不当群名
        XCTAssertEqual("研究员 · 回复：hi", Notices.banner(Notices.Notice(seq: 10, time: nil, botId: "b", name: "研究员", kind: "message", sessionId: "s", roomId: nil, title: "怪标题", text: "hi")))
    }

    func testNoticesPickBannerSkipsCurrentChatAndHiddenKinds() {
        // 停在 A 时断线：B 提问（5）、A 回复（6）——不能因最新一条是当前聊天而把 B 的提问一并压掉
        let b = n(5, "question", bot: "B")
        let a = n(6, "message", bot: "A")
        XCTAssertEqual(b, Notices.pickBanner([b, a], currentTarget: "A"))
        XCTAssertEqual(a, Notices.pickBanner([b, a], currentTarget: nil))
        XCTAssertNil(Notices.pickBanner([a], currentTarget: "A"))
        XCTAssertEqual(b, Notices.pickBanner([b, n(7, "view", bot: "C")], currentTarget: nil))
        // 群目标按 roomId 比较
        let g = n(8, "group", bot: "B", room: "room-1")
        XCTAssertNil(Notices.pickBanner([g], currentTarget: "room-1"))
        XCTAssertEqual(g, Notices.pickBanner([g], currentTarget: "B"))
        XCTAssertNil(Notices.pickBanner([], currentTarget: nil))
    }

    // MARK: - NoticeCursor（与 NoticesTest.kt 同型）

    func testCursorFirstHelloAdoptsCursorWithoutBackfill() {
        let c = NoticeCursor()
        XCTAssertEqual(.none, c.onHello("boot-1", 40))
        XCTAssertEqual("boot-1", c.boot)
        XCTAssertEqual(40, c.seq)
        XCTAssertFalse(c.accept(n(40))) // 首次连接前的旧提醒不弹
        XCTAssertTrue(c.accept(n(41)))
    }

    func testCursorLiveDuplicatesAndOutOfOrderDropped() {
        let c = NoticeCursor()
        _ = c.onHello("boot-1", 0)
        XCTAssertTrue(c.accept(n(3)))
        XCTAssertFalse(c.accept(n(3)))
        XCTAssertFalse(c.accept(n(2))) // 乱序晚到
        XCTAssertTrue(c.accept(n(4)))
        XCTAssertEqual(4, c.seq)
    }

    func testCursorSameBootReconnectFills() {
        let c = NoticeCursor()
        _ = c.onHello("boot-1", 5)
        _ = c.accept(n(6))
        XCTAssertEqual(.fill(boot: "boot-1", since: 6), c.onHello("boot-1", 9))
        XCTAssertEqual(.none, c.onHello("boot-1", 6)) // 没有新提醒
        let r = c.onBatch(Notices.Batch(boot: "boot-1", seq: 9, reset: false, notices: [n(9), n(6), n(7), n(8)]))
        XCTAssertFalse(r.refetch)
        XCTAssertEqual([7, 8, 9], r.fresh.map(\.seq)) // 已见的 6 丢弃，按 seq 升序
        XCTAssertEqual(9, c.seq)
    }

    func testCursorBootChangeResetsWithoutRepeat() {
        let c = NoticeCursor()
        _ = c.onHello("boot-1", 5)
        _ = c.accept(n(6))
        XCTAssertEqual(.reset, c.onHello("boot-2", 3)) // dsh 重启
        XCTAssertEqual("boot-2", c.boot)
        XCTAssertEqual(3, c.seq)
        XCTAssertFalse(c.accept(n(2)))
        XCTAssertTrue(c.accept(n(4)))
    }

    func testCursorHelloWithoutBootAfterBootIsReset() {
        let c = NoticeCursor()
        XCTAssertEqual(.none, c.onHello(nil, nil)) // 0.3.6 不带游标
        _ = c.onHello("boot-1", 1)
        XCTAssertEqual(.reset, c.onHello(nil, nil)) // 回退到旧版
        XCTAssertNil(c.boot)
    }

    func testCursorLiveNoticeDuringFillMakesOlderBatchItemsStale() {
        let c = NoticeCursor()
        _ = c.onHello("boot-1", 5)
        XCTAssertEqual(.fill(boot: "boot-1", since: 5), c.onHello("boot-1", 8))
        XCTAssertTrue(c.accept(n(9))) // 补齐在途时实时提醒先到
        let r = c.onBatch(Notices.Batch(boot: "boot-1", seq: 9, reset: false, notices: [n(6), n(7), n(8), n(9)]))
        XCTAssertFalse(r.refetch)
        XCTAssertEqual([], r.fresh.map(\.seq)) // 都不大于已见的 9
        XCTAssertEqual(9, c.seq)
        // 应答 seq 小于游标时游标不回退
        _ = c.onBatch(Notices.Batch(boot: "boot-1", seq: 3, reset: false, notices: []))
        XCTAssertEqual(9, c.seq)
        // 同 boot 但 hello 不带 seq：不补齐
        XCTAssertEqual(.none, c.onHello("boot-1", nil))
    }

    func testCursorBatchResetOrForeignBootRefetches() throws {
        let c = NoticeCursor()
        _ = c.onHello("boot-1", 5)
        let r = c.onBatch(Notices.Batch(boot: "boot-1", seq: 300, reset: true, notices: [n(299)]))
        XCTAssertTrue(r.refetch)
        XCTAssertEqual([], r.fresh.map(\.seq))
        XCTAssertEqual(300, c.seq)
        let r2 = c.onBatch(Notices.Batch(boot: "boot-9", seq: 2, reset: false, notices: [n(2)]))
        XCTAssertTrue(r2.refetch)
        XCTAssertEqual("boot-9", c.boot)
        // 真实 reset 夹具同样判为重取
        XCTAssertTrue(c.onBatch(try XCTUnwrap(Notices.decodeBatch(value("notices-reset.json")))).refetch)
    }

    // MARK: - 控制器编排（team 进快照、notice 横幅、补齐与门控）

    private final class Box<T>: @unchecked Sendable {
        private let lock = NSLock()
        private var v: T
        init(_ v: T) { self.v = v }
        var value: T { lock.withLock { v } }
        func set(_ nv: T) { lock.withLock { v = nv } }
    }

    private func makeApiMock(
        stateJson: String,
        noticesJson: String? = nil,
        noticesLog: Box<[String]>? = nil
    ) -> ScriptedHttpTransport {
        let apiMock = ScriptedHttpTransport()
        let updateJson = try! TestFixtures.dsbotText("update-check.json")
        let helloJson = try! TestFixtures.dsbotText("hello.json")
        apiMock.handler = { req in
            let body = String(decoding: req.body ?? Data(), as: UTF8.self)
            if body.contains(#""endpoint":"update-check""#) {
                return HttpResponse(statusCode: 200, headers: [], body: Data(updateJson.utf8))
            }
            if body.contains(#""endpoint":"hello""#) {
                return HttpResponse(statusCode: 200, headers: [], body: Data(helloJson.utf8))
            }
            if body.contains(#""endpoint":"state""#) {
                return HttpResponse(statusCode: 200, headers: [], body: Data(stateJson.utf8))
            }
            if body.contains(#""endpoint":"notices""#) {
                noticesLog?.set(noticesLog!.value + [body])
                if let noticesJson {
                    return HttpResponse(statusCode: 200, headers: [], body: Data(noticesJson.utf8))
                }
            }
            return HttpResponse(statusCode: 404, headers: [], body: Data())
        }
        return apiMock
    }

    /// 已保存配置与会话 cookie 的控制器（免走首次提交流程，直达在线编排）。
    private func makeController(
        apiMock: ScriptedHttpTransport,
        stream: SseStream,
        noticesEnabled: (@Sendable () -> Bool)? = nil
    ) throws -> MacosSessionController {
        let tmpDir = makeTempDir()
        try HarnessConfigStore(directory: tmpDir).save("http://127.0.0.1:13080/")
        let cookieStore = SessionCookieStore(directory: tmpDir)
        try cookieStore.replaceCookies(
            [SessionCookies.Cookie(name: "dsh-auth-s", value: "v1.test")],
            forUrl: "http://127.0.0.1:13080/"
        )
        cookieStore.load()
        return MacosSessionController(
            configStore: HarnessConfigStore(directory: tmpDir),
            cookieStore: cookieStore,
            probeTransport: apiMock,
            apiTransport: apiMock,
            sseStream: stream,
            noticesEnabled: noticesEnabled ?? { true }
        )
    }

    func testControllerPublishesTeamAndNotices() async throws {
        let boot = "237ee0c9-8b6b-4495-b63d-95bd2c05da59"
        let fillBatch = #"{"ok":true,"value":{"boot":"\#(boot)","seq":20,"reset":false,"notices":[{"seq":14,"botId":"bot-d","name":"D","kind":"done","sessionId":null,"roomId":null,"title":null,"text":null}]}}"#
        let noticesLog = Box<[String]>([])
        let apiMock = makeApiMock(
            stateJson: try TestFixtures.dsbotText("state-full.json"),
            noticesJson: fillBatch,
            noticesLog: noticesLog
        )
        let stream = LiveSseStream(initialChunks: [
            "event: hello\ndata: {\"revision\":\"1694\",\"api\":1,\"boot\":\"\(boot)\",\"seq\":10}\n\n",
        ])
        let controller = try makeController(apiMock: apiMock, stream: stream)

        await controller.start()
        try await waitUntil {
            let s = await controller.currentSnapshot()
            return s.status == .online && s.team != nil
        }
        var snap = await controller.currentSnapshot()
        // 团队列表组装进快照（state-full：11 Bot + 2 群，主 Bot 分组内排前）
        let team = try XCTUnwrap(snap.team)
        XCTAssertEqual(13, team.sections.reduce(0) { $0 + $1.entries.count })
        XCTAssertTrue(team.sections.contains { $0.entries.first?.isMain == true })

        // 非当前聊天的实时提醒：横幅发布（文本只用于界面）
        stream.yield("event: notice\ndata: {\"seq\":11,\"botId\":\"bot-b\",\"name\":\"研究员\",\"kind\":\"question\",\"sessionId\":\"s1\",\"text\":\"要选哪个方案？\"}\n\n")
        try await waitUntil { () -> Bool in
            let s = await controller.currentSnapshot()
            return s.noticeBanner != nil
        }
        snap = await controller.currentSnapshot()
        let banner1 = try XCTUnwrap(snap.noticeBanner)
        XCTAssertEqual("研究员 · 提问：要选哪个方案？", banner1.text)
        XCTAssertEqual("bot-b", banner1.target)

        // 收起后：当前聊天的提醒不弹、其他聊天的照常弹
        await controller.dismissNotice(id: banner1.id)
        await controller.setCurrentTarget("bot-b")
        stream.yield("event: notice\ndata: {\"seq\":12,\"botId\":\"bot-b\",\"name\":\"研究员\",\"kind\":\"question\",\"sessionId\":\"s1\",\"text\":\"又一个？\"}\n\n")
        try await Task.sleep(nanoseconds: 300_000_000)
        let afterCurrent = await controller.currentSnapshot()
        XCTAssertNil(afterCurrent.noticeBanner)
        stream.yield("event: notice\ndata: {\"seq\":13,\"botId\":\"bot-c\",\"name\":\"C\",\"kind\":\"message\",\"sessionId\":\"s2\",\"text\":\"hi\"}\n\n")
        try await waitUntil { () -> Bool in
            let s = await controller.currentSnapshot()
            return s.noticeBanner?.target == "bot-c"
        }
        let snap2 = await controller.currentSnapshot()
        let banner2 = try XCTUnwrap(snap2.noticeBanner)
        XCTAssertEqual("C · 回复：hi", banner2.text)
        await controller.dismissNotice(id: banner2.id)

        // 同一 boot 再见 hello（seq 前进）：经 notices {boot, since} 补齐，fresh 出横幅
        stream.yield("event: hello\ndata: {\"revision\":\"1694\",\"api\":1,\"boot\":\"\(boot)\",\"seq\":20}\n\n")
        try await waitUntil { () -> Bool in
            let s = await controller.currentSnapshot()
            return s.noticeBanner?.target == "bot-d"
        }
        let snap3 = await controller.currentSnapshot()
        let banner3 = try XCTUnwrap(snap3.noticeBanner)
        XCTAssertEqual("D · 任务完成", banner3.text)
        // 补齐请求体以游标为 since
        let fillBody = try XCTUnwrap(noticesLog.value.last)
        XCTAssertTrue(fillBody.contains(#""since":13"#), fillBody)
        XCTAssertTrue(fillBody.contains(#""boot":"\#(boot)""#), fillBody)
        await controller.stop()
    }

    func testControllerNoticeDisabledAndLegacyCapability() async throws {
        let boot = "237ee0c9-8b6b-4495-b63d-95bd2c05da59"
        // 开关关闭：不发布横幅
        let offStream = LiveSseStream(initialChunks: [
            "event: hello\ndata: {\"revision\":\"1694\",\"api\":1,\"boot\":\"\(boot)\",\"seq\":10}\n\n",
        ])
        let off = try makeController(
            apiMock: makeApiMock(stateJson: try TestFixtures.dsbotText("state-full.json")),
            stream: offStream,
            noticesEnabled: { false }
        )
        await off.start()
        try await waitUntil { await off.currentSnapshot().status == .online }
        offStream.yield("event: notice\ndata: {\"seq\":11,\"botId\":\"bot-b\",\"name\":\"B\",\"kind\":\"question\",\"sessionId\":\"s\",\"text\":\"?\"}\n\n")
        try await Task.sleep(nanoseconds: 300_000_000)
        let offSnap = await off.currentSnapshot()
        XCTAssertNil(offSnap.noticeBanner)
        await off.stop()

        // 远端未声明 notices 能力（0.3.6 无 hello）：notice 事件忽略
        let legacyState = #"{"ok":true,"value":{"revision":"r1","bots":[{"id":"b1","name":"B1"}],"rooms":[]}}"#
        let apiMock = ScriptedHttpTransport()
        apiMock.handler = { req in
            let body = String(decoding: req.body ?? Data(), as: UTF8.self)
            if body.contains(#""endpoint":"update-check""#) {
                let updateJson = try! TestFixtures.dsbotText("update-check.json")
                return HttpResponse(statusCode: 200, headers: [], body: Data(updateJson.utf8))
            }
            if body.contains(#""endpoint":"hello""#) {
                // 0.3.6：未知端点 → bot/not-found
                let err = try! TestFixtures.dsbotText("error-unknown-endpoint.json")
                return HttpResponse(statusCode: 200, headers: [], body: Data(err.utf8))
            }
            if body.contains(#""endpoint":"state""#) {
                return HttpResponse(statusCode: 200, headers: [], body: Data(legacyState.utf8))
            }
            return HttpResponse(statusCode: 404, headers: [], body: Data())
        }
        let legacyStream = LiveSseStream(initialChunks: [
            "event: hello\ndata: {\"revision\":\"r1\"}\n\n",
        ])
        let legacy = try makeController(apiMock: apiMock, stream: legacyStream)
        await legacy.start()
        try await waitUntil { () -> Bool in
            let s = await legacy.currentSnapshot()
            return s.status == .online && s.team != nil
        }
        legacyStream.yield("event: notice\ndata: {\"seq\":1,\"botId\":\"b1\",\"name\":\"B1\",\"kind\":\"question\",\"text\":\"?\"}\n\n")
        try await Task.sleep(nanoseconds: 300_000_000)
        let legacySnap = await legacy.currentSnapshot()
        XCTAssertNil(legacySnap.noticeBanner)
        await legacy.stop()
    }

    func testControllerBootChangeRefetchesStateWithoutBanner() async throws {
        let boot = "237ee0c9-8b6b-4495-b63d-95bd2c05da59"
        let apiMock = makeApiMock(stateJson: try TestFixtures.dsbotText("state-full.json"))
        let stream = LiveSseStream(initialChunks: [
            "event: hello\ndata: {\"revision\":\"1694\",\"api\":1,\"boot\":\"\(boot)\",\"seq\":10}\n\n",
        ])
        let controller = try makeController(apiMock: apiMock, stream: stream)
        await controller.start()
        try await waitUntil {
            let s = await controller.currentSnapshot()
            return s.status == .online && s.team != nil
        }
        let statePostsBefore = apiMock.requests.filter {
            String(decoding: $0.body ?? Data(), as: UTF8.self).contains(#""endpoint":"state""#)
        }.count
        // dsh 重启：feed hello 换 boot → 重取整体状态，不补弹
        stream.yield("event: hello\ndata: {\"revision\":\"1694\",\"api\":1,\"boot\":\"boot-new\",\"seq\":3}\n\n")
        try await waitUntil {
            let posts = apiMock.requests.filter {
                String(decoding: $0.body ?? Data(), as: UTF8.self).contains(#""endpoint":"state""#)
            }.count
            return posts > statePostsBefore
        }
        // 重取为全量：请求体不带 since/live
        let resetBody = apiMock.requests
            .filter { String(decoding: $0.body ?? Data(), as: UTF8.self).contains(#""endpoint":"state""#) }
            .last!
        let resetText = String(decoding: resetBody.body ?? Data(), as: UTF8.self)
        XCTAssertFalse(resetText.contains("since"), resetText)
        let bootSnap = await controller.currentSnapshot()
        XCTAssertNil(bootSnap.noticeBanner)
        await controller.stop()
    }
}

/// 打开时先产出既有块、之后可继续投递并保持挂起的 SSE 流（测试注入用）。
private final class LiveSseStream: SseStream, @unchecked Sendable {
    private let lock = NSLock()
    private var initialChunks: [String]
    private var continuations: [UUID: AsyncThrowingStream<String, Error>.Continuation] = [:]

    init(initialChunks: [String]) {
        self.initialChunks = initialChunks
    }

    func yield(_ chunk: String) {
        lock.withLock {
            for cont in continuations.values {
                cont.yield(chunk)
            }
        }
    }

    func open(url: String, headers: [(name: String, value: String)]) async throws -> AsyncThrowingStream<String, Error> {
        let chunks = lock.withLock {
            let out = initialChunks
            initialChunks = []
            return out
        }
        return AsyncThrowingStream { continuation in
            let id = UUID()
            lock.withLock {
                continuations[id] = continuation
            }
            continuation.onTermination = { [id] _ in
                self.lock.withLock {
                    self.continuations.removeValue(forKey: id)
                }
            }
            for chunk in chunks {
                continuation.yield(chunk)
            }
            // 保持挂起直到任务取消
        }
    }
}
