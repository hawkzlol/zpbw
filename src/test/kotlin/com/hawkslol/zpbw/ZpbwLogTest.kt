package com.hawkslol.zpbw

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ZpbwLogTest {
    @Test fun realNativeOccurrencePrefixesPreservePacketKindCounters() {
        val log=ZpbwLog()
        log.record("NETTY_ADMITTED occurrence=2465 action=null ServerboundClientTickEndPacket")
        log.record("NETTY_ADMITTED occurrence=2467 action=19 Pos xyz=1,2,3 yaw=NaN pitch=NaN")
        log.record("NETTY_ADMITTED occurrence=null action=null Accept id=3")
        log.record("SEND_REQUEST occurrence=2470 action=null kind=Pos xyz=1,2,3")
        log.record("SEND_REQUEST occurrence=2471 action=20 kind=Use sequence=42")
        val report=log.snapshot()
        assertContains(report,"NETTY_ADMITTED:ServerboundClientTickEndPacket=1")
        assertContains(report,"NETTY_ADMITTED:Pos=1");assertContains(report,"NETTY_ADMITTED:Accept=1")
        assertContains(report,"SEND_REQUEST:Pos=1");assertContains(report,"SEND_REQUEST:Use=1")
        assertFalse("NETTY_ADMITTED:detail=" in report);assertFalse("SEND_REQUEST:detail=" in report)
    }
    @Test fun unrelatedDomainsAndIssueContextsSurviveRepetitiveGeometryDiagnostics() {
        val log=ZpbwLog()
        log.record("MODE REPLAY")
        log.record("INPUT_QUEUED kind=warp_click capture=17")
        log.record("NETTY_ADMITTED Pos xyz=1,2,3")
        log.record("MISMATCH generation=8 reason=position_mismatch")
        log.record("RECOVERY_WAIT wait=fresh_input")
        log.record("RECOVERY_PREDICTION_RESUMED nativeAuthority=true")
        repeat(2000){log.record("PREDICTION_RAY attempt=$it step=voxel")}
        val report=log.snapshot()
        assertContains(report,"MODE REPLAY");assertContains(report,"INPUT_QUEUED kind=warp_click capture=17")
        assertContains(report,"RECOVERY_WAIT wait=fresh_input")
        assertContains(report,"Issue context: MISMATCH")
        assertContains(report,"MISMATCH generation=8 reason=position_mismatch")
        assertContains(report,"PREDICTION_RAY=2000")
        assertContains(report,"NETTY_ADMITTED:Pos=1")
        assertContains(report,"Chronological non-wire tail")
    }
    @Test fun repetitiveOneIssueDoesNotDisplaceOtherIssueTypesAndWireLossIsExplicit() {
        val log=ZpbwLog()
        log.record("TIMEOUT generation=1 ticks=20")
        repeat(1000){log.record("MISMATCH generation=$it")}
        repeat(1000){log.record("NETTY_ADMITTED Pos xyz=1,2,3")}
        val report=log.snapshot()
        assertContains(report,"Issue context: TIMEOUT")
        assertContains(report,"Issue context: MISMATCH")
        assertContains(report,"TIMEOUT=1");assertContains(report,"MISMATCH=1000")
        assertContains(report,"wire=968")
        assertTrue(report.toByteArray().size<=ZpbwLog.MAX_SNAPSHOT_BYTES)
    }
    @Test fun everyDomainCountersAndEightUnicodeIssueContextsRemainBounded() {
        val log=ZpbwLog();val text="界".repeat(1000)
        for(event in listOf("MISMATCH","FALLBACK","TIMEOUT","NATIVE_HANDLER_ABORTED","EXPERIMENTAL_STALL",
            "EXPERIMENTAL_STALE_SETTLEMENT","RECOVERY_NATIVE_OWNERSHIP_RETIRED","INPUT_TIMEOUT")) {
            repeat(150){log.record("MODE $text");log.record("INPUT_QUEUED $text");log.record("RECOVERY_WAIT $text")
                log.record("PREDICTION_RAY $text");log.record("NETTY_ADMITTED Pos $text");log.record("$event $text")}
        }
        repeat(200){log.record("TYPE_${it} value=test")}
        val report=log.snapshot()
        assertTrue(report.toByteArray().size<=ZpbwLog.MAX_SNAPSHOT_BYTES,report.toByteArray().size.toString())
        assertContains(report,"OTHER=");assertContains(report,"context clipped; logSeq=")
        assertContains(report,"Issue context: MISMATCH");assertContains(report,"Issue context: INPUT_TIMEOUT")
    }
    @Test fun lagCompensationTriggerAndDrainSurviveNoisyLifecycleTraffic() {
        val log=ZpbwLog()
        log.record("EXPERIMENTAL_STALL generation=3 reason=hold_limit holdAgeUs=750001 pending=2 retained=4")
        log.record("EXPERIMENTAL_STALE_SETTLEMENT generation=4 discarded=9")
        log.record("REPAIR_GENUINE_DRAIN_COMPLETE id=13")
        repeat(2000){log.record("PREPARED generation=$it")}
        log.record("FIRST_INSTALL_SHOWN version=test")
        val report=log.snapshot()
        assertContains(report,"EXPERIMENTAL_STALL generation=3 reason=hold_limit holdAgeUs=750001 pending=2 retained=4")
        assertContains(report,"EXPERIMENTAL_STALE_SETTLEMENT generation=4 discarded=9")
        assertContains(report,"REPAIR_GENUINE_DRAIN_COMPLETE id=13")
        assertTrue(report.indexOf("FIRST_INSTALL_SHOWN")<report.indexOf("Failures and cancellations"))
        assertTrue(report.toByteArray().size<=ZpbwLog.MAX_SNAPSHOT_BYTES)
    }
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

    @Test fun mismatchContextAndFollowingPositionsSurviveNoisyLifecycleTraffic() {
        val log = ZpbwLog()
        log.record("PREDICTION_MISMATCH_CONTEXT generation=33 rayOrigin=1,2,3")
        log.record("PREDICTION_MISMATCH_FOLLOWUP generation=33 index=1 matchesGuess=true")
        repeat(2_000) { log.record("PREPARED generation=$it") }
        val snapshot = log.snapshot()
        assertContains(snapshot, "PREDICTION_MISMATCH_CONTEXT generation=33 rayOrigin=1,2,3")
        assertContains(snapshot, "PREDICTION_MISMATCH_FOLLOWUP generation=33 index=1 matchesGuess=true")
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
