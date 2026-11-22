package com.hawkslol.zpbw

import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NativeTickTimeoutGraceTest {
    @Test fun originalTwentyPingBurstCanReceiveItsGenuineTeleportBeforeTheNativeFloor() {
        val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session)
        assertTrue(clock.start(session, 5, 20))
        for (id in -580 downTo -599) assertTrue(clock.ping(session, id).isEmpty())
        assertEquals(20L, clock.sample(session)); assertTrue(clock.expiredTickets().isEmpty())
        repeat(3) { assertTrue(clock.completedClientTick(session).isEmpty()) }
        clock.teleport(session)
        repeat(100) { assertTrue(clock.completedClientTick(session).isEmpty()); assertTrue(clock.ping(session, 1).isEmpty()) }
        assertTrue(clock.expiredTickets().isEmpty())
    }
    @Test fun aBurstCountsEveryIdAndTheLastNativeBoundaryExpiresOnceWithoutAnotherPing() {
        val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session); clock.start(session, 1, 20)
        repeat(1000) { assertTrue(clock.ping(session, if (it % 2 == 0) -1 else 1).isEmpty()) }
        assertEquals(1000L, clock.sample(session))
        repeat(19) { assertTrue(clock.completedClientTick(session).isEmpty()) }
        val ticket = clock.completedClientTick(session).single()
        assertEquals(1L, ticket.generation); assertEquals(20, ticket.ticks)
        repeat(30) { assertTrue(clock.completedClientTick(session).isEmpty()); assertTrue(clock.ping(session, 1).isEmpty()) }
        assertSame(ticket, clock.expiredTickets().single()); assertTrue(clock.claim(ticket)); assertFalse(clock.claim(ticket))
    }
    @Test fun naturalTicksWithoutEnoughPingsCannotCreateAWallClockFallback() {
        val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session); clock.start(session, 1, 20)
        repeat(1000) { assertTrue(clock.completedClientTick(session).isEmpty()) }
        assertEquals(0L, clock.sample(session))
        repeat(19) { assertTrue(clock.ping(session, 1).isEmpty()) }
        assertEquals(1L, clock.ping(session, 1).single().generation)
    }
    @Test fun ordinaryMatchingCadenceStillExpiresAtTheConfiguredTwentiethPing() {
        for (limit in 2..30) {
            val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session); clock.start(session, 1, limit)
            repeat(limit - 1) { assertTrue(clock.completedClientTick(session).isEmpty()); assertTrue(clock.ping(session, 1).isEmpty()) }
            assertTrue(clock.completedClientTick(session).isEmpty())
            assertEquals(limit, clock.ping(session, 1).single().ticks)
        }
    }
    @Test fun queuedIssueRetainsTheWaitingDeadlineButGetsItsOwnNativeResponseFloor() {
        val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session); val click = clock.stamp(session)!!
        repeat(20) { clock.ping(session, 1) }; repeat(7) { clock.completedClientTick(session) }
        assertEquals(false, clock.deadlineReached(session, click, 20)); assertTrue(clock.startAt(session, 1, 20, click))
        repeat(12) { assertTrue(clock.completedClientTick(session).isEmpty()) }
        assertEquals(false, clock.deadlineReached(session, click, 20))
        assertTrue(clock.completedClientTick(session).isEmpty())
        assertEquals(true, clock.deadlineReached(session, click, 20)); assertEquals(20L, clock.sample(session))
        repeat(6) { assertTrue(clock.completedClientTick(session).isEmpty()) }
        assertEquals(1L, clock.completedClientTick(session).single().generation)
    }
    @Test fun queuedIssueKeepsTheOriginalPingAnchorAfterItsNativeFloorWasAlreadySpent() {
        val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session); val click = clock.stamp(session)!!
        repeat(20) { clock.completedClientTick(session) }; repeat(19) { clock.ping(session, 1) }
        assertTrue(clock.startAt(session, 1, 20, click))
        assertTrue(clock.ping(session, 1).isEmpty())
        assertEquals(true, clock.deadlineReached(session, click, 20))
        repeat(19) { assertTrue(clock.completedClientTick(session).isEmpty()) }
        assertEquals(1L, clock.completedClientTick(session).single().generation)
    }
    @Test fun sustainedQueuedWarpWithSeventeenTicksSpentCanReceiveItsActualResponse() {
        val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session)
        val click = clock.stamp(session)!!
        repeat(61) { clock.ping(session, 1) }; repeat(17) { clock.completedClientTick(session) }
        assertFalse(clock.deadlineReached(session, click, 20)!!)
        assertTrue(clock.startAt(session, 596, 20, click))
        repeat(3) { assertTrue(clock.completedClientTick(session).isEmpty()) }
        assertTrue(clock.deadlineReached(session, click, 20)!!)
        // The original waiting deadline is spent, but the genuinely issued warp is live.
        clock.teleport(session)
        repeat(100) { assertTrue(clock.completedClientTick(session).isEmpty()); assertTrue(clock.ping(session, 1).isEmpty()) }
        assertTrue(clock.expiredTickets().isEmpty())
        clock.cancel(596)
    }
    @Test fun anUnansweredQueuedWarpStillExpiresExactlyOnceAtItsOwnNativeFloor() {
        val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session)
        val click = clock.stamp(session)!!
        repeat(61) { clock.ping(session, 1) }; repeat(17) { clock.completedClientTick(session) }
        assertTrue(clock.startAt(session, 596, 20, click))
        repeat(19) { assertTrue(clock.completedClientTick(session).isEmpty()) }
        val expiry = clock.completedClientTick(session).single()
        assertEquals(596L, expiry.generation); assertEquals(20, expiry.ticks)
        repeat(30) { assertTrue(clock.completedClientTick(session).isEmpty()) }
        assertTrue(clock.claim(expiry)); assertFalse(clock.claim(expiry))
    }
    @Test fun aQueuedDeadlineAlreadySpentInBothDomainsCannotBeRenewedOnIssue() {
        val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session); val click = clock.stamp(session)!!
        repeat(20) { clock.ping(session, 1); clock.completedClientTick(session) }
        assertEquals(true, clock.deadlineReached(session, click, 20)); assertFalse(clock.startAt(session, 1, 20, click))
        assertTrue(clock.expiredTickets().isEmpty())
    }
    @Test fun sameConnectionRebindingRejectsEvenAnUnspentZeroCounterSnapshot() {
        val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session); val old = clock.stamp(session)!!
        clock.bind(session)
        assertEquals(0L, clock.sample(session)); assertNull(clock.deadlineReached(session, old, 20))
        assertFalse(clock.startAt(session, 1, 20, old)); assertTrue(clock.startAt(session, 1, 20, clock.stamp(session)))
    }
    @Test fun zeroPingsForeignConnectionsAndSuspensionCannotAdvanceTheSuccessor() {
        val session = Any(); val old = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(old)
        val stale = clock.stamp(old)!!; clock.suspend(old); clock.bind(session); clock.start(session, 1, 2)
        repeat(100) { clock.ping(session, 0); clock.ping(old, 1); clock.completedClientTick(old) }
        assertEquals(0L, clock.sample(session)); assertEquals(0L, clock.stamp(session)!!.nativeTicks)
        assertNull(clock.deadlineReached(old, stale, 2)); assertFalse(clock.startAt(old, 2, 2, stale))
        repeat(2) { assertTrue(clock.ping(session, 1).isEmpty()) }
        assertTrue(clock.completedClientTick(session).isEmpty()); assertEquals(1L, clock.completedClientTick(session).single().generation)
    }
    @Test fun staggeredFiveWayFloorsPreserveArrivedPredecessorOwnership() {
        val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session)
        for (id in 1L..5L) { clock.start(session, id, 20); assertTrue(clock.completedClientTick(session).isEmpty()) }
        repeat(40) { assertTrue(clock.ping(session, 1).isEmpty()) }
        clock.teleport(session); clock.teleport(session)
        repeat(16) { assertTrue(clock.completedClientTick(session).isEmpty()) }
        val third = clock.completedClientTick(session).single(); assertEquals(3L, third.generation)
        assertFalse(clock.claim(third)); clock.cancel(1); clock.cancel(2); assertTrue(clock.claim(third))
        assertEquals(4L, clock.completedClientTick(session).single().generation)
        assertEquals(5L, clock.completedClientTick(session).single().generation)
    }
    @Test fun recoveryReplacementHasOneFreshFloorAndRetainsActualArrivalBarriers() {
        val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session)
        clock.start(session, 1, 2); clock.start(session, 2, 2)
        repeat(2) { clock.completedClientTick(session) }; clock.ping(session, 1); val old = clock.ping(session, 1).first()
        clock.teleport(session); assertTrue(clock.restartAfterExpiry(session, old, listOf(1, 2), 20)); assertFalse(clock.claim(old))
        repeat(40) { assertTrue(clock.ping(session, 1).isEmpty()) }
        repeat(19) { assertTrue(clock.completedClientTick(session).isEmpty()) }
        val fresh = clock.completedClientTick(session).single(); assertEquals(2L, fresh.generation)
        assertFalse(clock.claim(fresh)); clock.cancel(1); assertTrue(clock.claim(fresh))
    }
    @Test fun cleanupInvalidatesFloorTicketsAndCaptureStampsAtEveryBindingTransition() {
        for (transition in listOf<(ServerTickTimeouts<Any>, Any) -> Unit>(
            { clock, session -> clock.bind(session) }, { clock, session -> clock.suspend(session); clock.bind(session) },
            { clock, session -> clock.bind(null); clock.bind(session) })) {
            val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session); val old = clock.stamp(session)!!
            clock.start(session, 1, 2); repeat(2) { clock.ping(session, 1) }; clock.completedClientTick(session)
            val ticket = clock.completedClientTick(session).single(); transition(clock, session)
            assertFalse(clock.claim(ticket)); assertFalse(clock.startAt(session, 1, 2, old)); assertTrue(clock.expiredTickets().isEmpty())
        }
    }
    @Test fun simultaneousLastPingAndNaturalBoundaryCreateExactlyOneTicket() {
        val pool = Executors.newFixedThreadPool(2)
        try { repeat(250) {
            val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session); clock.start(session, 1, 2)
            clock.ping(session, 1); clock.completedClientTick(session); val start = CyclicBarrier(2)
            val ping = pool.submit<List<ServerTickTimeouts.Expiry>> { start.await(); clock.ping(session, 1) }
            val tick = pool.submit<List<ServerTickTimeouts.Expiry>> { start.await(); clock.completedClientTick(session) }
            val results = ping.get(5, TimeUnit.SECONDS) + tick.get(5, TimeUnit.SECONDS)
            assertEquals(1, results.size); assertSame(results.single(), clock.expiredTickets().single()); assertTrue(clock.claim(results.single()))
        } } finally { pool.shutdownNow() }
    }
    @Test fun genuineArrivalRacingTheLastNaturalBoundaryHasOnlyTheTwoOrderedOutcomes() {
        val pool = Executors.newFixedThreadPool(2)
        try { repeat(250) {
            val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session); clock.start(session, 1, 2)
            repeat(20) { clock.ping(session, 1) }; clock.completedClientTick(session); val start = CyclicBarrier(2)
            val tick = pool.submit<List<ServerTickTimeouts.Expiry>> { start.await(); clock.completedClientTick(session) }
            val arrival = pool.submit { start.await(); clock.teleport(session) }
            val result = tick.get(5, TimeUnit.SECONDS); arrival.get(5, TimeUnit.SECONDS)
            if (result.isEmpty()) assertTrue(clock.expiredTickets().isEmpty())
            else { assertTrue(clock.claim(result.single())); assertFalse(clock.claim(result.single())) }
        } } finally { pool.shutdownNow() }
    }
    @Test fun graceAndGenuineArrivalCannotReleaseHeldActionsBeforeEitherNativeContractCompletes() {
        for (contract in listOf(TestResponseContracts.PAIR, TestResponseContracts.COMBINED)) {
            val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session); clock.start(session, 1, 20)
            val ledger = ChainLedger<String, Any>(responseContract = contract); val action = Any()
            ledger.add(1, "warp"); ledger.cut(1); ledger.retain(action, action = true)
            repeat(30) { assertTrue(clock.ping(session, 1).isEmpty()) }; clock.teleport(session)
            repeat(30) { assertTrue(clock.completedClientTick(session).isEmpty()) }
            assertEquals(1, ledger.size); ledger.genuine(1, 8)
            for (step in contract) { assertFailsWith<IllegalStateException> { ledger.settle(1, 8) }; ledger.response(8, step) }
            assertSame(action, ledger.settle(1, 8).single()); clock.cancel(1); assertTrue(clock.expiredTickets().isEmpty())
        }
    }
    @Test fun onlyTheExistingCompletedClientTickCallbackAdvancesTheFloor() {
        val runtime = Files.readString(EffectiveSources.path("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
        val client = Files.readString(EffectiveSources.path("src/main/kotlin/com/hawkslol/zpbw/ZpbwClient.kt"))
        assertEquals(1, Regex("warpTimeouts\\.completedClientTick").findAll(runtime).count())
        val tick = runtime.substringAfter("fun tick() {").substringBefore("fun takeQueuedUse()")
        assertContains(tick, "warpTimeouts.completedClientTick(connection)")
        assertContains(client, "ClientTickEvents.END_CLIENT_TICK.register { ZpbwRuntime.tick() }")
        assertContains(runtime, "warpTimeouts.stamp(stream)")
        assertContains(runtime, "warpTimeouts.startAt(stream, generation, budget?.ticks ?: timeoutTicks, budget?.deadline)")
        assertContains(runtime, "warpTimeouts.deadlineReached(it.stream, it.deadline, it.ticks)")
    }
}
