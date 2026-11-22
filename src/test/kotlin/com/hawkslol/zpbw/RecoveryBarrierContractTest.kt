package com.hawkslol.zpbw

import java.nio.file.Files
import java.nio.file.Path
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

/** Wiring/ordering constraints only. Actual error-recovery physics requires owned-lab tests. */
class RecoveryBarrierContractTest {
    private val runtime get() = Files.readString(EffectiveSources.path("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))

    @Test fun barrierKeepsOnlyAlreadyDispatchedClosedSourceUsesAndNeverManufacturesResponses() {
        val expiry = runtime.substringAfter("private fun expire(").substringBefore("private fun drainTimeouts")
        assertFalse(expiry.contains("chain.count > 1"))
        assertContains(expiry, "entries.all { it.cut && it.value.use != null }")
        assertContains(expiry, "restartAfterExpiry(connection, expiry, entries.map { it.generation }, 20)")
        val handler = runtime.substringAfter("if (h.transportRecovery) {").substringBefore("if (chain.count == 0) epoch++")
        assertContains(handler, "barrier.authority = Pose.of(p.player)")
        assertContains(handler, "finishRecoveryBarrier(\"genuine_drain_complete\"")
        assertFalse(handler.contains("h.advanced!!.restore"))
        assertFalse(runtime.contains("ServerboundAcceptTeleportationPacket("))
        assertFalse(runtime.contains("ServerboundClientTickEndPacket("))
    }

    @Test fun queuedActionsWaitForAFreshNativeSampleAndAnActuallySentTickEnd() {
        val dispatch = runtime.substringAfter("fun dispatchWaitingInput()").substringBefore("fun finishInputDispatchTick")
        assertContains(dispatch, "if (recoveryBarrier != null || recoveryResumePending) return")
        val sent = runtime.substringAfter("fun afterSend(").substringBefore("fun sourceTick(")
        assertContains(sent, "packet is ServerboundClientTickEndPacket")
        assertContains(sent, "recoveryResume.naturalTickEndSent()")
        val sampled = runtime.substringAfter("fun recoveryInputSampled(").substringBefore("private fun finishRecoveryBarrier")
        assertContains(sampled, "player === sessionPlayer")
        assertContains(sampled, "recoveryResume.inputSampled()")
        val end = runtime.substringAfter("private fun finishRecoveryBarrier(").substringBefore("private fun refreshRecoveryPick")
        assertContains(end, "`zpbw\$setLastSentInput`(transmittedControls.input)")
        assertContains(end, "recoveryResume.begin(reason == \"genuine_drain_complete\" && completed != null &&")
        assertContains(end, "chain.count == 0 && barrier.receivedTeleport && barrier.admissionProof && !restore)")
        assertFalse(end.contains("NoSneakDelay.reset()"))
        assertFalse(end.contains("ServerboundPlayerInputPacket("))
    }

    @Test fun motionPausePreservesNativeSamplingAndHasNoPacketOrExtraTickPath() {
        val player = ClassNode()
        javaClass.classLoader.getResourceAsStream("net/minecraft/client/player/LocalPlayer.class").use {
            assertNotNull(it); ClassReader(it).accept(player, 0)
        }
        val ai = player.methods.single { it.name == "aiStep" && it.desc == "()V" }
        assertTrue(ai.instructions.toArray().filterIsInstance<MethodInsnNode>().any {
            it.owner == "net/minecraft/client/player/ClientInput" && it.name == "tick" && it.desc == "()V"
        })
        val mixin = Files.readString(EffectiveSources.path("src/main/java/com/hawkslol/zpbw/mixin/ZpbwRecoveryMotionMixin.java"))
        assertContains(mixin, "shift = At.Shift.AFTER")
        assertFalse(mixin.contains("ci.cancel")); assertFalse(mixin.contains(".tick("))
        val freeze = runtime.substringAfter("fun beforeRecoveryPhysics(").substringBefore("fun recoveryInputSampled(")
        assertContains(freeze, "recoveryBarrier !== frame.first")
        assertContains(freeze, "frame.second.restore(player)")
        assertFalse(freeze.contains(".send(")); assertFalse(freeze.contains(".aiStep("))
    }

    @Test fun sessionAndExternalOwnershipCannotInheritRecoveryState() {
        for (part in listOf(
            runtime.substringAfter("fun reset()").substringBefore("fun status()"),
            runtime.substringAfter("private fun attach(").substringBefore("private fun identity(")
        )) {
            assertContains(part, "recoveryBarrier = null; recoveryPhysics = null")
            assertContains(part, "recoveryResume.reset()")
        }
        val ownership = runtime.substringAfter("fun acquireExternalPrediction(").substringBefore("fun ownsExternalPrediction(")
        assertContains(ownership, "recoveryPhysics != null || recoveryResumePending")
        for (name in listOf("beforeRecoveryPhysics", "afterRecoveryPhysics", "recoveryInputSampled")) {
            val body = runtime.substringAfter("fun $name(").substringBefore("\n    }")
            assertContains(body, "externalPredictionActive()")
        }
    }

    @Test fun provedDrainRetiresGraceOnlyAtTheExistingNaturalResumeBoundary() {
        val sent = runtime.substringAfter("fun afterSend(").substringBefore("fun sourceTick(")
        val boundary = sent.substringAfter("if (packet is ServerboundClientTickEndPacket) {")
            .substringBefore("if (externalPredictionActive()) return")
        assertContains(boundary, "recoveryResume.naturalTickEndSent()")
        assertContains(boundary, "RecoveryResumeGate.Completion.END_PROVED_DRAIN_GRACE")
        assertContains(boundary, "recoveryAdmissionReady = true")
        val automatic = boundary.substringAfter("if (recoveryFence.naturalTickEnd(connection, clean)) {")
        assertContains(automatic, "recoveringUntil = 0L")
        assertContains(boundary, "handling == null && recoveryBarrier == null && chain.count == 0")
        assertContains(boundary, "!recoveryResumePending && waitingInputs.isEmpty()")
        assertFalse(boundary.contains(".send("))
        val sample = runtime.substringAfter("fun recoveryInputSampled(").substringBefore("\n    }")
        assertFalse(sample.contains("recoveringUntil ="))
        val early = runtime.substringAfter("fun canSendSneakEarly()").substringBefore("/**")
        assertContains(early, "!recoveryResumePending")
        val use = runtime.substringAfter("fun beforeUse(").substringBefore("fun capture(")
        assertFalse(use.contains("USE_VANILLA_RECOVERY_GRACE"))
        assertFalse(use.contains("recoveringUntil"))
        val recover = runtime.substringAfter("private fun recover(").substringBefore("fun beforeAuthoritativeMotion(")
        assertTrue(recover.indexOf("invalidateRecoveryAdmission()") < recover.indexOf("val p = chain.head?.value"))
        val select = runtime.substringAfter("fun select(").substringBefore("fun configureTimeout(")
        assertContains(select, "if (requested != mode) invalidateRecoveryAdmission()")
        assertTrue(select.indexOf("requested == Mode.OBSERVE && chain.count > 0") < select.indexOf("invalidateRecoveryAdmission()"))
        val invalidate = runtime.substringAfter("private fun invalidateRecoveryAdmission()").substringBefore("private fun finishRecoveryBarrier")
        assertContains(invalidate, "recoveryResume.invalidateProof()")
        assertContains(invalidate, "recoveryBarrier?.admissionProof = false")
        val passthrough = sent.substringAfter("// A real native use outside").substringBefore("event(\"USE_REQUESTED")
        assertContains(passthrough, "packet is ServerboundUseItemOnPacket")
        assertTrue(passthrough.indexOf("invalidateRecoveryAdmission()") < passthrough.indexOf("if (bypass || handling != null) return"))
        assertFalse(passthrough.contains("suspendRecoveryPrediction("))
        val genuine = runtime.substringAfter("fun beforeGenuine(").substringBefore("fun nativeResponse(")
        assertContains(genuine, "System.nanoTime() < recoveringUntil")
    }

    @Test fun unresolvedRecoveryBlocksNewPredictionButNotNativeUseAndSurvivesScopeCleanup() {
        val use = runtime.substringAfter("fun beforeUse(").substringBefore("fun capture(")
        assertTrue(use.indexOf("recoveryBarrier != null || recoveryResumePending") < use.indexOf("recoveryFence.isSuspended(stream)"))
        assertTrue(use.indexOf("recoveryFence.isSuspended(stream)") < use.indexOf("predictCurrentEtherwarpForRotation"))
        assertContains(use, "if (recoveryFence.isSuspended(stream)) return false")
        val attach = runtime.substringAfter("private fun attach(").substringBefore("private fun identity(")
        assertContains(attach, "stream === mc.connection?.connection && recoveryFence.bindCurrent(stream)")
        assertContains(attach, "session_changed_with_issued_use")
        assertContains(attach, "suspendRecoveryPrediction(connected, \"session_changed_with_issued_use\")")
        assertFalse(attach.contains("stream === connected && chain.pendingSnapshot()"))
        val end = runtime.substringAfter("private fun finishRecoveryBarrier(").substringBefore("private fun refreshRecoveryPick")
        assertTrue(end.indexOf("suspendRecoveryPrediction(") < end.indexOf("chain.clear()"))
        val recover = runtime.substringAfter("private fun recover(").substringBefore("fun beforeAuthoritativeMotion(")
        assertTrue(recover.indexOf("suspendRecoveryPrediction(") < recover.indexOf("else chain.clear()"))
        for (part in listOf(runtime.substringAfter("fun reset()").substringBefore("fun status()"),
            runtime.substringAfter("fun select(").substringBefore("fun configureTimeout("),
            runtime.substringAfter("fun acquireExternalPrediction(").substringBefore("fun ownsExternalPrediction("))) {
            assertFalse(part.contains("recoveryFence.bindCurrent("))
            assertFalse(part.contains("recoveryFence ="))
        }
    }
}
