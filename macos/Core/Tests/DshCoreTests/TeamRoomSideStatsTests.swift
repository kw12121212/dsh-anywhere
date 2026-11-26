import Foundation
import XCTest
@testable import DshCore

final class TeamRoomSideStatsTests: XCTestCase {
    // MARK: - TeamHome & TeamSections & TeamPreview

    func testTeamHomeWithRealStateAndSorting() throws {
        I18n.lang = .zh
        let state = try XCTUnwrap(DsbotModels.decodeState(try TestFixtures.dsbotValue("state-full.json")))
        let team = TeamHome.assemble(state)
        XCTAssertFalse(team.sections.isEmpty)
        let entries = team.sections.flatMap(\.entries)
        XCTAssertFalse(entries.isEmpty)
        XCTAssertTrue(entries.allSatisfy { !$0.name.isEmpty })

        let customJson = """
            {"revision":1,"mainBotIds":["m"],"bots":[
              {"id":"m","name":"Main","sessionId":"sm","lastAt":10,"sectionId":"secB"},
              {"id":"b1","name":"A","sessionId":"s1","sectionId":"secB","lastAt":100},
              {"id":"b2","name":"B","sessionId":"s2","sectionId":"gone","pinned":true,"lastAt":20},
              {"id":"b3","name":"C","sessionId":"s3","lastAt":300,"preview":"[Message from Alice] 你好"}],
             "rooms":[{"id":"r1","name":"R","members":["b1"],"sessionId":"r1","sectionId":"secB"}],
             "sections":[{"id":"secB","name":"后端","collapsed":true},{"id":"secE","name":"空"}],
             "work":{"approvals":[{"sessionId":"s1","botId":"b1","title":"部署审批"}]},
             "questions":{"s1":{"question":"选？"}},
             "views":{"s1":{"title":"卡"}}}
            """
        let customState = try XCTUnwrap(DsbotModels.decodeState(try XCTUnwrap(Json.parse(customJson))))
        let customTeam = TeamHome.assemble(customState)
        XCTAssertEqual([nil, "secB", "secE"], customTeam.sections.map(\.id))
        XCTAssertEqual(["Main", "B", "C"], customTeam.sections[0].entries.map(\.name))
        XCTAssertEqual([.approval, .question, .view], customTeam.sections[1].entries.first(where: { $0.id == "b1" })?.pendingKinds)
        XCTAssertEqual("你好", customTeam.sections[0].entries.last?.lastSummary)

        let searched = TeamHome.search(customTeam, query: "R")
        XCTAssertEqual(["secB"], searched.sections.map(\.id))
        XCTAssertEqual(["R"], searched.sections[0].entries.map(\.name))

        XCTAssertEqual("工作 组", TeamSections.clean(#" 「工作   组」 "#))
        XCTAssertEqual(#"{"id":"e1","sectionId":null}"#, TeamSections.Requests.moveEntry(entryId: "e1", sectionId: nil))
        XCTAssertEqual("{}", TeamSections.Requests.create("  "))
        XCTAssertEqual(#"{"id":"s1","name":"后端"}"#, TeamSections.Requests.rename(id: "s1", name: " 「后端」 "))
        XCTAssertEqual(#"{"id":"s1","collapsed":true}"#, TeamSections.Requests.collapse(id: "s1", collapsed: true))
        XCTAssertEqual(#"{"ids":["s2","s1"]}"#, TeamSections.Requests.order(["s2", "s1"]))
        XCTAssertEqual(#"{"id":"s1"}"#, TeamSections.Requests.delete(id: "s1"))
        XCTAssertEqual("请输入分组名称", TeamSections.nameProblem(sections: [], name: " "))
        let existingSec = [TeamSections.Section(id: "sec1", name: "后端", collapsed: false, color: nil)]
        XCTAssertNotNil(TeamSections.nameProblem(sections: existingSec, name: "后端"))
        XCTAssertNil(TeamSections.nameProblem(sections: existingSec, name: "后端", selfId: "sec1"))
        XCTAssertEqual(["b", "a"], TeamSections.move(["a", "b"], from: 0, to: 1))
        XCTAssertTrue(TeamSections.isPermutation(current: ["a", "b"], next: ["b", "a"]))
        XCTAssertFalse(TeamSections.isPermutation(current: ["a", "b"], next: ["a", "a"]))
        XCTAssertEqual(2, TeamSections.targetIndex(from: 0, offset: 95.0, rowHeight: 44.0, count: 4))
    }

    func testWorkPhraseI18nAndTeamPreview() throws {
        I18n.lang = .zh
        defer {
            I18n.source = nil
            I18n.lang = .zh
        }

        // WorkPhrase 优先级与中英文对照
        let swFolder = try XCTUnwrap(Json.parse(#"{"switching":true,"folder":true}"#)?.objectValue)
        XCTAssertEqual("切换工作文件夹", WorkPhrase.phrase(swFolder))
        let swFresh = try XCTUnwrap(Json.parse(#"{"switching":true}"#)?.objectValue)
        XCTAssertEqual("转入新对话", WorkPhrase.phrase(swFresh))
        let saidObj = try XCTUnwrap(Json.parse(#"{"said":"正在整理数据……。","tool":"shell"}"#)?.objectValue)
        XCTAssertEqual("正在整理数据", WorkPhrase.phrase(saidObj))
        XCTAssertEqual("思考中", WorkPhrase.phrase(nil))
        XCTAssertEqual("询问其他 Bot", WorkPhrase.toolLabel("message_bot"))
        XCTAssertEqual("运行命令", WorkPhrase.toolLabel("bash_exec"))
        XCTAssertNil(WorkPhrase.toolLabel("ask_user"))
        let toolWithTarget = try XCTUnwrap(Json.parse(#"{"tool":"web_search","target":"Swift 6"}"#)?.objectValue)
        XCTAssertEqual("联网搜索 · Swift 6", WorkPhrase.phrase(toolWithTarget))
        let genericFallback = try XCTUnwrap(Json.parse(#"{"tool":"ask_user"}"#)?.objectValue)
        XCTAssertEqual("处理中", WorkPhrase.phrase(genericFallback))

        I18n.source = { .en }
        XCTAssertEqual("Thinking about it", WorkPhrase.phrase(nil))
        XCTAssertEqual("Running shell commands", WorkPhrase.toolLabel("bash_exec"))
        I18n.source = nil
        I18n.lang = .zh

        // I18n.clip UTF-16 代理对边界（"a😀bc" 为 5 个 UTF-16 单元）
        XCTAssertEqual("a…", I18n.clip("a😀bc", maxUTF16: 3))
        XCTAssertEqual("a😀…", I18n.clip("a😀bc", maxUTF16: 4))

        // TeamPreview 优先级、过滤与多记录清洗
        let previewStateJson = """
            {"revision":1,"bots":[
              {"id":"b1","name":"B1","sessionId":"s1","role":"研究","preview":"[Message from X] 摘要"},
              {"id":"b2","name":"B2","sessionId":"s2","role":"顾问"}],
             "rooms":[{"id":"r1","name":"群一","members":["b1"],"sessionId":"r1"}],
             "work":{
               "approvals":[{"botId":"b1","roomId":"r1","answerable":true},{"botId":"b2","answerable":false}],
               "bots":{"b1":{"tool":"read_file","target":"a.txt"}}}}
            """
        let pState = try XCTUnwrap(DsbotModels.decodeState(try XCTUnwrap(Json.parse(previewStateJson))))
        // b1 的审批带 roomId，不作为私聊审批，降级到 work.bots
        XCTAssertEqual(TeamPreview.Preview(kind: .work, text: "打开文件 · a.txt"), TeamPreview.ofBot(pState.bots[0], state: pState))
        // b2 的审批 answerable=false 被忽略，降级到 role
        XCTAssertEqual(TeamPreview.Preview(kind: .role, text: "顾问"), TeamPreview.ofBot(pState.bots[1], state: pState))
        // r1 命中 roomId == r1 的审批
        XCTAssertEqual(TeamPreview.Preview(kind: .approval, text: "等你审批"), TeamPreview.ofRoom(pState.rooms[0], state: pState))

        let multiClean = TeamPreview.clean("张三：[Answer to question card] 选哪天 Answer: (skipped) [Browser handoff] 登录 Answer: done")
        XCTAssertEqual("张三：已回答「选哪天」：已跳过；浏览器交接：登录（已完成）", multiClean)
    }

    // MARK: - RoomChat

    func testRoomChatWithRealFixtureAndRoundEvents() throws {
        I18n.lang = .zh
        let page = RoomChat.decodeRoomPage(try TestFixtures.dsbotValue("room-chat-rounds.json"))
        XCTAssertTrue(page.running.isEmpty)
        XCTAssertTrue(page.speaking.isEmpty)
        XCTAssertEqual(57, page.items.count)

        let ids = Set(page.items.compactMap { RoomChat.speakerOf($0)?.botId })
        let roster = Dictionary(uniqueKeysWithValues: ids.map { ($0, RoomChat.Speaker(botId: $0, name: "名册-\($0)", color: "purple")) })
        let rendered = RoomChat.renderRoom(page, roster: roster)
        let lines = rendered.compactMap { item -> String? in
            if case .systemLine(let t) = item { return t }
            return nil
        }
        XCTAssertEqual(16, lines.count)
        XCTAssertTrue(lines.allSatisfy { $0.hasSuffix(" 开始做了") })
        let bots = rendered.compactMap { item -> ChatRender.Item.MessageData? in
            if case .message(let m) = item, !m.fromUser { return m }
            return nil
        }
        XCTAssertEqual(35, bots.count)
        XCTAssertTrue(bots.allSatisfy { $0.speakerColor == "purple" && ($0.speakerName?.hasPrefix("名册-") == true) })

        func eventLine(_ ev: String, extra: String = "") -> String? {
            let raw = Json.parse("{\"kind\":\"event\",\"event\":\"\(ev)\",\"botId\":\"b1\",\"name\":\"甲\"\(extra)}")
            return RoomChat.roundEventLine(.init(kind: "event", text: nil, time: nil, seq: nil, turn: nil, raw: raw))
        }

        XCTAssertEqual("甲 开始做了", eventLine("working"))
        XCTAssertEqual("甲 停下来等你批准", eventLine("approval"))
        XCTAssertEqual("甲 做完了", eventLine("done"))
        XCTAssertEqual("你停下了 甲 的工作", eventLine("stopped"))
        XCTAssertEqual("甲 没有及时回应", eventLine("missed"))
        XCTAssertEqual("甲 查看了自己的对话", eventLine("lookup"))
        XCTAssertEqual("甲 在自己的对话里查找「周报」", eventLine("lookup", extra: #","text":"周报""#))
        XCTAssertEqual("甲 的工作被重启打断，已经接着做", eventLine("resumed"))
        XCTAssertEqual("甲 的工作又被重启打断，已停下", eventLine("cut"))
        XCTAssertEqual("甲 停下了：账户额度已用尽。换个模型，或让它继续。", eventLine("failed", extra: #","text":"429","code":"QUOTA""#))
        XCTAssertEqual("甲 停下了：未知错误。换个模型，或让它继续。", eventLine("failed"))
        XCTAssertNil(eventLine("paused"))

        I18n.lang = .en
        defer { I18n.lang = .zh }
        XCTAssertEqual("甲 finished its work", eventLine("done"))
        XCTAssertEqual("甲 stopped: The account is out of quota. Switch its model or ask it to go on.", eventLine("failed", extra: #","code":"QUOTA""#))
    }

    // MARK: - SideChat & BotStatsText

    func testSideChatAndBotStatsText() throws {
        I18n.lang = .zh
        XCTAssertEqual(#"{"sessionId":"s1"}"#, SideChat.newSide(sessionId: "s1"))
        XCTAssertEqual(#"{"sessionId":"s1","text":"问"}"#, SideChat.ask(sessionId: "s1", text: "问"))
        XCTAssertEqual("[{\"role\":\"assistant\",\"text\":\"答一\"},{\"role\":\"user\",\"text\":\"问二\"}]", SideChat.snapshotOf([
            (true, "问一"), (false, "答一"), (true, "问二"),
        ], max: 2))

        let stateObj = try XCTUnwrap(Json.parse(#"{"sideChats":{"b1":[{"id":"side-a","entries":[{"kind":"user","text":"旁路"},{"kind":"bot","text":"回答"}]}]}}"#)?.objectValue)
        let sessions = SideChat.decodeSideSessions(stateObj)
        XCTAssertEqual(1, sessions.count)
        XCTAssertEqual("side-a", sessions[0].id)

        let statsVal = try XCTUnwrap(Json.parse("""
            {"sessionStats":{"turns":62,"steps":364,"llmMs":3885010,"toolMs":477214},
             "tokenUsage":{"uncachedInputTokens":6237275,"outputTokens":194239,"cacheReadTokens":65204352},
             "contextPressure":{"pressureTokens":371245,"contextWindow":1000000}}
            """))
        XCTAssertEqual([
            "对话 62 轮 · 364 步",
            "耗时 模型 1.1 小时 · 工具 8.0 分钟",
            "Token：输入 6.24M · 输出 194K · 缓存读取 65.20M",
            "上下文 371K / 1.00M（37%）",
        ].joined(separator: "\n"), BotStatsText.runStats(statsVal))

        XCTAssertEqual("暂无 Bot 间记录", BotStatsText.exchanges(.array([])))
        XCTAssertEqual("59 秒", BotStatsText.duration(59_000))
        XCTAssertEqual("1.0 分钟", BotStatsText.duration(60_000))
        XCTAssertEqual("1.5 小时", BotStatsText.duration(5_400_000))
        XCTAssertEqual("19K", BotStatsText.count(19_400))
        XCTAssertEqual("1.00M", BotStatsText.count(1_000_000))
    }
}
