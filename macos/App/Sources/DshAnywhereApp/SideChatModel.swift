import DshCore
import Foundation
import Observation

/// Side chat 编排（macos-side-chat）：右侧检查器的旁路会话——side-new 新建、side-ask 提问
///（附最近消息快照，不运行工具不打断 Bot 工作）、side-cancel 取消、side-close 关闭；
/// 回答不走 feed 落 state（tech dsbot-api 0.3.5 探针实证）：作答后即时刷新 + 有进行中时
/// 2.5s 单飞轮询 state.sideChats 取回；进行中状态跨聊天切换保留（状态在远端 state）。
@MainActor
@Observable
final class SideChatModel {
    private(set) var botId: String?
    private(set) var notice: String?
    private(set) var asking = false
    /// 当前旁路会话（side-new/side-ask 应答的 chatId；nil 表示未开）。
    private(set) var sideChatId: String?
    /// 最近一次 side-ask 的应答会话 id（乐观行按它归属清理）。
    private(set) var lastAskChatId: String?
    /// 本地乐观问题行（发送到 state 取回前显示）。
    private(set) var pendingQuestion: String?

    private weak var session: AppSessionModel?
    private var pollTask: Task<Void, Never>?

    struct QAPair: Identifiable, Equatable {
        let id: String
        let question: String
        let answer: String?
        let working: Bool
    }

    /// 当前 Bot 的旁路会话条目（state.sideChats[botId]）。
    private(set) var pairs: [QAPair] = []

    func bind(_ session: AppSessionModel) {
        self.session = session
    }

    /// 选中私聊时打开面板数据（botId 变化重置；进行中状态来自远端 state 自然保留）。
    func open(botId: String) {
        guard self.botId != botId else {
            refresh()
            return
        }
        self.botId = botId
        sideChatId = nil
        lastAskChatId = nil
        notice = nil
        pendingQuestion = nil
        refresh()
    }

    /// 从 state.sideChats 取回该 Bot 的旁路条目（tech 实证条目级 {id, question, answer,
    /// status: done|working}；旧 kind/text 形态降级回退——经 SideChat.decodeQA）。
    func refresh() {
        guard let session, let botId else { return }
        let raw = session.snapshot.stateRaw?.object("sideChats")?.array(botId) ?? []
        pairs = SideChat.decodeQA(raw).map { QAPair(id: $0.id, question: $0.question, answer: $0.answer, working: $0.working) }
        if sideChatId == nil, let first = pairs.first {
            sideChatId = first.id
        }
        // 轮询：有进行中回答才轮询（单飞：pollTask 存在即跳过）。
        if pairs.contains(where: \.working) {
            startPolling()
        } else {
            pollTask?.cancel()
            pollTask = nil
        }
        // 乐观行清理：应答会话已落 state 且非进行中（同文本不同条目不误清）。
        if pendingQuestion != nil,
           let sid = lastAskChatId,
           let landed = pairs.first(where: { $0.id == sid }), !landed.working {
            pendingQuestion = nil
        }
    }

