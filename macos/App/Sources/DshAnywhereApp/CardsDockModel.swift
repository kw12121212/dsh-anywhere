import DshCore
import Foundation
import Observation

/// 停靠区编排（macos-cards-dock）：从 state 汇集当前聊天的待处理卡（PendingDock.collect），
/// 一次展开一张（默认首张、pinned 固定不被新卡打断、作答移出后回落首张、可收起），
/// 作答经 chatAction（POST /api/bot），成功本地消隐并刷新 state，失败把服务端消息给聊天通知条。
@MainActor
@Observable
final class CardsDockModel {
    /// 作答成功、等待 state 刷新的过渡期排除集（按聊天重置）。
    private(set) var answered: Set<String> = []
    /// 用户固定展开的项；不在列表中时失效回落首张。
    private(set) var pinned: String?
    /// 收起全部（再点行头恢复）。
    private(set) var collapsed = false
    /// 在途作答（防重复提交）。
    private(set) var answering: Set<String> = []

    private weak var session: AppSessionModel?
    private var currentBotId: String?

    func bind(_ session: AppSessionModel) {
        self.session = session
    }

    /// 切换聊天时重置展开/收起与本地消隐（对齐 Android key(botId)）。
    func rebindChat(botId: String?) {
        guard botId != currentBotId else { return }
        currentBotId = botId
        answered = []
        pinned = nil
        collapsed = false
        answering = []
        cacheKey = nil
        cachedItems = nil
    }

    /// 当前聊天的待处理项：会话确认前不显示；看旧分段时过滤只属于当前分段的学习审批与交接。
    /// 按（state 代次、会话、作答集）缓存——流式期间每帧重算 collect 会卡主线程。
    func items(entry: TeamHome.Entry, chat: BotChatModel) -> [PendingDock.Item] {
        guard let session, let shownSid = chat.sessionId else { return [] }
        let key = "\(shownSid)|\(entry.id)|\(session.snapshot.stateRevision ?? "")|\(answered.count)|\(answering.count)"
        if key == cacheKey, let cached = cachedItems {
            return cached
        }
        let viewingCurrent = shownSid == entry.sessionId
        let collected = PendingDock.collect(
            session.snapshot.stateRaw,
            sessionIds: [shownSid],
            botId: entry.id,
            roomId: nil,
            answered: answered
        )
        .filter { viewingCurrent || ($0.kind != .learning && $0.kind != .handoff) }
        cacheKey = key
        cachedItems = collected
        return collected
    }

    /// 群聊停靠区（macos-group-chat）：sessionIds = 群会话+roomId+成员群内分段（rooms[].parts），
    /// botId=nil、roomId=群 id（审批按 roomId 归属；学习/交接为 Bot 私属不进群）。
    func itemsRoom(entry: TeamHome.Entry) -> [PendingDock.Item] {
        guard let session else { return [] }
        guard let room = (session.snapshot.stateRaw?.array("rooms") ?? [])
            .first(where: { $0.objectValue?.string("id") == entry.id })?.objectValue
        else { return [] }
        let key = "room|\(entry.id)|\(session.snapshot.stateRevision ?? "")|\(answered.count)|\(answering.count)"
        if key == cacheKey, let cached = cachedItems {
            return cached
        }
        var sessionIds: Set<String> = [entry.id]
        if let sid = room.string("sessionId") { sessionIds.insert(sid) }
        for part in room.stringList("parts") { sessionIds.insert(part) }
        let collected = PendingDock.collect(
            session.snapshot.stateRaw,
            sessionIds: sessionIds,
            botId: nil,
            roomId: entry.id,
            answered: answered
        )
        cacheKey = key
        cachedItems = collected
        return collected
    }

    private var cacheKey: String?
    private var cachedItems: [PendingDock.Item]?

    /// 对齐 Android SideEffect：展开项确定后回写 pinned，使默认展开始终固定在
    /// 首次见到的那一项——新到达的更高优先级卡不打断正在填写的那张。
    func syncPinned(with items: [PendingDock.Item]) {
        guard !collapsed else { return }
        let effective = expandedId(of: items)
        if pinned != effective {
            pinned = effective
        }
    }

    /// 当前应展开的项 id（收起时为 nil）。
    func expandedId(of items: [PendingDock.Item]) -> String? {
        if collapsed { return nil }
        if let pinned, items.contains(where: { $0.id == pinned }) { return pinned }
        let idx = PendingDock.expandedIndex(items)
        return idx >= 0 ? items[idx].id : nil
    }

    /// 点行头：已展开 → 收起全部；否则固定展开该项。
    func tapRow(itemId: String, in items: [PendingDock.Item]) {
        if expandedId(of: items) == itemId {
            collapsed = true
        } else {
            pinned = itemId
            collapsed = false
        }
    }

    /// 作答：ok 即成功（本地消隐 + 重取 state），失败把服务端消息给聊天通知条。
    /// onDone 在主线程回传成功与否（btw 行删除等非停靠项复用）。
    func answer(itemId: String, endpoint: String, payloadJson: String, onDone: ((Bool) -> Void)? = nil) {
        guard !answering.contains(itemId) else { return }
        answering.insert(itemId)
        Task { [weak self] in
            guard let self else { return }
            let result = await self.session?.chatAction(endpoint: endpoint, payloadJson: payloadJson)
                ?? .failure(status: nil, reason: .network)
            self.answering.remove(itemId)
            if case .ok = result {
                self.answered.insert(itemId)
                onDone?(true)
                await self.session?.refreshState()
            } else {
                if case .botError(_, let message) = result {
                    self.session?.chat.postNotice("作答失败：\(message)")
                } else {
                    self.session?.chat.postNotice("作答失败")
                }
                onDone?(false)
            }
        }
    }
}
