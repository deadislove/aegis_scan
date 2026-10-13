// Copyright 2026 AegisScan Authors. All rights reserved.
//
// Flutter plugin wrapping AegisScan's native camera pipelines
// (CameraScannerPipeline on iOS, AndroidCameraScannerEngine on Android).
// The JS/TS React Native bridge (../react-native/src/AegisScanCamera.tsx)
// has the identical shape -- same events, same redacted card fields -- so
// read that file's doc comments for the full rationale; this one stays
// terse to avoid duplicating it.
library;

import 'dart:async';

import 'package:flutter/services.dart';

const MethodChannel _channel = MethodChannel('aegis_scan_flutter');
const EventChannel _events = EventChannel('aegis_scan_flutter/events');

/// Best-effort -- no checksum backs this, unlike the PAN.
class ExpiryDate {
  const ExpiryDate({required this.month, required this.year});
  final int month;
  final int year;

  factory ExpiryDate.fromMap(Map<dynamic, dynamic> map) =>
      ExpiryDate(month: map['month'] as int, year: map['year'] as int);
}

/// Redacted shape only -- never raw PAN digits -- crosses the platform channel.
class CardScan {
  const CardScan({required this.brand, required this.last4, this.expiry, this.cardholderName});
  final String brand;
  final String last4;
  final ExpiryDate? expiry;
  final String? cardholderName;

  factory CardScan.fromMap(Map<dynamic, dynamic> map) => CardScan(
        brand: map['brand'] as String,
        last4: map['last4'] as String,
        expiry: map['expiry'] != null
            ? ExpiryDate.fromMap(map['expiry'] as Map<dynamic, dynamic>)
            : null,
        cardholderName: map['cardholderName'] as String?,
      );
}

class VoucherMatch {
  const VoucherMatch({required this.payload, required this.associatedText});
  final String payload;
  final String associatedText;

  factory VoucherMatch.fromMap(Map<dynamic, dynamic> map) => VoucherMatch(
        payload: map['payload'] as String,
        associatedText: map['associatedText'] as String,
      );
}

class ScannerError {
  const ScannerError(this.message);
  final String message;
}

/// One of [CardScan], [VoucherMatch], or [ScannerError] -- switch on the
/// runtime type, matching how the RN bridge exposes three separate
/// listener registration functions over one underlying event stream.
sealed class ScanEvent {}

class CardDetectedEvent extends ScanEvent {
  CardDetectedEvent(this.card);
  final CardScan card;
}

class VouchersMatchedEvent extends ScanEvent {
  VouchersMatchedEvent(this.match);
  final VoucherMatch match;
}

class ScanErrorEvent extends ScanEvent {
  ScanErrorEvent(this.error);
  final ScannerError error;
}

/// Starts the camera + scanner. No-op until an `AegisScanCameraView` is mounted.
Future<void> startScanning() => _channel.invokeMethod('start');

/// Stops the camera + scanner.
Future<void> stopScanning() => _channel.invokeMethod('stop');

/// Card/voucher/error events from the native pipeline, main-isolate delivery.
Stream<ScanEvent> get scanEvents => _events.receiveBroadcastStream().map((event) {
      final map = event as Map<dynamic, dynamic>;
      switch (map['type']) {
        case 'card':
          return CardDetectedEvent(CardScan.fromMap(map['card'] as Map<dynamic, dynamic>));
        case 'vouchers':
          return VouchersMatchedEvent(
              VoucherMatch.fromMap(map['match'] as Map<dynamic, dynamic>));
        default:
          return ScanErrorEvent(ScannerError(map['message'] as String));
      }
    });
