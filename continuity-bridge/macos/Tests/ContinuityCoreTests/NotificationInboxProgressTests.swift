import XCTest
@testable import ContinuityCore

@MainActor
final class NotificationInboxProgressTests: XCTestCase {
    func testProgress_whenFirstObserved_presentsInitialState() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        // When
        let receipt = try fixture.store.receive(inboxEvent(1, payload: progressNotice(10)))
        // Then
        XCTAssertTrue(receipt.shouldPresent)
        XCTAssertEqual(receipt.item?.progressState, .active)
        XCTAssertEqual(receipt.item?.progress?.value, 10)
    }

    func testProgress_whenPercentageAndTextChange_replacesSilently() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        let first = try fixture.store.receive(inboxEvent(1, payload: progressNotice(10, body: "10%")))
        // When
        let updated = try fixture.store.receive(inboxEvent(2, payload: progressNotice(70, body: "70%")))
        // Then
        XCTAssertFalse(updated.shouldPresent)
        XCTAssertEqual(updated.item?.id, first.item?.id)
        XCTAssertEqual(fixture.store.entries.count, 1)
        XCTAssertEqual(updated.item?.progress?.value, 70)
    }

    func testProgress_whenIndeterminate_remainsActiveWithoutGuessingCompletion() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.receive(inboxEvent(1, payload: progressNotice(0, max: 0, indeterminate: true)))
        // When
        let updated = try fixture.store.receive(inboxEvent(2, payload: progressNotice(0, max: 0, indeterminate: true)))
        // Then
        XCTAssertFalse(updated.shouldPresent)
        XCTAssertEqual(updated.item?.progressState, .active)
        XCTAssertEqual(updated.item?.progress?.indeterminate, true)
    }

    func testProgress_whenComplete_presentsTerminalTransitionOnceAcrossRestart() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.receive(inboxEvent(1, payload: progressNotice(10)))
        // When
        let completed = try fixture.store.receive(inboxEvent(2, payload: progressNotice(100, body: "Done")))
        let restored = try NotificationInboxStore(url: fixture.url)
        let repeated = try restored.receive(inboxEvent(3, payload: progressNotice(100, body: "Saved")))
        // Then
        XCTAssertTrue(completed.shouldPresent)
        XCTAssertEqual(completed.item?.progressState, .completed)
        XCTAssertFalse(repeated.shouldPresent)
    }

    func testProgress_whenSourceReportsErrorWithoutProgress_presentsFailure() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.receive(inboxEvent(1, payload: progressNotice(10)))
        // When
        let failed = try fixture.store.receive(inboxEvent(2, payload: progressNotice(nil, category: "err")))
        // Then
        XCTAssertTrue(failed.shouldPresent)
        XCTAssertEqual(failed.item?.progressState, .failed)
    }

    func testProgress_whenProgressDisappears_doesNotClaimSuccessOrPresent() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.receive(inboxEvent(1, payload: progressNotice(40)))
        // When
        let missing = try fixture.store.receive(inboxEvent(2, payload: progressNotice(nil, ongoing: false)))
        // Then
        XCTAssertFalse(missing.shouldPresent)
        XCTAssertEqual(missing.item?.progressState, .unknown)
        XCTAssertNil(missing.item?.progress)
    }

    func testProgress_whenFailureFollowsUnknownState_presentsTerminalEvidence() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.receive(inboxEvent(1, payload: progressNotice(40)))
        try fixture.store.receive(inboxEvent(2, payload: progressNotice(nil)))
        // When
        let failed = try fixture.store.receive(inboxEvent(3, payload: progressNotice(nil, category: "err")))
        // Then
        XCTAssertTrue(failed.shouldPresent)
        XCTAssertEqual(failed.item?.progressState, .failed)
    }

    func testProgress_whenCompletedKeyStartsNewCycle_allowsNewInitialAndTerminalBanner() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.receive(inboxEvent(1, payload: progressNotice(100)))
        let restored = try NotificationInboxStore(url: fixture.url)
        // When
        let restarted = try restored.receive(inboxEvent(2, payload: progressNotice(10)))
        let completed = try restored.receive(inboxEvent(3, payload: progressNotice(100)))
        // Then
        XCTAssertTrue(restarted.shouldPresent)
        XCTAssertEqual(restarted.item?.progressState, .active)
        XCTAssertTrue(completed.shouldPresent)
        XCTAssertEqual(completed.item?.progressState, .completed)
    }

    func testProgress_whenActiveValueResets_staysSilentAndNeverBecomesComplete() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.receive(inboxEvent(1, payload: progressNotice(80)))
        // When
        let restarted = try fixture.store.receive(inboxEvent(2, payload: progressNotice(10)))
        // Then
        XCTAssertFalse(restarted.shouldPresent)
        XCTAssertEqual(restarted.item?.progressState, .active)
    }

    func testOrdinaryNotice_whenContentChanges_presentsReplacement() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.receive(inboxEvent(1))
        let changed = NotificationPayload(notificationKey: "notice", packageName: "com.example.messages",
                                           appLabel: "Messages", title: "Another message", body: "New content")
        // When
        let receipt = try fixture.store.receive(inboxEvent(2, payload: changed))
        // Then
        XCTAssertTrue(receipt.shouldPresent)
        XCTAssertEqual(fixture.store.entries.count, 1)
    }

    func testOrdinaryNotice_whenOnlyEventIdentityChanges_doesNotPresentAgain() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.receive(inboxEvent(1))
        // When
        let receipt = try fixture.store.receive(inboxEvent(2))
        // Then
        XCTAssertFalse(receipt.shouldPresent)
    }
}

func progressNotice(_ value: Int?, max: Int = 100, indeterminate: Bool = false,
                    body: String = "Download", ongoing: Bool = true,
                    category: String? = nil) -> NotificationPayload {
    NotificationPayload(notificationKey: "download", packageName: "com.example.download",
                        appLabel: "Downloads", title: "File download", body: body,
                        progress: value.map { NotificationProgress(value: $0, max: max, indeterminate: indeterminate) },
                        isOngoing: ongoing, category: category)
}
