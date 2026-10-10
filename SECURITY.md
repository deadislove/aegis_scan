# Security Policy

AegisScan handles payment card data. Treat any vulnerability that could
affect the zero-disk guarantee, the PAN-wiping behavior, or the memory
safety of the native core as a priority report under this policy, even if
you're not sure it qualifies.

## Supported versions

This project is pre-1.0. Security fixes are made against the `main`
branch only; there are no maintained release branches yet.

| Version | Supported |
|---|---|
| `main` | ✅ |

## Reporting a vulnerability

**Do not open a public GitHub issue for a security vulnerability.**

Email **dawei.lin7689@gmail.com** with:

- A description of the vulnerability and its potential impact.
- Steps to reproduce it (a minimal code sample or test case helps a lot).
- The affected platform(s) (iOS / Android / Web / .NET / Java / the shared
  C++ core) and version/commit.

You should get an acknowledgment within **5 business days**. This is a
single-maintainer project, so please be patient on timelines — but every
report will get a response, and you'll be credited (unless you prefer to
stay anonymous) once a fix ships.

### What to expect

1. Acknowledgment of your report.
2. An assessment of impact and affected platforms.
3. A fix, developed privately.
4. Coordinated disclosure: a new release and a public advisory once a fix
   is available, crediting the reporter unless they ask not to be.

## Scope

In scope:

- `core/` — the shared C++20 engine and its C ABI.
- `ios/`, `android/`, `web/`, `bindings/dotnet/`, `bindings/java/` — the
  platform bindings.
- Anything that could cause a PAN, pixel buffer, or recognized OCR text to
  be persisted to disk, logged, or retained longer than a single callback.

Out of scope:

- `sample/` — example/demo code, not meant for production use as-is.
- Vulnerabilities in third-party dependencies (CameraX, ML Kit, Tesseract.js,
  etc.) — please report those directly to the relevant upstream project.
- Issues that require physical access to an already-compromised device.

## Design background

See `docs/technical/07-security-pci-dss.md` for how the zero-disk and
PAN-wiping guarantees are implemented on each platform — useful context
before filing a report.
