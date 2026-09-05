import CryptoKit
import Security
import XCTest
@testable import ContinuityCore

final class SecurityTests: XCTestCase {
    func testKeychain_whenRoundTripAndDelete_succeedsAndMissingFailsClosed() throws {
        let store = KeychainTokenStore(service: "com.froglike6.continuitybridge.tests", account: UUID().uuidString)
        defer { try? store.delete() }
        let firstToken = "task5-\(UUID().uuidString)"
        let secondToken = "task5-\(UUID().uuidString)"
        try store.save(firstToken)
        XCTAssertEqual(try store.read(), firstToken)
        try store.save(secondToken)
        XCTAssertEqual(try store.read(), secondToken)
        try store.delete()
        XCTAssertThrowsError(try store.read()) { XCTAssertEqual($0 as? KeychainError, .missingItem) }
    }

    func testTLS_whenExactCAHostPin_acceptsAndWrongInputsReject() throws {
        let fixtures = try TLSFixtures.load()
        let pin = SHA256.hash(data: fixtures.leafDER).map { String(format: "%02x", $0) }.joined()
        let wrongCA = try makeWrongCA()
        let exact = TLSPolicy.local(caDER: fixtures.caDER, leafPinHex: pin, expectedHost: "localhost")
        XCTAssertNoThrow(try exact.evaluate(leafDER: fixtures.leafDER, host: "localhost"))
        XCTAssertThrowsError(try TLSPolicy.local(caDER: fixtures.caDER, leafPinHex: String(repeating: "0", count: 64), expectedHost: "localhost").evaluate(leafDER: fixtures.leafDER, host: "localhost"))
        XCTAssertThrowsError(try exact.evaluate(leafDER: fixtures.leafDER, host: "wrong.local"))
        XCTAssertThrowsError(try exact.evaluate(leafDER: fixtures.leafDER, host: "10.0.2.2"))
        XCTAssertThrowsError(try TLSPolicy.local(caDER: wrongCA, leafPinHex: pin, expectedHost: "localhost").evaluate(leafDER: fixtures.leafDER, host: "localhost"))
        XCTAssertThrowsError(try exact.evaluate(leafDER: fixtures.leafDER, host: "localhost", verifyDate: Date(timeIntervalSince1970: 2_208_988_800)))
    }


    func testKeychain_whenStoredBytesAreCorrupt_failsClosed() throws {
        let service = "com.froglike6.continuitybridge.tests"
        let account = UUID().uuidString
        let store = KeychainTokenStore(service: service, account: account)
        defer { try? store.delete() }
        let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service,
                                      kSecAttrAccount: account, kSecValueData: Data([0xff, 0xfe])]
        XCTAssertEqual(SecItemAdd(query as CFDictionary, nil), errSecSuccess)
        XCTAssertThrowsError(try store.read()) { XCTAssertEqual($0 as? KeychainError, .corruptItem) }
    }

    private func makeWrongCA() throws -> Data {
        guard let url = Bundle.module.url(forResource: "wrong-ca", withExtension: "pem",
                                          subdirectory: "Fixtures") else {
            throw TLSValidationError.invalidCertificate
        }
        let pem = try String(contentsOf: url, encoding: .utf8)
        let body = pem.components(separatedBy: .newlines).filter { !$0.hasPrefix("---") }.joined()
        guard let data = Data(base64Encoded: body) else { throw TLSValidationError.invalidCertificate }
        return data
    }
}

private struct TLSFixtures {
    let caDER: Data
    let leafDER: Data

    static func load() throws -> TLSFixtures {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { root.deleteLastPathComponent() }
        let caPEM = try String(contentsOf: root.appendingPathComponent("runtime/tls/ca.pem"), encoding: .utf8)
        let body = caPEM.components(separatedBy: .newlines).filter { !$0.hasPrefix("---") }.joined()
        guard let ca = Data(base64Encoded: body) else { throw TLSValidationError.invalidCertificate }
        let leaf = try Data(contentsOf: root.appendingPathComponent("runtime/tls/server.der"))
        return TLSFixtures(caDER: ca, leafDER: leaf)
    }
}
