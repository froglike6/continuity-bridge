import XCTest
@testable import ContinuityCore

final class LaunchIntentTests: XCTestCase {
    func testSavedPublicEndpoint_withEmptyPinAllowsSystemTrustLaunch() {
        XCTAssertTrue(LaunchIntent.hasSavedConfiguration(endpoint: "https://bridge.example.com", pin: ""))
        XCTAssertTrue(LaunchIntent.hasSavedConfiguration(endpoint: "https://localhost:8443", pin: String(repeating: "a", count: 64)))
        XCTAssertFalse(LaunchIntent.hasSavedConfiguration(endpoint: nil, pin: ""))
        XCTAssertFalse(LaunchIntent.hasSavedConfiguration(endpoint: "https://bridge.example.com", pin: nil))
        XCTAssertFalse(LaunchIntent.hasSavedConfiguration(endpoint: "http://bridge.example.com", pin: ""))
        XCTAssertFalse(LaunchIntent.hasSavedConfiguration(endpoint: "https://bridge.example.com", pin: "invalid"))
    }

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
