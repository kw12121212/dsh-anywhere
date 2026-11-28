import DshCore
import Foundation
import Observation

/// 群聊编排（macos-group-chat）：room-chat 分页与渲染（成员名色/群公告/轮次事件/[PASS] 不可见）、
/// feed 实时落定（message 事件合并去重、stream 仅提示）、发送经 session/prompt（@提及/引用在文本中，
/// 发送箱跨聊天保留并可重试）、停止群/成员、room-progress 可读进度、群目标操作。
@MainActor
@Observable
final class RoomChatModel {
    private(set) var roomId: String?
    private(set) var sessionId: String?
    private(set) var items: [ChatRender.Item] = []
    private(set) var running: [String] = []
    private(set) var speaking: [String] = []
    private(set) var announcement: String?
    private(set) var earlierCursor: JsonValue?
    private(set) var loading = false
    private(set) var loadingEarlier = false
    private(set) var atEarliest = false
    private(set) var notice: String?
    private(set) var progressText: String?
    private(set) var streamingHint: String?

    /// 发送箱（chat-outbox：群聊同规格——发送中/未发送可重试/删除/跨聊天保留；内存会话级）。
    private let outbox = Outbox()

    var outboxRows: [Outbox.Entry] {
        roomId.map { outbox.entries(Self.chatKey($0)) } ?? []
    }

    private weak var session: AppSessionModel?
    private var readFlightTask: Task<Void, Never>?
    private var earlierTask: Task<Void, Never>?
    /// 原始条目（分页合并与去重按 seq/turn/文本；渲染层 items 由它派生）。
    private var rawItems: [DsbotModels.ChatItem] = []

    /// 回到最新 / N 条新消息（new-messages-jump；与私聊共用 DshCore JumpButton）。
    let jump = JumpButton(thresholdPx: 600)
    private(set) var scrollTargetBottom = 0

    var jumpLabel: String? { jump.label }

    func onScrollDistance(_ px: Double) {
        jump.onDistance(px)
    }

    func jumpToLatest(immediate: Bool = false) {
        jump.onJumped()
        scrollTargetBottom &+= 1
        if immediate { jump.onJumpEnded() }
    }

    static func chatKey(_ roomId: String) -> String {
        "room:\(roomId)"
    }

    /// 打开群聊（entry.sessionId 为群会话 id；缺省回落 room.id——tech 实测两者同值）。
    func open(roomId: String, sessionId: String?) {
        guard self.roomId != roomId else { return }
        self.roomId = roomId
        self.sessionId = sessionId ?? roomId
        items = []
        running = []
        speaking = []
        announcement = nil
        rawItems = []
        earlierCursor = nil
        atEarliest = false
        loading = false
        loadingEarlier = false
        notice = nil
        progressText = nil
        streamingHint = nil
        readFlightTask?.cancel()
        earlierTask?.cancel()
        reload()
    }

    func bind(_ session: AppSessionModel) {
        self.session = session
    }

    private var roster: [String: RoomChat.Speaker] {
        RoomChat.roster(from: session?.snapshot.stateRaw.map(JsonValue.object))
    }

    /// 重读群首页（打开/发送成功/刷新）。
    func reload(keepStream: Bool = false) {
        guard let roomId else { return }
        readFlightTask?.cancel()
        earlierTask?.cancel()
        loading = true
        let task = Task { [weak self] in
            let page = await self?.session?.fetchRoomChatPage(roomId: roomId, cursor: nil)
            guard let self, !Task.isCancelled, self.roomId == roomId else { return }
            self.loading = false
            guard let page else {
                self.notice = I18n.tr("群聊读取失败", "Could not load the group chat")
                return
            }
            self.running = page.running
            self.speaking = page.speaking
            self.announcement = page.announcement
            self.earlierCursor = page.cursor
            self.atEarliest = page.cursor == nil
            self.rawItems = page.items
            self.reconcileOutbox()
            self.rebuildItems()
            if !keepStream { self.streamingHint = nil }
        }
        readFlightTask = task
    }

    /// 向上翻页（cursor 透传）：旧页在前，按 (seq, turn) 去重（无 seq 行按全文）。
    func loadEarlier() {
        guard let roomId, let cursor = earlierCursor, !loadingEarlier, !atEarliest else { return }
        loadingEarlier = true
        let task = Task { [weak self] in
            let page = await self?.session?.fetchRoomChatPage(roomId: roomId, cursor: cursor)
            guard let self, !Task.isCancelled, self.roomId == roomId else { return }
            self.loadingEarlier = false
            guard let page else { return }
            self.running = page.running
            self.speaking = page.speaking
            if let a = page.announcement { self.announcement = a }
            self.earlierCursor = page.cursor
            self.atEarliest = page.cursor == nil
            self.rawItems = Self.mergeEarlier(page.items, self.rawItems)
            self.rebuildItems()
        }
        earlierTask = task
    }

