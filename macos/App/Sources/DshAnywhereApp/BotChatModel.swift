import DshCore
import Foundation
import Observation

/// 私聊编排（macos-bot-chat）：单聊的条目、分页、流式、发送箱、缓存与回到最新。
/// 纯逻辑件（Outbox/ChatCache/ReadFlight/JumpButton/ChatRefresh/StreamBuffer）均在 DshCore，
/// 本模型在主线程收敛它们的可变状态；网络与 feed 事件经 `AppSessionModel` 的控制器转发。
@MainActor
@Observable
final class BotChatModel {
    // MARK: - 当前聊天状态

    private(set) var botId: String?
    private(set) var sessionId: String?
    private(set) var items: [ChatRender.Item] = []
    /// 更早记录游标（首页应答的 cursor；nil = 已到最早或未加载）。
    private(set) var earlierCursor: JsonValue?
    private(set) var loading = false
    private(set) var loadingEarlier = false
    private(set) var atEarliest = false
    /// 正在流式写入的气泡文本；nil 为无流式。
    private(set) var streamingText: String?
    private(set) var notice: String?

    /// 展示一条通知（停靠区作答失败、连接器提示等外部来源复用通知条）。
    func postNotice(_ text: String) {
        notice = text
    }
    private(set) var statsText: String?
    private(set) var exchangesText: String?

    /// 发送箱行（按当前聊天过滤后的展示序）。
    var outboxRows: [Outbox.Entry] { botId.map { outbox.entries(chatKey($0)) } ?? [] }

    /// 待发送附件栏（attachments-send）。
    var pendingAttachments: [PendingAttachment] = []

    // MARK: - 纯逻辑件

    private let outbox = Outbox()
    private let cache = ChatCache<ChatRender.Item>()
    private let scrollMemory = ScrollMemory()
    private let readFlight = ReadFlight()
    let jump = JumpButton(thresholdPx: 600)
    var isAway: Bool { jump.away }
    private let streamBuffer = ChatRender.StreamBuffer()

    // MARK: - 装配

    private weak var session: AppSessionModel?

    init() {}

    /// AppSessionModel 全部属性初始化完成后绑定（模型持弱引用，避免引用环）。
    func bind(_ session: AppSessionModel) {
        self.session = session
    }

    private var hostKey: String? {
        session.flatMap { ReadState.hostKey($0.snapshot.cleanUrl) }
    }

    private func chatKey(_ botId: String) -> String {
        let host = hostKey ?? ""
        return "\(host):bot:\(botId)"
    }

    // MARK: - 打开与刷新（chat-cache-fast-switch）

    /// 打开某 Bot 的私聊：立即回缓存（含滚动位置），后台刷新合并。
    func open(botId: String) {
        guard self.botId != botId else { return }
        self.botId = botId
        sessionId = nil
        atEarliest = false
        loadingEarlier = false
        streamBuffer.settle()
        streamingText = nil
        notice = nil
        statsText = nil
        exchangesText = nil
        let cached = cache.entry(chatKey(botId))
        if let cached {
            items = cached.items
            earlierCursor = cached.cursor
            sessionId = cached.sessionId
            loading = false
        } else {
            items = []
            earlierCursor = nil
            loading = true
        }
        refresh(jumpToBottomOnFirst: cached == nil)
        // 上次聊天记忆：只记标识不存内容。
        session?.rememberLastChat(botId)
    }

    /// 关闭聊天（清当前态；缓存保留）。
    func close() {
        botId = nil
        sessionId = nil
        items = []
        earlierCursor = nil
        streamingText = nil
        statsText = nil
        exchangesText = nil
    }

    /// 后台刷新（ReadFlight 单飞 + ChatRefresh 合并）。
    private func refresh(jumpToBottomOnFirst: Bool) {
        guard let botId else { return }
        switch readFlight.begin(botId) {
        case .ignored, .queued:
            return
        case .start(let gen):
            Task { await fetchLatest(botId: botId, gen: gen, jumpToBottomOnFirst: jumpToBottomOnFirst) }
        }
    }

