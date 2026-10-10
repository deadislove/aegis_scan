// Copyright 2026 AegisScan Authors. All rights reserved.

import AVFoundation
import Foundation
import ImageIO

/// Errors surfaced by `CameraScannerPipeline`. None of them carry PAN data.
public enum ScannerError: Error, Equatable, Sendable {
    /// The user denied camera access (or never answered the prompt).
    case cameraPermissionDenied
    /// Camera access is blocked by parental controls / MDM.
    case cameraRestricted
    /// No back wide-angle camera on this device (e.g. simulator).
    case cameraUnavailable
    /// The session couldn't use the 1920x1080 preset or add input/output.
    case configurationFailed(String)
    /// AVCaptureSession reported a runtime error.
    case sessionRuntimeError(String)
    /// A frame was rejected before inference (diagnostics, never fatal).
    case frameRejected(FrameRejectionReason)
}

/// Why a frame was skipped before Vision ran (mirrors core `FrameStatus`).
public enum FrameRejectionReason: Int32, Sendable {
    case nullBuffer = 1
    case invalidDimensions = 2
    case invalidStride = 3
    case bufferTooSmall = 4
    case unsupportedAspectRatio = 5
    case tooDark = 6
    case tooBright = 7
    case blurry = 8
    case unsupportedPixelFormat = 100
    case lockFailed = 101
}

/// Tuning knobs. The defaults target 30 FPS at < 15% CPU on A13+ devices.
public struct ScannerConfiguration: Sendable {
    /// Scan for payment cards (OCR + Luhn).
    public var detectsCards = true
    /// Scan for voucher barcodes (+ spatial text association).
    public var detectsVouchers = true
    /// Orientation of the buffer relative to the upright image. `.right` is
    /// correct for the back camera in portrait.
    public var orientation: CGImagePropertyOrientation = .right
    /// The same PAN must appear in this many consecutive analyzed frames
    /// before it's reported (suppresses one-off OCR hallucinations).
    public var requiredConsecutiveCardHits = 2
    /// Region of interest in normalized Vision coordinates (bottom-left origin).
    public var regionOfInterest = CGRect(x: 0, y: 0, width: 1, height: 1)
    /// Minimum text height relative to the image (Vision speed knob).
    public var minimumTextHeight: Float = 0.03
    /// Barcode symbologies to detect (empty = Vision default set).
    public var symbologies: [VNBarcodeSymbologyWrapper] = VNBarcodeSymbologyWrapper.voucherDefaults
    /// Reports frames rejected by the defensive frame checks to the delegate.
    public var reportsFrameRejections = false

    public init() {}
}

/// `Sendable` wrapper so configuration stays value-typed across queues.
public struct VNBarcodeSymbologyWrapper: Sendable, Hashable {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }

    public static let voucherDefaults: [VNBarcodeSymbologyWrapper] = [
        "VNBarcodeSymbologyQR", "VNBarcodeSymbologyCode128",
        "VNBarcodeSymbologyEAN13", "VNBarcodeSymbologyPDF417",
        "VNBarcodeSymbologyDataMatrix", "VNBarcodeSymbologyITF14",
    ].map(VNBarcodeSymbologyWrapper.init(rawValue:))
}

/// Receives scanner results. Every method is called on the **main queue**.
public protocol CameraScannerPipelineDelegate: AnyObject {
    /// A validated card number was detected.
    ///
    /// The `SecurePAN` is wiped as soon as this method returns. Tokenize or
    /// encrypt it synchronously inside this call; don't store it, log it,
    /// or write it to `UserDefaults` or disk.
    func scanner(_ scanner: CameraScannerPipeline, didDetectCard pan: SecurePAN)

    /// One or more voucher barcodes were associated with printed text.
    func scanner(_ scanner: CameraScannerPipeline, didMatchVouchers matches: [VoucherMatch])

    /// A recoverable or fatal error occurred.
    func scanner(_ scanner: CameraScannerPipeline, didFailWith error: ScannerError)
}

public extension CameraScannerPipelineDelegate {
    func scanner(_ scanner: CameraScannerPipeline, didMatchVouchers matches: [VoucherMatch]) {}
    func scanner(_ scanner: CameraScannerPipeline, didFailWith error: ScannerError) {}
}

/// Pipeline statistics (thread-safe snapshot).
public struct ScannerStatistics: Sendable, Equatable {
    public let framesReceived: UInt64
    public let framesDropped: UInt64
    public let framesAnalyzed: UInt64
    public let framesRejected: UInt64
}
