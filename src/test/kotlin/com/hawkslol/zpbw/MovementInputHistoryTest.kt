package com.hawkslol.zpbw

import kotlin.test.*

class MovementInputHistoryTest {
    @Test fun discardedSneakChangeDoesNotAdvancePreviousSimulationInput() {
        val history = MovementInputHistory()
        history.input(false); history.position(false)
        history.input(true); history.position(true)
        history.rebase()
        assertTrue(history.hasRebase, "External ownership must wait for the restored native physics step")
        assertFalse(history.previousSneak(true))
        assertFalse(history.hasRebase)
        assertTrue(history.previousSneak(true), "Only the first resumed tick uses restored history")
        history.position(false); history.rebase()
        assertTrue(history.previousSneak(false))
    }
    @Test fun releaseAndRepeatedAttributeUpdatesKeepTheLastRealStep() {
        val history = MovementInputHistory()
        history.input(true); history.position(false)
        history.input(false); history.rebase(); history.rebase()
        assertTrue(history.previousSneak(false))
        history.position(false); history.rebase()
        assertFalse(history.previousSneak(true))
    }
    @Test fun unknownHistoryRecoveryAndWorldChangesCannotLeakAnOverride() {
        val history = MovementInputHistory()
        history.rebase(); assertTrue(history.previousSneak(true))
        history.position(false); history.rebase(); history.clearRebase()
        assertTrue(history.previousSneak(true))
        history.rebase(); history.reset(); history.rebase()
        assertTrue(history.previousSneak(true))
    }
}
