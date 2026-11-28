import XCTest
@testable import DshCore

/// dsbot-pending-dock：从 state 汇集待处理项（0.3.10 源码核对形态）、排序、作答请求体、
/// 学习提案与逐行差异（与 `PendingDockTest.kt` 同夹具；邮件发送卡不在 macOS 范围）。
final class PendingDockTests: XCTestCase {
    private func obj(_ t: String) throws -> JsonObject {
        try XCTUnwrap(Json.parse(t)?.objectValue, "不是合法 JSON 对象")
    }

    /// 0.3.10 state 片段：私聊 bot-1（当前分段 s-bot-1）与群 room-1（成员群内会话 g-1）。
    private let stateText = """
    {
      "questions":{
        "s-bot-1":{"id":"q-1","question":"选哪个窗口？","options":[{"label":"周六","description":"低峰"},{"label":"周日"}],"multiSelect":false,"allowCustom":true},
        "s-other":{"id":"q-x","question":"别的会话","options":[]},
        "g-1":{"id":"q-g","question":"群里问","options":[{"label":"好"}],"multiSelect":true}},
      "views":{"s-bot-1":{"id":"v-1","title":"巡检表","view":{"blocks":[]}}},
      "work":{"approvals":[
        {"id":"ap-1","sessionId":"s-bot-1","botId":"bot-1","tool":"bash","reason":"运行部署脚本","answerable":true},
        {"id":"ap-2","sessionId":"s-bot-1","botId":"bot-1","tool":"edit","answerable":false},
        {"id":"ap-3","sessionId":"g-1","botId":"bot-1","roomId":"room-1","tool":"bash"},
        {"id":"ap-4","sessionId":"t-1","botId":"bot-1","tool":"write","taskId":"task-1","taskTitle":"整理周报"}]},
      "secretRequests":{"bot-1":[
        {"id":"sr-1","sessionId":"s-bot-1","name":"OPENAI_KEY","purpose":"调用模型","kind":"fill","status":"pending"},
        {"id":"sr-2","sessionId":"s-bot-1","name":"OLD","status":"canceled"}]},
      "connectorRequests":{"bot-1":[
        {"id":"cr-1","sessionId":"s-bot-1","connectorId":"gmail","name":"Gmail","purpose":"读邮件","status":"pending"},
        {"id":"cr-2","sessionId":"s-old-part","connectorId":"x","status":"pending"}]},
      "learning":{"bot-1":[{"id":"lp-1","kind":"skill","action":"create","name":"写周报","content":"a\\nb"}]},
      "browserHolds":{"bot-1":{"by":"bot","reason":"需要你登录","note":"登录网站后点完成","callId":"c-9"},"bot-2":{"by":"user","reason":"x"}}
    }
    """

    private var state: JsonObject {
        (try? obj(stateText))!
    }

    func testPrivateChatCollectsAllKindsInPriorityOrder() throws {
        let items = PendingDock.collect(state, sessionIds: ["s-bot-1"], botId: "bot-1", roomId: nil, answered: [])
        XCTAssertEqual(["lp-1", "ap-1", "ap-4", "q-1", "sr-1", "cr-1", "handoff:bot-1:c-9", "v-1"], items.map(\.id))
        XCTAssertEqual(PendingDock.Kind.learning, items[0].kind)
        XCTAssertEqual("bash", items[1].title)
        XCTAssertEqual("整理周报 · write", items[2].title) // 后台任务里的审批带任务标题
        XCTAssertEqual("选哪个窗口？", items[3].title)
        guard case .question(let q) = items[3].payload else { return XCTFail("应为提问") }
        XCTAssertEqual("s-bot-1", q.sessionId)
        XCTAssertEqual(["周六", "周日"], q.options.map(\.label))
        XCTAssertTrue(q.allowCustom)
        XCTAssertEqual("登录网站后点完成", items[6].title)
        XCTAssertEqual(0, PendingDock.expandedIndex(items))
    }

