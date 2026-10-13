// Copyright 2026 AegisScan Authors. All rights reserved.
//
// Camera preview PlatformView: an AVCaptureVideoPreviewLayer attached to
// AegisScanFlutterPlugin's shared CameraScannerPipeline session -- same
// relationship as the RN bridge's AegisScanCameraView.swift and
// sample/ios/CameraApp/ContentView.swift's CameraPreviewView.
import AVFoundation
import Flutter
import UIKit

class AegisScanCameraViewFactory: NSObject, FlutterPlatformViewFactory {
    private let messenger: FlutterBinaryMessenger

    init(messenger: FlutterBinaryMessenger) {
        self.messenger = messenger
        super.init()
    }

    func create(withFrame frame: CGRect, viewIdentifier viewId: Int64, arguments args: Any?) -> FlutterPlatformView {
        AegisScanCameraPlatformView(frame: frame)
    }
}

class AegisScanCameraPlatformView: NSObject, FlutterPlatformView {
    private let containerView: UIView

    init(frame: CGRect) {
        containerView = PreviewContainerView(frame: frame)
        super.init()
    }

    func view() -> UIView { containerView }
}

private class PreviewContainerView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    private var videoPreviewLayer: AVCaptureVideoPreviewLayer {
        // swiftlint:disable:next force_cast
        layer as! AVCaptureVideoPreviewLayer
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        videoPreviewLayer.videoGravity = .resizeAspectFill
        videoPreviewLayer.session = AegisScanFlutterPlugin.shared?.pipeline.session
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used by Flutter's platform view factory")
    }
}
