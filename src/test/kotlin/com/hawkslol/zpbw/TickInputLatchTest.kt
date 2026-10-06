package com.hawkslol.zpbw
import kotlin.test.*
class TickInputLatchTest {
    @Test fun laterPressAndReleaseCannotCreateASecondCommitment() {
        val latch = TickInputLatch<Int>()
        assertTrue(latch.commit(32)); assertFalse(latch.commit(0)); assertFalse(latch.commit(40))
        assertEquals(32, latch.value)
    }
    @Test fun samplingDoesNotReleaseBeforeNaturalTickCloses() {
        val latch = TickInputLatch<Int>(); latch.commit(32); latch.sampled()
        assertFalse(latch.commit(0)); latch.finishTick(); assertTrue(latch.commit(0))
    }
    @Test fun unsimulatedFrameDoesNotLoseOutstandingCommitment() {
        val latch = TickInputLatch<Int>(); latch.commit(32); latch.finishTick()
        assertEquals(32, latch.value)
    }
    @Test fun disconnectDropsUnconsumedInput() {
        val latch = TickInputLatch<Int>(); latch.commit(32); latch.clear()
        assertNull(latch.value); assertTrue(latch.commit(0))
    }
    @Test fun consecutiveTicksCanCommitDifferentWholeSnapshots() {
        val latch = TickInputLatch<Int>()
        for (input in listOf(32, 8, 16, 0, 64, 0)) {
            assertTrue(latch.commit(input)); assertEquals(input,latch.value)
            latch.sampled(); latch.finishTick(); assertNull(latch.value)
        }
    }
    @Test fun shortJumpAfterCommitIsDelayedInsteadOfLost() {
        val latch = TickInputLatch<Int>(); latch.commit(32)
        assertEquals(32,latch.sample(48)); latch.finishTick()
        assertFalse(latch.commit(0), "An early send cannot overtake the delayed jump")
        assertEquals(48,latch.sample(0)); latch.finishTick()
        assertEquals(0,latch.sample(0)); assertNull(latch.deferred)
    }
    @Test fun changingInputsStayBoundedToOneDeferredSampleAndDrain() {
        val latch = TickInputLatch<Int>(); latch.commit(32)
        assertEquals(32,latch.sample(8)); latch.finishTick()
        assertEquals(8,latch.sample(16)); assertEquals(16,latch.sample(0))
        assertEquals(0,latch.sample(0)); assertNull(latch.deferred)
    }
    @Test fun deferredTapCanBeReportedEarlyInNextNaturalWindow() {
        val latch = TickInputLatch<Int>(); latch.commit(32); latch.sample(48)
        assertNull(latch.commitDeferred()); latch.finishTick()
        assertEquals(48,latch.commitDeferred()); assertNull(latch.deferred)
        assertFalse(latch.commit(0)); assertEquals(48,latch.sample(0))
        latch.finishTick(); assertEquals(0,latch.commitDeferred())
    }
}
