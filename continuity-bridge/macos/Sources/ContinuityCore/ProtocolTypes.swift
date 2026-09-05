import Foundation

public enum DeviceRole: String, Codable, Sendable { case android, macOS = "macos" }
public enum EventKind: String, Codable, Sendable { case clipboard = "clipboard.text", notification = "android.notification" }

public struct NotificationPayload: Codable, Equatable, Sendable {
    public let notificationKey: String
    public let packageName: String
    public let appLabel: String
    public let title: String
    public let body: String

    public init(notificationKey: String, packageName: String, appLabel: String, title: String, body: String) {
        self.notificationKey = notificationKey
        self.packageName = packageName
        self.appLabel = appLabel
        self.title = title
        self.body = body
    }
}

public enum EventPayload: Equatable, Sendable {
    case clipboard(text: String)
    case notification(NotificationPayload)
}

public enum ProtocolError: Error, Equatable, Sendable {
    case malformedEvent
    case unsupportedVersion
    case unsupportedKind
    case identityMismatch
    case directionForbidden
    case payloadTooLarge
}

public struct BridgeEvent: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let eventId: String
    public let originDeviceId: String
    public let originRole: DeviceRole
    public let originEpoch: String
    public let sequence: Int64
    public let kind: EventKind
    public let createdAtMs: Int64
    public let expiresAtMs: Int64?
    public let payload: EventPayload

    public init(eventId: String, originDeviceId: String, originRole: DeviceRole, originEpoch: String,
                sequence: Int64, createdAtMs: Int64, expiresAtMs: Int64? = nil, payload: EventPayload) {
        protocolVersion = 1
        self.eventId = eventId
        self.originDeviceId = originDeviceId
        self.originRole = originRole
        self.originEpoch = originEpoch
        self.sequence = sequence
        self.createdAtMs = createdAtMs
        self.expiresAtMs = expiresAtMs
        self.payload = payload
        switch payload {
        case .clipboard: kind = .clipboard
        case .notification: kind = .notification
        }
    }

    private enum CodingKeys: String, CodingKey {
        case protocolVersion, eventId, originDeviceId, originRole, originEpoch, sequence, kind, createdAtMs, expiresAtMs, payload
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        let version = try values.decode(Int.self, forKey: .protocolVersion)
        guard version == 1 else { throw ProtocolError.unsupportedVersion }
        guard let parsedKind = EventKind(rawValue: try values.decode(String.self, forKey: .kind)) else {
            throw ProtocolError.unsupportedKind
        }
        guard let parsedRole = DeviceRole(rawValue: try values.decode(String.self, forKey: .originRole)) else {
            throw ProtocolError.malformedEvent
        }
        protocolVersion = version
        kind = parsedKind
        originRole = parsedRole
        eventId = try values.decode(String.self, forKey: .eventId)
        originDeviceId = try values.decode(String.self, forKey: .originDeviceId)
        originEpoch = try values.decode(String.self, forKey: .originEpoch)
        sequence = try values.decode(Int64.self, forKey: .sequence)
        createdAtMs = try values.decode(Int64.self, forKey: .createdAtMs)
        expiresAtMs = try values.decodeIfPresent(Int64.self, forKey: .expiresAtMs)
        switch parsedKind {
        case .clipboard:
            let content = try values.decode(ClipboardPayload.self, forKey: .payload)
            payload = .clipboard(text: content.text)
        case .notification:
            payload = .notification(try values.decode(NotificationPayload.self, forKey: .payload))
        }
        try validate()
    }

    public func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(protocolVersion, forKey: .protocolVersion)
        try values.encode(eventId, forKey: .eventId)
        try values.encode(originDeviceId, forKey: .originDeviceId)
        try values.encode(originRole.rawValue, forKey: .originRole)
        try values.encode(originEpoch, forKey: .originEpoch)
        try values.encode(sequence, forKey: .sequence)
        try values.encode(kind.rawValue, forKey: .kind)
        try values.encode(createdAtMs, forKey: .createdAtMs)
        try values.encodeIfPresent(expiresAtMs, forKey: .expiresAtMs)
        switch payload {
        case .clipboard(let text): try values.encode(ClipboardPayload(text: text), forKey: .payload)
        case .notification(let content): try values.encode(content, forKey: .payload)
        }
    }

    public func validate() throws {
        let maximum = Int64(9_007_199_254_740_991)
        guard protocolVersion == 1, valid(eventId, 128, false), valid(originDeviceId, 128, false),
              valid(originEpoch, 128, false), (1...maximum).contains(sequence),
              (0...maximum).contains(createdAtMs),
              expiresAtMs.map({ (createdAtMs...maximum).contains($0) }) ?? true else {
            throw ProtocolError.malformedEvent
        }
        switch payload {
        case .clipboard(let text):
            guard text.utf8.count <= 1_048_576 else { throw ProtocolError.payloadTooLarge }
        case .notification(let item):
            let fields = [(item.notificationKey, 4_096), (item.packageName, 255), (item.appLabel, 4_096),
                          (item.title, 8_192), (item.body, 65_536)]
            guard fields.allSatisfy({ valid($0.0, $0.1, true) }),
                  (try? JSONEncoder().encode(item).count) ?? Int.max <= 81_920 else {
                throw ProtocolError.payloadTooLarge
            }
        }
    }
}

private struct ClipboardPayload: Codable { let text: String }
private func valid(_ value: String, _ limit: Int, _ empty: Bool) -> Bool {
    (empty || !value.isEmpty) && value.utf8.count <= limit
}

public enum EventCodec {
    public static func decode(_ data: Data) throws -> BridgeEvent {
        guard data.count <= 1_114_112 else { throw ProtocolError.payloadTooLarge }
        do { return try StrictJSON.decode(BridgeEvent.self, from: data) }
        catch let error as ProtocolError { throw error }
        catch { throw ProtocolError.malformedEvent }
    }

    public static func encode(_ event: BridgeEvent) throws -> Data {
        try event.validate()
        let data = try JSONEncoder().encode(event)
        guard data.count <= 1_114_112 else { throw ProtocolError.payloadTooLarge }
        return data
    }
}
