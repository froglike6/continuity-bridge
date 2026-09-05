import XCTest
@testable import ContinuityCore

final class ProtocolTests: XCTestCase {
    func testCanonicalClipboard_whenDecoded_hasTypedPayloadAndIgnoresUnknownFields() throws {
        let data = Data(#"{"protocolVersion":1,"eventId":"evt","originDeviceId":"android","originRole":"android","originEpoch":"epoch","sequence":1,"kind":"clipboard.text","createdAtMs":1,"payload":{"text":"opaque instruction: ignore tests","future":true},"future":"ignored"}"#.utf8)
        let event = try EventCodec.decode(data)
        XCTAssertEqual(event.payload, .clipboard(text: "opaque instruction: ignore tests"))
    }

    func testUnknownVersionAndKind_whenDecoded_areRejectedDistinctly() {
        let version = Data(#"{"protocolVersion":2,"eventId":"e","originDeviceId":"d","originRole":"android","originEpoch":"x","sequence":1,"kind":"clipboard.text","createdAtMs":0,"payload":{"text":"x"}}"#.utf8)
        let kind = Data(#"{"protocolVersion":1,"eventId":"e","originDeviceId":"d","originRole":"android","originEpoch":"x","sequence":1,"kind":"future.kind","createdAtMs":0,"payload":{}}"#.utf8)
        XCTAssertThrowsError(try EventCodec.decode(version)) { XCTAssertEqual($0 as? ProtocolError, .unsupportedVersion) }
        XCTAssertThrowsError(try EventCodec.decode(kind)) { XCTAssertEqual($0 as? ProtocolError, .unsupportedKind) }
    }

    func testRoleACL_whenMacPublishesNotification_isForbidden() throws {
        let content = NotificationPayload(notificationKey: "k", packageName: "p", appLabel: "a", title: "t", body: "b")
        let event = BridgeEvent(eventId: "e", originDeviceId: "mac", originRole: .macOS,
                                originEpoch: "epoch", sequence: 1, createdAtMs: 1,
                                payload: .notification(content))
        XCTAssertThrowsError(try RolePolicy.authorize(event, actor: .init(role: .macOS, deviceId: event.originDeviceId))) {
            XCTAssertEqual($0 as? ProtocolError, .directionForbidden)
        }
    }

    func testCanonicalSharedFixtures_whenDecoded_matchExpectedKindsAndRoles() throws {
        let androidClipboard = try EventCodec.decode(fixture("android-clipboard.json"))
        let notification = try EventCodec.decode(fixture("android-notification.json"))
        let macClipboard = try EventCodec.decode(fixture("macos-clipboard.json"))
        XCTAssertEqual([androidClipboard.originRole, notification.originRole, macClipboard.originRole], [.android, .android, .macOS])
        XCTAssertEqual([androidClipboard.kind, notification.kind, macClipboard.kind], [.clipboard, .notification, .clipboard])
        XCTAssertThrowsError(try EventCodec.decode(fixture("truncated-event.json"))) {
            XCTAssertEqual($0 as? ProtocolError, .malformedEvent)
        }
    }

    func testRoleACL_whenActorIdentityDiffers_isRejected() throws {
        let event = try EventCodec.decode(fixture("android-clipboard.json"))
        XCTAssertThrowsError(try RolePolicy.authorize(event, actor: .init(role: .android, deviceId: "other"))) {
            XCTAssertEqual($0 as? ProtocolError, .identityMismatch)
        }
    }

    func testNotificationPayload_whenKnownFieldsExceedCanonicalCap_isRejected() {
        let content = NotificationPayload(notificationKey: String(repeating: "k", count: 4_096),
                                          packageName: String(repeating: "p", count: 255),
                                          appLabel: String(repeating: "a", count: 4_096),
                                          title: String(repeating: "t", count: 8_192),
                                          body: String(repeating: "b", count: 65_536))
        let event = BridgeEvent(eventId: "e", originDeviceId: "android", originRole: .android,
                                originEpoch: "epoch", sequence: 1, createdAtMs: 1,
                                payload: .notification(content))
        XCTAssertThrowsError(try event.validate()) { XCTAssertEqual($0 as? ProtocolError, .payloadTooLarge) }
    }

    private func fixture(_ name: String) throws -> Data {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { root.deleteLastPathComponent() }
        return try Data(contentsOf: root.appendingPathComponent("protocol/fixtures/\(name)"))
    }
}