    private func startPolling() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                guard let self, !Task.isCancelled else { return }
                // 先拉网络再解析（feed 断线期间轮询仍能推进状态）。
                await self.session?.refreshState()
                self.refresh()
                if !self.pairs.contains(where: \.working) { break }
            }
            if let self, self.pollTask?.isCancelled != true {
                self.pollTask = nil
            }
        }
    }

    /// 新建旁路会话（side-new；应答 chatId 归属后续事件与轮询）。
    func newSide() {
        guard let session, let botId,
              let mainSid = session.chat.sessionId ?? session.snapshot.team?.sections.flatMap(\.entries).first(where: { $0.id == botId })?.sessionId
        else { return }
        Task { [weak self] in
            guard let self else { return }
            if case .ok(let value) = await self.session?.chatAction(endpoint: "side-new", payloadJson: SideChat.newSide(sessionId: mainSid)),
               let chatId = SideChat.decodeChatId(value) {
                self.sideChatId = chatId
                self.notice = nil
            } else {
                self.notice = I18n.tr("打开旁路会话失败", "Could not open a side chat")
            }
        }
    }

    /// 提问（side-ask，附最近消息快照；sessionId 为旁路会话，无则先新建；防重入）。
    func ask(_ text: String, recentMessages: [(fromUser: Bool, text: String)]) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !asking, !trimmed.isEmpty, let botId else { return }
        asking = true
        pendingQuestion = trimmed
        Task { [weak self] in
            guard let self else { return }
            var sideId = self.sideChatId
            if sideId == nil {
                if let mainSid = self.session?.chat.sessionId
                    ?? self.session?.snapshot.team?.sections.flatMap(\.entries).first(where: { $0.id == botId })?.sessionId,
                    case .ok(let value) = await self.session?.chatAction(endpoint: "side-new", payloadJson: SideChat.newSide(sessionId: mainSid)),
                    let chatId = SideChat.decodeChatId(value) {
                    sideId = chatId
                    self.sideChatId = chatId
                }
            }
            guard let sideId else {
                self.asking = false
                self.notice = I18n.tr("打开旁路会话失败", "Could not open a side chat")
                self.pendingQuestion = nil
                return
            }
            let payload = SideChat.ask(sessionId: sideId, text: trimmed, snapshotJson: SideChat.snapshotOf(recentMessages))
            let result = await self.session?.chatAction(endpoint: "side-ask", payloadJson: payload)
            self.asking = false
            if case .ok(let value) = result, let chatId = SideChat.decodeChatId(value) {
                // 应答带新 chatId（每次提问新建会话——App 据此归属后续取回与乐观行清理）。
                self.sideChatId = chatId
                self.lastAskChatId = chatId
            } else if case .botError(_, let message) = result {
                self.notice = message.isEmpty ? I18n.tr("提问失败", "Could not ask") : message
                self.pendingQuestion = nil
            } else if result != nil {
                self.notice = I18n.tr("提问失败", "Could not ask")
                self.pendingQuestion = nil
            }
            await self.session?.refreshState()
            self.refresh()
        }
    }

    /// 取消进行中回答（side-cancel；优先真正进行中的条目，最新会话作次选）。
    func cancel() {
        guard let sideId = pairs.first(where: \.working)?.id ?? sideChatId else { return }
        act("side-cancel", SideChat.cancel(sideId: sideId), I18n.tr("已请求取消", "Cancellation requested"))
    }

    /// 关闭当前旁路会话（side-close）。
    func close() {
        guard let sideId = sideChatId ?? pairs.last?.id else { return }
        act("side-close", SideChat.close(sideId: sideId), I18n.tr("已关闭旁路会话", "Side chat closed"))
    }

    private func act(_ endpoint: String, _ payload: String, _ okText: String) {
        Task { [weak self] in
            guard let self else { return }
            let result = await self.session?.chatAction(endpoint: endpoint, payloadJson: payload)
            if case .ok = result {
                self.notice = okText
                await self.session?.refreshState()
                self.refresh()
            } else if case .botError(_, let message) = result {
                self.notice = message.isEmpty ? I18n.tr("操作失败", "Action failed") : message
            } else {
                self.notice = I18n.tr("操作失败", "Action failed")
            }
        }
    }

    /// 收起提示条。
    func dismissNotice() {
        notice = nil
    }

    /// feed 事件（回答不走 feed，但 stream 提示可用于归属检查；message 事件按 sideId 触发即时刷新）。
    func handleChatEvent(_ event: ChatFeedEvent) {
        guard event.event == "message",
              let sid = event.sessionId,
              pairs.contains(where: { $0.id == sid }) || sid == sideChatId
        else { return }
        refresh()
    }

    /// 有进行中的回答（界面提示与轮询依据）。
    var hasWorking: Bool {
        pairs.contains(where: \.working) || pendingQuestion != nil
    }
}
