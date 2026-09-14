import Foundation

struct NotificationInboxSnapshot: Codable, Equatable {
    static let maximumReplayEvents = 4_096
    static let maximumReplayOrigins = 64
    static let maximumPresentationRecords = 4_096

    var version = 1
    var entries: [NotificationInboxItem] = []
    var apps: [NotificationInboxApp] = []
    var pauseBanners = false
    var receivedEventIds: [String] = []
    var origins: [NotificationInboxOrigin] = []
    var presentationRecords: [NotificationInboxPresentationRecord] = []

    func isReplay(_ event: BridgeEvent) -> Bool {
        receivedEventIds.contains(event.eventId) || origins.contains {
            $0.deviceId == event.originDeviceId && $0.epoch == event.originEpoch && $0.sequence >= event.sequence
        }
    }

    mutating func record(_ event: BridgeEvent) throws {
        if let index = origins.firstIndex(where: { $0.deviceId == event.originDeviceId && $0.epoch == event.originEpoch }) {
            origins[index].sequence = event.sequence
        } else {
            guard origins.count < Self.maximumReplayOrigins else { throw NotificationInboxError.capacityExceeded }
            origins.append(NotificationInboxOrigin(deviceId: event.originDeviceId,
                                                    epoch: event.originEpoch, sequence: event.sequence))
        }
        receivedEventIds.append(event.eventId)
        receivedEventIds = Array(receivedEventIds.suffix(Self.maximumReplayEvents))
    }

    mutating func recordApp(_ payload: NotificationPayload) -> NotificationInboxApp {
        let previous = apps.first { $0.packageName == payload.packageName } ?? entries.first {
            $0.packageName == payload.packageName
        }.map {
            NotificationInboxApp(packageName: $0.packageName, appLabel: $0.appLabel,
                                  iconPngBase64: $0.iconPngBase64, isBlocked: false)
        }
        let oldLabel = previous?.appLabel
        let preserveLabel = payload.isRedacted == true && oldLabel?.isEmpty == false && oldLabel != payload.packageName
        let label = preserveLabel ? oldLabel : (payload.appLabel.isEmpty ? oldLabel : payload.appLabel)
        let app = NotificationInboxApp(packageName: payload.packageName,
                                       appLabel: label ?? payload.packageName,
                                       iconPngBase64: payload.iconPngBase64 ?? previous?.iconPngBase64,
                                       isBlocked: previous?.isBlocked ?? false)
        apps.removeAll { $0.packageName == payload.packageName }
        if apps.count == NotificationInboxStore.maximumApps {
            guard let index = apps.lastIndex(where: { !$0.isBlocked }) else { return app }
            apps.remove(at: index)
        }
        apps.insert(app, at: 0)
        return app
    }

    mutating func recordPresentation(_ item: NotificationInboxItem) {
        presentationRecords.removeAll { $0.id == item.id }
        presentationRecords.insert(NotificationInboxPresentationRecord(item: item), at: 0)
        presentationRecords = Array(presentationRecords.prefix(Self.maximumPresentationRecords))
    }

    mutating func setBlocked(packageName: String, blocked: Bool) throws {
        if let index = apps.firstIndex(where: { $0.packageName == packageName }) {
            let previous = apps[index]
            apps[index] = NotificationInboxApp(packageName: packageName, appLabel: previous.appLabel,
                                               iconPngBase64: previous.iconPngBase64, isBlocked: blocked)
        } else if blocked {
            if apps.count == NotificationInboxStore.maximumApps {
                guard let index = apps.lastIndex(where: { !$0.isBlocked }) else {
                    throw NotificationInboxError.capacityExceeded
                }
                apps.remove(at: index)
            }
            apps.insert(NotificationInboxApp(packageName: packageName, appLabel: packageName,
                                              iconPngBase64: nil, isBlocked: true), at: 0)
        }
    }

    func validate() throws {
        guard version == 1, entries.count <= NotificationInboxStore.maximumEntries,
              apps.count <= NotificationInboxStore.maximumApps,
              receivedEventIds.count <= Self.maximumReplayEvents, origins.count <= Self.maximumReplayOrigins,
              presentationRecords.count <= Self.maximumPresentationRecords,
              Set(entries.map(\.id)).count == entries.count,
              Set(apps.map(\.packageName)).count == apps.count,
              Set(receivedEventIds).count == receivedEventIds.count,
              Set(origins.map { [$0.deviceId, $0.epoch] }).count == origins.count,
              Set(presentationRecords.map(\.id)).count == presentationRecords.count,
              receivedEventIds.allSatisfy(Self.validIdentifier) else { throw NotificationInboxError.corruptState }
        for origin in origins {
            guard Self.validIdentifier(origin.deviceId), Self.validIdentifier(origin.epoch),
                  (1...9_007_199_254_740_991).contains(origin.sequence) else {
                throw NotificationInboxError.corruptState
            }
        }
        for app in apps { try Self.validateIdentity(app) }
        let blocked = Set(apps.filter(\.isBlocked).map(\.packageName))
        for record in presentationRecords {
            try record.validate()
            guard !blocked.contains(record.packageName) else { throw NotificationInboxError.corruptState }
        }
        for item in entries {
            let event = BridgeEvent(eventId: item.eventId, originDeviceId: item.originDeviceId,
                                    originRole: .android, originEpoch: item.originEpoch, sequence: item.sequence,
                                    createdAtMs: item.createdAtMs, payload: .notification(item.payload))
            try event.validate()
            try Self.validateIdentity(NotificationInboxApp(packageName: item.packageName,
                                                            appLabel: item.appLabel,
                                                            iconPngBase64: item.iconPngBase64, isBlocked: false))
            guard !blocked.contains(item.packageName),
                  presentationRecords.contains(NotificationInboxPresentationRecord(item: item)),
                  origins.contains(where: {
                      $0.deviceId == item.originDeviceId && $0.epoch == item.originEpoch && $0.sequence >= item.sequence
                  }) else { throw NotificationInboxError.corruptState }
        }
    }

    private static func validateIdentity(_ app: NotificationInboxApp) throws {
        let payload = NotificationPayload(notificationKey: "", packageName: app.packageName,
                                          appLabel: app.appLabel, title: "", body: "", iconPngBase64: app.iconPngBase64)
        try BridgeEvent(eventId: "identity", originDeviceId: "identity", originRole: .android,
                        originEpoch: "identity", sequence: 1, createdAtMs: 0, payload: .notification(payload)).validate()
    }

    private static func validIdentifier(_ value: String) -> Bool { !value.isEmpty && value.utf8.count <= 128 }
}

struct NotificationInboxOrigin: Codable, Equatable {
    let deviceId: String
    let epoch: String
    var sequence: Int64
}
