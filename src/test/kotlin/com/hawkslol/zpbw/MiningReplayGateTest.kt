package com.hawkslol.zpbw

import kotlin.test.*

class MiningReplayGateTest {
    @Test fun queuedStartAllowsNativeContinuationButQueuedContinuationConsumesOnlyItsOwnTick() {
        val gate = MiningReplayGate(); val world = Any(); var progress = 0
        assertTrue(gate.replay(world, 10, false, true, false) { progress = 0 })
        assertFalse(gate.continuedAt(world, 10)) // Native start + one native continue is normal.
        assertTrue(gate.replay(world, 11, true, true, true) { progress++ })
        assertTrue(gate.continuedAt(world, 11)) // Do not also advance the ordinary continuation.
        assertEquals(1, progress)
        assertFalse(gate.continuedAt(world, 12)) // No queued backlog prevents the next real tick.
        assertFalse(gate.continuedAt(Any(), 11))
        gate.reset()
        assertFalse(gate.continuedAt(world, 11))
    }
    @Test fun instantBreakDoesNotReplayStartsOrContinuesAgainstAir() {
        val gate = MiningReplayGate(); val world = Any()
        var stone = true; var starts = 0
        fun replay(continuing: Boolean) = gate.replay(world, 10, continuing, stone, stone) {
            starts++; stone = false
        }
        assertTrue(replay(false))
        repeat(8) { assertFalse(replay(true)) }
        assertFalse(replay(false))
        assertEquals(1, starts)
    }

    @Test fun queuedHoldCannotCompressSeveralTicksOfMiningProgress() {
        val gate = MiningReplayGate(); val world = Any(); var progress = 0
        assertTrue(gate.replay(world, 10, false, true, false) { progress++ })
        repeat(6) { assertFalse(gate.replay(world, 10, true, true, true) { progress++ }) }
        assertEquals(1, progress)
        assertTrue(gate.replay(world, 11, true, true, true) { progress++ })
        assertFalse(gate.replay(world, 11, true, true, true) { progress++ })
        assertEquals(2, progress)
    }

    @Test fun replacedOrUnreachableTargetsAndAbortedMiningDoNotRestart() {
        val gate = MiningReplayGate(); val world = Any(); var calls = 0
        assertFalse(gate.replay(world, 1, false, false, true) { calls++ })
        assertFalse(gate.replay(world, 1, true, true, false) { calls++ })
        assertEquals(0, calls)
        // A separate explicit click still starts native mining.
        assertTrue(gate.replay(world, 1, false, true, false) { calls++ })
        assertEquals(1, calls)
    }

    @Test fun newWorldAndResetDoNotInheritProgressFromAnOldWorld() {
        val gate = MiningReplayGate(); var calls = 0
        val first = Any(); val second = Any()
        assertTrue(gate.replay(first, 12, true, true, true) { calls++ })
        assertTrue(gate.replay(second, 12, true, true, true) { calls++ })
        gate.reset()
        assertTrue(gate.replay(second, 12, true, true, true) { calls++ })
        assertEquals(3, calls)
    }
}
