package com.hawkslol.zpbw

import kotlin.test.*

class QueuedInputDeadlineTest {
    @Test fun dispatchKeepsTheOriginalDeadlineIncludingOneTickRemaining() {
        val clock = ServerTickTimeouts<Any>(); val stream = Any(); clock.bind(stream)
        val clicked = clock.sample(stream)!!
        repeat(19) { clock.ping(stream, it + 1) }
        assertTrue(clock.startAt(stream, 1, 20, clicked))
        val expiry = clock.ping(stream, 1).single()
        assertEquals(20, expiry.ticks); assertTrue(clock.claim(expiry))
    }
    @Test fun expiredOrForeignQueuedClickCannotStartANewWarp() {
        val clock = ServerTickTimeouts<Any>(); val old = Any(); clock.bind(old)
        val clicked = clock.sample(old)!!
        repeat(20) { clock.ping(old, 1) }
        assertFalse(clock.startAt(old, 1, 20, clicked))
        clock.bind(Any()); assertNull(clock.sample(old)); assertFalse(clock.startAt(old, 2, 20, clicked))
    }
    @Test fun onlyNonzeroPingsFromThisConnectionConsumeTheQueuedBudget() {
        val clock = ServerTickTimeouts<Any>(); val stream = Any(); clock.bind(stream)
        repeat(100) { clock.ping(stream, 0); clock.ping(Any(), 1) }
        assertEquals(0L, clock.sample(stream))
        clock.ping(stream, -1); assertEquals(1L, clock.sample(stream))
        clock.clear(); assertEquals(1L, clock.sample(stream))
    }
}
