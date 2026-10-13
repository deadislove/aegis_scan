// Copyright 2026 AegisScan Authors. All rights reserved.
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

const String _viewType = 'aegis_scan_flutter/camera_view';

/// Native camera preview. iOS: an `AVCaptureVideoPreviewLayer` attached to
/// the plugin's shared `CameraScannerPipeline` session. Android: a
/// `PreviewView` owning an `AndroidCameraScannerEngine`. Mount exactly one
/// at a time -- same constraint as the RN bridge's `<AegisScanCameraView />`.
class AegisScanCameraView extends StatelessWidget {
  const AegisScanCameraView({super.key});

  @override
  Widget build(BuildContext context) {
    if (defaultTargetPlatform == TargetPlatform.iOS) {
      return const UiKitView(viewType: _viewType, creationParams: null);
    }
    return const AndroidView(viewType: _viewType, creationParams: null);
  }
}
