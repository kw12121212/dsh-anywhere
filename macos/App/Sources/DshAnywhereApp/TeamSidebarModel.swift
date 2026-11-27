import DshCore
import Foundation
import Observation

/// 团队侧栏的界面装配状态（macos-team-sidebar）：本机已读（ReadState + 目录注入存储）、
/// 当前选中条目与搜索词。选中即按 readAt 标记已读写盘，并作为提醒抑制的当前聊天
///（经控制器 setCurrentTarget 联动）；首次见到某主机的团队时以最近活动打底避免整页未读。
@MainActor
@Observable
final class TeamSidebarModel {
    /// 当前选中的条目 id（Bot 或群）；nil 为未选中。
    var selection: String?
    var searchQuery: String = ""

    private(set) var readState: ReadState
    private let store: ReadStateStore
    private var hostKey: String?

    init(store: ReadStateStore) {
        self.store = store
        self.readState = store.load()
    }

    /// 连接地址变化时更新主机标识（按主机隔离已读）；换主机时清空选中（条目 id 属于旧主机）。
    func updateHost(_ cleanUrl: String?) -> Bool {
        let next = ReadState.hostKey(cleanUrl)
        guard next != hostKey else { return false }
        hostKey = next
        selection = nil
        return true
    }

    /// 快照团队更新：首次见到该主机时以条目最近活动打底（不把存量活动算成未读）；
    /// 当前选中聊天的最近活动前进时同步标记已读（收到当前聊天的实时消息时同步标记）。
    func teamDidUpdate(_ team: TeamHome.Team) {
        guard let hostKey else { return }
        var next = readState
        if !next.knows(hostKey) {
            var lastAts: [String: Int64?] = [:]
            for section in team.sections {
                for entry in section.entries {
                    lastAts[entry.id] = entry.lastAt
                }
            }
            next = next.seed(hostKey, lastAts: lastAts)
        }
        if let id = selection,
           let entry = team.sections.flatMap(\.entries).first(where: { $0.id == id }),
           let lastAt = entry.lastAt {
            next = next.mark(hostKey, id, lastAt)
        }
        if next != readState {
            readState = next
            try? store.save(readState)
        }
    }

    /// 选中某条目：设为当前聊天并标记已读（聊天内最新条目时间由后续聊天变更并入）；
    /// 已读未前进时不重复写盘。
    func select(id: String, team: TeamHome.Team?) {
        selection = id
        guard let hostKey else { return }
        let entry = team?.sections.flatMap(\.entries).first { $0.id == id }
        let at = ReadState.readAt(targetId: id, lastAt: entry?.lastAt, newest: nil)
        let next = readState.mark(hostKey, id, at)
        if next != readState {
            readState = next
            try? store.save(readState)
        }
    }

    /// 条目是否显示未读点（本机已读计算；当前选中不算未读）。
    func isUnread(_ entry: TeamHome.Entry) -> Bool {
        guard let hostKey else { return false }
        return readState.isUnread(hostKey, id: entry.id, lastAt: entry.lastAt, open: entry.id == selection)
    }
}
