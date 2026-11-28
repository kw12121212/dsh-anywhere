import XCTest
@testable import DshCore

/// macos-bot-chat：Outbox / ChatCache / ReadFlight / ChatRefresh / JumpButton /
/// FileDownload 与 BotChatApi 的 Kotlin 对等用例。
final class BotChatCoreTests: XCTestCase {
    private func row(_ seq: Int64?, _ text: String) -> Outbox.UserRow {
        Outbox.UserRow(order: seq, text: text)
    }

    // MARK: - Outbox（与 OutboxTest.kt 同型）

    func testServerRowRemovesSendingEntry() {
        let o = Outbox()
        let e = o.add("h:bot:a", text: "你好", display: "你好", window: 10)
        XCTAssertEqual([e.id], o.reconcile("h:bot:a", userRows: [row(5, "旧"), row(11, "你好")]))
        XCTAssertEqual([], o.entries("h:bot:a"))
    }

    func testOldSameTextRowOutsideWindowDoesNotMatch() {
        let o = Outbox()
        o.add("k", text: "好的", display: "好的", window: 20)
        XCTAssertEqual([], o.reconcile("k", userRows: [row(3, "好的"), row(20, "好的")])) // seq ≤ 窗口
        XCTAssertEqual(1, o.entries("k").count)
    }

    func testRowsWithoutSeqNeverMatch() {
        let o = Outbox()
        o.add("k", text: "hi", display: "hi", window: -1)
        XCTAssertEqual([], o.reconcile("k", userRows: [row(nil, "hi")]))
        XCTAssertEqual(1, o.entries("k").count)
    }

    func testRapidIdenticalSendsMatchFirstInFirstOut() {
        let o = Outbox()
        let e1 = o.add("k", text: "好的", display: "好的", window: 7)
        let e2 = o.add("k", text: "好的", display: "好的", window: 7)
        XCTAssertEqual([e1.id], o.reconcile("k", userRows: [row(8, "好的")]))
        XCTAssertEqual([e2.id], o.entries("k").map(\.id))
        XCTAssertEqual([e2.id], o.reconcile("k", userRows: [row(8, "好的"), row(9, "好的")]))
        XCTAssertEqual([], o.entries("k"))
    }

    func testTwoPendingSameTextNeedTwoServerRows() {
        let o = Outbox()
        o.add("k", text: "ok", display: "ok", window: 1)
        o.add("k", text: "ok", display: "ok", window: 1)
        XCTAssertEqual(1, o.reconcile("k", userRows: [row(2, "ok")]).count)
        XCTAssertEqual(1, o.entries("k").count)
    }

    func testFailedEntryAlsoRemovedWhenDelivered() {
        let o = Outbox()
        let e = o.add("k", text: "报告", display: "报告", window: 0)
        _ = o.setState(e.id, .failed)
        XCTAssertEqual(.failed, o.get(e.id)?.state)
        XCTAssertEqual([e.id], o.reconcile("k", userRows: [row(1, "报告")]))
        XCTAssertNil(o.get(e.id))
        XCTAssertNil(o.setState(e.id, .sending)) // 已移除
    }

    func testFailedEntryDoesNotStealConfirmationOfLaterSend() {
        let o = Outbox()
        let failed = o.add("k", text: "继续", display: "继续", window: 10)
        _ = o.setState(failed.id, .failed)
        let sent = o.add("k", text: "继续", display: "继续", window: 20)
        _ = o.setState(sent.id, .sent)
        XCTAssertEqual([sent.id], o.reconcile("k", userRows: [row(21, "继续")]))
        XCTAssertEqual([failed.id], o.entries("k").map(\.id))
        XCTAssertEqual([failed.id], o.reconcile("k", userRows: [row(21, "继续"), row(22, "继续")]))
    }

    func testEmptyTextEntryMatchesOnlyEmptyText() {
        let o = Outbox()
        o.add("k", text: "", display: "[附件] a.png", receipts: ["r"], window: 0)
        XCTAssertEqual([], o.reconcile("k", userRows: [row(1, "hi")]))
        XCTAssertEqual(1, o.reconcile("k", userRows: [row(2, "  ")]).count)
    }

