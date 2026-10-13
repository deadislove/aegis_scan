// Copyright 2026 AegisScan Authors. All rights reserved.
//
// Main-thread camera pipeline for the AegisScan Web SDK.
//
//   getUserMedia (environment camera, ideal 1920x1080)
//     → <video> → requestAnimationFrame / requestVideoFrameCallback
//     → createImageBitmap(video)        (only when the worker is idle)
//     → worker.postMessage({ imageBitmap }, [imageBitmap])   (0-copy transfer)
//
// Back-pressure: `isProcessing` is checked before any capture work. While the
// worker is busy no bitmap is created, so the frame is dropped at zero cost.
// JS runs one task at a time on the main thread, so that check-and-set is
// atomic with respect to the worker's reply (handled in a separate task).
//
// PCI-DSS: no localStorage / sessionStorage / IndexedDB / cookies / Cache
// API. PANs arrive in a transferred Uint8Array and are zeroed right after
// the onCard callback returns.

import type { MainToWorker, WireResult, WireVoucher, WorkerConfig, WorkerErrorCode, WorkerToMain } from './protocol.js';

export type ScannerErrorCode =
  | 'CAMERA_PERMISSION_DENIED'
  | 'CAMERA_NOT_FOUND'
  | 'CAMERA_IN_USE'
  | 'CAMERA_CONSTRAINTS_UNSATISFIED'
  | 'INSECURE_CONTEXT'
  | 'UNSUPPORTED_BROWSER'
  | 'WORKER_FAILED'
  | 'WORKER_TIMEOUT'
  | WorkerErrorCode;

export class ScannerError extends Error {
  constructor(readonly code: ScannerErrorCode, message: string, readonly recoverable = false) {
    super(message);
    this.name = 'ScannerError';
  }
}

/** Card result handed to `onCard`. `digits` is zeroed after the callback. */
export interface CardScan {
  /** ASCII digit codes. Read synchronously; it's wiped when onCard returns. */
  readonly digits: Uint8Array;
  readonly brand: string;
  readonly last4: string;
  /** Best-effort expiry, extracted from the same OCR text as the PAN. */
  readonly expiry: { month: number; year: number } | null;
  /** Best-effort cardholder name, extracted from the same OCR text as the
   * PAN. Markedly less reliable than `expiry` or the PAN itself. */
  readonly cardholderName: string | null;
}

export interface FintechWebScannerOptions {
  /** Element the camera stream is attached to (can be hidden). */
  video: HTMLVideoElement;
  /** Factory for the worker. Default: module worker from scanner.worker.js. */
  createWorker?: () => Worker;
  detectCards?: boolean;
  detectVouchers?: boolean;
  barcodeFormats?: string[];
  ocrModuleUrl?: string;
  barcodeFallbackUrl?: string;
  analysisScale?: number;
  /** Max frames per second sent to the worker (default 30). */
  maxFps?: number;
  /** Same PAN required on N consecutive results before onCard (default 2). */
  requiredConsecutiveCardHits?: number;
  /** A frame in flight longer than this is considered lost (default 5 s). */
  frameTimeoutMs?: number;
  onCard?: (card: CardScan) => void;
  onVouchers?: (vouchers: WireVoucher[]) => void;
  onError?: (error: ScannerError) => void;
  onReady?: (capabilities: { nativeBarcodeDetector: boolean; ocr: boolean; offscreenCanvas: boolean }) => void;
}

export interface ScannerStats {
  framesCaptured: number;
  framesDropped: number;
  framesProcessed: number;
  framesFailed: number;
  lastTotalMs: number;
}

export class FintechWebScanner {
  private readonly opts: Required<Pick<FintechWebScannerOptions,
    'detectCards' | 'detectVouchers' | 'maxFps' | 'requiredConsecutiveCardHits' | 'frameTimeoutMs'>> &
    FintechWebScannerOptions;
  private stream: MediaStream | null = null;
  private worker: Worker | null = null;
  private rafHandle = 0;
  private running = false;
  /** Back-pressure gate: true while the worker owns a frame. */
  private isProcessing = false;
  private inFlightSince = 0;
  private frameId = 0;
  private lastSentAt = 0;
  private pendingCard: Uint8Array | null = null;
  private pendingHits = 0;
  private readonly stats: ScannerStats = {
    framesCaptured: 0, framesDropped: 0, framesProcessed: 0, framesFailed: 0, lastTotalMs: 0,
  };

  constructor(options: FintechWebScannerOptions) {
    this.opts = {
      detectCards: true,
      detectVouchers: true,
      maxFps: 30,
      requiredConsecutiveCardHits: 2,
      frameTimeoutMs: 5000,
      ...options,
    };
  }

