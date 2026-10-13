// Copyright 2026 AegisScan Authors. All rights reserved.
//
// Thin typed wrapper over the native AegisScanCamera module/view (see
// ../ios/AegisScanRNBridge/AegisScanCamera.swift and
// ../android/aegisscan-rn-bridge/.../AegisScanCamera*.kt). The JS surface
// is a pass-through of the native delegate/listener callbacks already
// documented in docs/guide/01-quickstart-ios.md / 02-quickstart-android.md
// -- not a new API design.
import {
  NativeEventEmitter, NativeModules, Platform, requireNativeComponent, type ViewProps,
} from 'react-native';

const { AegisScanCamera: AegisScanCameraModule } = NativeModules;

/** Best-effort -- no checksum backs this, unlike the PAN. */
export interface ExpiryDate {
  month: number;
  year: number;
}

/** Redacted shape only -- never raw PAN digits -- crosses the bridge. */
export interface CardScan {
  brand: string;
  last4: string;
  expiry?: ExpiryDate;
  cardholderName?: string;
}

export interface VoucherMatch {
  payload: string;
  associatedText: string;
}

export interface ScannerError {
  message: string;
}

const emitter = new NativeEventEmitter(AegisScanCameraModule);

/** Starts the camera + scanner. No-op until `<AegisScanCameraView />` is mounted. */
export function startScanning(): void {
  AegisScanCameraModule.start();
}

/** Stops the camera + scanner. */
export function stopScanning(): void {
  AegisScanCameraModule.stop();
}

export function onCardDetected(listener: (card: CardScan) => void) {
  return emitter.addListener('onCardDetected', (card: Object) => listener(card as CardScan));
}

export function onVouchersMatched(listener: (match: VoucherMatch) => void) {
  return emitter.addListener(
    'onVouchersMatched',
    (match: Object) => listener(match as VoucherMatch),
  );
}

export function onScanError(listener: (error: ScannerError) => void) {
  return emitter.addListener('onError', (error: Object) => listener(error as ScannerError));
}

/**
 * Native camera preview. iOS: backed by `CameraScannerPipeline`'s capture
 * session. Android: a `PreviewView` owning an `AndroidCameraScannerEngine`.
 * Mount exactly one at a time.
 */
export const AegisScanCameraView = requireNativeComponent<ViewProps>(
  Platform.OS === 'ios' ? 'AegisScanCameraViewManager' : 'AegisScanCameraView',
);
