// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.core

/**
 * Parsed TD1 (3-line, ID card) or TD3 (2-line, passport) MRZ.
 *
 * Unlike [ExpiryDate]/cardholder name, every field here is backed by an
 * ICAO 9303 check digit (same mental model as Luhn backing a PAN) --
 * `*CheckValid` tells you which fields to trust. [compositeCheckValid]
 * covers document number, date of birth, and expiry (and, for a TD3
 * passport, the personal-number field) all at once, the way one Luhn
 * digit backs a whole PAN. Not PCI-DSS cardholder data -- it's a
 * different compliance surface (PII, not payment data).
 */
data class MrzResult(
    val documentNumber: String,
    val nationality: String,
    val dateOfBirth: String, // YYMMDD
    val expiryDate: String, // YYMMDD
    val sex: Char, // 'M', 'F', or '<' (unspecified)
    val surname: String,
    val givenNames: String,
    val documentNumberCheckValid: Boolean,
    val dateOfBirthCheckValid: Boolean,
    val expiryCheckValid: Boolean,
    val compositeCheckValid: Boolean,
)

/**
 * Kotlin port of `aegisscan::core::extract_mrz` (bit-for-bit identical
 * behavior, verified against the same ICAO specimen vectors as the C++
 * core). Stateless.
 */
object MrzParser {
    private fun isMrzChar(c: Char) = c in 'A'..'Z' || c in '0'..'9' || c == '<'

    private fun mrzCharValue(c: Char): Int = when {
        c in '0'..'9' -> c - '0'
        c in 'A'..'Z' -> c - 'A' + 10
        else -> 0 // '<' -- anything else is already excluded by isMrzChar.
    }

    private fun mrzCheckDigit(field: CharSequence): Int {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        for (i in field.indices) sum += mrzCharValue(field[i]) * weights[i % 3]
        return sum % 10
    }

    private fun checkDigitMatches(field: CharSequence, digit: Char) =
        digit in '0'..'9' && mrzCheckDigit(field) == (digit - '0')

    /** Repairs a 7-char date-plus-check-digit span in one shot; '<' and
     * other non-repairable chars make this fail, leaving the raw span. */
    private fun repairDateAndCheckField(raw7: CharSequence): String {
        val repaired = CharArray(7)
        for (i in 0 until 7) {
            val r = LuhnValidator.repairChar(raw7[i])
            if (r !in '0'..'9') return raw7.toString()
            repaired[i] = r
        }
        return String(repaired)
    }

    private fun stripFill(field: CharSequence): String = field.filter { it != '<' }.toString()

    /** Splits "SURNAME<<GIVEN<NAMES<<<<" into (surname, given names). */
    private fun parseNameField(field: CharSequence): Pair<String, String> {
        val sep = field.indexOf("<<")
        val surnamePart = if (sep < 0) field else field.subSequence(0, sep)
        val givenPart = if (sep < 0) "" else field.subSequence(sep + 2, field.length)
        fun render(part: CharSequence) =
            part.toString().replace('<', ' ').trimEnd(' ')
        return render(surnamePart) to render(givenPart)
    }

    /** Scans for `count` consecutive lines of exactly `width` MRZ-alphabet
     * characters, trimmed of surrounding whitespace. Null if none found. */
    private fun findMrzLines(rawOcr: CharSequence, width: Int, count: Int): List<String>? {
        val lines = rawOcr.split('\n').map { it.trim() }
        for (start in 0..lines.size - count) {
            var allMatch = true
            for (i in 0 until count) {
                val l = lines[start + i]
                if (l.length != width || !l.all(::isMrzChar)) {
                    allMatch = false
                    break
                }
            }
            if (allMatch) return lines.subList(start, start + count)
        }
        return null
    }

    private fun parseTd3(lines: List<String>): MrzResult {
        val l2 = lines[1]
        val (surname, given) = parseNameField(lines[0].substring(5))

        val docNumField = l2.substring(0, 9)
        val documentNumber = stripFill(docNumField)
        val docCheckValid = checkDigitMatches(docNumField, l2[9])
        val nationality = l2.substring(10, 13)

        val dob7 = repairDateAndCheckField(l2.substring(13, 20))
        val dobCheckValid = checkDigitMatches(dob7.substring(0, 6), dob7[6])
        val sex = l2[20]

        val exp7 = repairDateAndCheckField(l2.substring(21, 28))
        val expCheckValid = checkDigitMatches(exp7.substring(0, 6), exp7[6])

        val composite = buildString {
            append(l2.substring(0, 10)) // doc number + its check digit
            append(dob7) // DOB + its check digit (repaired)
            append(exp7) // expiry + its check digit (repaired)
            append(l2.substring(28, 43)) // personal number + its check digit
        }
        val compositeValid = checkDigitMatches(composite, l2[43])

        return MrzResult(
            documentNumber, nationality, dob7.substring(0, 6), exp7.substring(0, 6), sex,
            surname, given, docCheckValid, dobCheckValid, expCheckValid, compositeValid,
        )
    }

    private fun parseTd1(lines: List<String>): MrzResult {
        val l1 = lines[0]
        val l2 = lines[1]
        val (surname, given) = parseNameField(lines[2])

        val docNumField = l1.substring(5, 14)
        val documentNumber = stripFill(docNumField)
        val docCheckValid = checkDigitMatches(docNumField, l1[14])

        val dob7 = repairDateAndCheckField(l2.substring(0, 7))
        val dobCheckValid = checkDigitMatches(dob7.substring(0, 6), dob7[6])
        val sex = l2[7]

        val exp7 = repairDateAndCheckField(l2.substring(8, 15))
        val expCheckValid = checkDigitMatches(exp7.substring(0, 6), exp7[6])
        val nationality = l2.substring(15, 18)

        val composite = buildString {
            append(l1.substring(5, 30)) // doc number+check + optional data 1
            append(dob7) // DOB + its check digit (repaired)
            append(exp7) // expiry + its check digit (repaired)
            append(l2.substring(18, 29)) // optional data 2
        }
        val compositeValid = checkDigitMatches(composite, l2[29])

        return MrzResult(
            documentNumber, nationality, dob7.substring(0, 6), exp7.substring(0, 6), sex,
            surname, given, docCheckValid, dobCheckValid, expCheckValid, compositeValid,
        )
    }

    /**
     * Scans raw OCR text for a TD3 (passport, 2 lines x 44 chars) or TD1 (ID
     * card, 3 lines x 30 chars) MRZ block and parses it. TD3 is tried first
     * -- the two widths never collide. Returns null only when no MRZ-shaped
     * line run is found at all; when one is found but its check digits
     * fail, the fields are still returned with their `*CheckValid` flags
     * false.
     */
    fun extractMrz(rawOcr: CharSequence): MrzResult? {
        findMrzLines(rawOcr, 44, 2)?.let { return parseTd3(it) }
        findMrzLines(rawOcr, 30, 3)?.let { return parseTd1(it) }
        return null
    }
}
