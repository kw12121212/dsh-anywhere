import AppKit
import DshCore
import SwiftUI

@MainActor
private final class WeakWindowBox {
    weak var window: NSWindow?
}

@MainActor
private final class WindowTrackingView: NSView {
    var onWindowChange: (@MainActor (NSWindow?) -> Void)?

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        onWindowChange?(window)
    }
}

private struct SettingsWindowAccessor: NSViewRepresentable {
    let box: WeakWindowBox

    func makeNSView(context: Context) -> WindowTrackingView {
        let view = WindowTrackingView()
        view.onWindowChange = { [box] win in
            box.window = win
        }
        return view
    }

    func updateNSView(_ nsView: WindowTrackingView, context: Context) {
        nsView.onWindowChange = { [box] win in
            box.window = win
        }
        if let win = nsView.window {
            box.window = win
        }
    }
}

/// 标准 macOS `Settings` 设置窗口（⌘,）：独立维护地址草稿，查看连接状态与关于信息。
struct SettingsView: View {
    @Bindable var model: AppSessionModel
    @Environment(\.dismiss) private var dismiss

    @State private var draftUrl: String = ""
    @State private var saveError: String?
    @State private var isSaving: Bool = false
    @State private var diagDraft: String = ""
    @State private var diagSaveError: String?
    @State private var windowBox = WeakWindowBox()

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
    }

    var body: some View {
        let liveError = HarnessUrl.errorCategory(draftUrl).map(ConnectionCopy.urlErrorText)
        let isValidInput = liveError == nil

        VStack(spacing: 0) {
            Form {
                Section("连接地址") {
                    VStack(alignment: .leading, spacing: 8) {
                        TextField(
                            "",
                            text: $draftUrl,
                            prompt: Text("粘贴 dsh-url 或 dsh web 输出的地址")
                        )
                        .labelsHidden()
                        .textFieldStyle(.roundedBorder)
                        .frame(maxWidth: .infinity)
                        .onSubmit {
                            if isValidInput && !isSaving {
                                submit()
                            }
                        }

                        if !draftUrl.isEmpty, let liveError {
                            Text(liveError)
                                .font(.caption)
                                .foregroundStyle(.red)
                        }
                        if let saveError {
                            Text(saveError)
                                .font(.caption)
                                .foregroundStyle(.red)
                        }
                        Text("保存时会自动剥离 token 参数，仅保留干净地址。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                Section("连接状态") {
                    LabeledContent("当前状态") {
                        HStack(spacing: 8) {
                            Circle()
                                .fill(statusColor(model.snapshot.status))
                                .frame(width: 9, height: 9)
                            Text(ConnectionCopy.statusText(model.snapshot.status))
                                .font(.subheadline.weight(.medium))
                        }
                    }

                    if let cleanUrl = model.snapshot.cleanUrl {
                        LabeledContent("当前地址") {
                            Text(cleanUrl)
                                .textSelection(.enabled)
                                .foregroundStyle(.secondary)
                        }
                    }

                    if model.snapshot.status == .online {
                        Text("SSE 实时同步在线（revision \(model.snapshot.stateRevision ?? "-") · Bot \(model.snapshot.botCount) · 群聊 \(model.snapshot.roomCount)）")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else if let reason = model.snapshot.errorReason {
                        Text("\(ConnectionCopy.probeReasonTitle(reason))：\(model.snapshot.errorHint ?? "")")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("提醒") {
                    Toggle("应用内提醒", isOn: $model.noticeBannerEnabled)
                    Text("关闭后不再显示非当前聊天的应用内提醒横幅，其余功能不受影响；本机保存并跨启动保持。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("诊断上报") {
                    VStack(alignment: .leading, spacing: 8) {
                        if let endpoint = model.configuredDiagnosticsEndpoint {
                            LabeledContent("上报端点") {
                                Text(endpoint)
                                    .textSelection(.enabled)
                                    .foregroundStyle(.secondary)
                            }
                        } else {
                            // 未配置即零行为：不产生、不保存、不发送任何诊断报告。
                            Text("未配置：应用不产生、不保存、不发送任何诊断报告。")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        TextField(
                            "",
                            text: $diagDraft,
                            prompt: Text("https://…（可选诊断上报端点）")
                        )
                        .labelsHidden()
                        .textFieldStyle(.roundedBorder)
                        .frame(maxWidth: .infinity)

                        if !diagDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, let diagLiveError {
                            Text(diagLiveError)
                                .font(.caption)
                                .foregroundStyle(.red)
                        }
                        if let diagSaveError {
                            Text(diagSaveError)
                                .font(.caption)
                                .foregroundStyle(.red)
                        }

                        HStack {
                            Button("保存") {
                                saveDiagnostics()
                            }
                            .disabled(!diagDraftIsValid)

                            if model.diagnosticsConfigured {
                                Button("清除", role: .destructive) {
                                    model.clearDiagnosticsEndpoint()
                                    diagDraft = ""
                                    diagSaveError = nil
                                }
                            }
                        }

                        Text("报告经脱敏且不含 token、cookie、API 载荷与聊天内容。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                Section("关于") {
                    LabeledContent("应用名称", value: AppIdentity.displayName)
                    LabeledContent("版本", value: appVersion)
                    Text(AppIdentity.copyright)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .formStyle(.grouped)

            Divider()

            HStack {
                // 固定在底栏而不放进表单：窗口较矮时表单会滚动裁切，关于入口必须保持可见。
                Button("关于 \(AppIdentity.displayName)…") {
                    NSApplication.shared.orderFrontStandardAboutPanel(nil)
                }

                Spacer()
                Button("取消") {
                    closeSettingsWindow()
                }
                .keyboardShortcut(.cancelAction)
                .disabled(isSaving)

                Button(isSaving ? "正在验证…" : "保存") {
                    submit()
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(!isValidInput || isSaving)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .frame(minWidth: 520, idealWidth: 520, maxWidth: 520, minHeight: 420)
        .background(SettingsWindowAccessor(box: windowBox))
        .onAppear {
            draftUrl = model.snapshot.cleanUrl ?? ""
            saveError = nil
            diagDraft = model.configuredDiagnosticsEndpoint ?? ""
            diagSaveError = nil
        }
        .onChange(of: draftUrl) { _, _ in
            saveError = nil
        }
        .onChange(of: diagDraft) { _, _ in
            diagSaveError = nil
        }
    }

    /// 诊断端点草稿实时校验：与存储层同一校验口径，仅合法 https 地址可保存；空值不显示原因。
    private var diagDraftIsValid: Bool {
        DiagnosticsEndpointConfig.validate(diagDraft) != nil
    }

    private var diagLiveError: String? {
        guard !diagDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !diagDraftIsValid else { return nil }
        return "诊断上报端点仅接受合法 https 地址"
    }

    private func saveDiagnostics() {
        guard diagDraftIsValid else { return }
        if let saved = model.saveDiagnosticsEndpoint(diagDraft) {
            diagDraft = saved
            diagSaveError = nil
        } else {
            diagSaveError = "诊断端点保存失败，请检查地址后重试"
        }
    }

    private func submit() {
        guard !isSaving else { return }
        let candidate = draftUrl
        isSaving = true
        saveError = nil
        Task { @MainActor in
            let outcome = await model.saveSettingsUrl(candidate)
            isSaving = false
            switch outcome {
            case .saved, .unchanged:
                // 草稿只留已保存的干净地址：窗口若未能关闭，带 token 的输入也不继续显示在界面上。
                draftUrl = (try? HarnessUrl.normalize(candidate)) ?? ""
                closeSettingsWindow()
            case .failed(let message):
                saveError = message
            }
        }
    }

    private func closeSettingsWindow() {
        if let window = windowBox.window {
            window.close()
        } else {
            dismiss()
        }
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
