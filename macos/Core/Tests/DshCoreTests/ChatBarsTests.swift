import XCTest
@testable import DshCore

/// ChatBars（dsbot-chat-bars-0310，与 `ChatBarsTest.kt` 同夹具）：样本为 0.3.10 源码核对形态。
final class ChatBarsTests: XCTestCase {
    private func obj(_ text: String) throws -> JsonObject {
        try XCTUnwrap(Json.parse(text)?.objectValue, "不是合法 JSON 对象")
    }

    private func sample() throws -> JsonObject {
        try obj(try TestFixtures.loadText(relativeToTestdata: "dsbot-source/chat-bars-0310.json"))
    }

    /// 真实 0.3.10 夹具（dsbot/，脱敏副本）：响应信封 {ok,value} 时取 value。
    private func real(_ name: String) throws -> JsonObject {
        let root = try obj(try TestFixtures.dsbotText(name))
        return root.object("value") ?? root
    }

    func testTasksPrivateOnlyActiveSortedLikeWeb() throws {
        // task-3 已结束、task-4 带 roomId（群任务）不在私聊；等你审批排最前，其余按 updatedAt 倒序
        let tasks = ChatBars.tasks(try sample(), "bot-a")
        XCTAssertEqual(["task-2", "task-1"], tasks.map(\.id))
        XCTAssertEqual("waiting_approval", tasks[0].status)
        XCTAssertEqual("整理周报", tasks[1].title)
        XCTAssertTrue(tasks[1].running)
    }

