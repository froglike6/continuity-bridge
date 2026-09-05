import Foundation

public enum ConnectionStatus: String, Codable, Sendable {
    case disconnected, connecting, connected, authenticationFailed, tlsFailed, permissionRequired, retrying, stopped

    public var koreanText: String {
        switch self {
        case .disconnected: "연결 안 됨"
        case .connecting: "연결 중"
        case .connected: "연결됨"
        case .authenticationFailed: "인증 실패"
        case .tlsFailed: "보안 연결 확인 필요"
        case .permissionRequired: "권한 필요"
        case .retrying: "잠시 후 다시 시도"
        case .stopped: "서비스 중지됨"
        }
    }
}

public struct ConnectionConfiguration: Sendable {
    public let endpoint: URL
    public let tlsPolicy: TLSPolicy
    public let longPollMilliseconds: Int

    public init(endpoint: URL, tlsPolicy: TLSPolicy, longPollMilliseconds: Int = 25_000) {
        self.endpoint = endpoint
        self.tlsPolicy = tlsPolicy
        self.longPollMilliseconds = min(max(longPollMilliseconds, 0), 25_000)
    }

    public static var test: ConnectionConfiguration {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "localhost"
        return ConnectionConfiguration(endpoint: components.url ?? URL(fileURLWithPath: "/"),
                                       tlsPolicy: .systemTrust, longPollMilliseconds: 0)
    }
}

public struct ConnectionDependencies: Sendable {
    public let state: DurableStateStore
    public let tokenProvider: @Sendable () throws -> String
    public let apply: @Sendable (BridgeEvent) async throws -> Void
    public let sessionConfiguration: @Sendable () -> URLSessionConfiguration
    public let sleep: @Sendable (UInt64) async throws -> Void
    public let jitter: @Sendable () -> Double

    public init(state: DurableStateStore, tokenProvider: @escaping @Sendable () throws -> String,
                apply: @escaping @Sendable (BridgeEvent) async throws -> Void,
                sessionConfiguration: @escaping @Sendable () -> URLSessionConfiguration = { .ephemeral },
                sleep: @escaping @Sendable (UInt64) async throws -> Void = { milliseconds in
                    try await Task.sleep(for: .milliseconds(milliseconds))
                }, jitter: @escaping @Sendable () -> Double = { Double.random(in: 0...1) }) {
        self.state = state
        self.tokenProvider = tokenProvider
        self.apply = apply
        self.sessionConfiguration = sessionConfiguration
        self.sleep = sleep
        self.jitter = jitter
    }

    public static func test(state: DurableStateStore,
                            sessionConfiguration: @escaping @Sendable () -> URLSessionConfiguration) -> ConnectionDependencies {
        ConnectionDependencies(state: state, tokenProvider: { "test-token" }, apply: { _ in },
                               sessionConfiguration: sessionConfiguration, sleep: { _ in }, jitter: { 0 })
    }
}

public struct PublishResponse: Decodable, Sendable {
    public let accepted: Bool
    public let eventId: String
    public let cursor: String
    public let serverEpoch: String
    public let idempotent: Bool
}

public struct FetchEntry: Decodable, Sendable { public let cursor: String; public let event: BridgeEvent }
public struct FetchResponse: Decodable, Sendable {
    public let protocolVersion: Int
    public let serverEpoch: String
    public let after: String
    public let nextCursor: String
    public let events: [FetchEntry]
}
public struct AckResponse: Decodable, Sendable { public let acked: [String]; public let alreadyAbsent: [String] }

public enum TransportError: Error, Equatable {
    case invalidURL, invalidResponse, malformedResponse, applicationFailed, httpStatus(Int), cancelled
}
