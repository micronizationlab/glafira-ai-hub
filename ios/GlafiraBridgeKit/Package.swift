// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "GlafiraBridgeKit",
    platforms: [
        .iOS(.v16),
        .macOS(.v13)
    ],
    products: [
        .library(name: "GlafiraBridgeKit", targets: ["GlafiraBridgeKit"])
    ],
    targets: [
        .target(name: "GlafiraBridgeKit"),
        .testTarget(name: "GlafiraBridgeKitTests", dependencies: ["GlafiraBridgeKit"])
    ]
)
