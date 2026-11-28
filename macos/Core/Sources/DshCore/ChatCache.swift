import Foundation

/// 聊天流畅度（chat-cache-fast-switch，与 `ChatCache.kt` 逐条对齐）的纯逻辑：内存 LRU 聊天
/// 缓存、滚动位置记忆、读取单飞、行身份复用、「回到最新 / N 条新消息」按钮状态与后台刷新
/// 合并决策。只在内存（聊天内容不落盘）；非线程安全，由持有方收敛。

/// 最近 `capacity` 个聊天的 LRU 缓存；键见 `chatKey(host:kind:id:)`。
public final class ChatCache<T> where T: Sendable {
    /// 缓存条目：当前已显示的条目、更早记录游标、会话 id、附带文本（群公告等，群聊里程碑用）。
    public struct CachedEntry: Sendable {
        public let items: [T]
        public let cursor: JsonValue?
        public let sessionId: String?
        public let extra: String?

        public init(items: [T], cursor: JsonValue?, sessionId: String?, extra: String? = nil) {
            self.items = items
            self.cursor = cursor
            self.sessionId = sessionId
            self.extra = extra
        }
    }

    private let capacity: Int
    private var order: [String] = []
    private var map: [String: CachedEntry] = [:]

    public init(capacity: Int = ChatCacheDefaultCapacity.capacity) {
        self.capacity = max(1, capacity)
    }

    private func touch(_ key: String) {
        if let i = order.firstIndex(of: key) {
            order.remove(at: i)
        }
        order.append(key)
        while order.count > capacity {
            map.removeValue(forKey: order.removeFirst())
        }
    }

    public func entry(_ key: String) -> CachedEntry? {
        guard map[key] != nil else { return nil }
        touch(key)
        return map[key]
    }

    public func put(_ key: String, _ entry: CachedEntry) {
        map[key] = entry
        touch(key)
    }

    /// 分段切换等使分页历史失效时丢弃该键。
    public func invalidate(_ key: String) {
        map.removeValue(forKey: key)
        order.removeAll { $0 == key }
    }

    public var count: Int { map.count }

    /// 按最近访问从旧到新的键（测试用）。
    public var keys: [String] { order }

    /// 「主机:类型:id」——不同 harness 主机的同名 id 互不串用。
    public func key(host: String?, kind: String, id: String) -> String {
        "\(host ?? ""):\(kind):\(id)"
    }
}

/// ChatCache 的缺省容量（最近 16 个聊天）。
public enum ChatCacheDefaultCapacity {
    public static let capacity = 16
}

/// 最近 `capacity` 个聊天的滚动位置（首个可见项下标与像素偏移）。
public final class ScrollMemory {
    public struct Position: Equatable, Sendable {
        public let index: Int
        public let offset: Int

        public init(index: Int, offset: Int) {
            self.index = index
            self.offset = offset
        }
    }

    public static let capacityDefault = 50
    private let capacity: Int
    private var order: [String] = []
    private var map: [String: Position] = [:]

    public init(capacity: Int = ScrollMemory.capacityDefault) {
        self.capacity = max(1, capacity)
    }

    public func position(_ key: String) -> Position? {
        guard map[key] != nil else { return nil }
        if let i = order.firstIndex(of: key) {
            order.remove(at: i)
        }
        order.append(key)
        while order.count > capacity {
            map.removeValue(forKey: order.removeFirst())
        }
        return map[key]
    }

    public func save(_ key: String, index: Int, offset: Int) {
        guard index >= 0, offset >= 0 else { return }
        if let i = order.firstIndex(of: key) {
            order.remove(at: i)
        }
        order.append(key)
        map[key] = Position(index: index, offset: offset)
        while order.count > capacity {
            map.removeValue(forKey: order.removeFirst())
        }
    }

    public func forget(_ key: String) {
        map.removeValue(forKey: key)
        order.removeAll { $0 == key }
    }

    public var count: Int { map.count }
}

/// 读取单飞：同一目标同时只跑一次读取，在途期间再触发的刷新最多排队一次；换目标立即以新
/// 代号开始，旧读取的结果到达时按代号判过期丢弃。
public final class ReadFlight {
    public enum Begin: Equatable {
        /// 立即发起读取，结果回来时以 `gen` 调 `finish`。
        case start(gen: Int)
        /// 同目标在途：已记一次排队，完成后会要求重跑。
        case queued
        /// 同目标在途且已排队：忽略。
        case ignored
    }

    /// stale：结果已被更新的读取取代（丢弃）；rerun：有排队且目标未变，应再读一次。
    public struct Finish: Equatable {
        public let stale: Bool
        public let rerun: Bool

        public init(stale: Bool, rerun: Bool) {
            self.stale = stale
            self.rerun = rerun
        }
    }

    private var gen = 0
    private var target: String?
    private var inFlight = false
    private var queued = false

    public init() {}

    public var current: String? { target }

    public func begin(_ id: String) -> Begin {
        if inFlight, target == id {
            if queued { return .ignored }
            queued = true
            return .queued
        }
        target = id
        inFlight = true
        queued = false
        gen += 1
        return .start(gen: gen)
    }

    /// 分页历史失效（分段切换）：在途读取的结果一律作废，下一次 begin 立即开始。
    public func reset() {
        gen += 1
        inFlight = false
        queued = false
    }

