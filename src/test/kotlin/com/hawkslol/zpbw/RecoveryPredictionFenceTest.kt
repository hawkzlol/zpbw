package com.hawkslol.zpbw

import kotlin.test.*

class RecoveryPredictionFenceTest {
    private class Connection {
        override fun equals(other: Any?): Boolean = error("Connection equality must be referential")
        override fun hashCode(): Int = error("No identity hash is a transport fence")
    }

    @Test fun freshConnectionStartsUnsuspended() {
        val fence = RecoveryPredictionFence<Connection>(); val connection = Connection()
        assertFalse(fence.bindCurrent(connection)); assertFalse(fence.isSuspended(connection))
        assertNull(fence.reason(connection))
    }

    @Test fun suspensionRequiresAnAlreadyBoundActualConnection() {
        val fence = RecoveryPredictionFence<Connection>(); val connection = Connection()
        assertFalse(fence.suspend(connection, "unbound")); assertFalse(fence.isSuspended(connection))
        fence.bindCurrent(connection); assertTrue(fence.suspend(connection, "timeout"))
        assertTrue(fence.isSuspended(connection)); assertEquals("timeout", fence.reason(connection))
    }

    @Test fun nullPlayListenerDuringConfigurationDoesNotClearUncertainty() {
        val fence = RecoveryPredictionFence<Connection>(); val connection = Connection()
        fence.bindCurrent(connection); fence.suspend(connection, "late_effect")
        assertFalse(fence.bindCurrent(null)); assertFalse(fence.isSuspended(null))
        assertFalse(fence.bindCurrent(connection)); assertTrue(fence.isSuspended(connection))
    }

    @Test fun issuedUseDiscardWhilePlayIsAbsentSuspendsItsOldOwnerUntilARealFreshTransport() {
        val fence = RecoveryPredictionFence<Connection>(); val old = Connection(); val fresh = Connection()
        fence.bindCurrent(old)
        fence.bindCurrent(null) // Native PLAY listener temporarily absent, old transport still live.
        assertTrue(fence.suspend(old, "session_changed_with_issued_use"))
        assertFalse(fence.bindCurrent(old)); assertTrue(fence.isSuspended(old))
        assertTrue(fence.bindCurrent(fresh)); assertFalse(fence.isSuspended(fresh))
        assertFalse(fence.suspend(old, "stale_after_fresh_bind"))
    }

    @Test fun absentPlayDelaysTheNoticeWithoutDroppingItOrShowingItTwice() {
        val fence = RecoveryPredictionFence<Connection>(); val connection = Connection()
        fence.bindCurrent(connection); fence.suspend(connection, "configuration_discard")
        assertFalse(fence.takeNotice(null)); fence.bindCurrent(null)
        assertFalse(fence.takeNotice(Connection()))
        fence.bindCurrent(connection); assertTrue(fence.takeNotice(connection))
        assertFalse(fence.takeNotice(connection)); assertTrue(fence.isSuspended(connection))
    }

    @Test fun aFreshTransportDoesNotDisplayAStaleConnectionNotice() {
        val fence = RecoveryPredictionFence<Connection>(); val old = Connection(); val fresh = Connection()
        fence.bindCurrent(old); fence.suspend(old, "old")
        fence.bindCurrent(fresh); assertFalse(fence.takeNotice(old)); assertFalse(fence.takeNotice(fresh))
        fence.suspend(fresh, "fresh"); assertTrue(fence.takeNotice(fresh))
    }

    @Test fun normalUseAfterAProvedDrainInvalidatesOnlyDiagnosticProofNotTheTransport() {
        val connection = Connection(); val fence = RecoveryPredictionFence<Connection>()
        val resume = RecoveryResumeGate(); fence.bindCurrent(connection)
        resume.begin(true); resume.inputSampled()
        assertEquals(RecoveryResumeGate.Completion.END_PROVED_DRAIN_GRACE, resume.naturalTickEndSent())
        resume.invalidateProof() // Ordinary native block use does not create issued warp debt.
        assertFalse(resume.pending)
        assertFalse(fence.isSuspended(connection))
        assertFalse(fence.takeNotice(connection))
        assertEquals(RecoveryResumeGate.Completion.WAITING, resume.naturalTickEndSent())
    }

    @Test fun worldAndPlayerReplacementOnTheSameConnectionCannotResetTheFence() {
        val fence = RecoveryPredictionFence<Connection>(); val connection = Connection()
        fence.bindCurrent(connection); fence.suspend(connection, "discarded_use")
        repeat(5) { assertFalse(fence.bindCurrent(connection)); assertTrue(fence.isSuspended(connection)) }
    }

    @Test fun togglesAndExternalHandoffsCannotClearTheConnectionState() {
        val fence = RecoveryPredictionFence<Connection>(); val connection = Connection()
        fence.bindCurrent(connection); fence.suspend(connection, "unresolved")
        // Those operations do not replace the native Connection and have no clear API.
        repeat(4) { fence.bindCurrent(connection); assertEquals("unresolved", fence.reason(connection)) }
        assertTrue(fence.isSuspended(connection))
    }

    @Test fun positivelyCurrentFreshConnectionClearsSuspensionExactlyOnce() {
        val fence = RecoveryPredictionFence<Connection>(); val old = Connection(); val fresh = Connection()
        fence.bindCurrent(old); fence.suspend(old, "old_use")
        assertTrue(fence.bindCurrent(fresh)); assertFalse(fence.isSuspended(fresh))
        assertFalse(fence.isSuspended(old)); assertNull(fence.reason(fresh))
        assertFalse(fence.bindCurrent(fresh))
    }