    private func fetchLatest(botId: String, gen: Int, jumpToBottomOnFirst: Bool) async {
        guard let page = await session?.fetchChatPage(botId: botId) else {
            _ = applyFlightFinish(gen)
            if items.isEmpty {
                loading = false
                notice = "加载聊天失败，稍后重试"
            }
            return
        }
        let finish = applyFlightFinish(gen)
        let key = chatKey(botId)
        // 过期结果（分段切换/换目标后到达的旧读取）一律丢弃；离开后到达的不覆盖当前聊天。
        guard !finish.stale, self.botId == botId else { return }
        applyFreshPage(page, to: botId, key: key, forceReset: false, jumpToBottomOnFirst: jumpToBottomOnFirst)
        if droppedDuringLoad > 0 {
            // 加载窗口丢弃过实时事件：落地后补刷一次首页。
            droppedDuringLoad = 0
            refresh(jumpToBottomOnFirst: false)
        } else if finish.rerun {
            refresh(jumpToBottomOnFirst: false)
        }
    }

    /// 首页应答落地：合并决策、发送箱对账、缓存与流式定格互不干扰。
    private func applyFreshPage(_ page: DsbotModels.ChatPage, to botId: String, key: String, forceReset: Bool, jumpToBottomOnFirst: Bool) {
        let rendered = renderPage(page)
        let merged = ChatRefresh.merge(
            items,
            rendered,
            forceReset: forceReset,
            isLocal: { item in isLocalItem(item) },
            matches: { a, b in sameMessage(a, b) }
        )
        let hadNoItems = items.isEmpty
        if merged.changed || hadNoItems != rendered.isEmpty {
            jump.onAppended(merged.items.count - items.count)
        }
        items = merged.items
        if !merged.keepCursor {
            earlierCursor = page.cursor
            atEarliest = page.cursor == nil
        }
        if page.sessionId != nil, sessionId != page.sessionId {
            sessionId = page.sessionId
            // 会话确认前加入的 idle 附件在此补传（避免永久卡住）。
            for att in pendingAttachments where att.state == .idle {
                uploadPending(att.id)
            }
        }
        loading = false
        reconcileOutbox()
        cache.put(key, .init(items: items, cursor: earlierCursor, sessionId: sessionId))
        if jumpToBottomOnFirst || hadNoItems {
            jumpToLatest(immediate: true)
        }
    }

    /// ChatPage → 渲染条目（消息+图片/文件附带）。
    private func renderPage(_ page: DsbotModels.ChatPage) -> [ChatRender.Item] {
        // renderItem 与 extras 已按条目顺序产出；首页应答本身即时间序。
        var out: [ChatRender.Item] = []
        for raw in page.items {
            if let item = ChatRender.renderItem(raw) { out.append(item) }
            out.append(contentsOf: ChatRender.extras(raw))
        }
        return out
    }

    /// 本地条目（发送中/等待确认/流式定格）：刷新合并时保留。
    private func isLocalItem(_ item: ChatRender.Item) -> Bool {
        if case .message(let m) = item { return m.seq == nil }
        return false
    }

    /// 与 Kotlin sameMessage 对齐：身份 = fromUser + markdown（seq 有无不算差异）。
    private func sameMessage(_ a: ChatRender.Item, _ b: ChatRender.Item) -> Bool {
        if case .message(let x) = a, case .message(let y) = b {
            return x.fromUser == y.fromUser && x.markdown == y.markdown
        }
        return a == b
    }

    private func applyFlightFinish(_ gen: Int) -> ReadFlight.Finish {
        readFlight.finish(gen)
    }

    // MARK: - 更早分页

    func loadEarlier() {
        guard !loadingEarlier, let sid = sessionId, let cursor = earlierCursor, let botId else { return }
        loadingEarlier = true
        Task {
            let page = await session?.fetchEarlier(sessionId: sid, cursor: cursor)
            loadingEarlier = false
            guard self.botId == botId else { return }
            guard let page else {
                notice = "更早记录加载失败"
                return
            }
            let rendered = renderPage(page)
            items = rendered + items
            earlierCursor = page.cursor
            atEarliest = page.cursor == nil
            cache.put(chatKey(botId), .init(items: items, cursor: earlierCursor, sessionId: sessionId))
        }
    }

    // MARK: - 发送与发送箱（chat-outbox）

