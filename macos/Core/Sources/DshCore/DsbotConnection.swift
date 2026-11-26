import Foundation

public protocol DsbotConnectionListener: FeedSyncListener {
    func onStatus(_ status: DsbotConnection.Status)
    func probeAndGate()
    func connectFeed()
}

/// 原生连接状态机（与 `DsbotConnection.kt` 对齐）：聚合可达性、gate、feed 订阅与前后台信号。
public final class DsbotConnection {
    public enum Status: Equatable, Sendable {
        case connecting
        case online
        case reconnecting
        case offline
        case authExpired
        case forbiddenHost
    }

    public enum NativeGate: Equatable, Sendable {
        case ok
        case authExpired
        case forbiddenHost
    }

    private unowned let listener: any DsbotConnectionListener
    private let feedSync: FeedSync

    public private(set) var status: Status = .connecting
    private var inForeground: Bool = true
    private var feedWanted: Bool = false

    public init(listener: any DsbotConnectionListener, feedSync: FeedSync? = nil) {
        self.listener = listener
        self.feedSync = feedSync ?? FeedSync(listener: listener)
    }

    private func setStatus(_ next: Status) {
        if status != next {
            status = next
            listener.onStatus(next)
        }
    }

    public func beginConnect() {
        feedSync.reset()
        setStatus(.connecting)
        listener.probeAndGate()
    }

    public func onUnreachable() {
        feedWanted = false
        setStatus(.offline)
    }

    public func onGate(_ gate: NativeGate) {
        switch gate {
        case .ok:
            feedWanted = true
            setStatus(.connecting)
            listener.connectFeed()
        case .authExpired:
            feedWanted = false
            setStatus(.authExpired)
        case .forbiddenHost:
            feedWanted = false
            setStatus(.forbiddenHost)
        }
    }

    public func onFeedConnected() {
        feedSync.onConnected()
    }

    @discardableResult
    public func onFeedDisconnected() -> Int64 {
        let delay = feedSync.onDisconnected()
        if feedWanted {
            setStatus(inForeground ? .reconnecting : .connecting)
        }
        return delay
    }

    public func onBackoffElapsed() {
        if feedWanted && inForeground {
            listener.connectFeed()
        }
    }

    public func onFeedEvent(event: String, dataJson: String) {
        feedSync.onEvent(event: event, dataJson: dataJson)
        if status != .online && feedWanted {
            setStatus(.online)
        }
    }

    public func onStateFetched(_ state: DsbotModels.State) {
        feedSync.onStateFetched(state)
        if feedWanted {
            setStatus(.online)
        }
    }

    public func onStateFetched(_ delta: DsbotModels.StateDelta) {
        feedSync.onStateFetched(delta)
        if feedWanted {
            setStatus(.online)
        }
    }

    public func onBackground() {
        inForeground = false
        if feedWanted {
            setStatus(.connecting)
        }
    }

    public func onForeground() {
        if inForeground { return }
        inForeground = true
        if feedWanted {
            beginConnect()
        }
    }

    public func onSessionReestablished() {
        beginConnect()
    }

    public var localRevisionForTest: String? {
        feedSync.localRevision
    }

    public var liveKeyForTest: String? {
        feedSync.liveKey
    }
}
