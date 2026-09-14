import Foundation
import XCTest
@testable import ContinuityCore

@MainActor
final class NotificationInboxIdentityTests: XCTestCase {
    func testRedaction_whenContentUnavailable_preservesAppIdentityButReplacesPrivateText() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        let icon = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lZkAAAAASUVORK5CYII="
        let full = NotificationPayload(notificationKey: "notice", packageName: "com.example.messages",
                                       appLabel: "Messages", title: "Private sender", body: "Private message text",
                                       iconPngBase64: icon)
        try fixture.store.receive(inboxEvent(1, payload: full))
        let unavailable = NotificationPayload(notificationKey: "notice", packageName: "com.example.messages",
                                               appLabel: "", title: "System notification",
                                               body: "Notification content unavailable", isRedacted: true)
        // When
        let receipt = try fixture.store.receive(inboxEvent(2, payload: unavailable))
        let restored = try NotificationInboxStore(url: fixture.url)
        // Then
        XCTAssertEqual(receipt.item?.appLabel, "Messages")
        XCTAssertEqual(receipt.item?.iconPngBase64, icon)
        XCTAssertEqual(receipt.item?.title, "System notification")
        XCTAssertEqual(receipt.item?.body, "Notification content unavailable")
        XCTAssertEqual(restored.entries.first?.iconPngBase64, icon)
        XCTAssertFalse(String(decoding: try Data(contentsOf: fixture.url), as: UTF8.self).contains("Private message text"))
    }

    func testRedaction_whenNoSourceMarker_keepsDeliveredTextAndDoesNotInventRedaction() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        // When
        let receipt = try fixture.store.receive(inboxEvent(1))
        // Then
        XCTAssertEqual(receipt.item?.isRedacted, false)
        XCTAssertEqual(receipt.item?.body, "Message body")
    }

    func testCatalog_whenOverLimit_keepsBlockedPreferencesAndNewestIdentities() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.setBlocked(packageName: "com.example.blocked", blocked: true)
        for index in 0...NotificationInboxStore.maximumApps {
            let payload = NotificationPayload(notificationKey: "key-\(index)", packageName: "com.example.app\(index)",
                                              appLabel: "App \(index)", title: "Notice", body: "body")
            try fixture.store.receive(inboxEvent(Int64(index + 1), payload: payload))
        }
        // When
        let restored = try NotificationInboxStore(url: fixture.url)
        // Then
        XCTAssertEqual(restored.apps.count, NotificationInboxStore.maximumApps)
        XCTAssertEqual(restored.apps.first { $0.packageName == "com.example.blocked" }?.isBlocked, true)
        XCTAssertTrue(restored.apps.contains { $0.packageName == "com.example.app256" })
        XCTAssertFalse(restored.apps.contains { $0.packageName == "com.example.app0" })
    }

    func testReceive_whenOriginIsMac_rejectsWithoutMutation() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        let event = BridgeEvent(eventId: "wrong-role", originDeviceId: "mac", originRole: .macOS,
                                originEpoch: "epoch", sequence: 1, createdAtMs: 0, payload: inboxEvent(1).payload)
        // When
        XCTAssertThrowsError(try fixture.store.receive(event)) { error in
            XCTAssertEqual(error as? NotificationInboxError, .invalidEvent)
        }
        // Then
        XCTAssertTrue(fixture.store.entries.isEmpty)
        XCTAssertTrue(fixture.store.apps.isEmpty)
    }

    func testIdentity_whenTupleContainsSeparators_doesNotCollide() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        let first = NotificationPayload(notificationKey: "c", packageName: "p", appLabel: "P", title: "One", body: "")
        let second = NotificationPayload(notificationKey: "b\0c", packageName: "p", appLabel: "P", title: "Two", body: "")
        try fixture.store.receive(inboxEvent(1, payload: first, device: "a\0b"))
        // When
        try fixture.store.receive(inboxEvent(1, payload: second, device: "a"))
        // Then
        XCTAssertEqual(Set(fixture.store.entries.map(\.id)).count, 2)
    }
}