    /// 加入附件（选取/拖放/粘贴；图片按压缩规则处理名字/mediaType/字节）。
    func addAttachment(name: String, mediaType: String?, data: Data) {
        let compressed = ImageCompressor.process(name: name, mediaType: mediaType, data: data)
        let att = PendingAttachment(name: compressed.name, mediaType: compressed.mediaType, data: compressed.data)
        pendingAttachments.append(att)
        uploadPending(att.id)
    }

    /// 上传一个待发附件（失败单件重试）。
    func uploadPending(_ id: UUID) {
        guard let sid = sessionId,
              let idx = pendingAttachments.firstIndex(where: { $0.id == id })
        else {
            if sessionId == nil { notice = "正在确认会话，请稍候…" }
            return
        }
        pendingAttachments[idx].state = .uploading
        let att = pendingAttachments[idx]
        Task {
            do {
                let body = try await session?.uploadAttachment(sessionId: sid, name: att.name, mediaType: att.mediaType, data: att.data)
                guard let body else {
                    if let i = pendingAttachments.firstIndex(where: { $0.id == id }) {
                        pendingAttachments[i].state = .failed(reason: "上传失败，点按重试")
                    }
                    return
                }
                // ok:false 的应答读取服务端原因（不透传凭据，仅错误文本）。
                if case .err(_, let message)? = AttachmentMessages.parseServerResponse(body) {
                    if let i = pendingAttachments.firstIndex(where: { $0.id == id }) {
                        pendingAttachments[i].state = .failed(reason: message.isEmpty ? "上传被拒绝" : message)
                    }
                    return
                }
                guard let value = Json.parse(body)?.objectValue,
                      let upload = AttachmentMessages.decodeUpload(value["value"] ?? JsonValue.object(value))
                else {
                    if let i = pendingAttachments.firstIndex(where: { $0.id == id }) {
                        pendingAttachments[i].state = .failed(reason: "上传应答无法解析")
                    }
                    return
                }
                if let i = pendingAttachments.firstIndex(where: { $0.id == id }) {
                    pendingAttachments[i].state = .ready(receiptId: upload.receiptId, attachmentId: upload.attachmentId)
                }
            } catch {
                if let i = pendingAttachments.firstIndex(where: { $0.id == id }) {
                    pendingAttachments[i].state = .failed(reason: "上传失败，点按重试")
                }
            }
        }
    }

    func removeAttachment(_ id: UUID) {
        pendingAttachments.removeAll { $0.id == id }
    }

    /// 附件是否齐备（全部 Ready）。
    var attachmentsReady: Bool {
        pendingAttachments.allSatisfy {
            if case .ready = $0.state { return true }
            return false
        }
    }

    @discardableResult
    func send(_ raw: String) -> Bool {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !pendingAttachments.isEmpty else { return false }
        guard let botId else { return false }
        guard let sid = sessionId else {
            // 会话确认前提示稍候，不发往可能已过期的会话。
            notice = "正在确认会话，请稍候…"
            return false
        }
        // 带附件：receipt 齐备后经 mux session/prompt 提交；纯文本仍走 send。
        if !pendingAttachments.isEmpty {
            guard attachmentsReady else {
                notice = "附件还在上传，稍候再发送"
                return false
            }
            let receipts = pendingAttachments.compactMap { att -> AttachmentMessages.UploadResult? in
                if case .ready(let receiptId, let attachmentId) = att.state {
                    return AttachmentMessages.UploadResult(receiptId: receiptId, attachmentId: attachmentId, name: att.name, bytes: Int64(att.data.count))
                }
                return nil
            }
            let display = pendingAttachments.map { "[附件] \($0.name)" }.joined(separator: " ")
            let window = maxSeq()
            let entry = outbox.add(chatKey(botId), text: text, display: display.isEmpty ? text : "\(text) \(display)", receipts: receipts.map(\.receiptId), window: window)
            // 请求形态用 Kotlin 已实测的 AttachmentMessages.promptRequest（mode=queue、附件块在前）。
            let requestJson = AttachmentMessages.promptRequest(
                requestId: "req-\(UUID().uuidString)",
                sessionId: sid,
                receiptIds: receipts.map(\.receiptId),
                text: text
            )
            pendingAttachments.removeAll()
            Task {
                var ok = false
                if let body = try? await session?.submitAttachmentMessage(endpoint: "session/prompt", requestJson: requestJson),
                   case .ok = AttachmentMessages.parseServerResponse(body) {
                    ok = true
                }
                _ = outbox.setState(entry.id, ok ? .sent : .failed)
                if !ok { notice = "发送失败，可在下方未发送行点按重试" }
            }
            return true
        }
        let window = maxSeq()
        let entry = outbox.add(chatKey(botId), text: text, display: text, window: window)
        Task {
            let ok = await session?.sendChatMessage(sessionId: sid, text: text) ?? false
            _ = outbox.setState(entry.id, ok ? .sent : .failed)
            if !ok {
                notice = "发送失败，可在下方未发送行点按重试"
            }
        }
        return true
    }

