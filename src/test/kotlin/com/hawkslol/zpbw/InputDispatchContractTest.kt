package com.hawkslol.zpbw

import java.nio.file.Files
import java.nio.file.Path
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

/** Pin real tick hook and ordering/lifecycle wiring; effects are checked on the live server. */
class InputDispatchContractTest {
    private val runtime get() = Files.readString(Path.of("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
    @Test fun dispatchUsesANaturalInputPhaseBeforeNativePlayerMovement() {
        val node=ClassNode()
        javaClass.classLoader.getResourceAsStream("net/minecraft/client/Minecraft.class").use {
            assertNotNull(it); ClassReader(it).accept(node, 0)
        }
        assertTrue(node.methods.any { it.name=="handleKeybinds" && it.desc=="()V" })
        val tick=node.methods.single { it.name=="tick" && it.desc=="()V" }
        assertTrue(tick.instructions.toArray().filterIsInstance<MethodInsnNode>().any { it.name=="handleKeybinds" })
        val dispatcher=runtime.substringAfter("fun dispatchWaitingInput()").substringBefore("fun finishInputDispatchTick")
        assertContains(dispatcher,"chain.count != 0 || handling != null || bypass")
        assertContains(dispatcher,"waitingInputs.removeFirst()")
        assertFalse(dispatcher.contains("bypass = true"))
        assertFalse(dispatcher.contains("setPos("))
        assertContains(dispatcher,"next.action.run()")
        assertContains(dispatcher,"player.yRot = aim.first; player.xRot = aim.second")
    }
    @Test fun waitingActionsCannotOvertakeTheWarpOrSurviveALifecycleFailure() {
        val deferral=runtime.substringAfter("fun deferAction(").substringBefore("fun joined()")
        assertTrue(deferral.indexOf("return queueInput") < deferral.indexOf("val tail = chain.tail"))
        assertContains(runtime,"cancelWaitingInputs(\"unrelated_teleport\")")
        assertContains(runtime,"cancelWaitingInputs(\"session_changed\")")
        assertContains(runtime,"cancelWaitingInputs(\"disabled\")")
        assertContains(runtime,"cancelWaitingInputs(\"disconnect\")")
        val cancelled=runtime.substringAfter("private fun cancelInputDispatch(").substringBefore("private fun expireWaitingInputs(")
        assertContains(cancelled,"inputDispatchedThisTick = false")
        assertContains(cancelled,"cancelWaitingInputs(reason)")
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
        val movement=calls.indexOfFirst { it.name=="sendPosition" }
        assertTrue(physics>=0 && movement>physics)
        val frame=runtime.substringAfter("fun beginQueuedPhysicsTick(").substringBefore("fun beforeGenuine(")
        assertContains(frame,"player.yRot = aim.first; player.xRot = aim.second")
        assertContains(frame,"player.yRot = view.yaw; player.xRot = view.pitch")
        assertContains(frame,"player.yRotO = view.oldYaw; player.xRotO = view.oldPitch")
        val mixin=Files.readString(Path.of("src/main/java/com/hawkslol/zpbw/mixin/ZpbwSourceTickMixin.java"))
        assertContains(mixin,"@Inject(method = \"tick\", at = @At(\"HEAD\"))")
        assertContains(mixin,"@Inject(method = \"tick\", at = @At(\"RETURN\"))")
    }
    @Test fun openingGuiCancelsWaitingWorldClicksWithoutRequiringKeybindHandling() {
        val tick=runtime.substringAfter("fun tick() {").substringBefore("fun takeQueuedUse()")
        assertContains(tick,"mc.gui.screen() != null || mc.gui.overlay() != null")
        assertContains(tick,"cancelWaitingInputs(\"input_context_changed\")")
        assertFalse(tick.contains("dispatchWaitingInput()"))
    }
}
