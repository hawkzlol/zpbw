package com.hawkslol.zpbw

import kotlin.test.*

class SettlementSampleGateTest {
    private val stream = Any()
    private val world = Any()
    private val player = Any()
    private val gate = SettlementSampleGate()
    private fun arm(generation: Long = 7) = gate.arm(stream, world, player, generation)
    private fun pending() = gate.pending(stream, world, player)
    private fun begin() = gate.begin(stream, world, player)
    private fun complete() = gate.completed(stream, world, player)
    private fun end() = gate.end(stream, world, player)

    @Test fun inactiveGuardDoesNotDelayOrdinarySampling() {
        assertFalse(pending()); assertFalse(begin()); assertNull(complete()); assertNull(end())
    }

    @Test fun replayedPacketsAndTickEndsCannotReleaseAdmission() {
        arm()
        gate.sent("held Pos")
        repeat(8) { assertNull(end()) }
        assertTrue(pending())
        assertTrue(begin())
        gate.sent("fresh Pos")
        assertEquals(SettlementSampleGate.Sample(7, 1, "fresh Pos"), complete())
        gate.finish()
        assertTrue(pending()) // The original natural TickEnd must leave too.
        assertEquals(7L, end())
        assertFalse(pending())
        assertNull(end())
    }

    @Test fun nativeSuppressionDoesNotForceAPacketOrTwentyTickWait() {
        arm(); assertTrue(begin())
        assertEquals(SettlementSampleGate.Sample(7, 0, null), complete())
        gate.finish()
        assertEquals(7L, end())
    }

    @Test fun rotationOnlyAndGroundOnlyNativeSamplesKeepTheirActualShape() {
        for (kind in listOf("Rot", "StatusOnly")) {
            arm(); assertTrue(begin()); gate.sent(kind)
            assertEquals(SettlementSampleGate.Sample(7, 1, kind), complete())
            assertEquals(7L, end())
        }
    }

    @Test fun exceptionalSampleFinallyDoesNotQualify() {
        arm(); assertTrue(begin()); gate.sent("Pos")
        gate.finish() // No successful original return.
        assertNull(complete()); assertNull(end()); assertTrue(pending())
        assertTrue(begin()); gate.sent("later native Pos")
        assertEquals("later native Pos", complete()!!.firstPacket)
        gate.finish(); assertEquals(7L, end())
    }

    @Test fun partialSampleCannotBeReleasedByTickEnd() {
        arm(); assertTrue(begin()); assertNull(end())
        assertTrue(pending()); assertFalse(begin())
        complete(); gate.finish(); assertEquals(7L, end())
    }

    @Test fun replacementConnectionWorldOrPlayerInvalidatesTheGuard() {
        for (owner in 0..2) {
            arm(); assertTrue(begin())
            val identities = arrayOf(stream, world, player)
            identities[owner] = Any()
            assertFalse(gate.pending(identities[0], identities[1], identities[2]))
            assertNull(complete()); assertNull(end()); assertFalse(pending())
        }
    }

    @Test fun disablingDisconnectAndHandoffResetCannotReplayAnOldCompletion() {
        repeat(3) {
            arm(); begin(); gate.sent("Pos"); complete(); gate.reset()
            assertNull(end()); assertFalse(pending())
        }
    }

    @Test fun newerSettlementReplacesTheOldSampleWithoutInheritingItsTickEnd() {
        arm(); begin(); gate.sent("old Pos"); complete()
        arm(8)
        assertNull(end()); assertTrue(begin())
        gate.sent("new Pos"); assertEquals(8L, complete()!!.generation)
        gate.finish(); assertEquals(8L, end())
    }

    @Test fun firstSampleIsBoundedAndDoesNotAccumulateLaterTraffic() {
        arm(); begin(); gate.sent("first Pos"); gate.sent("second Pos")
        assertEquals(SettlementSampleGate.Sample(7, 2, "first Pos"), complete())
        gate.finish(); assertFalse(begin()); gate.sent("later unrelated")
        assertNull(complete()); assertEquals(7L, end())
    }

    @Test fun settlementDetailsSurviveHighVolumeWireTraffic() {
        val log = ZpbwLog()
        log.record("SETTLEMENT_SAMPLE_ARMED generation=7 authority=1,2,3")
        repeat(400) { log.record("NETTY_ADMITTED Pos xyz=1,2,3") }
        log.record("SETTLEMENT_SAMPLE_SENT generation=7 packets=1 first=Pos")
        assertContains(log.snapshot(), "SETTLEMENT_SAMPLE_ARMED generation=7")
        assertContains(log.snapshot(), "SETTLEMENT_SAMPLE_SENT generation=7")
    }
}
