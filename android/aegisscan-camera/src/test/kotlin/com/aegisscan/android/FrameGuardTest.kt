// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FrameGuardTest {
    private fun check(w: Int, h: Int, stride: Int = w, pixel: Int = 1, cap: Int = stride * h, planes: Int = 3) =
        FrameGuard.validate(FrameGuard.LumaPlane(w, h, stride, pixel, cap), planes, 0.25f, 4f)

    @Test fun acceptsValid1080p() = assertNull(check(1920, 1080, stride = 1920 + 64))
    @Test fun rejectsMissingPlanes() = assertEquals(FrameRejection.NULL_IMAGE, check(1920, 1080, planes = 1))
    @Test fun rejectsNullPlane() =
        assertEquals(FrameRejection.NULL_IMAGE, FrameGuard.validate(null, 3, 0.25f, 4f))
    @Test fun rejectsTinyOrNegative() {
        assertEquals(FrameRejection.INVALID_DIMENSIONS, check(32, 32))
        assertEquals(FrameRejection.INVALID_DIMENSIONS, check(-1920, 1080, stride = 1920, cap = 1920 * 1080))
    }
    @Test fun rejectsBadStride() = assertEquals(FrameRejection.INVALID_STRIDE, check(1920, 1080, stride = 1000))
    @Test fun rejectsTruncatedBuffer() =
        assertEquals(FrameRejection.BUFFER_TOO_SMALL, check(1920, 1080, cap = 1920 * 1000))
    @Test fun rejectsExtremeAspect() =
        assertEquals(FrameRejection.UNSUPPORTED_ASPECT_RATIO, check(4000, 200))
}
