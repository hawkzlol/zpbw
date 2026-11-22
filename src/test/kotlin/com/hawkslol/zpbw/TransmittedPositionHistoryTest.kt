package com.hawkslol.zpbw

import kotlin.test.*

class TransmittedPositionHistoryTest {
    @Test fun timeoutAtUnchangedSourceDoesNotForceAnEarlyHeartbeat() {
        val history = TransmittedPositionHistory()
        history.position(.5, 100.0, 6.5)
        history.tickEnd()
        repeat(5) { history.tickEnd() } // Original coalesced/held TickEnds, no held XYZ.
        assertEquals(TransmittedPositionHistory.Position(.5, 100.0, 6.5), history.position)
        assertEquals(5, history.reminder)
        assertTrue(history.reminder + 1 < 20)
    }
    @Test fun longStationaryJournalPreservesTheDueNativeHeartbeat() {
        val history = TransmittedPositionHistory()
        history.position(0.0, 100.0, 0.0); history.tickEnd()
        repeat(19) { history.tickEnd() }
        assertEquals(19, history.reminder)
        assertEquals(20, history.reminder + 1) // Vanilla increments before deciding.
        repeat(100) { history.tickEnd() }
        assertEquals(20, history.reminder)
    }
    @Test fun teleportResponseUpdatesCoordinatesWithoutInventingASimulationTick() {
        val history = TransmittedPositionHistory()
        history.position(0.0, 100.0, 0.0); history.tickEnd()
        repeat(4) { history.tickEnd() }
        history.position(12.5, 100.05, 12.5, regular = false)
        assertEquals(4, history.reminder)
        history.tickEnd()
        assertEquals(5, history.reminder)
        assertEquals(TransmittedPositionHistory.Position(12.5, 100.05, 12.5), history.position)
    }
    @Test fun newRegularPositionAndSessionResetRetireOldHistory() {
        val history = TransmittedPositionHistory()
        repeat(10) { history.tickEnd() }
        history.position(4.0, 99.0, 1.0); history.tickEnd()
        assertEquals(0, history.reminder)
        history.reset()
        assertNull(history.position)
        assertEquals(0, history.reminder)
    }
    @Test fun dueNativeReminderBlocksFurtherWarpsUntilPositionDelegateReturns() {
        val history = TransmittedPositionHistory()
        repeat(18) { history.tickEnd() }
        assertFalse(history.waitForPosition())
        history.tickEnd()
        assertTrue(history.waitForPosition())
        history.position(1.0, 100.0, 2.0, regular = false) // Combined ACK, no flying report.
        assertTrue(history.waitForPosition())
        history.movementRequested(true)
        history.position(1.0, 100.0, 2.0)
        assertFalse(history.waitForPosition())
    }
    @Test fun additionalRotationRepliesCountWithoutInventingTickEnds() {
        val history = TransmittedPositionHistory()
        repeat(17) { history.movementRequested(false) }
        assertEquals(0, history.reminder)
        assertFalse(history.waitForPosition())
        history.movementRequested(false)
        assertTrue(history.waitForPosition())
        assertEquals(0, history.reminder)
        history.movementRequested(true)
        assertFalse(history.waitForPosition())
        assertEquals(0, history.positionlessRequests)
    }
    @Test fun combinedAcknowledgementCannotRetireLatchedAdmissionPressure() {
        val history = TransmittedPositionHistory()
        repeat(18) { history.movementRequested(false) }
        assertTrue(history.waitForPosition())
        repeat(5) { history.position(it.toDouble(), 100.05, 0.5, regular = false) }
        assertTrue(history.awaitingPosition)
        assertEquals(18, history.positionlessRequests)
    }
    @Test fun retainedNativePacketsDoNotAdvanceTheRequestCounter() {
        val history = TransmittedPositionHistory()
        val held = ArrayDeque<Boolean>()
        repeat(30) { held.addLast(false) }
        assertFalse(history.waitForPosition())
        repeat(18) { history.movementRequested(held.removeFirst()) }
        assertTrue(history.waitForPosition())
        held.clear() // Discarding a suffix is not a transmitted position.
        assertTrue(history.waitForPosition())
    }
    @Test fun sessionResetRetiresBothReminderAndLatchedRequestPressure() {
        val history = TransmittedPositionHistory()
        repeat(100) { history.movementRequested(false); history.tickEnd() }
        assertTrue(history.waitForPosition())
        assertEquals(20, history.positionlessRequests)
        history.reset()
        assertFalse(history.waitForPosition())
        assertEquals(0, history.positionlessRequests)
        assertEquals(0, history.reminder)
    }
    @Test fun genuineDrainAloneDoesNotAdmitAnotherWarpOrChangeTheCeiling() {
        val history = TransmittedPositionHistory()
        val ledger = ChainLedger<String, Boolean>(responseContract = listOf(
            com.hawkslol.zpbw.nativebridge.NativeResponseStep.COMBINED_ACCEPT_POSITION_ROTATION))
        ledger.configureMaximum(3)
        ledger.add(1, "first"); ledger.cut(1)
        ledger.add(2, "second"); ledger.cut(2)
        repeat(18) { history.movementRequested(false) }
        assertTrue(history.waitForPosition())
        // Newest-destination original Pos must stay held through the first confirmation.
        ledger.retain(true)
        ledger.genuine(1, 101)
        ledger.response(101, com.hawkslol.zpbw.nativebridge.NativeResponseStep.COMBINED_ACCEPT_POSITION_ROTATION)
        assertTrue(ledger.settle(1, 101).isEmpty())
        assertTrue(history.waitForPosition())
        ledger.genuine(2, 102)
        ledger.response(102, com.hawkslol.zpbw.nativebridge.NativeResponseStep.COMBINED_ACCEPT_POSITION_ROTATION)
        val suffix = ledger.settle(2, 102)
        assertEquals(0, ledger.count)
        assertTrue(history.waitForPosition()) // Empty ledger did not erase the position obligation.
        suffix.forEach { history.movementRequested(it) }
        assertFalse(history.waitForPosition())
        assertEquals(3, ledger.maximum)
        assertTrue(ledger.canAdd)
    }
    @Test fun admissionProjectsOriginalCoalescedEndsBeforeDroppingTheirMovement() {
        val history = TransmittedPositionHistory()
        repeat(18) { history.tickEnd() }
        repeat(17) { history.movementRequested(false) }
        // The old gate accepted here, then coalescing two held ends made reminder20.
        assertFalse(history.waitForPosition())
        assertTrue(history.waitForPosition(coalescedTickEnds = 2))
        assertEquals(18, history.reminder, "Projection must not actually send/consume the ends")
        assertEquals(17, history.positionlessRequests)
    }
    @Test fun projectedEndsRespectPositionAlreadyRequestedInTheCurrentInterval() {
        val history = TransmittedPositionHistory()
        history.position(0.0, 100.0, 0.0)
        assertFalse(history.waitForPosition(coalescedTickEnds = 19)) // First end only closes position interval.
        assertEquals(0, history.reminder)
        assertTrue(history.waitForPosition(coalescedTickEnds = 20))
        history.reset()
        assertFailsWith<IllegalArgumentException> { history.waitForPosition(-1) }
        assertFailsWith<IllegalArgumentException> { history.waitForPosition(513) }
    }
}
