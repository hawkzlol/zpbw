package com.hawkslol.zpbw

import com.hawkslol.zpbw.visual.LandingCameraSettle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LandingCameraSettleTest {
    private val settle = LandingCameraSettle(arrivalY = 160.05, heightOffset = 0.05, startedAt = 0)

    @Test fun firstDestinationAndInterpolatedLandingFramesRenderAtFloorEyeHeight() {
        for (partialTick in listOf(0f, 0.25f, 0.5f, 1f)) {
            val initial = settle.offset(160.05, 160.05, partialTick, 1_000_000)
            assertEquals(0.05, requireNotNull(initial), 1e-9)

            val feet = 160.05 - 0.05 * partialTick
            val residual = settle.offset(160.05, 160.0, partialTick, 100_000_000)
            assertEquals(161.27, feet + 1.27 - requireNotNull(residual), 1e-9)
        }
        assertNull(settle.offset(160.0, 160.0, 0.25f, 150_000_000))
    }

    @Test fun firstFrameAfterPhysicsHasNoStrayOffset() {
        assertEquals(0.0125, requireNotNull(settle.offset(160.05, 160.0, 0.75f, 106_000_000)), 1e-9)
        assertNull(settle.offset(160.0, 160.0, 0.5f, 150_000_000))
    }

    @Test fun zeroOffsetLeavesCameraNative() {
        val exact = LandingCameraSettle(arrivalY = 160.0, heightOffset = 0.0, startedAt = 0)
        assertNull(exact.offset(160.0, 160.0, 0.25f, 1_000_000))
    }

    @Test fun nativeRiseFallAndCorrectionsEndTheVisualEffectImmediately() {
        assertNull(settle.offset(160.05, 160.42, 0.5f, 100_000_000))
        assertNull(settle.offset(160.05, 159.8, 0.5f, 100_000_000))
        assertNull(settle.offset(160.02, 160.04, 0.5f, 100_000_000))
        assertNull(settle.offset(159.9, 160.05, 0.5f, 100_000_000))
    }

    @Test fun invalidOrExpiredSamplesReleaseTheCamera() {
        assertNull(settle.offset(160.05, 160.0, 0.5f, 220_000_000))
        assertNull(settle.offset(160.05, 160.0, 0.5f, -1))
        assertNull(settle.offset(160.05, 160.0, Float.NaN, 100_000_000))
        assertNull(settle.offset(160.05, Double.NaN, 0.5f, 100_000_000))
    }
}
