import Darwin
import Foundation

enum NotificationInboxPersistence {
    static let maximumBytes = 33_554_432

    static func load(from url: URL) throws -> NotificationInboxSnapshot {
        guard FileManager.default.fileExists(atPath: url.path) else {
            let empty = NotificationInboxSnapshot()
            try save(empty, to: url)
            return empty
        }
        let data: Data
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
            guard attributes[.type] as? FileAttributeType == .typeRegular,
                  let size = attributes[.size] as? NSNumber, size.int64Value <= maximumBytes else {
                throw NotificationInboxError.corruptState
            }
            let file = try FileHandle(forReadingFrom: url)
            defer { try? file.close() }
            data = try file.read(upToCount: maximumBytes + 1) ?? Data()
        } catch let error as NotificationInboxError { throw error }
        catch { throw NotificationInboxError.storageUnavailable }
        guard data.count <= maximumBytes else { throw NotificationInboxError.corruptState }
        do {
            let snapshot = try StrictJSON.decode(NotificationInboxSnapshot.self, from: data)
            try snapshot.validate()
            return snapshot
        } catch { throw NotificationInboxError.corruptState }
    }

    static func save(_ snapshot: NotificationInboxSnapshot, to url: URL) throws {
        do {
            let data = try JSONEncoder().encode(snapshot)
            guard data.count <= maximumBytes else { throw NotificationInboxError.capacityExceeded }
            let directory = url.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                    attributes: [.posixPermissions: 0o700])
            let temporary = directory.appendingPathComponent(".notification-inbox-\(UUID().uuidString).tmp")
            let descriptor = open(temporary.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0o600)
            guard descriptor >= 0 else { throw NotificationInboxError.storageUnavailable }
            let file = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
            var renamed = false
            defer {
                try? file.close()
                if !renamed { try? FileManager.default.removeItem(at: temporary) }
            }
            try file.write(contentsOf: data)
            try file.synchronize()
            try file.close()
            guard rename(temporary.path, url.path) == 0 else { throw NotificationInboxError.storageUnavailable }
            renamed = true
        } catch let error as NotificationInboxError { throw error }
        catch { throw NotificationInboxError.storageUnavailable }
    }
}
