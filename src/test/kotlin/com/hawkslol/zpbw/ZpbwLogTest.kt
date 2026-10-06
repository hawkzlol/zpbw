package com.hawkslol.zpbw

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ZpbwLogTest {
    @Test fun noisyTrafficIsAggregatedWithoutEvictingUsefulEvents() {
        val log = ZpbwLog()
        log.record("INITIALIZED version=test")
        log.record("MISMATCH generation=1 distance=2.5")
        repeat(10_000) { log.record("RETAIN generation=1 packet=$it") }
        val snapshot = log.snapshot()
        assertContains(snapshot, "INITIALIZED version=test")
        assertContains(snapshot, "MISMATCH generation=1 distance=2.5")
        assertContains(snapshot, "RETAIN=10000")
        assertContains(snapshot, "packet=9999")
        assertFalse("packet=0\n" in snapshot)
        assertEquals(32, Regex("RETAIN generation=").findAll(snapshot).count())
        assertEquals(9_968L, log.dropped.get())
        assertEquals(0L, log.errors.get())
    }

    @Test fun lifecycleTrafficDoesNotEvictFailureHistory() {
        val log = ZpbwLog()
        log.record("FALLBACK reason=wrong_position generation=7")
        repeat(2_000) { log.record("PREPARED generation=$it") }
        val snapshot = log.snapshot()
        assertContains(snapshot, "FALLBACK reason=wrong_position generation=7")
        assertContains(snapshot, "PREPARED generation=1999")
        assertFalse("PREPARED generation=0\n" in snapshot)
    }

    @Test fun snapshotsAndOversizedUnicodeRecordsStayBounded() {
        val log = ZpbwLog()
        val detail = "界".repeat(100_000)
        repeat(200) {
            log.record("PREDICTION_RAY $detail")
            log.record("FALLBACK $detail")
            log.record("PASS $detail")
        }
        val snapshot = log.snapshot()
        assertTrue(snapshot.toByteArray(Charsets.UTF_8).size <= ZpbwLog.MAX_SNAPSHOT_BYTES)
        assertContains(snapshot, "[truncated]")
        assertContains(snapshot, "PASS=200")
        assertTrue(log.dropped.get() > 0)
        assertContains(snapshot, "Started:")
        assertTrue(Regex("\\+\\d+us PREDICTION_RAY").containsMatchIn(snapshot))
    }

    @Test fun controlsCannotInjectLinesAndFreeformInputsAreRejected() {
        val log = ZpbwLog()
        log.record("FALLBACK reason=bad\r\ninjected\t\u0000\u2028\u202Eend")
        log.record("ordinary chat text")
        log.record("CHAT private chat")
        log.record("EXCEPTION arbitrary payload")
        val snapshot = log.snapshot()
        assertContains(snapshot, "FALLBACK reason=bad  injected    end")
        assertFalse("private chat" in snapshot)
        assertFalse("arbitrary payload" in snapshot)
        assertFalse("ordinary chat" in snapshot)
        assertFalse(snapshot.any { it == '\r' || it == '\u0000' || it == '\u202E' })
        assertEquals(3L, log.dropped.get())
    }

    @Test fun concurrentRecordingAndSnapshotsHaveConsistentCompleteLines() {
        val log = ZpbwLog()
        val pool = Executors.newFixedThreadPool(5)
        val ready = CountDownLatch(1)
        try {
            val producers = (1..4).map { producer -> pool.submit {
                ready.await()
                repeat(1_000) { log.record("NETTY_ADMITTED producer=$producer packet=$it") }
                log.record("SETTLED producer=$producer")
            } }
            val reader = pool.submit {
                ready.await()
                repeat(200) {
                    val snapshot = log.snapshot()
                    assertTrue(snapshot.toByteArray(Charsets.UTF_8).size <= ZpbwLog.MAX_SNAPSHOT_BYTES)
                    assertTrue(snapshot.endsWith('\n'))
                }
            }
            ready.countDown()
            producers.forEach { it.get(10, TimeUnit.SECONDS) }
            reader.get(10, TimeUnit.SECONDS)
            val snapshot = log.snapshot()
            assertContains(snapshot, "NETTY_ADMITTED=4000")
            (1..4).forEach { assertContains(snapshot, "SETTLED producer=$it") }
            assertEquals(0L, log.errors.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun snapshotsAreCopiesAndCloseNeedsNoWorkerOrFilesystem() {
        val log = ZpbwLog()
        log.record("MODE OFF")
        val before = log.snapshot()
        log.close()
        log.record("MODE REPLAY")
        assertFalse("MODE REPLAY" in before)
        assertContains(log.snapshot(), "MODE REPLAY")
    }
}
