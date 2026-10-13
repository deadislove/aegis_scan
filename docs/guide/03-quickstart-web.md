# Quickstart: Web

Full technical detail: `docs/technical/04-platform-web.md`. Runnable
sample: `sample/web/` (zero-build static page — `node serve.mjs`, see its
`README.md`).

## 1. Build and install

```bash
cd web
npm install
npm run build   # produces dist/index.js, dist/scanner.worker.js, ...
```

## 2. Point it at a `<video>` element

```ts
import { FintechWebScanner, ScannerError } from '@aegisscan/web';

const support = FintechWebScanner.isSupported();
if (!support.supported) {
  console.warn('Cannot scan:', support.reason);
}

const scanner = new FintechWebScanner({
  video: document.querySelector('video')!,
  onCard: (card) => {
    // card.digits is zeroed right after this callback returns -- send it
    // to your tokenizer/HSM call here, synchronously. card.expiry/
    // cardholderName are best-effort (no checksum backs them, unlike the PAN).
    statusEl.textContent = `${card.brand} ****${card.last4} exp ${card.expiry} ${card.cardholderName}`;
  },
  onVouchers: (vouchers) => {
    statusEl.textContent = vouchers.map(v => v.payload).join(', ');
  },
  onError: (err: ScannerError) => console.warn(err.code, err.message),
});

startButton.onclick = () => scanner.start();
stopButton.onclick = () => scanner.stop();
```

This must run in a secure context (`https://` or `localhost`) — camera
access fails otherwise.

## 3. Barcode scanning works immediately; card OCR needs a module

Voucher barcodes use the browser's native `BarcodeDetector` out of the box.
Card OCR does not — browsers have no built-in text recognizer, so pass
`ocrModuleUrl` pointing at a module exporting `recognize(image: ImageData)`.
Without it, `onCard` never fires, but nothing breaks.

`sample/web/ocr-tesseract.js` is a complete, working example of this
contract backed by [Tesseract.js](https://github.com/naptha/tesseract.js)
(open-source WASM OCR) — copy that file's pattern (mainly: convert the
`ImageData` to an `OffscreenCanvas`, since that's what Tesseract.js
accepts, then flatten its block→paragraph→line result into `OcrLine[]`)
rather than starting from scratch. It's real and verified against the
core's `extractCard` function, but Tesseract.js is a general-purpose
engine, not tuned for cards — evaluate accuracy against your own card
fonts before shipping it.

## 4. Without a camera (text you already have)

```ts
import { extractCard, extractCardDetails } from '@aegisscan/web';

const pan = extractCard(ocrText);
if (pan) {
  console.log(pan.brand, pan.last4);
  pan.wipe();
}

// Also wants expiry/cardholder name from the same OCR pass:
const details = extractCardDetails(ocrText);
if (details) {
  console.log(details.expiry, details.cardholderName);  // e.g. { month: 12, year: 2029 } "JOHN DOE"
  details.wipe();
}
```

## Tuning

`detectCards`, `detectVouchers`, `maxFps` (default 30),
`requiredConsecutiveCardHits` (default 2), `frameTimeoutMs` (default 5000),
`analysisScale` (downscale before OCR/fallback barcode detection — native
`BarcodeDetector` still runs at full resolution regardless).

## Must-not-do

- Don't bundle the worker through a step that rewrites `import.meta.url` in
  a way that breaks `new URL('./scanner.worker.js', import.meta.url)` —
  most modern bundlers (Vite, esbuild, webpack 5) handle this pattern
  correctly, but double-check after any bundler config change.
- Don't hold onto `card.digits` past the `onCard` callback.
