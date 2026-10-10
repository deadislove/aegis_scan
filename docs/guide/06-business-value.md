# Business Value

This doc is for the person deciding *whether* to adopt AegisScan, not how
to call its API — see `docs/guide/00-getting-started.md` for that.

## The problem

Any app that needs a customer to get a card number or a voucher code into
a form today usually does one of three things, each with a real cost:

1. **Manual typing.** Slow, and the single biggest source of checkout
   abandonment and typo-driven failed transactions in card-not-present
   flows.
2. **Cloud OCR.** Works, but means uploading a photo of a payment card to a
   third-party API — which immediately pulls that vendor, and your
   transmission path to it, into your PCI-DSS assessment scope, adds
   per-scan API cost, and adds network latency (and a hard failure mode
   when the user has no connectivity).
3. **Building it yourself.** OCR + Luhn + brand detection + barcode
   matching, correctly, on five platforms, with real back-pressure and
   memory-wiping discipline, is a multi-month effort per platform for a
   team that isn't already expert in each one's camera/vision stack — and
   an easy place to introduce exactly the kind of bug (a cached frame, a
   stray log line) that turns into a compliance finding.

## What AegisScan changes

**Scanning happens entirely on-device, in RAM, and nothing is ever
written to disk or logged.** That one property is the basis for everything
below.

### 1. Smaller PCI-DSS footprint

Because the card number never leaves the device as an image and is never
persisted, the SDK itself touches none of the storage/logging surface that
usually drives PCI-DSS scope up. It doesn't do your compliance program for
you — you still need to handle what you *do* with the number after the
callback — but it removes "did we accidentally cache or log a PAN" as a
source of findings, and removes a third-party cloud-OCR vendor from your
data flow diagram entirely. See `docs/technical/07-security-pci-dss.md` for
exactly what's enforced and what remains your responsibility.

### 2. No per-scan cost, no connectivity dependency

Cloud OCR is billed per call and fails (or queues) without a network
connection. On-device scanning has neither problem: cost is fixed
(engineering + the SDK), and it works in a basement parking garage or on a
plane exactly as well as anywhere else — relevant for POS hardware, kiosk
checkouts, and field sales apps.

### 3. Faster user-facing experience

A local scan is bounded by on-device inference time (tens of
milliseconds), not a round trip. Combined with the back-pressure design
(never queuing frames, 30 FPS target, sub-15% CPU target), the user sees a
responsive live scan, not a spinner.

### 4. One engineering investment, five platforms

The Luhn/OCR-repair/brand-detection/spatial-matching logic is specified and
tested once (the C++ core and its test vectors — see
`docs/technical/01-core-engine.md`), then ported or linked identically into
iOS, Android, Web, .NET, and Java. A product team shipping on multiple
platforms gets consistent detection behavior everywhere without five
independent implementations to keep in sync, five sets of edge-case bugs,
or five different "which digits got misread" support tickets.

## Where this fits

- **Payment/fintech apps**: "add a card" flows (card-on-file setup),
  in-app payments, remittance apps.
- **POS / kiosk / field sales**: card capture on hardware or tablets with
  intermittent connectivity.
- **Retail / loyalty**: voucher and coupon barcode redemption, linking a
  scanned barcode to the printed terms/amount next to it.
- **Expense / receipt apps**: extracting a card's brand and last 4 for
  expense-report matching without ever handling the full PAN.

## What it does not do

AegisScan scans and validates — it does not tokenize, transmit, store, or
process a payment. It hands your application a validated card number (via
a buffer that's wiped the instant your callback returns) and the brand/
last-4 that are safe to display; what you do with it next (send it to a
payment gateway, a tokenization service, your own backend) is your
integration's responsibility, same as it would be with manual entry. See
each platform's "Must-not-do" section in the quickstarts for exactly where
that call belongs.

## Evaluating further

- Technical deep dive: `docs/technical/00-architecture.md`
- Run a sample in under five minutes: pick your platform from
  `docs/guide/00-getting-started.md`
- Common questions: `docs/guide/07-faq.md`
