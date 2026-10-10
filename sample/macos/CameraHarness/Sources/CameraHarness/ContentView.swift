import AppKit
import SwiftUI

@MainActor
final class HarnessModel: ObservableObject {
    let engine = CaptureEngine()
    @Published var statusText = "Press Start and point the camera at a card or voucher"
    @Published var rawOcrText = ""
    @Published var isRunning = false
    @Published var copyConfirmation = ""

    init() {
        engine.onStatus = { [weak self] text in self?.statusText = text }
        engine.onRawText = { [weak self] text in self?.rawOcrText = text }
    }

    func toggle() {
        isRunning ? stop() : start()
    }

    func start() {
        engine.start()
        isRunning = true
    }

    func stop() {
        engine.stop()
        isRunning = false
    }

    /// Copies the raw (pre-repair) recognized text so it can be pasted into
    /// the .NET/Java console samples -- see spec/sample/dotnet.md and
    /// spec/sample/java.md for the `-- "$(pbpaste)"` usage.
    func copyRawText() {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(rawOcrText, forType: .string)
        copyConfirmation = "Copied!"
        Task {
            try? await Task.sleep(for: .seconds(1.5))
            copyConfirmation = ""
        }
    }
}

struct ContentView: View {
    @StateObject private var model = HarnessModel()

    var body: some View {
        VStack(spacing: 0) {
            CameraPreview(engine: model.engine)
                .frame(minWidth: 480, minHeight: 360)
                .background(Color.black)

            VStack(alignment: .leading, spacing: 8) {
                Text(model.statusText)
                    .font(.headline)

                Text("Recognized text (raw, before repair):")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                ScrollView {
                    Text(model.rawOcrText.isEmpty ? "(none yet)" : model.rawOcrText)
                        .font(.system(.body, design: .monospaced))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(height: 70)
                .background(Color(nsColor: .textBackgroundColor))

                HStack {
                    Button(model.isRunning ? "Stop" : "Start") { model.toggle() }
                    Button("Copy recognized text") { model.copyRawText() }
                        .disabled(model.rawOcrText.isEmpty)
                    Text(model.copyConfirmation)
                        .foregroundStyle(.green)
                }
            }
            .padding()
        }
        .frame(minWidth: 480, minHeight: 520)
        .onDisappear { model.stop() }
    }
}

/// Hosts the engine's `AVCaptureVideoPreviewLayer` in an `NSView`.
struct CameraPreview: NSViewRepresentable {
    let engine: CaptureEngine

    func makeNSView(context: Context) -> PreviewNSView {
        let view = PreviewNSView()
        view.wantsLayer = true
        let previewLayer = engine.makePreviewLayer()
        previewLayer.frame = view.bounds
        view.layer = previewLayer
        view.previewLayer = previewLayer
        return view
    }

    func updateNSView(_ nsView: PreviewNSView, context: Context) {}

    final class PreviewNSView: NSView {
        var previewLayer: CALayer?
        override func layout() {
            super.layout()
            previewLayer?.frame = bounds
        }
    }
}