    /// 重试：只发一次（进行中该行状态为 SENDING，按钮禁用）；带附件的行经 session/prompt
    /// 按 receipts 原样重发（chat-outbox：含附件消息重试原样重发），纯文本仍走 send。
    func retryOutbox(_ id: Int64) {
        guard let entry = outbox.get(id), entry.state == .failed, let sid = sessionId else { return }
        _ = outbox.setState(id, .sending)
        Task {
            var ok = false
            if entry.receipts.isEmpty {
                ok = await session?.sendChatMessage(sessionId: sid, text: entry.text) ?? false
            } else {
                let requestJson = AttachmentMessages.promptRequest(
                    requestId: "req-\(UUID().uuidString)",
                    sessionId: sid,
                    receiptIds: entry.receipts,
                    text: entry.text
                )
                if let body = try? await session?.submitAttachmentMessage(endpoint: "session/prompt", requestJson: requestJson),
                   case .ok = AttachmentMessages.parseServerResponse(body) {
                    ok = true
                }
            }
            _ = outbox.setState(id, ok ? .sent : .failed)
        }
    }

    func deleteOutbox(_ id: Int64) {
        _ = outbox.remove(id)
    }

    /// 服务端条目到达后对账移除已确认的本地行。
    private func reconcileOutbox() {
        guard let botId else { return }
        let rows = userRows()
        _ = outbox.reconcile(chatKey(botId), userRows: rows)
    }

    /// 当前列表里的服务端用户行（order = seq）。
    private func userRows() -> [Outbox.UserRow] {
        items.compactMap { item in
            if case .message(let m) = item, m.fromUser {
                return Outbox.UserRow(order: m.seq, text: m.markdown)
            }
            return nil
        }
    }

    private func maxSeq() -> Int64 {
        items.reduce(Int64(0)) { acc, item in
            if case .message(let m) = item, let s = m.seq { return max(acc, s) }
            return acc
        }
    }

    // MARK: - feed 聊天事件（流式与定格）

    /// 加载窗口（sessionId 未定）丢弃的实时事件计数：首页落地后补刷。
    private var droppedDuringLoad = 0

    func handleChatEvent(_ event: ChatFeedEvent) {
        guard let botId else { return }
        guard let obj = Json.parse(event.data)?.objectValue else { return }
        let eventBotId = obj.nonEmptyString("botId")
        let eventSessionId = obj.nonEmptyString("sessionId")
        switch event.event {
        case "stream":
            // 流式按会话 gate（会话未定时丢弃并计数，首页落地后补刷）。
            guard let sid = eventSessionId else { return }
            guard sid == sessionId else {
                if sessionId == nil { droppedDuringLoad += 1 }
                return
            }
            let text = streamBuffer.onStream(
                botId: eventBotId,
                sessionId: eventSessionId,
                attemptId: obj.nonEmptyString("attemptId"),
                phase: obj.nonEmptyString("phase"),
                text: obj.string("text")
            )
            if let text {
                streamingText = text
                if case "end" = obj.nonEmptyString("phase") ?? "" {
                    settleStreamAsItem()
                }
            }
        case "message":
            // 用户行按会话 gate、Bot 行按 bot gate（与 Kotlin 对齐：会话切换窗口不丢 Bot 回复）。
            let isUserRow = obj["item"]?.objectValue?.string("kind") == "user"
            let belongs: Bool
            if isUserRow {
                belongs = eventSessionId != nil && eventSessionId == sessionId
            } else {
                belongs = eventBotId != nil && eventBotId == botId
            }
            guard belongs else {
                if sessionId == nil { droppedDuringLoad += 1 }
                return
            }
            if sessionId == nil, let sid = eventSessionId {
                // Bot 回复先到而首页在途：先认下会话，避免后续事件全丢。
                sessionId = sid
            }
            handleMessageRow(obj, for: botId)
        default:
            break
        }
    }

