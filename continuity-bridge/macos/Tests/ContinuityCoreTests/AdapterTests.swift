import AppKit
import XCTest
@testable import ContinuityCore

@MainActor
final class AdapterTests: XCTestCase {
    func testPasteboardMarkerType_whenProductionValue_isExactPrivateIdentifier() {
        XCTAssertEqual(PasteboardSynchronizer.eventIDType.rawValue,
                       "com.froglike6.continuitybridge.event-id")
    }

    func testPasteboardApply_whenUnicodeMultiline_persistsVerifiedMarkerBeforeSuccess() async throws {
        let fixture = try PasteboardFixture(testCase: self)
        let event = remoteClipboard(id: "remote-unicode", text: "한글\nsecond line")

        try await fixture.sync.apply(event)

        let snapshot = await fixture.store.snapshot()
        XCTAssertEqual(fixture.board.string(forType: .string), "한글\nsecond line")
        XCTAssertEqual(fixture.board.string(forType: PasteboardSynchronizer.eventIDType), event.eventId)
        XCTAssertEqual(snapshot.pasteboardCorrelation,
                       PasteboardCorrelation(eventId: event.eventId, changeCount: fixture.board.changeCount))
        XCTAssertTrue(snapshot.appliedEventIds.contains(event.eventId))
    }

    func testPasteboardMonitor_whenExactMarkerAndChangeCount_skipsOnceThenSameTextPublishesFreshID() async throws {
        let fixture = try PasteboardFixture(testCase: self)
        let event = remoteClipboard(id: "remote-echo", text: "same text")
        try await fixture.sync.apply(event)

        let skipped = try await fixture.sync.pollOnce(createdAtMs: 10)
        fixture.board.declareTypes([.string], owner: nil)
        fixture.board.setString("same text", forType: .string)
        let published = try await fixture.sync.pollOnce(createdAtMs: 11)

        XCTAssertNil(skipped)
        XCTAssertNotEqual(published?.eventId, event.eventId)
        XCTAssertEqual(published?.payload, .clipboard(text: "same text"))
    }

    func testPasteboardMonitor_whenRemoteWriteIsVisibleBeforeDurableCorrelation_doesNotPublishEcho() async throws {
        let fixture = try PasteboardFixture(testCase: self)
        let gate = WriteGate()
        let sync = PasteboardSynchronizer(surface: NSPasteboardSurface(fixture.board),
                                          state: fixture.store,
                                          afterVerifiedWrite: { await gate.suspend() })
        let event = remoteClipboard(id: "remote-in-flight", text: "race text")

        let apply = Task { try await sync.apply(event) }
        await gate.waitUntilSuspended()
        let restarted = PasteboardSynchronizer(surface: NSPasteboardSurface(fixture.board), state: fixture.store)
        let restartedPoll = try await restarted.pollOnce(createdAtMs: 11)
        let published = try await sync.pollOnce(createdAtMs: 12)
        let duplicate = try await sync.pollOnce(createdAtMs: 13)
        await gate.resume()
        try await apply.value

        XCTAssertNil(restartedPoll,
                     "a restart between write and durable completion must retain the remote event identity")
        XCTAssertNil(published,
                     "a visible remote write must not become a local event while durable correlation is pending")
        XCTAssertNil(duplicate, "duplicate polls of the in-flight remote observation must stay suppressed")
    }

    func testPasteboardMonitor_whenMarkerMissingOrMismatched_publishesFreshEvents() async throws {
        let fixture = try PasteboardFixture(testCase: self)
        try await fixture.sync.apply(remoteClipboard(id: "remote-marker", text: "opaque"))
        fixture.board.setString("different-id", forType: PasteboardSynchronizer.eventIDType)
        let mismatched = try await fixture.sync.pollOnce(createdAtMs: 20)
        fixture.board.declareTypes([.string], owner: nil)
        fixture.board.setString("opaque", forType: .string)
        let missing = try await fixture.sync.pollOnce(createdAtMs: 21)

        XCTAssertNotNil(mismatched)
        XCTAssertNotNil(missing)
        XCTAssertNotEqual(mismatched?.eventId, missing?.eventId)
    }

    func testPasteboardMonitor_whenRestartedOrMultipleChanges_correlatesPersistedApplyThenPublishesLatest() async throws {
        let fixture = try PasteboardFixture(testCase: self)
        try await fixture.sync.apply(remoteClipboard(id: "remote-restart", text: "remote"))
        let restarted = PasteboardSynchronizer(surface: NSPasteboardSurface(fixture.board), state: fixture.store)
        let skipped = try await restarted.pollOnce(createdAtMs: 30)
        XCTAssertNil(skipped)
        fixture.board.declareTypes([.string], owner: nil)
        fixture.board.setString("first", forType: .string)
        fixture.board.declareTypes([.string], owner: nil)
        fixture.board.setString("second", forType: .string)

        let event = try await restarted.pollOnce(createdAtMs: 31)

        XCTAssertEqual(event?.payload, .clipboard(text: "second"))
    }