    func testWhitespaceDifferencesStillMatch() {
        let o = Outbox()
        o.add("k", text: "  第一行\n第二行  ", display: "x", window: 0)
        XCTAssertEqual(1, o.reconcile("k", userRows: [row(1, "第一行 第二行")]).count)
        XCTAssertEqual("a b", Outbox.normalize(" a \t\n b "))
    }

    func testChatsAreIndependent() {
        let o = Outbox()
        let a = o.add("A", text: "hi", display: "hi", window: 0)
        let b = o.add("B", text: "hi", display: "hi", receipts: ["r1"], window: 0)
        XCTAssertEqual([], o.reconcile("A", userRows: []))
        XCTAssertEqual([b.id], o.reconcile("B", userRows: [row(1, "hi")]))
        XCTAssertEqual([a.id], o.entries("A").map(\.id))
        XCTAssertEqual([], o.entries("B"))
        XCTAssertEqual([], o.reconcile("missing", userRows: [row(1, "hi")]))
    }

    func testRetryAndDelete() {
        let o = Outbox()
        let e = o.add("k", text: "t", display: "t [附件] a.png", receipts: ["r1", "r2"], window: 3)
        _ = o.setState(e.id, .failed)
        let back = o.setState(e.id, .sending)
        XCTAssertEqual(.sending, back?.state)
        XCTAssertEqual(["r1", "r2"], back?.receipts) // 重试原样带回执
        XCTAssertTrue(o.remove(e.id))
        XCTAssertFalse(o.remove(e.id))
        XCTAssertEqual([], o.entries("k"))
    }

    // MARK: - ChatCache / ScrollMemory / ReadFlight

    func testChatCacheLruAndInvalidate() {
        let cache = ChatCache<Int>(capacity: 3)
        for i in 0..<3 {
            cache.put("k\(i)", .init(items: [i], cursor: nil, sessionId: nil))
        }
        XCTAssertEqual(["k0", "k1", "k2"], cache.keys)
        _ = cache.entry("k0") // 访问提升
        cache.put("k3", .init(items: [3], cursor: nil, sessionId: nil)) // 逐出最旧 k1
        XCTAssertEqual(["k2", "k0", "k3"], cache.keys)
        cache.invalidate("k0")
        XCTAssertEqual(["k2", "k3"], cache.keys)
        XCTAssertEqual([2], cache.entry("k2")?.items)
    }

    func testChatKeyHostIsolation() {
        let cache = ChatCache<Int>()
        let a = cache.key(host: "http://a:80", kind: "bot", id: "b1")
        let b = cache.key(host: nil, kind: "bot", id: "b1")
        XCTAssertEqual("http://a:80:bot:b1", a)
        XCTAssertEqual(":bot:b1", b)
        XCTAssertNotEqual(a, b)
    }

    func testScrollMemorySaveForgetAndCapacity() {
        let m = ScrollMemory(capacity: 2)
        m.save("a", index: 1, offset: 20)
        m.save("b", index: 2, offset: 30)
        m.save("c", index: 3, offset: 40) // 逐出 a
        XCTAssertEqual(2, m.count)
        XCTAssertNil(m.position("a"))
        XCTAssertEqual(ScrollMemory.Position(index: 3, offset: 40), m.position("c"))
        m.save("bad", index: -1, offset: 0) // 非法不写
        XCTAssertNil(m.position("bad"))
        m.forget("c")
        XCTAssertNil(m.position("c"))
    }

    func testReadFlightSingleFlightAndStale() {
        let f = ReadFlight()
        guard case .start(let g1) = f.begin("a") else { return XCTFail() }
        XCTAssertEqual(.queued, f.begin("a"))
        XCTAssertEqual(.ignored, f.begin("a"))
        var fin = f.finish(g1)
        XCTAssertFalse(fin.stale)
        XCTAssertTrue(fin.rerun) // 排队过 → 应重跑
        guard case .start(let g2) = f.begin("b") else { return XCTFail() } // 换目标
        fin = f.finish(g1) // 旧代号 → 过期丢弃
        XCTAssertTrue(fin.stale)
        XCTAssertFalse(fin.rerun)
        fin = f.finish(g2)
        XCTAssertFalse(fin.stale)
        // reset：在途作废，下一次 begin 立即开始
        guard case .start(let g3) = f.begin("c") else { return XCTFail() }
        f.reset()
        fin = f.finish(g3)
        XCTAssertTrue(fin.stale)
        guard case .start = f.begin("c") else { return XCTFail() }
    }

