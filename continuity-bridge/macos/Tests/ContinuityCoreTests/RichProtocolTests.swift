import Foundation
import XCTest
@testable import ContinuityCore

final class RichProtocolTests: XCTestCase {
    private let png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jkWQAAAAASUVORK5CYII="

    func testImage_whenRoundTripped_preservesBytesAndAuthorizesBothRoles() throws {
        // Given
        let image = ImagePayload(mimeType: "image/png", dataBase64: png)
        for role in [DeviceRole.android, .macOS] {
            let event = makeEvent(.image(image), role: role)
            // When
            let decoded = try EventCodec.decode(EventCodec.encode(event))
            // Then
            XCTAssertEqual(decoded, event)
            XCTAssertEqual(decoded.kind, .image)
            XCTAssertNoThrow(try RolePolicy.authorize(decoded, actor: .init(role: role, deviceId: "sender")))
        }
    }

    func testNotificationMetadata_whenRoundTripped_preservesOptionalTypes() throws {
        // Given
        let item = NotificationPayload(notificationKey: "k", packageName: "p", appLabel: "a", title: "t", body: "b",
            iconPngBase64: png, progress: .init(value: 3, max: 10, indeterminate: false),
            isOngoing: true, isRedacted: false, category: "")
        let event = makeEvent(.notification(item))
        // When
        let decoded = try EventCodec.decode(EventCodec.encode(event))
        // Then
        XCTAssertEqual(decoded, event)
    }

    func testNotificationMetadata_whenNullOrMistyped_isRejected() throws {
        // Given
        for fragment in [#""isOngoing":null"#, #""isRedacted":"false""#, #""category":null"#,
            #""progress":{"value":0,"max":0,"indeterminate":false}"#,
            #""progress":{"value":2,"max":1,"indeterminate":true}"#,
            #""progress":{"value":0,"max":2147483648,"indeterminate":true}"#] {
            let data = notificationJson(fragment)
            // When / Then
            XCTAssertThrowsError(try EventCodec.decode(data)) { XCTAssertEqual($0 as? ProtocolError, .malformedEvent) }
        }
    }

    func testNotificationMetadata_whenUnknownKeysAdded_isNormalized() throws {
        // Given
        let first = notificationJson(#""progress":{"value":0,"max":0,"indeterminate":true}"#)
        let second = notificationJson(#""future":true,"progress":{"value":0,"max":0,"indeterminate":true,"future":42}"#)
        // When
        let left = try EventCodec.decode(first); let right = try EventCodec.decode(second)
        // Then
        XCTAssertEqual(left, right)
        XCTAssertFalse(String(decoding: try EventCodec.encode(right), as: UTF8.self).contains("future"))
    }

    func testImage_whenBase64OrSignatureInvalid_isRejected() {
        // Given
        for image in [ImagePayload(mimeType: "image/png", dataBase64: png + "\n"),
            ImagePayload(mimeType: "image/jpeg", dataBase64: png),
            ImagePayload(mimeType: "image/gif", dataBase64: png),
            ImagePayload(mimeType: "image/png", dataBase64: "AB==")] {
            // When / Then
            XCTAssertThrowsError(try EventCodec.encode(makeEvent(.image(image)))) {
                XCTAssertEqual($0 as? ProtocolError, .malformedEvent)
            }
        }
    }

    func testImage_whenAtAndAboveDecodedLimit_obeysEightMiBBound() throws {
        // Given
        var bytes = Data(repeating: 0, count: 8_388_608)
        bytes.replaceSubrange(0..<8, with: [137, 80, 78, 71, 13, 10, 26, 10])
        let allowed = makeEvent(.image(.init(mimeType: "image/png", dataBase64: bytes.base64EncodedString())))
        bytes.append(0)
        let oversized = makeEvent(.image(.init(mimeType: "image/png", dataBase64: bytes.base64EncodedString())))
        // When / Then
        XCTAssertEqual(try EventCodec.decode(EventCodec.encode(allowed)), allowed)
        XCTAssertThrowsError(try EventCodec.encode(oversized)) { XCTAssertEqual($0 as? ProtocolError, .payloadTooLarge) }
    }

    private func makeEvent(_ payload: EventPayload, role: DeviceRole = .android) -> BridgeEvent {
        BridgeEvent(eventId: "event", originDeviceId: "sender", originRole: role, originEpoch: "epoch",
                    sequence: 1, createdAtMs: 0, payload: payload)
    }

    func testJPEG_whenBase64ContainsManySlashes_fitsTheWireLimitWithoutChangingBytes() throws {
        // Given
        var data = Data(repeating: 255, count: 8_388_608)
        data[1] = 216
        let image = ImagePayload(mimeType: "image/jpeg", dataBase64: data.base64EncodedString())
        // When
        let encoded = try EventCodec.encode(makeEvent(.image(image)))
        // Then
        XCTAssertLessThanOrEqual(encoded.count, WireLimits.eventBodyBytes)
        XCTAssertEqual(try EventCodec.decode(encoded).payload, .image(image))
    }

    func testNotificationIcon_whenGeometryOrDecodedSizeExceedsBound_isRejected() throws {
        // Given
        let original = try XCTUnwrap(Data(base64Encoded: png))
        var wide = original; wide[19] = 129
        var large = original; large.append(Data(repeating: 0, count: 12_289 - original.count))
        for (data, error) in [(wide, ProtocolError.malformedEvent), (large, .payloadTooLarge)] {
            let payload = NotificationPayload(notificationKey: "k", packageName: "p", appLabel: "a", title: "t", body: "b",
                                               iconPngBase64: data.base64EncodedString())
            // When / Then
            XCTAssertThrowsError(try EventCodec.encode(makeEvent(.notification(payload)))) { XCTAssertEqual($0 as? ProtocolError, error) }
        }
    }

    private func notificationJson(_ fragment: String) -> Data {
        Data((#"{"protocolVersion":1,"eventId":"event","originDeviceId":"sender","originRole":"android","originEpoch":"epoch","sequence":1,"kind":"android.notification","createdAtMs":0,"payload":{"notificationKey":"k","packageName":"p","appLabel":"a","title":"t","body":"b","# + fragment + "}}").utf8)
    }
}
