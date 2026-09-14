import Foundation

public actor ConnectionActor {
    public private(set) var status: ConnectionStatus = .stopped
    public private(set) var failureDescription: String?
    public private(set) var generation = 0
    private let configuration: ConnectionConfiguration
    private let dependencies: ConnectionDependencies
    private let retryPolicy: RetryPolicy
    private let session: URLSession
    private var runTask: Task<Void, Never>?
    private var pendingFetch: Task<(status: Int, value: FetchResponse), Error>?

    public init(configuration: ConnectionConfiguration, dependencies: ConnectionDependencies,
                retryPolicy: RetryPolicy = RetryPolicy()) {
        self.configuration = configuration
        self.dependencies = dependencies
        self.retryPolicy = retryPolicy
        session = URLSession(configuration: dependencies.sessionConfiguration())
    }

    @discardableResult
    public func start() -> Task<Void, Never> {
        if let runTask { return runTask }
        generation += 1
        let currentGeneration = generation
        status = .connecting
        failureDescription = nil
        let task = Task { [weak self] in
            guard let self else { return }
            await self.runLoop(generation: currentGeneration)
        }
        runTask = task
        return task
    }

    public func stop() async {
        let task = runTask
        task?.cancel()
        pendingFetch?.cancel()
        let tasks = await session.allTasks
        tasks.forEach { $0.cancel() }
        await task?.value
        runTask = nil
        status = .stopped
        failureDescription = nil
    }

    public func outboxChanged() {
        pendingFetch?.cancel()
    }

    private func runLoop(generation expectedGeneration: Int) async {
        var attempt = 1
        while !Task.isCancelled {
            do {
                let token = try dependencies.tokenProvider()
                try await flushOutbox(token: token)
                try await fetchApplyAndAck(token: token)
                status = .connected
                attempt = 1
            } catch {
                if Task.isCancelled || error as? TransportError == .cancelled { break }
                switch disposition(for: error) {
                case .terminalAuthentication:
                    status = .authenticationFailed
                    failureDescription = authenticationDescription(for: error)
                    break
                case .terminal:
                    status = terminalStatus(for: error)
                    break
                case .retryable:
                    status = .retrying
                    let delay = retryPolicy.delay(attempt: attempt, jitterUnit: dependencies.jitter())
                    attempt += 1
                    do { try await dependencies.sleep(delay) }
                    catch { break }
                    continue
                case .success:
                    continue
                }
                break
            }
        }
        if runTask != nil, generation == expectedGeneration { runTask = nil }
    }

    private func flushOutbox(token: String) async throws {
        while let event = await dependencies.state.snapshot().outbox.first {
            var request = try makeRequest(path: "/v1/events", method: "POST", token: token)
            request.httpBody = try EventCodec.encode(event)
            let result = try await send(request, as: PublishResponse.self)
            try ResponseValidation.publish(result.value, status: result.status, expectedEventId: event.eventId)
            _ = try await dependencies.state.observeServerEpoch(result.value.serverEpoch)
            try await dependencies.state.acknowledgeOutbox(eventIds: [event.eventId])
        }
    }

    private func fetchApplyAndAck(token: String) async throws {
        let snapshot = await dependencies.state.snapshot()
        guard snapshot.outbox.isEmpty else { return }
        let request = try fetchRequest(after: snapshot.cursor, token: token)
        let fetch = Task { try await send(request, as: FetchResponse.self) }
        pendingFetch = fetch
        defer { pendingFetch = nil }
        let result: (status: Int, value: FetchResponse)
        do { result = try await fetch.value }
        catch {
            if fetch.isCancelled && !Task.isCancelled { return }
            throw error
        }
        guard result.status == 200 else { throw TransportError.malformedResponse }
        let epochChanged = try ResponseValidation.fetch(result.value, expectedAfter: snapshot.cursor,
                                                        expectedServerEpoch: snapshot.serverEpoch)
        if epochChanged {
            _ = try await dependencies.state.observeServerEpoch(result.value.serverEpoch)
            return
        }
        _ = try await dependencies.state.observeServerEpoch(result.value.serverEpoch)
        for entry in result.value.events {
            let disposition = await dependencies.state.classifyInbound(entry.event)
            switch disposition {
            case .accept:
                do { try await dependencies.apply(entry.event) }
                catch let error as NotificationApplyError { throw error }
                catch { throw TransportError.applicationFailed }
                try await dependencies.state.markApplied(entry.event)
            case .duplicate:
                break
            case .stale, .conflict:
                throw TransportError.malformedResponse
            }
            try await acknowledge(entry.event.eventId, token: token)
            try await dependencies.state.advanceCursor(entry.cursor)
        }
        try await dependencies.state.advanceCursor(result.value.nextCursor)
    }

    private func acknowledge(_ eventId: String, token: String) async throws {
        struct Ack: Encodable {
            let protocolVersion = 1
            let recipientDeviceId: String
            let recipientRole: DeviceRole
            let eventIds: [String]
        }
        let snapshot = await dependencies.state.snapshot()
        var request = try makeRequest(path: "/v1/acks", method: "POST", token: token)
        request.httpBody = try JSONEncoder().encode(Ack(recipientDeviceId: snapshot.deviceId,
                                                       recipientRole: .macOS, eventIds: [eventId]))
        let result = try await send(request, as: AckResponse.self)
        guard result.status == 200 else { throw TransportError.malformedResponse }
        try ResponseValidation.ack(result.value, expectedEventId: eventId)
    }

    private func fetchRequest(after: String, token: String) throws -> URLRequest {
        guard var components = URLComponents(url: configuration.endpoint.appendingPathComponent("v1/events"),
                                             resolvingAgainstBaseURL: false) else { throw TransportError.invalidURL }
        components.queryItems = [URLQueryItem(name: "after", value: after),
                                 URLQueryItem(name: "waitMs", value: String(configuration.longPollMilliseconds))]
        guard let url = components.url else { throw TransportError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        try authenticate(&request, token: token)
        return request
    }

    private func makeRequest(path: String, method: String, token: String) throws -> URLRequest {
        var request = URLRequest(url: configuration.endpoint.appendingPathComponent(path))
        request.httpMethod = method
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        try authenticate(&request, token: token)
        return request
    }

    private func authenticate(_ request: inout URLRequest, token: String) throws {
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        guard configuration.cloudflareAccessEnabled else { return }
        let credentials: CloudflareAccessCredentials
        do { credentials = try dependencies.accessCredentialsProvider() }
        catch { throw TransportError.accessCredentialsUnavailable }
        credentials.apply(to: &request)
    }

    private func send<T: Decodable>(_ request: URLRequest, as type: T.Type) async throws -> (status: Int, value: T) {
        let data: Data
        let response: URLResponse
        let delegate = PinnedSessionDelegate(policy: configuration.tlsPolicy)
        do { (data, response) = try await session.data(for: request, delegate: delegate) }
        catch {
            if let rejection = delegate.validationError { throw rejection }
            if (error as? URLError)?.code == .cancelled { throw TransportError.cancelled }
            throw error
        }
        guard let http = response as? HTTPURLResponse else { throw TransportError.invalidResponse }
        guard (200...299).contains(http.statusCode) else { throw TransportError.httpStatus(http.statusCode) }
        let responseLimit = request.httpMethod == "GET" && request.url?.path == "/v1/events" ? WireLimits.responseBodyBytes : 1_114_112
        guard data.count <= responseLimit else { throw TransportError.malformedResponse }
        do { return (http.statusCode, try StrictJSON.decode(type, from: data)) }
        catch { throw TransportError.malformedResponse }
    }

    private func disposition(for error: Error) -> FailureDisposition {
        if case TransportError.httpStatus(let status) = error {
            return RetryPolicy.classify(statusCode: status, error: nil)
        }
        if case TransportError.malformedResponse = error { return .retryable }
        if case TransportError.invalidResponse = error { return .retryable }
        if case TransportError.applicationFailed = error { return .retryable }
        if let notification = error as? NotificationApplyError {
            switch notification {
            case .denied, .notDetermined, .invalidEvent: return .terminal
            case .addFailed: return .retryable
            }
        }
        return RetryPolicy.classify(statusCode: nil, error: error)
    }

    private func terminalStatus(for error: Error) -> ConnectionStatus {
        if error is TLSValidationError { return .tlsFailed }
        if error is KeychainError { return .authenticationFailed }
        if let notification = error as? NotificationApplyError,
           notification == .denied || notification == .notDetermined { return .permissionRequired }
        if let code = (error as? URLError)?.code,
           [.serverCertificateUntrusted, .serverCertificateHasBadDate,
            .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid,
            .secureConnectionFailed].contains(code) { return .tlsFailed }
        return .disconnected
    }

    private func authenticationDescription(for error: Error) -> String {
        switch error {
        case TransportError.accessCredentialsUnavailable:
            return "이 Mac의 Cloudflare Access 자격증명을 읽지 못했습니다. 설정에서 다시 저장해 주세요."
        case TransportError.httpStatus(300...399):
            return "인증 페이지로의 이동을 차단했습니다. 서버 주소와 Cloudflare Access 설정을 확인해 주세요."
        case TransportError.httpStatus(403):
            return configuration.cloudflareAccessEnabled
                ? "접근이 거부되었습니다. Access 자격증명·서비스 인증 정책과 릴레이 토큰을 확인해 주세요."
                : "접근이 거부되었습니다. 설정에서 이 Mac의 릴레이 인증 토큰을 확인해 주세요."
        default:
            return "릴레이 인증에 실패했습니다. 이 Mac의 인증 토큰을 확인해 주세요."
        }
    }
}