  /** Feature check without prompting for permissions. */
  static isSupported(): { supported: boolean; reason?: ScannerErrorCode } {
    if (typeof window === 'undefined') return { supported: false, reason: 'UNSUPPORTED_BROWSER' };
    if (!window.isSecureContext) return { supported: false, reason: 'INSECURE_CONTEXT' };
    if (!navigator.mediaDevices?.getUserMedia) return { supported: false, reason: 'UNSUPPORTED_BROWSER' };
    if (typeof Worker === 'undefined' || typeof createImageBitmap === 'undefined') {
      return { supported: false, reason: 'UNSUPPORTED_BROWSER' };
    }
    return { supported: true };
  }

  getStats(): Readonly<ScannerStats> {
    return { ...this.stats };
  }

  /**
   * Requests the camera, spins up the worker, and starts the capture loop.
   * Rejects with a ScannerError (also reported through onError).
   */
  async start(): Promise<void> {
    if (this.running) return;
    const support = FintechWebScanner.isSupported();
    if (!support.supported) {
      throw this.fail(new ScannerError(support.reason ?? 'UNSUPPORTED_BROWSER',
        support.reason === 'INSECURE_CONTEXT'
          ? 'Camera access requires HTTPS (secure context)'
          : 'This browser lacks getUserMedia, Web Workers, or createImageBitmap'));
    }
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: { facingMode: 'environment', width: { ideal: 1920 }, height: { ideal: 1080 } },
      });
    } catch (err) {
      throw this.fail(FintechWebScanner.mapMediaError(err));
    }
    const video = this.opts.video;
    video.muted = true;
    video.playsInline = true;
    video.setAttribute('playsinline', '');
    video.srcObject = this.stream;
    try {
      await video.play();
    } catch (err) {
      this.releaseCamera();
      throw this.fail(new ScannerError('CAMERA_IN_USE', `Unable to start video playback: ${(err as Error).name}`));
    }
    // A revoked permission or unplugged camera ends the track.
    for (const track of this.stream.getVideoTracks()) {
      track.addEventListener('ended', () => {
        this.fail(new ScannerError('CAMERA_NOT_FOUND', 'Camera track ended', true));
        this.stop();
      });
    }
    this.startWorker();
    this.running = true;
    this.scheduleNextFrame();
  }

  /** Stops capture, releases the camera, and terminates the worker. */
  stop(): void {
    this.running = false;
    if (this.rafHandle) cancelAnimationFrame(this.rafHandle);
    this.rafHandle = 0;
    this.releaseCamera();
    if (this.worker) {
      this.post({ type: 'dispose' });
      this.worker.terminate();
      this.worker = null;
    }
    this.isProcessing = false;
    this.resetCard();
  }

  // --------------------------------------------------------------- Internals

  private startWorker(): void {
    const worker = this.opts.createWorker
      ? this.opts.createWorker()
      : new Worker(new URL('./scanner.worker.js', import.meta.url), { type: 'module', name: 'aegisscan' });
    worker.onmessage = (e: MessageEvent<WorkerToMain>) => this.onWorkerMessage(e.data);
    worker.onerror = (e: ErrorEvent) => {
      e.preventDefault();
      this.isProcessing = false;
      this.fail(new ScannerError('WORKER_FAILED', 'Scanner worker crashed', true));
    };
    worker.onmessageerror = () => {
      this.isProcessing = false;
      this.stats.framesFailed++;
    };
    this.worker = worker;
    const config: WorkerConfig = {
      detectCards: this.opts.detectCards,
      detectVouchers: this.opts.detectVouchers,
      barcodeFormats: this.opts.barcodeFormats ?? ['qr_code', 'code_128', 'ean_13', 'pdf417', 'data_matrix', 'itf'],
      ocrModuleUrl: this.opts.ocrModuleUrl,
      barcodeFallbackUrl: this.opts.barcodeFallbackUrl,
      analysisScale: this.opts.analysisScale ?? 1,
    };
    this.post({ type: 'init', config });
  }

  private scheduleNextFrame(): void {
    if (!this.running) return;
    this.rafHandle = requestAnimationFrame((now) => this.onAnimationFrame(now));
  }

  private onAnimationFrame(now: number): void {
    this.scheduleNextFrame();  // Keep the loop going whatever happens below.
    if (!this.worker) return;
    // Recover from a lost reply (e.g. a worker GC pause or a dropped message).
    if (this.isProcessing && now - this.inFlightSince > this.opts.frameTimeoutMs) {
      this.isProcessing = false;
      this.stats.framesFailed++;
      this.fail(new ScannerError('WORKER_TIMEOUT', 'Frame timed out in worker', true));
    }
    // Atomic gate: busy → drop instantly, before any capture work.
    if (this.isProcessing) {
      this.stats.framesDropped++;
      return;
    }
    if (now - this.lastSentAt < 1000 / this.opts.maxFps) return;
    const video = this.opts.video;
    if (video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA || video.videoWidth === 0) return;

    this.isProcessing = true;  // Set before the async capture: no double send.
    this.inFlightSince = now;
    this.lastSentAt = now;
    const id = ++this.frameId;
    createImageBitmap(video).then((imageBitmap) => {
      if (!this.running || !this.worker) {
        imageBitmap.close();
        this.isProcessing = false;
        return;
      }
      this.stats.framesCaptured++;
      // Transferable: ownership moves to the worker, no pixel copy.
      this.post({ type: 'frame', frameId: id, imageBitmap, timestamp: now }, [imageBitmap]);
    }).catch(() => {
      this.isProcessing = false;  // Corrupt or transient frame: just skip it.
      this.stats.framesFailed++;
    });
  }

  private onWorkerMessage(msg: WorkerToMain): void {
    switch (msg.type) {
      case 'ready':
        this.opts.onReady?.(msg.capabilities);
        return;
      case 'fatal':
        this.fail(new ScannerError(msg.code, msg.message));
        this.stop();
        return;
      case 'frame-error':
        if (msg.frameId === this.frameId) this.isProcessing = false;
        this.stats.framesFailed++;
        if (msg.code === 'UNSUPPORTED_ASPECT_RATIO' || msg.code === 'OCR_FAILED' || msg.code === 'DETECTOR_FAILED') {
          this.fail(new ScannerError(msg.code, msg.message, true));
        }
        return;
      case 'result':
        if (msg.frameId === this.frameId) this.isProcessing = false;  // Release the gate first.
        this.stats.framesProcessed++;
        this.stats.lastTotalMs = msg.timings.totalMs;
        this.handleResult(msg);
        return;
    }
  }

  private handleResult(msg: WireResult): void {
    if (msg.card) {
      this.handleCard(msg.card.digits, msg.card.brand, msg.card.last4, msg.card.expiry, msg.card.cardholderName);
    } else {
      this.resetCard();
    }
    if (msg.vouchers.length > 0) this.opts.onVouchers?.(msg.vouchers);
  }

  /** Multi-frame confirmation, then delivery and an immediate wipe. */
  private handleCard(
    digits: Uint8Array, brand: string, last4: string,
    expiry: { month: number; year: number } | null, cardholderName: string | null,
  ): void {
    const same = this.pendingCard !== null && this.pendingCard.length === digits.length &&
      this.pendingCard.every((v, i) => v === digits[i]);
    if (same) {
      digits.fill(0);
      this.pendingHits++;
    } else {
      this.pendingCard?.fill(0);
      this.pendingCard = digits;
      this.pendingHits = 1;
    }
    if (this.pendingHits < Math.max(1, this.opts.requiredConsecutiveCardHits)) return;
    const confirmed = this.pendingCard!;
    this.pendingCard = null;
    this.pendingHits = 0;
    try {
      this.opts.onCard?.({ digits: confirmed, brand, last4, expiry, cardholderName });
    } finally {
      confirmed.fill(0);  // Purged as soon as the callback returns.
    }
  }

  private resetCard(): void {
    this.pendingCard?.fill(0);
    this.pendingCard = null;
    this.pendingHits = 0;
  }

  private post(msg: MainToWorker, transfer: Transferable[] = []): void {
    this.worker?.postMessage(msg, transfer);
  }

  private releaseCamera(): void {
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    this.opts.video.srcObject = null;
  }

  private fail(error: ScannerError): ScannerError {
    this.opts.onError?.(error);
    return error;
  }

  /** Maps getUserMedia DOMExceptions to stable SDK error codes. */
  static mapMediaError(err: unknown): ScannerError {
    const name = (err as { name?: string } | null)?.name ?? '';
    switch (name) {
      case 'NotAllowedError':
      case 'PermissionDeniedError':
      case 'SecurityError':
        return new ScannerError('CAMERA_PERMISSION_DENIED', 'Camera permission was denied by the user or policy');
      case 'NotFoundError':
      case 'DevicesNotFoundError':
        return new ScannerError('CAMERA_NOT_FOUND', 'No camera device found');
      case 'NotReadableError':
      case 'TrackStartError':
      case 'AbortError':
        return new ScannerError('CAMERA_IN_USE', 'Camera is in use by another application', true);
      case 'OverconstrainedError':
      case 'ConstraintNotSatisfiedError':
        return new ScannerError('CAMERA_CONSTRAINTS_UNSATISFIED', 'No camera satisfies the requested constraints');
      case 'TypeError':
        return new ScannerError('INSECURE_CONTEXT', 'getUserMedia unavailable (insecure context?)');
      default:
        return new ScannerError('UNSUPPORTED_BROWSER', 'Camera access failed');
    }
  }
}
