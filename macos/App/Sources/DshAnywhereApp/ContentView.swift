import AppKit
import DshCore
import SwiftUI

@MainActor
private final class MainWindowTrackingView: NSView {
    var onWindowChange: (@MainActor (NSWindow?) -> Void)?

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        onWindowChange?(window)
    }
}

/// 把主窗口的 NSWindow 挂载事件接到 `WindowChromeModel`（几何恢复与持久化）。
private struct MainWindowAccessor: NSViewRepresentable {
    let chrome: WindowChromeModel

    func makeNSView(context: Context) -> MainWindowTrackingView {
        let view = MainWindowTrackingView()
        view.onWindowChange = { [chrome] win in
            chrome.windowDidChange(win)
        }
        return view
    }

    func updateNSView(_ nsView: MainWindowTrackingView, context: Context) {
        nsView.onWindowChange = { [chrome] win in
            chrome.windowDidChange(win)
        }
        if let win = nsView.window {
            chrome.windowDidChange(win)
        }
    }
}

/// 侧栏列宽观测：NavigationSplitView 无公开列宽读取 API，经侧栏内容的实际布局宽度
/// 观测（背景 GeometryReader），只更新 `liveSidebarWidth` 供下次持久化使用。
private struct SidebarWidthReader: View {
    let onWidthChange: (Double) -> Void

    var body: some View {
        GeometryReader { geo in
            Color.clear.onChange(of: geo.size.width) { _, newWidth in
                onWidthChange(newWidth)
            }
        }
    }
}