    /// 原始条目稳定去重键：有 seq 按 (seq, turn)；无 seq（用户/事件行）按全文。
    private static func rawKey(_ item: DsbotModels.ChatItem) -> String {
        if let seq = item.seq {
            return "q|\(seq)|\(item.turn.map(String.init) ?? "")"
        }
        let raw = item.raw.map { Json.encode($0) } ?? (item.kind ?? "?") + "|" + (item.text ?? "")
        return "t|\(raw)"
    }

    private static func mergeEarlier(_ earlier: [DsbotModels.ChatItem], _ later: [DsbotModels.ChatItem]) -> [DsbotModels.ChatItem] {
        var seen = Set<String>()
        var out: [DsbotModels.ChatItem] = []
        for item in earlier + later {
            let key = rawKey(item)
            guard !seen.contains(key) else { continue }
            seen.insert(key)
            out.append(item)
        }
        return out
    }

    /// 渲染派生（服务端行 + 发送箱行）。
    private func rebuildItems() {
        let page = RoomChat.RoomPage(
            roomId: roomId,
            items: rawItems,
            running: running,
            speaking: speaking,
            cursor: earlierCursor
        )
        items = RoomChat.renderRoom(page, roster: roster)
    }

    // MARK: - 发送与发送箱（session/prompt，sessionId=群会话；成功后重读以服务端用户行确认）

    func send(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let sid = sessionId, let roomId else { return }
        let window = maxOrder()
        let entry = outbox.add(Self.chatKey(roomId), text: trimmed, display: trimmed, window: window)
        dispatch(entry, sessionId: sid)
    }

    /// 重试未发送行（原样重发）。
    func retryOutbox(_ id: Int64) {
        guard let entry = outbox.get(id), entry.state == .failed, let sid = sessionId else { return }
        _ = outbox.setState(id, .sending)
        dispatch(entry, sessionId: sid)
    }

    func deleteOutbox(_ id: Int64) {
        _ = outbox.remove(id)
    }

    private func dispatch(_ entry: Outbox.Entry, sessionId sid: String) {
        let request = AttachmentMessages.promptRequest(
            requestId: "rpc-\(UUID().uuidString)",
            sessionId: sid,
            receiptIds: entry.receipts,
            text: entry.text
        )
        Task { [weak self] in
            guard let self else { return }
            do {
                _ = try await self.session?.submitAttachmentMessage(endpoint: "session/prompt", requestJson: request)
                _ = self.outbox.setState(entry.id, .sent)
                // 群 feed 不推用户消息：立即重读，以服务端用户行确认（对账清除已送达行）。
                self.reload(keepStream: true)
            } catch {
                _ = self.outbox.setState(entry.id, .failed)
                self.notice = I18n.tr("发送失败，可在下方未发送行点按重试", "Send failed. Tap the unsent row below to retry")
            }
        }
    }

    /// 服务端用户行对账（群用户行无 seq：order 用 time；文本匹配即确认送达）。
    private func reconcileOutbox() {
        guard let roomId else { return }
        let rows = rawItems.compactMap { item -> Outbox.UserRow? in
            guard let obj = item.raw?.objectValue, obj.nonEmptyString("kind") == "user",
                  let text = item.text, !text.isEmpty
            else { return nil }
            return Outbox.UserRow(order: item.time, text: text)
        }
        _ = outbox.reconcile(Self.chatKey(roomId), userRows: rows)
    }

    private func maxOrder() -> Int64 {
        rawItems.compactMap(\.time).max() ?? 0
    }

    // MARK: - 停止 / 进度 / 目标

    func stopAll() {
        guard let roomId else { return }
        act("stop-room-work", RoomChat.stopRoomWork(roomId: roomId),
            ok: I18n.tr("已请求停止群工作", "Asked the group to stop"),
            fail: I18n.tr("停止群失败", "Could not stop the group"))
    }

    func stopMember(botId: String) {
        act("stop-member-work", "{\"roomId\":\"\",\"botId\":\(JsonString.of(botId))}",
            ok: I18n.tr("已请求停止该成员", "Asked the member to stop"),
            fail: I18n.tr("停止成员失败", "Could not stop the member"))
    }

