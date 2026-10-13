// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.core

/** Card scheme (values mirror the C++ core `CardBrand`). */
enum class CardBrand(val code: Int) { UNKNOWN(0), VISA(1), MASTERCARD(2), AMEX(3), JCB(4) }

/** A parsed MM/YY (or MM/YYYY) expiry, as printed on the card. */
data class ExpiryDate(val month: Int, val year: Int)

/**
 * PAN held in a wipeable `CharArray` (never a `String`, which is immutable,
 * may be interned, and can't be cleared).
 *
 * PCI-DSS: [toString] is redacted, the class is not `Serializable`/`Parcelable`,
 * and [close] zeroes the digits. Use it with `use { }` so the wipe is
 * deterministic and doesn't wait for the GC.
 */
class SecurePan internal constructor(
    private val digits: CharArray,
    val brand: CardBrand,
    /** Best-effort expiry, extracted from the same OCR text as the PAN. */
    val expiry: ExpiryDate? = null,
    /** Best-effort cardholder name, extracted from the same OCR text as the
     * PAN. Not PCI-DSS sensitive on its own, so it isn't wiped with [close]. */
    val cardholderName: String? = null,
) : AutoCloseable {
    /** Last four digits, safe to display (PCI-DSS 3.4). */
    val last4: String = if (digits.size >= 4) String(digits, digits.size - 4, 4) else ""

    @Volatile private var wiped = false

    val length: Int get() = if (wiped) 0 else digits.size
    val isWiped: Boolean get() = wiped

    /** Scoped access to the raw digits. Don't copy them into a `String`. */
    @Synchronized
    fun <R> withDigits(block: (CharArray) -> R): R {
        check(!wiped) { "SecurePan already wiped" }
        return block(digits)
    }

    @Synchronized
    fun contentEquals(other: SecurePan): Boolean =
        !wiped && !other.wiped && digits.contentEquals(other.digits)

    /** Zeroes the digits (idempotent). */
    @Synchronized
    override fun close() {
        digits.fill('\u0000')
        wiped = true
    }

    override fun toString(): String = "SecurePan($brand, ****$last4)"
}

/**
 * Kotlin port of `aegisscan::core::LuhnValidator` (bit-for-bit identical
 * behavior, verified by the shared test vectors). Stateless and thread-safe.
 * The hot path uses fixed-size `CharArray` scratch that's wiped before return.
 */
object LuhnValidator {
    const val MIN_PAN = 13
    const val MAX_PAN = 19
    private const val RUN_CAPACITY = 64
    private const val MAX_GROUPS = 32
    private val LENGTH_PREFERENCE = intArrayOf(16, 15, 19, 14, 13, 17, 18)

    /** Standard Luhn Mod-10 check (digits only, length >= 2). */
    fun validate(number: CharSequence): Boolean = validate(number, 0, number.length)

    private fun validate(s: CharSequence, start: Int, len: Int): Boolean {
        if (len < 2) return false
        var sum = 0
        var doubleIt = false
        for (i in start + len - 1 downTo start) {
            val c = s[i]
            if (c !in '0'..'9') return false
            var d = c - '0'
            if (doubleIt) { d *= 2; if (d > 9) d -= 9 }
            sum += d
            doubleIt = !doubleIt
        }
        return sum % 10 == 0
    }

    /** OCR confusion map: O D Q o→0, I l | i→1, Z z→2, S s $→5, B→8, A→4. */
    fun repairChar(c: Char): Char = when (c) {
        in '0'..'9' -> c
        'O', 'D', 'Q', 'o' -> '0'
        'I', 'l', '|', 'i' -> '1'
        'Z', 'z' -> '2'
        'S', 's', '$' -> '5'
        'B' -> '8'
        'A' -> '4'
        else -> '\u0000'
    }

    private fun isGroupSeparator(c: Char) = c == ' ' || c == '-' || c == '.' || c == '\t'

    private fun isHardBreak(c: Char) = when (c) {
        '\n', '\r', '\u000B', '\u000C', ':', ';', ',', '/', '\\', '#', '(', ')', '[', ']',
        '{', '}', '*', '=', '"', '\'' -> true
        else -> false
    }

    /** Brand from an all-digit PAN (IIN ranges + length). */
    fun detectBrand(d: CharSequence): CardBrand = detectBrand(d, 0, d.length)

