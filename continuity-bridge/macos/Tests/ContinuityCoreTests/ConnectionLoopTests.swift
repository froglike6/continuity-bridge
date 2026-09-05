import Foundation
import XCTest
@testable import ContinuityCore

@MainActor
final class ConnectionLoopTests: XCTestCase {
    func testStart_whenPublishAccepted_removesOnlyAcceptedOutboxThen401IsTerminal() async throws {
        let store = try DurableStateStore(url: temporaryStateURL())
        let event = try await store.enqueueClipboard(text: "wire", createdAtMs: 1)
        ScriptedURLProtocol.install([
            .response("POST", "/v1/events", status: 201, body: publishBody(event.eventId)),
            .response("GET", "/v1/events", status: 401, body: #"{"error":"unauthorized"}"#),
        ])
        let connection = makeConnection(store: store)
        let task = await connection.start(); await task.value
        let snapshot = await store.snapshot()
        let status = await connection.status
        XCTAssertTrue(snapshot.outbox.isEmpty)
        XCTAssertEqual(status, .authenticationFailed)
        XCTAssertEqual(ScriptedURLProtocol.requestCount, 2)
        XCTAssertNil(ScriptedURLProtocol.failure)
    }

    func testFetch_whenAppliedBeforeAck_persistsAppliedHighWaterAndCursor() async throws {
        let store = try DurableStateStore(url: temporaryStateURL())
        let event = remoteEvent(id: "remote-5", sequence: 5)
        let applied = LockedFlag()
        ScriptedURLProtocol.install([
            .response("GET", "/v1/events", status: 200, body: fetchBody(event, cursor: "5")),
            .response("POST", "/v1/acks", status: 200, body: ackBody(event.eventId), after: applied),
            .response("GET", "/v1/events", status: 401, body: #"{"error":"unauthorized"}"#),
        ])
        let dependencies = dependencies(store: store, apply: { _ in applied.set() })
        let connection = ConnectionActor(configuration: .test, dependencies: dependencies)
        let task = await connection.start(); await task.value
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.appliedEventIds, [event.eventId])
        XCTAssertEqual(snapshot.highWater["android/epoch"], 5)
        XCTAssertEqual(snapshot.cursor, "5")
        XCTAssertNil(ScriptedURLProtocol.failure)
    }

    func testFetch_whenServerEpochChangesAndTailResets_restartsFromZero() async throws {
        let store = try DurableStateStore(url: temporaryStateURL())
        try await store.updateRelay(serverEpoch: "server-old", cursor: "5")
        ScriptedURLProtocol.install([
            .response("GET", "/v1/events", status: 200,
                      body: #"{"protocolVersion":1,"serverEpoch":"server-new","after":"5","nextCursor":"0","events":[]}"#),
            .response("GET", "/v1/events", status: 401, body: #"{"error":"unauthorized"}"#),
        ])
        let connection = makeConnection(store: store)
        let task = await connection.start(); await task.value
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.serverEpoch, "server-new")
        XCTAssertEqual(snapshot.cursor, "0")
        XCTAssertEqual(ScriptedURLProtocol.requestCount, 2)
    }

    func testFetch_whenReplayOriginMetadataIsFull_withholdsApplyAndAckWithoutMutation() async throws {
        let store = try DurableStateStore(url: temporaryStateURL())
        for index in 0..<DurableStateStore.maximumReplayOriginKeys {
            try await store.markApplied(remoteEvent(id: "bounded-\(index)", sequence: 1,
                                                    epoch: "bounded-epoch-\(index)"))
        }
        let overflow = remoteEvent(id: "bounded-overflow", sequence: 1, epoch: "bounded-epoch-64")
        let applies = LockedCounter()
        ScriptedURLProtocol.install([
            .response("GET", "/v1/events", status: 200, body: fetchBody(overflow, cursor: "1")),
            .response("GET", "/v1/events", status: 401, body: #"{"error":"unauthorized"}"#),
        ])
        let connection = ConnectionActor(configuration: .test,
            dependencies: dependencies(store: store, apply: { _ in _ = applies.increment() }),
            retryPolicy: RetryPolicy(baseMilliseconds: 1, capMilliseconds: 2))
        let task = await connection.start(); await task.value
        let snapshot = await store.snapshot()
        XCTAssertEqual(applies.value, 0)
        XCTAssertEqual(snapshot.highWater.count, DurableStateStore.maximumReplayOriginKeys)
        XCTAssertNil(snapshot.highWater["android/bounded-epoch-64"])
        XCTAssertEqual(snapshot.cursor, "0")
        XCTAssertEqual(ScriptedURLProtocol.requestCount, 2)
        XCTAssertNil(ScriptedURLProtocol.failure)
    }

    func testAck_when503_retryFetchesDuplicateWithoutDoubleApply_thenAdvancesCursor() async throws {
        let store = try DurableStateStore(url: temporaryStateURL())
        let event = remoteEvent(id: "remote-6", sequence: 6)
        let applies = LockedCounter()
        ScriptedURLProtocol.install([
            .response("GET", "/v1/events", status: 200, body: fetchBody(event, cursor: "6")),
            .response("POST", "/v1/acks", status: 503, body: #"{"error":"busy"}"#),
            .response("GET", "/v1/events", status: 200, body: fetchBody(event, cursor: "6")),
            .response("POST", "/v1/acks", status: 200, body: ackBody(event.eventId)),
            .response("GET", "/v1/events", status: 401, body: #"{"error":"unauthorized"}"#),
        ])
        let connection = ConnectionActor(configuration: .test,
            dependencies: dependencies(store: store, apply: { _ in _ = applies.increment() }),
            retryPolicy: RetryPolicy(baseMilliseconds: 1, capMilliseconds: 2))
        let task = await connection.start(); await task.value
        let snapshot = await store.snapshot()
        XCTAssertEqual(applies.value, 1)
        XCTAssertEqual(snapshot.cursor, "6")
        XCTAssertNil(ScriptedURLProtocol.failure)
    }

    func testApplyFailure_whenRetried_doesNotAckUntilSuccessfulApply() async throws {
        enum ApplyError: Error { case firstAttempt }
        let store = try DurableStateStore(url: temporaryStateURL())
        let event = remoteEvent(id: "remote-7", sequence: 7)
        let applies = LockedCounter()
        ScriptedURLProtocol.install([
            .response("GET", "/v1/events", status: 200, body: fetchBody(event, cursor: "7")),
            .response("GET", "/v1/events", status: 200, body: fetchBody(event, cursor: "7")),
            .response("POST", "/v1/acks", status: 200, body: ackBody(event.eventId)),
            .response("GET", "/v1/events", status: 401, body: #"{"error":"unauthorized"}"#),
        ])
        let dependency = dependencies(store: store) { _ in
            if applies.increment() == 1 { throw ApplyError.firstAttempt }
        }
        let connection = ConnectionActor(configuration: .test, dependencies: dependency,
            retryPolicy: RetryPolicy(baseMilliseconds: 1, capMilliseconds: 2))
        let task = await connection.start(); await task.value
        let snapshot = await store.snapshot()
        XCTAssertEqual(applies.value, 2)
        XCTAssertEqual(snapshot.cursor, "7")
        XCTAssertNil(ScriptedURLProtocol.failure)
    }

    func testRetry_whenTimeoutThen503_usesExponentialDelaysBefore401Terminal() async throws {
        let store = try DurableStateStore(url: temporaryStateURL())
        let delays = LockedValues()
        ScriptedURLProtocol.install([
            .failure("GET", "/v1/events", code: .timedOut),
            .response("GET", "/v1/events", status: 503, body: #"{"error":"busy"}"#),
            .response("GET", "/v1/events", status: 401, body: #"{"error":"unauthorized"}"#),
        ])
        let dependency = dependencies(store: store, sleep: { delays.append($0) })
        let connection = ConnectionActor(configuration: .test, dependencies: dependency,
            retryPolicy: RetryPolicy(baseMilliseconds: 100, capMilliseconds: 1_000))
        let task = await connection.start(); await task.value
        let status = await connection.status
        XCTAssertEqual(delays.values, [100, 200])
        XCTAssertEqual(status, .authenticationFailed)
    }

    func testStart_whenConcurrent_hasSingleOwnerAndCancelResumeCreatesOneNewGeneration() async throws {
        let store = try DurableStateStore(url: temporaryStateURL())
        let started = AsyncSignal()
        let cancelled = AsyncSignal()
        ScriptedURLProtocol.install([.hang("GET", "/v1/events")], start: started,
                                    cancellation: cancelled)
        let connection = makeConnection(store: store)
        async let first = connection.start()
        async let second = connection.start()
        let (one, two) = await (first, second)
        let firstGeneration = await connection.generation
        XCTAssertEqual(firstGeneration, 1)
        XCTAssertFalse(one.isCancelled)
        XCTAssertFalse(two.isCancelled)
        await started.wait()
        XCTAssertEqual(ScriptedURLProtocol.requestCount, 1)
        await connection.stop()
        let stopped = await connection.status
        XCTAssertEqual(stopped, .stopped)
        await cancelled.wait()
        XCTAssertEqual(ScriptedURLProtocol.cancellationCount, 1)
        ScriptedURLProtocol.install([.response("GET", "/v1/events", status: 401,
                                              body: #"{"error":"unauthorized"}"#)])
        let resumed = await connection.start(); await resumed.value
        let secondGeneration = await connection.generation
        let finalStatus = await connection.status
        XCTAssertEqual(secondGeneration, 2)
        XCTAssertEqual(finalStatus, .authenticationFailed)
    }

    private func dependencies(store: DurableStateStore,
        apply: @escaping @Sendable (BridgeEvent) async throws -> Void = { _ in },
        sleep: @escaping @Sendable (UInt64) async throws -> Void = { _ in }) -> ConnectionDependencies {
        ConnectionDependencies(state: store, tokenProvider: { "test-token" }, apply: apply,
            sessionConfiguration: { ScriptedURLProtocol.configuration }, sleep: sleep, jitter: { 0 })
    }

    private func makeConnection(store: DurableStateStore) -> ConnectionActor {
        ConnectionActor(configuration: .test, dependencies: dependencies(store: store))
    }

    private func remoteEvent(id: String, sequence: Int64, epoch: String = "epoch") -> BridgeEvent {
        BridgeEvent(eventId: id, originDeviceId: "android", originRole: .android, originEpoch: epoch,
                    sequence: sequence, createdAtMs: sequence, payload: .clipboard(text: "opaque"))
    }

    private func publishBody(_ eventId: String) -> String {
        #"{"accepted":true,"eventId":"\#(eventId)","cursor":"1","serverEpoch":"server","idempotent":false}"#
    }

    private func fetchBody(_ event: BridgeEvent, cursor: String) -> String {
        let encoded = (try? EventCodec.encode(event)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return #"{"protocolVersion":1,"serverEpoch":"server","after":"0","nextCursor":"\#(cursor)","events":[{"cursor":"\#(cursor)","event":\#(encoded)}]}"#
    }

    private func ackBody(_ eventId: String) -> String {
        #"{"acked":["\#(eventId)"],"alreadyAbsent":[]}"#
    }

    private func temporaryStateURL() -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return directory.appendingPathComponent("state.json")
    }
}
