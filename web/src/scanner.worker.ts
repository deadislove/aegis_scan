// Copyright 2026 AegisScan Authors. All rights reserved.
//
// AegisScan Web Worker engine.
//
//   ImageBitmap (transferred, 0-copy)
//     → validation (dimensions / aspect ratio)
//     → BarcodeDetector (native, runs directly on the ImageBitmap)
//       ↘ WASM/JS fallback on OffscreenCanvas ImageData if unsupported
//     → OCR stage (pluggable WASM module) on OffscreenCanvas ImageData
//     → LuhnValidator (auto-repair) + SpatialMatcher
//     → structured result posted back; the PAN goes in a transferred buffer
//
// PCI-DSS: no localStorage / sessionStorage / IndexedDB / Cache API, no
// console logging of content. The ImageBitmap is closed and the canvas is
// cleared in a `finally` block for every frame.

// Compiled with tsconfig.worker.json (lib: WebWorker), so `self` is a
// DedicatedWorkerGlobalScope.

import { CARD_BRAND_NAMES, extractCardDetails } from './core/luhn.js';
import { SpatialMatcher, type BarcodeItem, type TextItem } from './core/spatialMatcher.js';
import type {
  MainToWorker, OcrLine, WireResult, WorkerConfig, WorkerErrorCode, WorkerToMain,
} from './protocol.js';

/** Minimal typing for the Shape Detection API (not in lib.dom yet). */
interface DetectedBarcodeLike {
  rawValue: string;
  format: string;
  boundingBox: DOMRectReadOnly;
}
interface BarcodeDetectorLike {
  detect(source: ImageBitmapSource | ImageData): Promise<DetectedBarcodeLike[]>;
}
interface BarcodeDetectorCtor {
  new (options?: { formats: string[] }): BarcodeDetectorLike;
  getSupportedFormats(): Promise<string[]>;
}
interface OcrModule {
  recognize(image: ImageData): Promise<OcrLine[]> | OcrLine[];
}
interface BarcodeFallbackModule {
  detect(image: ImageData): Promise<DetectedBarcodeLike[]> | DetectedBarcodeLike[];
}

const MIN_DIMENSION = 64;
const MAX_DIMENSION = 8192;
const MIN_ASPECT = 0.25;
const MAX_ASPECT = 4;

let config: WorkerConfig | null = null;
let detector: BarcodeDetectorLike | null = null;
let barcodeFallback: BarcodeFallbackModule | null = null;
let ocr: OcrModule | null = null;
let canvas: OffscreenCanvas | null = null;
let ctx: OffscreenCanvasRenderingContext2D | null = null;
const matcher = new SpatialMatcher();

const post = (msg: WorkerToMain, transfer: Transferable[] = []): void => self.postMessage(msg, transfer);

class FrameError extends Error {
  constructor(readonly code: WorkerErrorCode, message: string) {
    super(message);
  }
}

async function init(cfg: WorkerConfig): Promise<void> {
  config = cfg;
  const hasOffscreen = typeof OffscreenCanvas !== 'undefined';
  if (!hasOffscreen && !('BarcodeDetector' in self)) {
    post({ type: 'fatal', code: 'UNSUPPORTED_ENVIRONMENT',
      message: 'Neither OffscreenCanvas nor BarcodeDetector is available in this worker' });
    return;
  }
  const Ctor = (self as unknown as { BarcodeDetector?: BarcodeDetectorCtor }).BarcodeDetector;
  if (Ctor && cfg.detectVouchers) {
    try {
      const supported = await Ctor.getSupportedFormats();
      const formats = cfg.barcodeFormats.filter((f) => supported.includes(f));
      if (formats.length > 0) detector = new Ctor({ formats });
    } catch {
      detector = null;  // Fall through to the WASM/JS fallback.
    }
  }
  if (!detector && cfg.detectVouchers && cfg.barcodeFallbackUrl) {
    try {
      barcodeFallback = (await import(/* @vite-ignore */ cfg.barcodeFallbackUrl)) as BarcodeFallbackModule;
    } catch {
      barcodeFallback = null;
    }
  }
  if (cfg.ocrModuleUrl) {
    try {
      ocr = (await import(/* @vite-ignore */ cfg.ocrModuleUrl)) as OcrModule;
    } catch {
      ocr = null;
    }
  }
  post({ type: 'ready',
    capabilities: { nativeBarcodeDetector: detector !== null, ocr: ocr !== null, offscreenCanvas: hasOffscreen } });
}

function validateBitmap(bitmap: ImageBitmap): void {
  const { width, height } = bitmap;
  if (!Number.isFinite(width) || !Number.isFinite(height) || width < MIN_DIMENSION ||
      height < MIN_DIMENSION || width > MAX_DIMENSION || height > MAX_DIMENSION) {
    throw new FrameError('INVALID_FRAME', `Invalid frame dimensions ${width}x${height}`);
  }
  const aspect = width / height;
  if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) {
    throw new FrameError('UNSUPPORTED_ASPECT_RATIO', `Unsupported aspect ratio ${aspect.toFixed(2)}`);
  }
}

/** Draws the bitmap to the (reused) OffscreenCanvas and returns its pixels. */
function rasterize(bitmap: ImageBitmap, scale: number): ImageData {
  const w = Math.max(1, Math.round(bitmap.width * scale));
  const h = Math.max(1, Math.round(bitmap.height * scale));
  if (!canvas) {
    canvas = new OffscreenCanvas(w, h);
    ctx = canvas.getContext('2d', { willReadFrequently: true, alpha: false });
  } else if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w;  // Resizing also clears the backing store.
    canvas.height = h;
  }
  if (!ctx) throw new FrameError('UNSUPPORTED_ENVIRONMENT', '2D context unavailable on OffscreenCanvas');
  ctx.drawImage(bitmap, 0, 0, w, h);
  return ctx.getImageData(0, 0, w, h);
}

