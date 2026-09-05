import Foundation

enum ResponseValidation {
    static let maximum = UInt64(9_007_199_254_740_991)

    static func publish(_ value: PublishResponse, status: Int, expectedEventId: String) throws {
        guard (status == 200 || status == 201), value.accepted, value.eventId == expectedEventId,
              validCursor(value.cursor), validIdentifier(value.serverEpoch) else {
            throw TransportError.malformedResponse
        }
    }

    static func fetch(_ value: FetchResponse, expectedAfter: String) throws {
        guard value.protocolVersion == 1, value.after == expectedAfter,
              validIdentifier(value.serverEpoch), validCursor(value.nextCursor),
              let after = UInt64(value.after), let next = UInt64(value.nextCursor), next >= after else {
            throw TransportError.malformedResponse
        }
        var previous = after
        for entry in value.events {
            guard validCursor(entry.cursor), let cursor = UInt64(entry.cursor), cursor > previous, cursor <= next,
                  entry.event.originRole == .android else { throw TransportError.malformedResponse }
            previous = cursor
        }
    }

    static func ack(_ value: AckResponse, expectedEventId: String) throws {
        let all = value.acked + value.alreadyAbsent
        guard value.acked.count <= 100, value.alreadyAbsent.count <= 100,
              Set(all).count == all.count, all.count == 1, all[0] == expectedEventId else {
            throw TransportError.malformedResponse
        }
    }

    static func validCursor(_ value: String) -> Bool {
        guard !value.isEmpty, value.allSatisfy(\.isNumber), value == "0" || value.first != "0",
              let number = UInt64(value) else { return false }
        return number <= maximum
    }

    static func validIdentifier(_ value: String) -> Bool { !value.isEmpty && value.utf8.count <= 128 }
}
