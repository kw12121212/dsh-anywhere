import DshCore
import SwiftUI

/// 待处理停靠区（macos-cards-dock）：输入框上方的手风琴——行头（类型标签 + 标题 + 展开箭头），
/// 一次展开一张（默认首张、固定项不被新卡打断、作答移出展开下一张、可收起），高度上限可滚动，
/// 空时不显示。作答经 CardsDockModel（chatAction POST /api/bot），成功本地消隐并刷新 state。
struct CardsDockView: View {
    @Bindable var model: AppSessionModel
    let entry: TeamHome.Entry
    /// 聊天内容区可用高度（ChatView 外层量取），停靠区上限取其约一半。
    var availableHeight: CGFloat = 360

    private var dock: CardsDockModel { model.dock }
    private var items: [PendingDock.Item] { dock.items(entry: entry, chat: model.chat) }

    private var dockMaxHeight: CGFloat {
        min(max(availableHeight / 2, 120), 480)
    }

    var body: some View {
        if !items.isEmpty {
            VStack(spacing: 0) {
                Divider()
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(items, id: \.id) { item in
                            row(item)
                        }
                    }
                }
                .frame(maxHeight: dockMaxHeight)
                .scrollBounceBehavior(.basedOnSize)
                Divider()
            }
            .background(Color(nsColor: .controlBackgroundColor))
            .onAppear {
                dock.syncPinned(with: items)
            }
            .onChange(of: items.map(\.id)) {
                dock.syncPinned(with: items)
            }
        }
    }

    // MARK: - 行头与展开

    private func row(_ item: PendingDock.Item) -> some View {
        let expanded = dock.expandedId(of: items) == item.id
        return VStack(alignment: .leading, spacing: 0) {
            Button {
                dock.tapRow(itemId: item.id, in: items)
            } label: {
                HStack(spacing: 8) {
                    Text(item.kind.label)
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .background(kindTint(item.kind).opacity(0.14), in: Capsule())
                        .foregroundStyle(kindTint(item.kind))
                    Text(item.title)
                        .font(.callout.weight(.medium))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer()
                    if dock.answering.contains(item.id) {
                        ProgressView()
                            .controlSize(.small)
                    }
                    Text(expanded ? I18n.tr("收起", "Collapse") : I18n.tr("展开", "Expand"))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if expanded {
                Divider()
                body(item)
                    .padding(12)
            }
            Divider()
        }
    }

    private func kindTint(_ kind: PendingDock.Kind) -> Color {
        switch kind {
        case .learning: return .indigo
        case .approval: return .purple
        case .question: return .blue
        case .secret: return .orange
        case .connector: return .teal
        case .handoff: return .mint
        case .view: return .cyan
        }
    }

    // MARK: - 各卡内容

    @ViewBuilder
    private func body(_ item: PendingDock.Item) -> some View {
        switch item.payload {
        case .approval(let p):
            ApprovalBody(payload: p, answer: answer(item))
        case .question(let p):
            QuestionBody(payload: p, answer: answer(item))
        case .secret(let p):
            SecretBody(payload: p, busy: dock.answering.contains(item.id), answer: answer(item))
        case .view(let p):
            PendingViewCard(sessionId: p.sessionId, raw: p.raw, fallbackTitle: p.title, answer: answer(item))
        case .handoff(let p):
            HandoffBody(payload: p, answer: answer(item))
        case .learning(let proposal):
            LearningBody(proposal: proposal, answer: answer(item))
        case .connector(let request):
            ConnectorBody(request: request, answer: answer(item)) { hint in
                model.chat.postNotice(hint)
            }
        }
    }

    private func answer(_ item: PendingDock.Item) -> (String, String) -> Void {
        { endpoint, payload in
            dock.answer(itemId: item.id, endpoint: endpoint, payloadJson: payload)
        }
    }
}

// MARK: - 工具审批

private struct ApprovalBody: View {
    let payload: PendingDock.Payload.ApprovalEntry
    let answer: (String, String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(I18n.tr(
                "\(payload.taskTitle.map { "\($0) · " } ?? "")请求使用工具「\(payload.tool.isEmpty ? "?" : payload.tool)」",
                "\(payload.taskTitle.map { "\($0) · " } ?? "")Wants to use the tool “\(payload.tool.isEmpty ? "?" : payload.tool)”"
            ))
            .font(.callout.weight(.semibold))
            if let reason = payload.reason {
                Text(reason)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            HStack {
                Button(I18n.tr("允许", "Allow")) {
                    answer("approval-answer", PendingDock.Requests.approval(payload.id, true))
                }
                Button(I18n.tr("拒绝", "Deny")) {
                    answer("approval-answer", PendingDock.Requests.approval(payload.id, false))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - 提问（单选即交 / 多选与自填提交 / 可忽略）

private struct QuestionBody: View {
    let payload: PendingDock.Payload.Question
    let answer: (String, String) -> Void

    @State private var picked: Set<String> = []
    @State private var custom = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(payload.question).font(.callout.weight(.semibold))
            if let detail = payload.detail {
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
            ForEach(payload.options, id: \.label) { opt in
                let on = picked.contains(opt.label)
                Button {
                    if payload.multiSelect {
                        if on {
                            picked.remove(opt.label)
                        } else {
                            picked.insert(opt.label)
                        }
                    } else {
                        answer("answer-question", PendingDock.Requests.answerQuestion(
                            sessionId: payload.sessionId, questionId: payload.id, selected: [opt.label]
                        ))
                    }
                } label: {
                    HStack(alignment: .top, spacing: 6) {
                        Image(systemName: payload.multiSelect ? (on ? "checkmark.square.fill" : "square") : "circle")
                            .foregroundStyle(payload.multiSelect && on ? Color.accentColor : Color.secondary)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(opt.label).foregroundStyle(.primary)
                            if let d = opt.description {
                                Text(d).font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                    }
                }
                .buttonStyle(.plain)
            }
            if payload.allowCustom {
                TextField(I18n.tr("自己填写", "Your own answer"), text: $custom)
                    .onChange(of: custom) { _, newValue in
                        if newValue.count > 2000 { custom = String(newValue.prefix(2000)) }
                    }
            }
            HStack {
                if payload.multiSelect || payload.allowCustom {
                    Button(I18n.tr("提交", "Submit")) {
                        answer("answer-question", PendingDock.Requests.answerQuestion(
                            sessionId: payload.sessionId, questionId: payload.id,
                            selected: payload.options.map(\.label).filter { picked.contains($0) },
                            custom: custom
                        ))
                    }
                    .disabled(picked.isEmpty && custom.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                Button(I18n.tr("忽略", "Ignore")) {
                    answer("dismiss-question", PendingDock.Requests.dismissQuestion(payload.sessionId))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - 密钥请求（SecureField 不回显、不进日志与诊断）

private struct SecretBody: View {
    let payload: PendingDock.Payload.SecretEntry
    let busy: Bool
    let answer: (String, String) -> Void

    @State private var value = ""

    /// 与密钥设置页同口径校验：trim 后 4–16384 字符（SecretsSettings.valueProblem）。
    private var problem: Bool {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.count < 4 || trimmed.count > 16384
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(I18n.tr("请求密钥「\(payload.name)」", "Requests the key “\(payload.name)”"))
                .font(.callout.weight(.semibold))
            if let purpose = payload.purpose {
                Text(I18n.tr("用途：\(purpose)", "Purpose: \(purpose)"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if payload.kind != "allow" {
                SecureField(I18n.tr("输入密钥值（不回显，至少 4 个字符）", "Enter the key (hidden, at least 4 characters)"), text: $value)
                    .disabled(busy)
            }
            HStack {
                if payload.kind == "allow" {
                    Button(I18n.tr("允许使用已保存的", "Allow the saved key")) {
                        answer("secret-allow", PendingDock.Requests.secretAllow(payload.id))
                    }
                    .disabled(busy)
                } else {
                    Button(busy ? I18n.tr("提交中…", "Submitting…") : I18n.tr("提交", "Submit")) {
                        answer("secret-set", PendingDock.Requests.secretSet(
                            requestId: payload.id,
                            value: value.trimmingCharacters(in: .whitespacesAndNewlines)
                        ))
                    }
                    .disabled(busy || problem)
                }
                Button(I18n.tr("取消", "Cancel")) {
                    answer("secret-cancel", PendingDock.Requests.secretCancel(payload.id))
                }
                .disabled(busy)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - 浏览器交接（原生端无浏览器通道：接管按降级规则提示，已完成/跳过照常）

private struct HandoffBody: View {
    let payload: PendingDock.Payload.HandoffEntry
    let answer: (String, String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(payload.note ?? I18n.tr("Bot 请你在浏览器里完成一步操作", "The Bot asks you to finish a step in the browser"))
                .font(.callout.weight(.semibold))
            Text(I18n.tr("接管浏览器需在桌面应用中操作，这里可直接反馈结果", "Taking over the browser requires the desktop app; you can report the result here"))
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack {
                Button(I18n.tr("已完成", "Done")) {
                    answer("browser-give-back", PendingDock.Requests.giveBack(botId: payload.botId, callId: payload.callId, done: true))
                }
                Button(I18n.tr("跳过", "Skip")) {
                    answer("browser-give-back", PendingDock.Requests.giveBack(botId: payload.botId, callId: payload.callId, done: false))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - 学习审批（逐行差异折叠）

private struct LearningBody: View {
    let proposal: LearningProposal
    let answer: (String, String) -> Void

    @State private var showAll = false

    private var heading: String {
        if proposal.kind == "agents" {
            return I18n.tr("修改 \(proposal.path ?? "AGENTS.md")", "Edit \(proposal.path ?? "AGENTS.md")")
        }
        if proposal.action == "update" {
            return I18n.tr("更新技能「\(proposal.name ?? "?")」", "Update skill “\(proposal.name ?? "?")”")
        }
        return I18n.tr("新建技能「\(proposal.name ?? "?")」", "New skill “\(proposal.name ?? "?")”")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(heading).font(.callout.weight(.semibold))
            if let d = proposal.description, !d.isEmpty {
                Text(d).font(.caption).foregroundStyle(.secondary)
            }
            LearningReasons(proposal: proposal)
            LearningDiffLines(lines: proposal.lines(), showAll: showAll) {
                showAll = true
            }
            HStack {
                Button(I18n.tr("同意", "Approve")) {
                    answer("learning-answer", PendingDock.Requests.learning(proposal.id, true))
                }
                Button(I18n.tr("拒绝", "Deny")) {
                    answer("learning-answer", PendingDock.Requests.learning(proposal.id, false))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func background(_ op: LineDiff.Op) -> Color {
        switch op {
        case .add: return .green.opacity(0.12)
        case .del: return .red.opacity(0.12)
        case .same: return .clear
        }
    }
}

/// 学习提案理由（技能在顶层、AGENTS.md 在每条修改上；去重保序）。
private struct LearningReasons: View {
    let proposal: LearningProposal

    private var distinct: [String] {
        let raw = ([proposal.why].compactMap { $0 } + proposal.edits.compactMap(\.why))
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        var seen = Set<String>()
        return raw.filter { seen.insert($0).inserted }
    }

    var body: some View {
        ForEach(distinct, id: \.self) { r in
            Text(I18n.tr("理由：\(r)", "Why: \(r)")).font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// 逐行差异（新增绿/删除红，超过 8 行折叠可展开）。
private struct LearningDiffLines: View {
    let lines: [LineDiff.Line]
    let showAll: Bool
    let expand: () -> Void

    private var folded: LineDiff.Folded {
        showAll ? LineDiff.Folded(shown: lines, hidden: 0) : LineDiff.fold(lines)
    }

    var body: some View {
        let current = folded
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(current.shown.enumerated()), id: \.offset) { _, line in
                diffRow(line)
            }
            if current.hidden > 0 {
                Button(I18n.tr("展开其余 \(current.hidden) 行", "Show \(current.hidden) more lines"), action: expand)
                    .buttonStyle(.link)
                    .font(.caption)
            }
        }
        .overlay(
            RoundedRectangle(cornerRadius: 4)
                .stroke(Color.secondary.opacity(0.2))
        )
    }

    @ViewBuilder
    private func diffRow(_ line: LineDiff.Line) -> some View {
        let prefix = line.op == .add ? "+ " : (line.op == .del ? "- " : "  ")
        HStack(alignment: .top, spacing: 0) {
            Text(prefix)
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(.secondary)
            Text(line.text)
                .font(.system(size: 11, design: .monospaced))
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 1)
        .background(rowBackground(line.op))
    }

    private func rowBackground(_ op: LineDiff.Op) -> Color {
        switch op {
        case .add: return .green.opacity(0.12)
        case .del: return .red.opacity(0.12)
        case .same: return .clear
        }
    }
}

// MARK: - 连接器请求（取消即拒绝；去连接提示到网页端）

private struct ConnectorBody: View {
    let request: ConnectorRequest
    let answer: (String, String) -> Void
    let notify: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(request.name ?? request.connectorId ?? I18n.tr("连接器", "Connector"))
                .font(.callout.weight(.semibold))
            if let purpose = request.purpose {
                Text(I18n.tr("用途：\(purpose)", "Purpose: \(purpose)"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            HStack {
                Button(I18n.tr("取消请求", "Cancel request")) {
                    answer("connector-request-cancel", PendingDock.Requests.connectorCancel(request.id))
                }
                Button(I18n.tr("去连接", "Connect")) {
                    notify(I18n.tr("请在电脑上的网页端完成连接器连接", "Connect the connector in the web app on your computer"))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - show_view 卡（解码成功原生绘制；畸形/超限降级只读摘要）

private struct PendingViewCard: View {
    let sessionId: String
    let raw: JsonObject?
    let fallbackTitle: String?
    let answer: (String, String) -> Void

    /// decode 结果按原始项缓存（大卡 + 公式校验，避免流式期间每帧重算）。
    @State private var decoded: ViewCard.Decoded?

    var body: some View {
        let result = decoded ?? {
            let fresh = ViewCard.decode(sessionId, raw.map(JsonValue.object))
            Task { @MainActor in decoded = fresh }
            return fresh
        }()
        switch result {
        case .ok(let blueprint):
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    if let title = blueprint.title {
                        Text(title).font(.callout.weight(.semibold))
                    }
                    Spacer()
                    Button(I18n.tr("关闭", "Close")) {
                        answer("dismiss-view", PendingDock.Requests.dismissView(sessionId))
                    }
                    .buttonStyle(.link)
                    .font(.caption)
                }
                ViewCardRender(blueprint: blueprint) { payload in
                    answer("view-reply", payload)
                }
            }
        case .invalid(_, _, let title, let reason):
            VStack(alignment: .leading, spacing: 8) {
                Text(title ?? fallbackTitle ?? I18n.tr("卡片", "Card"))
                    .font(.callout.weight(.semibold))
                if let summary = raw?.object("view").map({ ViewCard.summary($0) }), !summary.isEmpty {
                    Text(summary)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
                Text(I18n.tr("只读显示：\(reason)", "Read-only: \(reason)"))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Button(I18n.tr("关闭", "Close")) {
                    answer("dismiss-view", PendingDock.Requests.dismissView(sessionId))
                }
                .buttonStyle(.link)
                .font(.caption)
            }
        }
    }
}
