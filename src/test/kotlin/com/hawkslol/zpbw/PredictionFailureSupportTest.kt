package com.hawkslol.zpbw

import java.util.Locale
import kotlin.test.*

class PredictionFailureSupportTest {
    private val target = PredictionFailureSupport.Point(10.5, 100.05, -30.5)
    @Test fun coordinatesAndDistancePreserveActualEuclideanError() {
        val guess = PredictionFailureSupport.Point(1.0, 2.0, 3.0)
        val actual = PredictionFailureSupport.Point(4.0, 6.0, 3.0)
        assertEquals("(1.000, 2.000, 3.000)", guess.coordinates())
        assertEquals("(4.000, 6.000, 3.000)", actual.coordinates())
        assertEquals("5.000", PredictionFailureSupport.distance(guess, actual))
        assertEquals(5.0, guess.distanceTo(actual))
    }
    @Test fun unavailableActualIsHonestAndFormattingDoesNotFollowSystemLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("(10.500, 100.050, -30.500)", target.coordinates())
            assertEquals("0.000", PredictionFailureSupport.distance(target, target))
            val unavailable = ZpbwMessages.failure(target, null).string
            assertContains(unavailable, "Received: (unavailable). Difference: unavailable.")
            assertFalse(unavailable.contains("Difference: 0.000"))
        } finally { Locale.setDefault(previous) }
    }
    @Test fun resetExplanationsKeepExactDiagnosticReasonForHover() {
        val packetReason = "unsupported_outgoing_ServerboundPlayerAbilitiesPacket"
        val packet = PredictionFailureSupport.explanation(packetReason)
        assertContains(packet, "Reason: $packetReason\n")
        assertContains(packet, "unsupported outgoing packet")
        assertContains(PredictionFailureSupport.explanation("duplicate_confirmation"), "server repeated a teleport confirmation")
        assertContains(PredictionFailureSupport.explanation("session_transition"), "game session changed")
        assertEquals("Reason: future_reason\nfuture reason", PredictionFailureSupport.explanation("future_reason"))
    }
}
