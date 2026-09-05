import Foundation

enum StrictJSON {
    static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        var scanner = StrictJSONScanner(bytes: Array(data))
        try scanner.parseDocument()
        return try JSONDecoder().decode(type, from: data)
    }
}

private struct StrictJSONScanner {
    let bytes: [UInt8]
    var index = 0

    mutating func parseDocument() throws {
        skipWhitespace()
        try parseValue()
        skipWhitespace()
        guard index == bytes.count else { throw ProtocolError.malformedEvent }
    }

    private mutating func parseValue() throws {
        guard let byte = current else { throw ProtocolError.malformedEvent }
        switch byte {
        case 0x7b: try parseObject()
        case 0x5b: try parseArray()
        case 0x22: _ = try parseString()
        case 0x74: try consumeLiteral([0x74, 0x72, 0x75, 0x65])
        case 0x66: try consumeLiteral([0x66, 0x61, 0x6c, 0x73, 0x65])
        case 0x6e: try consumeLiteral([0x6e, 0x75, 0x6c, 0x6c])
        case 0x2d, 0x30...0x39: try parseNumber()
        default: throw ProtocolError.malformedEvent
        }
    }

    private mutating func parseObject() throws {
        index += 1
        skipWhitespace()
        if consume(0x7d) { return }
        var keys = Set<String>()
        while true {
            guard current == 0x22 else { throw ProtocolError.malformedEvent }
            let range = try parseString()
            let raw = Data(bytes[range])
            guard let key = try? JSONDecoder().decode(String.self, from: raw), keys.insert(key).inserted else {
                throw ProtocolError.malformedEvent
            }
            skipWhitespace()
            guard consume(0x3a) else { throw ProtocolError.malformedEvent }
            skipWhitespace()
            try parseValue()
            skipWhitespace()
            if consume(0x7d) { return }
            guard consume(0x2c) else { throw ProtocolError.malformedEvent }
            skipWhitespace()
        }
    }

    private mutating func parseArray() throws {
        index += 1
        skipWhitespace()
        if consume(0x5d) { return }
        while true {
            try parseValue()
            skipWhitespace()
            if consume(0x5d) { return }
            guard consume(0x2c) else { throw ProtocolError.malformedEvent }
            skipWhitespace()
        }
    }

    private mutating func parseString() throws -> Range<Int> {
        let start = index
        index += 1
        while let byte = current {
            if byte == 0x22 {
                index += 1
                return start..<index
            }
            if byte < 0x20 { throw ProtocolError.malformedEvent }
            if byte == 0x5c {
                index += 1
                guard let escaped = current else { throw ProtocolError.malformedEvent }
                if escaped == 0x75 {
                    index += 1
                    for _ in 0..<4 {
                        guard let hex = current, Self.isHex(hex) else { throw ProtocolError.malformedEvent }
                        index += 1
                    }
                    continue
                }
                guard [0x22, 0x5c, 0x2f, 0x62, 0x66, 0x6e, 0x72, 0x74].contains(escaped) else {
                    throw ProtocolError.malformedEvent
                }
            }
            index += 1
        }
        throw ProtocolError.malformedEvent
    }

    private mutating func parseNumber() throws {
        _ = consume(0x2d)
        guard let first = current else { throw ProtocolError.malformedEvent }
        if first == 0x30 { index += 1 }
        else if (0x31...0x39).contains(first) {
            index += 1
            while current.map({ (0x30...0x39).contains($0) }) == true { index += 1 }
        } else { throw ProtocolError.malformedEvent }
        if consume(0x2e) {
            guard current.map({ (0x30...0x39).contains($0) }) == true else { throw ProtocolError.malformedEvent }
            while current.map({ (0x30...0x39).contains($0) }) == true { index += 1 }
        }
        if current == 0x65 || current == 0x45 {
            index += 1
            if current == 0x2b || current == 0x2d { index += 1 }
            guard current.map({ (0x30...0x39).contains($0) }) == true else { throw ProtocolError.malformedEvent }
            while current.map({ (0x30...0x39).contains($0) }) == true { index += 1 }
        }
    }

    private mutating func consumeLiteral(_ literal: [UInt8]) throws {
        guard index + literal.count <= bytes.count,
              Array(bytes[index..<(index + literal.count)]) == literal else { throw ProtocolError.malformedEvent }
        index += literal.count
    }

    private mutating func skipWhitespace() {
        while let byte = current, [0x20, 0x09, 0x0a, 0x0d].contains(byte) { index += 1 }
    }

    private mutating func consume(_ byte: UInt8) -> Bool {
        guard current == byte else { return false }
        index += 1
        return true
    }

    private var current: UInt8? { index < bytes.count ? bytes[index] : nil }
    private static func isHex(_ byte: UInt8) -> Bool {
        (0x30...0x39).contains(byte) || (0x41...0x46).contains(byte) || (0x61...0x66).contains(byte)
    }
}
