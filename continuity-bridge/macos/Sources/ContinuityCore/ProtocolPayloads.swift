import Foundation

public enum WireLimits {
    public static let eventBodyBytes = 12_582_912
    public static let responseBodyBytes = 12_582_912
    public static let imageDecodedBytes = 8_388_608
    public static let imageEncodedBytes = 11_184_812
    public static let notificationPayloadBytes = 81_920
}

public struct ImagePayload: Codable, Equatable, Sendable {
    public let mimeType: String
    public let dataBase64: String

    public init(mimeType: String, dataBase64: String) {
        self.mimeType = mimeType
        self.dataBase64 = dataBase64
    }

    public func validatedData() throws -> Data {
        let signature: [UInt8]
        switch mimeType {
        case "image/png": signature = WireImageValidation.pngSignature
        case "image/jpeg": signature = [255, 216, 255]
        default: throw ProtocolError.malformedEvent
        }
        let data = try WireImageValidation.decode(dataBase64, encodedLimit: WireLimits.imageEncodedBytes,
                                                  decodedLimit: WireLimits.imageDecodedBytes)
        guard data.starts(with: signature) else { throw ProtocolError.malformedEvent }
        return data
    }
}

public struct NotificationProgress: Codable, Equatable, Sendable {
    public let value: Int
    public let max: Int
    public let indeterminate: Bool

    public init(value: Int, max: Int, indeterminate: Bool) {
        self.value = value
        self.max = max
        self.indeterminate = indeterminate
    }

    func validate() throws {
        guard (0...2_147_483_647).contains(max), (0...max).contains(value), max > 0 || indeterminate else {
            throw ProtocolError.malformedEvent
        }
    }
}

public struct NotificationPayload: Codable, Equatable, Sendable {
    public let notificationKey: String
    public let packageName: String
    public let appLabel: String
    public let title: String
    public let body: String
    public let iconPngBase64: String?
    public let progress: NotificationProgress?
    public let isOngoing: Bool?
    public let isRedacted: Bool?
    public let category: String?

    public init(notificationKey: String, packageName: String, appLabel: String, title: String, body: String,
                iconPngBase64: String? = nil, progress: NotificationProgress? = nil, isOngoing: Bool? = nil,
                isRedacted: Bool? = nil, category: String? = nil) {
        self.notificationKey = notificationKey
        self.packageName = packageName
        self.appLabel = appLabel
        self.title = title
        self.body = body
        self.iconPngBase64 = iconPngBase64
        self.progress = progress
        self.isOngoing = isOngoing
        self.isRedacted = isRedacted
        self.category = category
    }

    private enum CodingKeys: String, CodingKey {
        case notificationKey, packageName, appLabel, title, body, iconPngBase64, progress, isOngoing, isRedacted, category
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        notificationKey = try values.decode(String.self, forKey: .notificationKey)
        packageName = try values.decode(String.self, forKey: .packageName)
        appLabel = try values.decode(String.self, forKey: .appLabel)
        title = try values.decode(String.self, forKey: .title)
        body = try values.decode(String.self, forKey: .body)
        iconPngBase64 = try values.decodePresent(String.self, forKey: .iconPngBase64)
        progress = try values.decodePresent(NotificationProgress.self, forKey: .progress)
        isOngoing = try values.decodePresent(Bool.self, forKey: .isOngoing)
        isRedacted = try values.decodePresent(Bool.self, forKey: .isRedacted)
        category = try values.decodePresent(String.self, forKey: .category)
    }

    func validate() throws {
        let fields = [(notificationKey, 4_096), (packageName, 255), (appLabel, 4_096), (title, 8_192), (body, 65_536)]
        guard fields.allSatisfy({ $0.0.utf8.count <= $0.1 }), (category?.utf8.count ?? 0) <= 128 else {
            throw ProtocolError.payloadTooLarge
        }
        try progress?.validate()
        if let iconPngBase64 { try WireImageValidation.validateIcon(iconPngBase64) }
        guard try protocolJSONEncoder().encode(self).count <= WireLimits.notificationPayloadBytes else {
            throw ProtocolError.payloadTooLarge
        }
    }
}

private extension KeyedDecodingContainer {
    func decodePresent<T: Decodable>(_ type: T.Type, forKey key: Key) throws -> T? {
        contains(key) ? try decode(type, forKey: key) : nil
    }
}

private enum WireImageValidation {
    static let pngSignature: [UInt8] = [137, 80, 78, 71, 13, 10, 26, 10]

    static func decode(_ value: String, encodedLimit: Int, decodedLimit: Int) throws -> Data {
        guard value.utf8.count <= encodedLimit else { throw ProtocolError.payloadTooLarge }
        guard let data = Data(base64Encoded: value), data.base64EncodedString() == value else {
            throw ProtocolError.malformedEvent
        }
        guard data.count <= decodedLimit else { throw ProtocolError.payloadTooLarge }
        return data
    }

    static func validateIcon(_ value: String) throws {
        let data = try decode(value, encodedLimit: 16_384, decodedLimit: 12_288)
        guard data.count >= 33, data.starts(with: pngSignature),
              data[8..<12].elementsEqual([0, 0, 0, 13]), data[12..<16].elementsEqual("IHDR".utf8) else {
            throw ProtocolError.malformedEvent
        }
        let width = data[16..<20].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        let height = data[20..<24].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        guard (1...128).contains(width), (1...128).contains(height) else { throw ProtocolError.malformedEvent }
    }
}

func protocolJSONEncoder() -> JSONEncoder {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.withoutEscapingSlashes]
    return encoder
}
