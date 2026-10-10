// AegisScan .NET sample: everything a POS / MAUI integrator needs to call
// the native core from C#. Run with `dotnet run` from this directory.
using System.Diagnostics;
using AegisScan.Interop;

if (args.Length > 0 && args[0] == "--camera")
{
    RunCameraLoop();
    return;
}

Console.WriteLine($"AegisScan core version: {FintechCoreWrapper.Version}");
Console.WriteLine();

// 1. Plain Luhn check -------------------------------------------------------
const string testPan = "4111111111111111"; // Visa test number, passes Luhn.
Console.WriteLine($"ValidateLuhn(\"{testPan}\") = {FintechCoreWrapper.ValidateLuhn(testPan)}");
Console.WriteLine();

// 2. OCR repair + extraction -------------------------------------------------
// Pass real camera-recognized text as an argument (e.g. from the macOS
// Camera Harness -- see ../../macos/CameraHarness -- via
// `dotnet run -- "$(pbpaste)"`) to test this against a real scan instead of
// the built-in demo string below.
string ocrText = args.Length > 0 ? string.Join(' ', args)
    // A couple of characters misread as letters ('I'/'l' for '1'); the
    // repair map fixes them, but the core still requires most digits to be
    // genuine (it refuses to "rescue" a mostly-garbled string) so only
    // light noise works.
    : "CARD NO: 4111-111I-1111-111l\nEXP 12/29 CVV 123";
Console.WriteLine(args.Length > 0 ? "Using OCR text from the command line:" : "Using built-in demo OCR text:");
Console.WriteLine($"  \"{ocrText}\"");
Console.WriteLine();
using SecurePan? pan = FintechCoreWrapper.ExtractPan(ocrText);
if (pan is null)
{
    Console.WriteLine("ExtractPan: no valid PAN found in the OCR text.");
}
else
{
    // Never call ToString() in real code paths that could be logged; this is
    // just to show the redacted output is safe for error messages.
    Console.WriteLine($"ExtractPan found: {pan} (brand={pan.Brand}, length={pan.Length})");
    pan.UseDigits(static digits =>
    {
        Console.WriteLine($"  Digits available via zero-copy span, length={digits.Length}");
        return 0;
    });
} // Disposing wipes the digits here, deterministically.
Console.WriteLine();

// 3. Frame quality check (synthetic luma plane) -----------------------------
const int width = 64, height = 64;
byte[] luma = new byte[width * height];
Array.Fill(luma, (byte)128); // Flat mid-gray frame.
FrameMetrics metrics = FintechCoreWrapper.AnalyzeLuma(luma, width, height, width);
Console.WriteLine($"AnalyzeLuma: ok={metrics.IsOk} meanLuma={metrics.MeanLuma:F1} focusScore={metrics.FocusScore:F3}");
Console.WriteLine();

// 4. Voucher barcode <-> printed text spatial matching -----------------------
using var wrapper = new FintechCoreWrapper(frameWidth: 1080, frameHeight: 1920);
var barcodes = new List<BarcodeItem>
{
    new("099123456789", new AegisBox(100, 800, 300, 80)),
};
var texts = new List<TextItem>
{
    new("THANK YOU FOR SHOPPING", new AegisBox(80, 200, 400, 40)),
    new("VOUCHER 099123456789", new AegisBox(100, 720, 320, 50)),
};
IReadOnlyList<MatchResult> matches = wrapper.MatchBarcodesWithText(barcodes, texts);
Console.WriteLine($"MatchBarcodesWithText: {matches.Count} match(es)");
foreach (MatchResult m in matches)
{
    Console.WriteLine($"  barcode \"{m.Barcode.Payload}\" <-> text \"{m.AssociatedText}\" " +
                       $"(distance={m.NormalizedDistance:F3}, alignment={m.Alignment})");
}

