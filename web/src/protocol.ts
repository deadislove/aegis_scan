// Copyright 2026 AegisScan Authors. All rights reserved.
//
// Structured messages exchanged between FintechWebScanner (main thread) and
// scanner.worker.ts. Only plain, structured-clone-safe data crosses it.

import type { Alignment, BoundingBox } from './core/spatialMatcher.js';

/** Pluggable OCR stage (e.g. a Tesseract/ONNX WASM build) run in the worker. */
export interface OcrLine {
  text: string;
  box: BoundingBox;
}

export interface WorkerConfig {
  detectCards: boolean;
  detectVouchers: boolean;
  /** Barcode formats for native BarcodeDetector (e.g. 'qr_code', 'code_128'). */
  barcodeFormats: string[];
  /** Module URL of an OCR engine exporting `recognize(ImageData) => OcrLine[]`. */
  ocrModuleUrl?: string;
  /** Module URL of a WASM barcode fallback exporting `detect(ImageData)`. */
  barcodeFallbackUrl?: string;
  /** Downscale factor applied before analysis (1 = full res). */
  analysisScale: number;
}

export type MainToWorker =
  | { type: 'init'; config: WorkerConfig }
  | { type: 'frame'; frameId: number; imageBitmap: ImageBitmap; timestamp: number }
  | { type: 'dispose' };

export interface WireCard {
  /** ASCII digit codes in a transferred buffer; the receiver must wipe it. */
  digits: Uint8Array;
  brand: string;
  last4: string;
  expiry: { month: number; year: number } | null;
  cardholderName: string | null;
}

export interface WireVoucher {
  payload: string;
  format: string;
  box: BoundingBox;
  associatedText: string;
  normalizedDistance: number;
  alignment: Alignment;
}

export interface WireResult {
  type: 'result';
  frameId: number;
  card: WireCard | null;
  vouchers: WireVoucher[];
  barcodes: Array<{ payload: string; format: string; box: BoundingBox }>;
  timings: { decodeMs: number; barcodeMs: number; ocrMs: number; totalMs: number };
}

export type WorkerToMain =
  | { type: 'ready'; capabilities: { nativeBarcodeDetector: boolean; ocr: boolean; offscreenCanvas: boolean } }
  | WireResult
  | { type: 'frame-error'; frameId: number; code: WorkerErrorCode; message: string }
  | { type: 'fatal'; code: WorkerErrorCode; message: string };

export type WorkerErrorCode =
  | 'UNSUPPORTED_ENVIRONMENT'
  | 'INVALID_FRAME'
  | 'UNSUPPORTED_ASPECT_RATIO'
  | 'DETECTOR_FAILED'
  | 'OCR_FAILED'
  | 'NOT_INITIALIZED';
