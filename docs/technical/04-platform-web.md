# Platform: Web

Source: `web/src/` (`@aegisscan/web`). Entry points:
`FintechWebScanner.ts` (main thread) and `scanner.worker.ts` (Web Worker).
Core port: `web/src/core/luhn.ts`, `web/src/core/spatialMatcher.ts`
(TypeScript, same algorithm as [Core Engine](01-core-engine.md), no WASM —
there's no C ABI story for a browser without compiling the core to WASM,
which this SDK doesn't do). Sample: `sample/web/` (zero-build static page).

## Main thread: `FintechWebScanner`

```
getUserMedia (environment camera, ideal 1920x1080)
  → <video> → requestAnimationFrame
  → createImageBitmap(video)                      (only when the worker is idle)
  → worker.postMessage({ imageBitmap }, [imageBitmap])   (Transferable: 0-copy)
```

**Back-pressure**: a plain `boolean` (`isProcessing`), checked *before* even
calling `createImageBitmap` — since JS runs one task at a time on the main
thread, the check-and-set is atomic with respect to the worker's reply
(handled in a separate task), with no need for a CAS primitive. A frame
in flight longer than `frameTimeoutMs` (default 5000ms) is treated as lost
and reported as `WORKER_TIMEOUT`, releasing the gate so capture can resume.

**Card confirmation**: identical pattern to iOS/Android —
`requiredConsecutiveCardHits` (default 2) consecutive worker results with
the same digit buffer before `onCard` fires.

`FintechWebScanner.isSupported()` checks secure context, `getUserMedia`,
`Worker`, and `createImageBitmap` availability **without** prompting for
camera permission — call it before doing anything else.

## Worker: `scanner.worker.ts`

```
ImageBitmap (transferred, 0-copy)
  → validate (dimensions / aspect ratio)
  → BarcodeDetector (native) on the ImageBitmap directly
      ↘ WASM/JS fallback on OffscreenCanvas ImageData if unsupported
  → OCR stage (pluggable module) on OffscreenCanvas ImageData
  → extractCard (Luhn + repair) + SpatialMatcher
  → structured result posted back; the PAN travels in a transferred buffer
```

**OCR is pluggable, by necessity**: browsers have no built-in text
recognizer. Pass `ocrModuleUrl` (a module exporting
`recognize(image: ImageData): OcrLine[]`) to enable card detection; without
it, `onCard` simply never fires, but voucher barcode detection still works
fully via the native
[`BarcodeDetector`](https://developer.mozilla.org/en-US/docs/Web/API/Barcode_Detection_API)
API where supported, or `barcodeFallbackUrl` otherwise.
`sample/web/ocr-tesseract.js` is a working reference implementation of the
`ocrModuleUrl` contract, backed by Tesseract.js. See
`sample/web/README.md` for exactly how to wire one in.

**Zero-copy throughout**: the `ImageBitmap` is a Transferable (no pixel
copy crossing the thread boundary), the native `BarcodeDetector` runs
directly on it (no canvas needed), and the `OffscreenCanvas` backing store
is only touched (and only allocated once, reused/resized after) when OCR or
the barcode fallback actually need raw pixels.

## Wire protocol (`protocol.ts`)

`MainToWorker` = `init | frame | dispose`. `WorkerToMain` = `ready |
result | frame-error | fatal`. Only plain, structured-clone-safe data
crosses the boundary (plus the one `Transferable` per frame) — see the
type definitions for the exact shape of `WireResult`/`WireCard`/
`WireVoucher`.

## PCI-DSS in the browser

No `localStorage`, `sessionStorage`, `IndexedDB`, Cache API, or cookies,
anywhere in this SDK. `onCard`'s `digits: Uint8Array` is zeroed
(`.fill(0)`) immediately after the callback returns; the `OffscreenCanvas`
is cleared (`clearRect`) at the end of every frame regardless of success or
failure.

## See also

- [Core Engine](01-core-engine.md) for the TypeScript port's algorithm.
- [Security & PCI-DSS](07-security-pci-dss.md).
- `sample/web/README.md` — run it with zero `npm install` beyond building
  the SDK once.
