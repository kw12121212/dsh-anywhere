// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "DshAnywhereApp",
    platforms: [
        .macOS(.v14),
    ],
    dependencies: [
        .package(path: "../Core"),
    ],
    targets: [
        .executableTarget(
            name: "DshAnywhereApp",
            dependencies: [
                .product(name: "DshCore", package: "Core"),
            ]
        ),
    ]
)
