import DshCore
import SwiftUI

/// 群聊内容区（macos-group-chat）：头部（群名、群公告、群目标条、运行成员与逐成员停止、
/// 停止全部、进度）、消息列表（成员名色、轮次事件系统行、[PASS] 不可见、分页与回到最新）、
/// 输入框上方群聊天条（群内后台任务条、电脑操作请求卡、成员失败修复卡）与群停靠区、
/// @ 成员补全与「引用一个聊天」文本标注输入框。复用私聊渲染组件（ChatItemView 族）。
struct RoomChatView: View {
    @Bindable var model: AppSessionModel
    let entry: TeamHome.Entry

    private var room: RoomChatModel { model.roomChat }

    // MARK: - @ 补全状态

    @State private var mentionQueryText: String? = nil
    @State private var mentionIndex = 0
    @State private var insertHandles: ComposerInsertHandles?

    private var memberNames: [String] {
        room.members.compactMap(\.name)
    }

    /// 当前 @ 词匹配的成员名（裸 @ 返回全量名册；前缀匹配保持名册顺序）。
    private var mentionCandidates: [String] {
        guard let query = mentionQueryText, !query.isEmpty, query.hasPrefix("@") else { return [] }
        let prefix = String(query.dropFirst()).lowercased()
        return memberNames.filter { prefix.isEmpty || $0.lowercased().hasPrefix(prefix) }
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            GeometryReader { geo in
                VStack(spacing: 0) {
                    messageList
                    roomBars
                    CardsDockView(model: model, entry: entry, availableHeight: geo.size.height, roomMode: true)
                    if let notice = room.notice ?? model.chat.notice {
                        noticeBar(notice)
                    }
                    if let progress = room.progressText {
                        noticeBar(progress)
                    }
                    composer
                }
            }
        }
    }

    // MARK: - 头部（公告 / 目标条 / 运行成员 / 停止 / 进度）

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text(entry.name)
                    .font(.headline)
                    .lineLimit(1)
                if !room.workingMembers.isEmpty {
                    Button(I18n.tr("停止全部", "Stop all")) { room.stopAll() }
                }
                Button(I18n.tr("进度", "Progress")) { room.showProgress() }
                Spacer()
            }
            if let announcement = room.announcement {
                Text(I18n.tr("公告：\(announcement)", "Announcement: \(announcement)"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            if let goal = room.goal {
                GoalBarView(goal: goal) { action in
                    room.goalControl(action)
                } onDraft: { text in
                    room.goalDraft(text)
                }
            }
            ForEach(room.workingMembers, id: \.speaker.botId) { member in
                HStack(spacing: 6) {
                    Circle()
                        .fill(dotColor(member.speaker.color))
                        .frame(width: 7, height: 7)
                    Text(member.speaker.name ?? "?")
                        .font(.callout.weight(.medium))
                    Text(member.phrase)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Spacer()
                    Button(I18n.tr("停止", "Stop")) {
                        if let botId = member.speaker.botId {
                            room.stopMember(botId: botId)
                        }
                    }
                    .buttonStyle(.link)
                    .font(.caption)
                }
            }
            if let hint = room.streamingHint {
                Text(hint)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }

    // MARK: - 消息列表（分页 + 回到最新；探针与私聊 ChatView 同型）

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    // 顶部：露出自动翻页（探针）+ 显式按钮兜底（短历史场景）。
                    GeometryReader { geo in
                        Color.clear.preference(
                            key: RoomTopVisibleKey.self,
                            value: geo.frame(in: .named("roomScroll")).minY > -8
                        )
                    }
                    .frame(height: 0)
                    if !room.atEarliest && room.earlierCursor != nil && !room.loadingEarlier {
                        Button(I18n.tr("更早的消息", "Earlier messages")) { room.loadEarlier() }
                            .buttonStyle(.link)
                            .font(.caption)
                            .frame(maxWidth: .infinity)
                    }
                    if room.loading && room.items.isEmpty {
                        ProgressView(I18n.tr("正在加载群聊…", "Loading the group chat…"))
                            .frame(maxWidth: .infinity)
                            .padding(.top, 32)
                    }
                    if room.loadingEarlier {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 4)
                    }
                    if room.atEarliest && !room.items.isEmpty {
                        Text(I18n.tr("已到最早", "Reached the beginning"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity)
                    }
                    ForEach(Array(room.items.enumerated()), id: \.offset) { index, item in
                        ChatItemView(item: item, model: model)
                            .id(stableId(item, fallback: index))
                    }
                    ForEach(room.outboxRows, id: \.id) { row in
                        OutboxRowView(
                            row: row,
                            onRetry: { room.retryOutbox($0) },
                            onDelete: { room.deleteOutbox($0) }
                        )
                    }
                    Color.clear.frame(height: 1).id("room-bottom")
                    // 末项底缘探针：喂离底距离（回到最新 / N 条新消息）。
                    GeometryReader { geo in
                        Color.clear.preference(
                            key: RoomBottomOffsetKey.self,
                            value: -geo.frame(in: .named("roomScroll")).maxY
                        )
                    }
                    .frame(height: 0)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .coordinateSpace(name: "roomScroll")
            .onPreferenceChange(RoomTopVisibleKey.self) { visible in
                if visible, !room.loading, !room.loadingEarlier, !room.atEarliest {
                    room.loadEarlier()
                }
            }
            .onPreferenceChange(RoomBottomOffsetKey.self) { below in
                room.onScrollDistance(below)
            }
            .onChange(of: room.scrollTargetBottom) {
                proxy.scrollTo("room-bottom", anchor: .bottom)
            }
            .onChange(of: room.items.count) { old, new in
                // 新增行计数（away 时显示 N 条新消息）；贴底跟随不打扰读历史。
                if new > old {
                    room.jump.onAppended(new - old)
                }
                if !room.jump.away, let last = room.items.indices.last {
                    proxy.scrollTo(stableId(room.items[last], fallback: last), anchor: .bottom)
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if let label = room.jumpLabel {
                    Button(label) {
                        room.jumpToLatest()
                    }
                    .padding(10)
                }
            }
            .onAppear {
                if !room.items.isEmpty {
                    proxy.scrollTo("room-bottom", anchor: .bottom)
                }
            }
        }
    }

    /// 行稳定 id（前插历史页不移位；与私聊 stableId 同型）。
    private func stableId(_ item: ChatRender.Item, fallback: Int) -> String {
        if case .message(let m) = item {
            if let seq = m.seq { return "m\(seq)" }
            return "l\(m.markdown.hashValue)"
        }
        return "x\(String(describing: item).hashValue)"
    }

    // MARK: - 群聊天条（群内任务条 / 电脑操作请求 / 成员失败修复卡）

    @ViewBuilder
    private var roomBars: some View {
        let state = model.snapshot.stateRaw
        let roomTasks = ChatBars.roomTasks(state, roomId: entry.id)
        if !roomTasks.isEmpty {
            RoomTaskBarView(rows: roomTasks, actions: model.barActions) { botId in
                room.stopMember(botId: botId)
            } nameOf: { botId in
                room.members.first { $0.botId == botId }?.name
            }
        }
        let asks = ChatBars.computerAsks(state, roomId: entry.id)
        if !asks.isEmpty {
            let idToName: (String) -> String? = { botId in
                room.members.first { $0.botId == botId }?.name
            }
            ComputerAskCardsView(asks: asks, actions: model.barActions, whoOf: idToName)
                .padding(.vertical, 4)
        }
        // 成员失败修复卡（resume 重试；服务商页未上线指引网页端）。
        ForEach(room.members, id: \.botId) { member in
            if let botId = member.botId, let repair = ChatBars.repair(state, botId) {
                RepairCardView(repair: repair, actions: model.barActions, botId: botId)
                    .padding(.vertical, 2)
            }
        }
    }

    // MARK: - 通知条

    private func noticeBar(_ text: String) -> some View {
        HStack {
            Text(text)
                .font(.caption)
                .lineLimit(2)
            Spacer()
            Button(I18n.tr("关闭", "Close")) {
                room.dismissNotices()
                model.chat.dismissNotice()
            }
            .buttonStyle(.link)
            .font(.caption2)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 5)
        .background(Color.yellow.opacity(0.15))
    }

    // MARK: - 输入框（@ 补全 + 引用）

    private var composer: some View {
        VStack(spacing: 4) {
            if !mentionCandidates.isEmpty {
                MentionOverlayView(
                    candidates: mentionCandidates,
                    selected: min(mentionIndex, mentionCandidates.count - 1),
                    onPick: { name in
                        insertHandles?.insertMention(name)
                    }
                )
                .padding(.horizontal, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(alignment: .bottom, spacing: 8) {
                referenceMenu
                ComposerTextEditor(
                    enterToSend: model.snapshot.enterToSend,
                    onSend: { text in
                        room.send(text)
                        return ""
                    },
                    mentionQuery: { query in
                        mentionQueryText = query
                        mentionIndex = 0
                    },
                    mentionMove: { delta in
                        let count = mentionCandidates.count
                        guard count > 0 else { return }
                        mentionIndex = ((mentionIndex + delta) % count + count) % count
                    },
                    mentionPick: {
                        mentionCandidates.isEmpty ? nil : mentionCandidates[min(mentionIndex, mentionCandidates.count - 1)]
                    },
                    mentionClose: {
                        mentionQueryText = nil
                    },
                    mentionActive: {
                        !mentionCandidates.isEmpty
                    },
                    onHandlesReady: { handles in
                        insertHandles = handles
                    }
                )
                .frame(height: 64)
                Button(I18n.tr("发送", "Send")) {
                    // 发送动作由 ComposerTextView 的通知路径触发（含 ⌘Enter）。
                    NotificationCenter.default.post(name: .composerSendNow, object: nil)
                }
                .controlSize(.large)
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
        }
    }

    /// 「引用一个聊天」（0.3.x 无结构化字段：以文本标注近似，插入「（引用了 X 的聊天）」）。
    private var referenceMenu: some View {
        Menu {
            ForEach(chatEntriesForReference, id: \.0) { id, name in
                Button(name) {
                    insertHandles?.insertAtCursor(I18n.tr("（引用了 \(name) 的聊天）", "(quoting \(name)'s chat)"))
                }
            }
        } label: {
            Image(systemName: "quote.opening")
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .padding(.bottom, 18)
    }

    /// 可引用的聊天（当前群除外的侧栏条目）。
    private var chatEntriesForReference: [(String, String)] {
        (model.snapshot.team?.sections.flatMap(\.entries) ?? [])
            .filter { $0.id != entry.id }
            .map { ($0.id, $0.name) }
    }

    private func dotColor(_ name: String?) -> Color {
        switch name {
        case "blue": return .blue
        case "green": return .green
        case "orange": return .orange
        case "red": return .red
        case "teal": return .teal
        case "violet": return .purple
        case "pink": return .pink
        case "yellow": return .yellow
        default: return .secondary
        }
    }
}

/// 群列表顶部探针（露出即翻页）。
private struct RoomTopVisibleKey: PreferenceKey {
    static let defaultValue = false
    static func reduce(value: inout Bool, nextValue: () -> Bool) { value = value || nextValue() }
}

/// 群列表末项离底距离探针（回到最新 / N 条新消息）。
private struct RoomBottomOffsetKey: PreferenceKey {
    static let defaultValue: Double = 0
    static func reduce(value: inout Double, nextValue: () -> Double) { value = min(value, nextValue()) }
}

/// @ 名册浮层（键盘驱动选中，点击也可插入）。
private struct MentionOverlayView: View {
    let candidates: [String]
    let selected: Int
    let onPick: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(candidates.enumerated()), id: \.offset) { index, name in
                Button {
                    onPick(name)
                } label: {
                    HStack(spacing: 6) {
                        Text("@\(name)")
                            .foregroundStyle(.primary)
                        Spacer()
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .background(index == selected ? Color.accentColor.opacity(0.18) : Color.clear)
            }
        }
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(Color(nsColor: .controlBackgroundColor))
                .shadow(radius: 3)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color.secondary.opacity(0.25))
        )
        .frame(maxWidth: 260)
    }
}

/// 群内后台任务条（每任务带归属成员名与逐任务停止 bot-stop-task）。
private struct RoomTaskBarView: View {
    let rows: [(task: ChatBars.Task, botId: String)]
    let actions: ChatBarActions
    let onStopMember: (String) -> Void
    let nameOf: (String) -> String?

    @State private var expanded = false

    var body: some View {
        VStack(spacing: 0) {
            Button {
                expanded.toggle()
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "checklist")
                        .foregroundStyle(.purple)
                    Text(I18n.tr("群内任务（\(rows.count)）", "Group tasks (\(rows.count))"))
                        .font(.callout.weight(.medium))
                    Spacer()
                    Image(systemName: expanded ? "chevron.down" : "chevron.right")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if expanded {
                Divider()
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(rows, id: \.task.id) { row in
                            HStack(spacing: 8) {
                                VStack(alignment: .leading, spacing: 1) {
                                    HStack(spacing: 4) {
                                        Text(nameOf(row.botId) ?? row.botId)
                                            .font(.caption2)
                                            .foregroundStyle(.secondary)
                                        Text(row.task.title)
                                            .font(.callout)
                                            .lineLimit(1)
                                            .truncationMode(.middle)
                                    }
                                    Text(ChatBars.taskStatusLabel(row.task.status))
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Button(I18n.tr("停止", "Stop")) {
                                    actions.act(
                                        key: "task:\(row.task.id)",
                                        endpoint: "bot-stop-task",
                                        payloadJson: "{\"botId\":\(JsonString.of(row.botId)),\"taskId\":\(JsonString.of(row.task.id))}"
                                    )
                                }
                                .disabled(actions.busy("task:\(row.task.id)"))
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            Divider()
                        }
                    }
                }
                .frame(maxHeight: 180)
                .scrollBounceBehavior(.basedOnSize)
            }
            Divider()
        }
    }
}

/// 群目标条（group-goal-bar）：进行中才显示；点按展开目标操作
///（drafting→确认草稿 confirm；active→推进下一阶段 advance——与网页端/Android 一致）。
struct GoalBarView: View {
    let goal: ChatBars.Goal
    let onControl: (String) -> Void
    let onDraft: (String) -> Void

    @State private var expanded = false
    @State private var draftText = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button {
                expanded.toggle()
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "flag")
                        .foregroundStyle(.teal)
                    VStack(alignment: .leading, spacing: 1) {
                        HStack(spacing: 6) {
                            Text(goal.title.isEmpty ? I18n.tr("群目标", "Group goal") : goal.title)
                                .font(.callout.weight(.medium))
                                .lineLimit(1)
                            Text(ChatBars.goalStatusLabel(goal.status))
                                .font(.caption2)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 1)
                                .background(Color.teal.opacity(0.14), in: Capsule())
                                .foregroundStyle(.teal)
                        }
                        if !goal.stages.isEmpty {
                            Text(I18n.tr(
                                "第 \(goal.current + 1)/\(goal.stages.count) 阶段 · \(goal.current >= 0 ? goal.stages[goal.current].title : "")",
                                "Stage \(goal.current + 1)/\(goal.stages.count) · \(goal.current >= 0 ? goal.stages[goal.current].title : "")"
                            ))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                        }
                    }
                    Spacer()
                    if !goal.stages.isEmpty {
                        ProgressView(value: Double(goal.passed), total: Double(goal.stages.count))
                            .frame(width: 90)
                    }
                    Image(systemName: expanded ? "chevron.down" : "chevron.right")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if expanded {
                Divider()
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array(goal.stages.enumerated()), id: \.offset) { index, stage in
                        HStack(spacing: 6) {
                            Image(systemName: stage.status == "passed" ? "checkmark.circle.fill" : (index == goal.current ? "arrow.right.circle" : "circle"))
                                .foregroundStyle(stage.status == "passed" ? Color.green : Color.secondary)
                            Text(stage.title)
                                .font(.caption)
                            Spacer()
                        }
                    }
                    HStack {
                        TextField(I18n.tr("目标草稿…", "Goal draft…"), text: $draftText)
                        Button(I18n.tr("提交草稿", "Submit draft")) {
                            let text = draftText.trimmingCharacters(in: .whitespacesAndNewlines)
                            guard !text.isEmpty else { return }
                            onDraft(String(text.prefix(2000)))
                            draftText = ""
                        }
                        .buttonStyle(.link)
                        .font(.caption)
                        if goal.status == "drafting" {
                            Button(I18n.tr("确认草稿", "Confirm draft")) { onControl("confirm") }
                                .buttonStyle(.link)
                                .font(.caption)
                        } else if goal.status == "active" {
                            Button(I18n.tr("推进下一阶段", "Advance stage")) { onControl("advance") }
                                .buttonStyle(.link)
                                .font(.caption)
                        }
                    }
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
            }
        }
        .background(Color.teal.opacity(0.06), in: RoundedRectangle(cornerRadius: 8))
    }
}
