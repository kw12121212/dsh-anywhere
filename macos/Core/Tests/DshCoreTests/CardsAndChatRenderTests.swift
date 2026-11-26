import Foundation
import XCTest
@testable import DshCore

final class CardsAndChatRenderTests: XCTestCase {
    override func tearDown() {
        I18n.lang = .zh
        super.tearDown()
    }

    private func obj(_ json: String) throws -> JsonObject {
        try XCTUnwrap(Json.parse(json)?.objectValue)
    }

    // MARK: - Cards

    func testApprovalQuestionAndViewCards() throws {
        I18n.lang = .zh
        let approval = Cards.parse(try obj(#"{"kind":"approval","callId":"a1","title":"部署审批","detail":"发布到生产"}"#))
        XCTAssertEqual(.approval(callId: "a1", title: "部署审批", detail: "发布到生产"), approval)

        let q = Cards.parse(try obj(#"{"kind":"question","callId":"q1","question":"选哪个维护窗口？","options":[{"label":"周六","description":"低峰"},{"label":"周日"}]}"#))
        XCTAssertEqual(.question(callId: "q1", title: "选哪个维护窗口？", options: [
            .init(label: "周六", description: "低峰"),
            .init(label: "周日", description: nil),
        ]), q)

        let view = Cards.parse(try obj("""
            {"kind":"view","callId":"v1","title":"巡检报告",
             "text":"结论先行",
             "metrics":{"错误率":"0.1%","延迟":"23ms"},
             "table":{"header":["项","值"],"rows":[["a","1"],["b","2"]]},
             "formula":{"name":"预算","placeholder":"填数字"},
             "progress":{"label":"巡检","percent":70},
             "choice":{"name":"方案","options":["甲","乙"]},
             "confirm":"按此执行",
             "sections":[{"text":"嵌套段落"}]}
            """))
        if case .view(let callId, let title, let sections) = view {
            XCTAssertEqual("v1", callId)
            XCTAssertEqual("巡检报告", title)
            XCTAssertEqual(9, sections.count)
            XCTAssertEqual(.progress(label: "巡检", percent: 70), sections[5])
            XCTAssertEqual(.text("嵌套段落"), sections[8])
        } else {
            XCTFail("期待 view 卡")
        }
    }

    func testCardsLimitsAndDegradation() throws {
        I18n.lang = .zh
        XCTAssertNil(Cards.parse(try obj(#"{"kind":"future-card","callId":"x"}"#)))
        XCTAssertNil(Cards.parse(try obj(#"{"kind":"approval","title":"无编号"}"#)))

        let big = "{\"kind\":\"view\",\"callId\":\"big\",\"text\":\"" + String(repeating: "x", count: 70_000) + "\"}"
        let folded = Cards.parse(try obj(big))
        if case .readOnly(_, let body) = folded {
            XCTAssertTrue(body.contains("过大"))
        } else {
            XCTFail("期待折叠为只读")
        }

        // 17 层深嵌套
        var deep = #"{"kind":"view","callId":"deep","title":"深嵌套","text":"顶层","sections":["#
        for _ in 1...17 { deep += #"{"text":"层","sections":["# }
        deep += #"{"text":"最底层"}"#
        for _ in 1...18 { deep += "]}" }
        let parsedDeep = Cards.parse(try obj(deep))
        XCTAssertNotNil(parsedDeep)

        // 65 选项截断到 64
        let opts = (1...65).map { "{\"label\":\"选项\($0)\"}" }.joined(separator: ",")
        let q = Cards.parse(try obj("{\"kind\":\"question\",\"callId\":\"many\",\"question\":\"多\",\"options\":[\(opts)]}"))
        if case .question(_, _, let list) = q {
            XCTAssertEqual(64, list.count)
        } else {
            XCTFail("期待 question 卡")
        }
    }

    func testCardsFromRealChatPageFixtureAndPayloads() throws {
        I18n.lang = .zh
        let chatPage = DsbotModels.decodeChatPage(try TestFixtures.dsbotValue("chat-page.json"))
        let answers = chatPage.items.filter { $0.kind == "answer" }
        XCTAssertFalse(answers.isEmpty)
        for a in answers {
            if case .readOnly = Cards.fromChatItem(a) {
            } else {
                XCTFail("0.3.10 answer 条目应渲染为只读")
            }
        }

        XCTAssertEqual(#"{"callId":"a1","approved":true}"#, Cards.approvalAnswer(callId: "a1", approved: true))
        XCTAssertEqual(#"{"callId":"q1","choice":0}"#, Cards.answerQuestion(callId: "q1", choice: 0))
        XCTAssertEqual(#"{"callId":"q1"}"#, Cards.dismiss(callId: "q1"))
        XCTAssertEqual(#"{"callId":"v1","values":{"k":"v"}}"#, Cards.viewReply(callId: "v1", valuesJson: #"{"k":"v"}"#))
        XCTAssertEqual(#"{"callId":"s1","value":"a\"b"}"#, Cards.secretSet(callId: "s1", value: "a\"b"))
        XCTAssertEqual(#"{"callId":"l1","choice":1}"#, Cards.learningAnswer(callId: "l1", choice: 1))
        XCTAssertEqual(#"{"callId":"b1","result":"done"}"#, Cards.browserGiveBack(callId: "b1", result: "done"))
    }

    // MARK: - ChatRender

    func testChatRenderWithFixturesAndStreamBuffer() throws {
        let rawPage = DsbotModels.decodeChatPage(try TestFixtures.dsbotValue("chat-page.json"))
        let rendered = ChatRender.render(rawPage)
        let messages = rendered.items.compactMap { item -> ChatRender.Item.MessageData? in
            if case .message(let m) = item { return m }
            return nil
        }
        XCTAssertTrue(messages.contains(where: { $0.fromUser }))
        XCTAssertTrue(messages.contains(where: { !$0.fromUser }))
        XCTAssertFalse(rendered.exhausted)
        XCTAssertEqual(Json.parse(#"{"part":0,"beforeSeq":2497}"#), rendered.cursor)

        let nullCursor = DsbotModels.decodeChatPage(try XCTUnwrap(Json.parse(#"{"sessionId":"s","items":[],"cursor":null}"#)))
        XCTAssertTrue(ChatRender.render(nullCursor).exhausted)

        let rawFileItem = try TestFixtures.dsbotJson("chat-item-files.json")
        let filesPage = DsbotModels.decodeChatPage(.object(JsonObject([("items", .array([rawFileItem]))])))
        let renderedFiles = ChatRender.render(filesPage)
        XCTAssertTrue(renderedFiles.items.contains {
            if case .presentedFile = $0 { return true }
            return false
        })

        let buf = ChatRender.StreamBuffer()
        XCTAssertEqual("", buf.onStream(botId: "b1", sessionId: "s1", attemptId: "a1", phase: "start", text: nil))
        XCTAssertTrue(buf.inProgress)
        XCTAssertEqual("你", buf.onStream(botId: "b1", sessionId: "s1", attemptId: "a1", phase: nil, text: "你"))
        XCTAssertNil(buf.onStream(botId: "b1", sessionId: "s1", attemptId: "other", phase: nil, text: "错"))
        XCTAssertEqual("你好", buf.onStream(botId: "b1", sessionId: "s1", attemptId: "a1", phase: "end", text: "好"))
        XCTAssertFalse(buf.inProgress)
        XCTAssertEqual("你好", buf.settle())
        XCTAssertNil(buf.settle())

        let items: [ChatRender.Item] = [.message(.init(fromUser: false, markdown: "答", seq: nil))]
        if case .upgrade(let idx, let upgraded) = ChatRender.appendBotMessage(items: items, text: "答", seq: 99) {
            XCTAssertEqual(0, idx)
            XCTAssertEqual(99, upgraded.seq)
        } else {
            XCTFail("期待升级序号")
        }
    }

    // MARK: - MarkdownParse

    func testMarkdownParseBlocksInlineTableAndNegatives() {
        let md = """
            | | 之前那版 | 现在 |
            |---|---|---|
            | **21 号陆地** | 多云 20% | **上午阴有雨** |
            | `어리목입구` | [说明](https://example.com/a) | 普通 |
            """
        let blocks = MarkdownParse.parseBlocks(md)
        XCTAssertEqual(1, blocks.count)
        guard case .table(let header, let rows) = blocks[0] else {
            return XCTFail("期待 table")
        }
        XCTAssertEqual(["", "之前那版", "现在"], header.map(MarkdownParse.plain))
        XCTAssertEqual(.bold, rows[0][0][0].kind)
        XCTAssertEqual("21 号陆地", rows[0][0][0].text)
        XCTAssertEqual("https://example.com/a", rows[1][1].first(where: { $0.kind == .link })?.url)

        let listAndExtras = MarkdownParse.parseBlocks("- [日报](/home/u1/REPORT.md)\n- [x] 已完成\n![图](</tmp/a b.png>)\n```swift\nlet x = 1\n```")
        XCTAssertEqual(4, listAndExtras.count)
        if case .bullet(let parts) = listAndExtras[0] {
            XCTAssertEqual("/home/u1/REPORT.md", parts.first(where: { $0.kind == .link })?.url)
        } else {
            XCTFail("期待 bullet")
        }
        XCTAssertEqual(.task(checked: true, parts: [.init(kind: .plain, text: "已完成")]), listAndExtras[1])
        XCTAssertEqual(.image(alt: "图", src: "/tmp/a b.png"), listAndExtras[2])
        XCTAssertEqual(.code("let x = 1"), listAndExtras[3])

        let broken = MarkdownParse.parseInline("残缺 [文字]( 和 [文字] 不成链接")
        XCTAssertTrue(broken.allSatisfy { $0.kind == .plain })
    }
}
