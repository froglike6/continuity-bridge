import Foundation

public enum LaunchIntent: Sendable, Equatable {
    case stopped
    case bootSmoke
    case start

    public static func hasSavedConfiguration(endpoint: String?, pin: String?) -> Bool {
        guard let endpoint, let pin, let url = URLComponents(string: endpoint),
              url.scheme == "https", url.host?.isEmpty == false,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil else { return false }
        return pin.isEmpty || (pin.count == 64 && pin.allSatisfy { $0.isASCII && $0.isHexDigit })
    }

    public static func resolve(arguments: [String], hasSavedConfiguration: Bool = true) -> LaunchIntent {
        let launchArguments = Array(arguments.dropFirst())
        if launchArguments.contains("--boot-smoke") { return .bootSmoke }
        return launchArguments == ["--start"] && hasSavedConfiguration ? .start : .stopped
    }
}