    private fun detectBrand(d: CharSequence, start: Int, n: Int): CardBrand {
        if (n < MIN_PAN || n > MAX_PAN) return CardBrand.UNKNOWN
        for (i in start until start + n) if (d[i] !in '0'..'9') return CardBrand.UNKNOWN
        fun prefix(k: Int): Int { var v = 0; for (i in 0 until k) v = v * 10 + (d[start + i] - '0'); return v }
        val p1 = prefix(1); val p2 = prefix(2); val p4 = prefix(4)
        return when {
            p1 == 4 && (n == 13 || n == 16 || n == 19) -> CardBrand.VISA
            n == 16 && (p2 in 51..55 || p4 in 2221..2720) -> CardBrand.MASTERCARD
            n == 15 && (p2 == 34 || p2 == 37) -> CardBrand.AMEX
            n >= 16 && p4 in 3528..3589 -> CardBrand.JCB
            else -> CardBrand.UNKNOWN
        }
    }

    /** Spec API: repaired PAN as a `String`. Prefer [extractCard]. */
    fun repairAndExtract(rawOcr: CharSequence): String? =
        extractCard(rawOcr)?.use { pan -> pan.withDigits { String(it) } }

    private fun repairedDigit(c: Char): Int {
        val r = repairChar(c)
        return if (r in '0'..'9') r - '0' else -1
    }

    /**
     * Scans for the first MM/YY or MM/YYYY pattern in raw OCR text. Unlike
     * [extractCard], there's no checksum to validate an expiry date against --
     * a transaction reference or address elsewhere in the frame that happens
     * to look like "12/29" can't be distinguished from the real expiry. The
     * month range check (01-12) is the only guard against an obviously-wrong
     * match.
     */
    fun extractExpiry(rawOcr: CharSequence): ExpiryDate? {
        for (i in rawOcr.indices) {
            if (rawOcr[i] != '/' || i < 2) continue
            val m1 = repairedDigit(rawOcr[i - 2])
            val m0 = repairedDigit(rawOcr[i - 1])
            if (m1 < 0 || m0 < 0) continue
            val month = m1 * 10 + m0
            if (month < 1 || month > 12) continue

            val yearStart = i + 1
            if (yearStart + 1 >= rawOcr.length) continue
            val y1 = repairedDigit(rawOcr[yearStart])
            val y0 = repairedDigit(rawOcr[yearStart + 1])
            if (y1 < 0 || y0 < 0) continue

            var year = 2000 + y1 * 10 + y0
            if (yearStart + 3 < rawOcr.length) {
                val y3 = repairedDigit(rawOcr[yearStart + 2])
                val y2 = repairedDigit(rawOcr[yearStart + 3])
                if (y3 >= 0 && y2 >= 0) year = y1 * 1000 + y0 * 100 + y3 * 10 + y2
            }
            return ExpiryDate(month, year)
        }
        return null
    }

    private val NAME_BLOCKLIST = setOf(
        "VISA", "MASTERCARD", "AMEX", "AMERICAN", "EXPRESS", "JCB",
        "DEBIT", "CREDIT", "BANK", "CARD", "GOLD", "PLATINUM", "SIGNATURE",
        "REWARDS", "WORLD", "ELITE", "BUSINESS", "VALID", "THRU",
    )

    private fun isNameCandidateLine(line: CharSequence): Boolean {
        if (line.length < 4) return false
        for (c in line) if (c !in 'A'..'Z' && c != ' ') return false
        var wordCount = 0
        var start = 0
        for (i in 0..line.length) {
            if (i < line.length && line[i] != ' ') continue
            if (i > start) {
                wordCount++
                if (line.substring(start, i) in NAME_BLOCKLIST) return false
            }
            start = i + 1
        }
        return wordCount >= 2
    }

    /**
     * Best-effort cardholder name: the longest line in the raw OCR text that
     * looks like a printed name (all uppercase letters/spaces, 2+ words, no
     * known bank/network word). Markedly less reliable than [extractCard] or
     * [extractExpiry] -- there's no structural signal to validate against,
     * only "looks like a name and isn't a known non-name word." A name alone
     * isn't PCI-DSS cardholder data, so this returns a plain `String`.
     */
    fun extractCardholderName(rawOcr: CharSequence): String? {
        var best: String? = null
        for (rawLine in rawOcr.split('\n')) {
            val line = rawLine.trim()
            if (line.length > (best?.length ?: -1) && isNameCandidateLine(line)) best = line
        }
        return best
    }

