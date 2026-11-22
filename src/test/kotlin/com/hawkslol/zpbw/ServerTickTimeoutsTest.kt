package com.hawkslol.zpbw

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ServerTickTimeoutsTest {
    @Test fun nativeMotionRecoveryPreservesQueuedReceiptsAndDoesNotAdvanceTheClock() {
        val session=Any();val clock=ServerTickTimeouts<Any>();clock.bind(session)
        clock.start(session,1,20);clock.start(session,2,20)
        repeat(7){clock.nativeTickThenPing(session,1)}
        clock.teleport(session)
        assertTrue(clock.restartForRecovery(session,listOf(1,2),20))
        assertEquals(7L,clock.sample(session))
        repeat(19){assertTrue(clock.nativeTickThenPing(session,1).isEmpty())}
        val expiry=clock.nativeTickThenPing(session,1).single()
        assertEquals(2L,expiry.generation)
        assertFalse(clock.claim(expiry));clock.cancel(1);assertTrue(clock.claim(expiry))
    }
    @Test fun nativeMotionRecoveryInvalidatesOldExpiryCallbacksWithoutFabricatingAnArrival() {
        val session=Any();val clock=ServerTickTimeouts<Any>();clock.bind(session)
        clock.start(session,1,2);clock.nativeTickThenPing(session,1);val old=clock.nativeTickThenPing(session,1).single()
        assertTrue(clock.restartForRecovery(session,listOf(1),20))
        assertFalse(clock.claim(old));repeat(19){assertTrue(clock.nativeTickThenPing(session,1).isEmpty())}
        val fresh=clock.nativeTickThenPing(session,1).single();assertEquals(1L,fresh.generation);assertTrue(clock.claim(fresh))
    }
    @Test fun nativeMotionCannotRestartAnotherSessionOrAnIncompleteOrReorderedLedger() {
        val session=Any();val clock=ServerTickTimeouts<Any>();clock.bind(session)
        clock.start(session,1,2);clock.start(session,2,2)
        assertFalse(clock.restartForRecovery(Any(),listOf(1,2),20))
        assertFalse(clock.restartForRecovery(session,listOf(1),20))
        assertFalse(clock.restartForRecovery(session,listOf(2,1),20))
        clock.nativeTickThenPing(session,1);assertEquals(listOf(1L,2L),clock.nativeTickThenPing(session,1).map{it.generation})
    }
    @Test fun nativeMotionRestartAndConcurrentRealArrivalKeepTheSameFifoOwner() {
        val pool=Executors.newFixedThreadPool(2)
        try{repeat(200){
            val session=Any();val clock=ServerTickTimeouts<Any>();clock.bind(session)
            clock.start(session,1,20);clock.start(session,2,20)
            val start=CyclicBarrier(2)
            val restart=pool.submit<Boolean>{start.await();clock.restartForRecovery(session,listOf(1,2),20)}
            val arrival=pool.submit{start.await();clock.teleport(session)}
            assertTrue(restart.get(5,TimeUnit.SECONDS));arrival.get(5,TimeUnit.SECONDS)
            repeat(19){assertTrue(clock.nativeTickThenPing(session,1).isEmpty())}
            val second=clock.nativeTickThenPing(session,1).single();assertEquals(2L,second.generation)
            assertFalse(clock.claim(second));clock.cancel(1);assertTrue(clock.claim(second))
        }}finally{pool.shutdownNow()}
    }

    @Test fun racingRecoveryRestartAndRealArrivalCannotShiftTheReceiptToASuccessor() {
        val pool=Executors.newFixedThreadPool(2)
        try { repeat(200) {
            val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session)
            clock.start(session,1,2); clock.start(session,2,20)
            clock.nativeTickThenPing(session,1); val old=clock.nativeTickThenPing(session,1).single()
            val start=CyclicBarrier(2)
            val restart=pool.submit<Boolean> { start.await(); clock.restartAfterExpiry(session,old,listOf(1,2),20) }
            val arrival=pool.submit { start.await(); clock.teleport(session) }
            assertTrue(restart.get(5,TimeUnit.SECONDS)); arrival.get(5,TimeUnit.SECONDS)
            repeat(19) { assertTrue(clock.nativeTickThenPing(session,1).isEmpty()) }
            val suffix=clock.nativeTickThenPing(session,1).single(); assertEquals(2L,suffix.generation)
            assertFalse(clock.claim(suffix)); clock.cancel(1); assertTrue(clock.claim(suffix))
        } } finally { pool.shutdownNow() }
    }

    @Test fun recoveryRestartRetiresAllOldCallbacksAndUsesOneFreshBound() {
        val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session)
        clock.start(session,1,2); clock.start(session,2,2)
        clock.nativeTickThenPing(session,1); val old=clock.nativeTickThenPing(session,1)
        assertFalse(clock.restartAfterExpiry(session,old.first(),listOf(1),20))
        assertFalse(clock.restartAfterExpiry(session,old.first(),listOf(1,2,3),20))
        assertTrue(clock.restartAfterExpiry(session,old.first(),listOf(1,2),20))
        assertTrue(clock.expiredTickets().isEmpty()); old.forEach { assertFalse(clock.claim(it)) }
        repeat(19) { assertTrue(clock.nativeTickThenPing(session,0).isEmpty()); assertTrue(clock.nativeTickThenPing(session,-1).isEmpty()) }
        val fresh=clock.nativeTickThenPing(session,-1)
        assertEquals(listOf(1L,2L),fresh.map { it.generation })
        fresh.forEach { assertEquals(20,it.ticks); assertTrue(clock.claim(it)) }
    }

    @Test fun queuedNativeArrivalSurvivesAtomicRecoveryRestart() {
        val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session)
        clock.start(session,1,2); clock.start(session,2,20)
        clock.nativeTickThenPing(session,1); val old=clock.nativeTickThenPing(session,1).single()
        clock.teleport(session) // Actual arrival follows the old deadline, before its main task.
        assertTrue(clock.restartAfterExpiry(session,old,listOf(1,2),20))
        repeat(19) { assertTrue(clock.nativeTickThenPing(session,1).isEmpty()) }
        val suffix=clock.nativeTickThenPing(session,1).single(); assertEquals(2L,suffix.generation)
        assertFalse(clock.claim(suffix), "A fresh recovery callback cannot overtake the already queued native teleport")
        clock.cancel(1); assertTrue(clock.claim(suffix))
    }

    @Test fun nativeArrivalAfterRestartStillOwnsTheOriginalFifoGeneration() {
        val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session)
        for(id in 1L..5L) clock.start(session,id,2)
        clock.nativeTickThenPing(session,1); val old=clock.nativeTickThenPing(session,1)
        assertTrue(clock.restartAfterExpiry(session,old.first(),(1L..5L).toList(),20))
        repeat(5) { index -> clock.teleport(session); clock.cancel(index+1L) }
        repeat(100) { assertTrue(clock.nativeTickThenPing(session,1).isEmpty()) }
        assertTrue(clock.expiredTickets().isEmpty()); old.forEach { assertFalse(clock.claim(it)) }
    }

    @Test fun recoveryRestartCannotRearmALiveStaleOrDifferentSessionTicket() {
        val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session)
        clock.start(session,1,2); clock.nativeTickThenPing(session,1); val old=clock.nativeTickThenPing(session,1).single()
        assertFalse(clock.restartAfterExpiry(Any(),old,listOf(1),20))
        assertTrue(clock.restartAfterExpiry(session,old,listOf(1),20))
        assertFalse(clock.restartAfterExpiry(session,old,listOf(1),20))
        clock.bind(session); assertFalse(clock.restartAfterExpiry(session,old,listOf(1),20))
    }

    @Test fun recoveryRestartDoesNotOvertakeAnEarlierReceivedNativeHandler() {
        val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session)
        clock.start(session,1,20); clock.start(session,2,2); clock.teleport(session)
        clock.nativeTickThenPing(session,1); val suffix=clock.nativeTickThenPing(session,1).single()
        assertFalse(clock.restartAfterExpiry(session,suffix,listOf(1,2),20))
        clock.cancel(1); assertTrue(clock.restartAfterExpiry(session,suffix,listOf(2),20))
    }

    @Test fun separateMainTaskQueueCannotOvertakeAnArrivedPredecessor() {
        val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session)
        clock.start(session,1,20); clock.start(session,2,2)
        clock.teleport(session) // First packet is queued for main-thread handling.
        clock.nativeTickThenPing(session,1); val expiry=clock.nativeTickThenPing(session,1).single()
        assertFalse(clock.claim(expiry), "Ordinary task queue ran before packet processor")
        assertEquals(listOf(expiry),clock.expiredTickets())
        clock.cancel(1) // Complete the predecessor's genuine response first.
        assertTrue(clock.claim(expiry))
    }
    @Test fun packetQueueRunningBeforeExpiryTaskStillSeesTheNettyDeadline() {
        val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session)
        clock.start(session,1,2); clock.nativeTickThenPing(session,1); val expiry=clock.nativeTickThenPing(session,1).single()
        clock.teleport(session)
        assertSame(expiry,clock.expiredTickets().single())
        assertTrue(clock.claim(clock.expiredTickets().single())) // beforeGenuine drains before matching.
        clock.clear(); assertFalse(clock.claim(expiry)) // Later ordinary callback cannot double-report.
    }
    @Test fun mismatchingPredecessorCancelsDeferredSuffixExpiryWithoutExtraFailure() {
        val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session)
        clock.start(session,1,20); clock.start(session,2,2); clock.teleport(session)
        clock.nativeTickThenPing(session,1); val expiry=clock.nativeTickThenPing(session,1).single()
        assertFalse(clock.claim(expiry)); clock.clear() // Wrong first teleport recovers whole chain.
        assertFalse(clock.claim(expiry)); assertTrue(clock.expiredTickets().isEmpty())
    }
    @Test fun everySupportedLimitExpiresOnExactlyThatNonzeroPacketOnce() {
        for (limit in 2..30) {
            val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session)
            assertTrue(clock.start(session, 1, limit))
            repeat(limit - 1) {
                assertTrue(clock.nativeTickThenPing(session, 0).isEmpty())
                assertTrue(clock.nativeTickThenPing(session, if (it % 2 == 0) -7 else 7).isEmpty())
            }
            val expiry = clock.nativeTickThenPing(session, Int.MIN_VALUE).single()
            assertEquals(1L, expiry.generation); assertEquals(limit, expiry.ticks)
            repeat(100) { assertTrue(clock.nativeTickThenPing(session, Int.MAX_VALUE).isEmpty()) }
            assertTrue(clock.claim(expiry)); assertFalse(clock.claim(expiry))
        }
    }
    @Test fun zeroIdsOldConnectionsAndIdleTimeCannotAdvanceAnyWarp() {
        val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session)
        clock.start(session, 1, 2)
        repeat(10000) { assertTrue(clock.nativeTickThenPing(session, 0).isEmpty()); assertTrue(clock.nativeTickThenPing(Any(), 1).isEmpty()) }
        assertTrue(clock.nativeTickThenPing(session, 1).isEmpty()); assertEquals(1L, clock.nativeTickThenPing(session, 1).single().generation)
    }
    @Test fun teleportAtEveryPreDeadlineOffsetCancelsBeforeMainThreadRuns() {
        for (limit in 2..30) for (offset in 0 until limit) {
            val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session)
            clock.start(session, 1, limit)
            repeat(offset) { assertTrue(clock.nativeTickThenPing(session, 1).isEmpty()) }
            clock.teleport(session) // Receipt is sufficient; the main thread remains blocked.
            repeat(60) { assertTrue(clock.nativeTickThenPing(session, 1).isEmpty()) }
        }
    }
    @Test fun lateArrivalDoesNotEraseExpiryAlreadyOrderedBeforeItsHandler() {
        val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session)
        clock.start(session, 1, 2); clock.nativeTickThenPing(session, 1)
        val ticket = clock.nativeTickThenPing(session, 1).single()
        clock.teleport(session)
        assertTrue(clock.claim(ticket)); assertFalse(clock.claim(ticket))
    }
    @Test fun fiveWarpTimersStartIndependentlyAndConfirmInReceiptOrder() {
        val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session)
        for (id in 1L..5L) { clock.start(session, id, 20); assertTrue(clock.nativeTickThenPing(session, 1).isEmpty()) }
        clock.teleport(session); clock.teleport(session)
        repeat(16) { assertTrue(clock.nativeTickThenPing(session, 1).isEmpty()) }
        assertEquals(3L, clock.nativeTickThenPing(session, 1).single().generation)
        clock.teleport(session) // Late response for expired third warp, not the fourth.
        assertEquals(4L, clock.nativeTickThenPing(session, 1).single().generation)
        clock.teleport(session)
        assertEquals(5L, clock.nativeTickThenPing(session, 1).single().generation)
    }
    @Test fun changingSettingOnlyAffectsNewTimersAndShorterDependentCanExpireFirst() {
        val session = Any(); val clock = ServerTickTimeouts<Any>(); clock.bind(session)
        clock.start(session, 1, 30); clock.nativeTickThenPing(session, 1); clock.start(session, 2, 2)
        assertTrue(clock.nativeTickThenPing(session, 1).isEmpty())
        val ticket = clock.nativeTickThenPing(session, 1).single(); assertEquals(2L, ticket.generation)
        assertTrue(clock.claim(ticket)); clock.clear() // Existing recovery cancels dependent chain.
        repeat(100) { assertTrue(clock.nativeTickThenPing(session, 1).isEmpty()) }
    }
    @Test fun allRecoveryPathsInvalidateQueuedExpiryEvenWhenGenerationIsReused() {
        for (reset in listOf<(ServerTickTimeouts<Any>, Any) -> Unit>(
            { c, _ -> c.clear() }, { c, s -> c.bind(s) }, { c, s -> c.suspend(s); c.bind(s) },
            { c, _ -> c.cancel(1) }, { c, s -> c.bind(null); c.bind(s) })) {
            val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session)
            clock.start(session, 1, 2); clock.nativeTickThenPing(session,1); val old=clock.nativeTickThenPing(session,1).single()
            reset(clock,session); clock.start(session,1,2)
            assertFalse(clock.claim(old)); assertTrue(clock.nativeTickThenPing(session,1).isEmpty())
            assertTrue(clock.claim(clock.nativeTickThenPing(session,1).single()))
        }
    }
    @Test fun worldTransitionSuspendsStartsUntilMainThreadBindsNewWorld() {
        val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session)
        clock.start(session,1,20); clock.suspend(session)
        assertFalse(clock.start(session,2,20)); repeat(40) { assertTrue(clock.nativeTickThenPing(session,1).isEmpty()) }
        clock.bind(session); assertTrue(clock.start(session,3,2))
        clock.suspend(Any()); clock.teleport(Any())
        assertTrue(clock.nativeTickThenPing(session,1).isEmpty()); assertEquals(3L,clock.nativeTickThenPing(session,1).single().generation)
    }
    @Test fun invalidLimitsDuplicatesAndSixthPendingAreRejectedWithoutResettingOthers() {
        val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session)
        for (bad in listOf(Int.MIN_VALUE,0,1,31,Int.MAX_VALUE)) assertFailsWith<IllegalArgumentException> { clock.start(session,1,bad) }
        for (id in 1L..5L) clock.start(session,id,2)
        assertFailsWith<IllegalStateException> { clock.start(session,1,2) }
        assertFailsWith<IllegalStateException> { clock.start(session,6,2) }
        clock.nativeTickThenPing(session,1); assertEquals((1L..5L).toList(),clock.nativeTickThenPing(session,1).map { it.generation })
    }
    @Test fun realConcurrentArrivalAndDeadlineHaveOnlyTwoValidOutcomes() {
        val pool=Executors.newFixedThreadPool(2)
        try { repeat(500) {
            val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session)
            clock.start(session,1,2); clock.nativeTickThenPing(session,1)
            val start=CyclicBarrier(2)
            val ping=pool.submit<List<ServerTickTimeouts.Expiry>> { start.await(); clock.nativeTickThenPing(session,1) }
            val arrival=pool.submit { start.await(); clock.teleport(session) }
            val result=ping.get(5,TimeUnit.SECONDS); arrival.get(5,TimeUnit.SECONDS)
            if (result.isNotEmpty()) { assertTrue(clock.claim(result.single())); assertFalse(clock.claim(result.single())) }
            repeat(3) { assertTrue(clock.nativeTickThenPing(session,1).isEmpty()) }
        } } finally { pool.shutdownNow() }
    }
}
