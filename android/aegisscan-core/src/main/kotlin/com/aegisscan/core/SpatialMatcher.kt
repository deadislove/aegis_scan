// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Axis-aligned box in image pixels (top-left origin, y grows downwards). */
data class BoundingBox(val x: Float, val y: Float, val width: Float, val height: Float) {
    internal val isValid: Boolean
        get() = x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() &&
            width > 0f && height > 0f
}

data class BarcodeItem(val payload: String, val box: BoundingBox, val format: Int = 0)
data class TextItem(val text: String, val box: BoundingBox)
data class MatchResult(
    val barcode: BarcodeItem,
    val associatedText: String,
    val normalizedDistance: Float,
    val alignment: Alignment,
)

enum class Alignment { TEXT_BELOW, TEXT_ABOVE, OVERLAPPING, SIDE_BY_SIDE, DIAGONAL }

/** Tuning (defaults identical to `aegisscan::core::SpatialMatcherOptions`). */
data class SpatialMatcherOptions(
    val frameWidth: Float = 0f,
    val frameHeight: Float = 0f,
    val maxGapRatio: Float = 2f,
    val minAxisOverlap: Float = 0.3f,
    val stackedWeight: Float = 0.5f,
    val sideBySideWeight: Float = 1f,
    val diagonalWeight: Float = 1.5f,
    val minOverlapChars: Int = 4,
)

/**
 * Kotlin port of `aegisscan::core::SpatialMatcher`: normalized center distance
 * × alignment weight (vertical stacking preferred) × payload-overlap bonus,
 * assigned greedily one-to-one. An instance owns reusable scratch, so use one
 * per analyzer thread (it isn't thread-safe; the CameraX analyzer is serial).
 */
class SpatialMatcher(private val options: SpatialMatcherOptions = SpatialMatcherOptions()) {
    private val foldedPayload = CharArray(MAX_FOLDED)
    private val foldedText = CharArray(MAX_FOLDED)
    private val rowA = IntArray(MAX_FOLDED + 1)
    private val rowB = IntArray(MAX_FOLDED + 1)

    private class Candidate(
        val b: Int, val t: Int, val score: Float, val dist: Float, val align: Alignment,
    )

    private class Frame(val ox: Float, val oy: Float, val scale: Float)

    fun matchBarcodesWithText(barcodes: List<BarcodeItem>, texts: List<TextItem>): List<MatchResult> {
        if (barcodes.isEmpty() || texts.isEmpty()) return emptyList()
        val frame = computeFrame(barcodes, texts)
        val candidates = ArrayList<Candidate>(barcodes.size * texts.size)

        for ((bi, bc) in barcodes.withIndex()) {
            if (!bc.box.isValid) continue
            val pLen = fold(bc.payload, foldedPayload)
            if (pLen == 0) continue
            for ((ti, tx) in texts.withIndex()) {
                if (!tx.box.isValid || !withinGap(bc.box, tx.box)) continue
                val tLen = fold(tx.text, foldedText)
                if (tLen == 0) continue
                val shorter = min(pLen, tLen)
                val required = min(options.minOverlapChars, shorter)
                val overlap = longestCommonSubstring(pLen, tLen)
                if (required == 0 || overlap < required) continue
                val align = classify(bc.box, tx.box)
                val dist = centerDistance(bc.box, tx.box, frame)
                val score = dist * weight(align) * (1f - 0.5f * overlap.toFloat() / shorter)
                candidates += Candidate(bi, ti, score, dist, align)
            }
        }
        foldedPayload.fill('\u0000')
        foldedText.fill('\u0000')

        candidates.sortWith(compareBy<Candidate>({ it.score }, { it.b }, { it.t }))
        val usedB = BooleanArray(barcodes.size)
        val usedT = BooleanArray(texts.size)
        val picked = ArrayList<Candidate>(min(barcodes.size, texts.size))
        for (c in candidates) {
            if (usedB[c.b] || usedT[c.t]) continue
            usedB[c.b] = true
            usedT[c.t] = true
            picked += c
        }
        picked.sortBy { it.b }
        return picked.map { MatchResult(barcodes[it.b], texts[it.t].text, it.dist, it.align) }
    }

