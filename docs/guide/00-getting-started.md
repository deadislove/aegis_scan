# Getting Started

AegisScan scans payment cards and voucher barcodes directly on-device —
nothing touches disk, nothing gets logged, nothing leaves the device unless
your own code sends it somewhere. One core engine, five platform SDKs.

## Pick your platform

| Platform | Quickstart | Sample you can run today |
|---|---|---|
| iOS (Swift) | [01-quickstart-ios.md](01-quickstart-ios.md) | `sample/ios/CoreDemo` (`swift run`) |
| Android (Kotlin) | [02-quickstart-android.md](02-quickstart-android.md) | `sample/android` (`./gradlew :sample-android:assembleDebug`) |
| Web (TypeScript) | [03-quickstart-web.md](03-quickstart-web.md) | `sample/web` (`node serve.mjs`) |
| .NET 8 (C#) | [04-quickstart-dotnet.md](04-quickstart-dotnet.md) | `sample/csharp` (`dotnet run`) |
| Java 21+ | [05-quickstart-java.md](05-quickstart-java.md) | `sample/java` (`make run`) |

Each quickstart is "copy this into your app" level detail. For *why*
something is built the way it is, see `docs/technical/`. For the business
case, see [Business Value](06-business-value.md).

## What every platform guarantees

- **Zero disk.** No file, cache, `UserDefaults`, `SharedPreferences`,
  `localStorage`, or IndexedDB access — ever — for frames, OCR text, or
  card numbers.
- **Wipeable PANs.** The card number is handed to your callback once, in a
  buffer that's zeroed the instant your callback returns. Printing it
  anywhere shows `****1234`, never the full number.
- **Instant frame dropping.** If the previous frame is still being
  analyzed, a new one is dropped immediately rather than queued — latency
  never grows with a backlog.
- **Typed errors, never crashes.** A denied camera permission, a corrupt
  frame, or an unsupported browser all produce a specific error your code
  can branch on.

## The one rule that matters most

**Do whatever you need to do with the card number *inside* the
callback/delegate/listener, synchronously.** Read `last4`/`brand` for
display, or hand the digits straight to your tokenizer/HSM call — then let
the SDK wipe it. Don't store the PAN object, don't pass it to another
thread, don't await anything with it still open. Every sample under
`sample/` marks the exact line this applies to with a comment.

## Next

Jump to your platform's quickstart above, or read
[Business Value](06-business-value.md) first if you're evaluating whether
AegisScan is the right fit.
