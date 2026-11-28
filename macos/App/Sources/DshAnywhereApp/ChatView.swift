import DshCore
import SwiftUI
import UniformTypeIdentifiers

/// 私聊内容区（macos-bot-chat）：头部工具栏（停止/恢复/分段/统计/记录）、消息列表、
/// 发送箱行、流式气泡、输入框（Enter 按团队偏好）与回到最新按钮。
struct ChatView: View {
    @Bindable var model: AppSessionModel
    private let entry: TeamHome.Entry

    init(model: AppSessionModel, entry: TeamHome.Entry) {
        self.model = model
        self.entry = entry
    }

    private var chat: BotChatModel { model.chat }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            messageList
            if let notice = chat.notice {
                noticeBar(notice)
            }
            composer
        }
        .sheet(item: Binding<BotChatModel.OpenedAttachment?>(
            get: { chat.openedAttachment },
            set: { _, _ in chat.dismissAttachment() }
        )) { opened in
            AttachmentSheet(opened: opened, chat: chat)
        }
        .sheet(item: Binding<ChatFullTextShown?>(
            get: { chat.fullText.map { ChatFullTextShown(text: $0) } },
            set: { _, _ in chat.dismissFullText() }
        )) { shown in
            FullTextSheet(text: shown.text) { chat.dismissFullText() }
        }
    }

    // MARK: - 头部工具栏

    private var header: some View {
        HStack(spacing: 8) {
            Text(entry.name)
                .font(.headline)
                .lineLimit(1)
            if entry.working {
                Button("停止") { chat.stop() }
            } else if entryInterrupted {
                Button("恢复") { chat.resume() }
            }
            Menu("分段") {
                Button("新开分段") { chat.newPart() }
                if entry.parts.count > 1 {
                    Divider()
                    ForEach(entry.parts, id: \.self) { part in
                        Button(part == entry.sessionId ? "\(partPrefix(part)) · 当前" : partPrefix(part)) {
                            chat.openPart(part)
                        }
                    }
                }
            }
            .disabled(entry.isMain)
            Spacer()
            Button("统计") { chat.showStats() }
            Button("记录") { chat.showExchanges() }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    private var entryInterrupted: Bool {
        // 中断可恢复：不工作且有旧分段（会话曾在）——与 Android interrupted 对齐的近似信号。
        !entry.working && entry.parts.contains(entry.sessionId ?? "")
    }

    private func partPrefix(_ part: String) -> String {
        String(part.suffix(8))
    }

    // MARK: - 消息列表

    private var messageList: some View {
        ZStack(alignment: .bottomTrailing) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        // 顶部可见即触发更早分页（earlier-pagination）。
                        GeometryReader { geo in
                            Color.clear.preference(
                                key: ChatTopVisibleKey.self,
                                value: geo.frame(in: .named("chatScroll")).minY > -8
                            )
                        }
                        .frame(height: 0)
                        if chat.loading && chat.items.isEmpty {
                            ProgressView("正在加载聊天…")
                                .frame(maxWidth: .infinity)
                                .padding(.top, 32)
                        }
                        if chat.loadingEarlier {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 4)
                        }
                        if chat.atEarliest && !chat.items.isEmpty {
                            Text("已到最早")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity)
                        }
                        ForEach(Array(chat.items.enumerated()), id: \.offset) { index, item in
                            ChatItemView(item: item, model: model)
                                .id(stableId(item, fallback: index))
                        }
                        ForEach(chat.outboxRows, id: \.id) { row in
                            OutboxRowView(row: row, chat: chat)
                        }
                        if let streaming = chat.streamingText {
                            BubbleView(markdown: streaming, fromUser: false)
                                .id("streaming")
                        }
                        Color.clear.frame(height: 1).id("bottom")
                        // 末项底缘探针：喂离底距离（回到最新/N 条新消息）。
                        GeometryReader { geo in
                            Color.clear.preference(
                                key: ChatBottomOffsetKey.self,
                                value: -geo.frame(in: .named("chatScroll")).maxY
                            )
                        }
                        .frame(height: 0)
                    }
                    .padding(12)
                }
                .coordinateSpace(name: "chatScroll")
                .onDrop(of: [.fileURL], isTargeted: nil) { providers in
                    var accepted = false
                    for provider in providers {
                        _ = provider.loadDataRepresentation(for: .fileURL) { data, _ in
                            guard let data,
                                  let path = String(data: data, encoding: .utf8),
                                  let url = URL(string: path.replacingOccurrences(of: "file://", with: "")),
                                  let fileData = try? Data(contentsOf: url)
                            else { return }
                            let media = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
                            Task { @MainActor in
                                chat.addAttachment(name: url.lastPathComponent, mediaType: media, data: fileData)
                            }
                        }
                        accepted = true
                    }
                    return accepted
                }
                .onPreferenceChange(ChatTopVisibleKey.self) { visible in
                    if visible, !chat.loading, !chat.loadingEarlier, !chat.atEarliest {
                        chat.loadEarlier()
                    }
                }
                .onPreferenceChange(ChatBottomOffsetKey.self) { below in
                    chat.onScrollDistance(below)
                }
                .onChange(of: chat.scrollTargetBottom) { proxy.scrollTo("bottom", anchor: .bottom) }
                .onChange(of: chat.items.count) {
                    // 仅贴底（未 away）时跟随新消息，读历史不打扰（流式不跳动）。
                    if !chat.jump.away, chat.streamingText == nil, let last = chat.items.indices.last {
                        proxy.scrollTo(stableId(chat.items[last], fallback: last), anchor: .bottom)
                    }
                }
                .onChange(of: chat.streamingText) { _, text in
                    // 流式追加贴底跟随。
                    if text != nil, !chat.jump.away {
                        proxy.scrollTo("streaming", anchor: .bottom)
                    }
                }
                .onAppear {
                    // 滚动位置记忆恢复（chat-cache-fast-switch）。
                    if let pos = chat.restoreScroll(), chat.items.indices.contains(pos.index) {
                        proxy.scrollTo(stableId(chat.items[pos.index], fallback: pos.index), anchor: .top)
                    } else if chat.items.isEmpty == false || chat.streamingText != nil {
                        proxy.scrollTo("bottom", anchor: .bottom)
                    }
                }
                .onChange(of: chat.botId) {
                    if let pos = chat.restoreScroll(), chat.items.indices.contains(pos.index) {
                        proxy.scrollTo(stableId(chat.items[pos.index], fallback: pos.index), anchor: .top)
                    } else {
                        proxy.scrollTo("bottom", anchor: .bottom)
                    }
                }
            }
            if let label = chat.jumpLabel {
                Button(label) {
                    chat.jumpToLatest()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .padding(12)
            }
        }
    }

    /// 稳定行 id：消息用 seq/内容，附带用内容；前插更早记录不改变现有行 id。
    private func stableId(_ item: ChatRender.Item, fallback: Int) -> String {
        if case .message(let m) = item {
            if let seq = m.seq { return "m\(seq)" }
            return "l\(m.markdown.hashValue)"
        }
        return "x\(String(describing: item).hashValue)"
    }

    private func noticeBar(_ text: String) -> some View {
        HStack {
            Image(systemName: "info.circle")
            Text(text)
                .font(.caption)
            Spacer()
            Button("关闭") { chat.dismissNotice() }
                .buttonStyle(.borderless)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Color.orange.opacity(0.1))
    }

    // MARK: - 输入框

    private var composer: some View {
        VStack(spacing: 6) {
            if !chat.pendingAttachments.isEmpty {
                attachmentBar
            }
            HStack(alignment: .bottom, spacing: 8) {
                Button(action: chooseAttachment) { Image(systemName: "paperclip") }
                    .buttonStyle(.borderless)
                    .help("添加附件")
                ComposerTextEditor(
                    enterToSend: model.snapshot.enterToSend,
                    onSend: { text in
                        chat.send(text) ? "" : text
                    }
                )
                .frame(minHeight: 52, maxHeight: 120)
                .onPasteCommand(of: [.image]) { providers in
                    for provider in providers {
                        provider.loadDataRepresentation(for: UTType.image) { data, _ in
                            guard let data else { return }
                            let name = "clipboard.png"
                            Task { @MainActor in
                                chat.addAttachment(name: name, mediaType: "image/png", data: data)
                            }
                        }
                    }
                }
                Button("发送") {
                    NotificationCenter.default.post(name: .composerSendNow, object: nil)
                }
                .buttonStyle(.borderedProminent)
                .disabled(chat.sessionId == nil)
            }
        }
        .padding(12)
    }

    /// 附件栏：上传中、失败单件重试、就绪与移除。
    private var attachmentBar: some View {
        HStack(spacing: 8) {
            ForEach(chat.pendingAttachments) { att in
                HStack(spacing: 4) {
                    Image(systemName: att.isImage ? "photo" : "doc")
                    Text(att.name).lineLimit(1).frame(maxWidth: 110)
                    switch att.state {
                    case .idle, .uploading:
                        ProgressView().controlSize(.mini)
                    case .ready:
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                    case .failed:
                        Button("重试") { chat.uploadPending(att.id) }
                            .controlSize(.mini)
                            .buttonStyle(.link)
                    }
                    Button(action: { chat.removeAttachment(att.id) }) {
                        Image(systemName: "xmark.circle.fill")
                    }
                    .buttonStyle(.borderless)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(RoundedRectangle(cornerRadius: 6).fill(Color.secondary.opacity(0.1)))
            }
        }
    }

    private func chooseAttachment() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        if panel.runModal() == .OK {
            for url in panel.urls {
                guard let data = try? Data(contentsOf: url) else { continue }
                let media = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
                chat.addAttachment(name: url.lastPathComponent, mediaType: media, data: data)
            }
        }
    }
}

