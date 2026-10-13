// AegisScan Web sample. Imports the built SDK directly -- no bundler.
// See README.md for why this needs to be served from the repo root.
import { FintechWebScanner, ScannerError } from '../../web/dist/index.js';

const video = document.getElementById('video');
const startBtn = document.getElementById('startBtn');
const stopBtn = document.getElementById('stopBtn');
const logEl = document.getElementById('log');
const cardOut = document.getElementById('cardOut');
const voucherOut = document.getElementById('voucherOut');

function log(msg) {
  const line = `[${new Date().toLocaleTimeString()}] ${msg}`;
  logEl.textContent += line + '\n';
  logEl.scrollTop = logEl.scrollHeight;
  console.log(line);
}

const support = FintechWebScanner.isSupported();
if (!support.supported) {
  log(`Unsupported browser/context: ${support.reason}`);
  startBtn.disabled = true;
}

const scanner = new FintechWebScanner({
  video,
  detectCards: true,
  detectVouchers: true,
  // Browsers have no built-in text recognizer, so card OCR is pluggable --
  // this points at a Tesseract.js-backed module (see ocr-tesseract.js and
  // README.md's "Card OCR" section). Requires `npm install` in this
  // directory once. Barcode detection needs no extra setup.
  ocrModuleUrl: '/sample/web/ocr-tesseract.js',
  onReady: (caps) => log(`worker ready: ${JSON.stringify(caps)}`),
  onCard: (card) => {
    // `card.digits` is wiped right after this callback returns -- read what
    // you need (never the raw digits themselves) synchronously, here.
    let summary = `${card.brand} ****${card.last4}`;
    if (card.expiry) summary += ` exp ${card.expiry.month}/${card.expiry.year}`;
    if (card.cardholderName) summary += ` (${card.cardholderName})`;
    cardOut.textContent = summary;
    log(`card detected: ${summary}`);
  },
  onVouchers: (vouchers) => {
    voucherOut.textContent = vouchers.map((v) => `${v.payload} (${v.associatedText})`).join(', ');
    log(`${vouchers.length} voucher match(es)`);
  },
  onError: (err) => {
    const recoverable = err instanceof ScannerError && err.recoverable;
    log(`error [${err.code}]${recoverable ? ' (recoverable)' : ''}: ${err.message}`);
  },
});

startBtn.addEventListener('click', async () => {
  startBtn.disabled = true;
  try {
    await scanner.start();
    stopBtn.disabled = false;
    log('camera started');
  } catch (err) {
    log(`failed to start: ${err.message}`);
    startBtn.disabled = false;
  }
});

stopBtn.addEventListener('click', () => {
  scanner.stop();
  stopBtn.disabled = true;
  startBtn.disabled = false;
  log('camera stopped');
});

setInterval(() => {
  if (!stopBtn.disabled) {
    const s = scanner.getStats();
    log(`stats: captured=${s.framesCaptured} dropped=${s.framesDropped} ` +
        `processed=${s.framesProcessed} failed=${s.framesFailed} lastMs=${s.lastTotalMs.toFixed(1)}`);
  }
}, 5000);
