import Foundation

/// 0.3.10 聊天条（dsbot-chat-bars-0310，与 `ChatBars.kt` 对齐）：从 state 全量/合并视图
///（含 live 字段——state 应答是 {...view, liveKey, ...live}，增量 live 经 DsbotModels.mergeDelta
/// 合并进同一对象）派生后台任务条、电脑操作请求、模型失败修复卡与群目标进度。
///
/// 字段形态为 DS-Bot 0.3.10 源码核对（tech/dsbot-api 的 chat-bars-fields-0310）：
/// 这些条目不在 hello 能力名单内，按字段存在显隐——缺失、类型不符或畸形一律返回空/nil，
/// 绝不抛异常（Bot 与远端数据不可信）。
public enum ChatBars: Sendable {
    public static let titleMax = 80
    public static let detailTextMax = 400
    public static let listMax = 20
    public static let stagesMax = 20

    /// 网页端 turn-error.js 同款公开充值页（不含任何用户凭据或 harness 地址）。
    public static let topUpURL = "https://platform.deepseek.com/top_up"

    public struct Task: Equatable, Sendable {
        public let id: String
        public let title: String
        public let status: String
        public let running: Bool

        public init(id: String, title: String, status: String, running: Bool) {
            self.id = id
            self.title = title
            self.status = status
            self.running = running
        }
    }

    /// 后台任务详情元数据（dsbot-bot-task-detail）：从 state.bots[].tasks[] 全量状态查找，
    /// 含已结束任务以便平滑切为只读。
    public struct TaskDetail: Equatable, Sendable {
        public let id: String
        public let title: String
        public let status: String
        public let sessionId: String?
        public let origin: String?
        public let startedAt: Int64?
        public let updatedAt: Int64?
        public let endedAt: Int64?
        public let summary: String?
        public let error: String?
        public let running: Bool
        public let active: Bool
    }

    public struct ComputerAsk: Equatable, Sendable {
        public let id: String
        public let botId: String
        public let sessionId: String?
        public let roomId: String?
        public let appName: String
    }

    /// 修复卡：title 为可读原因，guidance 为修复指引；topUp 时显示「去充值」（topUpURL）。
    /// fixInProviders：修复手段（填写或更换 API key）在「模型与服务商」页（dsbot-models-providers）。
    public struct Repair: Equatable, Sendable {
        public let code: String
        public let title: String
        public let guidance: String
        public let topUp: Bool
        public let fixInProviders: Bool
    }

    public struct Stage: Equatable, Sendable {
        public let title: String
        public let status: String
    }

    /// 群目标：current 为进行中阶段下标（无则 -1），passed 为已通过阶段数。
    public struct Goal: Equatable, Sendable {
        public let title: String
        public let status: String
        public let stages: [Stage]
        public let current: Int
        public let passed: Int
    }

    /// 活动中的任务状态（ds-bot threads.js ACTIVE_TASK_STATUSES）。
    static let activeTasks: Set<String> = ["running", "waiting_work", "waiting_approval"]

    public static func isActiveTaskStatus(_ status: String) -> Bool {
        activeTasks.contains(status)
    }

    /// 只有进行中的目标显示目标条；complete/stopped 不显示。
    static let shownGoals: Set<String> = ["drafting", "active", "paused"]

    /// Bot 正在操作电脑的控制态（computer/index.js 的 control state）。
    static let computerBusyStates: Set<String> = ["bot", "waiting"]

    /// 修复手段是填写或更换 API key 的失败代码（可直达「模型与服务商」页）。
    static let keyCodes: Set<String> = ["MISSING_CREDENTIAL", "INVALID_CREDENTIAL", "AUTH"]

    // MARK: - 后台任务

