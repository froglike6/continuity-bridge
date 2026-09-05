// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "ContinuityBridgeMac",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "ContinuityCore", targets: ["ContinuityCore"]),
        .executable(name: "ContinuityMenuBar", targets: ["ContinuityMenuBar"]),
    ],
    targets: [
        .target(name: "ContinuityCore"),
        .executableTarget(name: "ContinuityMenuBar", dependencies: ["ContinuityCore"]),
        .testTarget(name: "ContinuityCoreTests", dependencies: ["ContinuityCore"],
                    resources: [.copy("Fixtures")]),
    ]
)