    func testPasteboardMonitor_whenRestartStartsWithManualTextAndNoCorrelation_publishesFirstPoll() async throws {
        let fixture = try PasteboardFixture(testCase: self)
        fixture.board.declareTypes([.string], owner: nil)
        fixture.board.setString("재시작 중 수동 복사", forType: .string)
        let restarted = PasteboardSynchronizer(surface: NSPasteboardSurface(fixture.board),
                                                state: fixture.store)

        let published = try await restarted.pollOnce(createdAtMs: 40)

        XCTAssertEqual(published?.payload, .clipboard(text: "재시작 중 수동 복사"),
                       "a first snapshot without persisted correlation must not be silently baselined")
    }

    func testPasteboardMonitor_whenRestartStartsWithMismatchedMarker_publishesFirstPoll() async throws {
        let fixture = try PasteboardFixture(testCase: self)
        try await fixture.sync.apply(remoteClipboard(id: "persisted-remote", text: "remote"))
        fixture.board.declareTypes([.string, PasteboardSynchronizer.eventIDType], owner: nil)
        fixture.board.setString("재시작 전 수동 복사", forType: .string)
        fixture.board.setString("different-marker", forType: PasteboardSynchronizer.eventIDType)
        let restarted = PasteboardSynchronizer(surface: NSPasteboardSurface(fixture.board),
                                                state: fixture.store)

        let published = try await restarted.pollOnce(createdAtMs: 41)

        XCTAssertEqual(published?.payload, .clipboard(text: "재시작 전 수동 복사"),
                       "a mismatched marker must publish even on the first poll after restart")
        XCTAssertNotEqual(published?.eventId, "persisted-remote")
        let mismatchCorrelation = await fixture.store.snapshot().pasteboardCorrelation
        XCTAssertNil(mismatchCorrelation,
                     "publishing the changed snapshot must atomically clear stale remote correlation")
        let unchangedAfterMismatch = try await restarted.pollOnce(createdAtMs: 42)
        XCTAssertNil(unchangedAfterMismatch,
                     "an unchanged snapshot must publish only once")
    }

    func testPasteboardMonitor_whenMarkerChangesAfterExactPollWithoutCountChange_publishesOnce() async throws {
        let fixture = try PasteboardFixture(testCase: self)
        let applied = remoteClipboard(id: "marker-only-remote", text: "same ownership")
        try await fixture.sync.apply(applied)
        let exact = try await fixture.sync.pollOnce(createdAtMs: 43)
        let baselineCount = fixture.board.changeCount
        fixture.board.setString("marker-only-mismatch", forType: PasteboardSynchronizer.eventIDType)

        XCTAssertNil(exact)
        XCTAssertEqual(fixture.board.changeCount, baselineCount)
        let published = try await fixture.sync.pollOnce(createdAtMs: 44)
        let cleared = await fixture.store.snapshot().pasteboardCorrelation
        XCTAssertEqual(published?.payload, .clipboard(text: "same ownership"))
        XCTAssertNil(cleared)
        let repeated = try await fixture.sync.pollOnce(createdAtMs: 45)
        XCTAssertNil(repeated)
    }

    func testPasteboardMonitor_whenExactCorrelationSurvivesNthRestart_untilRealChangesPublishOnce() async throws {
        let fixture = try PasteboardFixture(testCase: self)
        let applied = remoteClipboard(id: "persisted-exact", text: "remote redelivery")
        try await fixture.sync.apply(applied)
        let exact = PasteboardCorrelation(eventId: applied.eventId, changeCount: fixture.board.changeCount)

        for restartIndex in 0..<5 {
            let restarted = PasteboardSynchronizer(surface: NSPasteboardSurface(fixture.board),
                                                    state: fixture.store)
            let firstPoll = try await restarted.pollOnce(createdAtMs: Int64(50 + restartIndex))
            let secondPoll = try await restarted.pollOnce(createdAtMs: Int64(60 + restartIndex))
            let retained = await fixture.store.snapshot().pasteboardCorrelation
            XCTAssertNil(firstPoll)
            XCTAssertNil(secondPoll)
            XCTAssertEqual(retained, exact,
                           "observing an unchanged exact correlation must never consume it")
        }

        fixture.board.declareTypes([.string, PasteboardSynchronizer.eventIDType], owner: nil)
        fixture.board.setString("manual changed count", forType: .string)
        fixture.board.setString(applied.eventId, forType: PasteboardSynchronizer.eventIDType)
        let changed = PasteboardSynchronizer(surface: NSPasteboardSurface(fixture.board),
                                             state: fixture.store)
        let changedEvent = try await changed.pollOnce(createdAtMs: 70)
        XCTAssertEqual(changedEvent?.payload, .clipboard(text: "manual changed count"))
        let unchangedAfterPublish = try await changed.pollOnce(createdAtMs: 71)
        let clearedCorrelation = await fixture.store.snapshot().pasteboardCorrelation
        XCTAssertNil(unchangedAfterPublish)
        XCTAssertNil(clearedCorrelation)
        let reopened = try DurableStateStore(url: fixture.stateURL)
        let persisted = await reopened.snapshot()
        XCTAssertEqual(persisted.outbox.first?.eventId, changedEvent?.eventId)
        XCTAssertNil(persisted.pasteboardCorrelation,
                     "local enqueue and stale-correlation clearing must persist atomically")

        fixture.board.declareTypes([.string], owner: nil)
        fixture.board.setString("manual changed count", forType: .string)
        let sameTextLater = try await changed.pollOnce(createdAtMs: 72)
        XCTAssertNotNil(sameTextLater)
        XCTAssertNotEqual(sameTextLater?.eventId, changedEvent?.eventId)
    }