    /// 私聊任务条：只取不带 roomId 的活动任务（群里接的任务属于群，对齐网页端 tasksForChat），
    /// 排序同网页端 taskSort——等你审批在前，其余按 updatedAt（缺省 startedAt）倒序。
    public static func tasks(_ state: JsonObject?, _ botId: String) -> [Task] {
        let list = botOf(state, botId)?.array("tasks") ?? []
        var rows: [(rank: Int, updated: Int64, task: Task, order: Int)] = []
        for (i, v) in list.enumerated() {
            guard let o = v.objectValue, let id = o.nonEmptyString("id"), let status = o.string("status") else { continue }
            guard activeTasks.contains(status) else { continue }
            if o.string("roomId") != nil { continue }
            let updated = num(o, "updatedAt") ?? num(o, "startedAt") ?? 0
            let title = clip(nonBlankTrimmed(o.string("title")) ?? id, titleMax)
            rows.append((status == "waiting_approval" ? 0 : 1, updated, Task(id: id, title: title, status: status, running: o.bool("running") ?? false), i))
        }
        return rows
            .sorted { a, b in
                if a.rank != b.rank { return a.rank < b.rank }
                if a.updated != b.updated { return a.updated > b.updated }
                return a.order < b.order
            }
            .map(\.task)
            .prefix(listMax).map { $0 }
    }

    /// 按 botId 与 taskId 查找任务详情元数据（不限活动态，dsbot-bot-task-detail）：
    /// 用户查看详情期间任务可能结束并保留在 state.bots[].tasks[] 中，查全量可平滑切只读并显示摘要/错误。
    public static func taskOf(_ state: JsonObject?, _ botId: String, _ taskId: String) -> TaskDetail? {
        guard !taskId.isEmpty else { return nil }
        let list = botOf(state, botId)?.array("tasks") ?? []
        guard let o = list.first(where: { $0.objectValue?.string("id") == taskId })?.objectValue,
              let status = o.nonEmptyString("status")
        else { return nil }
        return TaskDetail(
            id: taskId,
            title: clip(nonBlankTrimmed(o.string("title")) ?? taskId, titleMax),
            status: status,
            sessionId: o.nonEmptyString("sessionId"),
            origin: o.nonEmptyString("origin"),
            startedAt: num(o, "startedAt"),
            updatedAt: num(o, "updatedAt") ?? num(o, "startedAt"),
            endedAt: num(o, "endedAt"),
            summary: nonBlankTrimmed(o.string("summary")).map { clip($0, detailTextMax) },
            error: nonBlankTrimmed(o.string("error")).map { clip($0, detailTextMax) },
            running: o.bool("running") ?? false,
            active: isActiveTaskStatus(status)
        )
    }

    /// 群页任务条（macos-group-chat）：各 Bot 在该群接的活动任务（roomId 匹配），
    /// 排序同私聊（等你审批在前，其余按 updatedAt 倒序），带 botId 供群页显示归属。
    public static func roomTasks(_ state: JsonObject?, roomId: String) -> [(task: Task, botId: String)] {
        let bots = state?.array("bots") ?? []
        var rows: [(rank: Int, updated: Int64, order: Int, task: Task, botId: String)] = []
        var order = 0
        for b in bots {
            guard let bot = b.objectValue, let botId = bot.nonEmptyString("id") else { continue }
            for v in bot.array("tasks") ?? [] {
                guard let o = v.objectValue, let id = o.nonEmptyString("id"), let status = o.string("status") else { continue }
                guard activeTasks.contains(status) else { continue }
                guard o.string("roomId") == roomId else { continue }
                let updated = num(o, "updatedAt") ?? num(o, "startedAt") ?? 0
                let title = clip(nonBlankTrimmed(o.string("title")) ?? id, titleMax)
                rows.append((status == "waiting_approval" ? 0 : 1, updated, order, Task(id: id, title: title, status: status, running: o.bool("running") ?? false), botId))
                order += 1
            }
        }
        return rows
            .sorted { a, b in
                if a.rank != b.rank { return a.rank < b.rank }
                if a.updated != b.updated { return a.updated > b.updated }
                return a.order < b.order
            }
            .map { (task: $0.task, botId: $0.botId) }
            .prefix(listMax).map { $0 }
    }

