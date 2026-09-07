import AppKit
import Foundation

public enum PasteboardError: Error, Equatable {
    case invalidEvent
    case writeFailed
    case verificationFailed
}

@MainActor
public protocol PasteboardSurface {
    var changeCount: Int { get }
    func string(for type: NSPasteboard.PasteboardType) -> String?
    func replace(text: String, eventId: String) -> Int?
}

@MainActor
public final class NSPasteboardSurface: PasteboardSurface {
    private let pasteboard: NSPasteboard

    public init(_ pasteboard: NSPasteboard) { self.pasteboard = pasteboard }
    public var changeCount: Int { pasteboard.changeCount }
    public func string(for type: NSPasteboard.PasteboardType) -> String? {
        pasteboard.string(forType: type)
    }

    public func replace(text: String, eventId: String) -> Int? {
        pasteboard.declareTypes([.string, PasteboardSynchronizer.eventIDType], owner: nil)
        guard pasteboard.setString(text, forType: .string),
              pasteboard.setString(eventId, forType: PasteboardSynchronizer.eventIDType) else { return nil }
        return pasteboard.changeCount
    }
}

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
        guard event.originRole == .android, case .clipboard(let text) = event.payload else {
            throw PasteboardError.invalidEvent
        }
        try event.validate()
        try await state.beginPasteboardApply(event)
        guard let writtenChangeCount = surface.replace(text: text, eventId: event.eventId) else {
            throw PasteboardError.writeFailed
        }
        guard surface.changeCount == writtenChangeCount,
              surface.string(for: .string) == text,
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
        guard let text = surface.string(for: .string) else {
            if let correlation { try await state.consumePasteboardCorrelation(correlation) }
            lastObservedChangeCount = current
            return nil
        }
        let event = try await state.enqueueClipboard(text: text, createdAtMs: createdAtMs,
                                                      replacing: correlation)
        lastObservedChangeCount = current
        return event
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
