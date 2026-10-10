// Copyright 2026 AegisScan Authors. All rights reserved.
using System;
using System.Buffers;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace AegisScan.Interop;

public sealed record BarcodeItem(string Payload, AegisBox Box);
public sealed record TextItem(string Text, AegisBox Box);
public sealed record MatchResult(BarcodeItem Barcode, string AssociatedText, float NormalizedDistance, int Alignment);

/// <summary>Native call failed. Carries only a status code, never input data or addresses.</summary>
public sealed class FintechCoreException(int status, string operation)
    : Exception($"{operation} failed with status {status}")
{
    public int Status { get; } = status;
}

/// <summary>
/// .NET 8 wrapper over the AegisScan C++20 core.
/// <list type="bullet">
/// <item>Source-generated <c>[LibraryImport]</c> stubs with blittable
///   signatures only, so it's Native AOT and trimming safe.</item>
/// <item>Zero-copy: text goes in as a pinned UTF-16 span, images as
///   <c>ReadOnlySpan&lt;byte&gt;</c> or <c>ReadOnlyMemory&lt;byte&gt;</c>.</item>
/// <item>The native matcher is owned by a SafeHandle; <see cref="Dispose"/>
///   frees it right away, without waiting for the GC.</item>
/// <item>PANs come back as <see cref="SecurePan"/>. Scratch buffers are
///   wiped and nothing is logged.</item>
/// </list>
/// Stateless methods are thread-safe. Matching is serialized per instance.
/// </summary>
public sealed partial class FintechCoreWrapper : IDisposable
{
    private readonly SpatialMatcherHandle _matcher;
    private readonly object _matchLock = new();

    public FintechCoreWrapper(float frameWidth = 0, float frameHeight = 0)
    {
        _matcher = SpatialMatcherHandle.Create(frameWidth, frameHeight);
    }

    public static unsafe string Version
        => Marshal.PtrToStringUTF8((IntPtr)NativeMethods.Version()) ?? string.Empty;

    /// <summary>Standard Luhn Mod-10 check over ASCII digits.</summary>
    public static unsafe bool ValidateLuhn(ReadOnlySpan<char> digits)
    {
        if (digits.Length is < 2 or > 64) return false;
        Span<byte> ascii = stackalloc byte[digits.Length];
        try
        {
            for (int i = 0; i < digits.Length; i++)
            {
                ascii[i] = digits[i] < 0x80 ? (byte)digits[i] : (byte)'~';
            }
            fixed (byte* p = ascii) return NativeMethods.LuhnValidate(p, ascii.Length) == 1;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(ascii);
        }
    }

    /// <summary>
    /// Repairs OCR confusions and extracts the first valid PAN. The span is
    /// pinned and handed to the native UTF-16 entry point with no copy.
    /// </summary>
    /// <returns>A <see cref="SecurePan"/> (dispose it!), or null if none was found.</returns>
    public static unsafe SecurePan? ExtractPan(ReadOnlySpan<char> rawOcr)
    {
        Span<char> output = stackalloc char[NativeMethods.PanBufferSize];
        try
        {
            int len, brand, status;
            fixed (char* input = rawOcr)
            fixed (char* outPtr = output)
            {
                status = NativeMethods.ExtractPanUtf16(input, rawOcr.Length, outPtr, output.Length,
                                                       &len, &brand);
            }
            if (status == NativeMethods.NotFound) return null;
            if (status != NativeMethods.Ok) throw new FintechCoreException(status, "aegis_extract_pan_utf16");
            return new SecurePan(output[..len], (CardBrand)brand);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(MemoryMarshal.AsBytes(output));
        }
    }

    /// <summary>Validates and measures an 8-bit luma plane without copying it.</summary>
    public static unsafe FrameMetrics AnalyzeLuma(ReadOnlySpan<byte> luma, int width, int height, int rowStride)
    {
        FrameMetrics metrics;
        fixed (byte* p = luma)
        {
            int status = NativeMethods.AnalyzeLuma(p, luma.Length, width, height, rowStride, &metrics);
            if (status != NativeMethods.Ok) throw new FintechCoreException(status, "aegis_analyze_luma");
        }
        return metrics;
    }

    /// <summary>Overload for camera frames held in pooled / native memory.</summary>
    public static FrameMetrics AnalyzeLuma(ReadOnlyMemory<byte> luma, int width, int height, int rowStride)
        => AnalyzeLuma(luma.Span, width, height, rowStride);

    /// <summary>Array overload (resolves span/memory ambiguity; still zero-copy).</summary>
    public static FrameMetrics AnalyzeLuma(byte[] luma, int width, int height, int rowStride)
        => AnalyzeLuma(new ReadOnlySpan<byte>(luma), width, height, rowStride);

    /// <summary>Frees the native matcher immediately (idempotent).</summary>
    public void Dispose() => _matcher.Dispose();
}
