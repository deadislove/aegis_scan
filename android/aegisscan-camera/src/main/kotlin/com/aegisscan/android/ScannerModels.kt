// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.android

import com.aegisscan.core.MatchResult
import com.aegisscan.core.SecurePan

/** Results emitted on the main thread. None of them contain a raw PAN string. */
sealed interface ScanEvent {
    /**
     * A validated card number. The [pan] is wiped as soon as the listener
     * returns: tokenize or encrypt it synchronously and never persist or log it.
     */
    class CardDetected(val pan: SecurePan) : ScanEvent {
        override fun toString(): String = "CardDetected($pan)"  // Redacted.
    }

    /** Voucher barcodes associated with nearby printed text. */
    data class VouchersMatched(val matches: List<MatchResult>) : ScanEvent

    /** Recoverable or fatal error (no sensitive data). */
    data class Error(val error: ScannerError) : ScanEvent
}

sealed class ScannerError(message: String) : Exception(message) {
    object CameraPermissionDenied : ScannerError("Camera permission denied")
    object CameraUnavailable : ScannerError("No back camera available")
    class BindFailed(reason: String) : ScannerError("Camera bind failed: $reason")
    class FrameRejected(val reason: FrameRejection) : ScannerError("Frame rejected: $reason")
    class InferenceFailed(reason: String) : ScannerError("ML Kit failure: $reason")
}

/** Tuning knobs (defaults target 30 FPS on mid-range devices). */
data class ScannerConfig(
    val detectCards: Boolean = true,
    val detectVouchers: Boolean = true,
    /** Same PAN required in N consecutive analyzed frames before reporting. */
    val requiredConsecutiveCardHits: Int = 2,
    val targetWidth: Int = 1920,
    val targetHeight: Int = 1080,
    val minAspectRatio: Float = 0.25f,
    val maxAspectRatio: Float = 4f,
    val reportFrameRejections: Boolean = false,
)

/** Thread-safe counters snapshot. */
data class ScannerStats(
    val framesReceived: Long,
    val framesDropped: Long,
    val framesAnalyzed: Long,
    val framesRejected: Long,
)