    /**
     * PAN + expiry + cardholder name from one OCR pass. [extractCard] stays
     * PAN-only for callers that don't want the extra scans.
     */
    fun extractCardDetails(rawOcr: CharSequence): SecurePan? {
        val pan = extractCard(rawOcr) ?: return null
        val expiry = extractExpiry(rawOcr)
        val name = extractCardholderName(rawOcr)
        if (expiry == null && name == null) return pan
        return pan.use { p -> p.withDigits { digits -> SecurePan(digits.copyOf(), p.brand, expiry, name) } }
    }

    /**
     * Allocation-bounded extraction: repairs OCR confusions, groups tokens into
     * candidate runs, and returns the first 13–19 digit candidate with a known
     * BIN that passes Luhn. Scratch buffers are wiped before returning.
     */
    fun extractCard(rawOcr: CharSequence): SecurePan? {
        val run = CharArray(RUN_CAPACITY)
        val bounds = IntArray(MAX_GROUPS + 1)
        val token = CharArray(RUN_CAPACITY)
        var runSize = 0; var groups = 0; var realDigits = 0; var longGroup = false
        var tokSize = 0; var tokReal = 0; var tokValid = true; var tokStarted = false
        var found: SecurePan? = null

        fun evaluateRun(): SecurePan? {
            if (runSize < MIN_PAN || realDigits * 2 < runSize) return null
            val view = CharArrayView(run, runSize)
            fun attempt(start: Int, len: Int): SecurePan? {
                val brand = detectBrand(view, start, len)
                if (brand == CardBrand.UNKNOWN || !validate(view, start, len)) return null
                return SecurePan(run.copyOfRange(start, start + len), brand)
            }
            fun isBoundary(pos: Int, from: Int): Boolean {
                for (g in from..groups) { if (bounds[g] == pos) return true; if (bounds[g] > pos) return false }
                return false
            }
            for (g in 0 until groups) {
                val start = bounds[g]
                for (len in LENGTH_PREFERENCE) {
                    val end = start + len
                    if (end > runSize || !isBoundary(end, g + 1)) continue
                    attempt(start, len)?.let { return it }
                }
            }
            if (longGroup) {
                var start = 0
                while (start + MIN_PAN <= runSize) {
                    for (len in LENGTH_PREFERENCE) {
                        if (start + len > runSize) continue
                        attempt(start, len)?.let { return it }
                    }
                    start++
                }
            }
            return null
        }
        fun flushRun() {
            if (found == null) found = evaluateRun()
            run.fill('\u0000', 0, runSize)
            runSize = 0; groups = 0; realDigits = 0; longGroup = false
        }
        fun flushToken() {
            if (tokStarted) {
                if (tokValid) {
                    if (runSize + tokSize > RUN_CAPACITY || groups >= MAX_GROUPS) flushRun()
                    System.arraycopy(token, 0, run, runSize, tokSize)
                    runSize += tokSize; realDigits += tokReal; groups++
                    bounds[groups] = runSize
                    if (tokSize > MAX_PAN) longGroup = true
                } else {
                    flushRun()
                }
            }
            token.fill('\u0000', 0, tokSize)
            tokSize = 0; tokReal = 0; tokValid = true; tokStarted = false
        }

        try {
            var i = 0
            while (i <= rawOcr.length && found == null) {
                val c = if (i < rawOcr.length) rawOcr[i] else '\n'
                i++
                if (isGroupSeparator(c)) { flushToken(); continue }
                if (isHardBreak(c)) { flushToken(); flushRun(); continue }
                tokStarted = true
                if (!tokValid) continue
                val r = if (c.code < 0x80) repairChar(c) else '\u0000'
                if (r == '\u0000' || tokSize >= token.size) { tokValid = false; continue }
                token[tokSize++] = r
                if (c in '0'..'9') tokReal++
            }
        } finally {
            run.fill('\u0000'); token.fill('\u0000')
        }
        return found
    }

    /** Zero-copy `CharSequence` view over a scratch array (no String alloc). */
    private class CharArrayView(private val a: CharArray, override val length: Int) : CharSequence {
        override fun get(index: Int): Char = a[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            throw UnsupportedOperationException("PAN scratch must not be copied")
        override fun toString(): String = "CharArrayView(len=$length)"
    }
}
