package com.hawkslol.zpbw

import com.hawkslol.zpbw.RecoveryResumeGate.Completion.*
import kotlin.test.*

class RecoveryResumeGateTest {
    @Test fun neitherSampleNorTickEndBeforeRecoveryCanAuthorizeLaterRetirement() {
        val gate = RecoveryResumeGate()
        gate.inputSampled()
        assertEquals(WAITING, gate.naturalTickEndSent())
        gate.begin(true)
        assertTrue(gate.pending)
        assertEquals(WAITING, gate.naturalTickEndSent())
    }

    @Test fun provedDrainStillRequiresFreshSampleThenOriginalTickEnd() {
        val gate = RecoveryResumeGate()
        gate.begin(true)
        repeat(3) { assertEquals(WAITING, gate.naturalTickEndSent()); assertTrue(gate.pending) }
        gate.inputSampled()
        assertTrue(gate.pending)
        assertEquals(END_PROVED_DRAIN_GRACE, gate.naturalTickEndSent())
        assertFalse(gate.pending)
        assertEquals(WAITING, gate.naturalTickEndSent())
    }

    @Test fun expiredOrDiscardedRecoveryResumesWithoutRetiringItsGrace() {
        val gate = RecoveryResumeGate()
        gate.begin(false)
        gate.inputSampled()
        assertEquals(KEEP_GRACE, gate.naturalTickEndSent())
        assertFalse(gate.pending)
    }

    @Test fun replacingARecoveryRequiresANewSampleAndDiscardsTheOldProof() {
        val gate = RecoveryResumeGate()
        gate.begin(true)
        gate.inputSampled()
        gate.begin(false)
        assertEquals(WAITING, gate.naturalTickEndSent())
        gate.inputSampled()
        assertEquals(KEEP_GRACE, gate.naturalTickEndSent())
    }

    @Test fun sessionResetCannotCarrySampleOrProofIntoTheNextRecovery() {
        val gate = RecoveryResumeGate()
        gate.begin(true)
        gate.inputSampled()
        gate.reset()
        assertFalse(gate.pending)
        assertEquals(WAITING, gate.naturalTickEndSent())
        gate.begin(false)
        assertEquals(WAITING, gate.naturalTickEndSent())
        gate.inputSampled()
        assertEquals(KEEP_GRACE, gate.naturalTickEndSent())
    }

    @Test fun repeatedSamplesDoNotProduceMultipleResumptions() {
        val gate = RecoveryResumeGate()
        gate.begin(true)
        repeat(10) { gate.inputSampled() }
        assertEquals(END_PROVED_DRAIN_GRACE, gate.naturalTickEndSent())
        repeat(10) { gate.inputSampled(); assertEquals(WAITING, gate.naturalTickEndSent()) }
    }

    @Test fun proofInvalidationKeepsTheRequiredNativeSampleAndTickEndPending() {
        val gate = RecoveryResumeGate()
        gate.begin(true)
        gate.invalidateProof()
        assertTrue(gate.pending)
        assertEquals(WAITING, gate.naturalTickEndSent())
        gate.inputSampled()
        assertEquals(KEEP_GRACE, gate.naturalTickEndSent())
    }

    @Test fun modeChangeAfterSamplingCannotRetireGraceOrForceAnExtraSample() {
        val gate = RecoveryResumeGate()
        gate.begin(true)
        gate.inputSampled()
        gate.invalidateProof()
        assertTrue(gate.pending)
        assertEquals(KEEP_GRACE, gate.naturalTickEndSent())
        assertFalse(gate.pending)
    }

    @Test fun aLaterUnprovedRecoveryCannotInheritTheCompletedDrainProof() {
        val gate = RecoveryResumeGate()
        gate.begin(true)
        gate.inputSampled()
        assertEquals(END_PROVED_DRAIN_GRACE, gate.naturalTickEndSent())
        gate.begin(false)
        assertEquals(WAITING, gate.naturalTickEndSent())
        gate.inputSampled()
        assertEquals(KEEP_GRACE, gate.naturalTickEndSent())
    }

    @Test fun bothNativeContractsMustActuallySettleEveryRetainedGenerationBeforeProvedResume() {
        for (contract in listOf(TestResponseContracts.PAIR, TestResponseContracts.COMBINED)) {
            val chain = ChainLedger<String, String>(responseContract = contract)
            val gate = RecoveryResumeGate()
            for (generation in 1L..5L) { chain.add(generation, "use$generation"); chain.cut(generation) }
            for (generation in 1L..5L) {
                val id = generation.toInt() + 20
                chain.genuine(generation, id)
                assertFailsWith<IllegalStateException> { chain.settle(generation, id) }
                for (step in contract) chain.response(id, step)
                val completed = chain.settle(generation, id)
                if (chain.count == 0) gate.begin(true)
                else {
                    assertFalse(gate.pending)
                    assertEquals(WAITING, gate.naturalTickEndSent())
                    assertTrue(completed.isEmpty())
                }
            }
            assertTrue(gate.pending)
            assertEquals(WAITING, gate.naturalTickEndSent())
            gate.inputSampled()
            assertEquals(END_PROVED_DRAIN_GRACE, gate.naturalTickEndSent())
        }
    }
}
