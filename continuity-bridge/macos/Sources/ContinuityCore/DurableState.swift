import Foundation

public struct ClientState: Codable, Equatable, Sendable {
    public var deviceId: String
    public var originEpoch: String
    public var nextSequence: Int64
    public var outbox: [BridgeEvent]
    public var appliedEventIds: [String]
    public var serverEpoch: String?
    public var cursor: String
    public var highWater: [String: Int64]
    public var inboundSequenceIds: [String: String]
    public var pasteboardCorrelation: PasteboardCorrelation? = nil
}

public struct PasteboardCorrelation: Codable, Equatable, Sendable {
    public let eventId: String
    public let changeCount: Int?

    public init(eventId: String, changeCount: Int? = nil) {
        self.eventId = eventId
        self.changeCount = changeCount
    }
}

public enum DurableStateError: Error, Equatable { case corruptState, invalidCursor, sequenceExhausted }
public enum InboundDisposition: Equatable, Sendable { case accept, duplicate, stale, conflict }

public actor DurableStateStore {
    static let maximumReplayOriginKeys = 64
    private var state: ClientState
    private let url: URL
    private let appliedLimit: Int
    private static let maximum = Int64(9_007_199_254_740_991)

    public static func applicationStateURL(fileManager: FileManager = .default) throws -> URL {
        let base = try fileManager.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                       appropriateFor: nil, create: true)
        return base.appendingPathComponent("ContinuityBridge", isDirectory: true).appendingPathComponent("state.json")
    }

    public init(url: URL, appliedLimit: Int = 4_096) throws {
        self.url = url
        self.appliedLimit = appliedLimit
        if FileManager.default.fileExists(atPath: url.path) {
            do { state = try StrictJSON.decode(ClientState.self, from: Data(contentsOf: url)) }
            catch { throw DurableStateError.corruptState }
            guard Self.valid(state, appliedLimit: appliedLimit) else { throw DurableStateError.corruptState }
        } else {
            state = ClientState(deviceId: UUID().uuidString.lowercased(), originEpoch: UUID().uuidString.lowercased(),
                                nextSequence: 1, outbox: [], appliedEventIds: [], serverEpoch: nil,
                                cursor: "0", highWater: [:], inboundSequenceIds: [:])
            try Self.persist(state, to: url)
        }
    }

    public func snapshot() -> ClientState { state }

    @discardableResult
    public func enqueueClipboard(text: String, createdAtMs: Int64,
                                 replacing correlation: PasteboardCorrelation? = nil) throws -> BridgeEvent {
        guard state.nextSequence <= Self.maximum else { throw DurableStateError.sequenceExhausted }
        let event = BridgeEvent(eventId: UUID().uuidString.lowercased(), originDeviceId: state.deviceId,
                                originRole: .macOS, originEpoch: state.originEpoch, sequence: state.nextSequence,
                                createdAtMs: createdAtMs, payload: .clipboard(text: text))
        try event.validate()
        try commit { candidate in
            candidate.nextSequence += 1
            candidate.outbox = [event]
            if let correlation, candidate.pasteboardCorrelation == correlation {
                candidate.pasteboardCorrelation = nil
            }
        }
        return event
    }

    public func acknowledgeOutbox(eventIds: Set<String>) throws {
        try commit { $0.outbox.removeAll { eventIds.contains($0.eventId) } }
    }

    public func markApplied(_ event: BridgeEvent) throws {
        try event.validate()
        guard event.originRole == .android else { throw DurableStateError.corruptState }
        try commit { try Self.recordApplied(event, in: &$0, limit: appliedLimit) }
    }

    public func markPasteboardApplied(_ event: BridgeEvent, changeCount: Int) throws {
        try event.validate()
        guard event.originRole == .android, event.kind == .clipboard, changeCount >= 0 else {
            throw DurableStateError.corruptState
        }
        try commit { candidate in
            try Self.recordApplied(event, in: &candidate, limit: appliedLimit)
            candidate.pasteboardCorrelation = PasteboardCorrelation(eventId: event.eventId,
                                                                      changeCount: changeCount)
        }
    }

    public func beginPasteboardApply(_ event: BridgeEvent) throws {
        try event.validate()
        guard event.originRole == .android, event.kind == .clipboard else {
            throw DurableStateError.corruptState
        }
        try commit { $0.pasteboardCorrelation = PasteboardCorrelation(eventId: event.eventId) }
    }

    public func consumePasteboardCorrelation(_ correlation: PasteboardCorrelation) throws {
        guard state.pasteboardCorrelation == correlation else { return }
        try commit { $0.pasteboardCorrelation = nil }
    }

    public func classifyInbound(_ event: BridgeEvent) -> InboundDisposition {
        if state.appliedEventIds.contains(event.eventId) { return .duplicate }
        let originKey = "\(event.originDeviceId)/\(event.originEpoch)"
        if state.highWater[originKey] == nil && state.highWater.count >= Self.maximumReplayOriginKeys {
            return .conflict
        }
        if let recorded = state.inboundSequenceIds["\(originKey)/\(event.sequence)"] {
            return recorded == event.eventId ? .duplicate : .conflict
        }
        if event.sequence <= state.highWater[originKey] ?? 0 { return .stale }
        return .accept
    }

    public func observeServerEpoch(_ serverEpoch: String) throws -> Bool {
        guard Self.validIdentifier(serverEpoch) else { throw DurableStateError.corruptState }
        let changed = state.serverEpoch != nil && state.serverEpoch != serverEpoch
        try commit { candidate in
            if changed { candidate.cursor = "0" }
            candidate.serverEpoch = serverEpoch
        }
        return changed
    }

    public func advanceCursor(_ cursor: String) throws {
        guard Self.validCursor(cursor) else { throw DurableStateError.invalidCursor }
        try commit { $0.cursor = cursor }
    }

    public func updateRelay(serverEpoch: String, cursor: String) throws {
        guard Self.validIdentifier(serverEpoch), Self.validCursor(cursor) else {
            throw DurableStateError.corruptState
        }
        try commit { candidate in
            let changed = candidate.serverEpoch != nil && candidate.serverEpoch != serverEpoch
            candidate.serverEpoch = serverEpoch
            candidate.cursor = changed ? "0" : cursor
        }
    }

    private func commit(_ mutation: (inout ClientState) throws -> Void) throws {
        var candidate = state
        try mutation(&candidate)
        guard Self.valid(candidate, appliedLimit: appliedLimit) else { throw DurableStateError.corruptState }
        try Self.persist(candidate, to: url)
        state = candidate
    }

    private static func persist(_ value: ClientState, to url: URL) throws {
        let directory = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        try JSONEncoder().encode(value).write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    private static func valid(_ value: ClientState, appliedLimit: Int) -> Bool {
        guard validIdentifier(value.deviceId), validIdentifier(value.originEpoch),
              (1...(maximum + 1)).contains(value.nextSequence), validCursor(value.cursor),
              value.serverEpoch.map(validIdentifier) ?? true, value.outbox.count <= 1,
              value.appliedEventIds.count <= appliedLimit,
              Set(value.appliedEventIds).count == value.appliedEventIds.count,
              value.appliedEventIds.allSatisfy(validIdentifier),
              value.highWater.count <= maximumReplayOriginKeys,
              value.highWater.keys.allSatisfy({ !$0.isEmpty && $0.utf8.count <= 257 }),
              value.highWater.values.allSatisfy({ (1...maximum).contains($0) }),
              value.inboundSequenceIds.count <= appliedLimit,
              value.inboundSequenceIds.keys.allSatisfy({ !$0.isEmpty && $0.utf8.count <= 274 }),
              value.inboundSequenceIds.values.allSatisfy(validIdentifier),
              Set(value.inboundSequenceIds.values).isSubset(of: Set(value.appliedEventIds)),
              value.pasteboardCorrelation.map({
                  validIdentifier($0.eventId) && ($0.changeCount.map { $0 >= 0 } ?? true)
              }) ?? true else {
            return false
        }
        return value.outbox.allSatisfy { event in
            event.originRole == .macOS && event.originDeviceId == value.deviceId &&
            event.originEpoch == value.originEpoch && event.kind == .clipboard &&
            event.sequence < value.nextSequence && (try? event.validate()) != nil
        }
    }

    private static func validIdentifier(_ value: String) -> Bool { !value.isEmpty && value.utf8.count <= 128 }

    private static func recordApplied(_ event: BridgeEvent, in candidate: inout ClientState, limit: Int) throws {
        let originKey = "\(event.originDeviceId)/\(event.originEpoch)"
        guard candidate.highWater[originKey] != nil || candidate.highWater.count < maximumReplayOriginKeys else {
            throw DurableStateError.corruptState
        }
        candidate.appliedEventIds.removeAll { $0 == event.eventId }
        candidate.appliedEventIds.append(event.eventId)
        if candidate.appliedEventIds.count > limit {
            candidate.appliedEventIds.removeFirst(candidate.appliedEventIds.count - limit)
        }
        candidate.highWater[originKey] = max(candidate.highWater[originKey] ?? 0, event.sequence)
        candidate.inboundSequenceIds["\(originKey)/\(event.sequence)"] = event.eventId
        let retained = Set(candidate.appliedEventIds)
        candidate.inboundSequenceIds = candidate.inboundSequenceIds.filter { retained.contains($0.value) }
    }
    private static func validCursor(_ value: String) -> Bool {
        guard !value.isEmpty, value.allSatisfy(\.isNumber), value == "0" || value.first != "0",
              let number = UInt64(value) else { return false }
        return number <= UInt64(maximum)
    }
}
