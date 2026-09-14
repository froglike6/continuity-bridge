import Foundation
@testable import ContinuityCore

final class LockedFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var stored = false
    var value: Bool { lock.withLock { stored } }
    func set() { lock.withLock { stored = true } }
}

final class LockedCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var stored = 0
    var value: Int { lock.withLock { stored } }
    func increment() -> Int { lock.withLock { stored += 1; return stored } }
}

final class LockedValues: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [UInt64] = []
    var values: [UInt64] { lock.withLock { stored } }
    func append(_ value: UInt64) { lock.withLock { stored.append(value) } }
}

final class AsyncSignal: @unchecked Sendable {
    private let lock = NSLock()
    private var signaled = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func signal() {
        let pending = lock.withLock {
            signaled = true
            defer { waiters.removeAll() }
            return waiters
        }
        pending.forEach { $0.resume() }
    }

    func wait() async {
        await withCheckedContinuation { continuation in
            let resumeNow = lock.withLock {
                if signaled { return true }
                waiters.append(continuation)
                return false
            }
            if resumeNow { continuation.resume() }
        }
    }
}

enum ScriptOutcome: @unchecked Sendable {
    case response(Int, String)
    case failure(URLError.Code)
    case hang
}

struct ScriptStep: @unchecked Sendable {
    let method: String
    let path: String
    let outcome: ScriptOutcome
    let requiredFlag: LockedFlag?

    static func response(_ method: String, _ path: String, status: Int, body: String,
                         after flag: LockedFlag? = nil) -> ScriptStep {
        ScriptStep(method: method, path: path, outcome: .response(status, body), requiredFlag: flag)
    }
    static func failure(_ method: String, _ path: String, code: URLError.Code) -> ScriptStep {
        ScriptStep(method: method, path: path, outcome: .failure(code), requiredFlag: nil)
    }
    static func hang(_ method: String, _ path: String) -> ScriptStep {
        ScriptStep(method: method, path: path, outcome: .hang, requiredFlag: nil)
    }
}

final class ScriptedURLProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) private static var steps: [ScriptStep] = []
    nonisolated(unsafe) private static var mismatch: String?
    nonisolated(unsafe) private static var requests = 0
    nonisolated(unsafe) private static var capturedRequests: [URLRequest] = []
    nonisolated(unsafe) private static var cancellations = 0
    nonisolated(unsafe) private static var cancelledHangs = 0
    nonisolated(unsafe) private static var startSignal: AsyncSignal?
    nonisolated(unsafe) private static var cancellationSignal: AsyncSignal?
    private static let lock = NSLock()
    private var observedCancellationSignal: AsyncSignal?
    private var isHanging = false

    static var configuration: URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ScriptedURLProtocol.self]
        return configuration
    }
    static var requestCount: Int { lock.withLock { requests } }
    static var observedRequests: [URLRequest] { lock.withLock { capturedRequests } }
    static var cancellationCount: Int { lock.withLock { cancellations } }
    static var cancelledHangCount: Int { lock.withLock { cancelledHangs } }
    static var failure: String? { lock.withLock { mismatch } }

    static func install(_ values: [ScriptStep], start: AsyncSignal? = nil,
                        cancellation: AsyncSignal? = nil) {
        lock.withLock {
            steps = values
            mismatch = nil
            requests = 0
            capturedRequests = []
            cancellations = 0
            cancelledHangs = 0
            startSignal = start
            cancellationSignal = cancellation
        }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let result: (ScriptStep?, AsyncSignal?, AsyncSignal?) = Self.lock.withLock {
            Self.requests += 1
            Self.capturedRequests.append(request)
            guard !Self.steps.isEmpty else {
                Self.mismatch = "unexpected request"
                return (nil, Self.startSignal, Self.cancellationSignal)
            }
            return (Self.steps.removeFirst(), Self.startSignal, Self.cancellationSignal)
        }
        let (step, started, cancelled) = result
        observedCancellationSignal = cancelled
        started?.signal()
        guard let step else { fail(URLError(.badServerResponse)); return }
        guard request.httpMethod == step.method, request.url?.path == step.path else {
            Self.lock.withLock { Self.mismatch = "expected \(step.method) \(step.path)" }
            fail(URLError(.badURL)); return
        }
        guard step.requiredFlag?.value != false else {
            Self.lock.withLock { Self.mismatch = "required durable/apply flag not set" }
            fail(URLError(.cannotParseResponse)); return
        }
        switch step.outcome {
        case .response(let status, let body):
            guard let url = request.url,
                  let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: nil,
                                                 headerFields: ["Content-Type": "application/json"]) else {
                fail(URLError(.badServerResponse)); return
            }
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        case .failure(let code): fail(URLError(code))
        case .hang: isHanging = true
        }
    }

    override func stopLoading() {
        Self.lock.withLock {
            Self.cancellations += 1
            if isHanging { Self.cancelledHangs += 1 }
        }
        observedCancellationSignal?.signal()
    }
    private func fail(_ error: Error) { client?.urlProtocol(self, didFailWithError: error) }
}
