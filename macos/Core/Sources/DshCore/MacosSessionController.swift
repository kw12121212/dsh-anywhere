import Foundation

/// macOS 原生会话连接状态。
public enum MacosConnectionStatus: Equatable, Sendable {
    case connecting
    case online
    case reconnecting
    case disconnected
    case authExpired
    case forbiddenHost
}

/// macOS 原生主界面路由状态。
public enum MacosScreen: Equatable, Sendable {
    case setup(invalidConfig: Bool)
    case connecting
    case connected
    case error(ProbeFailureReason)
    case authExpired
    case forbiddenHost
}

/// 设置窗口保存结果。
public enum SettingsSaveOutcome: Equatable, Sendable {
    case saved
    case unchanged
    case failed(String)
}

/// macOS 原生会话可观察快照（供 SwiftUI 主线程视图镜像渲染）。
public struct MacosSessionSnapshot: Equatable, Sendable {
    public var screen: MacosScreen
    public var status: MacosConnectionStatus
    public var cleanUrl: String?
    public var errorReason: ProbeFailureReason?
    public var errorHint: String?
    public var versionBanner: DsbotVersions.Banner?
    public var versionBannerDismissed: Bool
    public var capabilities: Capabilities
    public var stateRevision: String?
    public var liveKey: String?
    public var botCount: Int
    public var roomCount: Int
    public var isSubmitting: Bool
    public var formError: String?
    public var editingAddress: Bool
    public var migratedFromElectron: Bool
    public var migratedDiagnosticsEndpoint: Bool

    public init(
        screen: MacosScreen = .setup(invalidConfig: false),
        status: MacosConnectionStatus = .disconnected,
        cleanUrl: String? = nil,
        errorReason: ProbeFailureReason? = nil,
        errorHint: String? = nil,
        versionBanner: DsbotVersions.Banner? = nil,
        versionBannerDismissed: Bool = false,
        capabilities: Capabilities = .legacy,
        stateRevision: String? = nil,
        liveKey: String? = nil,
        botCount: Int = 0,
        roomCount: Int = 0,
        isSubmitting: Bool = false,
        formError: String? = nil,
        editingAddress: Bool = false,
        migratedFromElectron: Bool = false,
        migratedDiagnosticsEndpoint: Bool = false
    ) {
        self.screen = screen
        self.status = status
        self.cleanUrl = cleanUrl
        self.errorReason = errorReason
        self.errorHint = errorHint
        self.versionBanner = versionBanner
        self.versionBannerDismissed = versionBannerDismissed
        self.capabilities = capabilities
        self.stateRevision = stateRevision
        self.liveKey = liveKey
        self.botCount = botCount
        self.roomCount = roomCount
        self.isSubmitting = isSubmitting
        self.formError = formError
        self.editingAddress = editingAddress
        self.migratedFromElectron = migratedFromElectron
        self.migratedDiagnosticsEndpoint = migratedDiagnosticsEndpoint
    }
}

public extension ConnectionCopy {
    static func statusText(_ status: MacosConnectionStatus) -> String {
        switch status {
        case .connecting:
            return "连接中"
        case .online:
            return "在线"
        case .reconnecting:
            return "已断开，正在重连"
        case .disconnected:
            return "已断开"
        case .authExpired:
            return "需要重新认证"
        case .forbiddenHost:
            return "Host 未受信任"
        }
    }
}

