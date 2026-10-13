// Copyright 2026 AegisScan Authors. All rights reserved.
//
// Flutter plugin wrapping AegisScan's CameraScannerPipeline. Dart calls
// start()/stop() over a MethodChannel; card/voucher/error events are
// forwarded over an EventChannel. Same shape as the React Native bridge
// (../../react-native/ios/AegisScanRNBridge/AegisScanCamera.swift) --
// PCI-DSS rationale lives there, not repeated here.
import AegisScan
import Flutter
import UIKit

public class AegisScanFlutterPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
    static var shared: AegisScanFlutterPlugin?

    let pipeline = CameraScannerPipeline()
    private var eventSink: FlutterEventSink?

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = AegisScanFlutterPlugin()
        shared = instance
        instance.pipeline.delegate = instance

        let methodChannel = FlutterMethodChannel(
            name: "aegis_scan_flutter", binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(instance, channel: methodChannel)

        let eventChannel = FlutterEventChannel(
            name: "aegis_scan_flutter/events", binaryMessenger: registrar.messenger())
        eventChannel.setStreamHandler(instance)

        registrar.register(
            AegisScanCameraViewFactory(messenger: registrar.messenger()),
            withId: "aegis_scan_flutter/camera_view")
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "start":
            pipeline.start()
            result(nil)
        case "stop":
            pipeline.stop()
            result(nil)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        eventSink = events
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        eventSink = nil
        return nil
    }
}

extension AegisScanFlutterPlugin: CameraScannerPipelineDelegate {
    public nonisolated func scanner(_ scanner: CameraScannerPipeline, didDetectCard pan: SecurePAN) {
        // Redacted shape only -- never the raw digits -- crosses the channel.
        var card: [String: Any] = ["brand": "\(pan.brand)", "last4": pan.last4]
        if let expiry = pan.expiry {
            card["expiry"] = ["month": expiry.month, "year": expiry.year]
        }
        if let name = pan.cardholderName {
            card["cardholderName"] = name
        }
        DispatchQueue.main.async { self.eventSink?(["type": "card", "card": card]) }
    }

    public nonisolated func scanner(_ scanner: CameraScannerPipeline, didMatchVouchers matches: [VoucherMatch]) {
        guard let first = matches.first else { return }
        let match: [String: Any] = [
            "payload": first.barcode.payload,
            "associatedText": first.associatedText,
        ]
        DispatchQueue.main.async { self.eventSink?(["type": "vouchers", "match": match]) }
    }

    public nonisolated func scanner(_ scanner: CameraScannerPipeline, didFailWith error: ScannerError) {
        DispatchQueue.main.async { self.eventSink?(["type": "error", "message": "\(error)"]) }
    }
}
