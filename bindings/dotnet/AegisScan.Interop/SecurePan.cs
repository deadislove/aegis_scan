// Copyright 2026 AegisScan Authors. All rights reserved.
using System;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Threading;

namespace AegisScan.Interop;

public enum CardBrand { Unknown = 0, Visa = 1, Mastercard = 2, Amex = 3, Jcb = 4 }

/// <summary>
/// A PAN in a wipeable buffer, used instead of <see cref="System.Security.SecureString"/>,
/// which Microsoft marks obsolete for new code because it gives no protection
/// beyond what a deterministic wipe does.
/// <list type="bullet">
/// <item>The digits live in a <b>pinned</b> array allocated on the Pinned
///   Object Heap (<see cref="GC.AllocateUninitializedArray{T}"/> with
///   pinned: true), so the GC can never copy them elsewhere and leave
///   unwiped remnants.</item>
/// <item><see cref="Dispose"/> zeroes them with
///   <see cref="CryptographicOperations.ZeroMemory"/>, which the JIT can't
///   optimize away.</item>
/// <item><see cref="ToString"/> is redacted. No serialization attributes are
///   applied, so the PAN can't end up in logs or persisted JSON.</item>
/// </list>
/// </summary>
public sealed class SecurePan : IDisposable
{
    private readonly char[] _digits;
    private readonly int _length;
    private int _disposed;

    internal SecurePan(ReadOnlySpan<char> digits, CardBrand brand)
    {
        _digits = GC.AllocateArray<char>(NativeMethods.PanBufferSize, pinned: true);
        digits.CopyTo(_digits);
        _length = digits.Length;
        Brand = brand;
        Last4 = _length >= 4 ? new string(_digits, _length - 4, 4) : string.Empty;
    }

    public CardBrand Brand { get; }

    /// <summary>Last four digits (safe to display under PCI-DSS 3.4).</summary>
    public string Last4 { get; }

    public bool IsDisposed => Volatile.Read(ref _disposed) != 0;

    public int Length => IsDisposed ? 0 : _length;

    /// <summary>
    /// Scoped, zero-copy access to the digits. Pass them straight to a
    /// tokenizer or HSM; don't turn them into a <c>string</c>.
    /// </summary>
    public TResult UseDigits<TResult>(ReadOnlySpanFunc<char, TResult> action)
    {
        ObjectDisposedException.ThrowIf(IsDisposed, this);
        return action(new ReadOnlySpan<char>(_digits, 0, _length));
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) == 0)
        {
            CryptographicOperations.ZeroMemory(MemoryMarshal.AsBytes(_digits.AsSpan()));
        }
    }

    public override string ToString() => $"SecurePan({Brand}, ****{Last4})";
}

public delegate TResult ReadOnlySpanFunc<T, out TResult>(ReadOnlySpan<T> span);
