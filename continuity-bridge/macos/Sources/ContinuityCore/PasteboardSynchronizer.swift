import AppKit
import Foundation
import UniformTypeIdentifiers

@MainActor
public final class PasteboardSynchronizer {
    public static let eventIDType = NSPasteboard.PasteboardType(
        "com.froglike6.continuitybridge.event-id"
    )

    private let surface: PasteboardSurface
    private let state: DurableStateStore
    private let afterVerifiedWrite: @MainActor @Sendable () async -> Void
    private var lastObservedChangeCount: Int?
    private var monitorTask: Task<Void, Never>?
    public private(set) var lastCaptureError: PasteboardError?

    public init(surface: PasteboardSurface, state: DurableStateStore,
                afterVerifiedWrite: @escaping @MainActor @Sendable () async -> Void = {}) {
        self.surface = surface
        self.state = state
        self.afterVerifiedWrite = afterVerifiedWrite
        lastObservedChangeCount = nil
    }

    public convenience init(pasteboard: NSPasteboard, state: DurableStateStore) {
        self.init(surface: NSPasteboardSurface(pasteboard), state: state)
    }

    public func apply(_ event: BridgeEvent) async throws {
        guard event.originRole == .android, event.kind.isClipboard else {
            throw PasteboardError.invalidEvent
        }
        try await Task.detached(priority: .utility) { try event.validate() }.value
        let writtenChangeCount: Int
        let contentMatches: Bool
        switch event.payload {
        case .clipboard(let text):
            try await state.beginPasteboardApply(event)
            guard let count = surface.replace(text: text, eventId: event.eventId) else { throw PasteboardError.writeFailed }
            writtenChangeCount = count
            contentMatches = surface.string(for: .string) == text
        case .image(let image):
            let data = try await Task.detached(priority: .utility) { try PasteboardImageCodec.validate(image) }.value
            try await state.beginPasteboardApply(event)
            guard let count = surface.replace(image: image, data: data, eventId: event.eventId) else { throw PasteboardError.writeFailed }
            writtenChangeCount = count
            contentMatches = surface.data(for: NSPasteboardSurface.imageType(image.mimeType)) == data
        case .notification: throw PasteboardError.invalidEvent
        }
        guard contentMatches, surface.changeCount == writtenChangeCount,
              surface.string(for: Self.eventIDType) == event.eventId else {
            throw PasteboardError.verificationFailed
        }
        await afterVerifiedWrite()
        try await state.markPasteboardApplied(event, changeCount: writtenChangeCount)
    }

    public func pollOnce(createdAtMs: Int64) async throws -> BridgeEvent? {
        let current = surface.changeCount
        let marker = surface.string(for: Self.eventIDType)
        let correlation = await state.snapshot().pasteboardCorrelation
        if let correlation,
           correlation.eventId == marker,
           correlation.changeCount == nil || correlation.changeCount == current {
            lastObservedChangeCount = current
            return nil
        }
        guard current != lastObservedChangeCount || correlation != nil else { return nil }
        let payload: EventPayload?
        do { payload = try await capturedPayload() }
        catch let error as PasteboardError {
            lastCaptureError = error
            if current == surface.changeCount {
                if let correlation { try await state.consumePasteboardCorrelation(correlation) }
                lastObservedChangeCount = current
            }
            throw error
        }
        guard current == surface.changeCount else { return nil }
        guard let payload else {
            if let correlation { try await state.consumePasteboardCorrelation(correlation) }
            lastObservedChangeCount = current
            lastCaptureError = nil
            return nil
        }
        let event = try await state.enqueueClipboard(payload: payload, createdAtMs: createdAtMs, replacing: correlation)
        lastObservedChangeCount = current
        lastCaptureError = nil
        return event
    }

    private func capturedPayload() async throws -> EventPayload? {
        let formats: [(NSPasteboard.PasteboardType, String)] = [(.png, "image/png"), (.init("public.jpeg"), "image/jpeg"), (.tiff, "image/tiff")]
        for (type, mimeType) in formats where surface.types.contains(type) {
            guard let data = surface.data(for: type) else { throw PasteboardError.invalidImage }
            let image = try await Task.detached(priority: .utility) {
                try PasteboardImageCodec.capture(data, mimeType: mimeType)
            }.value
            return .image(image)
        }
        if surface.types.contains(where: { UTType($0.rawValue)?.conforms(to: .image) == true }) {
            throw PasteboardError.unsupportedImageFormat
        }
        return surface.string(for: .string).map { .clipboard(text: $0) }
    }

    public func start(intervalMilliseconds: Int = 500,
                      onEvent: @escaping @Sendable (BridgeEvent) async -> Void,
                      onError: @escaping @Sendable () async -> Void) {
        stop()
        let interval = min(max(intervalMilliseconds, 200), 2_000)
        monitorTask = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: .milliseconds(interval)) }
                catch { break }
                guard let self else { break }
                do {
                    if let event = try await self.pollOnce(
                        createdAtMs: Int64(Date().timeIntervalSince1970 * 1_000)
                    ) { await onEvent(event) }
                } catch { await onError() }
            }
        }
    }

    public func stop() {
        monitorTask?.cancel()
        monitorTask = nil
    }
}
