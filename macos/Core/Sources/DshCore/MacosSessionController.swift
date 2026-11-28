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

/// feed 聊天事件（message/stream）向 App 聊天编排的转发载荷：sessionId 过滤用，data 为原始 JSON。
public struct ChatFeedEvent: Equatable, Sendable {
    public let sessionId: String?
    public let event: String
    public let data: String

    public init(sessionId: String?, event: String, data: String) {
        self.sessionId = sessionId
        self.event = event
        self.data = data
    }
}

/// 窗口内提醒横幅（in-app-notice-banner）：id 每次递增（界面据此重置 8 秒计时），
/// text 为「[群名 · ]名称 · 类型：摘要」（只用于界面，不进日志），target 为点按进入的聊天。
public struct NoticeBanner: Equatable, Sendable {
    public let id: UInt64
    public let text: String
    public let target: String

    public init(id: UInt64, text: String, target: String) {
        self.id = id
        self.text = text
        self.target = target
    }
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
    /// 组装好的团队列表（macos-team-sidebar）：nil 表示尚未取到首份 state（侧栏显示加载提示）。
    public var team: TeamHome.Team?
    /// 团队偏好 enterToSend（缺省 true；state.prefs 只存与默认不同的键）。
    public var enterToSend: Bool
    /// 当前显示的提醒横幅；nil 为无横幅。
    public var noticeBanner: NoticeBanner?
    public var isSubmitting: Bool
    public var formError: String?
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
        team: TeamHome.Team? = nil,
        enterToSend: Bool = true,
        noticeBanner: NoticeBanner? = nil,
        isSubmitting: Bool = false,
        formError: String? = nil,
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
        self.team = team
        self.enterToSend = enterToSend
        self.noticeBanner = noticeBanner
        self.isSubmitting = isSubmitting
        self.formError = formError
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
    private let noticesEnabled: @Sendable () -> Bool
    private let onChatEvent: @Sendable (ChatFeedEvent) -> Void
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

    // 应用内提醒（in-app-notice-banner）：(boot, seq) 游标与横幅序号；当前聊天用于抑制。
    private var noticeCursor = NoticeCursor()
    private var noticeId: UInt64 = 0
    private var currentTarget: String?