    @Test fun staleFailureCannotSuspendTheFreshConnectionOrRebindItsOwner() {
        val fence = RecoveryPredictionFence<Connection>(); val old = Connection(); val fresh = Connection()
        fence.bindCurrent(old); fence.suspend(old, "old")
        fence.bindCurrent(fresh); assertFalse(fence.suspend(old, "late_callback"))
        assertFalse(fence.isSuspended(fresh)); assertTrue(fence.suspend(fresh, "new_failure"))
        assertEquals("new_failure", fence.reason(fresh))
    }

    @Test fun repeatedFailuresDoNotReplaceTheFirstReasonOrRepeatItsNoticeSignal() {
        val fence = RecoveryPredictionFence<Connection>(); val connection = Connection()
        fence.bindCurrent(connection); assertTrue(fence.suspend(connection, "first"))
        repeat(20) { assertFalse(fence.suspend(connection, "later")) }
        assertEquals("first", fence.reason(connection))
    }

    @Test fun diagnosticReasonIsBoundedWithoutKeepingArbitraryPayloads() {
        val fence = RecoveryPredictionFence<Connection>(); val connection = Connection()
        fence.bindCurrent(connection); fence.suspend(connection, "x".repeat(1000))
        assertEquals("x".repeat(96), fence.reason(connection))
    }

    @Test fun nullAndOtherConnectionQueriesCannotMutateSuspendedState() {
        val fence = RecoveryPredictionFence<Connection>(); val connection = Connection(); val other = Connection()
        fence.bindCurrent(connection); fence.suspend(connection, "pending")
        assertNull(fence.reason(null)); assertNull(fence.reason(other)); assertFalse(fence.suspend(null, "null"))
        assertFalse(fence.suspend(other, "other")); assertTrue(fence.isSuspended(connection))
    }

    @Test fun aQueuedSingleUseGetsItsIssuedGraceAndCanCompleteBothNativeRecoveryContracts() {
        for (contract in listOf(TestResponseContracts.PAIR, TestResponseContracts.COMBINED)) {
            val connection = Connection(); val clock = ServerTickTimeouts<Connection>()
            val fence = RecoveryPredictionFence<Connection>(); fence.bindCurrent(connection)
            val chain = ChainLedger<String, String>(responseContract = contract)
            val resume = RecoveryResumeGate()
            clock.bind(connection); val queuedAt = clock.stamp(connection)!!
            repeat(17) { clock.nativeTickThenPing(connection, 1) }
            assertTrue(clock.startAt(connection, 1, 20, queuedAt))
            chain.add(1, "actual_dispatched_use"); chain.cut(1)
            repeat(2) { assertTrue(clock.nativeTickThenPing(connection, 1).isEmpty()) }
            assertFalse(clock.deadlineReached(connection, queuedAt, 20)!!)
            assertTrue(clock.nativeTickThenPing(connection, 1).isEmpty())
            assertTrue(clock.deadlineReached(connection, queuedAt, 20)!!)
            repeat(16) { assertTrue(clock.nativeTickThenPing(connection, 1).isEmpty()) }
            val expiry = clock.completedClientTick(connection).single()
            assertTrue(clock.ping(connection, 1).isEmpty())
            assertEquals(37L, clock.sample(connection))
            assertTrue(clock.restartAfterExpiry(connection, expiry, listOf(1), 20))
            assertFalse(clock.claim(expiry))
            clock.teleport(connection)
            chain.genuine(1, 41)
            for (step in contract) chain.response(41, step)
            chain.settle(1, 41); clock.cancel(1)
            resume.begin(chain.count == 0)
            assertEquals(RecoveryResumeGate.Completion.WAITING, resume.naturalTickEndSent())
            resume.inputSampled()
            assertEquals(RecoveryResumeGate.Completion.END_PROVED_DRAIN_GRACE, resume.naturalTickEndSent())
            assertFalse(fence.isSuspended(connection))
        }
    }

    @Test fun missingSingleUseResponseHasOnlyOneBoundedRecoveryAndCannotResumePrediction() {
        val connection = Connection(); val clock = ServerTickTimeouts<Connection>()
        val fence = RecoveryPredictionFence<Connection>(); fence.bindCurrent(connection)
        clock.bind(connection); clock.start(connection, 1, 2); clock.nativeTickThenPing(connection, 1)
        val original = clock.nativeTickThenPing(connection, 1).single()
        assertTrue(clock.restartAfterExpiry(connection, original, listOf(1), 20))
        repeat(19) { assertTrue(clock.nativeTickThenPing(connection, 1).isEmpty()) }
        val recovery = clock.nativeTickThenPing(connection, 1).single(); assertTrue(clock.claim(recovery))
        assertTrue(fence.suspend(connection, "recovery_ping_deadline"))
        clock.clear()
        val resume = RecoveryResumeGate(); resume.begin(false); resume.inputSampled()
        assertEquals(RecoveryResumeGate.Completion.KEEP_GRACE, resume.naturalTickEndSent())
        assertTrue(fence.isSuspended(connection))
        repeat(50) { assertTrue(clock.nativeTickThenPing(connection, 1).isEmpty()) }
        assertTrue(fence.isSuspended(connection))
    }
}
