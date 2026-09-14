import CryptoKit
import Foundation

public enum NotificationInboxProgressState: String, Codable, Equatable, Sendable {
    case none, active, completed, failed, unknown
}

public struct NotificationInboxItem: Codable, Equatable, Identifiable, Sendable {
    public let eventId: String
    public let originDeviceId: String
    public let originEpoch: String
    public let sequence: Int64
    public let createdAtMs: Int64
    public let payload: NotificationPayload
    public let appLabel: String
    public let iconPngBase64: String?
    public let progressState: NotificationInboxProgressState

    public var id: String { Self.identifier(deviceId: originDeviceId, notificationKey: notificationKey) }
    public var notificationKey: String { payload.notificationKey }
    public var packageName: String { payload.packageName }
    public var title: String { payload.title }
    public var body: String { payload.body }
    public var progress: NotificationProgress? { payload.progress }
    public var isOngoing: Bool { payload.isOngoing ?? false }
    public var isRedacted: Bool { payload.isRedacted ?? false }
    public var category: String? { payload.category }

    static func identifier(deviceId: String, notificationKey: String) -> String {
        let identity = "\(deviceId.utf8.count):\(deviceId)\(notificationKey.utf8.count):\(notificationKey)"
        return SHA256.hash(data: Data(identity.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    init(event: BridgeEvent, payload: NotificationPayload, app: NotificationInboxApp,
         progressState: NotificationInboxProgressState) {
        eventId = event.eventId
        originDeviceId = event.originDeviceId
        originEpoch = event.originEpoch
        sequence = event.sequence
        createdAtMs = event.createdAtMs
        self.payload = payload
        appLabel = app.appLabel
        iconPngBase64 = app.iconPngBase64
        self.progressState = progressState
    }
}

public struct NotificationInboxApp: Codable, Equatable, Identifiable, Sendable {
    public let packageName: String
    public let appLabel: String
    public let iconPngBase64: String?
    public let isBlocked: Bool
    public var id: String { packageName }
}

public struct NotificationInboxReceipt: Equatable, Sendable {
    public let item: NotificationInboxItem?
    public let shouldPresent: Bool
}

public enum NotificationInboxError: Error, Equatable, Sendable {
    case invalidEvent, corruptState, capacityExceeded, storageUnavailable
}
