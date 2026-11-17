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
    @Test fun nextUncommittedSampleUsesCurrentJumpInsteadOfReplayingAnObsoleteTap() {
        val latch = TickInputLatch<Int>(); latch.commit(32)
        assertEquals(32,latch.sample(48)); latch.finishTick()
        assertEquals(0,latch.sample(0), "An unsent jump that has ended must not be replayed a tick later")
        assertNull(latch.value)
    }
    @Test fun movementDoesNotRemainOneSnapshotBehindAfterTheCommitment() {
        val latch = TickInputLatch<Int>(); latch.commit(32)
        assertEquals(32,latch.sample(8)); latch.finishTick()
        assertEquals(16,latch.sample(16)); latch.finishTick()
        assertEquals(0,latch.sample(0)); latch.finishTick()
        assertEquals(64,latch.sample(64))
    }
    @Test fun nextEarlySneakEdgeCommitsAFreshSnapshotWithoutAnOlderDeferredSend() {
        val latch = TickInputLatch<Int>(); latch.commit(32); latch.sample(48)
        assertFalse(latch.commit(0)); latch.finishTick()
        assertTrue(latch.commit(8), "Only a fresh early send may commit the next input window")
        assertEquals(8,latch.sample(0)); latch.finishTick()
        assertEquals(0,latch.sample(0))
    }
}