    public func finish(_ gen: Int) -> Finish {
        if gen != self.gen { return Finish(stale: true, rerun: false) }
        inFlight = false
        let rerun = queued
        queued = false
        return Finish(stale: false, rerun: rerun)
    }
}

/// 「回到最新 / N 条新消息」按钮状态：离底距离超过阈值为 away；away 期间新到条目累加。
public final class JumpButton {
    private let thresholdPx: Double
    private var jumping = false

    public private(set) var away = false
    public private(set) var newCount = 0

    public init(thresholdPx: Double) {
        self.thresholdPx = thresholdPx
    }

    /// 滚动或布局变化后喂入当前离底距离；回到底部附近即清零。
    public func onDistance(_ distanceBelowPx: Double) {
        let far = distanceBelowPx > thresholdPx
        if jumping {
            if far { return }
            jumping = false
        }
        away = far
        if !away { newCount = 0 }
    }

    /// 列表末尾新增 n 条（实时消息或刷新）；不在 away 状态时不计数。
    public func onAppended(_ n: Int) {
        if away, n > 0 { newCount += n }
    }

    /// 点按按钮回到最新：动画途中不判 away（避免按钮闪回）。
    public func onJumped() {
        away = false
        newCount = 0
        jumping = true
    }

    /// 跳转动画结束或被打断：恢复正常的离底判断。
    public func onJumpEnded() {
        jumping = false
    }

    /// 按钮文案；不显示时为 nil。
    public var label: String? {
        if !away { return nil }
        if newCount > 0 { return I18n.tr("\(newCount) 条新消息", "\(newCount) new messages") }
        return I18n.tr("回到最新", "Jump to latest")
    }
}

/// 估算离列表底部的像素距离：最后可见项底部超出视口的部分，加上其后未布局项数乘平均项高。
public enum ScrollDistance {
    public static func belowPx(
        totalItems: Int,
        lastVisibleIndex: Int,
        lastVisibleBottomPx: Double,
        viewportEndPx: Double,
        averageItemPx: Double
    ) -> Double {
        if totalItems <= 0 || lastVisibleIndex < 0 { return 0 }
        let overflow = max(lastVisibleBottomPx - viewportEndPx, 0)
        let after = max(totalItems - 1 - lastVisibleIndex, 0)
        return overflow + Double(after) * max(averageItemPx, 0)
    }
}

/// 后台刷新的合并决策：服务端首页是最新 N 条的窗口，新消息会让窗口前移。当前列表 = 更早
/// 记录 + 旧首页 + 实时追加；新首页的开头若能在当前列表中找到（之后连续 `minRun` 条一致，
/// 或一致到当前列表末尾），说明两者有重叠、中间无缺口：保留重叠点之前的条目与原游标，尾部
/// 换成新首页。找不到重叠（窗口已整体越过、分段换了）或强制重置时，整体换成新首页并改用新
/// 游标（宁可让用户重新上翻，也不显示有缺口的历史）。
public enum ChatRefresh {
    public static let minRun = 3

    public struct Result<T> {
        public let items: [T]
        public let keepCursor: Bool
        public let changed: Bool

        public init(items: [T], keepCursor: Bool, changed: Bool) {
            self.items = items
            self.keepCursor = keepCursor
            self.changed = changed
        }
    }

    /// `isLocal`：本地尚未落到服务端的条目（乐观发送、流式定格且无序号）；被替换掉的尾部里
    /// 这类条目若在新首页中找不到对应（`matches`），合并后重新追加到末尾——读取与发送并发时
    /// 自己刚发的消息不会暂时消失。分段切换的强制重置不保留本地条目。
    public static func merge<T: Equatable>(
        _ current: [T],
        _ fresh: [T],
        forceReset: Bool = false,
        isLocal: (T) -> Bool = { _ in false },
        matches: (T, T) -> Bool = { $0 == $1 }
    ) -> Result<T> {
        if forceReset { return Result(items: fresh, keepCursor: false, changed: fresh != current) }
        if current.isEmpty { return Result(items: fresh, keepCursor: false, changed: !fresh.isEmpty) }
        let k = fresh.isEmpty ? -1 : overlapStart(current, fresh)
        let base: [T]
        let dropped: [T]
        if k < 0 {
            base = fresh
            dropped = current
        } else {
            base = Array(current[0..<k]) + fresh
            dropped = Array(current[k...])
        }
        let keptLocal = dropped.filter { item in
            isLocal(item) && !fresh.contains { matches(item, $0) }
        }
        let merged = base + keptLocal
        return Result(items: merged, keepCursor: k >= 0, changed: merged != current)
    }

    /// 新首页开头在当前列表中的重叠起点，无则 -1。合格条件：从该位置起与新首页连续一致至少
    /// `minRun` 条，或新首页本身不足 `minRun` 条且全部一致到当前列表末尾。多个合格位置取连续
    /// 一致最长的（相同取靠后），避免内容相同的条目在末尾造成伪重叠而重复或漏掉消息。
    public static func overlapStart<T: Equatable>(_ current: [T], _ fresh: [T]) -> Int {
        var best = -1
        var bestRun = 0
        for k in current.indices {
            guard current[k] == fresh[0] else { continue }
            let available = min(current.count - k, fresh.count)
            var run = 0
            while run < available, current[k + run] == fresh[run] { run += 1 }
            let ok = run >= minRun || (fresh.count < minRun && run == available)
            if ok, run >= bestRun {
                best = k
                bestRun = run
            }
        }
        return best
    }
}
