import AppKit
import XCTest
@testable import ContinuityCore

@MainActor
final class ImageClipboardTests: XCTestCase {
    func testPNG_whenCapturedAndRestarted_preservesBytesAndCoalescesText() async throws {
        let fixture = try makeFixture()
        let data = try imageData(.png)
        try await fixture.store.enqueueClipboard(text: "older", createdAtMs: 1)
        fixture.board.clearContents()
        fixture.board.setData(data, forType: .png)

        let event = try await fixture.sync.pollOnce(createdAtMs: 2)

        XCTAssertEqual(event?.payload, .image(ImagePayload(mimeType: "image/png", dataBase64: data.base64EncodedString())))
        let restored = try DurableStateStore(url: fixture.url)
        let snapshot = await restored.snapshot()
        XCTAssertEqual(snapshot.outbox, event.map { [$0] } ?? [])
        try await fixture.store.enqueueClipboard(text: "newest", createdAtMs: 3)
        let replaced = await fixture.store.snapshot()
        XCTAssertEqual(replaced.outbox.map(\.payload), [.clipboard(text: "newest")])
    }

    func testJPEG_whenApplied_hasOriginalDataAndSkipsEchoAfterRestart() async throws {
        let fixture = try makeFixture()
        let data = try imageData(.jpeg)
        let payload = ImagePayload(mimeType: "image/jpeg", dataBase64: data.base64EncodedString())
        let event = BridgeEvent(eventId: "remote-image", originDeviceId: "android", originRole: .android,
                                originEpoch: "epoch", sequence: 1, createdAtMs: 1, payload: .image(payload))

        try await fixture.sync.apply(event)

        XCTAssertEqual(fixture.board.data(forType: .init("public.jpeg")), data)
        let restarted = PasteboardSynchronizer(pasteboard: fixture.board, state: try DurableStateStore(url: fixture.url))
        let echo = try await restarted.pollOnce(createdAtMs: 2)
        XCTAssertNil(echo)
        let snapshot = await fixture.store.snapshot()
        XCTAssertTrue(snapshot.appliedEventIds.contains(event.eventId))
        XCTAssertEqual(snapshot.pasteboardCorrelation?.eventId, event.eventId)
    }

    func testInvalidImage_whenPolled_preservesClipboardAndReportsFormatFailure() async throws {
        let fixture = try makeFixture()
        let data = Data([137, 80, 78, 71, 13, 10, 26, 10])
        fixture.board.clearContents(); fixture.board.setData(data, forType: .png)

        do {
            _ = try await fixture.sync.pollOnce(createdAtMs: 1)
            XCTFail("invalid PNG must be rejected at the image decoder")
        } catch { XCTAssertEqual(error as? PasteboardError, .invalidImage) }

        XCTAssertEqual(fixture.board.data(forType: .png), data)
        XCTAssertEqual(fixture.sync.lastCaptureError, .invalidImage)
        let snapshot = await fixture.store.snapshot()
        XCTAssertTrue(snapshot.outbox.isEmpty)
    }

    func testOversizeImage_whenPolled_preservesSourceAndReportsSize() async throws {
        let fixture = try makeFixture()
        let data = Data(repeating: 0, count: 8_388_609)
        fixture.board.clearContents(); fixture.board.setData(data, forType: .png)
        do { _ = try await fixture.sync.pollOnce(createdAtMs: 1); XCTFail("oversize image accepted") }
        catch { XCTAssertEqual(error as? PasteboardError, .imageTooLarge) }
        XCTAssertEqual(fixture.board.data(forType: .png), data)
    }

    func testTIFF_whenNativeCopyHasNoPNG_encodesBoundedPNG() async throws {
        let fixture = try makeFixture()
        let data = try imageData(.tiff)
        fixture.board.clearContents(); fixture.board.setData(data, forType: .tiff)
        let event = try await fixture.sync.pollOnce(createdAtMs: 1)
        guard case .image(let payload) = event?.payload else { return XCTFail("native TIFF copy did not become an image") }
        XCTAssertEqual(payload.mimeType, "image/png")
        XCTAssertNoThrow(try payload.validatedData())
        XCTAssertEqual(fixture.board.data(forType: .tiff), data)
    }

    func testUnsupportedImage_whenReplacedByText_resumesCaptureAndClearsFailure() async throws {
        let fixture = try makeFixture()
        fixture.board.clearContents(); fixture.board.setData(Data("GIF89a".utf8), forType: .init("com.compuserve.gif"))
        do { _ = try await fixture.sync.pollOnce(createdAtMs: 1); XCTFail("unsupported image accepted") }
        catch { XCTAssertEqual(error as? PasteboardError, .unsupportedImageFormat) }
        fixture.board.clearContents(); fixture.board.setString("text still works", forType: .string)
        let event = try await fixture.sync.pollOnce(createdAtMs: 2)
        XCTAssertEqual(event?.payload, .clipboard(text: "text still works"))
        XCTAssertNil(fixture.sync.lastCaptureError)
    }

    func testImageDimensions_whenOverBound_areRejectedBeforeBitmapDecode() throws {
        let bitmap = try XCTUnwrap(NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 8_193, pixelsHigh: 1,
                bitsPerSample: 8, samplesPerPixel: 3, hasAlpha: false, isPlanar: false,
                colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0))
        let data = try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
        XCTAssertThrowsError(try PasteboardImageCodec.capture(data, mimeType: "image/png")) {
            XCTAssertEqual($0 as? PasteboardError, .imageDimensionsTooLarge)
        }
    }

    func testMaximumWireImage_whenPersistedWithManyBase64Slashes_fitsBoundAndRestarts() async throws {
        let fixture = try makeFixture()
        var bytes = Data([137, 80, 78, 71, 13, 10, 26, 10])
        bytes.append(Data(repeating: 255, count: WireLimits.imageDecodedBytes - bytes.count))
        let image = ImagePayload(mimeType: "image/png", dataBase64: bytes.base64EncodedString())
        try await fixture.store.enqueueClipboard(payload: .image(image), createdAtMs: 1)
        let size = try fixture.url.resourceValues(forKeys: [.fileSizeKey]).fileSize
        XCTAssertLessThan(try XCTUnwrap(size), 16_777_216)
        let restored = try DurableStateStore(url: fixture.url)
        let snapshot = await restored.snapshot()
        XCTAssertEqual(snapshot.outbox.first?.payload, .image(image))
    }

    private func imageData(_ type: NSBitmapImageRep.FileType) throws -> Data {
        let bitmap = try XCTUnwrap(NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 2, pixelsHigh: 2,
                bitsPerSample: 8, samplesPerPixel: 3, hasAlpha: false, isPlanar: false,
                colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0))
        bitmap.setColor(NSColor(calibratedRed: 0.1, green: 0.4, blue: 0.8, alpha: 1), atX: 0, y: 0)
        bitmap.setColor(NSColor(calibratedRed: 0.9, green: 0.5, blue: 0.1, alpha: 1), atX: 1, y: 1)
        return try XCTUnwrap(bitmap.representation(using: type, properties: [:]))
    }

    private func makeFixture() throws -> (board: NSPasteboard, store: DurableStateStore, sync: PasteboardSynchronizer, url: URL) {
        let board = NSPasteboard(name: .init("continuity-image-\(UUID().uuidString)"))
        board.clearContents()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let url = directory.appendingPathComponent("state.json")
        let store = try DurableStateStore(url: url)
        addTeardownBlock { board.releaseGlobally(); try? FileManager.default.removeItem(at: directory) }
        return (board, store, PasteboardSynchronizer(pasteboard: board, state: store), url)
    }
}
