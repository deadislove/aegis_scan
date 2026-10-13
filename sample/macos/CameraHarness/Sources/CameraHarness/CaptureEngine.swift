// AegisScan Camera Harness -- capture engine.
//
// Deliberately NOT CameraScannerPipeline: that class only ever requests a
// `position: .back` camera (correct for an iPhone, non-existent on a Mac),
// so there's no device for it to open here. This is new, Mac-only
// AVFoundation + Vision glue, written to call the identical
// FintechCore/VoucherMatcher Swift API a real iPhone build exercises --
// see docs/technical/02-platform-ios.md for what CameraScannerPipeline
// itself does on frames it successfully opens.
import AegisScan
import AVFoundation
import Vision

final class CaptureEngine: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    private let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "com.aegisscan.harness.capture")
    private var isProcessing = false  // touched only on `queue`, which is serial
    private var matcher: VoucherMatcher?

    var onStatus: ((String) -> Void)?
    var onRawText: ((String) -> Void)?

    func makePreviewLayer() -> AVCaptureVideoPreviewLayer {
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        return layer
    }

    func start() {
        queue.async { [weak self] in self?.configureAndStart() }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self, self.session.isRunning else { return }
            self.session.stopRunning()
        }
    }

    private func configureAndStart() {
        if session.inputs.isEmpty {
            session.beginConfiguration()
            defer { session.commitConfiguration() }
            guard let camera = AVCaptureDevice.default(for: .video) else {
                report(status: "No camera found.")
                return
            }
            guard let input = try? AVCaptureDeviceInput(device: camera), session.canAddInput(input) else {
                report(status: "Could not open the camera (is it in use by another app?).")
                return
            }
            session.addInput(input)
            output.alwaysDiscardsLateVideoFrames = true
            output.setSampleBufferDelegate(self, queue: queue)
            guard session.canAddOutput(output) else {
                report(status: "Could not attach the video output.")
                return
            }
            session.addOutput(output)
        }
        session.startRunning()
        report(status: "Point the camera at a card or voucher...")
    }

    // Runs on `queue` (serial), so `isProcessing` needs no lock.
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                        from connection: AVCaptureConnection) {
        guard !isProcessing, let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        isProcessing = true
        analyze(pixelBuffer)
    }

    /// `.accurate`, not the SDK's `.fast` -- this harness is for a held-still
    /// test print, not 30fps live scanning, so it favors correctness.
    private func analyze(_ pixelBuffer: CVPixelBuffer) {
        defer { isProcessing = false }
        let imageSize = CGSize(width: CVPixelBufferGetWidth(pixelBuffer), height: CVPixelBufferGetHeight(pixelBuffer))

        let textRequest = VNRecognizeTextRequest()
        textRequest.recognitionLevel = .accurate
        textRequest.usesLanguageCorrection = false
        let barcodeRequest = VNDetectBarcodesRequest()
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: .up, options: [:])
        do {
            try handler.perform([textRequest, barcodeRequest])
        } catch {
            return
        }

        let lines: [DetectedText] = (textRequest.results ?? []).compactMap { observation in
            guard let candidate = observation.topCandidates(1).first else { return nil }
            return DetectedText(text: candidate.string,
                                boundingBox: Self.imagePixelRect(observation.boundingBox, imageSize))
        }
        let joined = lines.map(\.text).joined(separator: "\n")
        onRawText?(joined)

        if let pan = FintechCore.extractPAN(from: joined) {
            var status = "Card: \(pan.brand) ****\(pan.last4)"
            if let expiry = FintechCore.extractExpiry(from: joined) {
                status += " exp \(expiry.month)/\(expiry.year)"
            }
            if let name = FintechCore.extractCardholderName(from: joined) {
                status += " (\(name))"
            }
            report(status: status)
            pan.wipe()
            return
        }

        let barcodes: [DetectedBarcode] = (barcodeRequest.results ?? []).compactMap { observation in
            guard let payload = observation.payloadStringValue, !payload.isEmpty else { return nil }
            return DetectedBarcode(payload: payload, symbology: observation.symbology.rawValue,
                                   boundingBox: Self.imagePixelRect(observation.boundingBox, imageSize))
        }
        if !barcodes.isEmpty, !lines.isEmpty {
            if matcher == nil { matcher = VoucherMatcher(frameSize: imageSize) }
            if let first = matcher?.match(barcodes: barcodes, texts: lines).first {
                report(status: "Voucher: \(first.barcode.payload) -> \(first.associatedText)")
                return
            }
        }
        report(status: joined.isEmpty ? "No text recognized yet..." : "Reading text, no valid card/voucher yet...")
    }

    private func report(status: String) {
        DispatchQueue.main.async { self.onStatus?(status) }
    }

    /// Vision's normalized rect (origin bottom-left) -> image pixels (origin
    /// top-left). Same transform `CameraScannerPipeline.imageRect` applies;
    /// reimplemented here since that one is internal to the AegisScan module.
    private static func imagePixelRect(_ normalized: CGRect, _ size: CGSize) -> CGRect {
        CGRect(x: normalized.minX * size.width,
               y: (1 - normalized.maxY) * size.height,
               width: normalized.width * size.width,
               height: normalized.height * size.height)
    }
}
