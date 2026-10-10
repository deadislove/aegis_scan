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
                    items[i] = new AegisItem
                    {
                        Text = basePtr + offset,
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