    /// stream end 定格：文本落为无序号条目，message 到达时经 upgrade 补序号。
    private func settleStreamAsItem() {
        guard let text = streamBuffer.settle(), !text.isEmpty else {
            streamingText = nil
            return
        }
        streamingText = nil
        switch ChatRender.appendBotMessage(items: items, text: text, seq: nil) {
        case .upgrade:
            break
        default:
            items.append(.message(ChatRender.Item.MessageData(fromUser: false, markdown: text, turn: nil, seq: nil)))
        }
        jump.onAppended(1)
        if let botId {
            cache.put(chatKey(botId), ChatCache<ChatRender.Item>.CachedEntry(items: items, cursor: earlierCursor, sessionId: sessionId))
        }
    }

    private func handleMessageRow(_ obj: JsonObject, for botId: String) {
        guard let raw = obj["item"], let rendered = ChatRender.renderItem(chatItemFrom(raw)) else { return }
        if case .message(let m) = rendered, !m.fromUser {
            _ = streamBuffer.settle()
            streamingText = nil
            switch ChatRender.appendBotMessage(items: items, text: m.markdown, seq: m.seq) {
            case .upgrade(let index, let upgraded):
                if items.indices.contains(index) {
                    items[index] = .message(upgraded)
                }
            default:
                // 同 seq 已在（重复事件）不重复追加。
                if case .message(let msg) = rendered, let seq = msg.seq,
                   items.contains(where: { if case .message(let e) = $0 { return e.seq == seq && e.seq != nil } ; return false }) {
                    return
                }
                items.append(rendered)
            }
            jump.onAppended(1)
        } else {
            streamingText = nil
            // 用户行：同 seq 去重（S7）。
            if case .message(let msg) = rendered, let seq = msg.seq,
               items.contains(where: { if case .message(let e) = $0 { return e.seq == seq && e.seq != nil } ; return false }) {
                return
            }
            items.append(rendered)
            jump.onAppended(1)
        }
        // 实时附带（图片/文件条目）。
        let extras = ChatRender.extras(chatItemFrom(raw))
        items.append(contentsOf: extras)
        reconcileOutbox()
        cache.put(chatKey(botId), ChatCache<ChatRender.Item>.CachedEntry(items: items, cursor: earlierCursor, sessionId: sessionId))
    }

    private func chatItemFrom(_ value: JsonValue) -> DsbotModels.ChatItem {
        DsbotModels.ChatItem(
            kind: value.objectValue?.string("kind"),
            text: value.objectValue?.string("text"),
            time: value.objectValue?.int64("time"),
            seq: value.objectValue?.int64("seq"),
            turn: value.objectValue?.int64("turn"),
            raw: value
        )
    }

    // MARK: - 动作（停止/恢复/分段/统计/记录）

    func stop() {
        guard let botId else { return }
        Task {
            if case .ok = await session?.chatAction(endpoint: "stop-member-work", payloadJson: BotChatApi.stopPayload(botId: botId)) {
                await session?.refreshState()
            } else {
                notice = "停止失败"
            }
        }
    }

    func resume() {
        guard let botId else { return }
        Task {
            if case .ok = await session?.chatAction(endpoint: "resume", payloadJson: BotChatApi.resumePayload(botId: botId)) {
                await session?.refreshState()
            } else {
                notice = "恢复失败"
            }
        }
    }

    /// 新开分段：缓存失效、在途读取作废，按新分段（chat 应答）重载。
    func newPart() {
        guard let botId else { return }
        Task {
            if case .ok = await session?.chatAction(endpoint: "bot-new-part", payloadJson: BotChatApi.newPartPayload(botId: botId)) {
                invalidateAndReload(botId: botId)
            } else {
                notice = "新开分段失败"
            }
        }
    }

    func openPart(_ part: String) {
        guard let botId else { return }
        Task {
            if case .ok = await session?.chatAction(endpoint: "bot-open-part", payloadJson: BotChatApi.openPartPayload(botId: botId, part: part)) {
                invalidateAndReload(botId: botId)
            } else {
                notice = "切换分段失败"
            }
        }
    }

