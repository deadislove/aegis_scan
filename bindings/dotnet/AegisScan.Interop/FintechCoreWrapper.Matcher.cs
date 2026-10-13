// Copyright 2026 AegisScan Authors. All rights reserved.
using System;
using System.Buffers;
using System.Collections.Generic;
using System.Security.Cryptography;
using System.Text;

namespace AegisScan.Interop;

public sealed partial class FintechCoreWrapper
{
    /// <summary>One-to-one association of barcodes with nearby text labels.</summary>
    public IReadOnlyList<MatchResult> MatchBarcodesWithText(IReadOnlyList<BarcodeItem> barcodes,
                                                           IReadOnlyList<TextItem> texts)
    {
        ObjectDisposedException.ThrowIf(_matcher.IsClosed, this);
        if (barcodes.Count == 0 || texts.Count == 0) return Array.Empty<MatchResult>();
        lock (_matchLock) return MatchCore(barcodes, texts);
    }

    private unsafe IReadOnlyList<MatchResult> MatchCore(IReadOnlyList<BarcodeItem> barcodes,
                                                       IReadOnlyList<TextItem> texts)
    {
        // One pooled UTF-8 arena for all strings, pinned only for the call.
        int total = 0;
        for (int i = 0; i < barcodes.Count; i++) total += Encoding.UTF8.GetMaxByteCount(barcodes[i].Payload.Length);
        for (int i = 0; i < texts.Count; i++) total += Encoding.UTF8.GetMaxByteCount(texts[i].Text.Length);
        byte[] arena = ArrayPool<byte>.Shared.Rent(Math.Max(1, total));
        var items = new AegisItem[barcodes.Count + texts.Count];
        var output = new AegisMatch[Math.Min(barcodes.Count, texts.Count)];
        try
        {
            // ArrayPool<T>.Rent is virtual; its "at least N bytes" contract
            // isn't visible to static analysis, so the pointer arithmetic
            // below on a too-small buffer would read/write out of bounds.
            // Validate explicitly rather than trusting the call result.
            if (arena.Length < total)
            {
                throw new InvalidOperationException(
                    $"ArrayPool<byte>.Shared.Rent returned {arena.Length} bytes, less than the {total} requested.");
            }
            fixed (byte* basePtr = arena)
            fixed (AegisItem* itemPtr = items)
            fixed (AegisMatch* outPtr = output)
            {
                int offset = 0;
                for (int i = 0; i < items.Length; i++)
                {
                    bool isBarcode = i < barcodes.Count;
                    string s = isBarcode ? barcodes[i].Payload : texts[i - barcodes.Count].Text;
                    int n = Encoding.UTF8.GetBytes(s, arena.AsSpan(offset));
                    // &arena[offset] (ldelema) is a bounds-checked array-element
                    // address -- the runtime throws IndexOutOfRangeException if
                    // offset is invalid, unlike `basePtr + offset`, which is raw
                    // pointer arithmetic with no check at all. This is what
                    // CodeQL's cs/unvalidated-local-pointer-arithmetic actually
                    // flags: the `+` operator on a pointer derived from a virtual
                    // call (ArrayPool<T>.Rent), not merely the absence of a
                    // nearby `if`. The nested `fixed` is only syntax (C# requires
                    // `&arr[i]` as a fixed-statement initializer, CS0212); the
                    // outer `basePtr` fixed above already pins `arena` for this
                    // whole method, so the resulting pointer stays valid past
                    // this block. n == 0 needs no address into arena at all
                    // (TextLen 0 means native code never reads through it), so
                    // it routes to the plain base pointer instead of indexing
                    // one-past-the-end.
                    byte* elementPtr;
                    if (n > 0)
                    {
                        fixed (byte* p = &arena[offset]) { elementPtr = p; }
                    }
                    else
                    {
                        elementPtr = basePtr;
                    }
                    items[i] = new AegisItem
                    {
                        Text = elementPtr,
                        TextLen = n,
                        Box = isBarcode ? barcodes[i].Box : texts[i - barcodes.Count].Box,
                    };
                    offset += n;
                }
                int count;
                int status = NativeMethods.MatcherMatch(_matcher, itemPtr, barcodes.Count,
                    itemPtr + barcodes.Count, texts.Count, outPtr, output.Length, &count);
                if (status != NativeMethods.Ok) throw new FintechCoreException(status, "aegis_matcher_match");
                var results = new MatchResult[count];
                for (int i = 0; i < count; i++)
                {
                    AegisMatch m = output[i];
                    results[i] = new MatchResult(barcodes[m.BarcodeIndex], texts[m.TextIndex].Text,
                                                 m.NormalizedDistance, m.Alignment);
                }
                return results;
            }
        }
        finally
        {
            CryptographicOperations.ZeroMemory(arena.AsSpan(0, Math.Min(total, arena.Length)));
            ArrayPool<byte>.Shared.Return(arena);
        }
    }
}