struct ContentView: View {
    @Bindable var model: AppSessionModel
    @Bindable var chrome: WindowChromeModel
    @Environment(\.openSettings) private var openSettings

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
    }

    var body: some View {
        VStack(spacing: 0) {
            topStatusBar
            Divider()
            if let banner = model.snapshot.noticeBanner {
                noticeBannerView(banner)
                Divider()
            }
            if let banner = model.snapshot.versionBanner, !model.snapshot.versionBannerDismissed {
                versionBannerView(banner)
                Divider()
            }
            mainContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(minWidth: 560, minHeight: 380)
        .background(MainWindowAccessor(chrome: chrome))
        .task {
            model.startIfNeeded()
        }
    }

    /// 应用内提醒横幅（in-app-notice-banner）：点按进入对应聊天，约 8 秒未点按自动收起
    ///（新提醒换 id 重新计时）。
    private func noticeBannerView(_ banner: NoticeBanner) -> some View {
        NoticeBannerView(text: banner.text) {
            model.selectChatById(banner.target)
            model.dismissNotice(id: banner.id)
        }
        .task(id: banner.id) {
            try? await Task.sleep(for: .seconds(8))
            model.dismissNotice(id: banner.id)
        }
    }

    private var topStatusBar: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(statusColor(model.snapshot.status))
                .frame(width: 9, height: 9)
            Text(ConnectionCopy.statusText(model.snapshot.status))
                .font(.subheadline.weight(.medium))
            Text("· 版本 \(appVersion)")
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
            if model.snapshot.cleanUrl != nil {
                Button("刷新") {
                    model.retry()
                }
                .disabled(model.snapshot.isSubmitting)
            }
            Button("设置…") {
                openSettings()
            }
            .disabled(model.snapshot.isSubmitting)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private func versionBannerView(_ banner: DsbotVersions.Banner) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
            Text("远端版本（DS-Bot \(banner.dsBot) / dsh \(banner.dsh)）与已测基准不一致，部分功能可能受限，仍可继续使用")
                .font(.callout)
            Spacer()
            Button("关闭") {
                model.dismissVersionBanner()
            }
            .buttonStyle(.borderless)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Color.orange.opacity(0.1))
    }

    @ViewBuilder
    private var mainContent: some View {
        switch model.snapshot.screen {
        case .setup(let invalidConfig):
            firstRunSetupView(invalidConfig: invalidConfig)
        case .connecting:
            VStack(spacing: 12) {
                ProgressView()
                Text("正在连接…")
                    .font(.headline)
                if let cleanUrl = model.snapshot.cleanUrl {
                    Text(cleanUrl)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(32)
        case .connected:
            // 三栏外壳（macos-shell-window）：侧栏与检查器为占位，内容区由
            // macos-native-chat 填充；其余界面保持全宽单页。
            shellView
        case .error(let reason):
            errorView(reason: reason)
        case .authExpired:
            reauthView
        case .forbiddenHost:
            forbiddenHostView
        }
    }

    // MARK: - 三栏外壳（macos-shell-window）

    private var shellView: some View {
        NavigationSplitView(columnVisibility: $chrome.sidebarVisibility) {
            TeamSidebarView(model: model)
                .navigationSplitViewColumnWidth(
                    min: MacosWindowState.minSidebarWidth,
                    ideal: chrome.restoredSidebarWidth,
                    max: MacosWindowState.maxSidebarWidth
                )
                .background(
                    SidebarWidthReader { newWidth in
                        chrome.updateLiveSidebarWidth(newWidth)
                    }
                )
        } detail: {
            chatContentPlaceholder
        }
        .inspector(isPresented: $chrome.inspectorVisible) {
            inspectorPlaceholder
        }
    }

    private enum StatsShown: Identifiable {
        case stats(String)
        case exchanges(String)

        var id: String {
            switch self {
            case .stats: return "stats"
            case .exchanges: return "exchanges"
            }
        }

        var title: String {
            switch self {
            case .stats: return "运行统计"
            case .exchanges: return "Bot 间消息记录"
            }
        }

        var text: String {
            switch self {
            case .stats(let t): return t
            case .exchanges(let t): return t
            }
        }
    }

    private var statsSheetPresented: StatsShown? {
        if let t = model.chat.statsText { return .stats(t) }
        if let t = model.chat.exchangesText { return .exchanges(t) }
        return nil
    }

    /// 当前选中的条目（侧栏选中后内容区占位显示其名称）。
    private var selectedEntry: TeamHome.Entry? {
        guard let team = model.snapshot.team, let id = model.sidebar.selection else { return nil }
        return team.sections.flatMap(\.entries).first { $0.id == id }
    }

    /// 聊天内容区（macos-bot-chat 已填私聊；群聊由 macos-group-chat 承接）。
    @ViewBuilder
    private var chatContentPlaceholder: some View {
        if let entry = selectedEntry, entry.kind == .bot, model.chat.botId == entry.id {
            ChatView(model: model, chrome: chrome, entry: entry)
                .sheet(item: Binding(
                    get: { statsSheetPresented },
                    set: { newValue in
                        // 关闭（含 Esc/系统手势）时清模型状态，保证可再次打开。
                        if newValue == nil {
                            model.chat.dismissStats()
                            model.chat.dismissExchanges()
                        }
                    }
                )) { shown in
                    StatsSheet(title: shown.title, text: shown.text) {
                        model.chat.dismissStats()
                        model.chat.dismissExchanges()
                    }
                }
        } else if let entry = selectedEntry, entry.kind == .room, model.roomChat.roomId == entry.id {
            RoomChatView(model: model, entry: entry)
        } else if let entry = selectedEntry {
            VStack(spacing: 12) {
                Image(systemName: "bubble.left.and.bubble.right")
                    .font(.system(size: 32))
                    .foregroundStyle(.secondary)
                Text(entry.name)
                    .font(.title2.weight(.semibold))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(32)
        } else {
            VStack(spacing: 12) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 36))
                    .foregroundStyle(.green)
                Text(AppIdentity.displayName)
                    .font(.title2.weight(.semibold))
                if let cleanUrl = model.snapshot.cleanUrl {
                    Text(cleanUrl)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                Text("会话已建立，SSE 实时同步在线（revision \(model.snapshot.stateRevision ?? "-") · Bot \(model.snapshot.botCount) · 群聊 \(model.snapshot.roomCount)）")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(32)
        }
    }

    /// 右侧检查器：Side chat 面板（macos-side-chat；私聊选中时可用，否则提示）。
    @ViewBuilder
    private var inspectorPlaceholder: some View {
        if let entry = selectedEntry, entry.kind == .bot {
            SideChatPanelView(model: model, botId: entry.id)
        } else {
            VStack(spacing: 10) {
                Text("详情")
                    .font(.headline)
                Text(I18n.tr("选中一个 Bot 的私聊后可在此旁路提问", "Select a Bot chat to ask on the side here"))
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(20)
        }
    }

    private func firstRunSetupView(invalidConfig: Bool) -> some View {
        let liveError = HarnessUrl.errorCategory(model.urlInput).map(ConnectionCopy.urlErrorText)
        let isValidInput = liveError == nil

        return VStack(alignment: .leading, spacing: 14) {
            Text("欢迎使用 \(AppIdentity.displayName)")
                .font(.title2.weight(.semibold))

            if invalidConfig {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                    Text("已有配置无效或已损坏，请重新填写地址。")
                        .font(.callout)
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
            }

            Text("请粘贴在 DSH 主机上运行 dsh-url 或 dsh web 输出的地址，系统会仅保存去掉 token 的干净地址。")
                .font(.callout)
                .foregroundStyle(.secondary)

            TextField("粘贴 dsh-url 或 dsh web 输出的地址", text: $model.urlInput)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    if isValidInput && !model.snapshot.isSubmitting {
                        model.submitUrl()
                    }
                }

            if !model.urlInput.isEmpty, let liveError {
                Text(liveError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
            if let formError = model.snapshot.formError {
                Text(formError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            HStack {
                Button(model.snapshot.isSubmitting ? "正在连接…" : "开始使用") {
                    model.submitUrl()
                }
                .buttonStyle(.borderedProminent)
                .disabled(!isValidInput || model.snapshot.isSubmitting)
            }
        }
        .frame(maxWidth: 460, alignment: .leading)
        .padding(32)
    }

    private func errorView(reason: ProbeFailureReason) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 36))
                .foregroundStyle(.orange)
            Text(ConnectionCopy.probeReasonTitle(reason))
                .font(.title3.weight(.semibold))
            if let cleanUrl = model.snapshot.cleanUrl {
                Text(cleanUrl)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            if let hint = model.snapshot.errorHint {
                Text(hint)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            HStack(spacing: 12) {
                Button("重试") {
                    model.retry()
                }
                .buttonStyle(.borderedProminent)
                Button("修改地址") {
                    openSettings()
                }
            }
        }
        .frame(maxWidth: 460)
        .padding(32)
    }

    private var reauthView: some View {
        let liveError = HarnessUrl.errorCategory(model.urlInput).map(ConnectionCopy.urlErrorText)
        let isValidInput = liveError == nil

        return VStack(alignment: .leading, spacing: 14) {
            Text("需要重新认证")
                .font(.title3.weight(.semibold))

            if model.snapshot.migratedFromElectron {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "arrow.triangle.2.circlepath.circle.fill")
                        .foregroundStyle(.blue)
                    Text("已从原版 \(AppIdentity.displayName) 自动带入连接地址\(model.snapshot.migratedDiagnosticsEndpoint ? "与诊断上报端点" : "")。出于安全隔离，会话凭据未迁移，请粘贴新的 dsh-url 地址完成重新认证。")
                        .font(.callout)
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.blue.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
            }

            if let cleanUrl = model.snapshot.cleanUrl {
                Text("当前地址：\(cleanUrl)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Text("当前 dsh 会话已失效或未建立。请在 DSH 主机上运行 dsh-url 获取新的带 token 地址并粘贴到下方，无需重启即可恢复。")
                .font(.callout)
                .foregroundStyle(.secondary)

            TextField("粘贴 dsh-url 输出的新地址", text: $model.urlInput)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    if isValidInput && !model.snapshot.isSubmitting {
                        model.submitUrl()
                    }
                }

            if !model.urlInput.isEmpty, let liveError {
                Text(liveError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
            if let formError = model.snapshot.formError {
                Text(formError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            HStack {
                Button(model.snapshot.isSubmitting ? "正在认证…" : "重新认证") {
                    model.submitUrl()
                }
                .buttonStyle(.borderedProminent)
                .disabled(!isValidInput || model.snapshot.isSubmitting)

                Button("修改地址") {
                    openSettings()
                }
                .disabled(model.snapshot.isSubmitting)
            }
        }
        .frame(maxWidth: 460, alignment: .leading)
        .padding(32)
    }

    private var forbiddenHostView: some View {
        VStack(spacing: 14) {
            Image(systemName: "hand.raised.fill")
                .font(.system(size: 36))
                .foregroundStyle(.red)
            Text("Host 未受信任")
                .font(.title3.weight(.semibold))
            Text("请在 DSH 主机的 dsh web --trusted-host 参数中加入当前访问的主机名。")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if let cleanUrl = model.snapshot.cleanUrl {
                Text(cleanUrl)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            HStack(spacing: 12) {
                Button("重试") {
                    model.retry()
                }
                .buttonStyle(.borderedProminent)
                Button("修改地址") {
                    openSettings()
                }
            }
        }
        .frame(maxWidth: 460)
        .padding(32)
    }

    private func statusColor(_ status: MacosConnectionStatus) -> Color {
        switch status {
        case .online:
            return .green
        case .connecting, .reconnecting:
            return .orange
        case .disconnected, .authExpired, .forbiddenHost:
            return .red
        }
    }
}