    // MARK: - 电脑操作

    /// 待作答的电脑操作请求：roomId 非空时取该群的请求；否则取 botId 的私聊请求（无 roomId）。
    /// 按 at 升序（先来的在前）。
    public static func computerAsks(_ state: JsonObject?, botId: String? = nil, roomId: String? = nil) -> [ComputerAsk] {
        guard let asks = computerLive(state)?.object("asks") else { return [] }
        var rows: [(at: Int64, ask: ComputerAsk, order: Int)] = []
        for (i, v) in asks.entries.enumerated() {
            guard let o = v.value.objectValue,
                  let id = o.nonEmptyString("id"),
                  let owner = o.string("botId")
            else { continue }
            let askRoom = o.string("roomId")
            let matches: Bool
            if let roomId {
                matches = askRoom == roomId
            } else {
                matches = owner == botId && askRoom == nil
            }
            guard matches else { continue }
            let at = num(o, "at") ?? Int64.max
            rows.append((at, ComputerAsk(
                id: id,
                botId: owner,
                sessionId: o.string("sessionId"),
                roomId: askRoom,
                appName: clip(nonBlankTrimmed(o.string("appName")) ?? "?", titleMax)
            ), i))
        }
        return rows
            .sorted { a, b in
                if a.at != b.at { return a.at < b.at }
                return a.order < b.order
            }
            .map(\.ask)
            .prefix(listMax).map { $0 }
    }

    public static func computerBusy(_ state: JsonObject?, _ botId: String) -> Bool {
        guard let entry = computerLive(state)?.object("bots")?.object(botId) else { return false }
        return entry.string("state").map { computerBusyStates.contains($0) } ?? false
    }

    static func computerLive(_ state: JsonObject?) -> JsonObject? {
        state?.object("computerLive")
    }

    // MARK: - 模型失败修复卡

    public static func repair(_ state: JsonObject?, _ botId: String, lang: I18n.Lang = I18n.lang) -> Repair? {
        guard let bot = botOf(state, botId), let blocked = bot.object("blocked"),
              let code = blocked.nonEmptyString("code")
        else { return nil }
        let name = clip(bot.string("name") ?? botId, titleMax)
        return repairOf(code, provider: blocked.string("provider"), status: num(blocked, "status"), botName: name, lang: lang)
    }

