import CryptoKit
import Foundation

struct NotificationInboxPresentationRecord: Codable, Equatable {
    let id: String
    let packageName: String
    let contentDigest: String
    let progressState: NotificationInboxProgressState

    init(item: NotificationInboxItem) {
        id = item.id
        packageName = item.packageName
        contentDigest = Self.digest(item)
        progressState = item.progressState
    }

    static func digest(_ item: NotificationInboxItem) -> String {
        let content = "\(item.title.utf8.count):\(item.title)\(item.body.utf8.count):\(item.body):\(item.isRedacted)"
        return SHA256.hash(data: Data(content.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    func validate() throws {
        guard Self.validDigest(id), Self.validDigest(contentDigest), packageName.utf8.count <= 255 else {
            throw NotificationInboxError.corruptState
        }
    }

    private static func validDigest(_ value: String) -> Bool {
        value.utf8.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
    }
}
