// Copyright 2026 AegisScan Authors. All rights reserved.
using System;
using System.Runtime.InteropServices;

namespace AegisScan.Interop;

/// <summary>Mirrors <c>aegis_box</c>.</summary>
[StructLayout(LayoutKind.Sequential)]
public struct AegisBox
{
    public float X, Y, Width, Height;

    public AegisBox(float x, float y, float width, float height)
    {
        X = x; Y = y; Width = width; Height = height;
    }
}

/// <summary>Mirrors <c>aegis_item</c> (borrowed UTF-8 pointer + box).</summary>
[StructLayout(LayoutKind.Sequential)]
internal unsafe struct AegisItem
{
    public byte* Text;
    public int TextLen;
    public AegisBox Box;
}

/// <summary>Mirrors <c>aegis_match</c>.</summary>
[StructLayout(LayoutKind.Sequential)]
internal struct AegisMatch
{
    public int BarcodeIndex;
    public int TextIndex;
    public float Score;
    public float NormalizedDistance;
    public int OverlapChars;
    public int Alignment;
}

/// <summary>Mirrors <c>aegis_frame_metrics</c>.</summary>
[StructLayout(LayoutKind.Sequential)]
public struct FrameMetrics
{
    public int Status;
    public float MeanLuma;
    public float FocusScore;
    public readonly bool IsOk => Status == 0;
}

/// <summary>
/// Source-generated P/Invoke stubs ([LibraryImport]): marshalling code is
/// produced at compile time, so there's no runtime IL stub generation and
/// the wrapper is Native AOT / trimming compatible. All signatures use
/// blittable types only, which makes the stubs direct calls.
/// </summary>
internal static unsafe partial class NativeMethods
{
    /// <summary>Resolved per platform: aegis_core.dll / libaegis_core.so / libaegis_core.dylib.</summary>
    internal const string Library = "aegis_core";

    internal const int Ok = 0;
    internal const int NotFound = 1;
    internal const int ErrBufferTooSmall = -2;
    internal const int PanBufferSize = 20;

    [LibraryImport(Library, EntryPoint = "aegis_version")]
    internal static partial byte* Version();

    [LibraryImport(Library, EntryPoint = "aegis_luhn_validate")]
    internal static partial int LuhnValidate(byte* digits, int len);

    [LibraryImport(Library, EntryPoint = "aegis_extract_pan_utf16")]
    internal static partial int ExtractPanUtf16(char* rawOcr, int rawLen, char* outDigits,
                                                int outCapacity, int* outLen, int* outBrand);

    [LibraryImport(Library, EntryPoint = "aegis_analyze_luma")]
    internal static partial int AnalyzeLuma(byte* data, long dataLen, int width, int height,
                                            int rowStride, FrameMetrics* outMetrics);

    [LibraryImport(Library, EntryPoint = "aegis_matcher_create")]
    internal static partial IntPtr MatcherCreate(float frameWidth, float frameHeight);

    [LibraryImport(Library, EntryPoint = "aegis_matcher_destroy")]
    internal static partial void MatcherDestroy(IntPtr matcher);

    [LibraryImport(Library, EntryPoint = "aegis_matcher_match")]
    internal static partial int MatcherMatch(SpatialMatcherHandle matcher, AegisItem* barcodes,
                                             int barcodeCount, AegisItem* texts, int textCount,
                                             AegisMatch* output, int outCapacity, int* outCount);

    [LibraryImport(Library, EntryPoint = "aegis_secure_zero")]
    internal static partial void SecureZero(void* data, long len);
}

/// <summary>
/// Owns a native <c>aegis_matcher_t*</c>. SafeHandle gives critical
/// finalization, protection against handle-recycling races (ref counting
/// during P/Invoke calls), and deterministic release through Dispose.
/// </summary>
public sealed class SpatialMatcherHandle : SafeHandle
{
    public SpatialMatcherHandle() : base(IntPtr.Zero, ownsHandle: true) { }

    public override bool IsInvalid => handle == IntPtr.Zero;

    protected override bool ReleaseHandle()
    {
        NativeMethods.MatcherDestroy(handle);
        return true;
    }

    internal static SpatialMatcherHandle Create(float frameWidth, float frameHeight)
    {
        var h = new SpatialMatcherHandle();
        h.SetHandle(NativeMethods.MatcherCreate(frameWidth, frameHeight));
        if (h.IsInvalid)
        {
            h.Dispose();
            throw new OutOfMemoryException("aegis_matcher_create failed");
        }
        return h;
    }
}
