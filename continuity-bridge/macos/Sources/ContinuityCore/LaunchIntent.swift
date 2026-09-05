import Foundation

public enum LaunchIntent: Sendable, Equatable {
    case stopped
    case bootSmoke
    case start

    public static func resolve(arguments: [String], hasSavedConfiguration: Bool = true) -> LaunchIntent {
        let launchArguments = Array(arguments.dropFirst())
        if launchArguments.contains("--boot-smoke") { return .bootSmoke }
        return launchArguments == ["--start"] && hasSavedConfiguration ? .start : .stopped
    }
}