    /// blocked 代码到文案（对齐网页端 failureText/fixesFor；密钥内容从不出现）。
    public static func repairOf(_ code: String, provider: String?, status: Int64?, botName: String, lang: I18n.Lang = I18n.lang) -> Repair {
        let zh = lang == .zh
        let p = providerName(provider, lang: lang)
        let st = status.map { v in "（\(v)）" } ?? ""
        let stEn = status.map { v in " (\(v))" } ?? ""
        let topUp = (code == "QUOTA" && provider == "deepseek-official") || code == "ACCOUNT_QUOTA"
        let keyFix = zh
            ? "请在「模型与服务商」页为该服务商填写或更换 API key（或在 Bot 详情改用其他模型）；修好后此卡会自动消失，未消失可点「重试」。"
            : "Add or replace this provider's API key on the Models & providers page (or choose another model in the Bot's details); this card clears itself once fixed, otherwise tap Retry."
        let signInFix = zh
            ? "请在电脑上的网页端重新登录 DeepSeek 账号（或改用其他模型）；登录后此卡会自动消失，未消失可点「重试」。"
            : "Sign in to the DeepSeek account again in the web app on your computer (or choose another model); this card clears itself once signed in, otherwise tap Retry."
        let localFix = zh
            ? "请在运行 dsh 的电脑上检查该本地 Agent，处理后点「重试」。"
            : "Check this local agent on the computer running dsh, then tap Retry."
        let title: String
        let guidance: String
        switch code {
        case "MISSING_CREDENTIAL":
            title = zh ? "\(botName) 使用的模型还没有 API key" : "\(botName)'s model has no API key yet"
            guidance = keyFix
        case "INVALID_CREDENTIAL":
            title = zh ? "API key 格式不对" : "This API key is not in the right format"
            guidance = keyFix
        case "AUTH":
            title = zh ? "\(p) 未接受当前 API key\(st)" : "\(p) did not accept the current API key\(stEn)"
            guidance = keyFix
        case "QUOTA":
            title = zh ? "\(p) 余额或额度不足\(st)" : "\(p) is out of balance or quota\(stEn)"
            guidance = topUp
                ? (zh ? "充值后点「重试」。" : "Top up, then tap Retry.")
                : (zh ? "请到该服务商充值，或在电脑上的网页端改用其他模型，然后点「重试」。" : "Top up with this provider, or choose another model in the web app, then tap Retry.")
        case "ACCOUNT_QUOTA":
            title = zh ? "DeepSeek 账号余额不足" : "The DeepSeek account balance is too low"
            guidance = zh ? "充值后点「重试」。" : "Top up, then tap Retry."
        case "ACCOUNT_SIGN_IN_REQUIRED", "ACCOUNT_SIGNED_OUT", "ACCOUNT_TOKEN_INVALID":
            title = zh ? "DeepSeek 账号登录已失效" : "The DeepSeek account sign-in has expired"
            guidance = signInFix
        case "LOCAL_AGENT_MISSING":
            title = zh ? "找不到本地 Agent" : "The local agent was not found"
            guidance = localFix
        case "LOCAL_AGENT_SIGNIN":
            title = zh ? "本地 Agent 未登录" : "The local agent is not signed in"
            guidance = localFix
        case "LOCAL_AGENT_QUOTA":
            title = zh ? "本地 Agent 额度不足" : "The local agent is out of quota"
            guidance = localFix
        case "LOCAL_AGENT_EXIT":
            title = zh ? "本地 Agent 异常退出" : "The local agent exited unexpectedly"
            guidance = localFix
        default:
            title = zh ? "\(botName) 上一轮运行失败（\(code)）" : "\(botName)'s last turn failed (\(code))"
            guidance = zh ? "请在电脑上的网页端查看原因并处理，然后点「重试」。" : "Check the cause in the web app on your computer, then tap Retry."
        }
        return Repair(code: code, title: title, guidance: guidance, topUp: topUp, fixInProviders: keyCodes.contains(code))
    }

    static func providerName(_ provider: String?, lang: I18n.Lang) -> String {
        switch provider {
        case nil, "":
            return lang == .zh ? "模型服务" : "The model service"
        case "deepseek-official":
            return "DeepSeek"
        case "deepseek-account":
            return lang == .zh ? "DeepSeek 账号" : "DeepSeek account"
        case .some(let p):
            return clip(p, titleMax)
        }
    }

    // MARK: - 群目标

    public static func goal(_ room: JsonObject?) -> Goal? {
        guard let g = room?.object("goal"), let status = g.string("status") else { return nil }
        guard shownGoals.contains(status) else { return nil }
        let stages = (g.array("stages") ?? []).compactMap { v -> Stage? in
            guard let o = v.objectValue else { return nil }
            return Stage(title: clip(nonBlankTrimmed(o.string("title")) ?? "", titleMax), status: o.string("status") ?? "pending")
        }
        .prefix(stagesMax).map { $0 }
        let cur = num(g, "current") ?? -1
        let current = cur >= 0 && cur < stages.count ? Int(cur) : -1
        let title = clip(nonBlankTrimmed(g.string("title")) ?? "", titleMax)
        return Goal(title: title, status: status, stages: stages, current: current, passed: stages.filter { $0.status == "passed" }.count)
    }

    public static func goalStatusLabel(_ status: String, lang: I18n.Lang = I18n.lang) -> String {
        switch status {
        case "drafting": return lang == .zh ? "起草中" : "Drafting"
        case "active": return lang == .zh ? "进行中" : "In progress"
        case "paused": return lang == .zh ? "已暂停" : "Paused"
        default: return status
        }
    }

