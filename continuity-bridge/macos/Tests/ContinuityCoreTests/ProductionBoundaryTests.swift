import Foundation
import XCTest
@testable import ContinuityCore

@MainActor
final class ProductionBoundaryTests: XCTestCase {
    func testStrictBoundary_whenDuplicateMembersAtTopAndPayloadDepth_rejects() {
        let top = Data(#"{"protocolVersion":1,"eventId":"first","eventId":"second","originDeviceId":"d","originRole":"android","originEpoch":"e","sequence":1,"kind":"clipboard.text","createdAtMs":0,"payload":{"text":"x"}}"#.utf8)
        let nested = Data(#"{"protocolVersion":1,"eventId":"first","originDeviceId":"d","originRole":"android","originEpoch":"e","sequence":1,"kind":"clipboard.text","createdAtMs":0,"payload":{"text":"x","text":"y"}}"#.utf8)
        XCTAssertThrowsError(try EventCodec.decode(top))
        XCTAssertThrowsError(try EventCodec.decode(nested))
    }

    func testNumericBoundary_whenUnsafeIntegerNonFiniteOrExpiryInvalid_rejects() {
        let maximum = Int64(9_007_199_254_740_991)
        let payload = EventPayload.clipboard(text: "x")
        XCTAssertThrowsError(try BridgeEvent(eventId: "e", originDeviceId: "d", originRole: .android,
            originEpoch: "o", sequence: maximum + 1, createdAtMs: 0, payload: payload).validate())
        XCTAssertThrowsError(try BridgeEvent(eventId: "e", originDeviceId: "d", originRole: .android,
            originEpoch: "o", sequence: 1, createdAtMs: maximum + 1, payload: payload).validate())
        XCTAssertThrowsError(try BridgeEvent(eventId: "e", originDeviceId: "d", originRole: .android,
            originEpoch: "o", sequence: 1, createdAtMs: 2, expiresAtMs: 1, payload: payload).validate())
        let nonFinite = Data(#"{"protocolVersion":1,"eventId":"e","originDeviceId":"d","originRole":"android","originEpoch":"o","sequence":NaN,"kind":"clipboard.text","createdAtMs":0,"payload":{"text":"x"}}"#.utf8)
        XCTAssertThrowsError(try EventCodec.decode(nonFinite))
    }

    func testPublish_whenMalformedDuplicateMember2xx_doesNotRemoveOutbox() async throws {
        let store = try DurableStateStore(url: temporaryStateURL())
        let event = try await store.enqueueClipboard(text: "opaque", createdAtMs: 1)
        let malformed = #"{"accepted":true,"accepted":true,"eventId":"\#(event.eventId)","cursor":"1","serverEpoch":"server","idempotent":false}"#
        ScriptedURLProtocol.install([
            .response("POST", "/v1/events", status: 201, body: malformed),
            .response("POST", "/v1/events", status: 401, body: #"{"error":"unauthorized"}"#),
        ])
        let connection = makeConnection(store: store)
        let task = await connection.start()
        await task.value
        let snapshot = await store.snapshot()
        let status = await connection.status
        XCTAssertEqual(snapshot.outbox.map(\.eventId), [event.eventId])
        XCTAssertEqual(status, .authenticationFailed)
        XCTAssertNil(ScriptedURLProtocol.failure)
    }

    private func makeConnection(store: DurableStateStore) -> ConnectionActor {
        ConnectionActor(configuration: .test, dependencies: .test(state: store,
            sessionConfiguration: { ScriptedURLProtocol.configuration }),
            retryPolicy: RetryPolicy(baseMilliseconds: 1, capMilliseconds: 2))
    }

    private func temporaryStateURL() -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return directory.appendingPathComponent("state.json")
    }
}