    /// 当前连接周期的聊天用传输客户端（feed 循环创建，聊天 API 复用同源）。
    private var chatClient: DsbotTransportClient?

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
        noticesEnabled: @escaping @Sendable () -> Bool = { true },
        onChatEvent: @escaping @Sendable (ChatFeedEvent) -> Void = { _ in },
        onSnapshot: @escaping @Sendable (MacosSessionSnapshot) -> Void = { _ in }
    ) {
        self.configStore = configStore
        self.cookieStore = cookieStore
        self.probeTransport = probeTransport
        self.apiTransport = apiTransport
        self.sseStream = sseStream
        self.electronConfigDirectories = electronConfigDirectories
        self.sleepMs = sleepMs
        self.noticesEnabled = noticesEnabled
        self.onChatEvent = onChatEvent
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
        launchConnect(cleanUrl: cleanUrl, preserveSyncCursor: false, skipInitialProbe: false)
    }

    public func dismissVersionBanner() {
        snapshot.versionBannerDismissed = true
        emitSnapshot()
    }

    /// 侧栏当前选中的聊天（in-app-notice-banner 的当前聊天抑制；nil 为未选中）。
    public func setCurrentTarget(_ target: String?) {
        currentTarget = target
    }

    /// 收起指定横幅（8 秒自动收起或点按后调用）；id 不匹配时忽略。
    public func dismissNotice(id: UInt64) {
        guard snapshot.noticeBanner?.id == id else { return }
        snapshot.noticeBanner = nil
        emitSnapshot()
    }

    // MARK: - 聊天 API（macos-bot-chat）

    /// 在线时复用当前连接周期的聊天客户端执行 POST；离线返回网络失败。
    private func chatPost(endpoint: String, payloadJson: String) async -> DsbotApi.Result {
        guard snapshot.screen == .connected, let client = chatClient else {
            return .failure(status: nil, reason: .network)
        }
        return await client.postBot(endpoint: endpoint, payloadJson: payloadJson)
    }

    /// 首页或预取聊天（chat {botId, cursor?}）；失败返回 nil（App 保留现状并提示）。
    public func fetchChatPage(botId: String, cursor: JsonValue? = nil) async -> DsbotModels.ChatPage? {
        if case .ok(let value) = await chatPost(endpoint: "chat", payloadJson: BotChatApi.chatPayload(botId: botId, cursor: cursor)) {
            return DsbotModels.decodeChatPage(value)
        }
        return nil
    }

    /// 向上翻页（earlier {sessionId, cursor}）；失败返回 nil。
    public func fetchEarlier(sessionId: String, cursor: JsonValue) async -> DsbotModels.ChatPage? {
        if case .ok(let value) = await chatPost(endpoint: "earlier", payloadJson: BotChatApi.earlierPayload(sessionId: sessionId, cursor: cursor)) {
            return DsbotModels.decodeChatPage(value)
        }
        return nil
    }

    /// 发送纯文本（send）：ok 即请求成功（等待服务端用户行确认），失败由发送箱置 FAILED。
    public func sendChatMessage(sessionId: String, text: String) async -> Bool {
        if case .ok = await chatPost(endpoint: "send", payloadJson: BotChatApi.sendPayload(sessionId: sessionId, text: text)) {
            return true
        }
        return false
    }

    /// 聊天动作端点共用（stop-member-work / resume / bot-new-part / bot-open-part / run-stats / exchange）。
    public func chatAction(endpoint: String, payloadJson: String) async -> DsbotApi.Result {
        await chatPost(endpoint: endpoint, payloadJson: payloadJson)
    }

    /// 动作后重取整体 state（在线时；不做完整重连，避免拆掉界面）。
    public func refreshState() async {
        guard snapshot.screen == .connected, let client = chatClient else { return }
        _ = await fetchState(client: client, gen: generation)
    }

    // MARK: - 附件 API（macos-attachments-files）

    /// 附件调用上下文：控制器在 actor 内按当前连接组装（base + cookie），调用时传参，
    /// 避免闭包捕获陈旧值（凭据不经日志）。
    public struct AttachmentCallContext: Equatable, Sendable {
        public let base: String
        public let cookieHeader: String?

        public init(base: String, cookieHeader: String?) {
            self.base = base
            self.cookieHeader = cookieHeader
        }
    }

    /// 上传执行器：App 层注入（URLSession octet-stream 直传），返回 uploadFileBinary 应答文本。
    public typealias AttachmentUploader = @Sendable (_ sessionId: String, _ name: String, _ mediaType: String?, _ data: Data, _ context: AttachmentCallContext?) async throws -> String

    /// session 族 HTTP unary 调用器（上下文由控制器在 actor 内组装传入）。
    public typealias MuxCaller = @Sendable (_ endpoint: String, _ requestJson: String, _ context: AttachmentCallContext?) async throws -> String

    private var attachmentUploader: AttachmentUploader?
    private var muxCaller: MuxCaller?

    /// 注入附件装配（App 启动后调用；重复注入覆盖）。
    public func attach(uploader: AttachmentUploader?, mux: MuxCaller?) {
        attachmentUploader = uploader
        muxCaller = mux
    }

    /// 上传一个附件（receipt 应答文本原样返回，App 侧经 AttachmentMessages.decodeUpload 解析）。
    public func uploadAttachment(sessionId: String, name: String, mediaType: String?, data: Data) async throws -> String {
        guard let uploader = attachmentUploader else {
            throw TransportError(.other)
        }
        return try await uploader(sessionId, name, mediaType, data, callContext())
    }

    /// 当前连接周期的附件调用上下文（actor 内组装）。
    private func callContext() -> AttachmentCallContext? {
        guard let base = snapshot.cleanUrl else { return nil }
        return AttachmentCallContext(base: base, cookieHeader: cookieStore.cookieHeader(forUrl: base))
    }

    /// 经 dsh mux 提交带附件消息（session/prompt）；应答文本原样返回。
    public func submitAttachmentMessage(endpoint: String, requestJson: String) async throws -> String {
        try await muxCall(endpoint: endpoint, requestJson: requestJson)
    }

    /// file-text / image / readAttachment 等附件读取共用：经 mux 调用。
    public func muxCall(endpoint: String, requestJson: String) async throws -> String {
        guard let mux = muxCaller else {
            throw TransportError(.other)
        }
        return try await mux(endpoint, requestJson, callContext())
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
        // 新的连接周期：游标从头采用 hello，旧横幅不再显示（重启不补弹）。
        noticeCursor = NoticeCursor()
        snapshot.noticeBanner = nil
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
        chatClient = client
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
            let feedHello = DsbotHello.decodeFeedHello(frame.data)
            if let feedHello, capabilities.shouldReread(feedHello) {
                if let abort = await refreshVersionAndCapabilities(client: client, gen: gen) {
                    switch abort {
                    case .authExpired: setAuthExpiredScreen()
                    case .forbiddenHost: setForbiddenHostScreen()
                    }
                    return .stopLoop
                }
            }
            // 提醒游标（in-app-notice-banner）：首次采用 hello 游标不补弹；同一 boot 断线
            // 重连以 notices {boot, since} 补齐（远端声明 notices 能力时）；boot 变化重取
            // 整体状态，不补弹旧提醒。
            switch noticeCursor.onHello(feedHello?.boot, feedHello?.seq) {
            case .fill(let boot, let since):
                if capabilities.has("notices"),
                   let stop = await fillNotices(boot: boot, since: since, client: client, gen: gen) {
                    return stop
                }
            case .reset:
                // dsh 重启：不带 since/live 重取整体状态（对齐 Android fetchStateOnce(null, null)，
                // tech/dsbot-api notices-fields-0310：reset 或 boot 变化不得把新 delta 合并进旧状态）。
                return await fetchState(client: client, gen: gen, fullRefetch: true)
            case .none:
                break
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
        case "message", "stream":
            // 聊天事件（bot-chat-send-stream）：按 sessionId 转发给 App 编排，界面自行过滤。
            if let obj = Json.parse(frame.data)?.objectValue {
                onChatEvent(ChatFeedEvent(
                    sessionId: obj.nonEmptyString("sessionId"),
                    event: frame.event,
                    data: frame.data
                ))
            }
            markOnline()
            return .continueLoop
        case "notice":
            // 实时提醒：能力门控 + 游标去重后按 pickBanner 挑选（当前聊天不弹）。
            if capabilities.has("notices"),
               let notice = Notices.decode(Json.parse(frame.data)),
               noticeCursor.accept(notice) {
                showNotices([notice])
            }
            markOnline()
            return .continueLoop
        default:
            markOnline()
            return .continueLoop
        }
    }

    /// 同一 boot 断线重连：POST /api/bot/notices {boot, since} 补齐（应答回到同代际且游标
    /// 仍在本 boot 时才生效），reset 或陌生 boot 时重取整体状态；返回 stopLoop 时中断循环。
    private func fillNotices(
        boot: String,
        since: Int64,
        client: DsbotTransportClient,
        gen: UInt64
    ) async -> LoopControl? {
        let payload = "{\"boot\":\(JsonString.of(boot)),\"since\":\(since)}"
        let res = await client.postBot(endpoint: "notices", payloadJson: payload)
        guard !Task.isCancelled, generation == gen, isActive else { return .stopLoop }
        // 请求在途期间远端已重启并 Reset 过：旧 boot 的应答作废，免得游标被改回旧 boot。
        guard noticeCursor.boot == boot else { return nil }
        guard case .ok(let value) = res, let batch = Notices.decodeBatch(value) else { return nil }
        let outcome = noticeCursor.onBatch(batch)
        if outcome.refetch {
            return await fetchState(client: client, gen: gen)
        }
        showNotices(outcome.fresh)
        return nil
    }

    /// 从一批新提醒中选出要显示的一条（白名单内、非当前聊天的最后一条）；开关关闭时
    /// 不发布。新提醒替换旧横幅并换 id（界面据此重置 8 秒计时）。文本不写日志。
    private func showNotices(_ fresh: [Notices.Notice]) {
        guard noticesEnabled(), let pick = Notices.pickBanner(fresh, currentTarget: currentTarget) else { return }
        noticeId &+= 1
        snapshot.noticeBanner = NoticeBanner(id: noticeId, text: Notices.banner(pick), target: Notices.target(pick))
        emitSnapshot()
    }

    /// `fullRefetch` 为 true 时不带 since/live 请求整体状态（boot 变化后的重取）。
    private func fetchState(client: DsbotTransportClient, gen: UInt64, fullRefetch: Bool = false) async -> LoopControl {
        let payload = DsbotModels.statePayload(
            since: fullRefetch ? nil : localRevision,
            live: fullRefetch ? nil : liveKey,
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
                snapshot.team = TeamHome.assemble(full)
                snapshot.enterToSend = full.raw.object("prefs")?.bool("enterToSend") ?? true
                markOnline()
            } else if let delta = DsbotModels.decodeStateDelta(value) {
                if let rev = delta.revision { localRevision = rev }
                if let lk = delta.liveKey { liveKey = lk }
                if let current = lastState {
                    let merged = DsbotModels.mergeDelta(state: current, delta: delta)
                    lastState = merged
                    snapshot.botCount = merged.bots.count
                    snapshot.roomCount = merged.rooms.count
                    snapshot.team = TeamHome.assemble(merged)
                    snapshot.enterToSend = merged.raw.object("prefs")?.bool("enterToSend") ?? true
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
