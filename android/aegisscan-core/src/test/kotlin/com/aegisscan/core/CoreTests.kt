// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Shared vectors, identical to the C++ inline GoogleTest suite. */
class LuhnValidatorTest {
    private fun extract(s: String) = LuhnValidator.repairAndExtract(s)

    @Test fun validate() {
        for (pan in listOf("4111111111111111", "4012888888881881", "4222222222222", "5555555555554444",
            "2223003122003222", "378282246310005", "3530111333300000")) {
            assertTrue(LuhnValidator.validate(pan), pan.take(4))
        }
        assertFalse(LuhnValidator.validate("4111111111111112"))
        assertFalse(LuhnValidator.validate(""))
        assertFalse(LuhnValidator.validate("4111 1111 1111 1111"))
    }

    @Test fun brands() {
        assertEquals(CardBrand.AMEX, LuhnValidator.detectBrand("371449635398431"))
        assertEquals(CardBrand.MASTERCARD, LuhnValidator.detectBrand("2720990000000007"))
        assertEquals(CardBrand.UNKNOWN, LuhnValidator.detectBrand("2721000000000000"))
        assertEquals(CardBrand.JCB, LuhnValidator.detectBrand("3530111333300000"))
        assertEquals(CardBrand.UNKNOWN, LuhnValidator.detectBrand("6011111111111117"))
    }

    @Test fun ocrEightAsB() = assertEquals("4012888888881881", extract("4012 B888 88B8 1881"))
    @Test fun ocrZeroAsODQ() = assertEquals("5105105105105100", extract("51O5 1D51 O51Q 51OO"))
    @Test fun ocrOneAsLI() = assertEquals("4111111111111111", extract("4lI1 |111 1i11 1111"))
    @Test fun ocrFiveTwoFour() {
        assertEquals("5555555555554444", extract("\$555 5555 5555 4444"))
        assertEquals("2223003122003222", extract("Z223 0031 Z200 3222"))
        assertEquals("4111111111111111", extract("A111 1111 1111 1111"))
    }

    @Test fun noisyText() {
        assertEquals("4111111111111111", extract("BANK\nCard No: 4111 1111 1111 1111\nVALID 12/28"))
        assertEquals("4111111111111111", extract("Ref 1234 4111 1111 1111 1111"))
        assertEquals("4111111111111111", extract("4111 1111 1111 1111 123"))
        assertEquals("4111111111111111", extract("994111111111111111199"))
    }

    @Test fun rejects() {
        for (s in listOf("", "HELLO WORLD", "4111 1111 1111 1112", "6011 1111 1111 1117", "4111 1111 1111",
            "**** **** **** 1111", "SOLO BOSS SOLO BOSS", "4111 1111 EXP 1111 1111", "4111 1111\n1111 1111",
            "1".repeat(100_000))) {
            assertNull(extract(s), "should reject input of length ${s.length}")
        }
    }

    @Test fun securePanRedactedAndWiped() {
        val pan = LuhnValidator.extractCard("3782 822463 10005")!!
        assertEquals(CardBrand.AMEX, pan.brand)
        assertEquals("0005", pan.last4)
        assertFalse(pan.toString().contains("378282"))
        pan.close()
        assertTrue(pan.isWiped)
        assertEquals(0, pan.length)
    }
}

class SpatialMatcherTest {
    private fun box(x: Number, y: Number, w: Number, h: Number) =
        BoundingBox(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())

    @Test fun matchesTextBelow() {
        val r = SpatialMatcher().matchBarcodesWithText(
            listOf(BarcodeItem("VCH-12345678", box(100, 100, 300, 80))),
            listOf(TextItem("Gift Voucher", box(100, 20, 200, 30)),
                TextItem("VCH 1234 5678", box(110, 190, 280, 30)),
                TextItem("12345678", box(900, 900, 100, 30))),
        )
        assertEquals(1, r.size)
        assertEquals("VCH 1234 5678", r[0].associatedText)
        assertEquals(Alignment.TEXT_BELOW, r[0].alignment)
    }

    @Test fun prefersVerticalOverCloserSideBySide() {
        val r = SpatialMatcher().matchBarcodesWithText(
            listOf(BarcodeItem("9876543210", box(100, 100, 200, 60))),
            listOf(TextItem("9876543210", box(300, 115, 40, 30)), TextItem("98765 43210", box(100, 250, 200, 30))),
        )
        assertEquals("98765 43210", r.single().associatedText)
    }

    @Test fun overlapRequiredAndOcrFolding() {
        val bc = listOf(BarcodeItem("AB1058", box(0, 0, 200, 60)))
        assertTrue(SpatialMatcher().matchBarcodesWithText(bc, listOf(TextItem("TOTAL DUE", box(0, 70, 200, 20)))).isEmpty())
        assertEquals("A8 IO5B", SpatialMatcher().matchBarcodesWithText(bc, listOf(TextItem("A8 IO5B", box(0, 70, 200, 20)))).single().associatedText)
    }

    @Test fun oneToOneAndInvalidBoxes() {
        val r = SpatialMatcher().matchBarcodesWithText(
            listOf(BarcodeItem("111122223333", box(0, 0, 200, 60)), BarcodeItem("444455556666", box(0, 200, 200, 60))),
            listOf(TextItem("4444 5555 6666", box(0, 270, 200, 20)), TextItem("1111 2222 3333", box(0, 70, 200, 20))),
        )
        assertEquals(listOf("1111 2222 3333", "4444 5555 6666"), r.map { it.associatedText })
        assertTrue(SpatialMatcher().matchBarcodesWithText(
            listOf(BarcodeItem("12345", box(Float.NaN, 0, 10, 10))), listOf(TextItem("12345", box(0, 12, 10, 10))),
        ).isEmpty())
        assertTrue(SpatialMatcher().matchBarcodesWithText(emptyList(), emptyList()).isEmpty())
    }
}