// 5. Live camera mode (`dotnet run -- --camera`) -----------------------------
// Opens the Mac's webcam directly from this C# program -- no separate app,
// no clipboard bridge. Pixel capture and text/barcode recognition are NOT
// reimplemented here (that's a platform Vision/ML Kit's job on iOS/Android,
// and would be a multi-year effort to build from scratch); instead this
// shells out to two small, well-known CLI tools that do exactly that:
//   imagesnap -- grabs a frame from the webcam (https://github.com/rharder/imagesnap)
//   tesseract -- OCR engine (same engine the web sample uses, via WASM there)
//   zbarimg   -- barcode decoder
// Every frame's OCR/barcode output is then fed through this SDK's own real
// ExtractPan/Luhn/repair logic -- the actual thing this sample demonstrates.
static void RunCameraLoop()
{
    Console.WriteLine("Live camera mode: capturing from this Mac's webcam, running real OCR");
    Console.WriteLine("(tesseract) and barcode decoding (zbarimg) on each frame, then feeding the");
    Console.WriteLine("result through this SDK's real ExtractPan/Luhn logic. Press Ctrl+C to stop.");
    Console.WriteLine();

    RequireTool("imagesnap", "brew install imagesnap");
    RequireTool("tesseract", "brew install tesseract");
    RequireTool("zbarimg", "brew install zbar");

    string tempDir = Path.Combine(Path.GetTempPath(), "aegisscan-camera-sample");
    Directory.CreateDirectory(tempDir);
    string framePath = Path.Combine(tempDir, "frame.jpg");

    using var stopSignal = new SemaphoreSlim(0);
    Console.CancelKeyPress += (_, e) =>
    {
        e.Cancel = true;
        stopSignal.Release();
    };

    try
    {
        int frameNum = 0;
        while (!stopSignal.Wait(0))
        {
            frameNum++;
            RunProcess("imagesnap", "-w", "1", "-q", framePath);
            if (!File.Exists(framePath))
            {
                Console.WriteLine($"[{frameNum}] capture failed (no camera? permission denied?), retrying...");
                continue;
            }

            string ocrText = RunProcessCapture("tesseract",
                framePath, "stdout", "-c", "tessedit_char_whitelist=0123456789");
            string barcodeText = RunProcessCapture("zbarimg", "--raw", "-q", framePath).Trim();
            File.Delete(framePath);

            using SecurePan? framePan = string.IsNullOrWhiteSpace(ocrText) ? null : FintechCoreWrapper.ExtractPan(ocrText);
            if (framePan is not null)
            {
                Console.WriteLine($"[{frameNum}] CARD DETECTED: {framePan} (brand={framePan.Brand})");
            }
            else if (barcodeText.Length > 0)
            {
                Console.WriteLine($"[{frameNum}] barcode detected: {barcodeText}");
            }
            else
            {
                string preview = ocrText.Replace('\n', ' ').Trim();
                if (preview.Length > 40) preview = preview[..40] + "...";
                Console.WriteLine($"[{frameNum}] no card/barcode yet (OCR saw: \"{preview}\")");
            }

            stopSignal.Wait(500);
        }
    }
    finally
    {
        Console.WriteLine("Stopped.");
        if (Directory.Exists(tempDir)) Directory.Delete(tempDir, recursive: true);
    }
}

static void RequireTool(string name, string installHint)
{
    try
    {
        using Process? p = Process.Start(new ProcessStartInfo(name, "--version")
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        });
        p?.WaitForExit();
    }
    catch (System.ComponentModel.Win32Exception)
    {
        Console.Error.WriteLine($"'{name}' not found on PATH. Install it with: {installHint}");
        Environment.Exit(1);
    }
}

static void RunProcess(string fileName, params string[] arguments)
{
    var psi = new ProcessStartInfo(fileName) { RedirectStandardOutput = true, RedirectStandardError = true };
    foreach (string a in arguments) psi.ArgumentList.Add(a);
    using Process process = Process.Start(psi)!;
    process.WaitForExit();
}

static string RunProcessCapture(string fileName, params string[] arguments)
{
    var psi = new ProcessStartInfo(fileName) { RedirectStandardOutput = true, RedirectStandardError = true };
    foreach (string a in arguments) psi.ArgumentList.Add(a);
    using Process process = Process.Start(psi)!;
    string stdout = process.StandardOutput.ReadToEnd();
    process.StandardError.ReadToEnd();
    process.WaitForExit();
    return stdout;
}
