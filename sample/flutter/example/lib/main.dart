// AegisScan Flutter sample. Mirrors every other platform sample's shape
// (sample/ios/CameraApp, sample/android, sample/web/main.js,
// sample/react-native): full-screen camera preview, a status overlay
// showing brand/last4/expiry/cardholderName on a detected card.
import 'dart:async';

import 'package:aegis_scan_flutter/aegis_scan_camera_view.dart';
import 'package:aegis_scan_flutter/aegis_scan_flutter.dart' as aegis_scan;
import 'package:flutter/material.dart';

void main() {
  runApp(const AegisScanApp());
}

class AegisScanApp extends StatelessWidget {
  const AegisScanApp({super.key});

  @override
  Widget build(BuildContext context) =>
      const MaterialApp(home: ScannerScreen());
}

class ScannerScreen extends StatefulWidget {
  const ScannerScreen({super.key});

  @override
  State<ScannerScreen> createState() => _ScannerScreenState();
}

class _ScannerScreenState extends State<ScannerScreen> {
  String _statusText = 'Point the camera at a card or voucher';
  StreamSubscription<aegis_scan.ScanEvent>? _subscription;

  @override
  void initState() {
    super.initState();
    _subscription = aegis_scan.scanEvents.listen(_handleEvent);
    // Permission handling is native-side: iOS prompts automatically when
    // the capture session starts (NSCameraUsageDescription in Info.plist);
    // Android's native plugin requests CAMERA via ActivityAware and calls
    // start() again once granted -- see AegisScanFlutterPlugin.kt.
    aegis_scan.startScanning();
  }

  void _handleEvent(aegis_scan.ScanEvent event) {
    setState(() {
      switch (event) {
        case aegis_scan.CardDetectedEvent(card: final card):
          var text = '${card.brand} ****${card.last4}';
          if (card.expiry != null) {
            text += ' exp ${card.expiry!.month}/${card.expiry!.year}';
          }
          if (card.cardholderName != null) {
            text += ' (${card.cardholderName})';
          }
          _statusText = text;
        case aegis_scan.VouchersMatchedEvent(match: final match):
          _statusText = 'Voucher: ${match.payload} -> ${match.associatedText}';
        case aegis_scan.ScanErrorEvent(error: final error):
          _statusText = 'Error: ${error.message}';
      }
    });
  }

  @override
  void dispose() {
    _subscription?.cancel();
    aegis_scan.stopScanning();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          const Positioned.fill(child: AegisScanCameraView()),
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: Container(
              padding: const EdgeInsets.all(16),
              color: Colors.black.withValues(alpha: 0.6),
              child: Text(
                _statusText,
                style: const TextStyle(color: Colors.white, fontSize: 16),
                textAlign: TextAlign.center,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