extension Notification.Name {
    static let composerSendNow = Notification.Name("composerSendNow")
}

/// 列表顶部是否可见（触发更早分页）。
private struct ChatTopVisibleKey: PreferenceKey {
    static let defaultValue = false
    static func reduce(value: inout Bool, nextValue: () -> Bool) { value = value || nextValue() }
}

/// 末项底缘离视口底的距离（喂回到最新按钮状态）。
private struct ChatBottomOffsetKey: PreferenceKey {
    static let defaultValue: Double = 0
    static func reduce(value: inout Double, nextValue: () -> Double) { value = min(value, nextValue()) }
}

/// 多行输入（NSTextView）：Enter 行为按 enterToSend（开：Enter 发送 ⌥Enter 换行；关反之）。
struct ComposerTextEditor: NSViewRepresentable {
    let enterToSend: Bool
    let onSend: (String) -> String

    func makeNSView(context: Context) -> ComposerTextView {
        let view = ComposerTextView()
        view.enterToSend = enterToSend
        view.onSend = onSend
        view.font = NSFont.systemFont(ofSize: NSFont.systemFontSize)
        view.isRichText = false
        view.allowsUndo = true
        view.textContainerInset = NSSize(width: 6, height: 8)
        view.backgroundColor = .controlBackgroundColor
        context.coordinator.observeSend(view)
        return view
    }

