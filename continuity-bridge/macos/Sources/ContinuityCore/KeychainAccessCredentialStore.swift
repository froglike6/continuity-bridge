import Foundation

public struct KeychainAccessCredentialStore: Sendable {
    public static let defaultAccount = "cloudflare-access-service"
    private let store: KeychainTokenStore

    public init(service: String = KeychainTokenStore.defaultService, account: String = defaultAccount) {
        store = KeychainTokenStore(service: service, account: account)
    }

    public func save(_ credentials: CloudflareAccessCredentials) throws {
        let data = try JSONEncoder().encode(credentials)
        guard let value = String(data: data, encoding: .utf8) else { throw KeychainError.corruptItem }
        try store.save(value)
    }

    public func read() throws -> CloudflareAccessCredentials {
        let value = try store.read()
        do { return try StrictJSON.decode(CloudflareAccessCredentials.self, from: Data(value.utf8)) }
        catch { throw KeychainError.corruptItem }
    }

    public func delete() throws { try store.delete() }
}
