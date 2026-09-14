import Foundation
import XCTest
@testable import ContinuityCore

@MainActor
final class AccessConnectionTests: XCTestCase {
    func testEnabled_addsBothAccessHeadersAndRelayBearerToPublishFetchAndAck() async throws {
        let credentials = try CloudflareAccessCredentials(clientID: "mac-test.access", clientSecret: "cfast_test")
        let reads = LockedCounter()
        let requests = try await roundTrip(enabled: true) { _ = reads.increment(); return credentials }
        XCTAssertEqual(reads.value, 4)
        for request in requests {
            XCTAssertEqual(request.value(forHTTPHeaderField: "CF-Access-Client-Id"), credentials.clientID)
            XCTAssertEqual(request.value(forHTTPHeaderField: "CF-Access-Client-Secret"), credentials.clientSecret)
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-relay-token")
        }
    }

    func testDisabled_neverReadsAccessCredentialsOrSendsAccessHeaders() async throws {
        let reads = LockedCounter()
        let requests = try await roundTrip(enabled: false) {
            _ = reads.increment()
            throw KeychainError.corruptItem
        }
        XCTAssertEqual(reads.value, 0)
        for request in requests {
            XCTAssertNil(request.value(forHTTPHeaderField: "CF-Access-Client-Id"))
            XCTAssertNil(request.value(forHTTPHeaderField: "CF-Access-Client-Secret"))
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-relay-token")
        }
    }

    func testEnabled_missingOrCorruptCredentialsStopBeforeAnyGETOrPOST() async throws {
        for failure in [KeychainError.missingItem, .corruptItem] {
            for hasOutbox in [false, true] {
                let state = try makeState()
                if hasOutbox { _ = try await state.enqueueClipboard(text: "pending", createdAtMs: 1) }
                ScriptedURLProtocol.install([])
                let retries = LockedCounter()
                let dependencies = ConnectionDependencies(state: state, tokenProvider: { "test-relay-token" }, apply: { _ in },
                    sessionConfiguration: { ScriptedURLProtocol.configuration }, sleep: { _ in _ = retries.increment() },
                    accessCredentialsProvider: { throw failure })
                let connection = ConnectionActor(configuration: configuration(enabled: true), dependencies: dependencies)
                let task = await connection.start(); await task.value
                let status = await connection.status
                let description = await connection.failureDescription
                let snapshot = await state.snapshot()
                XCTAssertEqual(status, .authenticationFailed)
                XCTAssertEqual(ScriptedURLProtocol.requestCount, 0)
                XCTAssertEqual(retries.value, 0)
                XCTAssertEqual(snapshot.outbox.count, hasOutbox ? 1 : 0)
                XCTAssertEqual(snapshot.cursor, "0")
                XCTAssertTrue(description?.contains("Access") == true)
                XCTAssertNil(ScriptedURLProtocol.failure)
            }
        }
    }

    func testRedirectAndForbiddenStatuses_stopWithoutRetryOrAcknowledgement() async throws {
        for statusCode in [301, 302, 303, 307, 308, 403] {
            let state = try makeState()
            let pending = try await state.enqueueClipboard(text: "pending", createdAtMs: 1)
            ScriptedURLProtocol.install([.response("POST", "/v1/events", status: statusCode, body: "Access page")])
            let retries = LockedCounter()
            let dependencies = ConnectionDependencies(state: state, tokenProvider: { "test-relay-token" }, apply: { _ in },
                sessionConfiguration: { ScriptedURLProtocol.configuration }, sleep: { _ in _ = retries.increment() },
                accessCredentialsProvider: { try CloudflareAccessCredentials(clientID: "device.access", clientSecret: "cfast_test") })
            let connection = ConnectionActor(configuration: configuration(enabled: true), dependencies: dependencies)
            let task = await connection.start(); await task.value
            let status = await connection.status
            let description = await connection.failureDescription
            let snapshot = await state.snapshot()
            XCTAssertEqual(status, .authenticationFailed)
            XCTAssertEqual(retries.value, 0)
            XCTAssertEqual(ScriptedURLProtocol.requestCount, 1)
            XCTAssertEqual(snapshot.outbox.map(\.eventId), [pending.eventId])
            XCTAssertEqual(snapshot.cursor, "0")
            XCTAssertTrue(description?.contains("설정") == true || description?.contains("정책") == true)
            XCTAssertFalse(description?.contains("cfast_test") == true)
        }
    }

    private func roundTrip(enabled: Bool, credentials: @escaping @Sendable () throws -> CloudflareAccessCredentials) async throws -> [URLRequest] {
        let state = try makeState()
        let outgoing = try await state.enqueueClipboard(text: "outgoing", createdAtMs: 1)
        let incoming = BridgeEvent(eventId: "android-incoming", originDeviceId: "android", originRole: .android,
            originEpoch: "epoch", sequence: 1, createdAtMs: 1, payload: .clipboard(text: "incoming"))
        let encoded = try XCTUnwrap(String(data: EventCodec.encode(incoming), encoding: .utf8))
        ScriptedURLProtocol.install([
            .response("POST", "/v1/events", status: 201,
                body: #"{"accepted":true,"eventId":"\#(outgoing.eventId)","cursor":"1","serverEpoch":"server","idempotent":false}"#),
            .response("GET", "/v1/events", status: 200,
                body: #"{"protocolVersion":1,"serverEpoch":"server","after":"0","nextCursor":"1","events":[{"cursor":"1","event":\#(encoded)}]}"#),
            .response("POST", "/v1/acks", status: 200, body: #"{"acked":["android-incoming"],"alreadyAbsent":[]}"#),
            .response("GET", "/v1/events", status: 401, body: "unauthorized"),
        ])
        let dependencies = ConnectionDependencies(state: state, tokenProvider: { "test-relay-token" }, apply: { _ in },
            sessionConfiguration: { ScriptedURLProtocol.configuration }, accessCredentialsProvider: credentials)
        let connection = ConnectionActor(configuration: configuration(enabled: enabled), dependencies: dependencies)
        let task = await connection.start(); await task.value
        XCTAssertNil(ScriptedURLProtocol.failure)
        let requests = ScriptedURLProtocol.observedRequests
        XCTAssertEqual(requests.map { "\($0.httpMethod ?? "") \($0.url?.path ?? "")" },
                       ["POST /v1/events", "GET /v1/events", "POST /v1/acks", "GET /v1/events"])
        return requests
    }

    private func configuration(enabled: Bool) -> ConnectionConfiguration {
        ConnectionConfiguration(endpoint: ConnectionConfiguration.test.endpoint, tlsPolicy: .systemTrust,
                                longPollMilliseconds: 0, cloudflareAccessEnabled: enabled)
    }

    private func makeState() throws -> DurableStateStore {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return try DurableStateStore(url: directory.appendingPathComponent("state.json"))
    }
}
