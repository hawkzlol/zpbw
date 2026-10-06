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
}
