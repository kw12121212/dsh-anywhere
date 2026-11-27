import Foundation

/// 本机已读状态（team-read-state，与 `ReadState.kt` 逐条对齐）：按 harness 主机分别保存
/// 每个 Bot/群聊的最后已读时间（epoch 毫秒，取服务端时间——条目 lastAt 与聊天条目 time）。
/// 值语义：mark/merge/seed 返回新实例。
///
/// 只前移：非正数与不大于已有值的标记不改变状态；解码忽略非正数与非数字，整体非法回空
///（tech/state-persistence 的容错读取）。网页端已读在浏览器本地，不与客户端同步。
public struct ReadState: Equatable, Sendable {
    public static let empty = ReadState()

    private let hosts: [String: [String: Int64]]

    private init(hosts: [String: [String: Int64]] = [:]) {
        self.hosts = hosts
    }

    /// 某主机下某条目的已读时间；未标记为 0。
    public func seenAt(_ host: String, id: String) -> Int64 {
        hosts[host]?[id] ?? 0
    }

    /// 标记已读到 `at`；只前移。
    public func mark(_ host: String, _ id: String, _ at: Int64) -> ReadState {
        guard at > 0, !host.isEmpty, !id.isEmpty, at > seenAt(host, id: id) else { return self }
        var entries = hosts[host] ?? [:]
        entries[id] = at
        var out = hosts
        out[host] = entries
        return ReadState(hosts: out)
    }

    /// 该主机是否已有任何已读记录。
    public func knows(_ host: String) -> Bool {
        !(hosts[host]?.isEmpty ?? true)
    }

    /// 首次在某主机上看到团队时以当前最近活动时间打底（该主机已有记录则不变）：升级或换
    /// harness 后不至于整页都是未读点，之后的新活动照常显示未读。无效 lastAt 跳过。
    public func seed(_ host: String, lastAts: [String: Int64?]) -> ReadState {
        guard !host.isEmpty, !knows(host) else { return self }
        var out = self
        for (id, at) in lastAts {
            if let at { out = out.mark(host, id, at) }
        }
        return out
    }

    /// 逐项取较大的已读时间。
    public func merge(_ other: ReadState) -> ReadState {
        var out = self
        for (host, entries) in other.hosts {
            for (id, at) in entries {
                out = out.mark(host, id, at)
            }
        }
        return out
    }

    /// 未读 = 不是当前打开的聊天，且最近活动时间有效并晚于已读时间。
    public func isUnread(_ host: String, id: String, lastAt: Int64?, open: Bool) -> Bool {
        if open || lastAt == nil { return false }
        guard let lastAt else { return false }
        return lastAt > 0 && lastAt > seenAt(host, id: id)
    }

    /// 序列化为 read-state.json 的文本形态（与 Kotlin 版同构；键序不参与等值比较）。
    public func encode() -> String {
        var out = "{\"v\":1,\"hosts\":{"
        var firstHost = true
        for (host, entries) in hosts.sorted(by: { $0.key < $1.key }) {
            if !firstHost { out += "," }
            firstHost = false
            out += "\(JsonString.of(host)):{"
            var firstEntry = true
            for (id, at) in entries.sorted(by: { $0.key < $1.key }) {
                if !firstEntry { out += "," }
                firstEntry = false
                out += "\(JsonString.of(id)):\(at)"
            }
            out += "}"
        }
        out += "}}"
        return out
    }

    /// 解码持久化文本；缺失、损坏或形态不符一律为空，单个非法值跳过。
    public static func decode(_ text: String?) -> ReadState {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let root = Json.parse(text)?.objectValue,
              let hostsObj = root.object("hosts")
        else {
            return .empty
        }
        var out = ReadState.empty
        for (host, value) in hostsObj.entries {
            guard let entries = value.objectValue else { continue }
            for (id, at) in entries.entries {
                // 与 Kotlin 版一致：只收整数字面量（Notices 的 seq 才有 Double 折算兜底）。
                guard let raw = at.numberRaw, let ms = Int64(raw) else { continue }
                out = out.mark(host, id, ms)
            }
        }
        return out
    }

    /// 打开聊天时应标记的已读时间：max(条目 lastAt, 控制器读到的最新条目时间)；最新条目
    /// 只在属于 `targetId` 时采用（切换聊天时不把上一个聊天的时间记过来）。都没有为 0。
    public static func readAt(targetId: String, lastAt: Int64?, newest: (id: String, at: Int64)?) -> Int64 {
        max(lastAt ?? 0, newest.flatMap { $0.id == targetId ? $0.at : nil } ?? 0)
    }

    /// 主机标识：harness 地址的 scheme://host:port（无显式端口时补默认端口），不含路径、
    /// 查询与 token，同一主机不同 token/路径共用已读；无法解析时为 nil（不标记、不显示未读）。
    public static func hostKey(_ url: String?) -> String? {
        guard let url, let origin = HarnessUrl.canonicalOrigin(url) else { return nil }
        return origin.lowercased()
    }

}

/// 基于指定目录的 read-state.json 读写与 `0600` 原子持久化（目录由 App 层注入，模式同
/// `WindowStateStore`）。读取端把文件缺失、无法读取与内容损坏一律当作没有已读记录。
public final class ReadStateStore: Sendable {
    public let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    public var fileURL: URL {
        directory.appendingPathComponent("read-state.json")
    }

    public func load() -> ReadState {
        guard let data = try? Data(contentsOf: fileURL),
              let text = String(data: data, encoding: .utf8)
        else {
            return .empty
        }
        return ReadState.decode(text)
    }

    public func save(_ state: ReadState) throws {
        try AtomicFileWriter.write(
            data: Data(state.encode().utf8),
            to: fileURL,
            in: directory,
            tmpPrefix: ".read-state.json."
        )
    }
}
