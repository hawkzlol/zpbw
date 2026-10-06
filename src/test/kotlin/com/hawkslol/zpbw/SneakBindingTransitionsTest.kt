package com.hawkslol.zpbw
import kotlin.test.*
class SneakBindingTransitionsTest {
    @Test fun unchangedBindingDoesNotFightForcedSimulationSneak() {
        val transitions = SneakBindingTransitions()
        repeat(30) { transitions.observe(false); assertNull(transitions.pending) }
    }
    @Test fun rapidChangesCoalesceToLatestBindingWithoutErasingACommitment() {
        val transitions = SneakBindingTransitions()
        transitions.observe(false); transitions.observe(true); assertEquals(true,transitions.pending)
        transitions.consumed(); transitions.observe(false); assertEquals(false,transitions.pending)
    }
    @Test fun newPlayerStartsWithoutReplayingOldBindingEdges() {
        val transitions = SneakBindingTransitions(); transitions.observe(false); transitions.observe(true)
        transitions.reset(); transitions.observe(false); assertNull(transitions.pending)
    }
}
