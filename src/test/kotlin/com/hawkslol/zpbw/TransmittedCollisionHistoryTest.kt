package com.hawkslol.zpbw

import kotlin.test.*

class TransmittedCollisionHistoryTest {
    @Test fun teleportAndDependentLookFlagsDoNotReplacePhysicsGroundHistory() {
        val history = TransmittedCollisionHistory()
        history.sent(true, false, true)
        history.sent(false, false, false) // Genuine native teleport response.
        assertEquals(false to false, history.sourceFlags(true))
        assertEquals(true to false, history.simulation)
        history.sent(false, false, false) // New dependent source look, with XYZ coalesced.
        assertEquals(true to false, history.simulation)
        history.sent(false, false, true) // First actual replayed movement after settlement.
        assertEquals(false to false, history.simulation)
    }

    @Test fun initialOverlapUsesRegularPhysicsAndNewSessionsForgetBothHistories() {
        val history = TransmittedCollisionHistory()
        history.sent(true, true, true)
        history.sent(false, false, false)
        assertEquals(true to true, history.sourceFlags(false))
        history.reset()
        assertNull(history.simulation); assertNull(history.sourceFlags(true))
    }
}
