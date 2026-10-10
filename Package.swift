// swift-tools-version:5.9
// AegisScan SDK -- Swift Package (iOS 15+, macOS 12+ for host-side tests).
//
// Targets
//   CAegisCore : C++20 core (fintech_core.hpp) exposed through the C ABI only.
//   AegisScan  : Swift layer (CameraScannerPipeline, SecurePAN, bridges).
import PackageDescription

let package = Package(
    name: "AegisScan",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "AegisScan", targets: ["AegisScan"]),
    ],
    targets: [
        .target(
            name: "CAegisCore",
            path: "core",
            exclude: ["Makefile", "tests", "bindings", "build"],
            sources: ["src/fintech_core_c.cpp"],
            publicHeadersPath: "include",
            cxxSettings: [
                .define("AEGIS_CORE_BUILD"),
                .unsafeFlags(["-fvisibility=hidden"]),
            ]
        ),
        .target(
            name: "AegisScan",
            dependencies: ["CAegisCore"],
            path: "ios/Sources/AegisScan"
        ),
        .testTarget(
            name: "AegisScanTests",
            dependencies: ["AegisScan"],
            path: "ios/Tests/AegisScanTests"
        ),
    ],
    cxxLanguageStandard: .cxx20
)
