package com.hawkslol.zpbw

import com.hawkslol.zpbw.nativebridge.NativeResponseStep
import kotlin.test.*

class NativeFallbackOwnershipTest {
    private fun ledger(contract: List<NativeResponseStep> = TestResponseContracts.PAIR) =
        ChainLedger<Any, Any>(responseContract = contract)

    @Test fun cancelsTheExactJournalButKeepsAllIssuedEntryIdentitiesAndNoAssignedIds() {
        val c = ledger()
        (1L..5L).forEach { c.add(it, Any()); c.cut(it) }
        val entries = c.pendingSnapshot()
        val motion = Any(); val action = Any()
        c.retain(motion); c.retain(action, true)
        val cancelled = c.beginNativeFallback()
        assertSame(motion, cancelled[0]); assertSame(action, cancelled[1])
        assertEquals(0, c.size); assertFalse(c.hasActions); assertEquals(5, c.count)
        entries.zip(c.pendingSnapshot()).forEach { (old, current) ->
            assertSame(old, current); assertNull(current.realId); assertEquals(0, current.responses)
        }
        assertTrue(c.nativeFallback)
    }

    @Test fun ceilingChangesNeverReopenAdmissionOrRetireAnUnansweredGeneration() {
        val c = ledger(); (1L..5L).forEach { c.add(it, Any()); c.cut(it) }
        val original = c.pendingSnapshot(); c.beginNativeFallback()
        for (maximum in listOf(1, 5, 2, 4, 3)) {
            c.configureMaximum(maximum)
            assertEquals(original, c.pendingSnapshot())
            assertFalse(c.canAdd); assertFalse(c.canDispatch(true)); assertFalse(c.canDispatch(false))
            assertFailsWith<IllegalStateException> { c.add(6, Any()) }
            assertFailsWith<IllegalStateException> { c.retain(Any(), true) }
        }
    }

    @Test fun fallbackCannotInterruptAnOpenNativePairOrAnUnclosedSource() {
        val c = ledger(); c.add(1, Any())
        assertFailsWith<IllegalStateException> { c.beginNativeFallback() }
        assertFalse(c.nativeFallback); assertEquals(1, c.count)
        c.cut(1); c.genuine(1, 37); c.response(37, NativeResponseStep.ACCEPT_ID)
        val entry = c.head
        assertFailsWith<IllegalStateException> { c.beginNativeFallback() }
        assertSame(entry, c.head); assertEquals(1, c.head!!.responses)
        c.response(37, NativeResponseStep.POSITION_ROTATION); c.settle(1, 37)
        assertEquals(0, c.count)
    }

    @Test fun lateAuthorityCannotMasqueradeAsSuccessfulSettlementInEitherNativeContract() {
        for (contract in listOf(TestResponseContracts.PAIR, TestResponseContracts.COMBINED)) {
            val c = ledger(contract); c.add(1, Any()); c.cut(1); c.beginNativeFallback()
            assertFailsWith<IllegalStateException> { c.genuine(1, 55) }
            contract.forEach { assertFailsWith<IllegalStateException> { c.response(55, it) } }
            assertFailsWith<IllegalStateException> { c.settle(1, 55) }
            assertEquals(1, c.count); assertNull(c.head!!.realId); assertEquals(0, c.head!!.responses)
        }
    }

    @Test fun releaseKeepsActualRecoveryTimerAndArrivalBarrierUntilExplicitRetirement() {
        val c = ledger(); c.add(1, Any()); c.cut(1)
        val stream = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(stream)
        assertTrue(clock.start(stream, 1, 2))
        repeat(2) { clock.ping(stream, 8) }
        assertTrue(clock.completedClientTick(stream).isEmpty())
        val expired = clock.completedClientTick(stream).single()
        assertTrue(clock.restartAfterExpiry(stream, expired, listOf(1), 20))
        val anchor = clock.stamp(stream)!!
        c.beginNativeFallback()
        assertEquals(2L, clock.sample(stream)); assertEquals(false, clock.deadlineReached(stream, anchor, 20))
        // Real arrival stops the timer even while its native handler is queued.
        clock.teleport(stream)
        repeat(40) { clock.ping(stream, -4); clock.completedClientTick(stream) }
        assertTrue(clock.expiredTickets().isEmpty()); assertEquals(1, c.count)
        c.clear(); clock.clear()
        assertFalse(c.nativeFallback); assertEquals(0, c.count)
    }

    @Test fun ownershipRetirementDoesNotClearTheConnectionFenceOrFabricateAProvedDrain() {
        val stream = Any(); val fence = RecoveryPredictionFence<Any>(); fence.bindCurrent(stream)
        val c = ledger(); c.add(1, Any()); c.cut(1); c.beginNativeFallback()
        assertTrue(fence.suspend(stream, "issued_warp_timeout"))
        val resume = RecoveryResumeGate(); resume.begin(false)
        assertEquals(RecoveryResumeGate.Completion.WAITING, resume.naturalTickEndSent())
        resume.inputSampled()
        assertEquals(RecoveryResumeGate.Completion.KEEP_GRACE, resume.naturalTickEndSent())
        assertTrue(c.clear().isEmpty()); assertTrue(fence.isSuspended(stream))
        assertFalse(fence.bindCurrent(stream)); assertTrue(fence.isSuspended(stream))
        assertTrue(fence.bindCurrent(Any())); assertFalse(fence.isSuspended(stream))
    }
}