    func testPasteboardPolicy_whenEmptyAndOneMiBBoundary_acceptsButOversizeAndWriteFailureReject() async throws {
        let fixture = try PasteboardFixture(testCase: self)
        try await fixture.sync.apply(remoteClipboard(id: "empty", text: ""))
        try await fixture.sync.apply(remoteClipboard(id: "limit", text: String(repeating: "a", count: 1_048_576)))
        await XCTAssertThrowsErrorAsync {
            try await fixture.sync.apply(self.remoteClipboard(id: "oversize", text: String(repeating: "a", count: 1_048_577)))
        }
        let failing = PasteboardSynchronizer(surface: FailingPasteboardSurface(), state: fixture.store)
        await XCTAssertThrowsErrorAsync { try await failing.apply(self.remoteClipboard(id: "fail", text: "x")) }
    }

    func testNotificationIdentifier_whenSameKey_replacesAndDifferentKeyDiffers() {
        let first = AndroidNotificationRenderer.requestIdentifier(deviceId: "Device-A", notificationKey: "key")
        let update = AndroidNotificationRenderer.requestIdentifier(deviceId: "Device-A", notificationKey: "key")
        let unrelated = AndroidNotificationRenderer.requestIdentifier(deviceId: "Device-A", notificationKey: "other")

        XCTAssertEqual(first, update)
        XCTAssertNotEqual(first, unrelated)
        XCTAssertEqual(first.count, 64)
        XCTAssertEqual(first, first.lowercased())
        XCTAssertEqual(first, "4bf512ba116c3f7252dce8bc2b875cf3ff453c333b9b76c38d0c3733f5cd94d0")
    }

    func testNotificationApply_whenAuthorized_addsMappedRequestBeforeSuccess() async throws {
        let center = RecordingNotificationCenter(authorization: .authorized)
        let renderer = AndroidNotificationRenderer(center: center)
        let event = remoteNotification(id: "notification", key: "stable", title: "", body: "본문")

        try await renderer.apply(event)

        let requests = await center.requests
        XCTAssertEqual(requests.count, 1)
        XCTAssertEqual(requests[0].title, "앱")
        XCTAssertEqual(requests[0].body, "본문")
    }

    func testNotificationApply_whenEmptyBodyAndMultibyteCaps_mapsEmptyAndEnforcesUTF8Bytes() async throws {
        let center = RecordingNotificationCenter(authorization: .authorized)
        let renderer = AndroidNotificationRenderer(center: center)
        let boundary = NotificationPayload(notificationKey: "utf8", packageName: "com.example",
                                           appLabel: "", title: String(repeating: "한", count: 2_730),
                                           body: String(repeating: "글", count: 21_845))
        let accepted = BridgeEvent(eventId: "utf8-ok", originDeviceId: "android", originRole: .android,
                                   originEpoch: "epoch", sequence: 1, createdAtMs: 1,
                                   payload: .notification(boundary))
        let empty = remoteNotification(id: "empty-body", key: "empty", title: "", body: "")

        try await renderer.apply(accepted)
        try await renderer.apply(empty)

        let requests = await center.requests
        XCTAssertEqual(requests.last?.body, "")
        let oversize = NotificationPayload(notificationKey: "utf8-large", packageName: "com.example",
                                           appLabel: "", title: "",
                                           body: String(repeating: "글", count: 21_846))
        let rejected = BridgeEvent(eventId: "utf8-bad", originDeviceId: "android", originRole: .android,
                                   originEpoch: "epoch", sequence: 2, createdAtMs: 1,
                                   payload: .notification(oversize))
        await XCTAssertThrowsErrorAsync { try await renderer.apply(rejected) }
    }