    func updateNSView(_ nsView: ComposerTextView, context: Context) {
        nsView.enterToSend = enterToSend
        if nsView.onSend == nil { nsView.onSend = onSend }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        var observer: NSObjectProtocol?
        private weak var view: ComposerTextView?
        func observeSend(_ view: ComposerTextView) {
            self.view = view
            observer = NotificationCenter.default.addObserver(
                forName: .composerSendNow, object: nil, queue: .main
            ) { [weak self] _ in
                self?.view?.sendIfAble()
            }
        }
        deinit {
            if let observer {
                NotificationCenter.default.removeObserver(observer)
            }
        }
    }
}

/// 发送键拦截的 NSTextView。
final class ComposerTextView: NSTextView, NSTextViewDelegate {
    var enterToSend = true
    var onSend: ((String) -> String)?

    override func awakeFromNib() {
        super.awakeFromNib()
        delegate = self
    }

    func sendIfAble() {
        guard let onSend else { return }
        let text = string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        string = onSend(text)
        needsDisplay = true
    }

    override func keyDown(with event: NSEvent) {
        // 输入法组合期间（标记文本存在）Enter 交给 IME 确认候选，不触发发送。
        if markedRange().location != NSNotFound {
            super.keyDown(with: event)
            return
        }
        // Enter / ⌥Enter：按偏好一个发送一个换行；⌘Enter 恒为发送；小键盘 Enter 同主键。
        if event.keyCode == 36 || event.keyCode == 76, !event.modifierFlags.contains(.shift) {
            let option = event.modifierFlags.contains(.option)
            let command = event.modifierFlags.contains(.command)
            let shouldSend = command || (enterToSend ? !option : option)
            if shouldSend {
                sendIfAble()
                return
            }
        }
        super.keyDown(with: event)
    }
}

