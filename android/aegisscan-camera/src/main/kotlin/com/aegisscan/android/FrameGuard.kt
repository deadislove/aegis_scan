// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.android

/** Why a frame was skipped before ML Kit ran. */
enum class FrameRejection {
    NULL_IMAGE, UNSUPPORTED_FORMAT, INVALID_DIMENSIONS, INVALID_STRIDE,
    BUFFER_TOO_SMALL, UNSUPPORTED_ASPECT_RATIO,
}

/**
 * Pure (Android-free) defensive validation of a YUV_420_888 frame's geometry,
 * so it can be unit-tested on the JVM. It mirrors the C++ core `FrameAnalyzer`
 * structural checks.
 */
object FrameGuard {
    /** Plane-0 (luma) geometry as reported by `ImageProxy.planes[0]`. */
    data class LumaPlane(
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val pixelStride: Int,
        val bufferCapacity: Int,
    )

    const val MIN_DIMENSION = 64
    const val MAX_DIMENSION = 16384

    /** @return null if the frame is usable, otherwise the rejection reason. */
    fun validate(plane: LumaPlane?, planeCount: Int, minAspect: Float, maxAspect: Float): FrameRejection? {
        if (plane == null || planeCount < 3) return FrameRejection.NULL_IMAGE
        val (w, h, rowStride, pixelStride, capacity) = plane
        if (w < MIN_DIMENSION || h < MIN_DIMENSION || w > MAX_DIMENSION || h > MAX_DIMENSION) {
            return FrameRejection.INVALID_DIMENSIONS
        }
        if (pixelStride < 1 || rowStride < w * pixelStride) return FrameRejection.INVALID_STRIDE
        val required = rowStride.toLong() * (h - 1) + (w - 1).toLong() * pixelStride + 1
        if (capacity.toLong() < required) return FrameRejection.BUFFER_TOO_SMALL
        val aspect = w.toFloat() / h
        if (aspect < minAspect || aspect > maxAspect) return FrameRejection.UNSUPPORTED_ASPECT_RATIO
        return null
    }
}
