package com.hawkslol.zpbw

import kotlin.test.*

class SessionTeleportIdHistoryTest {
    @Test fun sameWorldStillRejectsDuplicatesAndRepeatedBindDoesNotEraseHistory() {
        val history = SessionTeleportIdHistory(); val connection = Any(); val world = Any()
        assertTrue(history.bind(connection, world))
        assertFalse(history.record(4)); assertTrue(history.record(4))
        assertFalse(history.bind(connection, world)); assertTrue(history.record(4))
        assertFalse(history.record(11)); assertEquals(2, history.size)
    }
    @Test fun worldOnlyTransferAllowsLegitimateReusedIdsOnSameConnection() {
        val history = SessionTeleportIdHistory(); val connection = Any()
        history.bind(connection, Any())
        val ids = listOf(4, 5, 7, 8, 9, 10, 11, 12, 13, 15, 17, 19, 21, 22, 24, 26)
        ids.forEach { assertFalse(history.record(it)) }
        assertTrue(history.bind(connection, Any())); assertEquals(0, history.size)
        ids.forEach { assertFalse(history.record(it), "Reused ID $it belongs to the new world") }
        ids.forEach { assertTrue(history.record(it), "Same-world duplicate guard remains active") }
    }
    @Test fun equalDimensionNamesDoNotHideDifferentWorldObjects() {
        data class World(val dimension: String)
        val history = SessionTeleportIdHistory(); val connection = Any()
        val old = World("minecraft:overworld"); val next = World("minecraft:overworld")
        assertEquals(old, next); assertNotSame(old, next)
        history.bind(connection, old); history.record(4)
        assertTrue(history.bind(connection, next)); assertFalse(history.record(4))
    }
    @Test fun reconnectAndExplicitDisconnectResetBothIdentityAndHistory() {
        val history = SessionTeleportIdHistory(); val world = Any(); val connection = Any()
        history.bind(connection, world); history.record(4)
        assertTrue(history.bind(Any(), world)); assertFalse(history.record(4))
        history.clear(); assertEquals(0, history.size)
        assertTrue(history.bind(connection, world)); assertFalse(history.record(4))
    }
    @Test fun boundedHistoryAllowsGapsAndOnlyContainsActuallyReceivedIds() {
        val history = SessionTeleportIdHistory(2); history.bind(Any(), Any())
        assertFalse(history.record(4)); assertFalse(history.record(99)); assertFalse(history.record(-5))
        assertEquals(2, history.size); assertTrue(history.record(99)); assertFalse(history.record(4))
        assertEquals(2, history.size)
        assertFailsWith<IllegalArgumentException> { SessionTeleportIdHistory(0) }
    }
    @Test fun reportedReusedIdCanEnterNewWorldsGenuineHandlerAndDrainOnlyAfterBothResponses() {
        val connection = Any(); val history = SessionTeleportIdHistory()
        history.bind(connection, Any()); history.record(4) // Earlier world, 07:31:16.
        history.bind(connection, Any()) // Same connection, new world, 07:43:36.
        val ledger = ChainLedger<String, Any>(); val original = Any()
        ledger.add(81, "new-world-guess"); ledger.cut(81); ledger.retain(original)
        assertFalse(history.record(4), "07:43:38 genuine ID 4 must not be mistaken for the old world")
        ledger.genuine(81, 4)
        assertFailsWith<IllegalStateException> { ledger.settle(81, 4) }
        ledger.response(4, true); ledger.response(4, false)
        assertSame(original, ledger.settle(81, 4).single())
        assertTrue(history.record(4))
    }
}
