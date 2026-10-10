// AegisScan Web sample -- pluggable OCR module backed by Tesseract.js.
//
// Implements the contract scanner.worker.ts expects from `ocrModuleUrl`:
// a module exporting `recognize(image: ImageData) => Promise<OcrLine[]>`.
// See web/src/protocol.ts (OcrLine) and web/src/scanner.worker.ts
// (recognizeText) for the consumer side.
//
// Requires `npm install` in this directory once (pulls in tesseract.js).
// The WASM engine + English trained data are fetched from tesseract.js's
// own default CDN the first time recognize() runs, then cached by the
// browser -- there's no vendoring of multi-megabyte binaries in this repo.
// This file is a browser-targeted bundle with a DEFAULT export only
// (`export { tesseract_min as default }`) -- unlike the `tesseract.js`
// package's Node entry point (src/index.js, CommonJS), which is what
// `import { createWorker } from 'tesseract.js'` resolves to under Node and
// does expose a named `createWorker`. Importing this file the same way
// throws a SyntaxError at module-link time (no such named export), which
// scanner.worker.ts swallows silently (`catch { ocr = null }`) -- it just
// looks like OCR never turned on, no error anywhere. Use the default
// export's `.createWorker` instead.
import Tesseract from './node_modules/tesseract.js/dist/tesseract.esm.min.js';

let workerPromise = null;

function getWorker() {
  if (!workerPromise) {
    workerPromise = Tesseract.createWorker('eng');
  }
  return workerPromise;
}

/** ImageData isn't one of Tesseract's accepted input types; OffscreenCanvas is. */
function toOffscreenCanvas(image) {
  const canvas = new OffscreenCanvas(image.width, image.height);
  canvas.getContext('2d').putImageData(image, 0, 0);
  return canvas;
}

export async function recognize(image) {
  const worker = await getWorker();
  const canvas = toOffscreenCanvas(image);
  const { data } = await worker.recognize(canvas, {}, { blocks: true, text: false });
  const lines = (data.blocks ?? []).flatMap((block) =>
    block.paragraphs.flatMap((paragraph) =>
      paragraph.lines.map((line) => ({
        text: line.text.trim(),
        box: {
          x: line.bbox.x0,
          y: line.bbox.y0,
          width: line.bbox.x1 - line.bbox.x0,
          height: line.bbox.y1 - line.bbox.y0,
        },
      })),
    ),
  );
  return lines.filter((line) => line.text.length > 0);
}