    private fun computeFrame(barcodes: List<BarcodeItem>, texts: List<TextItem>): Frame {
        if (options.frameWidth > 0f && options.frameHeight > 0f) {
            return Frame(0f, 0f, max(options.frameWidth, options.frameHeight))
        }
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        fun extend(b: BoundingBox) {
            if (!b.isValid) return
            minX = min(minX, b.x); minY = min(minY, b.y)
            maxX = max(maxX, b.x + b.width); maxY = max(maxY, b.y + b.height)
        }
        barcodes.forEach { extend(it.box) }
        texts.forEach { extend(it.box) }
        if (minX > maxX) return Frame(0f, 0f, 1f)
        val extent = max(maxX - minX, maxY - minY)
        return Frame(minX, minY, if (extent.isFinite() && extent > 0f) extent else 1f)
    }

    private fun centerDistance(a: BoundingBox, b: BoundingBox, f: Frame): Float {
        val ax = (a.x + a.width * 0.5f - f.ox) / f.scale
        val ay = (a.y + a.height * 0.5f - f.oy) / f.scale
        val bx = (b.x + b.width * 0.5f - f.ox) / f.scale
        val by = (b.y + b.height * 0.5f - f.oy) / f.scale
        return hypot(ax - bx, ay - by)
    }

    private fun withinGap(a: BoundingBox, b: BoundingBox): Boolean {
        val gx = maxOf(0f, b.x - (a.x + a.width), a.x - (b.x + b.width))
        val gy = maxOf(0f, b.y - (a.y + a.height), a.y - (b.y + b.height))
        return hypot(gx, gy) <= options.maxGapRatio * max(a.height, b.height)
    }

    private fun axisOverlap(a0: Float, a1: Float, b0: Float, b1: Float): Float {
        val overlap = min(a1, b1) - max(a0, b0)
        val shorter = min(a1 - a0, b1 - b0)
        return if (overlap <= 0f || shorter <= 0f) 0f else overlap / shorter
    }

    private fun classify(bc: BoundingBox, tx: BoundingBox): Alignment {
        val col = axisOverlap(bc.x, bc.x + bc.width, tx.x, tx.x + tx.width) >= options.minAxisOverlap
        val row = axisOverlap(bc.y, bc.y + bc.height, tx.y, tx.y + tx.height) >= options.minAxisOverlap
        return when {
            col && row -> Alignment.OVERLAPPING
            col -> if (tx.y + tx.height / 2 >= bc.y + bc.height / 2) Alignment.TEXT_BELOW
                   else Alignment.TEXT_ABOVE
            row -> Alignment.SIDE_BY_SIDE
            else -> Alignment.DIAGONAL
        }
    }

    private fun weight(a: Alignment) = when (a) {
        Alignment.TEXT_BELOW, Alignment.TEXT_ABOVE, Alignment.OVERLAPPING -> options.stackedWeight
        Alignment.SIDE_BY_SIDE -> options.sideBySideWeight
        Alignment.DIAGONAL -> options.diagonalWeight
    }

    /** Upper-case, keep alphanumerics, fold OCR-confusable letters to digits. */
    private fun fold(s: String, out: CharArray): Int {
        var n = 0
        for (raw in s) {
            if (n == out.size) break
            val c = if (raw in 'a'..'z') raw - ('a' - 'A') else raw
            if (c !in '0'..'9' && c !in 'A'..'Z') continue
            val r = LuhnValidator.repairChar(c)
            out[n++] = if (r != '\u0000') r else c
        }
        return n
    }

    /** Longest common substring over the folded scratch buffers (rolling rows). */
    private fun longestCommonSubstring(aLen: Int, bLen: Int): Int {
        var prev = rowA
        var curr = rowB
        prev.fill(0)
        curr.fill(0)
        var best = 0
        for (i in 1..aLen) {
            for (j in 1..bLen) {
                curr[j] = if (foldedPayload[i - 1] == foldedText[j - 1]) prev[j - 1] + 1 else 0
                if (curr[j] > best) best = curr[j]
            }
            val tmp = prev; prev = curr; curr = tmp
            curr[0] = 0
        }
        return best
    }

    private companion object {
        const val MAX_FOLDED = 128
    }
}
