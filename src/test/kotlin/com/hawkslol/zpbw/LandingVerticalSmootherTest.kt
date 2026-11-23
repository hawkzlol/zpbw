package com.hawkslol.zpbw

import com.hawkslol.zpbw.visual.LandingVerticalSmoother
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LandingVerticalSmootherTest {
    @Test fun firstDestinationFrameIsImmediateAndSmallSettlingConverges() {
        val landing = LandingVerticalSmoother(0)
        assertEquals(91.62, landing.sample(91.62, 10_000_000))
        val first = requireNotNull(landing.sample(91.57, 20_000_000))
        val last = requireNotNull(landing.sample(91.57, 210_000_000))
        assertTrue(first in 91.60..91.62)
        assertTrue(last < first && last - 91.57 < 0.001)
        assertNull(landing.sample(91.57, 220_000_000))
    }

    @Test fun realJumpOrLargeCorrectionExitsOnTheFirstAffectedFrame() {
        for (height in listOf(90.5, 92.04)) {
            val landing = LandingVerticalSmoother(0)
            landing.sample(91.62, 0)
            assertNull(landing.sample(height, 10_000_000))
        }
    }
}
