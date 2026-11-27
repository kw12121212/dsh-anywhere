import DshCore
import SwiftUI

/// 团队侧栏（macos-team-sidebar）：搜索框（⌘K 聚焦）、分组列表与条目行（头像颜色圈 +
/// 工作状态点、名称、Main/私有/待办标签、预览优先级、本机未读点）、空团队引导与加载提示。
/// 数据来自快照组装好的 `TeamHome.Team`，本视图无网络逻辑。
struct TeamSidebarView: View {
    @Bindable var model: AppSessionModel
    @Bindable private var sidebar: TeamSidebarModel
    @FocusState private var searchFocused: Bool

    init(model: AppSessionModel) {
        self.model = model
        self.sidebar = model.sidebar
    }

    /// 应用搜索过滤后的团队（结构不变，与 Android TeamHome.search 一致）。
    private var displayedTeam: TeamHome.Team? {
        guard let team = model.snapshot.team else { return nil }
        return sidebar.searchQuery.isEmpty ? team : TeamHome.search(team, query: sidebar.searchQuery)
    }

    private var isSearching: Bool {
        !sidebar.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        VStack(spacing: 0) {
            searchField
            Divider()
            content
        }
        .background(.thinMaterial)
        .onChange(of: model.sidebarSearchFocusTick) {
            searchFocused = true
        }
    }

    private var searchField: some View {
        TextField("搜索名称或消息", text: $sidebar.searchQuery)
            .textFieldStyle(.roundedBorder)
            .focused($searchFocused)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
    }

    @ViewBuilder
    private var content: some View {
        if let team = displayedTeam {
            if team.isEmpty && !isSearching {
                emptyGuide
            } else if isSearching && team.sections.allSatisfy({ $0.entries.isEmpty }) {
                noMatchHint
            } else {
                teamList(team)
            }
        } else {
            ProgressView("正在同步团队…")
                .padding(.top, 32)
                .frame(maxWidth: .infinity)
        }
    }

    private var emptyGuide: some View {
        VStack(spacing: 8) {
            Text("还没有团队成员")
                .font(.headline)
            Text("在电脑端 DS-Bot 创建第一个 Chief 后，这里会显示团队列表。")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(24)
        .frame(maxWidth: .infinity)
    }

    private var noMatchHint: some View {
        Text("没有匹配的条目")
            .font(.callout)
            .foregroundStyle(.secondary)
            .padding(.top, 24)
            .frame(maxWidth: .infinity)
    }

    private func teamList(_ team: TeamHome.Team) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(team.sections, id: \.sectionKey) { section in
                    sectionHeader(section)
                    ForEach(section.entries, id: \.id) { entry in
                        entryRow(entry)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func sectionHeader(_ section: TeamHome.Section) -> some View {
        if section.id == nil {
            Text(section.name)
                .font(.caption.weight(.medium))
                .foregroundStyle(Color.accentColor)
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
        } else {
            // 分组按远端 sections 原样显示（名称与条数）；折叠写回与管理由后续变更承接。
            Text("\(section.name)（\(section.entries.count)）")
                .font(.caption.weight(.medium))
                .foregroundStyle(Color.accentColor)
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
        }
    }

    /// 条目行为 Button：点击选中（VoiceOver 与键盘可激活），Plain 样式保持行外观。
    private func entryRow(_ entry: TeamHome.Entry) -> some View {
        let selected = entry.id == sidebar.selection
        let unread = sidebar.isUnread(entry)
        return Button {
            model.selectChatById(entry.id)
        } label: {
            entryRowContent(entry, selected: selected, unread: unread)
        }
        .buttonStyle(.plain)
    }

    private func entryRowContent(_ entry: TeamHome.Entry, selected: Bool, unread: Bool) -> some View {
        HStack(spacing: 10) {
            ZStack(alignment: .bottomTrailing) {
                Circle()
                    .fill(entryColor(entry.color))
                    .frame(width: 30, height: 30)
                Circle()
                    .fill(entry.working ? Color.accentColor : Color.secondary.opacity(0.35))
                    .frame(width: 9, height: 9)
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(entry.name)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    if entry.isMain {
                        tag("主")
                    }
                    if entry.isPrivate {
                        tag("私有")
                    }
                    ForEach(entry.pendingKinds, id: \.self) { kind in
                        pendingTag(kind)
                    }
                }
                if let preview = entry.preview {
                    Text(preview.text)
                        .font(.caption)
                        .foregroundStyle(preview.waiting ? Color.accentColor : Color.secondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }
            Spacer(minLength: 0)
            if unread {
                Circle()
                    .fill(Color.accentColor)
                    .frame(width: 8, height: 8)
                    .accessibilityLabel("未读")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 6)
                .fill(selected ? Color.accentColor.opacity(0.12) : Color.clear)
        )
    }

    private func tag(_ text: String) -> some View {
        Text(" \(text)")
            .font(.caption2)
            .foregroundStyle(.secondary)
    }

    /// 待办行内提示（needs-you-summary 行内化）：审批/提问/卡片，强调色。
    private func pendingTag(_ kind: TeamHome.PendingItem.Kind) -> some View {
        Text(" \(pendingLabel(kind))")
            .font(.caption2.weight(.medium))
            .foregroundStyle(Color.accentColor)
    }

    private func pendingLabel(_ kind: TeamHome.PendingItem.Kind) -> String {
        switch kind {
        case .approval: return "审批"
        case .question: return "提问"
        case .view: return "卡片"
        }
    }

    /// DS-Bot 经典色系（与 Android teamColorOf 一致；未知色用强调色）。
    private func entryColor(_ name: String?) -> Color {
        switch name {
        case "red": return Color(red: 0xE5 / 255, green: 0x39 / 255, blue: 0x35 / 255)
        case "orange": return Color(red: 0xFB / 255, green: 0x8C / 255, blue: 0x00 / 255)
        case "yellow": return Color(red: 0xFD / 255, green: 0xD8 / 255, blue: 0x35 / 255)
        case "green": return Color(red: 0x43 / 255, green: 0xA0 / 255, blue: 0x47 / 255)
        case "cyan": return Color(red: 0x00 / 255, green: 0xAC / 255, blue: 0xC1 / 255)
        case "blue": return Color(red: 0x1E / 255, green: 0x88 / 255, blue: 0xE5 / 255)
        case "violet": return Color(red: 0x8E / 255, green: 0x24 / 255, blue: 0xAA / 255)
        case "magenta": return Color(red: 0xD8 / 255, green: 0x1B / 255, blue: 0x60 / 255)
        case "gray", "charcoal", "graphite": return Color(red: 0x75 / 255, green: 0x75 / 255, blue: 0x75 / 255)
        case "deepseek": return Color(red: 0x4D / 255, green: 0x6B / 255, blue: 0xFE / 255)
        default: return .accentColor
        }
    }
}

private extension TeamHome.Section {
    /// 分组标识：未分组与具名分组区分（未分组 id 为 nil）。
    var sectionKey: String {
        id ?? "ungrouped:\(name)"
    }
}

/// 窗口内提醒横幅（in-app-notice-banner）：来源·类型：摘要，点按进入对应聊天，
/// 约 8 秒自动收起（由父视图按 banner.id 计时）。
struct NoticeBannerView: View {
    let text: String
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 10) {
                Image(systemName: "bell.badge.fill")
                    .foregroundStyle(Color.accentColor)
                Text(text)
                    .font(.callout)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .multilineTextAlignment(.leading)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(Color.accentColor.opacity(0.1))
    }
}