    func testTasksSortByUpdatedDescending() throws {
        let st = try obj(#"""
        {"bots":[{"id":"b","tasks":[
          {"id":"old","status":"running","updatedAt":10},
          {"id":"new","status":"running","updatedAt":30},
          {"id":"mid","status":"waiting_work","startedAt":20},
          {"id":"appr","status":"waiting_approval","updatedAt":1}]}]}
        """#)
        XCTAssertEqual(["appr", "new", "mid", "old"], ChatBars.tasks(st, "b").map(\.id))
    }

    func testRealBaselineStateDerivesNothing() throws {
        // 真实 0.3.10 全量 state（computerLive/work 为空、无 tasks/blocked/goal）：所有派生为空，页面不变
        let full = try real("state-full.json")
        let bots = (full.array("bots") ?? []).compactMap { $0.objectValue?.string("id") }
        let rooms = (full.array("rooms") ?? []).compactMap(\.objectValue)
        XCTAssertFalse(bots.isEmpty)
        for id in bots {
            XCTAssertEqual(0, ChatBars.tasks(full, id).count)
            XCTAssertEqual(0, ChatBars.computerAsks(full, botId: id).count)
            XCTAssertFalse(ChatBars.computerBusy(full, id))
            XCTAssertNil(ChatBars.repair(full, id))
            XCTAssertNil(ChatBars.botWork(full, id))
        }
        for r in rooms {
            let rid = try XCTUnwrap(r.string("id"))
            XCTAssertNil(ChatBars.goal(r))
            XCTAssertEqual(0, ChatBars.computerAsks(full, roomId: rid).count)
            XCTAssertEqual(0, ChatBars.roomWork(full, rid).count)
        }
    }

    func testLiveFieldsReadableAfterMergeDelta() throws {
        // 设计依赖：增量 unchanged+live 应答经 mergeDelta 合并后，computerLive/work 仍在合并对象顶层
        let state = try XCTUnwrap(DsbotModels.decodeState(.object(try real("state-full.json"))))
        let delta = try XCTUnwrap(DsbotModels.decodeStateDelta(.object(try real("state-unchanged-live.json"))))
        let merged = DsbotModels.mergeDelta(state: state, delta: delta).raw
        XCTAssertNotNil(merged.object("computerLive"))
        XCTAssertNotNil(merged.object("work"))
        // 用合成 live 覆盖验证读取路径：work.bots / computerLive.asks 合并后可读
        let liveDelta = try XCTUnwrap(DsbotModels.decodeStateDelta(.object(try obj(#"""
        {"revision":1,"unchanged":true,"liveKey":"k2",
         "work":{"bots":{"x":{"tool":"bash"}},"rooms":{}},
         "computerLive":{"bots":{"x":{"state":"bot"}},"asks":{"a":{"id":"a","botId":"x","appName":"Notes","at":1}}}}
        """#))))
        let merged2 = DsbotModels.mergeDelta(state: state, delta: liveDelta).raw
        XCTAssertEqual("运行命令", WorkPhrase.phrase(ChatBars.botWork(merged2, "x")))
        XCTAssertTrue(ChatBars.computerBusy(merged2, "x"))
        XCTAssertEqual(["a"], ChatBars.computerAsks(merged2, botId: "x").map(\.id))
    }

    func testTasksAbsentOrMalformedAreEmpty() throws {
        let s = try sample()
        XCTAssertEqual(0, ChatBars.tasks(s, "bot-c").count)
        XCTAssertEqual(0, ChatBars.tasks(s, "nobody").count)
        XCTAssertEqual(0, ChatBars.tasks(nil, "bot-a").count)
        let bad = try obj(#"{"bots":[{"id":"x","tasks":"oops"},{"id":"y","tasks":[1,{"title":"no id","status":"running"},{"id":"z","status":7}]}]}"#)
        XCTAssertEqual(0, ChatBars.tasks(bad, "x").count)
        XCTAssertEqual(0, ChatBars.tasks(bad, "y").count)
    }

    func testTasksCapAndTitleFallbackAndClip() throws {
        let many = (1...30).map { #"{"id":"t\#($0)","status":"running"}"# }.joined(separator: ",")
        let long = String(repeating: "长", count: 200)
        let st = try obj(#"{"bots":[{"id":"b","tasks":[\#(many)]},{"id":"c","tasks":[{"id":"t","title":"\#(long)","status":"running"}]}]}"#)
        let tasks = ChatBars.tasks(st, "b")
        XCTAssertEqual(ChatBars.listMax, tasks.count)
        XCTAssertEqual("t1", tasks[0].title) // 无标题回落 id
        let clipped = ChatBars.tasks(st, "c")[0].title
        XCTAssertEqual(ChatBars.titleMax, clipped.utf16.count)
        XCTAssertTrue(clipped.hasSuffix("…"))
    }

    func testComputerAsksPrivateFilterAndOrder() throws {
        let asks = ChatBars.computerAsks(try sample(), botId: "bot-a")
        XCTAssertEqual(["ask-1", "ask-2"], asks.map(\.id)) // 按 at 升序，群请求不在私聊
        XCTAssertEqual("Notes", asks[0].appName)
        XCTAssertNil(asks[0].roomId)
        XCTAssertEqual(0, ChatBars.computerAsks(try sample(), botId: "bot-b").count)
    }

    func testComputerAsksRoomFilter() throws {
        let asks = ChatBars.computerAsks(try sample(), roomId: "room-1")
        XCTAssertEqual(["ask-3"], asks.map(\.id))
        XCTAssertEqual("bot-b", asks[0].botId)
        XCTAssertEqual(0, ChatBars.computerAsks(try sample(), roomId: "room-2").count)
    }

    func testComputerAbsentOrMalformed() throws {
        XCTAssertEqual(0, ChatBars.computerAsks(try obj(#"{"bots":[]}"#), botId: "a").count)
        XCTAssertEqual(0, ChatBars.computerAsks(try obj(#"{"computerLive":{"asks":[1,2]}}"#), botId: "a").count)
        XCTAssertEqual(
            0,
            ChatBars.computerAsks(try obj(#"{"computerLive":{"asks":{"x":{"botId":"a"},"y":"bad"}}}"#), botId: "a").count
        )
        XCTAssertFalse(ChatBars.computerBusy(try obj(#"{"computerLive":{"bots":{"a":"bad"}}}"#), "a"))
    }

    func testComputerBusyStates() throws {
        let s = try sample()
        XCTAssertTrue(ChatBars.computerBusy(s, "bot-a")) // waiting
        XCTAssertFalse(ChatBars.computerBusy(s, "bot-c")) // idle
        XCTAssertFalse(ChatBars.computerBusy(s, "bot-b")) // 无条目
        XCTAssertTrue(ChatBars.computerBusy(try obj(#"{"computerLive":{"bots":{"a":{"state":"bot"}}}}"#), "a"))
    }

    func testRepairMissingKeyUsesBotNameAndNoTopUp() throws {
        let r = try XCTUnwrap(ChatBars.repair(try sample(), "bot-a"))
        XCTAssertEqual("MISSING_CREDENTIAL", r.code)
        XCTAssertEqual("合成甲 使用的模型还没有 API key", r.title)
        XCTAssertTrue(r.guidance.contains("模型与服务商")) // dsbot-models-providers：改为直达 App 内页面
        XCTAssertTrue(r.fixInProviders)
        XCTAssertFalse(r.topUp)
    }

    func testRepairQuotaOfficialShowsTopUpWithStatus() throws {
        let r = try XCTUnwrap(ChatBars.repair(try sample(), "bot-b"))
        XCTAssertEqual("DeepSeek 余额或额度不足（402）", r.title)
        XCTAssertTrue(r.topUp)
        XCTAssertEqual("https://platform.deepseek.com/top_up", ChatBars.topUpURL)
    }

    func testRepairTopUpOnlyForOfficialQuotaOrAccountQuota() {
        XCTAssertFalse(ChatBars.repairOf("QUOTA", provider: "openai", status: nil, botName: "B").topUp)
        XCTAssertTrue(ChatBars.repairOf("ACCOUNT_QUOTA", provider: "deepseek-account", status: nil, botName: "B").topUp)
        XCTAssertFalse(ChatBars.repairOf("AUTH", provider: "deepseek-official", status: 401, botName: "B").topUp)
    }

    func testRepairDirectsOnlyKeyCodesToProviders() {
        for c in ["MISSING_CREDENTIAL", "INVALID_CREDENTIAL", "AUTH"] {
            XCTAssertTrue(ChatBars.repairOf(c, provider: "openai", status: nil, botName: "B").fixInProviders, c)
        }
        for c in ["QUOTA", "ACCOUNT_QUOTA", "ACCOUNT_SIGNED_OUT", "LOCAL_AGENT_MISSING", "WEIRD"] {
            XCTAssertFalse(ChatBars.repairOf(c, provider: "openai", status: nil, botName: "B").fixInProviders, c)
        }
        XCTAssertTrue(ChatBars.repairOf("ACCOUNT_SIGNED_OUT", provider: "deepseek-account", status: nil, botName: "B").guidance.contains("网页端"))
    }

    func testRepairCodesHaveReadableTexts() {
        XCTAssertEqual("openai 未接受当前 API key（401）", ChatBars.repairOf("AUTH", provider: "openai", status: 401, botName: "B").title)
        XCTAssertEqual("模型服务 未接受当前 API key", ChatBars.repairOf("AUTH", provider: nil, status: nil, botName: "B").title)
        XCTAssertEqual("API key 格式不对", ChatBars.repairOf("INVALID_CREDENTIAL", provider: nil, status: nil, botName: "B").title)
        for c in ["ACCOUNT_SIGN_IN_REQUIRED", "ACCOUNT_SIGNED_OUT", "ACCOUNT_TOKEN_INVALID"] {
            XCTAssertEqual("DeepSeek 账号登录已失效", ChatBars.repairOf(c, provider: "deepseek-account", status: nil, botName: "B").title)
        }
        XCTAssertEqual("本地 Agent 异常退出", ChatBars.repairOf("LOCAL_AGENT_EXIT", provider: nil, status: nil, botName: "B").title)
        XCTAssertEqual("找不到本地 Agent", ChatBars.repairOf("LOCAL_AGENT_MISSING", provider: nil, status: nil, botName: "B").title)
        XCTAssertEqual("B 上一轮运行失败（WEIRD）", ChatBars.repairOf("WEIRD", provider: nil, status: nil, botName: "B").title)
        XCTAssertEqual("B's model has no API key yet", ChatBars.repairOf("MISSING_CREDENTIAL", provider: nil, status: nil, botName: "B", lang: .en).title)
        XCTAssertEqual("DeepSeek is out of balance or quota (402)", ChatBars.repairOf("QUOTA", provider: "deepseek-official", status: 402, botName: "B", lang: .en).title)
    }

    func testRepairAbsentOrMalformedIsNull() throws {
        XCTAssertNil(ChatBars.repair(try sample(), "bot-c"))
        XCTAssertNil(ChatBars.repair(try obj(#"{"bots":[{"id":"a","blocked":"x"}]}"#), "a"))
        XCTAssertNil(ChatBars.repair(try obj(#"{"bots":[{"id":"a","blocked":{"code":""}}]}"#), "a"))
        XCTAssertNil(ChatBars.repair(try obj(#"{"bots":"oops"}"#), "a"))
    }

    func testGoalActiveDecodesStagesAndProgress() throws {
        let room = try XCTUnwrap((try sample().array("rooms") ?? []).first?.objectValue)
        let g = try XCTUnwrap(ChatBars.goal(room))
        XCTAssertEqual("发布新版本", g.title)
        XCTAssertEqual("active", g.status)
        XCTAssertEqual(4, g.stages.count)
        XCTAssertEqual(1, g.current)
        XCTAssertEqual("实现", g.stages[g.current].title)
        XCTAssertEqual(1, g.passed)
        XCTAssertEqual("进行中", ChatBars.goalStatusLabel(g.status))
    }

    func testGoalHiddenWhenCompleteOrAbsent() throws {
        let room2 = try XCTUnwrap((try sample().array("rooms") ?? []).dropFirst().first?.objectValue)
        XCTAssertNil(ChatBars.goal(room2))
        XCTAssertNil(ChatBars.goal(try obj(#"{"id":"r"}"#)))
        XCTAssertNil(ChatBars.goal(try obj(#"{"goal":{"status":"stopped","stages":[]}}"#)))
        XCTAssertNil(ChatBars.goal(try obj(#"{"goal":{"title":"no status"}}"#)))
        XCTAssertNil(ChatBars.goal(nil))
    }

    func testGoalDraftingWithoutStagesAndBadCurrent() throws {
        let d = try XCTUnwrap(ChatBars.goal(try obj(#"{"goal":{"status":"drafting","title":"草稿","stages":[],"current":-1}}"#)))
        XCTAssertEqual(0, d.stages.count)
        XCTAssertEqual(-1, d.current)
        let bad = try XCTUnwrap(ChatBars.goal(try obj(#"{"goal":{"status":"paused","title":"t","stages":[{"title":"a"},5],"current":9}}"#)))
        XCTAssertEqual(1, bad.stages.count)
        XCTAssertEqual("pending", bad.stages[0].status)
        XCTAssertEqual(-1, bad.current)
        let many = (1...40).map { #"{"title":"s\#($0)","status":"pending"}"# }.joined(separator: ",")
        let capped = try XCTUnwrap(ChatBars.goal(try obj(#"{"goal":{"status":"active","stages":[\#(many)],"current":0}}"#)))
        XCTAssertEqual(ChatBars.stagesMax, capped.stages.count)
    }

    func testGoalAllStagesPassedHasNoCurrent() throws {
        let g = try XCTUnwrap(ChatBars.goal(try obj(#"{"goal":{"status":"active","title":"t","current":2,"stages":[{"title":"a","status":"passed"},{"title":"b","status":"passed"}]}}"#)))
        XCTAssertEqual(-1, g.current)
        XCTAssertEqual(2, g.passed)
        // 超大 current 不回绕成合法下标
        let huge = try XCTUnwrap(ChatBars.goal(try obj(#"{"goal":{"status":"active","current":4294967296,"stages":[{"title":"a"}]}}"#)))
        XCTAssertEqual(-1, huge.current)
    }

    func testAsksWithoutAtSortLast() throws {
        let st = try obj(#"{"computerLive":{"asks":{"n":{"id":"n","botId":"b","appName":"A"},"t":{"id":"t","botId":"b","appName":"B","at":5}}}}"#)
        XCTAssertEqual(["t", "n"], ChatBars.computerAsks(st, botId: "b").map(\.id))
    }

    func testRepairGuidanceVariants() {
        XCTAssertTrue(ChatBars.repairOf("QUOTA", provider: "openai", status: nil, botName: "B").guidance.contains("改用其他模型"))
        XCTAssertEqual("充值后点「重试」。", ChatBars.repairOf("ACCOUNT_QUOTA", provider: nil, status: nil, botName: "B").guidance)
        XCTAssertTrue(ChatBars.repairOf("MISSING_CREDENTIAL", provider: nil, status: nil, botName: "B").guidance.contains("自动消失"))
        XCTAssertTrue(ChatBars.repairOf("ACCOUNT_SIGNED_OUT", provider: nil, status: nil, botName: "B").guidance.contains("重新登录"))
    }

    func testActionErrorsAreReadable() {
        XCTAssertEqual("操作失败：任务、请求或 Bot 已不存在", ChatBars.actionError("bot/not-found"))
        XCTAssertEqual("操作失败：网络不可用，请稍后重试", ChatBars.actionError("network"))
        XCTAssertEqual("操作失败：网络不可用，请稍后重试", ChatBars.actionError(nil))
        XCTAssertTrue(ChatBars.actionError("bot/read-only").contains("只读"))
        XCTAssertTrue(ChatBars.actionError("bot/stale").contains("刷新"))
        XCTAssertEqual("操作失败（bot/weird）", ChatBars.actionError("bot/weird"))
        XCTAssertEqual("Failed (x)", ChatBars.actionError("x", lang: .en))
    }

    func testClipKeepsSurrogatePairs() {
        let s = String(repeating: "a", count: 8) + "😀" + String(repeating: "b", count: 10)
        let c = ChatBars.clip(s, 10) // 第 9、10 个单元是 emoji 的代理对：不切开，整体舍去
        XCTAssertEqual(String(repeating: "a", count: 8) + "…", c)
        XCTAssertEqual("abc", ChatBars.clip("abc", 10))
    }

    func testWorkEntriesForBotAndRoom() throws {
        let s = try sample()
        let w = try XCTUnwrap(ChatBars.botWork(s, "bot-a"))
        XCTAssertEqual("联网搜索 · example.com", WorkPhrase.phrase(w))
        XCTAssertNil(ChatBars.botWork(s, "bot-c"))
        let room = ChatBars.roomWork(s, "room-1")
        XCTAssertEqual(["bot-b"], room.map(\.0))
        XCTAssertEqual("正在跑测试", WorkPhrase.phrase(room[0].1))
        XCTAssertEqual(0, ChatBars.roomWork(s, "room-2").count)
        XCTAssertEqual(0, ChatBars.roomWork(try obj(#"{"work":{"rooms":{"r":"bad"}}}"#), "r").count)
    }

    func testTaskStatusLabels() {
        XCTAssertEqual("运行中", ChatBars.taskStatusLabel("running"))
        XCTAssertEqual("等你审批", ChatBars.taskStatusLabel("waiting_approval"))
        XCTAssertEqual("等待交接", ChatBars.taskStatusLabel("waiting_work"))
        XCTAssertEqual("Running", ChatBars.taskStatusLabel("running", lang: .en))
    }
}

extension ChatBarsTests {
    /// 群页任务条（macos-group-chat）：roomId 匹配的活动任务、审批优先、跨 Bot 合并。
    func testRoomTasksFilterAndSort() throws {
        let st = try obj(#"""
        {"bots":[
          {"id":"a","tasks":[
            {"id":"t1","status":"running","roomId":"room-1","updatedAt":5,"title":"甲任务"},
            {"id":"t2","status":"waiting_approval","roomId":"room-1","updatedAt":1},
            {"id":"t3","status":"running","updatedAt":9},
            {"id":"t4","status":"completed","roomId":"room-1"}]},
          {"id":"b","tasks":[
            {"id":"t5","status":"waiting_work","roomId":"room-1","startedAt":3}]}]}
        """#)
        let rows = ChatBars.roomTasks(st, roomId: "room-1")
        XCTAssertEqual(["t2", "t1", "t5"], rows.map(\.task.id)) // 审批最前，其余按时间倒序
        XCTAssertEqual(["a", "a", "b"], rows.map(\.botId))
        XCTAssertEqual("甲任务", rows[1].task.title)
        XCTAssertEqual(0, ChatBars.roomTasks(try obj(#"{"bots":[{"id":"a","tasks":"bad"}]}"#), roomId: "room-1").count)
        XCTAssertEqual(0, ChatBars.roomTasks(nil, roomId: "room-1").count)
    }
}
