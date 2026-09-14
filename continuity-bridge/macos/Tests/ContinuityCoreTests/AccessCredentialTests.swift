import Foundation
import XCTest
@testable import ContinuityCore

final class AccessCredentialTests: XCTestCase {
    func testCredentials_acceptLegacyAndCurrentSecretFormatsWithoutFixedLength() throws {
        for secret in [String(repeating: "a", count: 64), "cfast_example-TEST_123.+=/"] {
            let credentials = try CloudflareAccessCredentials(clientID: "device.access", clientSecret: secret)
            let restored = try JSONDecoder().decode(CloudflareAccessCredentials.self,
                                                    from: JSONEncoder().encode(credentials))
            XCTAssertEqual(restored, credentials)
        }
    }

    func testCredentials_rejectEmptyValuesWhitespaceControlsAndNonASCII() {
        for invalid in ["", " ", "bad value", "bad\rvalue", "bad\nvalue", "bad\tvalue", "bad\0value", "bad\u{7f}", "한글"] {
            XCTAssertThrowsError(try CloudflareAccessCredentials(clientID: invalid, clientSecret: "cfast_test")) {
                XCTAssertEqual($0 as? CloudflareAccessCredentialError, .invalidClientID)
            }
            XCTAssertThrowsError(try CloudflareAccessCredentials(clientID: "device.access", clientSecret: invalid)) {
                XCTAssertEqual($0 as? CloudflareAccessCredentialError, .invalidClientSecret)
            }
        }
    }

    func testBlankSecret_reusesOnlyTheSameClientID() throws {
        let saved = try CloudflareAccessCredentials(clientID: "first.access", clientSecret: "cfast_saved")
        XCTAssertEqual(try CloudflareAccessCredentials.resolve(clientID: saved.clientID, clientSecret: "", stored: { saved }), saved)
        XCTAssertThrowsError(try CloudflareAccessCredentials.resolve(clientID: "second.access", clientSecret: "", stored: { saved })) {
            XCTAssertEqual($0 as? CloudflareAccessCredentialError, .clientSecretRequired)
        }
        let changed = try CloudflareAccessCredentials.resolve(clientID: "second.access", clientSecret: "cfast_new") {
            XCTFail("A new secret must not reuse the stored pair")
            return saved
        }
        XCTAssertEqual(changed.clientID, "second.access")
        XCTAssertEqual(changed.clientSecret, "cfast_new")
    }

    func testBlankSecret_missingOrCorruptStorageFailsClosed() {
        for failure in [KeychainError.missingItem, .corruptItem] {
            XCTAssertThrowsError(try CloudflareAccessCredentials.resolve(clientID: "device.access", clientSecret: "") {
                throw failure
            }) { XCTAssertEqual($0 as? KeychainError, failure) }
        }
    }

    func testKeychainPair_roundTripsSeparatelyFromRelayToken() throws {
        let service = "com.froglike6.continuitybridge.tests.\(UUID().uuidString)"
        let relay = KeychainTokenStore(service: service)
        let access = KeychainAccessCredentialStore(service: service)
        defer { try? relay.delete(); try? access.delete() }
        try relay.save("test-relay-token")
        let first = try CloudflareAccessCredentials(clientID: "first.access", clientSecret: "cfast_first")
        let second = try CloudflareAccessCredentials(clientID: "second.access", clientSecret: "cfast_second")
        try access.save(first)
        XCTAssertEqual(try access.read(), first)
        try access.save(second)
        XCTAssertEqual(try access.read(), second)
        XCTAssertEqual(try relay.read(), "test-relay-token")
        XCTAssertNotEqual(KeychainAccessCredentialStore.defaultAccount, KeychainTokenStore.defaultAccount)
    }

    func testKeychainPair_missingMalformedOrInvalidPairsFailClosed() throws {
        let service = "com.froglike6.continuitybridge.tests.\(UUID().uuidString)"
        let access = KeychainAccessCredentialStore(service: service)
        let raw = KeychainTokenStore(service: service, account: KeychainAccessCredentialStore.defaultAccount)
        defer { try? access.delete() }
        XCTAssertThrowsError(try access.read()) { XCTAssertEqual($0 as? KeychainError, .missingItem) }
        for invalid in ["not-json", #"{"clientID":"device.access"}"#,
                        #"{"clientID":"device.access","clientSecret":""}"#,
                        #"{"clientID":"device.access","clientSecret":"bad\r\ninjected"}"#,
                        #"{"clientID":"first.access","clientID":"second.access","clientSecret":"cfast_test"}"#] {
            try raw.save(invalid)
            XCTAssertThrowsError(try access.read()) { XCTAssertEqual($0 as? KeychainError, .corruptItem) }
        }
    }
}
