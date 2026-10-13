// Copyright 2026 AegisScan Authors. All rights reserved.
using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using AegisScan.Interop;
using Xunit;

namespace AegisScan.Interop.Tests;

public class FintechCoreWrapperTests
{
    [Fact]
    public void VersionAndLuhn()
    {
        Assert.Equal("1.0.0", FintechCoreWrapper.Version);
        Assert.True(FintechCoreWrapper.ValidateLuhn("4111111111111111"));
        Assert.False(FintechCoreWrapper.ValidateLuhn("4111111111111112"));
        Assert.False(FintechCoreWrapper.ValidateLuhn(""));
    }

    [Theory]
    [InlineData("4012 B888 88B8 1881", "4012888888881881", CardBrand.Visa)]       // 8 <-> B
    [InlineData("51O5 1D51 O51Q 51OO", "5105105105105100", CardBrand.Mastercard)] // 0 <-> O/D/Q
    [InlineData("4lI1 |111 1i11 1111", "4111111111111111", CardBrand.Visa)]       // 1 <-> l/I/|
    [InlineData("3782 822463 1OOO5", "378282246310005", CardBrand.Amex)]
    public void ExtractPanRepairsOcr(string ocr, string expected, CardBrand brand)
    {
        using SecurePan? pan = FintechCoreWrapper.ExtractPan(ocr.AsSpan());
        Assert.NotNull(pan);
        Assert.Equal(brand, pan!.Brand);
        Assert.True(pan.UseDigits(d => d.SequenceEqual(expected)));
        Assert.DoesNotContain(expected[..6], pan.ToString());
    }

    [Fact]
    public void ExtractPanRejectsNoiseAndWipesOnDispose()
    {
        Assert.Null(FintechCoreWrapper.ExtractPan("SOLO BOSS SOLO BOSS"));
        Assert.Null(FintechCoreWrapper.ExtractPan(ReadOnlySpan<char>.Empty));
        var pan = FintechCoreWrapper.ExtractPan("5555 5555 5555 4444")!;
        Assert.Equal("4444", pan.Last4);
        pan.Dispose();
        pan.Dispose();  // Idempotent.
        Assert.True(pan.IsDisposed);
        Assert.Equal(0, pan.Length);
        Assert.Throws<ObjectDisposedException>(() => pan.UseDigits(d => d.Length));
    }

    [Fact]
    public void ExtractExpiryParsesAndRejectsInvalidMonths()
    {
        ExpiryDate? expiry = FintechCoreWrapper.ExtractExpiry("VALID THRU 12/29");
        Assert.Equal(12, expiry?.Month);
        Assert.Equal(2029, expiry?.Year);
        Assert.Null(FintechCoreWrapper.ExtractExpiry("4111111111111111"));
        Assert.Null(FintechCoreWrapper.ExtractExpiry("13/45"));  // Invalid month is skipped, not returned.
    }

    [Fact]
    public void ExtractCardholderNameFiltersBrandWords()
    {
        Assert.Equal("JOHN DOE",
            FintechCoreWrapper.ExtractCardholderName("BANK OF AEGIS\n4111111111111111\nJOHN DOE"));
        Assert.Null(FintechCoreWrapper.ExtractCardholderName("VISA PLATINUM\n4111111111111111"));
    }

    [Fact]
    public void ExtractCardDetailsAttachesExpiryAndName()
    {
        using SecurePan? pan = FintechCoreWrapper.ExtractCardDetails(
            "BANK OF AEGIS\n4111 1111 1111 1111\nVALID THRU 12/29\nJOHN DOE");
        Assert.NotNull(pan);
        Assert.True(pan!.UseDigits(d => d.SequenceEqual("4111111111111111")));
        Assert.Equal(12, pan.Expiry?.Month);
        Assert.Equal(2029, pan.Expiry?.Year);
        Assert.Equal("JOHN DOE", pan.CardholderName);
    }

