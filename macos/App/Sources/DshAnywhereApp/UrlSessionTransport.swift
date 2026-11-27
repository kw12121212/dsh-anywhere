import DshCore
import Foundation

/// 禁用 `URLSession` 自动重定向（API 传输由 `SessionEstablisher` 显式控制同源重定向；
/// 诊断发送则拒绝跟随，3xx 视为失败保留队列——端点必须是用户配置的最终目标）。
final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

/// 禁用系统 cookie 与缓存的 ephemeral 会话配置（诊断发送与 API 传输共用；
/// 自动重定向的禁用由各会话挂载的 `NoRedirectDelegate` 承担，非配置项）。
func makeEphemeralConfiguration(requestTimeout: TimeInterval, resourceTimeout: TimeInterval) -> URLSessionConfiguration {
    let config = URLSessionConfiguration.ephemeral
    config.httpShouldSetCookies = false
    config.httpCookieStorage = nil
    config.httpCookieAcceptPolicy = .never
    config.urlCache = nil
    config.requestCachePolicy = .reloadIgnoringLocalCacheData
    config.timeoutIntervalForRequest = requestTimeout
    config.timeoutIntervalForResource = resourceTimeout
    return config
}

/// 基于 ephemeral `URLSession` 的 `HttpTransport` 实现。
final class UrlSessionHttpTransport: HttpTransport, Sendable {
    private let session: URLSession
    private let delegate: NoRedirectDelegate

    init(requestTimeout: TimeInterval, resourceTimeout: TimeInterval) {
        let delegate = NoRedirectDelegate()
        self.delegate = delegate
        self.session = URLSession(
            configuration: makeEphemeralConfiguration(
                requestTimeout: requestTimeout,
                resourceTimeout: resourceTimeout
            ),
            delegate: delegate,
            delegateQueue: nil
        )
    }

    deinit {
        session.invalidateAndCancel()
    }

    func send(_ request: HttpRequest) async throws -> HttpResponse {
        guard let url = URL(string: request.url) else {
            throw TransportError(.other)
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = request.method
        urlRequest.httpBody = request.body
        urlRequest.httpShouldHandleCookies = false
        for header in request.headers {
            urlRequest.setValue(header.value, forHTTPHeaderField: header.name)
        }

        let (data, rawResponse) = try await session.data(for: urlRequest)
        guard let http = rawResponse as? HTTPURLResponse else {
            throw TransportError(.other)
        }

        var responseHeaders: [(name: String, value: String)] = []
        for (rawKey, rawValue) in http.allHeaderFields {
            guard let name = rawKey as? String, let value = rawValue as? String else { continue }
            responseHeaders.append((name: name, value: value))
        }
        return HttpResponse(statusCode: http.statusCode, headers: responseHeaders, body: data)
    }
}

/// 控制 SSE 握手 `CheckedContinuation` 与底层 `readerTask` 取消的线程安全门闩。
private final class SseOpenGate: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<AsyncThrowingStream<String, Error>, Error>?
    private var readerTask: Task<Void, Never>?
    private var cancelled = false

    func setContinuation(_ cont: CheckedContinuation<AsyncThrowingStream<String, Error>, Error>) {
        let shouldCancelImmediately: Bool = lock.withLock {
            if cancelled {
                return true
            }
            continuation = cont
            return false
        }
        if shouldCancelImmediately {
            cont.resume(throwing: CancellationError())
        }
    }

    func setTask(_ task: Task<Void, Never>) {
        let shouldCancelImmediately: Bool = lock.withLock {
            readerTask = task
            return cancelled
        }
        if shouldCancelImmediately {
            task.cancel()
        }
    }

    func resume(returning stream: AsyncThrowingStream<String, Error>) -> Bool {
        let cont: CheckedContinuation<AsyncThrowingStream<String, Error>, Error>? = lock.withLock {
            let c = continuation
            continuation = nil
            return c
        }
        cont?.resume(returning: stream)
        return cont != nil
    }

