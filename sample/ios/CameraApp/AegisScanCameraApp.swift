// Entry point for a real iOS app target. This file (and ContentView.swift)
// is meant to be copied into an Xcode "App" project, not built standalone --
// see ../README.md for the exact steps. For a build you CAN run right now
// without Xcode, see ../CoreDemo (the same core API, driven from a plain
// command-line executable).
import SwiftUI

@main
struct AegisScanCameraApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
