import XCTest
@testable import ContinuityCore

final class LaunchIntentTests: XCTestCase {
    func testLaunchIntent_whenNormalOrUnknownArguments_staysStopped() {
        XCTAssertEqual(LaunchIntent.resolve(arguments: ["ContinuityBridge"]), .stopped)
        XCTAssertEqual(LaunchIntent.resolve(arguments: ["ContinuityBridge", "--unknown"]), .stopped)
        XCTAssertEqual(LaunchIntent.resolve(arguments: ["ContinuityBridge", "--start", "--unknown"]), .stopped)
    }

    func testLaunchIntent_whenOnlyStartArgument_selectsStart() {
        XCTAssertEqual(LaunchIntent.resolve(arguments: ["ContinuityBridge", "--start"]), .start)
    }

    func testLaunchIntent_whenSavedEndpointOrPinIsMissing_staysStopped() {
        XCTAssertEqual(LaunchIntent.resolve(arguments: ["ContinuityBridge", "--start"], hasSavedConfiguration: false), .stopped)
    }

    func testLaunchIntent_whenBootSmokeIsPresent_preventsStart() {
        XCTAssertEqual(LaunchIntent.resolve(arguments: ["ContinuityBridge", "--boot-smoke"]), .bootSmoke)
        XCTAssertEqual(LaunchIntent.resolve(arguments: ["ContinuityBridge", "--boot-smoke", "--start"]), .bootSmoke)
    }
}
