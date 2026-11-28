import DshCore
import SwiftUI

/// 右侧检查器 Side chat 面板（macos-side-chat）：该 Bot 的旁路会话列表（问答对、进行中可取消）、
/// 底部提问输入（附最近消息快照）、标注「基于目前进度回答，未运行任何工具」、不接受附件提示。
struct SideChatPanelView: View {
    @Bindable var model: AppSessionModel
    let botId: String?

    private var side: SideChatModel { model.sideChat }

    @State private var questionText = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                Text(I18n.tr("Side chat", "Side chat"))
                    .font(.headline)
                Spacer()
                if side.hasWorking {
                    Button(I18n.tr("取消回答", "Cancel answer")) { side.cancel() }
                        .buttonStyle(.link)
                        .font(.caption)
                }
                Button(I18n.tr("新会话", "New")) { side.newSide() }
                    .buttonStyle(.link)
                    .font(.caption)
                Button(I18n.tr("关闭", "Close")) { side.close() }
                    .buttonStyle(.link)
                    .font(.caption)
            }
            .padding(10)
            Divider()
            if let notice = side.notice {
                HStack {
                    Text(notice).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
                    Spacer()
                    Button(I18n.tr("关闭", "Close")) { side.dismissNotice() }
                        .buttonStyle(.link).font(.caption2)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(Color.yellow.opacity(0.15))
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    if side.pendingQuestion != nil || side.pairs.isEmpty {
                        hintBlock
                    }
                    ForEach(side.pairs) { pair in
                        qaBlock(pair)
                    }
                }
                .padding(10)
            }
            Divider()
            VStack(alignment: .leading, spacing: 6) {
                TextField(I18n.tr("旁路提问（不打断 Bot 工作）…", "Ask on the side (won't interrupt work)…"), text: $questionText)
                    .onSubmit {
                        guard !side.asking else { return }
                        send()
                    }
                HStack {
                    Text(I18n.tr("基于目前进度回答，未运行任何工具；不接受附件", "Answers from current progress, no tools; attachments not supported"))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button(I18n.tr("提问", "Ask")) { send() }
                        .disabled(questionText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || side.asking)
                }
            }
            .padding(10)
        }
        .frame(minWidth: 230)
        .onAppear {
            if let botId { side.open(botId: botId) }
        }
        .onChange(of: botId) { _, new in
            if let new { side.open(botId: new) }
        }
    }

    private var hintBlock: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let pending = side.pendingQuestion {
                BubbleView(markdown: pending, fromUser: true)
                HStack(spacing: 6) {
                    ProgressView().controlSize(.small)
                    Text(I18n.tr("提问中…", "Asking…"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } else {
                Text(I18n.tr("旁路提问基于 Bot 目前进度回答，不运行工具、不打断它正在做的事。", "Side answers use the Bot's current progress without tools or interrupting its work."))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func qaBlock(_ pair: SideChatModel.QAPair) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            BubbleView(markdown: pair.question, fromUser: true)
            if pair.working {
                HStack(spacing: 6) {
                    ProgressView().controlSize(.small)
                    Text(I18n.tr("回答中…", "Answering…"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } else if let answer = pair.answer {
                BubbleView(markdown: answer, fromUser: false)
            }
        }
    }

    private func send() {
        let text = questionText
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        questionText = ""
        // 最近消息快照（私聊当前已加载行的尾部 5 条）。
        var recent: [(fromUser: Bool, text: String)] = []
        for item in model.chat.items.suffix(5) {
            if case .message(let m) = item {
                recent.append((m.fromUser, m.markdown))
            }
        }
        side.ask(text, recentMessages: recent)
    }
}
