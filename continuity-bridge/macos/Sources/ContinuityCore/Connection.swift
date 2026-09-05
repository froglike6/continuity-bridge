import Foundation

public actor ConnectionActor {
    public private(set) var status: ConnectionStatus = .stopped
    public private(set) var generation = 0
    private let configuration: ConnectionConfiguration
    private let dependencies: ConnectionDependencies
    private let retryPolicy: RetryPolicy
    private let session: URLSession
    private let delegate: PinnedSessionDelegate
    private var runTask: Task<Void, Never>?

    public init(configuration: ConnectionConfiguration, dependencies: ConnectionDependencies,
                retryPolicy: RetryPolicy = RetryPolicy()) {
        self.configuration = configuration
        self.dependencies = dependencies
        self.retryPolicy = retryPolicy
        delegate = PinnedSessionDelegate(policy: configuration.tlsPolicy)
        session = URLSession(configuration: dependencies.sessionConfiguration(), delegate: delegate, delegateQueue: nil)
    }

    @discardableResult
    public func start() -> Task<Void, Never> {
        if let runTask { return runTask }
        generation += 1
        let currentGeneration = generation
        status = .connecting
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
        let tasks = await session.allTasks
        tasks.forEach { $0.cancel() }
        await task?.value
        runTask = nil
        status = .stopped
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
            var request = makeRequest(path: "/v1/events", method: "POST", token: token)
            request.httpBody = try EventCodec.encode(event)
            let result = try await send(request, as: PublishResponse.self)
            try ResponseValidation.publish(result.value, status: result.status, expectedEventId: event.eventId)
            _ = try await dependencies.state.observeServerEpoch(result.value.serverEpoch)
            try await dependencies.state.acknowledgeOutbox(eventIds: [event.eventId])
        }
    }

    private func fetchApplyAndAck(token: String) async throws {
        let snapshot = await dependencies.state.snapshot()
        let request = try fetchRequest(after: snapshot.cursor, token: token)
        let result = try await send(request, as: FetchResponse.self)
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
        var request = makeRequest(path: "/v1/acks", method: "POST", token: token)
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
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return request
    }

    private func makeRequest(path: String, method: String, token: String) -> URLRequest {
        var request = URLRequest(url: configuration.endpoint.appendingPathComponent(path))
        request.httpMethod = method
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return request
    }

    private func send<T: Decodable>(_ request: URLRequest, as type: T.Type) async throws -> (status: Int, value: T) {
        let data: Data
        let response: URLResponse
        do { (data, response) = try await session.data(for: request) }
        catch let error as URLError where error.code == .cancelled { throw TransportError.cancelled }
        guard let http = response as? HTTPURLResponse else { throw TransportError.invalidResponse }
        guard (200...299).contains(http.statusCode) else { throw TransportError.httpStatus(http.statusCode) }
        guard data.count <= 1_114_112 else { throw TransportError.malformedResponse }
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
}
