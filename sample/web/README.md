# AegisScan Web Sample

A plain HTML + ES module page (no bundler) that drives `@aegisscan/web`
end to end: camera capture, the Web Worker pipeline, barcode detection,
**and real card OCR** (via a bundled Tesseract.js module — one
`npm install` needed for that part; everything else stays build-free).

## Why it needs the repo root as the server root

`main.js` imports the SDK with a relative path:

```js
import { FintechWebScanner } from '../../web/dist/index.js';
```

That module in turn loads its worker with
`new URL('./scanner.worker.js', import.meta.url)`, which resolves relative
to `web/dist/`. Both resolve correctly in any browser with no build step —
you just need a static server whose root is at (or above) the repo root, so
`/web/dist/...` is reachable. Serving only `sample/web/` would 404 on the
`../../` import.

## Run

```bash
cd ../../web && npm install && npm run build   # produce web/dist (one-time)
cd ../sample/web
npm install                                     # pulls in Tesseract.js, for card OCR
node serve.mjs                                  # zero-dependency static server
```

Open the printed URL (`http://localhost:5173/sample/web/index.html`) in a
browser. `localhost` counts as a secure context, so camera access works over
plain HTTP there — it would not on a non-localhost hostname or IP.

Click **Start camera**, point it at a barcode (QR, Code128, EAN-13, PDF417,
Data Matrix, or ITF) with some text nearby, and watch the log and the
"Last vouchers" panel. Point it at a printed card number and watch the
"Last card" panel.

## Card OCR

Browsers have no built-in text recognizer, so `FintechWebScanner` takes OCR
as a pluggable module (`ocrModuleUrl`, exporting
`recognize(ImageData) => OcrLine[]`). This sample wires in
`ocr-tesseract.js`, which wraps [Tesseract.js](https://github.com/naptha/tesseract.js)
(an open-source WASM OCR engine) to implement that contract — see that
file for the ~30 lines doing the work, mainly converting the `ImageData`
AegisScan hands it into the `OffscreenCanvas` Tesseract.js expects, and
flattening its block→paragraph→line result into `OcrLine[]`.

Notes:
- First recognition in a fresh browser profile downloads Tesseract's WASM
  engine + English trained data from its own default CDN (a few MB,
  cached afterward) — needs network on first use, not after.
- Tesseract.js is a general-purpose OCR engine, not tuned for cards the
  way Apple Vision/Google ML Kit are — expect it to be slower and less
  accurate, especially on small/stylized card fonts. Good lighting, a
  large flat printed test number, and holding the camera steady help a lot.
- This is a real, working OCR integration, not a stub — but it's also not
  what you'd ship to production as-is; evaluate accuracy/performance
  against your actual card fonts before relying on it.

Voucher barcode detection needs no extra setup: it uses the native
[`BarcodeDetector`](https://developer.mozilla.org/en-US/docs/Web/API/Barcode_Detection_API)
where available (e.g. Chrome/Edge on Android and recent desktop builds), and
falls back to `barcodeFallbackUrl` (a WASM/JS module exporting
`detect(ImageData)`) otherwise.

## What to copy into your app

- `FintechWebScanner.isSupported()` before constructing anything — it checks
  secure context, `getUserMedia`, Workers, and `createImageBitmap` without
  prompting for permission.
- `onCard`'s `card.digits` is a `Uint8Array` that's zeroed right after your
  callback returns. Read `brand`/`last4` for display; send `digits` straight
  to your tokenizer/HSM call inside the callback, synchronously.
- Always pair `start()` with `stop()` (e.g. on page unload) — it releases
  the camera track and terminates the worker.
