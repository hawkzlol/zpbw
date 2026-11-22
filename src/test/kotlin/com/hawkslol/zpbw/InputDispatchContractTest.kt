package com.hawkslol.zpbw

import java.nio.file.Files
import java.nio.file.Path
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

/** Pin real tick hook and ordering/lifecycle wiring; effects are checked on the live server. */
class InputDispatchContractTest {
    private val runtime get() = Files.readString(EffectiveSources.path("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
    @Test fun duePositionBackpressurePrecedesWarpAdmissionAndQueueRemoval() {
        val use = runtime.substringAfter("fun beforeUse(").substringBefore("fun capture(")
        assertTrue(use.indexOf("val positionPending = positionReportPending()") < use.indexOf("chain.coalesce()"))
        assertContains(use, "(positionPending || !chain.canAdd)")
        val dispatch = runtime.substringAfter("fun dispatchWaitingInput()").substringBefore("fun finishInputDispatchTick")
        assertTrue(dispatch.indexOf("next.warp && positionReportPending()") < dispatch.indexOf("chain.canDispatch(next.warp)"))
        assertTrue(dispatch.indexOf("next.warp && positionReportPending()") < dispatch.indexOf("waitingInputs.removeFirst()"))
        val click = runtime.substringAfter("fun deferUseClick()").substringBefore("fun deferredUseInput()")
        assertContains(click, "val positionPending = warpClick && positionReportPending()")
        assertContains(click, "if (warpClick && !positionPending")
        val sent = runtime.substringAfter("fun afterSend(").substringBefore("fun sourceTick(")
        assertContains(sent, "transmittedPosition.movementRequested(packet.hasPosition())")
        assertFalse(sent.contains("dispatchWaitingInput()"), "Position delegate return cannot reenter a queued native click")
        val gate = runtime.substringAfter("private fun positionReportPending()").substringBefore("private fun forwardEnvelope(")
        for (forbidden in listOf(".send(", "chain.clear", "warpTimeouts", "setPositionReminder", "dispatchWaitingInput"))
            assertFalse(gate.contains(forbidden), forbidden)
    }
    @Test fun dispatchUsesANaturalInputPhaseBeforeNativePlayerMovement() {
        val node=ClassNode()
        javaClass.classLoader.getResourceAsStream("net/minecraft/client/Minecraft.class").use {
            assertNotNull(it); ClassReader(it).accept(node, 0)
        }
        assertTrue(node.methods.any { it.name=="handleKeybinds" && it.desc=="()V" })
        val tick=node.methods.single { it.name=="tick" && it.desc=="()V" }
        assertTrue(tick.instructions.toArray().filterIsInstance<MethodInsnNode>().any { it.name=="handleKeybinds" })
        val dispatcher=runtime.substringAfter("fun dispatchWaitingInput()").substringBefore("fun finishInputDispatchTick")
        assertContains(dispatcher,"handling != null || bypass")
        assertContains(dispatcher,"if (!chain.canDispatch(next.warp)) return")
        assertContains(dispatcher,"waitingInputs.removeFirst()")
        assertFalse(dispatcher.contains("bypass = true"))
        assertFalse(dispatcher.contains("setPos("))
        assertContains(dispatcher,"next.action.run()")
        assertContains(dispatcher,"player.yRot = aim.first; player.xRot = aim.second")
    }
    @Test fun waitingActionsCannotOvertakeTheWarpOrSurviveALifecycleFailure() {
        val deferral=runtime.substringAfter("fun deferAction(").substringBefore("fun joined()")
        assertTrue(deferral.indexOf("return queueInput") < deferral.indexOf("val tail = chain.tail"))
        assertTrue(deferral.indexOf("chain.tail?.cut == false") in 0 until deferral.indexOf("val tail = chain.tail"),
            "World actions must queue even before a newly dispatched warp closes its source tick")
        assertContains(runtime, "if (dispatchingInput != null || chain.hasConfirmedPrefix) queuedTickAim = packet.yRot to packet.xRot")
        assertContains(runtime,"cancelWaitingInputs(\"unrelated_teleport\")")
        assertContains(runtime,"cancelWaitingInputs(\"session_changed\")")
        assertContains(runtime,"cancelWaitingInputs(\"disabled\")")
        assertContains(runtime,"cancelWaitingInputs(\"disconnect\")")
        val cancelled=runtime.substringAfter("private fun cancelInputDispatch(").substringBefore("private fun expireWaitingInputs(")
        assertContains(cancelled,"inputDispatchedThisTick = false")
        assertContains(cancelled,"cancelWaitingInputs(reason)")
        assertContains(cancelled,"INPUT_DISPATCH_CANCELLED")
        assertContains(cancelled,"ZpbwMessages.queuedCancellation(")
        assertFalse(cancelled.contains("ZpbwMessages.queueFailure("))
        assertFalse(cancelled.contains("failed++"))
        assertFalse(cancelled.contains("recover("))
        val source=runtime.substringAfter("fun sourceTick(").substringBefore("fun beginQueuedPhysicsTick(")
        assertFalse(source.contains("player.yRot ="), "Wire movement must use the same live look as native physics")
        assertFalse(source.contains("player.xRot ="))
        assertContains(runtime,"aimAtClickedPoint(player, hit.location.lerp(entity.boundingBox.center, 1e-6))")
    }
    @Test fun queuedInputAimCoversNativePhysicsAndMovementAndRestoresInterpolatedView() {
        val node=ClassNode()
        javaClass.classLoader.getResourceAsStream("net/minecraft/client/player/LocalPlayer.class").use {
            assertNotNull(it); ClassReader(it).accept(node,0)
        }
        val calls=node.methods.single { it.name=="tick" && it.desc=="()V" }
            .instructions.toArray().filterIsInstance<MethodInsnNode>()
        val physics=calls.indexOfFirst { it.owner==node.superName && it.name=="tick" }
        assertTrue(physics>=0)
        if (EffectiveSources.nativeTarget == "26.2") {
            val movement=calls.indexOfFirst { it.name=="sendPosition" }
            assertTrue(movement>physics)
        } else {
            assertFalse(calls.any { it.name=="sendPosition" })
            val phase=node.methods.single { it.name=="sendChanges" && it.desc=="()V" }
                .instructions.toArray().filterIsInstance<MethodInsnNode>()
            assertTrue(phase.any { it.name=="sendPosition" })
        }
        val frame=runtime.substringAfter("fun beginQueuedPhysicsTick(").substringBefore("fun beforeGenuine(")
        assertContains(frame,"player.yRot = aim.first; player.xRot = aim.second")
        assertContains(frame,"player.yRot = view.yaw; player.xRot = view.pitch")
        assertContains(frame,"player.yRotO = view.oldYaw; player.xRotO = view.oldPitch")
        val mixin=Files.readString(EffectiveSources.path("src/main/java/com/hawkslol/zpbw/mixin/ZpbwSourceTickMixin.java"))
        assertContains(mixin,"@WrapMethod(method = \"tick\")")
        assertContains(mixin,"finally { ZpbwRuntime.finishQueuedPhysicsTick")
        if (EffectiveSources.nativeTarget == "26.3") assertContains(mixin,"@WrapMethod(method = \"sendChanges\")")
    }
    @Test fun openingGuiCancelsWaitingWorldClicksWithoutRequiringKeybindHandling() {
        val tick=runtime.substringAfter("fun tick() {").substringBefore("fun takeQueuedUse()")
        assertContains(tick,"mc.gui.screen() != null || mc.gui.overlay() != null")
        assertContains(tick,"cancelWaitingInputs(\"input_context_changed\")")
        assertContains(tick,"ZpbwMessages.queuedCancellation(\"A screen was opened.\"")
        assertFalse(tick.contains("dispatchWaitingInput()"))
    }
}
