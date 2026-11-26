import Foundation

public protocol FeedSyncListener: AnyObject {
    func fetchState(localRevision: String?, liveKey: String?)
    func onFeedHello(_ hello: DsbotHello.FeedHello)
}

public extension FeedSyncListener {
    func onFeedHello(_ hello: DsbotHello.FeedHello) {}
}

/// feed 订阅与断线增量补齐状态机（与 `FeedSync.kt` 对齐）。
public final class FeedSync {
    private unowned let listener: any FeedSyncListener
    private let backoff: (Int) -> Int64

    public private(set) var localRevision: String?
    public private(set) var liveKey: String?
    public private(set) var isConnected: Bool = false
    private var backoffAttempt: Int = 0

    public init(
        listener: any FeedSyncListener,
        backoff: @escaping (Int) -> Int64 = FeedSync.defaultBackoffMs
    ) {
        self.listener = listener
        self.backoff = backoff
    }

    public func reset() {
        localRevision = nil
        liveKey = nil
    }

    public func onConnected() {
        isConnected = true
        backoffAttempt = 0
    }

    @discardableResult
    public func onDisconnected() -> Int64 {
        isConnected = false
        let delay = backoff(backoffAttempt)
        backoffAttempt += 1
        return delay
    }

    public func onEvent(event: String, dataJson: String) {
        switch event {
        case "hello":
            if let hello = DsbotHello.decodeFeedHello(dataJson) {
                listener.onFeedHello(hello)
            }
            let remote = revisionOf(dataJson)
            if remote != localRevision {
                listener.fetchState(localRevision: localRevision, liveKey: liveKey)
            }
        case "revision":
            let remote = revisionOf(dataJson)
            if let remote, remote == localRevision {
                return
            }
            listener.fetchState(localRevision: localRevision, liveKey: liveKey)
        case "live":
            listener.fetchState(localRevision: localRevision, liveKey: liveKey)
        default:
            break
        }
    }

    public func onStateFetched(_ state: DsbotModels.State) {
        localRevision = state.revision
        if let lk = state.liveKey {
            liveKey = lk
        }
    }

    public func onStateFetched(_ delta: DsbotModels.StateDelta) {
        if let rev = delta.revision {
            localRevision = rev
        }
        if let lk = delta.liveKey {
            liveKey = lk
        }
    }

    private func revisionOf(_ dataJson: String) -> String? {
        Json.parse(dataJson)?.objectValue?.scalarString("revision")
    }

    /// 指数退避：1s、2s、4s … 封顶 60s（毫秒）。
    public static func defaultBackoffMs(attempt: Int) -> Int64 {
        let clamped = min(max(attempt, 0), 16)
        return min(Int64(1_000) << clamped, 60_000)
    }
}
