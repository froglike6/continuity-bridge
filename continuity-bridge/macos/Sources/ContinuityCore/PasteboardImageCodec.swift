import Foundation
import ImageIO
import UniformTypeIdentifiers

enum PasteboardImageCodec {
    static let maximumDimension = 8_192
    static let maximumPixels = 16_777_216

    static func capture(_ data: Data, mimeType: String) throws -> ImagePayload {
        guard data.count <= WireLimits.imageDecodedBytes else { throw PasteboardError.imageTooLarge }
        if mimeType == "image/tiff" {
            let source = try source(data, expectedType: UTType.tiff.identifier)
            guard let image = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCache: false] as CFDictionary),
                  let output = CFDataCreateMutable(nil, 0),
                  let destination = CGImageDestinationCreateWithData(output, UTType.png.identifier as CFString, 1, nil)
            else { throw PasteboardError.invalidImage }
            CGImageDestinationAddImage(destination, image, nil)
            guard CGImageDestinationFinalize(destination) else { throw PasteboardError.invalidImage }
            return try capture(output as Data, mimeType: "image/png")
        }
        let payload = ImagePayload(mimeType: mimeType, dataBase64: data.base64EncodedString())
        _ = try validate(payload)
        return payload
    }

    static func validate(_ payload: ImagePayload) throws -> Data {
        let data: Data
        do { data = try payload.validatedData() }
        catch ProtocolError.payloadTooLarge { throw PasteboardError.imageTooLarge }
        catch { throw PasteboardError.invalidImage }
        let type: String
        switch payload.mimeType {
        case "image/png": type = UTType.png.identifier
        case "image/jpeg": type = UTType.jpeg.identifier
        default: throw PasteboardError.unsupportedImageFormat
        }
        let source = try source(data, expectedType: type)
        let options = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                       kCGImageSourceThumbnailMaxPixelSize: 1_024,
                       kCGImageSourceShouldCacheImmediately: true] as CFDictionary
        guard CGImageSourceCreateThumbnailAtIndex(source, 0, options) != nil else { throw PasteboardError.invalidImage }
        return data
    }

    private static func source(_ data: Data, expectedType: String) throws -> CGImageSource {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetStatus(source) == .statusComplete,
              let type = CGImageSourceGetType(source), type as String == expectedType,
              CGImageSourceGetCount(source) == 1,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0 else { throw PasteboardError.invalidImage }
        guard width <= maximumDimension, height <= maximumDimension,
              width <= maximumPixels / height else { throw PasteboardError.imageDimensionsTooLarge }
        return source
    }
}