    // MARK: - ChatRefresh

    func testChatRefreshOverlapMergeKeepsEarlierAndCursor() {
        let current = ["a", "b", "c", "d", "e"] // a..c 为已翻更早 + 旧首页 d,e
        let fresh = ["c", "d", "e"] // 新首页从 c 开始（连续 ≥3 条一致）
        let r = ChatRefresh.merge(current, fresh)
        XCTAssertEqual(["a", "b", "c", "d", "e"], r.items)
        XCTAssertTrue(r.keepCursor)
        XCTAssertFalse(r.changed)
        // 新首页短于 minRun 且全部一致到末尾：也算重叠（保留更早记录）
        let short = ChatRefresh.merge(current, ["d", "e"])
        XCTAssertTrue(short.keepCursor)
        // 有新消息：尾部追加
        let appended = ChatRefresh.merge(current, ["c", "d", "e", "f"])
        XCTAssertEqual(["a", "b", "c", "d", "e", "f"], appended.items)
        XCTAssertTrue(appended.changed)
    }

    func testChatRefreshNoOverlapResets() {
        let r = ChatRefresh.merge(["a", "b"], ["x", "y", "z"])
        XCTAssertEqual(["x", "y", "z"], r.items)
        XCTAssertFalse(r.keepCursor)
        let forced = ChatRefresh.merge(["a", "b"], ["x"], forceReset: true)
        XCTAssertEqual(["x"], forced.items)
    }

    func testChatRefreshKeepsLocalItems() {
        let current = ["a", "b", "local1"]
        let fresh = ["x", "y", "z"]
        let r = ChatRefresh.merge(current, fresh, isLocal: { $0.hasPrefix("local") })
        XCTAssertEqual(["x", "y", "z", "local1"], r.items)
        // 本地条目已在新首页时不再追加
        let r2 = ChatRefresh.merge(["a", "local1"], ["local1", "y", "z"], isLocal: { $0.hasPrefix("local") })
        XCTAssertEqual(["local1", "y", "z"], r2.items)
    }

    func testOverlapStartPrefersLongestRun() {
        XCTAssertEqual(2, ChatRefresh.overlapStart(["a", "b", "c", "d"], ["c", "d"]))
        XCTAssertEqual(-1, ChatRefresh.overlapStart(["a", "b"], ["x"]))
        // 伪重叠（同内容工具卡）不成立：连续不足 minRun 且 fresh 足够长
        XCTAssertEqual(-1, ChatRefresh.overlapStart(["a", "b", "c", "d", "e"], ["e", "z", "w", "v"]))
    }

    // MARK: - JumpButton / ScrollDistance

    func testJumpButtonLabelAndCounting() {
        let j = JumpButton(thresholdPx: 600)
        XCTAssertNil(j.label)
        j.onDistance(700)
        XCTAssertEqual("回到最新", j.label)
        j.onAppended(2)
        j.onAppended(1)
        XCTAssertEqual("3 条新消息", j.label)
        j.onJumped()
        j.onJumpEnded()
        XCTAssertNil(j.label)
        j.onDistance(100)
        j.onAppended(1) // 不在 away 不计数
        XCTAssertNil(j.label)
    }

    func testScrollDistanceBelowPx() {
        XCTAssertEqual(0, ScrollDistance.belowPx(totalItems: 0, lastVisibleIndex: -1, lastVisibleBottomPx: 0, viewportEndPx: 100, averageItemPx: 10))
        // 末项底部超视口 30px + 之后 4 项 × 平均 10px
        XCTAssertEqual(70, ScrollDistance.belowPx(totalItems: 10, lastVisibleIndex: 5, lastVisibleBottomPx: 130, viewportEndPx: 100, averageItemPx: 10))
    }

    // MARK: - BotChatApi 请求体字面值

