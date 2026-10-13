// Copyright 2026 AegisScan Authors. All rights reserved.
//
// Native camera preview for the RN bridge: an AVCaptureVideoPreviewLayer
// attached to AegisScanCamera's (the module's) shared CameraScannerPipeline
// session -- the same session start()/stop() control, same relationship
// as sample/ios/CameraApp/ContentView.swift's CameraPreviewView.
import AVFoundation
import React
import UIKit

class AegisScanCameraView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    private var videoPreviewLayer: AVCaptureVideoPreviewLayer {
        // swiftlint:disable:next force_cast
        layer as! AVCaptureVideoPreviewLayer
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        videoPreviewLayer.videoGravity = .resizeAspectFill
        videoPreviewLayer.session = AegisScanCamera.shared?.pipeline.session
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used by React Native's view manager")
    }
}

@objc(AegisScanCameraViewManager)
class AegisScanCameraViewManager: RCTViewManager {
    override func view() -> UIView! {
        AegisScanCameraView(frame: .zero)
    }

    override static func requiresMainQueueSetup() -> Bool { true }
}