/// 单条聊天条目。
struct ChatItemView: View {
    let item: ChatRender.Item
    let model: AppSessionModel

    var body: some View {
        switch item {
        case .message(let m):
            BubbleView(markdown: m.markdown, fromUser: m.fromUser, onReadFull: m.wholeFileJson != nil ? {
                model.chat.readFullText(fileJson: m.wholeFileJson)
            } : nil)
        case .toolCard(let name, let summary):
            ToolCardView(name: name, summary: summary)
        case .image(let image):
            Button {
                model.chat.openAttachment(name: image.name ?? "图片.png", attachmentRefJson: image.attachmentRefJson, presentedFile: nil, isImage: true)
            } label: {
                FileLikeEntryView(systemImage: "photo", name: image.name ?? image.ref ?? "图片", subtitle: image.attachmentRefJson != nil ? "点击查看" : "图片条目")
            }
            .buttonStyle(.plain)
            .disabled(image.attachmentRefJson == nil)
        case .systemLine(let text):
            if let text {
                Text(text)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
            }
        case .fileAttachment(let name, let refJson):
            Button {
                model.chat.openAttachment(name: name ?? "文件", attachmentRefJson: refJson, presentedFile: nil, isImage: false)
            } label: {
                FileLikeEntryView(systemImage: "doc", name: name ?? "文件", subtitle: "点击预览 / 保存")
            }
            .buttonStyle(.plain)
        case .presentedFile(let name, let sessionId, let seq, let index):
            Button {
                model.chat.openPresented(name: name ?? "文件", sessionId: sessionId, seq: seq, index: index)
            } label: {
                FileLikeEntryView(systemImage: "doc", name: name ?? "文件", subtitle: "点击取回 / 打开")
            }
            .buttonStyle(.plain)
        case .interactiveCard:
            // 交互卡由 macos-cards-dock 渲染。
            EmptyView()
        case .btw(let id, let text, let from):
            BtwRowView(id: id, text: text, from: from)
        }
    }
}

/// 消息气泡：Markdown 自绘 + 右键菜单（复制整条/选择文字就地）+ textSelection。
struct BubbleView: View {
    let markdown: String
    let fromUser: Bool
    var onReadFull: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            MarkdownView(markdown: markdown)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(fromUser ? Color.accentColor.opacity(0.16) : Color(nsColor: .controlBackgroundColor))
        )
        .frame(maxWidth: 560, alignment: fromUser ? .trailing : .leading)
        .textSelection(.enabled)
        .contextMenu {
            Button("复制整条消息") {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(markdown, forType: .string)
            }
            if let onReadFull {
                Button("阅读全文", action: onReadFull)
            }
        }
    }
}

/// Markdown 自绘（MarkdownParse 块）。
struct MarkdownView: View {
    let markdown: String

