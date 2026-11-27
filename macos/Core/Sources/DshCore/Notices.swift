import Foundation

/// 应用内提醒（in-app-notice-banner，与 `Notices.kt` 逐条对齐）：DS-Bot 0.3.10 的 feed
/// notice 事件与 notices {boot, since} 补齐应答的解码、横幅文案与挑选。形态以
/// testdata/dsbot 的真实夹具为准（notices.json、notices-reset.json、feed-stream-0310.sse，
/// 见 tech/dsbot-api notices-fields-0310）。提醒文本只用于界面，不进日志。
public enum Notices: Sendable {
    /// 计划变更约定的白名单；kind=view 与未知类型忽略。
    public static let kinds: Set<String> = ["question", "approval", "message", "group", "done"]

    public static let summaryMax = 80

    public struct Notice: Equatable, Sendable {
        public let seq: Int64
        public let time: Int64?
        public let botId: String
        public let name: String?
        public let kind: String
        public let sessionId: String?
        public let roomId: String?
        public let title: String?
        public let text: String?

        public init(
            seq: Int64,
            time: Int64?,
            botId: String,
            name: String?,
            kind: String,
            sessionId: String?,
            roomId: String?,
            title: String?,
            text: String?
        ) {
            self.seq = seq
            self.time = time
            self.botId = botId
            self.name = name
            self.kind = kind
            self.sessionId = sessionId
            self.roomId = roomId
            self.title = title
            self.text = text
        }
    }

    /// notices 应答：reset=true 表示陌生 boot 或缓冲已越过 since，须重取整体状态。
    public struct Batch: Equatable, Sendable {
        public let boot: String?
        public let seq: Int64?
        public let reset: Bool
        public let notices: [Notice]

        public init(boot: String?, seq: Int64?, reset: Bool, notices: [Notice]) {
            self.boot = boot
            self.seq = seq
            self.reset = reset
            self.notices = notices
        }
    }

    /// 解码单条提醒（feed notice 的 data 或 notices[] 的元素）；缺 seq/botId/kind 返回 nil。
    public static func decode(_ value: JsonValue?) -> Notice? {
        guard let o = value?.objectValue,
              let seq = long(o, "seq"),
              let botId = o.nonEmptyString("botId"),
              let kind = o.string("kind")
        else {
            return nil
        }
        return Notice(
            seq: seq,
            time: long(o, "time"),
            botId: botId,
            name: o.string("name"),
            kind: kind,
            sessionId: o.string("sessionId"),
            roomId: o.string("roomId"),
            title: o.string("title"),
            text: o.string("text")
        )
    }

    public static func decodeBatch(_ value: JsonValue?) -> Batch? {
        guard let o = value?.objectValue,
              let list = o.array("notices")
        else {
            return nil
        }
        return Batch(
            boot: o.string("boot"),
            seq: long(o, "seq"),
            reset: o.bool("reset") ?? false,
            notices: list.compactMap(decode)
        )
    }

    /// 是否显示为横幅（白名单内）。
    public static func shown(_ n: Notice) -> Bool {
        kinds.contains(n.kind)
    }

    /// 横幅文案：「[群名 · ]名称 · 类型：摘要」。title 只在群场景出现、值为群名
    ///（roomLineAdded / approvalWaiting 的 room.name），摘要一律取 text（群消息正文、审批
    /// 的工具名、提问卡文本）；text 为空（如群任务完成）时只显示来源与类型。
    public static func banner(_ n: Notice) -> String {
        let label: String
        switch n.kind {
        case "question": label = I18n.tr("提问", "question")
        case "approval": label = I18n.tr("等你审批", "needs approval")
        case "message": label = I18n.tr("回复", "reply")
        case "group": label = I18n.tr("群消息", "group message")
        case "done": label = I18n.tr("任务完成", "task done")
        default: label = n.kind
        }
        let trimmedName = n.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let name = trimmedName.isEmpty ? "Bot" : trimmedName
        let trimmedTitle = n.title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let hasRoom = !(n.roomId ?? "").isEmpty
        let room = (!trimmedTitle.isEmpty && hasRoom) ? trimmedTitle : nil
        let source = room.map { "\(I18n.clip($0, maxUTF16: 24)) · \(name)" } ?? name
        let summary = (n.text ?? "")
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        let head = "\(source) · \(label)"
        return summary.isEmpty ? head : head + I18n.tr("：", ": ") + I18n.clip(summary, maxUTF16: summaryMax)
    }

