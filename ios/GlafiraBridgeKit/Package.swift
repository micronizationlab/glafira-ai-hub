// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "GlafiraBridgeKit",
    platforms: [
        .iOS(.v16),
        .macOS(.v13)
    ],
    products: [
        .library(name: "GlafiraBridgeKit", targets: ["GlafiraBridgeKit"]),
        .library(name: "GlafiraIOSUI", targets: ["GlafiraIOSUI"])
    ],
    targets: [
        .target(name: "GlafiraBridgeKit"),
        .target(name: "GlafiraIOSUI", dependencies: ["GlafiraBridgeKit"]),
        .testTarget(name: "GlafiraBridgeKitTests", dependencies: ["GlafiraBridgeKit", "GlafiraIOSUI"])
    ]
)
