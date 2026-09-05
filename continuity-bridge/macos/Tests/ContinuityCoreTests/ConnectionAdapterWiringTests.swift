import AppKit
import XCTest
@testable import ContinuityCore

@MainActor
final class ConnectionAdapterWiringTests: XCTestCase {
    func testConnectionActor_whenNotificationAddSucceeds_acknowledgesAfterRealRenderer() async throws {
        let fixture = try WiringFixture(testCase: self, permission: .authorized)
        let event = notificationEvent(id: "wired-notification")
        ScriptedURLProtocol.install([
            .response("GET", "/v1/events", status: 200, body: fetchBody(event)),
            .response("POST", "/v1/acks", status: 200, body: ackBody(event.eventId)),
            .response("GET", "/v1/events", status: 401, body: #"{"error":"unauthorized"}"#),
        ])

        let connection = fixture.connection()
        let run = await connection.start()
        await run.value

        let requestCount = await fixture.center.requestCount
        let cursor = await fixture.store.snapshot().cursor
        XCTAssertEqual(requestCount, 1)
        XCTAssertEqual(cursor, "1")
        XCTAssertNil(ScriptedURLProtocol.failure)
    }

    func testConnectionActor_whenNotificationDenied_withholdsAckAndExposesPermissionState() async throws {
        let fixture = try WiringFixture(testCase: self, permission: .denied)
        let event = notificationEvent(id: "denied-notification")
        ScriptedURLProtocol.install([
            .response("GET", "/v1/events", status: 200, body: fetchBody(event)),
        ])

        let connection = fixture.connection()
        let run = await connection.start()
        await run.value

        let status = await connection.status
        let cursor = await fixture.store.snapshot().cursor
        XCTAssertEqual(status, .permissionRequired)
        XCTAssertEqual(ScriptedURLProtocol.requestCount, 1)
        XCTAssertEqual(cursor, "0")
    }

    private func notificationEvent(id: String) -> BridgeEvent {
        let payload = NotificationPayload(notificationKey: "key", packageName: "com.example",
                                          appLabel: "앱", title: "제목", body: "내용")
        return BridgeEvent(eventId: id, originDeviceId: "android", originRole: .android,
                           originEpoch: "epoch", sequence: 1, createdAtMs: 1,
                           payload: .notification(payload))
    }

    private func fetchBody(_ event: BridgeEvent) -> String {
        let encoded = String(data: try! EventCodec.encode(event), encoding: .utf8)!
        return #"{"protocolVersion":1,"serverEpoch":"server","after":"0","nextCursor":"1","events":[{"cursor":"1","event":\#(encoded)}]}"#
    }

    private func ackBody(_ eventId: String) -> String {
        #"{"acked":["\#(eventId)"],"alreadyAbsent":[]}"#
    }
}

@MainActor
private final class WiringFixture {
    let store: DurableStateStore
    let pasteboard: PasteboardSynchronizer
    let center: WiringNotificationCenter

    init(testCase: XCTestCase, permission: NotificationAuthorization) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        testCase.addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        store = try DurableStateStore(url: directory.appendingPathComponent("state.json"))
        let board = NSPasteboard(name: NSPasteboard.Name("com.froglike6.connection.\(UUID().uuidString)"))
        testCase.addTeardownBlock { board.releaseGlobally() }
        pasteboard = PasteboardSynchronizer(pasteboard: board, state: store)
        center = WiringNotificationCenter(permission: permission)
    }

    func connection() -> ConnectionActor {
        let applier = RemoteEventApplier(pasteboard: pasteboard,
                                         notifications: AndroidNotificationRenderer(center: center))
        let dependencies = ConnectionDependencies(
            state: store,
            tokenProvider: { "token" },
            apply: { event in try await applier.apply(event) },
            sessionConfiguration: { ScriptedURLProtocol.configuration },
            sleep: { _ in }, jitter: { 0 }
        )
        return ConnectionActor(configuration: .test, dependencies: dependencies,
                               retryPolicy: RetryPolicy(baseMilliseconds: 1, capMilliseconds: 2))
    }
}

private actor WiringNotificationCenter: NotificationCenterClient {
    let permission: NotificationAuthorization
    private(set) var requestCount = 0
    init(permission: NotificationAuthorization) { self.permission = permission }
    func authorizationStatus() -> NotificationAuthorization { permission }
    func add(_ request: NativeNotificationRequest) { requestCount += 1 }
}
