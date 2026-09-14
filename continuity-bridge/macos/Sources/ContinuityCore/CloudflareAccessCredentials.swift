import Foundation

public enum CloudflareAccessCredentialError: Error, Equatable, Sendable {
    case invalidClientID, invalidClientSecret, clientSecretRequired
}

public struct CloudflareAccessCredentials: Codable, Equatable, Sendable {
    public let clientID: String
    public let clientSecret: String

    public init(clientID: String, clientSecret: String) throws {
        guard Self.isHeaderValue(clientID) else { throw CloudflareAccessCredentialError.invalidClientID }
        guard Self.isHeaderValue(clientSecret) else { throw CloudflareAccessCredentialError.invalidClientSecret }
        self.clientID = clientID
        self.clientSecret = clientSecret
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(clientID: values.decode(String.self, forKey: .clientID),
                      clientSecret: values.decode(String.self, forKey: .clientSecret))
    }

    public static func resolve(clientID: String, clientSecret: String,
                               stored: () throws -> Self) throws -> Self {
        guard isHeaderValue(clientID) else { throw CloudflareAccessCredentialError.invalidClientID }
        if !clientSecret.isEmpty { return try Self(clientID: clientID, clientSecret: clientSecret) }
        let saved = try stored()
        guard saved.clientID == clientID else { throw CloudflareAccessCredentialError.clientSecretRequired }
        return saved
    }

    public func apply(to request: inout URLRequest) {
        request.setValue(clientID, forHTTPHeaderField: "CF-Access-Client-Id")
        request.setValue(clientSecret, forHTTPHeaderField: "CF-Access-Client-Secret")
    }

    private static func isHeaderValue(_ value: String) -> Bool {
        !value.isEmpty && value.unicodeScalars.allSatisfy { (0x21...0x7e).contains($0.value) }
    }
}
