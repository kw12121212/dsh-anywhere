import Foundation

/// 发送箱（chat-outbox，与 `Outbox.kt` 逐条对齐）：按聊天保存尚未被服务端确认的本地消息行
/// （发送中 / 已发送待确认 / 未发送），服务端出现对应用户行后移除。只在内存、从不自动重发。
/// 非线程安全：由持有方收敛（主线程 ChatModel）。
public final class Outbox {
    /// SENDING：请求在途；SENT：请求成功、等服务端用户行出现；FAILED：请求失败、等用户重试或删除。
    public enum State: Equatable, Sendable {
        case sending
        case sent
        case failed
    }

    /// 本地行：`text` 为实际提交的文本（对账用），`display` 为显示文本（含附件名），`receipts`
    /// 为附件回执（重试时原样重发，附件里程碑接入），`window` 为发送时该聊天已知的最大顺序值
    /// （私聊为 seq）——只有顺序值更大的服务端行才能确认它。
    public struct Entry: Equatable, Sendable {
        public let id: Int64
        public let chatKey: String
        public let text: String
        public let display: String
        public let receipts: [String]
        public let state: State
        public let window: Int64

        public init(id: Int64, chatKey: String, text: String, display: String, receipts: [String], state: State, window: Int64) {
            self.id = id
            self.chatKey = chatKey
            self.text = text
            self.display = display
            self.receipts = receipts
            self.state = state
            self.window = window
        }
    }

    /// 服务端用户行：order 为顺序值（私聊 seq），为空时无法判断先后，不参与对账。
    public struct UserRow: Equatable, Sendable {
        public let order: Int64?
        public let text: String

        public init(order: Int64?, text: String) {
            self.order = order
            self.text = text
        }
    }

    private var byChat: [String: [Entry]] = [:]
    private var nextId: Int64 = 1

    public init() {}

    @discardableResult
    public func add(_ chatKey: String, text: String, display: String, receipts: [String] = [], window: Int64) -> Entry {
        let entry = Entry(id: nextId, chatKey: chatKey, text: text, display: display, receipts: receipts, state: .sending, window: window)
        nextId += 1
        byChat[chatKey, default: []].append(entry)
        return entry
    }

    public func entries(_ chatKey: String) -> [Entry] {
        byChat[chatKey] ?? []
    }

    public func get(_ id: Int64) -> Entry? {
        byChat.values.flatMap { $0 }.first { $0.id == id }
    }

    /// 改状态（重试置回 sending、失败置 failed）；条目已被对账移除时返回 nil。
    @discardableResult
    public func setState(_ id: Int64, _ state: State) -> Entry? {
        for (key, list) in byChat {
            guard let i = list.firstIndex(where: { $0.id == id }) else { continue }
            let updated = Entry(id: list[i].id, chatKey: list[i].chatKey, text: list[i].text, display: list[i].display, receipts: list[i].receipts, state: state, window: list[i].window)
            byChat[key]?[i] = updated
            return updated
        }
        return nil
    }

    @discardableResult
    public func remove(_ id: Int64) -> Bool {
        for (key, list) in byChat {
            let kept = list.filter { $0.id != id }
            if kept.count != list.count {
                if kept.isEmpty {
                    byChat.removeValue(forKey: key)
                } else {
                    byChat[key] = kept
                }
                return true
            }
        }
        return false
    }

    /// 对账：为本地行在 `userRows` 中找第一条顺序值非空、大于其窗口、未被占用、规范化文本相同的
    /// 服务端行，找到即移除该本地行；分两轮——先让非失败的行按发送顺序匹配，剩余的服务端行再给
    /// 失败的行（未发送但实际已送达的也要消掉，免得重试重复；又不能让真失败的行抢走后来同文本
    /// 发送的确认）。返回被移除的 id。
    @discardableResult
    public func reconcile(_ chatKey: String, userRows: [UserRow]) -> [Int64] {
        guard var list = byChat[chatKey] else { return [] }
        var used = Set<Int>()
        var removed: [Int64] = []
        let ordered = list.filter { $0.state != .failed } + list.filter { $0.state == .failed }
        for e in ordered {
            let want = Outbox.normalize(e.text)
            guard let hit = userRows.indices.first(where: { i in
                let order = userRows[i].order
                return !used.contains(i) && order != nil && order! > e.window && Outbox.normalize(userRows[i].text) == want
            }) else { continue }
            used.insert(hit)
            list.removeAll { $0.id == e.id }
            removed.append(e.id)
        }
        if list.isEmpty {
            byChat.removeValue(forKey: chatKey)
        } else {
            byChat[chatKey] = list
        }
        return removed
    }

    /// 去空白比较：折叠连续空白、去首尾。
    public static func normalize(_ text: String) -> String {
        text.components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }
}