    private func invalidateAndReload(botId: String) {
        cache.invalidate(chatKey(botId))
        readFlight.reset()
        items = []
        earlierCursor = nil
        sessionId = nil
        atEarliest = false
        loading = true
        refresh(jumpToBottomOnFirst: true)
    }

    func showStats() {
        guard let botId else { return }
        Task {
            let result = await session?.chatAction(endpoint: "run-stats", payloadJson: BotChatApi.runStatsPayload(botId: botId)) ?? .failure(status: nil, reason: .network)
            if case .ok(let value) = result {
                let text = BotStatsText.runStats(value)
                statsText = text.isEmpty ? "暂无统计数据" : text
            } else {
                statsText = "统计不可用"
            }
        }
    }

    func showExchanges() {
        Task {
            let result = await session?.chatAction(endpoint: "exchange", payloadJson: BotChatApi.exchangePayload) ?? .failure(status: nil, reason: .network)
            if case .ok(let value) = result {
                exchangesText = BotStatsText.exchanges(value)
            } else {
                exchangesText = "Bot 间记录不可用"
            }
        }
    }

    // MARK: - 滚动与回到最新

    var jumpLabel: String? { jump.label }

    func onScrollDistance(_ px: Double) {
        jump.onDistance(px)
    }

    func jumpToLatest(immediate: Bool = false) {
        jump.onJumped()
        jump.onJumpEnded()
        scrollTargetBottom = (scrollTargetBottom ?? 0) + 1
    }

    /// 底部锚点自增（视图 ScrollViewReader 监听后滚到底）。
    var scrollTargetBottom: Int?

    /// 启动预取（last-chat-prefetch）：只填缓存不开聊天。
    func prefetch(botId: String) async {
        guard cache.entry(chatKey(botId)) == nil else { return }
        guard let page = await session?.fetchChatPage(botId: botId) else { return }
        let rendered = renderPage(page)
        cache.put(chatKey(botId), ChatCache<ChatRender.Item>.CachedEntry(items: rendered, cursor: page.cursor, sessionId: page.sessionId))
    }

    /// 滚动位置记忆（chat-cache-fast-switch）：视图保存与恢复。
    func saveScroll(index: Int, offset: Int) {
        guard let botId else { return }
        scrollMemory.save(chatKey(botId), index: index, offset: offset)
    }

    func restoreScroll() -> ScrollMemory.Position? {
        guard let botId else { return nil }
        return scrollMemory.position(chatKey(botId))
    }

    // MARK: - 附件查看与阅读全文（macos-attachments-files）

    /// 打开中的附件（查看 sheet/Quick Look 载荷）。
    struct OpenedAttachment: Identifiable, Equatable {
        let id = UUID()
        let url: URL
        let name: String
        let isImage: Bool
    }

    private(set) var openedAttachment: OpenedAttachment?
    /// 阅读全文（file-text 或超长消息）。
    private(set) var fullText: String?

    /// 打开附件条目：经 session/attachment（attachmentRefJson / refJson 指明附件）取回字节，
    /// 落系统临时目录（文件名经 FileDownload.safeName 清洗），交系统打开（图片/Quick Look）。
    func openAttachment(name: String, attachmentRefJson: String?, presentedFile: DsbotModels.ChatItem?, isImage: Bool) {
        guard let session else {
            notice = "附件打开需要已连接的会话"
            return
        }
        // 引用来源：消息附件（attachmentRefJson / fileRefJson）。
        guard let sid = sessionId, let refJson = attachmentRefJson else {
            notice = "该附件缺少引用，暂不能打开"
            return
        }
        let requestJson = AttachmentMessages.attachmentRequest(sessionId: sid, attachmentJson: refJson)
        notice = "正在取回附件…"
        Task {
            do {
                let body = try await session.muxCall(endpoint: "session/attachment", requestJson: requestJson)
                guard case .ok(let valueJson)? = AttachmentMessages.parseServerResponse(body),
                      let value = valueJson.flatMap({ Json.parse($0) }),
                      // FINDINGS：value={attachment, data:base64}（data 在顶层）。
                      let b64 = value.objectValue?.string("data"),
                      let data = Data(base64Encoded: b64)
                else { throw TransportError(.other) }
                let safe = FileDownload.safeName(name)
                Self.clearAttachmentTempFiles()
                let url = FileManager.default.temporaryDirectory
                    .appendingPathComponent("dsh-\(safe)")
                try data.write(to: url, options: .atomic)
                openedAttachment = OpenedAttachment(url: url, name: safe, isImage: isImage)
                notice = nil
            } catch {
                notice = "附件取回失败，稍后重试"
            }
        }
    }

