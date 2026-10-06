package com.hawkslol.zpbw

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstInstallNoticeTest {
    @Test fun waitsFourContinuousSecondsIncludingZeroClock() {
        val timer = FirstInstallNotice()
        val session = Any()
        assertFalse(timer.poll(true, session, true, 0))
        assertFalse(timer.poll(true, session, true, 3_999_999_999L))
        assertTrue(timer.poll(true, session, true, 4_000_000_000L))
    }

    @Test fun leavingSkyblockRestartsTheWholeDelay() {
        val timer = FirstInstallNotice()
        val session = Any()
        assertFalse(timer.poll(true, session, true, 0))
        assertFalse(timer.poll(true, session, false, 3_000_000_000L))
        assertFalse(timer.poll(true, session, true, 4_000_000_000L))
        assertFalse(timer.poll(true, session, true, 7_999_999_999L))
        assertTrue(timer.poll(true, session, true, 8_000_000_000L))
    }

    @Test fun changingSessionIdentityRestartsEvenForEqualValues() {
        val timer = FirstInstallNotice()
        val first = String(charArrayOf('a'))
        val second = String(charArrayOf('a'))
        assertFalse(timer.poll(true, first, true, 0))
        assertFalse(timer.poll(true, second, true, 4_000_000_000L))
        assertTrue(timer.poll(true, second, true, 8_000_000_000L))
    }

    @Test fun missingSessionAndDisabledPendingStateCancelDelay() {
        val timer = FirstInstallNotice()
        val session = Any()
        assertFalse(timer.poll(true, null, true, 0))
        assertFalse(timer.poll(true, session, true, 1_000_000_000L))
        assertFalse(timer.poll(false, session, true, 5_000_000_000L))
        assertFalse(timer.poll(true, session, true, 6_000_000_000L))
        assertFalse(timer.poll(true, null, true, 10_000_000_000L))
        assertFalse(timer.poll(true, session, true, 11_000_000_000L))
        assertTrue(timer.poll(true, session, true, 15_000_000_000L))
    }

    @Test fun deliveredNoticeNeverRepeatsEvenIfSavingFailedOrSessionResets() {
        val timer = FirstInstallNotice()
        val session = Any()
        assertFalse(timer.poll(true, session, true, 0))
        assertTrue(timer.poll(true, session, true, 4_000_000_000L))
        assertFalse(timer.poll(true, session, true, 8_000_000_000L))
        timer.reset()
        val other = Any()
        assertFalse(timer.poll(true, other, true, 12_000_000_000L))
        assertFalse(timer.poll(true, other, true, 16_000_000_000L))
    }

    @Test fun explicitResetCancelsPendingTimer() {
        val timer = FirstInstallNotice()
        val session = Any()
        assertFalse(timer.poll(true, session, true, 0))
        timer.reset()
        assertFalse(timer.poll(true, session, true, 4_000_000_000L))
        assertTrue(timer.poll(true, session, true, 8_000_000_000L))
    }

    @Test fun largeMonotonicClockAndSignedWrapRemainValid() {
        val timer = FirstInstallNotice()
        val session = Any()
        val start = Long.MAX_VALUE - 1_000_000_000L
        assertFalse(timer.poll(true, session, true, start))
        assertFalse(timer.poll(true, session, true, start + 3_999_999_999L))
        assertTrue(timer.poll(true, session, true, start + 4_000_000_000L))
    }
}
