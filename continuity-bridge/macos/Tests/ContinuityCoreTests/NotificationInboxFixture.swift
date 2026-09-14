import Foundation
import XCTest
@testable import ContinuityCore

@MainActor
struct NotificationInboxFixture {
    let directory: URL
    let url: URL
    let store: NotificationInboxStore

    init(testCase: XCTestCase) throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("NotificationInboxTests-\(UUID().uuidString)", isDirectory: true)
        url = directory.appendingPathComponent("inbox.json")
        store = try NotificationInboxStore(url: url)
        let ownedDirectory = directory
        testCase.addTeardownBlock { try? FileManager.default.removeItem(at: ownedDirectory) }
    }
}

func inboxEvent(_ sequence: Int64, payload: NotificationPayload? = nil,
                device: String = "android-device", epoch: String = "android-epoch") -> BridgeEvent {
    BridgeEvent(eventId: "\(device)-\(epoch)-\(sequence)", originDeviceId: device,
                originRole: .android, originEpoch: epoch, sequence: sequence,
                createdAtMs: sequence * 1_000,
                payload: .notification(payload ?? NotificationPayload(
                    notificationKey: "notice", packageName: "com.example.messages",
                    appLabel: "Messages", title: "New message", body: "Message body")))
}
