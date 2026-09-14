import AppKit
import Foundation

public enum PasteboardError: Error, Equatable, LocalizedError, Sendable {
    case invalidEvent, writeFailed, verificationFailed
    case imageTooLarge, unsupportedImageFormat, invalidImage, imageDimensionsTooLarge

    public var errorDescription: String? {
        switch self {
        case .imageTooLarge: "이미지는 8 MiB 이하만 공유할 수 있어요"
        case .unsupportedImageFormat: "PNG·JPEG 이미지만 공유할 수 있어요"
        case .invalidImage: "이미지 파일을 읽거나 확인하지 못했어요"
        case .imageDimensionsTooLarge: "이미지 해상도가 너무 커요"
        case .invalidEvent: "클립보드 데이터 형식을 확인하지 못했어요"
        case .writeFailed: "클립보드에 저장하지 못했어요"
        case .verificationFailed: "클립보드 적용을 확인하지 못했어요"
        }
    }
}

@MainActor
public protocol PasteboardSurface {
    var changeCount: Int { get }
    var types: [NSPasteboard.PasteboardType] { get }
    func string(for type: NSPasteboard.PasteboardType) -> String?
    func data(for type: NSPasteboard.PasteboardType) -> Data?
    func replace(text: String, eventId: String) -> Int?
    func replace(image: ImagePayload, data: Data, eventId: String) -> Int?
}

public extension PasteboardSurface {
    var types: [NSPasteboard.PasteboardType] { [] }
    func data(for type: NSPasteboard.PasteboardType) -> Data? { nil }
    func replace(image: ImagePayload, data: Data, eventId: String) -> Int? { nil }
}

@MainActor
public final class NSPasteboardSurface: PasteboardSurface {
    private let pasteboard: NSPasteboard
    public init(_ pasteboard: NSPasteboard) { self.pasteboard = pasteboard }
    public var changeCount: Int { pasteboard.changeCount }
    public var types: [NSPasteboard.PasteboardType] {
        pasteboard.pasteboardItems?.first?.types ?? pasteboard.types ?? []
    }
    public func string(for type: NSPasteboard.PasteboardType) -> String? { pasteboard.string(forType: type) }
    public func data(for type: NSPasteboard.PasteboardType) -> Data? { pasteboard.data(forType: type) }

    public func replace(text: String, eventId: String) -> Int? {
        pasteboard.declareTypes([.string, PasteboardSynchronizer.eventIDType], owner: nil)
        guard pasteboard.setString(text, forType: .string),
              pasteboard.setString(eventId, forType: PasteboardSynchronizer.eventIDType) else { return nil }
        return pasteboard.changeCount
    }

    public func replace(image: ImagePayload, data: Data, eventId: String) -> Int? {
        let item = NSPasteboardItem()
        guard item.setData(data, forType: Self.imageType(image.mimeType)),
              item.setString(eventId, forType: PasteboardSynchronizer.eventIDType) else { return nil }
        pasteboard.clearContents()
        guard pasteboard.writeObjects([item]) else { return nil }
        return pasteboard.changeCount
    }

    static func imageType(_ mimeType: String) -> NSPasteboard.PasteboardType {
        mimeType == "image/png" ? .png : .init("public.jpeg")
    }
}
