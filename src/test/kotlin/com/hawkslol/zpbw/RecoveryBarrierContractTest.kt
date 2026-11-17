package com.hawkslol.zpbw

import java.nio.file.Files
import java.nio.file.Path
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

/** Wiring/ordering constraints only. Actual error-recovery physics requires owned-lab tests. */
class RecoveryBarrierContractTest {
    private val runtime get() = Files.readString(Path.of("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))

    @Test fun barrierKeepsOnlyAlreadyDispatchedClosedSourceUsesAndNeverManufacturesResponses() {
        val expiry = runtime.substringAfter("private fun expire(").substringBefore("private fun drainTimeouts")
        assertContains(expiry, "chain.count > 1 || chain.hasConfirmedPrefix")
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
        assertContains(sent, "recoveryResumePending && recoveryResumeSampled")
        val sampled = runtime.substringAfter("fun recoveryInputSampled(").substringBefore("private fun finishRecoveryBarrier")
        assertContains(sampled, "player === sessionPlayer")
        assertContains(sampled, "recoveryResumeSampled = true")
        val end = runtime.substringAfter("private fun finishRecoveryBarrier(").substringBefore("private fun refreshRecoveryPick")
        assertContains(end, "`zpbw\$setLastSentInput`(transmittedControls.input)")
        assertContains(end, "recoveryResumePending = true; recoveryResumeSampled = false")
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
        val mixin = Files.readString(Path.of("src/main/java/com/hawkslol/zpbw/mixin/ZpbwRecoveryMotionMixin.java"))
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
            assertContains(part, "recoveryResumePending = false; recoveryResumeSampled = false")
        }
        val ownership = runtime.substringAfter("fun acquireExternalPrediction(").substringBefore("fun ownsExternalPrediction(")
        assertContains(ownership, "recoveryPhysics != null || recoveryResumePending")
        for (name in listOf("beforeRecoveryPhysics", "afterRecoveryPhysics", "recoveryInputSampled")) {
            val body = runtime.substringAfter("fun $name(").substringBefore("\n    }")
            assertContains(body, "externalPredictionActive()")
        }
    }
}
