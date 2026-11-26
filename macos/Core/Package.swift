// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Core",
    platforms: [
        .macOS(.v14),
    ],
    products: [
        .library(
            name: "DshCore",
            targets: ["DshCore"]
        ),
    ],
    targets: [
        .target(
            name: "DshCore"
        ),
        .testTarget(
            name: "DshCoreTests",
            dependencies: ["DshCore"]
        ),
    ]
)
