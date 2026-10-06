package com.hawkslol.zpbw

import kotlin.test.*

class PredictionOwnershipTest {
    private val ready = PredictionOwnership.DrainState()
    private fun bound() = PredictionOwnership().apply { bind(Any(), Any(), Any()) }

    @Test fun ownershipIsIdentityBasedAndIdempotent() {
        class EqualToken { override fun equals(other: Any?) = other is EqualToken; override fun hashCode() = 1 }
        val lease = bound(); val owner = EqualToken(); val impostor = EqualToken()
        assertTrue(lease.tryAcquire(owner, true, ready))
        assertTrue(lease.tryAcquire(owner, true, ready.copy(pending = 5)))
        assertFalse(lease.tryAcquire(impostor, true, ready))
        assertFalse(lease.owns(impostor, true)); assertFalse(lease.release(impostor, true))
        assertTrue(lease.owns(owner, true)); assertTrue(lease.release(owner, true))
        assertFalse(lease.release(owner, true)); assertFalse(lease.active())
        assertTrue(lease.tryAcquire(impostor, true, ready))
    }
    @Test fun everyUndrainedBoundaryRejectsWithoutReserving() {
        val blockers = listOf(ready.copy(pending = 1), ready.copy(retained = 1), ready.copy(actions = true),
            ready.copy(handling = true), ready.copy(replaying = true), ready.copy(sourcePhysics = true),
            ready.copy(recovery = true), ready.copy(inputCommitted = true))
        for (state in blockers) {
            val lease = bound()
            assertFalse(lease.tryAcquire(Any(), true, state), state.toString())
            assertFalse(lease.active())
            assertTrue(lease.tryAcquire(Any(), true, ready))
        }
    }
    @Test fun nullAndOffThreadCallsCannotAcquireInspectOrRelease() {
        val lease = bound(); val owner = Any()
        assertFalse(lease.tryAcquire(null, true, ready))
        assertFalse(lease.tryAcquire(owner, false, ready)); assertFalse(lease.active())
        assertTrue(lease.tryAcquire(owner, true, ready))
        assertFalse(lease.owns(owner, false)); assertFalse(lease.release(owner, false))
        assertFalse(lease.release(null, true)); assertTrue(lease.owns(owner, true))
        assertFalse(lease.tryAcquire(owner, false, ready))
    }
    @Test fun everySessionIdentityChangeInvalidatesEvenEqualObjects() {
        for (index in 0..2) {
            val lease = PredictionOwnership(); val identities = Array<Any>(3) { String(charArrayOf('x')) }
            val owner = Any()
            lease.bind(identities[0], identities[1], identities[2])
            assertTrue(lease.tryAcquire(owner, true, ready))
            lease.bind(identities[0], identities[1], identities[2]); assertTrue(lease.owns(owner, true))
            identities[index] = String(charArrayOf('x'))
            lease.bind(identities[0], identities[1], identities[2])
            assertFalse(lease.owns(owner, true)); assertFalse(lease.release(owner, true))
            assertTrue(lease.tryAcquire(Any(), true, ready))
        }
    }
    @Test fun disconnectedAndMissingWorldSessionsCannotAcquire() {
        val lease = PredictionOwnership(); val c = Any(); val w = Any(); val p = Any(); val owner = Any()
        for (parts in listOf(listOf(null, w, p), listOf(c, null, p), listOf(c, w, null))) {
            lease.bind(parts[0], parts[1], parts[2]); assertFalse(lease.tryAcquire(owner, true, ready))
        }
        lease.bind(c, w, p); assertTrue(lease.tryAcquire(owner, true, ready))
        lease.invalidateConnection(Any()); assertTrue(lease.owns(owner, true))
        lease.invalidateConnection(c); assertFalse(lease.owns(owner, true)); assertFalse(lease.tryAcquire(owner, true, ready))
        val next = Any(); lease.bind(next, w, p); assertTrue(lease.tryAcquire(owner, true, ready))
        lease.invalidateConnection(c); assertTrue(lease.owns(owner, true))
    }
    @Test fun actualChainAndInputCommitmentMustFinishBeforeOwnershipTransfers() {
        val lease = bound(); val owner = Any(); val chain = ChainLedger<String, String>(); val latch = TickInputLatch<String>()
        fun acquire() = lease.tryAcquire(owner, true, PredictionOwnership.DrainState(
            pending = chain.count, retained = chain.size, actions = chain.hasActions,
            handling = chain.handling, inputCommitted = latch.value != null || latch.deferred != null))
        chain.add(1, "warp"); chain.cut(1); chain.retain("click", action = true)
        assertFalse(acquire()); chain.genuine(1, 42); assertFalse(acquire())
        chain.response(42, true); assertFalse(acquire()); chain.response(42, false)
        assertEquals(listOf("click"), chain.settle(1, 42))
        latch.commit("sneak"); assertFalse(acquire())
        latch.sample("unsneak"); latch.finishTick(); assertFalse(acquire())
        latch.commitDeferred(); latch.sample("unsneak"); latch.finishTick()
        assertTrue(acquire()); assertTrue(lease.release(owner, true))
        // The same ledger can predict normally again; handoff never toggles or retires it.
        chain.add(2, "manual"); chain.cut(2); assertEquals(1, chain.count)
        assertFalse(acquire())
    }
}
