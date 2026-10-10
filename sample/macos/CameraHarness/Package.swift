// swift-tools-version:5.9
// Mac-native camera test harness: opens the Mac's built-in camera, runs
// Apple Vision text + barcode detection on each frame, and calls the exact
// same AegisScan Swift API (`FintechCore`, `VoucherMatcher`) that
// `CameraScannerPipeline` calls on a real iPhone. This is a standalone
// capture loop, not `CameraScannerPipeline` itself -- that class only ever
// opens a `position: .back` camera, which doesn't exist on a Mac. See
// ../../ios/README.md for why, and this package's own README.md for how
// to run it.
import PackageDescription

let package = Package(
    name: "AegisScanCameraHarness",
    platforms: [.macOS(.v13)],
    dependencies: [
        .package(path: "../../..")
    ],
    targets: [
        .executableTarget(
            name: "CameraHarness",
            dependencies: [.product(name: "AegisScan", package: "aegis_scan")],
            linkerSettings: [
                // Embeds Info.plist into the binary's __TEXT segment so
                // macOS's privacy (TCC) system recognizes
                // NSCameraUsageDescription even though this isn't a proper
                // .app bundle. Without this, calling an AVFoundation camera
                // API from a bare SwiftPM executable crashes the process
                // instead of prompting for permission.
                .unsafeFlags([
                    "-Xlinker", "-sectcreate",
                    "-Xlinker", "__TEXT",
                    "-Xlinker", "__info_plist",
                    "-Xlinker", "Info.plist",
                ]),
            ]
        )
    ]
)