    func testAnswerableFalseOtherSessionsAndSettledAreExcluded() throws {
        let ids = PendingDock.collect(state, sessionIds: ["s-bot-1"], botId: "bot-1", roomId: nil, answered: []).map(\.id)
        XCTAssertFalse(ids.contains("ap-2")) // answerable=false
        XCTAssertFalse(ids.contains("ap-3")) // 群里的审批不在私聊
        XCTAssertFalse(ids.contains("q-x"))
        XCTAssertFalse(ids.contains("q-g"))
        XCTAssertFalse(ids.contains("sr-2")) // 已取消
        XCTAssertFalse(ids.contains("cr-2")) // 旧分段的请求
    }

    func testGroupChatCollectsRoomApprovalsAndMemberSessionQuestions() throws {
        let items = PendingDock.collect(state, sessionIds: ["room-1", "g-1"], botId: nil, roomId: "room-1", answered: [])
        XCTAssertEqual(["ap-3", "q-g"], items.map(\.id))
        guard case .question(let q) = items[1].payload else { return XCTFail("应为提问") }
        XCTAssertTrue(q.multiSelect)
    }

    func testAnsweredIdsExcludedAndEmptyState() throws {
        let items = PendingDock.collect(state, sessionIds: ["s-bot-1"], botId: "bot-1", roomId: nil, answered: ["lp-1", "ap-1"])
        XCTAssertEqual("ap-4", items.first?.id)
        XCTAssertEqual(0, PendingDock.collect(nil, sessionIds: ["x"], botId: "b", roomId: nil, answered: []).count)
        XCTAssertEqual(-1, PendingDock.expandedIndex([]))
        XCTAssertEqual(0, PendingDock.collect(try obj(#"{"questions":"bad","work":{"approvals":{}}}"#), sessionIds: ["s"], botId: "b", roomId: nil, answered: []).count)
    }

    func testHandoffNeedsCallIdAndEachHandoffHasItsOwnId() throws {
        XCTAssertNil(PendingDock.handoffOf(state, botId: "bot-2")) // 用户自己接管的不是待处理
        guard let h = PendingDock.handoffOf(state, botId: "bot-1") else {
            return XCTFail("应为交接")
        }
        XCTAssertEqual("登录网站后点完成", h.note)
        XCTAssertEqual("c-9", h.callId)
        // 第一次交接作答后（id 记入 answered），同一聊天的第二次交接（新 callId）照样出现
        let second = try obj(#"{"browserHolds":{"bot-1":{"by":"bot","note":"再登录一次","callId":"c-10"}}}"#)
        let items = PendingDock.collect(second, sessionIds: ["s"], botId: "bot-1", roomId: nil, answered: ["handoff:bot-1:c-9"])
        XCTAssertEqual(["handoff:bot-1:c-10"], items.map(\.id))
    }

    func testRequestBodiesMatchUpstream() {
        XCTAssertEqual(#"{"id":"ap-1","choice":"allow"}"#, PendingDock.Requests.approval("ap-1", true))
        XCTAssertEqual(#"{"id":"ap-1","choice":"reject"}"#, PendingDock.Requests.approval("ap-1", false))
        XCTAssertEqual(
            #"{"sessionId":"s","questionId":"q","answer":{"selected":["周六"]}}"#,
            PendingDock.Requests.answerQuestion(sessionId: "s", questionId: "q", selected: ["周六"])
        )
        XCTAssertEqual(
            #"{"sessionId":"s","questionId":"q","answer":{"selected":[],"custom":"自填"}}"#,
            PendingDock.Requests.answerQuestion(sessionId: "s", questionId: "q", selected: [], custom: " 自填 ")
        )
        XCTAssertEqual(#"{"sessionId":"s"}"#, PendingDock.Requests.dismissQuestion("s"))
        XCTAssertEqual(#"{"requestId":"r","value":"v"}"#, PendingDock.Requests.secretSet(requestId: "r", value: "v"))
        XCTAssertEqual(#"{"requestId":"r"}"#, PendingDock.Requests.secretCancel("r"))
        XCTAssertEqual(#"{"id":"lp","approve":false}"#, PendingDock.Requests.learning("lp", false))
        XCTAssertEqual(#"{"requestId":"cr"}"#, PendingDock.Requests.connectorCancel("cr"))
        XCTAssertEqual(#"{"botId":"b","callId":"c","outcome":"skip"}"#, PendingDock.Requests.giveBack(botId: "b", callId: "c", done: false))
        XCTAssertEqual(#"{"botId":"b","callId":"c","outcome":"done"}"#, PendingDock.Requests.giveBack(botId: "b", callId: "c", done: true))
        XCTAssertEqual(#"{"requestId":"r"}"#, PendingDock.Requests.secretAllow("r"))
        XCTAssertEqual(#"{"sessionId":"s"}"#, PendingDock.Requests.dismissView("s"))
        XCTAssertEqual(#"{"sessionId":"s","cardId":"c"}"#, PendingDock.Requests.btwDelete(sessionId: "s", cardId: "c"))
    }

    func testLearningLinesSkillAndAgents() throws {
        let create = try XCTUnwrap(PendingDock.learningOf(state, "bot-1").first)
        XCTAssertEqual([LineDiff.Op.add, .add], create.lines().map(\.op))
        XCTAssertEqual(["a", "b"], create.lines().map(\.text))
        let update = LearningProposal(
            id: "u", kind: "skill", action: "update", name: "n", description: nil, why: nil,
            content: "a\nB\nc", previousContent: "a\nb\nc\n", path: nil, edits: []
        )
        // previous 结尾换行不产生多余删除行
        XCTAssertEqual([LineDiff.Op.same, .del, .add, .same], update.lines().map(\.op))
        XCTAssertEqual(["a", "b", "B", "c"], update.lines().map(\.text))
        let agentsJson = #"{"id":"g","kind":"agents","path":"/w/AGENTS.md","edits":[{"op":"replace","old":"旧规则","text":"新规则\n补充","why":"更清楚"},{"op":"add","text":"x"}]}"#
        let agents = try XCTUnwrap(LearningProposal.decode(.object(try obj(agentsJson))))
        XCTAssertEqual("更清楚", agents.edits[0].why)
        XCTAssertEqual([LineDiff.Op.del, .add, .add, .add], agents.lines().map(\.op))
        XCTAssertEqual(["旧规则", "新规则", "补充", "x"], agents.lines().map(\.text))
    }

    func testLineDiffCasesAndFold() {
        XCTAssertTrue(LineDiff.diff("x\ny", "x\ny").allSatisfy { $0.op == .same })
        XCTAssertEqual([LineDiff.Op.add], LineDiff.diff("", "n").map(\.op))
        XCTAssertEqual(["n"], LineDiff.diff("", "n").map(\.text))
        XCTAssertEqual([LineDiff.Op.del], LineDiff.diff("o", "").map(\.op))
        let d = LineDiff.diff("1\n2\n3\n4", "1\n3\n4\n5")
        XCTAssertEqual([LineDiff.Op.same, .del, .same, .same, .add], d.map(\.op))
        XCTAssertEqual(["1", "2", "3", "4", "5"], d.map(\.text))
        XCTAssertEqual(["a", "b"], LineDiff.split("a\r\nb\n"))
        let big = (1...500).map(String.init).joined(separator: "\n")
        XCTAssertEqual(1000, LineDiff.diff(big, big).count) // 超规模降级为全删全增
        let lines = (1...12).map { LineDiff.Line(op: .add, text: "l\($0)") }
        let folded = LineDiff.fold(lines)
        XCTAssertEqual(8, folded.shown.count)
        XCTAssertEqual(4, folded.hidden)
        XCTAssertEqual(0, LineDiff.fold(Array(lines.prefix(8))).hidden)
    }

    func testDecodeRejectsMalformed() throws {
        XCTAssertNil(LearningProposal.decode(.object(try obj(#"{"kind":"skill"}"#))))
        XCTAssertNil(ConnectorRequest.decode(.object(try obj(#"{"id":""}"#))))
        XCTAssertNil(LearningProposal.decode(Json.parse("[1]")))
        XCTAssertEqual(1, LearningProposal.decode(.object(try obj(#"{"id":"p","edits":[1,{"op":"add","text":"t"}]}"#)))?.edits.count)
    }
}