    [Fact]
    public void ExtractMrzParsesValidTd3PassportSpecimen()
    {
        // ICAO Doc 9303 worked example ("ERIKSSON ANNA MARIA"), the standard
        // specimen used industry-wide for this purpose, not a real document.
        MrzResult? mrz = FintechCoreWrapper.ExtractMrz(
            "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\n" +
            "L898902C36UTO7408122F1204159ZE184226B<<<<<10");
        Assert.NotNull(mrz);
        Assert.Equal("L898902C3", mrz!.DocumentNumber);
        Assert.Equal("UTO", mrz.Nationality);
        Assert.Equal("740812", mrz.DateOfBirth);
        Assert.Equal("120415", mrz.ExpiryDate);
        Assert.Equal('F', mrz.Sex);
        Assert.Equal("ERIKSSON", mrz.Surname);
        Assert.Equal("ANNA MARIA", mrz.GivenNames);
        Assert.True(mrz.DocumentNumberCheckValid);
        Assert.True(mrz.DateOfBirthCheckValid);
        Assert.True(mrz.ExpiryCheckValid);
        Assert.True(mrz.CompositeCheckValid);
    }

    [Fact]
    public void ExtractMrzDetectsCorruptedCheckDigitAndRejectsNonMrz()
    {
        MrzResult? corrupted = FintechCoreWrapper.ExtractMrz(
            "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\n" +
            "L898902C36UTO7408122F1204159ZE184226B<<<<<11");
        Assert.NotNull(corrupted);
        Assert.True(corrupted!.DocumentNumberCheckValid); // Unaffected field.
        Assert.False(corrupted.CompositeCheckValid);

        Assert.Null(FintechCoreWrapper.ExtractMrz("JUST SOME ORDINARY TEXT"));
        Assert.Null(FintechCoreWrapper.ExtractMrz(""));
    }

    [Fact]
    public void AnalyzeLumaIsZeroCopyAndDefensive()
    {
        byte[] frame = Enumerable.Repeat((byte)128, 640 * 480).ToArray();
        Assert.True(FintechCoreWrapper.AnalyzeLuma(frame, 640, 480, 640).IsOk);
        Assert.Equal(4, FintechCoreWrapper.AnalyzeLuma(frame.AsSpan(0, 100), 640, 480, 640).Status);
        Assert.Equal(3, FintechCoreWrapper.AnalyzeLuma(frame, 640, 480, 320).Status);
        Assert.Equal(1, FintechCoreWrapper.AnalyzeLuma(ReadOnlySpan<byte>.Empty, 640, 480, 640).Status);
        Assert.Equal(6, FintechCoreWrapper.AnalyzeLuma(new ReadOnlyMemory<byte>(new byte[320 * 240]), 320, 240, 320).Status);
    }

    [Fact]
    public void MatcherAndDeterministicDispose()
    {
        var core = new FintechCoreWrapper(1920, 1080);
        var results = core.MatchBarcodesWithText(
            new[] { new BarcodeItem("VCH-12345678", new AegisBox(100, 100, 300, 80)) },
            new[] { new TextItem("Gift Voucher", new AegisBox(100, 20, 200, 30)),
                    new TextItem("VCH 1234 5678", new AegisBox(110, 190, 280, 30)) });
        Assert.Single(results);
        Assert.Equal("VCH 1234 5678", results[0].AssociatedText);
        Assert.Equal(0, results[0].Alignment);
        core.Dispose();
        core.Dispose();
        Assert.Throws<ObjectDisposedException>(() => core.MatchBarcodesWithText(
            new[] { new BarcodeItem("1", new AegisBox(0, 0, 1, 1)) }, new[] { new TextItem("1", new AegisBox(0, 0, 1, 1)) }));
    }

    [Fact]
    public void ConcurrentUseIsSafe()
    {
        using var core = new FintechCoreWrapper();
        var bc = new[] { new BarcodeItem("5012345678900", new AegisBox(0, 0, 200, 60)) };
        var tx = new[] { new TextItem("5 012345 678900", new AegisBox(0, 70, 200, 20)) };
        int ok = 0;
        Parallel.For(0, 2000, _ =>
        {
            using var pan = FintechCoreWrapper.ExtractPan("4111 1111 1111 1111");
            if (pan?.Length == 16 && core.MatchBarcodesWithText(bc, tx).Count == 1) Interlocked.Increment(ref ok);
        });
        Assert.Equal(2000, ok);
    }
}