    func testBotChatApiPayloads() {
        XCTAssertEqual(#"{"botId":"b1"}"#, BotChatApi.chatPayload(botId: "b1"))
        XCTAssertEqual(#"{"botId":"b1","cursor":{"part":1,"beforeSeq":9}}"#, BotChatApi.chatPayload(botId: "b1", cursor: Json.parse(#"{"part":1,"beforeSeq":9}"#)))
        XCTAssertEqual(#"{"sessionId":"s1","cursor":{"part":1}}"#, BotChatApi.earlierPayload(sessionId: "s1", cursor: Json.parse(#"{"part":1}"#)!))
        XCTAssertEqual(#"{"sessionId":"s1","text":"你好\n世界"}"#, BotChatApi.sendPayload(sessionId: "s1", text: "你好\n世界"))
        XCTAssertEqual(#"{"roomId":"","botId":"b1"}"#, BotChatApi.stopPayload(botId: "b1"))
        XCTAssertEqual(#"{"botId":"b1"}"#, BotChatApi.resumePayload(botId: "b1"))
        XCTAssertEqual(#"{"botId":"b1"}"#, BotChatApi.newPartPayload(botId: "b1"))
        XCTAssertEqual(#"{"botId":"b1","part":"p2"}"#, BotChatApi.openPartPayload(botId: "b1", part: "p2"))
        XCTAssertEqual(#"{"botId":"b1"}"#, BotChatApi.runStatsPayload(botId: "b1"))
        XCTAssertEqual("{}", BotChatApi.exchangePayload)
    }

    // MARK: - FileDownload（真实夹具 + 拼接校验）

    func testFileDownloadDecodesRealChunkAndAssembler() throws {
        let first = try XCTUnwrap(FileDownload.decode(TestFixtures.dsbotValue("file-download-chunk-small.json")))
        XCTAssertEqual(512 * 1024, FileDownload.chunkBytes)
        XCTAssertEqual(11207, first.size)
        XCTAssertEqual("report.md", first.name)
        XCTAssertEqual("text/markdown", first.mime)
        XCTAssertTrue(first.done) // 真实夹具：小文件单块即完
        let asm = FileDownload.Assembler()
        if case .done = asm.accept(first) {} else { XCTFail("单块文件应完成") }
        XCTAssertEqual("report.md", asm.name)
        XCTAssertEqual(11207, asm.offset)
        // 重复同块（offset 不前进）→ 乱序失败
        if case .fail = asm.accept(first) {} else { XCTFail("重复块应判乱序") }
    }

    func testFileDownloadAssemblerRejectsBadSequences() {
        func chunk(size: Int64, next: Int64, version: String?, bytes: Int, done: Bool = false) -> FileDownload.Chunk {
            FileDownload.Chunk(name: "f", mime: nil, bytes: Data(repeating: 1, count: bytes), size: size, version: version, nextOffset: next, done: done)
        }
        // 完成长度不符
        var asm = FileDownload.Assembler()
        if case .fail(.malformed) = asm.accept(chunk(size: 10, next: 5, version: "v", bytes: 5, done: true)) {} else { XCTFail("done 但未到总量") }
        // 首块后 version 变化
        asm = FileDownload.Assembler()
        _ = asm.accept(chunk(size: 10, next: 5, version: "v1", bytes: 5))
        if case .fail(.versionChanged) = asm.accept(chunk(size: 10, next: 10, version: "v2", bytes: 5)) {} else { XCTFail("version 变化") }
        // 超量
        asm = FileDownload.Assembler(maxBytes: 8)
        if case .fail(.tooLarge) = asm.accept(chunk(size: 10, next: 5, version: "v", bytes: 5)) {} else { XCTFail("超量") }
        // 正常完成
        asm = FileDownload.Assembler()
        _ = asm.accept(chunk(size: 10, next: 5, version: "v", bytes: 5))
        if case .done = asm.accept(chunk(size: 10, next: 10, version: "v", bytes: 5)) {} else { XCTFail("应完成") }
        XCTAssertEqual("f", asm.name)
    }

    func testFileDownloadSafeName() {
        XCTAssertEqual("file", FileDownload.safeName(nil))
        XCTAssertEqual("a.png", FileDownload.safeName("/tmp/x/../a.png"))
        XCTAssertEqual("file.hidden", FileDownload.safeName(".hidden"))
        let long = FileDownload.safeName(String(repeating: "名", count: 200) + ".md")
        XCTAssertLessThanOrEqual(long.count, 120)
        XCTAssertTrue(long.hasSuffix(".md"))
    }
}
