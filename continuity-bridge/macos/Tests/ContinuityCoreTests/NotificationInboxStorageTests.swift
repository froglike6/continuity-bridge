import Foundation
import XCTest
@testable import ContinuityCore

@MainActor
final class NotificationInboxStorageTests: XCTestCase {
    func testReceive_whenPersistenceFails_keepsMemoryAndEventEligibleForRetry() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        let backup = fixture.directory.appendingPathComponent("saved.json")
        try FileManager.default.moveItem(at: fixture.url, to: backup)
        try FileManager.default.createDirectory(at: fixture.url, withIntermediateDirectories: false)
        // When
        XCTAssertThrowsError(try fixture.store.receive(inboxEvent(1))) { error in
            XCTAssertEqual(error as? NotificationInboxError, .storageUnavailable)
        }
        // Then
        XCTAssertTrue(fixture.store.entries.isEmpty)
        XCTAssertTrue(fixture.store.apps.isEmpty)
        try FileManager.default.removeItem(at: fixture.url)
        try FileManager.default.moveItem(at: backup, to: fixture.url)
        XCTAssertTrue(try fixture.store.receive(inboxEvent(1)).shouldPresent)
    }

    func testFilter_whenPersistenceFails_doesNotPublishUnsavedFilter() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.receive(inboxEvent(1))
        let before = fixture.store.entries
        let backup = fixture.directory.appendingPathComponent("saved.json")
        try FileManager.default.moveItem(at: fixture.url, to: backup)
        try FileManager.default.createDirectory(at: fixture.url, withIntermediateDirectories: false)
        // When
        XCTAssertThrowsError(try fixture.store.setBlocked(packageName: "com.example.messages", blocked: true))
        // Then
        XCTAssertEqual(fixture.store.entries, before)
        XCTAssertEqual(fixture.store.apps.first?.isBlocked, false)
    }

    func testPersistence_whenCreated_limitsFileAccessToOwner() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.receive(inboxEvent(1))
        // When
        let attributes = try FileManager.default.attributesOfItem(atPath: fixture.url.path)
        // Then
        XCTAssertEqual((attributes[.posixPermissions] as? NSNumber)?.intValue, 0o600)
    }

    func testRestore_whenJSONIsCorrupt_failsClosed() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try Data("{".utf8).write(to: fixture.url)
        // When
        XCTAssertThrowsError(try NotificationInboxStore(url: fixture.url)) { error in
            XCTAssertEqual(error as? NotificationInboxError, .corruptState)
        }
    }

    func testClear_whenStoreRestarts_preservesFilterAndReplayProtection() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        try fixture.store.receive(inboxEvent(1))
        try fixture.store.setBlocked(packageName: "com.example.blocked", blocked: true)
        // When
        try fixture.store.clear()
        let restored = try NotificationInboxStore(url: fixture.url)
        let replay = try restored.receive(inboxEvent(1))
        // Then
        XCTAssertTrue(restored.entries.isEmpty)
        XCTAssertFalse(replay.shouldPresent)
        XCTAssertEqual(restored.apps.first { $0.packageName == "com.example.blocked" }?.isBlocked, true)
    }

    func testRestore_whenFileExceedsBound_rejectsBeforeLoadingContents() throws {
        // Given
        let fixture = try NotificationInboxFixture(testCase: self)
        let file = try FileHandle(forWritingTo: fixture.url)
        try file.truncate(atOffset: UInt64(NotificationInboxPersistence.maximumBytes + 1))
        try file.close()
        // When
        XCTAssertThrowsError(try NotificationInboxStore(url: fixture.url)) { error in
            XCTAssertEqual(error as? NotificationInboxError, .corruptState)
        }
    }
}
