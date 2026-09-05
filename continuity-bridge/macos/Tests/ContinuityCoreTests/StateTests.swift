import XCTest
@testable import ContinuityCore

@MainActor
final class StateTests: XCTestCase {
    func testEpochMetadata_whenOneOriginIsPersisted_preservesReplayAndConflictProtection() async throws {
        let url = temporaryStateURL()
        let event = remoteEvent(id: "epoch-characterization", sequence: 1)
        let store = try DurableStateStore(url: url)
        let accepted = await store.classifyInbound(event)
        XCTAssertEqual(accepted, .accept)
        try await store.markApplied(event)
        let restored = try DurableStateStore(url: url)
        let replay = await restored.classifyInbound(event)
        let conflict = await restored.classifyInbound(remoteEvent(id: "conflict", sequence: 1))
        let snapshot = await restored.snapshot()
        XCTAssertEqual(replay, .duplicate)
        XCTAssertEqual(conflict, .conflict)
        XCTAssertEqual(snapshot.highWater.count, 1)
    }

    func testEpochMetadata_whenAtMaximum_acceptsKnownKeyAndRejectsNewKeyWithoutMutation() async throws {
        let store = try DurableStateStore(url: temporaryStateURL())
        for index in 0..<DurableStateStore.maximumReplayOriginKeys {
            try await store.markApplied(remoteEvent(id: "bounded-\(index)", sequence: 1,
                                                    epoch: "bounded-epoch-\(index)"))
        }
        var snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.highWater.count, DurableStateStore.maximumReplayOriginKeys)
        let existing = remoteEvent(id: "bounded-existing", sequence: 2, epoch: "bounded-epoch-63")
        let existingDisposition = await store.classifyInbound(existing)
        XCTAssertEqual(existingDisposition, .accept)
        try await store.markApplied(existing)
        snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.highWater.count, DurableStateStore.maximumReplayOriginKeys)
        XCTAssertEqual(snapshot.highWater["android/bounded-epoch-63"], 2)

        let overflow = remoteEvent(id: "bounded-overflow", sequence: 1, epoch: "bounded-epoch-64")
        let overflowDisposition = await store.classifyInbound(overflow)
        let before = await store.snapshot()
        XCTAssertEqual(overflowDisposition, .conflict)
        do {
            try await store.markApplied(overflow)
            XCTFail("over-limit origin key must fail closed")
        } catch {}
        let after = await store.snapshot()
        XCTAssertEqual(after, before)
    }

    func testEpochMetadata_whenOversizedStateIsRestored_failsClosed() throws {
        let url = temporaryStateURL()
        let highWater = Dictionary(uniqueKeysWithValues: (0...DurableStateStore.maximumReplayOriginKeys).map { ("android/epoch-\($0)", Int64(1)) })
        let invalid = ClientState(deviceId: "device", originEpoch: "epoch", nextSequence: 1, outbox: [],
                                  appliedEventIds: [], serverEpoch: nil, cursor: "0", highWater: highWater,
                                  inboundSequenceIds: [:])
        try JSONEncoder().encode(invalid).write(to: url)
        XCTAssertThrowsError(try DurableStateStore(url: url))

        let duplicateURL = temporaryStateURL()
        let valid = ClientState(deviceId: "device", originEpoch: "epoch", nextSequence: 1, outbox: [],
                                appliedEventIds: [], serverEpoch: nil, cursor: "0", highWater: [:],
                                inboundSequenceIds: [:])
        let encoded = String(decoding: try JSONEncoder().encode(valid), as: UTF8.self)
        let duplicate = encoded.replacingOccurrences(of: #""highWater":{}"#,
                                                      with: #""highWater":{},"highWater":{}"#)
        XCTAssertNotEqual(duplicate, encoded)
        try Data(duplicate.utf8).write(to: duplicateURL)
        XCTAssertThrowsError(try DurableStateStore(url: duplicateURL))
    }

    func testOutbox_whenRestarted_preservesIdentityAndAckRemovesOnlyAcknowledged() async throws {
        let url = temporaryStateURL()
        let store = try DurableStateStore(url: url, appliedLimit: 3)
        let first = try await store.enqueueClipboard(text: "first", createdAtMs: 1)
        let restarted = try DurableStateStore(url: url, appliedLimit: 3)
        let retry = await restarted.snapshot().outbox[0]
        XCTAssertEqual(first, retry)
        _ = try await store.enqueueClipboard(text: "second", createdAtMs: 2)
        try await store.acknowledgeOutbox(eventIds: [first.eventId])
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.outbox.map(\.payload), [.clipboard(text: "second")])
    }

    func testAppliedIDs_whenOverLimit_evictsOldestAndPersistsCursorHighWater() async throws {
        let url = temporaryStateURL()
        let store = try DurableStateStore(url: url, appliedLimit: 3)
        for value in 1...4 { try await store.markApplied(remoteEvent(id: "e\(value)", sequence: Int64(value))) }
        try await store.updateRelay(serverEpoch: "server-a", cursor: "9")
        let restarted = try DurableStateStore(url: url, appliedLimit: 3)
        let loaded = await restarted.snapshot()
        XCTAssertEqual(loaded.appliedEventIds, ["e2", "e3", "e4"])
        XCTAssertEqual(loaded.cursor, "9")
        XCTAssertEqual(loaded.highWater["android/epoch"], 4)
    }

    func testState_whenTruncatedOrSemanticallyForged_failsClosed() throws {
        let truncated = temporaryStateURL()
        try Data("{".utf8).write(to: truncated)
        XCTAssertThrowsError(try DurableStateStore(url: truncated))
        let forged = temporaryStateURL()
        let invalid = ClientState(deviceId: "", originEpoch: "epoch", nextSequence: 1, outbox: [],
                                  appliedEventIds: [], serverEpoch: nil, cursor: "0", highWater: [:],
                                  inboundSequenceIds: [:])
        try JSONEncoder().encode(invalid).write(to: forged)
        XCTAssertThrowsError(try DurableStateStore(url: forged))
        let invalidKey = temporaryStateURL()
        let forgedKey = ClientState(deviceId: "device", originEpoch: "epoch", nextSequence: 1, outbox: [],
                                    appliedEventIds: ["event"], serverEpoch: nil, cursor: "0",
                                    highWater: ["": 1], inboundSequenceIds: ["": "event"])
        try JSONEncoder().encode(forgedKey).write(to: invalidKey)
        XCTAssertThrowsError(try DurableStateStore(url: invalidKey))
    }

    func testRelayEpoch_whenChanged_resetsCursorButKeepsOutboxAndAppliedState() async throws {
        let store = try DurableStateStore(url: temporaryStateURL())
        let event = try await store.enqueueClipboard(text: "retained", createdAtMs: 1)
        try await store.markApplied(remoteEvent(id: "remote", sequence: 2))
        try await store.updateRelay(serverEpoch: "server-a", cursor: "8")
        try await store.updateRelay(serverEpoch: "server-b", cursor: "9")
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.cursor, "0")
        XCTAssertEqual(snapshot.outbox, [event])
        XCTAssertEqual(snapshot.appliedEventIds, ["remote"])
    }

    func testInboundOrdering_whenDuplicateStaleAndConflict_areDistinctAcrossRestart() async throws {
        let url = temporaryStateURL()
        let store = try DurableStateStore(url: url)
        try await store.markApplied(remoteEvent(id: "accepted", sequence: 3))
        let restarted = try DurableStateStore(url: url)
        let duplicate = await restarted.classifyInbound(remoteEvent(id: "accepted", sequence: 3))
        let conflict = await restarted.classifyInbound(remoteEvent(id: "other", sequence: 3))
        let stale = await restarted.classifyInbound(remoteEvent(id: "older", sequence: 2))
        let accept = await restarted.classifyInbound(remoteEvent(id: "new", sequence: 4))
        XCTAssertEqual([duplicate, conflict, stale, accept], [.duplicate, .conflict, .stale, .accept])
    }

    func testConcurrentEnqueue_whenFiftyWriters_preservesUniqueMonotonicSequencesAndAtomicRestart() async throws {
        let url = temporaryStateURL()
        let store = try DurableStateStore(url: url)
        let sequences = try await withThrowingTaskGroup(of: Int64.self) { group in
            for value in 1...50 {
                group.addTask { try await store.enqueueClipboard(text: "v\(value)", createdAtMs: Int64(value)).sequence }
            }
            var values: [Int64] = []
            for try await value in group { values.append(value) }
            return values
        }
        XCTAssertEqual(Set(sequences), Set((1...50).map(Int64.init)))
        let restarted = try DurableStateStore(url: url)
        let loaded = await restarted.snapshot()
        XCTAssertEqual(loaded.nextSequence, 51)
        XCTAssertEqual(loaded.outbox.count, 1)
    }

    func testPersistenceInterruption_whenAtomicWriteFails_keepsMemoryUnchanged() async throws {
        let url = temporaryStateURL()
        let store = try DurableStateStore(url: url)
        let directory = url.deletingLastPathComponent()
        try FileManager.default.removeItem(at: directory)
        try Data("blocked-directory".utf8).write(to: directory)
        do {
            _ = try await store.enqueueClipboard(text: "must-not-commit", createdAtMs: 1)
            XCTFail("persistence failure must fail closed")
        } catch {}
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.nextSequence, 1)
        XCTAssertTrue(snapshot.outbox.isEmpty)
    }

    private func remoteEvent(id: String, sequence: Int64, epoch: String = "epoch") -> BridgeEvent {
        BridgeEvent(eventId: id, originDeviceId: "android", originRole: .android, originEpoch: epoch,
                    sequence: sequence, createdAtMs: sequence, payload: .clipboard(text: "x"))
    }

    private func temporaryStateURL() -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try! FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return directory.appendingPathComponent("state.json")
    }
}