    var body: some View {
        let blocks = MarkdownParse.parseBlocks(markdown)
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                blockView(block)
            }
        }
    }

    @ViewBuilder
    private func blockView(_ block: MarkdownParse.MdBlock) -> some View {
        switch block {
        case .heading(let level, let parts):
            Text(inline(parts))
                .font(level <= 1 ? .title2.weight(.semibold) : level == 2 ? .title3.weight(.semibold) : .headline)
        case .paragraph(let parts):
            if !inline(parts).isEmpty {
                Text(inlineText(parts)).textSelection(.enabled)
            }
        case .bullet(let parts):
            listItem(marker: "•", parts: parts)
        case .ordered(let index, let parts):
            listItem(marker: "\(index).", parts: parts)
        case .task(let checked, let parts):
            listItem(marker: checked ? "☑" : "☐", parts: parts)
        case .code(let code):
            CodeBlockView(code: code)
        case .table(let header, let rows):
            TableBlockView(header: header, rows: rows)
        case .image(let alt, _):
            Text(alt).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func listItem(marker: String, parts: [MarkdownParse.InlinePart]) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text(marker).foregroundStyle(.secondary)
            Text(inlineText(parts)).textSelection(.enabled)
        }
    }

    private func inline(_ parts: [MarkdownParse.InlinePart]) -> String {
        MarkdownParse.plain(parts)
    }

    /// 行内富文本（链接经 openURL 环境分流：http(s) 走系统，其余路径链接交 App 打开）。
    private func inlineText(_ parts: [MarkdownParse.InlinePart]) -> AttributedString {
        var out = AttributedString()
        for part in parts {
            switch part.kind {
            case .plain:
                out += AttributedString(part.text)
            case .bold:
                var s = AttributedString(part.text)
                s.inlinePresentationIntent = .stronglyEmphasized
                out += s
            case .italic:
                var s = AttributedString(part.text)
                s.inlinePresentationIntent = .emphasized
                out += s
            case .code:
                var s = AttributedString(part.text)
                s.font = .system(.body, design: .monospaced)
                out += s
            case .link:
                var s = AttributedString(part.text.isEmpty ? (part.url ?? "") : part.text)
                if let url = part.url, let target = URL(string: url) {
                    s.link = target
                }
                out += s
            }
        }
        return out
    }
}

/// 代码块：等宽 + 复制按钮。
struct CodeBlockView: View {
    let code: String

    var body: some View {
        VStack(alignment: .trailing, spacing: 2) {
            Button {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(code, forType: .string)
            } label: {
                Label("复制", systemImage: "doc.on.doc")
                    .font(.caption2)
            }
            .buttonStyle(.borderless)
            .padding(.trailing, 6)
            ScrollView(.horizontal) {
                Text(code)
                    .font(.system(.callout, design: .monospaced))
                    .textSelection(.enabled)
            }
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 6).fill(Color.black.opacity(0.06)))
    }
}

/// 表格：横向滚动。
struct TableBlockView: View {
    let header: [[MarkdownParse.InlinePart]]
    let rows: [[[MarkdownParse.InlinePart]]]

    var body: some View {
        ScrollView(.horizontal) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 12) {
                    ForEach(Array(header.enumerated()), id: \.offset) { _, cell in
                        Text(MarkdownParse.plain(cell))
                            .font(.caption.weight(.semibold))
                            .frame(minWidth: 60, alignment: .leading)
                    }
                }
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    HStack(spacing: 12) {
                        ForEach(Array(row.enumerated()), id: \.offset) { _, cell in
                            Text(MarkdownParse.plain(cell))
                                .font(.caption)
                                .frame(minWidth: 60, alignment: .leading)
                        }
                    }
                }
            }
        }
    }
}

/// 工具调用折叠卡。
struct ToolCardView: View {
    let name: String?
    let summary: String?
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button {
                withAnimation { expanded.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: expanded ? "chevron.down" : "chevron.right")
                        .font(.caption2)
                    Text("工具\(name.map { " · \($0)" } ?? "")")
                        .font(.caption.weight(.medium))
                    if let summary, !expanded {
                        Text(summary)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
            }
            .buttonStyle(.borderless)
            if expanded, let summary {
                Text(summary)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 8).fill(Color.secondary.opacity(0.08)))
    }
}

