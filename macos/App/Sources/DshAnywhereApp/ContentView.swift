import DshCore
import SwiftUI

struct ContentView: View {
    @Bindable var model: AppSessionModel

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
    }

    var body: some View {
        VStack(spacing: 0) {
            topStatusBar
            Divider()
            if let banner = model.snapshot.versionBanner, !model.snapshot.versionBannerDismissed {
                versionBannerView(banner)
                Divider()
            }
            mainContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(minWidth: 560, minHeight: 380)
        .task {
            model.startIfNeeded()
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
                Button("修改地址") {
                    model.openEditAddress()
                }
                .disabled(model.snapshot.isSubmitting)
            }
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
        if model.snapshot.editingAddress {
            addressFormView(
                title: "修改 Harness 地址",
                subtitle: "输入新的 dsh-url 输出地址或干净地址，验证可达后生效。",
                submitLabel: "保存",
                showCancel: true
            )
        } else {
            switch model.snapshot.screen {
            case .setup(let invalidConfig):
                addressFormView(
                    title: "首次连接设置",
                    subtitle: invalidConfig
                        ? "已有配置无效或已损坏，请重新粘贴 dsh-url 输出的地址。"
                        : "请粘贴 DSH 主机上运行 dsh-url 输出的带 token 地址。",
                    submitLabel: "连接",
                    showCancel: false
                )
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
                .padding(32)
            case .error(let reason):
                errorView(reason: reason)
            case .authExpired:
                reauthView
            case .forbiddenHost:
                forbiddenHostView
            }
        }
    }

    private func addressFormView(
        title: String,
        subtitle: String,
        submitLabel: String,
        showCancel: Bool
    ) -> some View {
        let liveError = HarnessUrl.errorCategory(model.urlInput).map(ConnectionCopy.urlErrorText)
        let isValidInput = liveError == nil

        return VStack(alignment: .leading, spacing: 14) {
            Text(title)
                .font(.title3.weight(.semibold))
            Text(subtitle)
                .font(.callout)
                .foregroundStyle(.secondary)

            TextField("粘贴 dsh-url 输出的地址", text: $model.urlInput)
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
                Button(model.snapshot.isSubmitting ? "正在验证…" : submitLabel) {
                    model.submitUrl()
                }
                .buttonStyle(.borderedProminent)
                .disabled(!isValidInput || model.snapshot.isSubmitting)

                if showCancel {
                    Button("取消") {
                        model.cancelEditAddress()
                    }
                    .disabled(model.snapshot.isSubmitting)
                }
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
                    model.openEditAddress()
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
                    model.openEditAddress()
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
                    model.openEditAddress()
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