    func showProgress() {
        guard let roomId else { return }
        Task { [weak self] in
            guard let self else { return }
            let result = await self.session?.chatAction(endpoint: "room-progress", payloadJson: RoomChat.roomProgress(roomId: roomId))
                ?? .failure(status: nil, reason: .network)
            switch result {
            case .ok(let value):
                // value 为成员进度数组：逐元素取 botId/name 与 stage/task/summary/text 可读拼接（≤200 字符）。
                let arr = value.arrayValue ?? []
                let lines = arr.compactMap { el -> String? in
                    guard let o = el.objectValue else { return nil }
                    let who = o.nonEmptyString("botId") ?? o.nonEmptyString("name") ?? "?"
                    let what = o.nonEmptyString("stage") ?? o.nonEmptyString("task") ?? o.nonEmptyString("summary") ?? o.nonEmptyString("text") ?? ""
                    return what.isEmpty ? who : "\(who): \(what)"
                }
                self.progressText = lines.isEmpty
                    ? I18n.tr("当前没有成员在工作", "No member is working")
                    : I18n.tr("成员进度：", "Member progress:") + String(lines.joined(separator: "; ").prefix(200))
            case .botError(let code, _):
                self.progressText = I18n.tr("进度不可用：", "Progress unavailable: ") + code
            case .failure:
                self.progressText = I18n.tr("进度不可用（网络）", "Progress unavailable (network)")
            }
        }
    }

    func goalDraft(_ text: String) {
        guard let roomId else { return }
        act("goal-draft", RoomChat.goalDraft(roomId: roomId, text: text),
            ok: I18n.tr("目标草稿已提交", "Goal draft submitted"),
            fail: I18n.tr("提交目标草稿失败", "Could not submit the goal draft"))
    }

    /// 目标控制（group-chat：确认草稿 confirm / 推进下一阶段 advance，与网页端 Android 端一致）。
    func goalControl(_ action: String) {
        guard let roomId else { return }
        act("goal-control", RoomChat.goalControl(roomId: roomId, action: action),
            ok: I18n.tr("目标操作已提交", "Goal action submitted"),
            fail: I18n.tr("目标操作失败", "Goal action failed"))
    }

    private func act(_ endpoint: String, _ payload: String, ok: String, fail: String) {
        Task { [weak self] in
            guard let self else { return }
            if case .ok = await self.session?.chatAction(endpoint: endpoint, payloadJson: payload) {
                self.notice = ok
                await self.session?.refreshState()
            } else {
                self.notice = fail
            }
        }
    }

    func postNotice(_ text: String) {
        notice = text
    }

    /// 收起通知条与进度条。
    func dismissNotices() {
        notice = nil
        progressText = nil
    }

    // MARK: - feed 事件（群会话过滤：message 落定、stream 提示）

    func handleChatEvent(_ event: ChatFeedEvent) {
        guard let roomId else { return }
        // 群相关事件：sessionId 为群会话/群 id（或缺失——对齐 Android 宽松接受）。
        if let sid = event.sessionId, sid != sessionId, sid != roomId {
            return
        }
        switch event.event {
        case "message":
            // 群条目以 message 事件落定：重读首页（服务端行为准，天然去重）。
            reload(keepStream: true)
        case "stream":
            // 多成员并行时流式仅作提示。
            if let obj = Json.parse(event.data)?.objectValue {
                let who = obj.nonEmptyString("name") ?? obj.nonEmptyString("botId").flatMap { roster[$0]?.name }
                streamingHint = who.map { I18n.tr("\($0) 正在回复…", "\($0) is typing…") }
                    ?? I18n.tr("成员正在回复…", "A member is typing…")
            }
        default:
            break
        }
    }

    // MARK: - 派生（头部）

    /// 群成员名册（来自 state bots，按成员 id）。
    var members: [RoomChat.Speaker] {
        guard let roomId,
              let room = (session?.snapshot.stateRaw?.array("rooms") ?? []).first(where: { $0.objectValue?.string("id") == roomId })?.objectValue
        else { return [] }
        let all = roster
        return room.stringList("members").compactMap { all[$0] }
    }

    /// 运行中成员（room-chat running 或 live work.rooms）：有工作详情给短语，否则中性「工作中」
    ///（work-phrase：远端未返回工作详情时只显示原指示，不以「思考中」兜底）。
    var workingMembers: [(speaker: RoomChat.Speaker, phrase: String)] {
        let works = ChatBars.roomWork(session?.snapshot.stateRaw, roomId ?? "")
        var byId: [String: JsonObject] = [:]
        for (id, o) in works { byId[id] = o }
        let ids = Set(running + works.map(\.0))
        return members
            .filter { m in m.botId.map(ids.contains) ?? false }
            .map { m in
                let entry = m.botId.flatMap { byId[$0] }
                let phrase = entry.map { WorkPhrase.phrase($0) } ?? I18n.tr("工作中", "Working")
                return (m, phrase)
            }
    }

    /// 群目标（进行中才显示）。
    var goal: ChatBars.Goal? {
        guard let roomId,
              let room = (session?.snapshot.stateRaw?.array("rooms") ?? []).first(where: { $0.objectValue?.string("id") == roomId })?.objectValue
        else { return nil }
        return ChatBars.goal(room)
    }
}