/** Wipes the canvas so no pixels outlive the frame (PCI-DSS). */
function clearCanvas(): void {
  if (ctx && canvas) ctx.clearRect(0, 0, canvas.width, canvas.height);
}

const unscale = (b: { x: number; y: number; width: number; height: number }, s: number) =>
  s === 1 ? b : { x: b.x / s, y: b.y / s, width: b.width / s, height: b.height / s };

async function detectBarcodes(bitmap: ImageBitmap, image: ImageData | null,
                              scale: number): Promise<BarcodeItem[]> {
  let raw: DetectedBarcodeLike[] = [];
  let s = 1;
  try {
    if (detector) {
      raw = await detector.detect(bitmap);  // Native: full-res, no canvas copy.
    } else if (barcodeFallback && image) {
      raw = await barcodeFallback.detect(image);
      s = scale;
    }
  } catch {
    throw new FrameError('DETECTOR_FAILED', 'Barcode detection failed');
  }
  return raw
    .filter((b) => typeof b.rawValue === 'string' && b.rawValue.length > 0)
    .map((b) => ({
      payload: b.rawValue,
      format: b.format,
      box: unscale({ x: b.boundingBox.x, y: b.boundingBox.y,
        width: b.boundingBox.width, height: b.boundingBox.height }, s),
    }));
}

async function recognizeText(image: ImageData | null, scale: number): Promise<TextItem[]> {
  if (!ocr || !image) return [];
  try {
    const lines = await ocr.recognize(image);
    return lines.map((l) => ({ text: l.text, box: unscale(l.box, scale) }));
  } catch {
    throw new FrameError('OCR_FAILED', 'OCR engine failed');
  }
}

async function processFrame(frameId: number, bitmap: ImageBitmap): Promise<void> {
  const t0 = performance.now();
  let image: ImageData | null = null;
  const timings = { decodeMs: 0, barcodeMs: 0, ocrMs: 0, totalMs: 0 };
  try {
    if (!config) throw new FrameError('NOT_INITIALIZED', 'Worker not initialized');
    validateBitmap(bitmap);
    const cfg = config;
    const needPixels = (cfg.detectVouchers && !detector && barcodeFallback !== null) || ocr !== null;
    if (needPixels) {
      image = rasterize(bitmap, cfg.analysisScale);
      timings.decodeMs = performance.now() - t0;
    }
    const scale = image ? cfg.analysisScale : 1;

    // Barcode detection and OCR run concurrently.
    const tStart = performance.now();
    const [barcodes, texts] = await Promise.all([
      cfg.detectVouchers
        ? detectBarcodes(bitmap, image, scale).finally(() => { timings.barcodeMs = performance.now() - tStart; })
        : Promise.resolve<BarcodeItem[]>([]),
      recognizeText(image, scale).finally(() => { timings.ocrMs = performance.now() - tStart; }),
    ]);

    // Cards: one string per OCR line; '\n' is a hard break in the core.
    let card: WireResult['card'] = null;
    const transfer: Transferable[] = [];
    if (cfg.detectCards && texts.length > 0) {
      const pan = extractCardDetails(texts.map((t) => t.text).join('\n'));
      if (pan) {
        const digits = new Uint8Array(pan.digits);  // Dedicated, transferable.
        pan.wipe();
        card = {
          digits, brand: CARD_BRAND_NAMES[pan.brand], last4: pan.last4,
          expiry: pan.expiry, cardholderName: pan.cardholderName,
        };
        transfer.push(digits.buffer);
      }
    }
    const vouchers = cfg.detectVouchers && barcodes.length > 0 && texts.length > 0
      ? matcher.matchBarcodesWithText(barcodes, texts).map((m) => ({
        payload: m.barcode.payload,
        format: m.barcode.format ?? 'unknown',
        box: m.barcode.box,
        associatedText: m.associatedText,
        normalizedDistance: m.normalizedDistance,
        alignment: m.alignment,
      }))
      : [];
    timings.totalMs = performance.now() - t0;
    post({
      type: 'result', frameId, card, vouchers, timings,
      barcodes: barcodes.map((b) => ({ payload: b.payload, format: b.format ?? 'unknown', box: b.box })),
    }, transfer);  // Transfer detaches `digits` here: no copy stays in the worker.
  } catch (err) {
    const code: WorkerErrorCode = err instanceof FrameError ? err.code : 'DETECTOR_FAILED';
    post({ type: 'frame-error', frameId, code, message: err instanceof Error ? err.message : 'unknown' });
  } finally {
    bitmap.close();             // Release the transferred frame immediately.
    if (image) image.data.fill(0);
    clearCanvas();
  }
}

self.onmessage = (event: MessageEvent<MainToWorker>): void => {
  const msg = event.data;
  switch (msg.type) {
    case 'init':
      void init(msg.config);
      break;
    case 'frame':
      void processFrame(msg.frameId, msg.imageBitmap);
      break;
    case 'dispose':
      clearCanvas();
      if (canvas) {
        canvas.width = 0;   // Release the backing store.
        canvas.height = 0;
      }
      canvas = null;
      ctx = null;
      detector = null;
      ocr = null;
      barcodeFallback = null;
      self.close();
      break;
  }
};