    func testNotificationApply_whenDeniedNotDeterminedAddFailureInvalidOrOversize_withholdsSuccess() async {
        for permission in [NotificationAuthorization.denied, .notDetermined] {
            let renderer = AndroidNotificationRenderer(center: RecordingNotificationCenter(authorization: permission))
            await XCTAssertThrowsErrorAsync { try await renderer.apply(self.remoteNotification(id: "n", key: "k")) }
        }
        let failing = AndroidNotificationRenderer(center: RecordingNotificationCenter(authorization: .authorized,
                                                                                        addFails: true))
        await XCTAssertThrowsErrorAsync { try await failing.apply(self.remoteNotification(id: "n2", key: "k")) }
        let renderer = AndroidNotificationRenderer(center: RecordingNotificationCenter(authorization: .authorized))
        await XCTAssertThrowsErrorAsync { try await renderer.apply(self.remoteClipboard(id: "wrong-kind", text: "x")) }
        await XCTAssertThrowsErrorAsync {
            try await renderer.apply(self.remoteNotification(id: "large", key: String(repeating: "k", count: 4_097)))
        }
    }

    func testRemoteEventApplier_whenClipboardAndNotification_routesBothProductionSeams() async throws {
        let fixture = try PasteboardFixture(testCase: self)
        let center = RecordingNotificationCenter(authorization: .authorized)
        let applier = RemoteEventApplier(pasteboard: fixture.sync,
                                         notifications: AndroidNotificationRenderer(center: center))

        try await applier.apply(remoteClipboard(id: "wired-clip", text: "wired"))
        try await applier.apply(remoteNotification(id: "wired-note", key: "key"))

        let count = await center.requests.count
        XCTAssertEqual(fixture.board.string(forType: .string), "wired")
        XCTAssertEqual(count, 1)
    }

    private func remoteClipboard(id: String, text: String) -> BridgeEvent {
        BridgeEvent(eventId: id, originDeviceId: "android", originRole: .android, originEpoch: "epoch",
                    sequence: Int64(abs(id.hashValue % 10_000) + 1), createdAtMs: 1,
                    payload: .clipboard(text: text))
    }

    private func remoteNotification(id: String, key: String, title: String = "제목", body: String = "내용") -> BridgeEvent {
        let payload = NotificationPayload(notificationKey: key, packageName: "com.example.app",
                                          appLabel: "앱", title: title, body: body)
        return BridgeEvent(eventId: id, originDeviceId: "android-device", originRole: .android,
                           originEpoch: "epoch", sequence: Int64(abs(id.hashValue % 10_000) + 1),
                           createdAtMs: 1, payload: .notification(payload))
    }
}

@MainActor
private final class PasteboardFixture {
    let board: NSPasteboard
    let store: DurableStateStore
    let sync: PasteboardSynchronizer
    let stateURL: URL

    init(testCase: XCTestCase) throws {
        let name = NSPasteboard.Name("com.froglike6.continuitybridge.tests.\(UUID().uuidString)")
        let pasteboard = NSPasteboard(name: name)
        pasteboard.clearContents()
        board = pasteboard
        testCase.addTeardownBlock { pasteboard.releaseGlobally() }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        testCase.addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        stateURL = directory.appendingPathComponent("state.json")
        store = try DurableStateStore(url: stateURL)
        sync = PasteboardSynchronizer(surface: NSPasteboardSurface(board), state: store)
    }
}

private struct FailingPasteboardSurface: PasteboardSurface {
    var changeCount: Int { 1 }
    func string(for type: NSPasteboard.PasteboardType) -> String? { nil }
    func replace(text: String, eventId: String) -> Int? { nil }
}

private actor WriteGate {
    private var suspended = false
    private var continuation: CheckedContinuation<Void, Never>?

    func suspend() async {
        suspended = true
        await withCheckedContinuation { continuation = $0 }
    }

    func waitUntilSuspended() async {
        while !suspended { await Task.yield() }
    }

    func resume() {
        continuation?.resume()
        continuation = nil
    }
}

private actor RecordingNotificationCenter: NotificationCenterClient {
    let authorization: NotificationAuthorization
    let addFails: Bool
    private(set) var requests: [NativeNotificationRequest] = []

    init(authorization: NotificationAuthorization, addFails: Bool = false) {
        self.authorization = authorization
        self.addFails = addFails
    }

    func authorizationStatus() async -> NotificationAuthorization { authorization }
    func add(_ request: NativeNotificationRequest) async throws {
        if addFails { throw CocoaError(.fileWriteUnknown) }
        requests.append(request)
    }
}

@MainActor
private func XCTAssertThrowsErrorAsync(_ expression: () async throws -> Void,
                                       file: StaticString = #filePath, line: UInt = #line) async {
    do { try await expression(); XCTFail("expected error", file: file, line: line) }
    catch {}
}