    /// 从一批新提醒（按到达顺序）中选出要显示的那一条：最后一条在白名单内、且目标不是
    /// 当前打开聊天的提醒；都不符合时返回 nil。实时与补齐共用，避免「最新一条恰是当前
    /// 聊天」时把其他人在等的提醒一并压掉。
    public static func pickBanner(_ fresh: [Notice], currentTarget: String?) -> Notice? {
        fresh.last { shown($0) && target($0) != currentTarget }
    }

    /// 点按横幅进入的聊天：群提醒进群（roomId），否则进该 Bot 私聊。
    public static func target(_ n: Notice) -> String {
        if let room = n.roomId, !room.isEmpty { return room }
        return n.botId
    }

    /// 数字文本 → Int64：整数字面量直取，其余按 Double 折算（与 Kotlin 版一致）。
    private static func long(_ o: JsonObject, _ key: String) -> Int64? {
        guard let raw = o[key]?.numberRaw else { return nil }
        if let v = Int64(raw) { return v }
        if let d = Double(raw) { return Int64(d) }
        return nil
    }
}

/// (boot, seq) 游标（与 `NoticeCursor` in `Notices.kt` 逐条对齐）：首次连接与远端重启时
/// 直接采用 hello 的游标（不补弹历史），同一 boot 内断线重连经 notices 补齐；实时 notice
/// 的 seq 不大于已见值即丢弃（乱序/重复）。非线程安全，由持有方收敛（控制器 actor）。
public final class NoticeCursor {
    /// onHello 的动作：无需动作 / 同一 boot 有新提醒须补齐 / 远端重启或回退须重取整体状态。
    public enum HelloAction: Equatable {
        case none
        case fill(boot: String, since: Int64)
        case reset
    }

    /// onBatch 结果：refetch=true 时应重取整体状态；fresh 为新接收（按 seq 升序）的提醒。
    public struct BatchResult: Equatable {
        public let refetch: Bool
        public let fresh: [Notices.Notice]

        public init(refetch: Bool, fresh: [Notices.Notice]) {
            self.refetch = refetch
            self.fresh = fresh
        }
    }

    public private(set) var boot: String?
    public private(set) var seq: Int64 = 0

    public func onHello(_ helloBoot: String?, _ helloSeq: Int64?) -> HelloAction {
        if helloBoot == nil {
            let had = boot != nil
            boot = nil
            seq = 0
            return had ? .reset : .none
        }
        guard let current = boot else {
            boot = helloBoot
            seq = helloSeq ?? 0
            return .none
        }
        if current != helloBoot {
            boot = helloBoot
            seq = helloSeq ?? 0
            return .reset
        }
        if let helloSeq, helloSeq > seq {
            return .fill(boot: current, since: seq)
        }
        return .none
    }

    /// 实时提醒：seq 大于已见值才接收并推进游标；未建立游标（未收到 hello）时也接收。
    public func accept(_ n: Notices.Notice) -> Bool {
        guard n.seq > seq else { return false }
        seq = n.seq
        return true
    }

    public func onBatch(_ batch: Notices.Batch) -> BatchResult {
        if batch.reset || batch.boot == nil || batch.boot != boot {
            boot = batch.boot
            seq = batch.seq ?? 0
            return BatchResult(refetch: true, fresh: [])
        }
        let fresh = batch.notices.sorted { $0.seq < $1.seq }.filter(accept)
        if let s = batch.seq, s > seq {
            seq = s
        }
        return BatchResult(refetch: false, fresh: fresh)
    }
}