    public static func taskStatusLabel(_ status: String, lang: I18n.Lang = I18n.lang) -> String {
        switch status {
        case "running": return lang == .zh ? "运行中" : "Running"
        case "waiting_approval": return lang == .zh ? "等你审批" : "Waiting for approval"
        case "waiting_work": return lang == .zh ? "等待交接" : "Waiting for hand-off"
        case "completed": return lang == .zh ? "已完成" : "Done"
        case "failed": return lang == .zh ? "已失败" : "Failed"
        case "interrupted": return lang == .zh ? "已中断" : "Interrupted"
        case "stopped": return lang == .zh ? "已停止" : "Stopped"
        default: return status
        }
    }

    public static func taskOriginLabel(_ origin: String?, lang: I18n.Lang = I18n.lang) -> String? {
        switch origin {
        case "fg-handoff": return lang == .zh ? "转自对话" : "Moved from chat"
        case "bot-handoff": return lang == .zh ? "同伴委派" : "Teammate request"
        default: return nil
        }
    }

    // MARK: - 写操作失败提示

    /// 聊天条写操作（停止任务、电脑作答/停止、重试）失败的可读提示；nil code 为网络失败。
    public static func actionError(_ code: String?, lang: I18n.Lang = I18n.lang) -> String {
        let zh = lang == .zh
        switch code {
        case nil, "network":
            return zh ? "操作失败：网络不可用，请稍后重试" : "Failed: the network is unavailable, try again later"
        case "bot/not-found":
            return zh ? "操作失败：任务、请求或 Bot 已不存在" : "Failed: the task, request or Bot no longer exists"
        case "bot/stale":
            return zh ? "操作失败：状态已变化，请刷新后再试" : "Failed: the state changed, refresh and try again"
        case "bot/read-only":
            return zh ? "操作失败：远端当前只读（另一个 dsh 正在使用该团队）" : "Failed: the team is read-only on this host"
        case "bot/invalid":
            return zh ? "操作失败：请求参数无效" : "Failed: the request was invalid"
        case "bot/forbidden", "bot/refused":
            return zh ? "操作失败：远端拒绝了该操作" : "Failed: the host refused this action"
        case "bot/unavailable":
            return zh ? "操作失败：该功能在远端不可用" : "Failed: this feature is unavailable on the host"
        case .some(let code):
            return zh ? "操作失败（\(code)）" : "Failed (\(code))"
        }
    }

    // MARK: - 工作详情（live 的 work）

    /// 私聊：live work.bots[botId]（{since, tool?, said?, note?, target?, roomId?}）。
    public static func botWork(_ state: JsonObject?, _ botId: String) -> JsonObject? {
        state?.object("work")?.object("bots")?.object(botId)
    }

    /// 群聊：live work.rooms[roomId] 的成员工作（botId → 工作详情），保持服务端顺序。
    public static func roomWork(_ state: JsonObject?, _ roomId: String) ->[(String, JsonObject)] {
        let list = state?.object("work")?.object("rooms")?.array(roomId) ?? []
        return list.compactMap { v in
            guard let o = v.objectValue, let id = o.string("botId") else { return nil }
            return (id, o)
        }
        .prefix(listMax).map { $0 }
    }

    // MARK: - 小工具

    static func botOf(_ state: JsonObject?, _ botId: String) -> JsonObject? {
        (state?.array("bots") ?? []).first { $0.objectValue?.string("id") == botId }?.objectValue
    }

    static func num(_ o: JsonObject, _ key: String) -> Int64? {
        guard let raw = o[key]?.numberRaw else { return nil }
        return Int64(raw) ?? Double(raw).map { Int64($0) }
    }

    static func nonBlankTrimmed(_ s: String?) -> String? {
        guard let s else { return nil }
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }

    /// 截断到 max 个 UTF-16 单元（含省略号），不切开代理对。
    static func clip(_ s: String, _ max: Int) -> String {
        I18n.clip(s, maxUTF16: max)
    }
}