/// 纯 Foundation macOS 会话与传输编排控制器（`HttpTransport`/`SseStream`/时钟均可注入并在 Linux 回归）。
public actor MacosSessionController {
    private enum LoopControl {
        case continueLoop
        case stopLoop
    }

    private let configStore: HarnessConfigStore
    private let cookieStore: SessionCookieStore
    private let probeTransport: any HttpTransport
    private let apiTransport: any HttpTransport
    private let sseStream: any SseStream
    private let electronConfigDirectories: [URL]
    private let sleepMs: @Sendable (Int64) async throws -> Void
    private let onSnapshot: @Sendable (MacosSessionSnapshot) -> Void

    private var snapshot = MacosSessionSnapshot()
    private var lastEmittedSnapshot: MacosSessionSnapshot?
    private var generation: UInt64 = 0
    private var activeTask: Task<Void, Never>?
    private var isActive: Bool = true
    private var wasPausedWhileActive: Bool = false

    private var localRevision: String?
    private var liveKey: String?
    private var lastState: DsbotModels.State?
    private var lastRemoteVersion: DsbotVersions.Remote?
    private var capabilities: Capabilities = .legacy
    private var backoffAttempt: Int = 0

    public init(
        configStore: HarnessConfigStore,
        cookieStore: SessionCookieStore,
        probeTransport: any HttpTransport,
        apiTransport: any HttpTransport,
        sseStream: any SseStream,
        electronConfigDirectories: [URL] = [],
        sleepMs: @escaping @Sendable (Int64) async throws -> Void = { ms in
            try await Task.sleep(nanoseconds: UInt64(max(ms, 0)) * 1_000_000)
        },
        onSnapshot: @escaping @Sendable (MacosSessionSnapshot) -> Void = { _ in }
    ) {
        self.configStore = configStore
        self.cookieStore = cookieStore
        self.probeTransport = probeTransport
        self.apiTransport = apiTransport
        self.sseStream = sseStream
        self.electronConfigDirectories = electronConfigDirectories
        self.sleepMs = sleepMs
        self.onSnapshot = onSnapshot
    }

    public func currentSnapshot() -> MacosSessionSnapshot {
        snapshot
    }

    public func start() {
        cookieStore.load()
        var migratedFromElectron = false
        var migratedDiagnostics = false
        var configLoad = configStore.load()
        if case .missing = configLoad, !electronConfigDirectories.isEmpty {
            let migration = ElectronConfigMigrator.migrateIfNeeded(
                nativeDirectory: configStore.directory,
                electronDirectories: electronConfigDirectories
            )
            if migration.didMigrateHarnessUrl {
                migratedFromElectron = true
                migratedDiagnostics = migration.migratedDiagnosticsEndpoint != nil
                configLoad = configStore.load()
            }
        }
        switch configLoad {
        case .missing:
            stopActiveTask()
            resetSyncCursor()
            snapshot = MacosSessionSnapshot(screen: .setup(invalidConfig: false), status: .disconnected)
            emitSnapshot()
        case .invalid:
            stopActiveTask()
            resetSyncCursor()
            snapshot = MacosSessionSnapshot(screen: .setup(invalidConfig: true), status: .disconnected)
            emitSnapshot()
        case .ok(let cleanUrl):
            resetSyncCursor()
            snapshot.migratedFromElectron = migratedFromElectron
            snapshot.migratedDiagnosticsEndpoint = migratedDiagnostics
            launchConnect(cleanUrl: cleanUrl, preserveSyncCursor: false, skipInitialProbe: false)
        }
    }

    public func submitUrl(_ rawUrl: String) async {
        guard !snapshot.isSubmitting else { return }
        let trimmed = rawUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanUrl: String
        do {
            cleanUrl = try HarnessUrl.normalize(trimmed)
        } catch let err as HarnessUrl.ValidationError {
            snapshot.formError = ConnectionCopy.urlErrorText(err.category)
            emitSnapshot()
            return
        } catch {
            snapshot.formError = ConnectionCopy.urlErrorText(.malformed)
            emitSnapshot()
            return
        }

        snapshot.isSubmitting = true
        snapshot.formError = nil
        emitSnapshot()

        let hasToken = HarnessUrl.hasToken(trimmed)
        switch await probeEstablishAndSave(cleanUrl: cleanUrl, trimmedInput: trimmed, hasToken: hasToken) {
        case .failure(.message(let message)):
            snapshot.isSubmitting = false
            snapshot.formError = message
            emitSnapshot()
        case .success(let probeStatusCode):
            applySavedConnection(cleanUrl: cleanUrl, hasToken: hasToken, probeStatusCode: probeStatusCode)
        }
    }

    /// 从标准设置窗口保存 harness 地址：
    /// - 在线且地址未变（不带 token）时直接返回 `.unchanged`，不中断或重建连接；
    /// - 非在线状态下地址未变（不带 token）时触发重连并返回 `.saved`；
    /// - 地址变更或带 token 时先探测（并按需建立一次会话）再原子保存，无需重启即生效；
    /// - 任一步失败均保留旧配置与主窗口状态，返回不含 token 的可读错误信息。
    public func saveSettingsUrl(_ rawUrl: String) async -> SettingsSaveOutcome {
        guard !snapshot.isSubmitting else {
            return .failed("正在保存，请稍候")
        }
        let trimmed = rawUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanUrl: String
        do {
            cleanUrl = try HarnessUrl.normalize(trimmed)
        } catch let err as HarnessUrl.ValidationError {
            return .failed(ConnectionCopy.urlErrorText(err.category))
        } catch {
            return .failed(ConnectionCopy.urlErrorText(.malformed))
        }

        let previous = configStore.load()
        let hasToken = HarnessUrl.hasToken(trimmed)
        if case .ok(let savedUrl) = previous, savedUrl == cleanUrl, !hasToken {
            if snapshot.screen == .connected && snapshot.status == .online {
                return .unchanged
            }
            snapshot.formError = nil
            snapshot.editingAddress = false
            launchConnect(cleanUrl: cleanUrl, preserveSyncCursor: false, skipInitialProbe: false)
            return .saved
        }

        snapshot.isSubmitting = true
        emitSnapshot()

        switch await probeEstablishAndSave(cleanUrl: cleanUrl, trimmedInput: trimmed, hasToken: hasToken) {
        case .failure(.message(let message)):
            snapshot.isSubmitting = false
            emitSnapshot()
            return .failed(message)
        case .success(let probeStatusCode):
            applySavedConnection(cleanUrl: cleanUrl, hasToken: hasToken, probeStatusCode: probeStatusCode)
            return .saved
        }
    }

    private enum SaveStageFailure: Error {
        case message(String)
    }

    private func probeEstablishAndSave(
        cleanUrl: String,
        trimmedInput: String,
        hasToken: Bool
    ) async -> Result<Int, SaveStageFailure> {
        // 提交前先对干净地址做 10s 可达性探测，不可达不覆盖现有配置。
        var probeHeaders: [(name: String, value: String)] = []
        if let host = HarnessUrl.hostHeader(cleanUrl) {
            probeHeaders.append(("Host", host))
        }
        let probeResponse: HttpResponse
        do {
            probeResponse = try await probeTransport.send(HttpRequest(method: "GET", url: cleanUrl, headers: probeHeaders))
        } catch {
            let reason = NetErrorClassifier.classify(error)
            return .failure(.message("\(ConnectionCopy.probeReasonTitle(reason))：\(ConnectionCopy.probeHint(forCleanUrl: cleanUrl))"))
        }

        if hasToken {
            let establisher = SessionEstablisher(transport: apiTransport, cookieStore: cookieStore)
            switch await establisher.establish(tokenUrl: trimmedInput) {
            case .ok:
                break
            case .rejected(let status):
                let msg = status == 403
                    ? "Host 未受信任，请在 DSH 主机的 dsh web --trusted-host 参数中加入当前主机名"
                    : "未能建立会话，请确认 dsh-url 输出的地址仍有效后重试"
                return .failure(.message(msg))
            case .networkError(let reason):
                return .failure(.message("\(ConnectionCopy.probeReasonTitle(reason))：\(ConnectionCopy.probeHint(forCleanUrl: cleanUrl))"))
            }
        }

        do {
            _ = try configStore.save(cleanUrl)
        } catch {
            return .failure(.message("保存配置失败，请检查应用数据目录权限"))
        }

        return .success(probeResponse.statusCode)
    }

    private func applySavedConnection(cleanUrl: String, hasToken: Bool, probeStatusCode: Int) {
        resetSyncCursor()
        snapshot.cleanUrl = cleanUrl
        snapshot.editingAddress = false
        snapshot.isSubmitting = false
        snapshot.formError = nil
        snapshot.versionBanner = nil
        snapshot.versionBannerDismissed = false
        snapshot.errorReason = nil
        snapshot.errorHint = nil
        snapshot.migratedFromElectron = false
        snapshot.migratedDiagnosticsEndpoint = false

        if !hasToken && probeStatusCode == 403 {
            stopActiveTask()
            setForbiddenHostScreen()
            return
        }
        launchConnect(cleanUrl: cleanUrl, preserveSyncCursor: false, skipInitialProbe: hasToken)
    }

    public func retry() {
        let target: String?
        if case .ok(let saved) = configStore.load() {
            target = saved
        } else {
            target = snapshot.cleanUrl
        }
        guard let cleanUrl = target else {
            start()
            return
        }
        snapshot.formError = nil
        snapshot.editingAddress = false
        launchConnect(cleanUrl: cleanUrl, preserveSyncCursor: false, skipInitialProbe: false)
    }

    public func openEditAddress() {
        snapshot.editingAddress = true
        snapshot.formError = nil
        emitSnapshot()
    }

    public func cancelEditAddress() {
        snapshot.editingAddress = false
        snapshot.formError = nil
        emitSnapshot()
    }

    public func dismissVersionBanner() {
        snapshot.versionBannerDismissed = true
        emitSnapshot()
    }

    public func setActive(_ active: Bool) {
        guard active != isActive else { return }
        isActive = active
        if !active {
            generation &+= 1
            activeTask?.cancel()
            activeTask = nil
            switch snapshot.screen {
            case .connected, .connecting:
                wasPausedWhileActive = true
                snapshot.status = .disconnected
                emitSnapshot()
            default:
                wasPausedWhileActive = false
            }
        } else if wasPausedWhileActive, let cleanUrl = snapshot.cleanUrl {
            wasPausedWhileActive = false
            launchConnect(cleanUrl: cleanUrl, preserveSyncCursor: true, skipInitialProbe: false)
        }
    }

    public func stop() {
        stopActiveTask()
    }

    // MARK: - 连接与同步编排

    private func stopActiveTask() {
        generation &+= 1
        activeTask?.cancel()
        activeTask = nil
        wasPausedWhileActive = false
    }

    private func resetSyncCursor() {
        localRevision = nil
        liveKey = nil
        lastState = nil
        lastRemoteVersion = nil
        capabilities = .legacy
        backoffAttempt = 0
    }

    private func launchConnect(cleanUrl: String, preserveSyncCursor: Bool, skipInitialProbe: Bool) {
        stopActiveTask()
        let gen = generation
        if !preserveSyncCursor {
            resetSyncCursor()
        } else {
            backoffAttempt = 0
        }
        snapshot.cleanUrl = cleanUrl
        snapshot.screen = .connecting
        snapshot.status = .connecting
        snapshot.errorReason = nil
        snapshot.errorHint = nil
        snapshot.stateRevision = localRevision
        snapshot.liveKey = liveKey
        emitSnapshot()

        guard isActive else {
            wasPausedWhileActive = true
            snapshot.status = .disconnected
            emitSnapshot()
            return
        }

        activeTask = Task { [weak self] in
            await self?.runConnect(cleanUrl: cleanUrl, gen: gen, skipInitialProbe: skipInitialProbe)
        }
    }

    private func runConnect(cleanUrl: String, gen: UInt64, skipInitialProbe: Bool) async {
        guard !Task.isCancelled, generation == gen, isActive else { return }

        let scopedProbe = OriginScopedHttpTransport(baseUrl: cleanUrl, transport: probeTransport, cookieStore: cookieStore)
        let scopedApi = OriginScopedHttpTransport(baseUrl: cleanUrl, transport: apiTransport, cookieStore: cookieStore)

        if !skipInitialProbe {
            let probeGate = await checkGate(cleanUrl: cleanUrl, transport: scopedProbe, allowRetryOn401: true)
            guard !Task.isCancelled, generation == gen, isActive else { return }
            switch probeGate {
            case .ok:
                break
            case .unreachable(let reason):
                setErrorScreen(cleanUrl: cleanUrl, reason: reason)
                return
            case .authExpired:
                setAuthExpiredScreen()
                return
            case .forbiddenHost:
                setForbiddenHostScreen()
                return
            }
        }

        guard cookieStore.hasAuthCookies(forUrl: cleanUrl) else {
            setAuthExpiredScreen()
            return
        }

        let client = DsbotTransportClient(baseUrl: cleanUrl, transport: scopedApi)
        if let gateAbort = await refreshVersionAndCapabilities(client: client, gen: gen) {
            guard !Task.isCancelled, generation == gen, isActive else { return }
            switch gateAbort {
            case .authExpired:
                setAuthExpiredScreen()
                return
            case .forbiddenHost:
                setForbiddenHostScreen()
                return
            }
        }

        while !Task.isCancelled, generation == gen, isActive {
            let feedUrl = DsbotApi.url(base: cleanUrl, path: "/api/bot/feed")
            let feedHeaders: [(name: String, value: String)]
            do {
                feedHeaders = try OriginHeaderPolicy.sanitize(
                    url: feedUrl,
                    allowedBaseUrl: cleanUrl,
                    cookieHeader: cookieStore.cookieHeader(forUrl: cleanUrl),
                    headers: [("Accept", "text/event-stream"), ("Cache-Control", "no-cache")]
                )
            } catch {
                setErrorScreen(cleanUrl: cleanUrl, reason: .other)
                return
            }

            do {
                let stream = try await sseStream.open(url: feedUrl, headers: feedHeaders)
                guard !Task.isCancelled, generation == gen, isActive else { return }
                backoffAttempt = 0
                let parser = SseParser()

                for try await chunk in stream {
                    guard !Task.isCancelled, generation == gen, isActive else { return }
                    for frame in parser.feed(chunk) {
                        let control = await handleFeedFrame(frame, client: client, gen: gen)
                        if control == .stopLoop {
                            return
                        }
                    }
                }
                for frame in parser.finish() {
                    guard !Task.isCancelled, generation == gen, isActive else { return }
                    let control = await handleFeedFrame(frame, client: client, gen: gen)
                    if control == .stopLoop {
                        return
                    }
                }
            } catch let streamErr as FeedStreamError {
                guard !Task.isCancelled, generation == gen, isActive else { return }
                switch streamErr {
                case .authExpired:
                    setAuthExpiredScreen()
                    return
                case .forbiddenHost:
                    setForbiddenHostScreen()
                    return
                case .httpStatus(let code) where code == 401:
                    setAuthExpiredScreen()
                    return
                case .httpStatus(let code) where code == 403:
                    setForbiddenHostScreen()
                    return
                case .httpStatus, .invalidResponse:
                    break
                }
            } catch {
                guard !Task.isCancelled, generation == gen, isActive else { return }
            }

            guard !Task.isCancelled, generation == gen, isActive else { return }
            snapshot.status = .reconnecting
            emitSnapshot()
            let delay = FeedSync.defaultBackoffMs(attempt: backoffAttempt)
            backoffAttempt += 1
            do {
                try await sleepMs(delay)
            } catch {
                return
            }
        }
    }

    private enum GateOutcome {
        case ok
        case unreachable(ProbeFailureReason)
        case authExpired
        case forbiddenHost
    }

    private enum AbortReason {
        case authExpired
        case forbiddenHost
    }

    private func checkGate(
        cleanUrl: String,
        transport: OriginScopedHttpTransport,
        allowRetryOn401: Bool
    ) async -> GateOutcome {
        do {
            let response = try await transport.send(HttpRequest(method: "GET", url: cleanUrl))
            if response.statusCode == 403 {
                return .forbiddenHost
            }
            if response.statusCode == 401 {
                if allowRetryOn401 && cookieStore.hasAuthCookies(forUrl: cleanUrl) {
                    return await checkGate(cleanUrl: cleanUrl, transport: transport, allowRetryOn401: false)
                }
                return .authExpired
            }
            return .ok
        } catch {
            return .unreachable(NetErrorClassifier.classify(error))
        }
    }

    private func refreshVersionAndCapabilities(client: DsbotTransportClient, gen: UInt64) async -> AbortReason? {
        let updateRes = await client.postBot(endpoint: "update-check")
        guard !Task.isCancelled, generation == gen else { return nil }
        switch updateRes {
        case .ok(let value):
            lastRemoteVersion = DsbotVersions.fromUpdateCheck(value)
        case .failure(_, .authExpired):
            return .authExpired
        case .failure(_, .forbiddenHost):
            return .forbiddenHost
        case .botError, .failure:
            lastRemoteVersion = nil
        }

        let helloRes = await client.postBot(endpoint: "hello", payloadJson: DsbotHello.requestPayload)
        guard !Task.isCancelled, generation == gen else { return nil }
        if case .failure(_, .authExpired) = helloRes {
            return .authExpired
        }
        if case .failure(_, .forbiddenHost) = helloRes {
            return .forbiddenHost
        }

        capabilities = Capabilities.from(helloRes)
        snapshot.capabilities = capabilities
        snapshot.versionBanner = DsbotVersions.banner(remote: lastRemoteVersion, capabilities: capabilities)
        emitSnapshot()
        return nil
    }

    private func handleFeedFrame(
        _ frame: SseParser.Frame,
        client: DsbotTransportClient,
        gen: UInt64
    ) async -> LoopControl {
        guard !Task.isCancelled, generation == gen, isActive else { return .stopLoop }

        switch frame.event {
        case "hello":
            if let feedHello = DsbotHello.decodeFeedHello(frame.data),
               capabilities.shouldReread(feedHello) {
                if let abort = await refreshVersionAndCapabilities(client: client, gen: gen) {
                    switch abort {
                    case .authExpired: setAuthExpiredScreen()
                    case .forbiddenHost: setForbiddenHostScreen()
                    }
                    return .stopLoop
                }
            }
            let remoteRev = Json.parse(frame.data)?.objectValue?.scalarString("revision")
            if remoteRev != localRevision || localRevision == nil {
                return await fetchState(client: client, gen: gen)
            }
            markOnline()
            return .continueLoop
        case "revision":
            let remoteRev = Json.parse(frame.data)?.objectValue?.scalarString("revision")
            if let remoteRev, remoteRev == localRevision {
                markOnline()
                return .continueLoop
            }
            return await fetchState(client: client, gen: gen)
        case "live":
            return await fetchState(client: client, gen: gen)
        default:
            markOnline()
            return .continueLoop
        }
    }

    private func fetchState(client: DsbotTransportClient, gen: UInt64) async -> LoopControl {
        let payload = DsbotModels.statePayload(
            since: localRevision,
            live: liveKey,
            models: DsbotModels.modelsKey(lastState?.raw)
        )
        let res = await client.postBot(endpoint: "state", payloadJson: payload)
        guard !Task.isCancelled, generation == gen, isActive else { return .stopLoop }

        switch res {
        case .ok(let value):
            if let full = DsbotModels.decodeState(value) {
                lastState = full
                localRevision = full.revision
                if let lk = full.liveKey { liveKey = lk }
                snapshot.stateRevision = localRevision
                snapshot.liveKey = liveKey
                snapshot.botCount = full.bots.count
                snapshot.roomCount = full.rooms.count
                markOnline()
            } else if let delta = DsbotModels.decodeStateDelta(value) {
                if let rev = delta.revision { localRevision = rev }
                if let lk = delta.liveKey { liveKey = lk }
                if let current = lastState {
                    let merged = DsbotModels.mergeDelta(state: current, delta: delta)
                    lastState = merged
                    snapshot.botCount = merged.bots.count
                    snapshot.roomCount = merged.rooms.count
                }
                snapshot.stateRevision = localRevision
                snapshot.liveKey = liveKey
                markOnline()
            }
            return .continueLoop
        case .failure(_, .authExpired):
            setAuthExpiredScreen()
            return .stopLoop
        case .failure(_, .forbiddenHost):
            setForbiddenHostScreen()
            return .stopLoop
        case .botError, .failure:
            return .continueLoop
        }
    }

    private func markOnline() {
        snapshot.screen = .connected
        snapshot.status = .online
        snapshot.errorReason = nil
        snapshot.errorHint = nil
        emitSnapshot()
    }

    private func setErrorScreen(cleanUrl: String, reason: ProbeFailureReason) {
        snapshot.screen = .error(reason)
        snapshot.status = .disconnected
        snapshot.errorReason = reason
        snapshot.errorHint = ConnectionCopy.probeHint(forCleanUrl: cleanUrl)
        snapshot.versionBanner = nil
        emitSnapshot()
    }

    private func setAuthExpiredScreen() {
        snapshot.screen = .authExpired
        snapshot.status = .authExpired
        snapshot.errorReason = nil
        snapshot.errorHint = nil
        emitSnapshot()
    }

    private func setForbiddenHostScreen() {
        snapshot.screen = .forbiddenHost
        snapshot.status = .forbiddenHost
        snapshot.errorReason = nil
        snapshot.errorHint = nil
        emitSnapshot()
    }

    private func emitSnapshot() {
        guard snapshot != lastEmittedSnapshot else { return }
        lastEmittedSnapshot = snapshot
        onSnapshot(snapshot)
    }
}
