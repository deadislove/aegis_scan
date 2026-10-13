// Copyright 2026 AegisScan Authors. All rights reserved.
//
// React Native (classic bridge) native module wrapping AegisScan's
// CameraScannerPipeline. JS calls start()/stop(); card/voucher/error
// events are forwarded as RCTEventEmitter events, subscribed to from JS
// via NativeEventEmitter (see ../../src/AegisScanCamera.ts).
//
// PCI-DSS: the event payload for a detected card carries only brand/
// last4/expiry/cardholderName -- the redacted shape, never raw PAN
// digits. `pan` itself is wiped by the pipeline immediately after this
// delegate method returns, same as every other platform's camera
// pipeline integration in this SDK.

import AegisScan
import Foundation
import React

@objc(AegisScanCamera)
class AegisScanCamera: RCTEventEmitter {
    /// The view manager reads this to attach `AegisScanCameraView`'s preview
    /// layer to the same capture session this module's start()/stop() control.
    static var shared: AegisScanCamera?

    let pipeline = CameraScannerPipeline()
    private var hasListeners = false

    override init() {
        super.init()
        pipeline.delegate = self
        AegisScanCamera.shared = self
    }

    override static func requiresMainQueueSetup() -> Bool { true }

    override func supportedEvents() -> [String]! {
        ["onCardDetected", "onVouchersMatched", "onError"]
    }

    override func startObserving() { hasListeners = true }
    override func stopObserving() { hasListeners = false }

    @objc func start() {
        DispatchQueue.main.async { self.pipeline.start() }
    }

    @objc func stop() {
        DispatchQueue.main.async { self.pipeline.stop() }
    }

    private func emit(_ name: String, _ body: [String: Any]) {
        guard hasListeners else { return }
        sendEvent(withName: name, body: body)
    }
}

extension AegisScanCamera: CameraScannerPipelineDelegate {
    nonisolated func scanner(_ scanner: CameraScannerPipeline, didDetectCard pan: SecurePAN) {
        // Redacted shape only -- never the raw digits -- crosses the bridge.
        var body: [String: Any] = ["brand": "\(pan.brand)", "last4": pan.last4]
        if let expiry = pan.expiry {
            body["expiry"] = ["month": expiry.month, "year": expiry.year]
        }
        if let name = pan.cardholderName {
            body["cardholderName"] = name
        }
        DispatchQueue.main.async { self.emit("onCardDetected", body) }
    }

    nonisolated func scanner(_ scanner: CameraScannerPipeline, didMatchVouchers matches: [VoucherMatch]) {
        guard let first = matches.first else { return }
        let body: [String: Any] = [
            "payload": first.barcode.payload,
            "associatedText": first.associatedText,
        ]
        DispatchQueue.main.async { self.emit("onVouchersMatched", body) }
    }

    nonisolated func scanner(_ scanner: CameraScannerPipeline, didFailWith error: ScannerError) {
        let body: [String: Any] = ["message": "\(error)"]
        DispatchQueue.main.async { self.emit("onError", body) }
    }
}
