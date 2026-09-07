import AppKit
import XCTest
@testable import ContinuityCore

@MainActor
final class PasteboardRecoveryTests: XCTestCase {
    func testLocalCopy_whenPersistenceFails_retriesUnchangedClipboardAfterRecovery() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let directory = root.appendingPathComponent("state")
        let backup = root.appendingPathComponent("backup")
        let board = NSPasteboard(name: .init("continuity-recovery-\(UUID().uuidString)"))
        defer {
            board.releaseGlobally()
            try? FileManager.default.removeItem(at: root)
        }
        let store = try DurableStateStore(url: directory.appendingPathComponent("state.json"))
        let monitor = PasteboardSynchronizer(pasteboard: board, state: store)
        board.clearContents()
        board.setString("복구 후 전송할 내용", forType: .string)
        try FileManager.default.moveItem(at: directory, to: backup)
        try Data("blocked".utf8).write(to: directory)

        do {
            _ = try await monitor.pollOnce(createdAtMs: 1)
            XCTFail("the unavailable state directory must reject the first enqueue")
        } catch {}
        try FileManager.default.removeItem(at: directory)
        try FileManager.default.moveItem(at: backup, to: directory)
        let recovered = try await monitor.pollOnce(createdAtMs: 2)
        let duplicate = try await monitor.pollOnce(createdAtMs: 3)

        XCTAssertEqual(recovered?.payload, .clipboard(text: "복구 후 전송할 내용"))
        XCTAssertNil(duplicate)
        let state = await store.snapshot()
        XCTAssertEqual(state.outbox.count, 1)
    }
}
