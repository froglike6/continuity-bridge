import XCTest
@testable import ContinuityCore

@MainActor
final class NotificationInboxDismissalTests: XCTestCase {
    func testProgress_whenDismissedAndUpdatedAfterRestart_doesNotRepeatInitialBanner() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        let first = try fixture.store.receive(inboxEvent(1, payload: progressNotice(10)))
        try fixture.store.dismiss(id: XCTUnwrap(first.item).id)
        let restored = try NotificationInboxStore(url: fixture.url)
        // When
        let updated = try restored.receive(inboxEvent(2, payload: progressNotice(70)))
        // Then
        XCTAssertFalse(updated.shouldPresent)
        XCTAssertEqual(updated.item?.progressState, .active)
    }

    func testOrdinaryNotice_whenClearedAndRepeatedWithFreshEventId_staysSilent() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.receive(inboxEvent(1))
        try fixture.store.clear()
        let restored = try NotificationInboxStore(url: fixture.url)
        // When
        let repeated = try restored.receive(inboxEvent(2))
        // Then
        XCTAssertFalse(repeated.shouldPresent)
    }

    func testProgress_whenDismissedBeforeCompletion_stillPresentsTerminalTransition() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        let first = try fixture.store.receive(inboxEvent(1, payload: progressNotice(10)))
        try fixture.store.dismiss(id: XCTUnwrap(first.item).id)
        // When
        let completed = try fixture.store.receive(inboxEvent(2, payload: progressNotice(100)))
        // Then
        XCTAssertTrue(completed.shouldPresent)
        XCTAssertEqual(completed.item?.progressState, .completed)
    }
}
