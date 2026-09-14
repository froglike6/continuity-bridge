import AppKit
import SwiftUI

enum BridgeTheme {
    static let contentInset: CGFloat = 24
    static let surfaceRadius: CGFloat = 16
    static let sidebarWidth: CGFloat = 184
    static let bannerWidth: CGFloat = 380
    static let bannerInset: CGFloat = 16
}

enum WorkspacePage: String, CaseIterable, Identifiable {
    case overview = "개요"
    case notifications = "알림"
    case applications = "앱별 수신"
    case connection = "연결 설정"

    var id: String { rawValue }
    var symbol: String {
        switch self {
        case .overview: "rectangle.grid.1x2"
        case .notifications: "bell"
        case .applications: "app.badge"
        case .connection: "network"
        }
    }
}

@MainActor
final class WorkspaceNavigation: ObservableObject {
    @Published var page: WorkspacePage = .overview
}

struct InsetSurface<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 4) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(Color(nsColor: .controlBackgroundColor),
                        in: RoundedRectangle(cornerRadius: BridgeTheme.surfaceRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: BridgeTheme.surfaceRadius, style: .continuous)
                    .stroke(Color(nsColor: .separatorColor).opacity(0.35), lineWidth: 0.5)
            }
    }
}

@MainActor
private enum SourceIconCache {
    static let images: NSCache<NSString, NSImage> = {
        let cache = NSCache<NSString, NSImage>()
        cache.countLimit = 256
        cache.totalCostLimit = 8 * 1_024 * 1_024
        return cache
    }()

    static func image(_ encoded: String?) -> NSImage? {
        guard let encoded, encoded.utf8.count <= 16_384 else { return nil }
        if let image = images.object(forKey: encoded as NSString) { return image }
        guard let data = Data(base64Encoded: encoded), let image = NSImage(data: data),
              image.size.width > 0, image.size.height > 0,
              image.size.width <= 128, image.size.height <= 128 else { return nil }
        images.setObject(image, forKey: encoded as NSString, cost: data.count * 4)
        return image
    }
}

struct SourceAppIcon: View {
    let encoded: String?
    let name: String
    var size: CGFloat = 36

    var body: some View {
        Group {
            if let image = SourceIconCache.image(encoded) {
                Image(nsImage: image).resizable().interpolation(.high).scaledToFit()
            } else {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(Color(nsColor: .quaternaryLabelColor).opacity(0.35))
                    Text(name.isEmpty ? "앱" : String(name.prefix(1)))
                        .font(.system(size: size * 0.42, weight: .semibold))
                        .foregroundStyle(.secondary)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityHidden(true)
    }
}

struct BridgeIdentity: View {
    var size: CGFloat = 64

    var body: some View {
        Image(nsImage: NSApp.applicationIconImage)
            .resizable().interpolation(.high).scaledToFit()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

struct WorkspaceEmptyState: View {
    let symbol: String
    let title: String
    let detail: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: symbol).font(.system(size: 32, weight: .light))
                .foregroundStyle(.secondary).accessibilityHidden(true)
            Text(title).font(.title3.weight(.semibold))
            Text(detail).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).frame(maxWidth: 320)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