    func resume(throwing error: Error) -> Bool {
        let cont: CheckedContinuation<AsyncThrowingStream<String, Error>, Error>? = lock.withLock {
            let c = continuation
            continuation = nil
            return c
        }
        cont?.resume(throwing: error)
        return cont != nil
    }

    func cancel() {
        let (cont, task): (CheckedContinuation<AsyncThrowingStream<String, Error>, Error>?, Task<Void, Never>?) = lock.withLock {
            cancelled = true
            let c = continuation
            continuation = nil
            return (c, readerTask)
        }
        task?.cancel()
        cont?.resume(throwing: CancellationError())
    }
}

/// 基于 `URLSession.bytes(for:)` 的原始字节流 SSE 适配器（按 `0x0A` 换行切分，保留空行）。
final class UrlSessionSseStream: SseStream, Sendable {
    private let session: URLSession
    private let delegate: NoRedirectDelegate

    init() {
        let delegate = NoRedirectDelegate()
        self.delegate = delegate
        self.session = URLSession(
            configuration: makeEphemeralConfiguration(
                requestTimeout: 60,
                resourceTimeout: 60 * 60 * 24 * 7
            ),
            delegate: delegate,
            delegateQueue: nil
        )
    }

    deinit {
        session.invalidateAndCancel()
    }

    func open(url: String, headers: [(name: String, value: String)]) async throws -> AsyncThrowingStream<String, Error> {
        guard let target = URL(string: url) else {
            throw TransportError(.other)
        }
        var mutableRequest = URLRequest(url: target)
        mutableRequest.httpMethod = "GET"
        mutableRequest.httpShouldHandleCookies = false
        for header in headers {
            mutableRequest.setValue(header.value, forHTTPHeaderField: header.name)
        }
        let urlRequest = mutableRequest

        let gate = SseOpenGate()
        let (stream, continuation) = AsyncThrowingStream<String, Error>.makeStream()
        let session = self.session

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { openCont in
                gate.setContinuation(openCont)
                let readerTask = Task {
                    do {
                        let (bytes, rawResponse) = try await session.bytes(for: urlRequest)
                        guard let http = rawResponse as? HTTPURLResponse else {
                            _ = gate.resume(throwing: FeedStreamError.invalidResponse)
                            return
                        }
                        if http.statusCode == 401 {
                            _ = gate.resume(throwing: FeedStreamError.authExpired)
                            return
                        }
                        if http.statusCode == 403 {
                            _ = gate.resume(throwing: FeedStreamError.forbiddenHost)
                            return
                        }
                        guard http.statusCode == 200 else {
                            _ = gate.resume(throwing: FeedStreamError.httpStatus(http.statusCode))
                            return
                        }

                        guard gate.resume(returning: stream) else {
                            return
                        }

                        // 严禁使用 AsyncBytes.lines：其会跳过空行导致 SSE 事件帧无法分割。
                        var lineBuffer: [UInt8] = []
                        for try await byte in bytes {
                            if Task.isCancelled { break }
                            lineBuffer.append(byte)
                            if byte == 0x0A {
                                continuation.yield(String(decoding: lineBuffer, as: UTF8.self))
                                lineBuffer.removeAll(keepingCapacity: true)
                            }
                        }
                        if !lineBuffer.isEmpty && !Task.isCancelled {
                            continuation.yield(String(decoding: lineBuffer, as: UTF8.self))
                        }
                        continuation.finish()
                    } catch {
                        if !gate.resume(throwing: error) {
                            continuation.finish(throwing: error)
                        }
                    }
                }
                continuation.onTermination = { @Sendable _ in
                    readerTask.cancel()
                }
                gate.setTask(readerTask)
                if Task.isCancelled {
                    gate.cancel()
                }
            }
        } onCancel: {
            gate.cancel()
        }
    }
}
