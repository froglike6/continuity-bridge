import Foundation
import Security

public enum KeychainError: Error, Equatable { case missingItem, corruptItem, operationFailed(OSStatus) }

public struct KeychainTokenStore: Sendable {
    public static let defaultService = "com.froglike6.continuitybridge.token"
    public static let defaultAccount = "relay-bearer"
    public let service: String
    public let account: String

    public init(service: String = defaultService, account: String = defaultAccount) {
        self.service = service
        self.account = account
    }

    public func save(_ token: String) throws {
        guard !token.isEmpty, let data = token.data(using: .utf8) else { throw KeychainError.corruptItem }
        let query = baseQuery
        let updateStatus = SecItemUpdate(query as CFDictionary, [kSecValueData: data] as CFDictionary)
        switch updateStatus {
        case errSecSuccess: return
        case errSecItemNotFound:
            var addition = query
            addition[kSecValueData] = data
            addition[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let addStatus = SecItemAdd(addition as CFDictionary, nil)
            guard addStatus == errSecSuccess else { throw KeychainError.operationFailed(addStatus) }
        default: throw KeychainError.operationFailed(updateStatus)
        }
    }

    public func read() throws -> String {
        var query = baseQuery
        query[kSecReturnData] = true
        query[kSecMatchLimit] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status != errSecItemNotFound else { throw KeychainError.missingItem }
        guard status == errSecSuccess else { throw KeychainError.operationFailed(status) }
        guard let data = result as? Data, let token = String(data: data, encoding: .utf8), !token.isEmpty else {
            throw KeychainError.corruptItem
        }
        return token
    }

    public func delete() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.operationFailed(status)
        }
    }

    private var baseQuery: [CFString: Any] {
        [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account]
    }
}