/// 图片/文件条目（内容打开由附件里程碑承接）。
struct FileLikeEntryView: View {
    let systemImage: String
    let name: String
    let subtitle: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: systemImage)
                .foregroundStyle(.tint)
            VStack(alignment: .leading, spacing: 1) {
                Text(name).font(.callout.weight(.medium)).lineLimit(1)
                Text(subtitle).font(.caption2).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 8).fill(Color.secondary.opacity(0.08)))
    }
}

/// 发送箱行：发送中/已发送等待确认/未发送（重试一次与删除）。
struct OutboxRowView: View {
    let row: Outbox.Entry
    let chat: BotChatModel

    var body: some View {
        HStack(spacing: 8) {
            Text(row.display)
                .font(.callout)
                .lineLimit(3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .textSelection(.enabled)
            switch row.state {
            case .sending:
                ProgressView()
                    .controlSize(.small)
                Text("发送中")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            case .sent:
                Text("已发送，等待确认")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button("删除") { chat.deleteOutbox(row.id) }
                    .controlSize(.small)
            case .failed:
                Button("重试") { chat.retryOutbox(row.id) }
                    .controlSize(.small)
                    .buttonStyle(.link)
                Text("未发送")
                    .font(.caption)
                    .foregroundStyle(.red)
                Button("删除") { chat.deleteOutbox(row.id) }
                    .controlSize(.small)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(Color.accentColor.opacity(0.16))
        )
    }
}

/// 统计/记录弹层。
struct StatsSheet: View {
    let title: String
    let text: String
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(title).font(.headline)
                Spacer()
                Button("关闭", action: onDismiss)
            }
            ScrollView {
                Text(text)
                    .font(.callout.monospaced())
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(16)
        .frame(width: 460, height: 320)
    }
}


/// btw 行（浅色提示行）。
struct BtwRowView: View {
    let id: String?
    let text: String
    let from: String?

    var body: some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: "bubble.left")
                .font(.caption)
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 1) {
                if let from {
                    Text(from).font(.caption2).foregroundStyle(.secondary)
                }
                Text(text).font(.caption).textSelection(.enabled)
            }
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 8).fill(Color.secondary.opacity(0.06)))
    }
}


/// 附件查看 sheet：预览（图片直接显示 / 文件交系统打开）+ 保存面板。
struct AttachmentSheet: View {
    let opened: BotChatModel.OpenedAttachment
    let chat: BotChatModel
    @State private var image: NSImage?

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                Text(opened.name).font(.headline)
                Spacer()
                Button("保存…") { save() }
                Button("打开") { NSWorkspace.shared.open(opened.url) }
                Button("关闭") { chat.dismissAttachment() }
            }
            if let image {
                ScrollView([.horizontal, .vertical]) {
                    Image(nsImage: image).resizable().aspectRatio(contentMode: .fit)
                }
            } else {
                Text("文件已取回到本地，可预览或保存。")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .padding(14)
        .frame(width: 560, height: 420)
        .onAppear {
            if opened.isImage {
                image = NSImage(contentsOf: opened.url)
            }
        }
    }

    private func save() {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = opened.name
        if panel.runModal() == .OK, let url = panel.url {
            chat.saveOpenedAttachment(to: url)
        }
    }
}

/// 阅读全文 sheet：可选择文字的全文显示。
struct FullTextSheet: View {
    let text: String
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                Text("阅读全文").font(.headline)
                Spacer()
                Button("复制全文") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(text, forType: .string)
                }
                Button("关闭", action: onClose)
            }
            ScrollView {
                Text(text)
                    .font(.body)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(14)
        .frame(width: 560, height: 480)
    }
}

/// 阅读全文 sheet 的可识别载荷。
struct ChatFullTextShown: Identifiable {
    let id = UUID()
    let text: String
}
