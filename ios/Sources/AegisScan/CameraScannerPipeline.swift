// Copyright 2026 AegisScan Authors. All rights reserved.

import AVFoundation
import CAegisCore
import CoreVideo
import Foundation
import ImageIO
import Vision

/// Real-time camera pipeline that detects payment cards and voucher barcodes.
///
/// ## Threading model
/// | Queue | Kind | Work |
/// |---|---|---|
/// | `com.aegisscan.sdk.session` | serial | session configuration, start/stop |
/// | `com.aegisscan.sdk.processing` | serial, `.userInitiated` | sample-buffer delegate, frame gate, core logic (Luhn, matcher) |
/// | `com.aegisscan.sdk.inference` | concurrent, `.userInitiated` | `VNRecognizeTextRequest` and `VNDetectBarcodesRequest` in parallel |
/// | main | serial | delegate callbacks |
///
/// ## Back-pressure
/// `isProcessingFrame` is an atomic gate. A frame is analyzed only if the gate
/// can be taken without waiting; otherwise it's dropped immediately and its
/// `CVPixelBuffer` goes straight back to the capture pool. With
/// `alwaysDiscardsLateVideoFrames = true`, at most one frame is ever in
/// flight, so latency stays bounded at any inference cost.
///
/// ## PCI-DSS
/// - Pixel buffers and recognized text never leave RAM. Nothing is written to
///   disk, `UserDefaults`, caches, or logs, and the pipeline doesn't log PANs.
/// - Each pixel buffer reference is dropped as soon as both Vision requests
///   finish.
/// - PANs are delivered as `SecurePAN` and wiped right after the delegate
///   callback returns.
public final class CameraScannerPipeline: NSObject, @unchecked Sendable {

    // MARK: Public API

    /// Receives results on the main queue. Held weakly.
    public weak var delegate: CameraScannerPipelineDelegate?

    /// Optional closure alternative to the delegate (main queue; the PAN is
    /// wiped right after the closure returns).
    public var onCardDetected: ((SecurePAN) -> Void)?

    /// The capture session, for attaching an `AVCaptureVideoPreviewLayer`.
    public let session = AVCaptureSession()

    /// Active configuration. Changes take effect on the next analyzed frame.
    public var configuration: ScannerConfiguration {
        get { configLock.withLock { _configuration } }
        set { configLock.withLock { _configuration = newValue } }
    }

    /// Thread-safe pipeline statistics.
    public var statistics: ScannerStatistics {
        ScannerStatistics(framesReceived: framesReceived.current,
                          framesDropped: framesDropped.current,
                          framesAnalyzed: framesAnalyzed.current,
                          framesRejected: framesRejected.current)
    }

    // MARK: Queues & state

    private let sessionQueue = DispatchQueue(label: "com.aegisscan.sdk.session")
    /// Dedicated frame-processing queue (spec: `.userInitiated`).
    let processingQueue = DispatchQueue(label: "com.aegisscan.sdk.processing",
                                        qos: .userInitiated)
    private let inferenceQueue = DispatchQueue(label: "com.aegisscan.sdk.inference",
                                               qos: .userInitiated,
                                               attributes: .concurrent)
    private let videoOutput = AVCaptureVideoDataOutput()

    /// Atomic back-pressure gate (true while a frame is in flight).
    let isProcessingFrame = AtomicFlag()
    private let running = AtomicFlag()

    private let configLock = NSLock()
    private var _configuration: ScannerConfiguration

    private let framesReceived = AtomicCounter()
    private let framesDropped = AtomicCounter()
    private let framesAnalyzed = AtomicCounter()
    private let framesRejected = AtomicCounter()

    // Only touched on `processingQueue` (serial), so no locks are needed.
    private var matcher: VoucherMatcher?
    private var pendingCard: SecurePAN?
    private var pendingCardHits = 0
    private var isConfigured = false
    private var observers: [NSObjectProtocol] = []

    /// Creates a pipeline. Call `start()` to request permission and begin.
    public init(configuration: ScannerConfiguration = ScannerConfiguration()) {
        _configuration = configuration
        super.init()
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
        pendingCard?.wipe()
    }

    // MARK: Lifecycle