    /// Bot 交付文件（presented）：file-download 分块取回拼接（Assembler 校验）后打开。
    func openPresented(name: String, sessionId itemSid: String?, seq: Int64?, index: Int?) {
        guard let session else {
            notice = "文件打开需要已连接的会话"
            return
        }
        guard let itemSid, let seq, let index else {
            notice = "该文件条目缺少定位信息，暂不能打开"
            return
        }
        notice = "正在取回文件…"
        Task {
            await downloadPresented(session: session, itemSid: itemSid, seq: seq, index: index, name: name)
        }
    }

    private func downloadPresented(session: AppSessionModel, itemSid: String, seq: Int64, index: Int, name: String) async {
        do {
            let assembler = FileDownload.Assembler()
            var offset: Int64 = 0
            var version: String? = nil
            let safe = FileDownload.safeName(name)
            Self.clearAttachmentTempFiles()
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("dsh-\(safe)")
            FileManager.default.createFile(atPath: url.path, contents: nil)
            let handle = try FileHandle(forWritingTo: url)
            defer { try? handle.close() }
            while true {
                let payload = FileDownload.presented(sessionId: itemSid, seq: seq, index: index, offset: offset, version: version)
                let body = try await session.muxCall(endpoint: "file-download", requestJson: payload)
                guard case .ok(let valueJson)? = AttachmentMessages.parseServerResponse(body),
                      let chunk = FileDownload.decode(valueJson.flatMap({ Json.parse($0) }))
                else { throw TransportError(.other) }
                switch assembler.accept(chunk) {
                case .done:
                    try handle.write(contentsOf: chunk.bytes)
                    openedAttachment = OpenedAttachment(url: url, name: safe, isImage: false)
                    notice = nil
                    return
                case .next(let nextOffset, let nextVersion):
                    try handle.write(contentsOf: chunk.bytes)
                    offset = nextOffset
                    version = nextVersion
                case .fail:
                    notice = "文件在取回途中发生变化，已中止；请重试"
                    try? FileManager.default.removeItem(at: url)
                    return
                }
            }
        } catch {
            notice = "文件取回失败，稍后重试"
        }
    }

    /// 保存打开中的附件（NSSavePanel）。
    func saveOpenedAttachment(to target: URL) {
        guard let opened = openedAttachment,
              let data = try? Data(contentsOf: opened.url)
        else { return }
        try? data.write(to: target, options: .atomic)
    }

    /// 阅读全文（read-full-text）：入口仅对服务端标记 whole.file 的消息显示（视图按
    /// wholeFileJson 传参）；经 file-text 取回全文（whole=false 时提示截断）。
    func readFullText(fileJson: String?) {
        guard let session else {
            notice = "阅读全文需要已连接的会话"
            return
        }
        guard let fileJson else {
            notice = "该消息没有可阅读的全文"
            return
        }
        Task {
            do {
                let body = try await session.muxCall(endpoint: "file-text", requestJson: FileText.request(fileJson: fileJson))
                guard case .ok(let valueJson)? = AttachmentMessages.parseServerResponse(body),
                      let result = FileText.decode(valueJson.flatMap({ Json.parse($0) }))
                else { throw TransportError(.other) }
                fullText = result.whole ? result.text : result.text + "\n\n（内容较长，服务端已截断）"
            } catch {
                notice = "全文取回失败，稍后重试"
            }
        }
    }

    func dismissAttachment() { openedAttachment = nil }
    func dismissFullText() { fullText = nil }

    /// 清理附件临时文件（打开新附件前复用单槽位，不留堆积）。
    static func clearAttachmentTempFiles() {
        let fm = FileManager.default
        if let files = try? fm.contentsOfDirectory(atPath: fm.temporaryDirectory.path) {
            for f in files where f.hasPrefix("dsh-") {
                try? fm.removeItem(at: fm.temporaryDirectory.appendingPathComponent(f))
            }
        }
    }

    func dismissStats() { statsText = nil }
    func dismissExchanges() { exchangesText = nil }
    func dismissNotice() { notice = nil }
}
