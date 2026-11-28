import DshCore
import SwiftUI

/// 聊天条动作盒（macos-group-chat，dsbot-chat-bars-0310）：统一在途防重、成功后刷新 state、
/// 失败给 ChatBars.actionError 可读提示（群页给群通知条、私聊给聊天通知条）。
/// 动作经闭包注入（AppSessionModel 组装，避免模型反向持有）。
@MainActor
@Observable
final class ChatBarActions {
    private(set) var inFlight: Set<String> = []

    private(set) var notify: (String) -> Void
    private let perform: @Sendable (_ endpoint: String, _ payloadJson: String) async -> DsbotApi.Result
    private let refreshState: @Sendable () async -> Void

    init(
        notify: @escaping (String) -> Void,
        perform: @escaping @Sendable (_ endpoint: String, _ payloadJson: String) async -> DsbotApi.Result,
        refreshState: @escaping @Sendable () async -> Void
    ) {
        self.notify = notify
        self.perform = perform
        self.refreshState = refreshState
    }

    func busy(_ key: String) -> Bool {
        inFlight.contains(key)
    }

    /// 执行聊天条写操作；key 用于在途防重（作答后按钮立即禁用直到请求结束）。
    func act(key: String, endpoint: String, payloadJson: String, refresh: Bool = true) {
        guard !inFlight.contains(key) else { return }
        inFlight.insert(key)
        Task { [weak self] in
            guard let self else { return }
            let result = await self.perform(endpoint, payloadJson)
            self.inFlight.remove(key)
            switch result {
            case .ok:
                if refresh {
                    await self.refreshState()
                }
            case .botError(let code, _):
                self.notify(ChatBars.actionError(code))
            case .failure:
                self.notify(ChatBars.actionError(nil))
            }
        }
    }
}

// MARK: - 模型失败修复卡（私聊）

struct RepairCardView: View {
    let repair: ChatBars.Repair
    let actions: ChatBarActions
    let botId: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
                Text(repair.title).font(.callout.weight(.semibold))
                Spacer()
            }
            Text(repair.guidance)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Button(I18n.tr("重试", "Retry")) {
                    actions.act(key: "resume:\(botId)", endpoint: "resume", payloadJson: "{\"botId\":\(JsonString.of(botId))}")
                }
                .disabled(actions.busy("resume:\(botId)"))
                if repair.fixInProviders {
                    // 模型与服务商页尚未在 macOS 上线（macos-native-manage 承接）：指引网页端。
                    Button(I18n.tr("模型与服务商", "Models & providers")) {
                        actions.notify(I18n.tr("请在电脑上的网页端打开「模型与服务商」设置", "Open Models & providers in the web app on your computer"))
                    }
                    .buttonStyle(.link)
                    .font(.caption)
                }
                if repair.topUp {
                    Button(I18n.tr("去充值", "Top up")) {
                        if let url = URL(string: ChatBars.topUpURL) {
                            NSWorkspace.shared.open(url)
                        }
                    }
                    .buttonStyle(.link)
                    .font(.caption)
                }
            }
        }
        .padding(10)
        .background(Color.orange.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
        .padding(.horizontal, 12)
    }
}

// MARK: - 电脑操作请求卡（私聊与群；最多展开两张、其余计数、作答后补位）

struct ComputerAskCardsView: View {
    let asks: [ChatBars.ComputerAsk]
    let actions: ChatBarActions
    /// 群内显示发起成员名。
    var whoOf: (String) -> String? = { _ in nil }

    var body: some View {
        if !asks.isEmpty {
            VStack(spacing: 6) {
                ForEach(asks.prefix(2)) { ask in
                    ComputerAskRow(ask: ask, actions: actions, who: whoOf(ask.botId))
                }
                if asks.count > 2 {
                    Text(I18n.tr("还有 \(asks.count - 2) 个电脑操作请求", "\(asks.count - 2) more computer requests"))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.horizontal, 12)
        }
    }
}

private struct ComputerAskRow: View {
    let ask: ChatBars.ComputerAsk
    let actions: ChatBarActions
    let who: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 6) {
                Image(systemName: "desktopcomputer")
                    .foregroundStyle(.blue)
                Text(who.map { I18n.tr("\($0) 请求操作「\(ask.appName)」", "\($0) asks to use “\(ask.appName)”") }
                     ?? I18n.tr("请求操作电脑「\(ask.appName)」", "Requests the computer app “\(ask.appName)”"))
                    .font(.callout.weight(.medium))
                Spacer()
            }
            HStack {
                ForEach([("deny", I18n.tr("拒绝", "Deny")), ("once", I18n.tr("本次允许", "Allow once")), ("always", I18n.tr("总是允许", "Always allow"))], id: \.0) { answer, label in
                    Button(label) {
                        actions.act(
                            key: "computer:\(ask.id)",
                            endpoint: "computer-app-answer",
                            payloadJson: "{\"id\":\(JsonString.of(ask.id)),\"answer\":\"\(answer)\"}"
                        )
                    }
                    .disabled(actions.busy("computer:\(ask.id)"))
                }
            }
        }
        .padding(10)
        .background(Color.blue.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
    }
}

// MARK: - 停止操作电脑条

struct ComputerStopBarView: View {
    let actions: ChatBarActions
    let botId: String

    var body: some View {
        HStack {
            Button(I18n.tr("停止操作电脑", "Stop using the computer")) {
                actions.act(key: "computer-stop:\(botId)", endpoint: "computer-stop", payloadJson: "{\"botId\":\(JsonString.of(botId))}")
            }
            .disabled(actions.busy("computer-stop:\(botId)"))
            Spacer()
        }
        .padding(.horizontal, 12)
    }
}

// MARK: - 后台任务条（私聊；展开限高滚动、逐任务停止）

struct TaskBarView: View {
    let tasks: [ChatBars.Task]
    let actions: ChatBarActions
    let botId: String

    @State private var expanded = false

    var body: some View {
        if !tasks.isEmpty {
            VStack(spacing: 0) {
                Button {
                    expanded.toggle()
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "checklist")
                            .foregroundStyle(.purple)
                        Text(I18n.tr("后台任务（\(tasks.count)）", "Background tasks (\(tasks.count))"))
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
                            ForEach(tasks) { task in
                                TaskRow(task: task, actions: actions, botId: botId)
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
}

private struct TaskRow: View {
    let task: ChatBars.Task
    let actions: ChatBarActions
    let botId: String

    var body: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 1) {
                Text(task.title)
                    .font(.callout)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(ChatBars.taskStatusLabel(task.status))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button(I18n.tr("停止", "Stop")) {
                actions.act(
                    key: "task:\(task.id)",
                    endpoint: "bot-stop-task",
                    payloadJson: "{\"botId\":\(JsonString.of(botId)),\"taskId\":\(JsonString.of(task.id))}"
                )
            }
            .disabled(actions.busy("task:\(task.id)"))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }
}

extension ChatBars.Task: Identifiable {}
extension ChatBars.ComputerAsk: Identifiable {}
