import Combine
import Foundation

@MainActor
public final class NotificationInboxStore: ObservableObject {
    public nonisolated static let maximumEntries = 100
    public nonisolated static let maximumApps = 256

    @Published public private(set) var entries: [NotificationInboxItem]
    @Published public private(set) var apps: [NotificationInboxApp]
    @Published public private(set) var pauseBanners: Bool

    private let url: URL
    private var snapshot: NotificationInboxSnapshot

    public static func applicationStateURL(fileManager: FileManager = .default) throws -> URL {
        let base = try fileManager.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                       appropriateFor: nil, create: true)
        return base.appendingPathComponent("ContinuityBridge", isDirectory: true)
            .appendingPathComponent("notification-inbox.json")
    }

    public init(url: URL? = nil) throws {
        self.url = try url ?? Self.applicationStateURL()
        snapshot = try NotificationInboxPersistence.load(from: self.url)
        entries = snapshot.entries
        apps = Self.sortedApps(snapshot.apps)
        pauseBanners = snapshot.pauseBanners
    }

    @discardableResult
    public func receive(_ event: BridgeEvent) throws -> NotificationInboxReceipt {
        guard event.originRole == .android, case .notification(let payload) = event.payload else {
            throw NotificationInboxError.invalidEvent
        }
        do { try event.validate() }
        catch { throw NotificationInboxError.invalidEvent }
        let id = NotificationInboxItem.identifier(deviceId: event.originDeviceId,
                                                   notificationKey: payload.notificationKey)
        if snapshot.isReplay(event) {
            return NotificationInboxReceipt(item: snapshot.entries.first { $0.id == id }, shouldPresent: false)
        }
        let previous = snapshot.presentationRecords.first { $0.id == id }

        var candidate = snapshot
        try candidate.record(event)
        let app = candidate.recordApp(payload)
        candidate.entries.removeAll { $0.id == id }
        let item: NotificationInboxItem?
        let shouldPresent: Bool
        if app.isBlocked {
            candidate.presentationRecords.removeAll { $0.id == id }
            item = nil
            shouldPresent = false
        } else {
            let state = NotificationInboxPresentation.progressState(for: payload, previous: previous)
            let received = NotificationInboxItem(event: event, payload: payload, app: app, progressState: state)
            item = received
            shouldPresent = !candidate.pauseBanners &&
                NotificationInboxPresentation.shouldPresent(received, replacing: previous)
            candidate.entries.insert(received, at: 0)
            candidate.entries = Array(candidate.entries.prefix(Self.maximumEntries))
            candidate.recordPresentation(received)
        }
        try commit(candidate)
        return NotificationInboxReceipt(item: item, shouldPresent: shouldPresent)
    }

    public func setBlocked(packageName: String, blocked: Bool) throws {
        guard packageName.utf8.count <= 255 else { throw NotificationInboxError.invalidEvent }
        var candidate = snapshot
        try candidate.setBlocked(packageName: packageName, blocked: blocked)
        if blocked {
            candidate.entries.removeAll { $0.packageName == packageName }
            candidate.presentationRecords.removeAll { $0.packageName == packageName }
        }
        try commit(candidate)
    }

    public func dismiss(id: String) throws {
        var candidate = snapshot
        candidate.entries.removeAll { $0.id == id }
        try commit(candidate)
    }

    public func clear() throws {
        var candidate = snapshot
        candidate.entries.removeAll()
        try commit(candidate)
    }

    public func setPauseBanners(_ paused: Bool) throws {
        var candidate = snapshot
        candidate.pauseBanners = paused
        try commit(candidate)
    }

    private func commit(_ candidate: NotificationInboxSnapshot) throws {
        guard candidate != snapshot else { return }
        try NotificationInboxPersistence.save(candidate, to: url)
        snapshot = candidate
        entries = candidate.entries
        apps = Self.sortedApps(candidate.apps)
        pauseBanners = candidate.pauseBanners
    }

    private static func sortedApps(_ apps: [NotificationInboxApp]) -> [NotificationInboxApp] {
        apps.sorted {
            let order = $0.appLabel.localizedCaseInsensitiveCompare($1.appLabel)
            return order == .orderedSame ? $0.packageName < $1.packageName : order == .orderedAscending
        }
    }
}
