import CryptoKit
import Foundation
import UserNotifications

public enum NotificationAuthorization: Equatable, Sendable {
    case notDetermined, denied, authorized, provisional, ephemeral
}

public struct NativeNotificationRequest: Equatable, Sendable {
    public let identifier: String
    public let title: String
    public let subtitle: String
    public let body: String

    public init(identifier: String, title: String, subtitle: String, body: String) {
        self.identifier = identifier
        self.title = title
        self.subtitle = subtitle
        self.body = body
    }
}

public protocol NotificationCenterClient: Sendable {
    func authorizationStatus() async -> NotificationAuthorization
    func add(_ request: NativeNotificationRequest) async throws
}

public enum NotificationApplyError: Error, Equatable {
    case invalidEvent, denied, notDetermined, addFailed
}

public struct AndroidNotificationRenderer: Sendable {
    private enum Destination: Sendable {
        case native(any NotificationCenterClient)
        case custom(@Sendable (BridgeEvent) async throws -> Void)
    }
    private let destination: Destination

    public init(center: NotificationCenterClient) { destination = .native(center) }

    public init(deliver: @escaping @Sendable (BridgeEvent) async throws -> Void) {
        destination = .custom(deliver)
    }

    public static func requestIdentifier(deviceId: String, notificationKey: String) -> String {
        let data = Data("\(deviceId)\0\(notificationKey)".utf8)
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    public func apply(_ event: BridgeEvent) async throws {
        guard event.originRole == .android, case .notification(let payload) = event.payload else {
            throw NotificationApplyError.invalidEvent
        }
        do { try event.validate() }
        catch { throw NotificationApplyError.invalidEvent }
        switch destination {
        case .custom(let deliver):
            try await deliver(event)
        case .native(let center):
            try await deliverNative(event, payload: payload, center: center)
        }
    }

    private func deliverNative(_ event: BridgeEvent, payload: NotificationPayload,
                               center: any NotificationCenterClient) async throws {
        switch await center.authorizationStatus() {
        case .authorized, .provisional, .ephemeral:
            break
        case .denied:
            throw NotificationApplyError.denied
        case .notDetermined:
            throw NotificationApplyError.notDetermined
        }
        let fallback = payload.appLabel.isEmpty ? payload.packageName : payload.appLabel
        let request = NativeNotificationRequest(
            identifier: Self.requestIdentifier(deviceId: event.originDeviceId,
                                               notificationKey: payload.notificationKey),
            title: payload.title.isEmpty ? fallback : payload.title,
            subtitle: payload.appLabel,
            body: payload.body
        )
        do { try await center.add(request) }
        catch { throw NotificationApplyError.addFailed }
    }
}

public final class SystemNotificationCenterClient: NotificationCenterClient, @unchecked Sendable {
    private let center: UNUserNotificationCenter

    public init(center: UNUserNotificationCenter = .current()) { self.center = center }

    public func authorizationStatus() async -> NotificationAuthorization {
        await withCheckedContinuation { continuation in
            center.getNotificationSettings { settings in
                let status: NotificationAuthorization
                switch settings.authorizationStatus {
                case .authorized: status = .authorized
                case .denied: status = .denied
                case .notDetermined: status = .notDetermined
                case .provisional: status = .provisional
                case .ephemeral: status = .ephemeral
                @unknown default: status = .denied
                }
                continuation.resume(returning: status)
            }
        }
    }

    public func add(_ request: NativeNotificationRequest) async throws {
        let content = UNMutableNotificationContent()
        content.title = request.title
        content.subtitle = request.subtitle
        content.body = request.body
        let native = UNNotificationRequest(identifier: request.identifier, content: content, trigger: nil)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            center.add(native) { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            }
        }
    }
}

public struct RemoteEventApplier: Sendable {
    private let pasteboard: PasteboardSynchronizer
    private let notifications: AndroidNotificationRenderer

    public init(pasteboard: PasteboardSynchronizer, notifications: AndroidNotificationRenderer) {
        self.pasteboard = pasteboard
        self.notifications = notifications
    }

    public func apply(_ event: BridgeEvent) async throws {
        switch event.payload {
        case .clipboard, .image:
            try await pasteboard.apply(event)
        case .notification:
            try await notifications.apply(event)
        }
    }
}
