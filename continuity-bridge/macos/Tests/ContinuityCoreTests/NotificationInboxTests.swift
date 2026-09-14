import Foundation
import XCTest
@testable import ContinuityCore

@MainActor
final class NotificationInboxTests: XCTestCase {
    func testReceive_whenFirstNotice_arrivesInHistoryAndPresents() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        let event = inboxEvent(1)
        // When
        let receipt = try fixture.store.receive(event)
        // Then
        XCTAssertTrue(receipt.shouldPresent)
        XCTAssertEqual(receipt.item, fixture.store.entries.first)
        XCTAssertEqual(receipt.item?.body, "Message body")
        XCTAssertEqual(fixture.store.apps.first?.appLabel, "Messages")
    }

    func testFilter_whenBlocked_removesRetainedBodiesAndSurvivesRestart() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.receive(inboxEvent(1))
        // When
        try fixture.store.setBlocked(packageName: "com.example.messages", blocked: true)
        let restored = try NotificationInboxStore(url: fixture.url)
        // Then
        XCTAssertTrue(restored.entries.isEmpty)
        XCTAssertEqual(restored.apps.first?.isBlocked, true)
        XCTAssertFalse(String(decoding: try Data(contentsOf: fixture.url), as: UTF8.self)
            .contains("Message body"))
    }

    func testReceive_whenBlocked_retainsAppIdentityWithoutBodyOrBanner() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.setBlocked(packageName: "com.example.messages", blocked: true)
        // When
        let receipt = try fixture.store.receive(inboxEvent(1))
        // Then
        XCTAssertNil(receipt.item)
        XCTAssertFalse(receipt.shouldPresent)
        XCTAssertTrue(fixture.store.entries.isEmpty)
        XCTAssertEqual(fixture.store.apps.first?.appLabel, "Messages")
        XCTAssertFalse(String(decoding: try Data(contentsOf: fixture.url), as: UTF8.self)
            .contains("Message body"))
    }

    func testReceive_whenUnblocked_appliesOnlyFreshEvents() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.setBlocked(packageName: "com.example.messages", blocked: true)
        try fixture.store.receive(inboxEvent(1))
        try fixture.store.setBlocked(packageName: "com.example.messages", blocked: false)
        // When
        let old = try fixture.store.receive(inboxEvent(1))
        let fresh = try fixture.store.receive(inboxEvent(2))
        // Then
        XCTAssertFalse(old.shouldPresent)
        XCTAssertNil(old.item)
        XCTAssertTrue(fresh.shouldPresent)
        XCTAssertEqual(fixture.store.entries.count, 1)
    }

    func testReplay_whenStoreRestarts_doesNotPresentOrReplaceCurrentContent() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.receive(inboxEvent(2))
        let restored = try NotificationInboxStore(url: fixture.url)
        // When
        let receipt = try restored.receive(inboxEvent(1))
        // Then
        XCTAssertFalse(receipt.shouldPresent)
        XCTAssertEqual(restored.entries.first?.eventId, inboxEvent(2).eventId)
    }

    func testDismiss_whenEventReplaysAfterRestart_doesNotRestoreDismissedItem() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        let receipt = try fixture.store.receive(inboxEvent(1))
        try fixture.store.dismiss(id: XCTUnwrap(receipt.item).id)
        let restored = try NotificationInboxStore(url: fixture.url)
        // When
        let replay = try restored.receive(inboxEvent(1))
        // Then
        XCTAssertFalse(replay.shouldPresent)
        XCTAssertNil(replay.item)
        XCTAssertTrue(restored.entries.isEmpty)
    }

    func testIdentity_whenSameKeyComesFromDifferentDevices_retainsSeparateItems() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.receive(inboxEvent(1, device: "first"))
        // When
        try fixture.store.receive(inboxEvent(1, device: "second"))
        // Then
        XCTAssertEqual(Set(fixture.store.entries.map(\.id)).count, 2)
    }

    func testHistory_whenOverLimit_keepsLatestHundredAcrossRestart() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        for index in 1...101 {
            let payload = NotificationPayload(notificationKey: "key-\(index)", packageName: "com.example.messages",
                                              appLabel: "Messages", title: "Item \(index)", body: "body")
            try fixture.store.receive(inboxEvent(Int64(index), payload: payload))
        }
        // When
        let restored = try NotificationInboxStore(url: fixture.url)
        // Then
        XCTAssertEqual(restored.entries.count, 100)
        XCTAssertEqual(restored.entries.first?.notificationKey, "key-101")
        XCTAssertEqual(restored.entries.last?.notificationKey, "key-2")
    }

    func testPause_whenPersisted_retainsIncomingItemsWithoutBanner() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.setPauseBanners(true)
        let restored = try NotificationInboxStore(url: fixture.url)
        // When
        let receipt = try restored.receive(inboxEvent(1))
        // Then
        XCTAssertTrue(restored.pauseBanners)
        XCTAssertFalse(receipt.shouldPresent)
        XCTAssertEqual(restored.entries.count, 1)
    }
}