    /// Requests camera permission (if needed), configures the session, and
    /// starts capturing. Errors are reported through the delegate on main.
    public func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            sessionQueue.async { [weak self] in self?.configureAndRun() }
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard let self else { return }
                if granted {
                    self.sessionQueue.async { self.configureAndRun() }
                } else {
                    self.report(.cameraPermissionDenied)
                }
            }
        case .denied:
            report(.cameraPermissionDenied)
        case .restricted:
            report(.cameraRestricted)
        @unknown default:
            report(.cameraPermissionDenied)
        }
    }

    /// Stops capturing and wipes any pending (unconfirmed) card candidate.
    public func stop() {
        running.release()
        sessionQueue.async { [weak self] in
            guard let self else { return }
            if self.session.isRunning { self.session.stopRunning() }
            self.processingQueue.async { self.resetCardCandidate() }
        }
    }

    // MARK: Session configuration (sessionQueue)

    private func configureAndRun() {
        dispatchPrecondition(condition: .onQueue(sessionQueue))
        if !isConfigured {
            do {
                try configureSession()
                isConfigured = true
            } catch let error as ScannerError {
                report(error)
                return
            } catch {
                report(.configurationFailed("unexpected"))
                return
            }
        }
        _ = running.tryAcquire()
        if !session.isRunning { session.startRunning() }
    }

    private func configureSession() throws {
        session.beginConfiguration()
        defer { session.commitConfiguration() }

        guard session.canSetSessionPreset(.hd1920x1080) else {
            throw ScannerError.configurationFailed("hd1920x1080 preset unsupported")
        }
        session.sessionPreset = .hd1920x1080

        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera,
                                                   for: .video, position: .back) else {
            throw ScannerError.cameraUnavailable
        }
        let input: AVCaptureDeviceInput
        do {
            input = try AVCaptureDeviceInput(device: camera)
        } catch {
            throw ScannerError.configurationFailed("camera input unavailable")
        }
        guard session.canAddInput(input) else {
            throw ScannerError.configurationFailed("cannot add camera input")
        }
        session.addInput(input)
        configureFocus(camera)

        // Bi-planar 4:2:0 full range is Vision's native format: no conversion.
        videoOutput.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String:
                kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
        ]
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(self, queue: processingQueue)
        guard session.canAddOutput(videoOutput) else {
            throw ScannerError.configurationFailed("cannot add video output")
        }
        session.addOutput(videoOutput)
        videoOutput.connection(with: .video)?.isEnabled = true

        observeSessionNotifications()
    }

    private func configureFocus(_ camera: AVCaptureDevice) {
        do {
            try camera.lockForConfiguration()
            defer { camera.unlockForConfiguration() }
            if camera.isFocusModeSupported(.continuousAutoFocus) {
                camera.focusMode = .continuousAutoFocus
            }
            #if os(iOS)
            if camera.isAutoFocusRangeRestrictionSupported {
                camera.autoFocusRangeRestriction = .near  // Cards are held close.
            }
            #endif
            if camera.isExposureModeSupported(.continuousAutoExposure) {
                camera.exposureMode = .continuousAutoExposure
            }
            // Cap at 30 FPS: enough for scanning, and it bounds CPU/thermal load.
            camera.activeVideoMinFrameDuration = CMTime(value: 1, timescale: 30)
        } catch {
            // Focus tuning is best-effort; scanning still works without it.
        }
    }

    private func observeSessionNotifications() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: .AVCaptureSessionRuntimeError,
                                            object: session, queue: nil) { [weak self] note in
            let code = (note.userInfo?[AVCaptureSessionErrorKey] as? AVError)?.code.rawValue ?? -1
            self?.report(.sessionRuntimeError("AVError \(code)"))
            // Media services reset: restart if we were running.
            self?.sessionQueue.async {
                guard let self, self.running.isSet, !self.session.isRunning else { return }
                self.session.startRunning()
            }
        })
    }

    // MARK: Frame validation (processingQueue)

    /// Defensive checks before any inference: pixel format, plane geometry,
    /// stride, buffer size, aspect ratio, and exposure, all via the C++ core
    /// on the luma plane (read-only lock, zero copy).
    /// - Returns: `nil` when the frame is usable, otherwise the reason.
    static func validate(_ pixelBuffer: CVPixelBuffer) -> FrameRejectionReason? {
        let format = CVPixelBufferGetPixelFormatType(pixelBuffer)
        guard format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
            || format == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        else { return .unsupportedPixelFormat }
        guard CVPixelBufferGetPlaneCount(pixelBuffer) >= 1 else { return .nullBuffer }

        guard CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly) == kCVReturnSuccess else {
            return .lockFailed
        }
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        let base = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0)
        let width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0)
        let height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0)
        let stride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
        guard width <= Int(Int32.max), height <= Int(Int32.max),
              stride <= Int(Int32.max) else { return .invalidDimensions }

        var metrics = aegis_frame_metrics()
        let status = aegis_analyze_luma(base?.assumingMemoryBound(to: UInt8.self),
                                        Int64(stride) * Int64(height),
                                        Int32(width), Int32(height), Int32(stride),
                                        &metrics)
        guard status == AEGIS_OK else { return .nullBuffer }
        return metrics.status == 0 ? nil : FrameRejectionReason(rawValue: metrics.status)
    }

    // MARK: Inference

    /// Holds the only strong pipeline reference to the frame's pixel buffer,
    /// so it goes back to the capture pool as soon as Vision finishes.
    private final class FrameBox: @unchecked Sendable {
        var pixelBuffer: CVPixelBuffer?
        var textObservations: [VNRecognizedTextObservation] = []
        var barcodeObservations: [VNBarcodeObservation] = []
        init(_ pixelBuffer: CVPixelBuffer) { self.pixelBuffer = pixelBuffer }
    }

    private func analyze(_ pixelBuffer: CVPixelBuffer, config: ScannerConfiguration) {
        dispatchPrecondition(condition: .onQueue(processingQueue))
        let box = FrameBox(pixelBuffer)
        let orientation = config.orientation
        let group = DispatchGroup()

        if config.detectsCards || config.detectsVouchers {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .fast
            request.usesLanguageCorrection = false  // Must never "fix" digits.
            request.minimumTextHeight = config.minimumTextHeight
            request.regionOfInterest = config.regionOfInterest
            perform(request, on: box, orientation: orientation, group: group) { box, results in
                box.textObservations = results as? [VNRecognizedTextObservation] ?? []
            }
        }
        if config.detectsVouchers {
            let request = VNDetectBarcodesRequest()
            request.regionOfInterest = config.regionOfInterest
            let wanted = config.symbologies.map { VNBarcodeSymbology(rawValue: $0.rawValue) }
            if !wanted.isEmpty { request.symbologies = wanted }
            perform(request, on: box, orientation: orientation, group: group) { box, results in
                box.barcodeObservations = results as? [VNBarcodeObservation] ?? []
            }
        }

        group.notify(queue: processingQueue) { [weak self] in
            // Both requests are done: release the pixel buffer immediately.
            let imageSize = Self.orientedSize(of: box.pixelBuffer, orientation: orientation)
            box.pixelBuffer = nil
            defer {
                box.textObservations.removeAll()
                box.barcodeObservations.removeAll()
                self?.isProcessingFrame.release()
            }
            guard let self, self.running.isSet else { return }
            self.framesAnalyzed.increment()
            self.handleResults(text: box.textObservations,
                               barcodes: box.barcodeObservations,
                               imageSize: imageSize, config: config)
        }
    }

    /// Runs one Vision request on the concurrent inference queue. Each request
    /// gets its own handler so text and barcode detection run in parallel on
    /// the same `CVPixelBuffer`.
    private func perform(_ request: VNRequest, on box: FrameBox,
                         orientation: CGImagePropertyOrientation, group: DispatchGroup,
                         store: @escaping (FrameBox, [VNObservation]) -> Void) {
        guard let pixelBuffer = box.pixelBuffer else { return }
        group.enter()
        inferenceQueue.async {
            autoreleasepool {
                let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer,
                                                    orientation: orientation, options: [:])
                do {
                    try handler.perform([request])
                    store(box, request.results ?? [])
                } catch {
                    store(box, [])  // A Vision failure means an empty frame, never fatal.
                }
            }
            group.leave()
        }
    }

    private static func orientedSize(of buffer: CVPixelBuffer?,
                                     orientation: CGImagePropertyOrientation) -> CGSize {
        guard let buffer else { return .zero }
        let w = CGFloat(CVPixelBufferGetWidth(buffer))
        let h = CGFloat(CVPixelBufferGetHeight(buffer))
        switch orientation {
        case .left, .leftMirrored, .right, .rightMirrored: return CGSize(width: h, height: w)
        default: return CGSize(width: w, height: h)
        }
    }

    // MARK: Core logic (processingQueue)

    private func handleResults(text: [VNRecognizedTextObservation],
                               barcodes: [VNBarcodeObservation],
                               imageSize: CGSize, config: ScannerConfiguration) {
        dispatchPrecondition(condition: .onQueue(processingQueue))
        let lines: [DetectedText] = text.compactMap { observation in
            guard let candidate = observation.topCandidates(1).first else { return nil }
            return DetectedText(text: candidate.string,
                                boundingBox: Self.imageRect(observation.boundingBox, imageSize))
        }

        if config.detectsCards { processCards(lines, config: config) }

        if config.detectsVouchers, !barcodes.isEmpty, !lines.isEmpty {
            let detected: [DetectedBarcode] = barcodes.compactMap { observation in
                guard let payload = observation.payloadStringValue, !payload.isEmpty else {
                    return nil
                }
                return DetectedBarcode(payload: payload,
                                       symbology: observation.symbology.rawValue,
                                       boundingBox: Self.imageRect(observation.boundingBox,
                                                                   imageSize))
            }
            if matcher == nil { matcher = VoucherMatcher(frameSize: imageSize) }
            if let matches = matcher?.match(barcodes: detected, texts: lines), !matches.isEmpty {
                deliverVouchers(matches)
            }
        }
    }

    /// Card numbers are usually printed on one line; joining lines with "\n"
    /// keeps the core's hard line break, so digits from separate lines are
    /// never glued together.
    func processCards(_ lines: [DetectedText], config: ScannerConfiguration) {
        var joined = lines.map(\.text).joined(separator: "\n")
        defer { joined.removeAll(keepingCapacity: false) }
        guard let pan = FintechCore.extractPAN(from: joined) else {
            resetCardCandidate()
            return
        }
        // Require the same PAN in N consecutive frames before reporting it.
        if let pending = pendingCard, Self.sameDigits(pending, pan) {
            pan.wipe()
            pendingCardHits += 1
        } else {
            pendingCard?.wipe()
            pendingCard = pan
            pendingCardHits = 1
        }
        guard pendingCardHits >= max(1, config.requiredConsecutiveCardHits),
              let confirmed = pendingCard else { return }
        pendingCard = nil
        pendingCardHits = 0
        deliverCard(confirmed)
    }

    private func resetCardCandidate() {
        pendingCard?.wipe()
        pendingCard = nil
        pendingCardHits = 0
    }

    private static func sameDigits(_ a: SecurePAN, _ b: SecurePAN) -> Bool {
        a.withDigits { da in b.withDigits { db in da.elementsEqual(db) } }
    }

    /// Vision normalized rect (bottom-left origin) → image pixels (top-left).
    static func imageRect(_ normalized: CGRect, _ size: CGSize) -> CGRect {
        CGRect(x: normalized.minX * size.width,
               y: (1 - normalized.maxY) * size.height,
               width: normalized.width * size.width,
               height: normalized.height * size.height)
    }

    // MARK: Delivery (main queue)

    private func deliverCard(_ pan: SecurePAN) {
        DispatchQueue.main.async { [weak self] in
            defer { pan.wipe() }  // Purged as soon as the callback returns.
            guard let self else { return }
            self.delegate?.scanner(self, didDetectCard: pan)
            self.onCardDetected?(pan)
        }
    }

    private func deliverVouchers(_ matches: [VoucherMatch]) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.delegate?.scanner(self, didMatchVouchers: matches)
        }
    }

    private func report(_ error: ScannerError) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.delegate?.scanner(self, didFailWith: error)
        }
    }

    // MARK: Frame entry point (processingQueue)

    /// Back-pressure gate + validation + inference. Shared by the capture
    /// callback and host-side tests.
    func process(pixelBuffer: CVPixelBuffer) {
        framesReceived.increment()
        guard isProcessingFrame.tryAcquire() else {
            framesDropped.increment()  // Busy: drop instantly, never queue.
            return
        }
        let config = configuration
        if let reason = Self.validate(pixelBuffer) {
            framesRejected.increment()
            isProcessingFrame.release()
            if config.reportsFrameRejections { report(.frameRejected(reason)) }
            return
        }
        analyze(pixelBuffer, config: config)
    }

    /// Marks the pipeline as running without starting the camera (tests).
    func markRunningForTesting() { _ = running.tryAcquire() }
}

// MARK: - AVCaptureVideoDataOutputSampleBufferDelegate

extension CameraScannerPipeline: AVCaptureVideoDataOutputSampleBufferDelegate {

    /// Called on `com.aegisscan.sdk.processing` for every captured frame.
    /// O(1) when busy: the atomic gate fails, the frame is dropped, and the
    /// sample buffer returns to the pool when this method exits.
    public func captureOutput(_ output: AVCaptureOutput,
                              didOutput sampleBuffer: CMSampleBuffer,
                              from connection: AVCaptureConnection) {
        guard running.isSet else { return }
        guard CMSampleBufferIsValid(sampleBuffer),
              CMSampleBufferDataIsReady(sampleBuffer),
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            framesReceived.increment()
            framesRejected.increment()
            return
        }
        process(pixelBuffer: pixelBuffer)
    }

    /// Frames AVFoundation discarded itself (late frames) count as drops.
    public func captureOutput(_ output: AVCaptureOutput,
                              didDrop sampleBuffer: CMSampleBuffer,
                              from connection: AVCaptureConnection) {
        framesReceived.increment()
        framesDropped.increment()
    }
}
