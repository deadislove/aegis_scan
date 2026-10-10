// swift-tools-version:5.9
// Runnable core-logic demo for AegisScan: `swift run` on macOS exercises the
// same Luhn/repair/spatial-matching API the iOS camera pipeline calls --
// see ../CameraApp for the UIKit/Vision-facing half, which needs a real
// iOS app target (Xcode) instead of a plain SwiftPM executable.
import PackageDescription

let package = Package(
    name: "AegisScanCoreDemo",
    platforms: [.macOS(.v12)],
    dependencies: [
        .package(path: "../../..")
    ],
    targets: [
        .executableTarget(
            name: "CoreDemo",
            dependencies: [.product(name: "AegisScan", package: "aegis_scan")]
        )
    ]
)
