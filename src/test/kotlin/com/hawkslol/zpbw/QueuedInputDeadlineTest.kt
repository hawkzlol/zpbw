package com.hawkslol.zpbw

import kotlin.test.*

class QueuedInputDeadlineTest {
    @Test fun dispatchPreservesOneTickOfWaitingBudgetButGrantsTheIssuedResponseItsNativeFloor() {
        val clock = ServerTickTimeouts<Any>(); val stream = Any(); clock.bind(stream)
        val clicked = clock.stamp(stream)!!
        repeat(19) { clock.nativeTickThenPing(stream, it + 1) }
        assertTrue(clock.startAt(stream, 1, 20, clicked))
        assertTrue(clock.nativeTickThenPing(stream, 1).isEmpty())
        assertTrue(clock.deadlineReached(stream, clicked, 20)!!)
        repeat(18) { assertTrue(clock.nativeTickThenPing(stream, 1).isEmpty()) }
        // The original Ping budget is already spent: the final native boundary owns expiry.
        val expiry = clock.completedClientTick(stream).single()
        assertTrue(clock.ping(stream, 1).isEmpty())
        assertEquals(20, expiry.ticks); assertTrue(clock.claim(expiry))
    }
    @Test fun expiredOrForeignQueuedClickCannotStartANewWarp() {
        val clock = ServerTickTimeouts<Any>(); val old = Any(); clock.bind(old)
        val clicked = clock.stamp(old)!!
        repeat(20) { clock.nativeTickThenPing(old, 1) }
        assertFalse(clock.startAt(old, 1, 20, clicked))
        clock.bind(Any()); assertNull(clock.sample(old)); assertFalse(clock.startAt(old, 2, 20, clicked))
    }
    @Test fun onlyNonzeroPingsFromThisConnectionConsumeTheQueuedBudget() {
        val clock = ServerTickTimeouts<Any>(); val stream = Any(); clock.bind(stream)
        repeat(100) { clock.nativeTickThenPing(stream, 0); clock.nativeTickThenPing(Any(), 1) }
        assertEquals(0L, clock.sample(stream))
        clock.nativeTickThenPing(stream, -1); assertEquals(1L, clock.sample(stream))
        clock.clear(); assertEquals(1L, clock.sample(stream))
    }
}
