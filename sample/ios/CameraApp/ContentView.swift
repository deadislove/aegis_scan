// SwiftUI wrapper around CameraScannerPipeline (AegisScan). See
// ../README.md for how to drop this into a real Xcode App project --
// AVFoundation camera capture needs a real device/simulator and an
// Info.plist camera-usage string, neither of which a plain SwiftPM
// executable can provide.
import AegisScan
import AVFoundation
import SwiftUI

/// Bridges `CameraScannerPipeline`'s delegate callbacks (main queue) to
/// SwiftUI state. One instance per screen; `pipeline` owns the capture
/// session that `CameraPreviewView` attaches a preview layer to.
@MainActor
final class ScannerViewModel: NSObject, ObservableObject, CameraScannerPipelineDelegate {
    let pipeline = CameraScannerPipeline()

    @Published var statusText = "Point the camera at a card or voucher"

    override init() {
        super.init()
        pipeline.delegate = self
    }

    func start() { pipeline.start() }
    func stop() { pipeline.stop() }

    nonisolated func scanner(_ scanner: CameraScannerPipeline, didDetectCard pan: SecurePAN) {
        // Called on the main queue; `pan` is wiped as soon as this method
        // returns. A real integration would hand the digits to its
        // tokenizer/HSM call synchronously, right here, instead of just
        // reading brand/last4/expiry/cardholderName for display.
        var text = "Card: \(pan.brand) ****\(pan.last4)"
        if let expiry = pan.expiry { text += " exp \(expiry.month)/\(expiry.year)" }
        if let name = pan.cardholderName { text += " (\(name))" }
        Task { @MainActor in self.statusText = text }
    }

    nonisolated func scanner(_ scanner: CameraScannerPipeline, didMatchVouchers matches: [VoucherMatch]) {
        guard let first = matches.first else { return }
        let text = "Voucher: \(first.barcode.payload) -> \(first.associatedText)"
        Task { @MainActor in self.statusText = text }
    }

    nonisolated func scanner(_ scanner: CameraScannerPipeline, didFailWith error: ScannerError) {
        let text = "Error: \(error)"
        Task { @MainActor in self.statusText = text }
    }
}

struct ContentView: View {
    @StateObject private var viewModel = ScannerViewModel()

    var body: some View {
        ZStack(alignment: .bottom) {
            CameraPreviewView(session: viewModel.pipeline.session)
                .ignoresSafeArea()

            Text(viewModel.statusText)
                .font(.callout)
                .foregroundStyle(.white)
                .padding()
                .frame(maxWidth: .infinity)
                .background(.black.opacity(0.6))
        }
        .onAppear { viewModel.start() }
        .onDisappear { viewModel.stop() }
    }
}

/// Hosts an `AVCaptureVideoPreviewLayer` for the pipeline's `AVCaptureSession`.
struct CameraPreviewView: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        view.videoPreviewLayer.session = session
        view.videoPreviewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewUIView, context: Context) {}

    final class PreviewUIView: UIView {
        var videoPreviewLayer: AVCaptureVideoPreviewLayer {
            layer as! AVCaptureVideoPreviewLayer
        }
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    }
}

#Preview {
    ContentView()
}
