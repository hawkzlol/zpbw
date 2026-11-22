package com.hawkslol.zpbw

import java.nio.file.Files
import java.nio.file.Path
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import kotlin.test.*

/** Binary hook/order checks supplement state regressions; no transformed client is launched. */
class NsdNativeContractTest {
    private fun node(name: String) = ClassNode().also { node ->
        javaClass.classLoader.getResourceAsStream("$name.class").use {
            assertNotNull(it, name); ClassReader(it).accept(node, 0)
        }
    }
    private val nsd get() = Files.readString(EffectiveSources.path("src/main/kotlin/com/hawkslol/zpbw/NoSneakDelay.kt"))

    @Test fun nativeOrderIsClickThenSamplePhysicsThenNativeInputMovementAndTickEnd() {
        val minecraft = node("net/minecraft/client/Minecraft")
        val tick = minecraft.methods.single { it.name == "tick" && it.desc == "()V" }.instructions.toArray()
        val keys = tick.indexOfFirst { it is MethodInsnNode && it.name == "handleKeybinds" }
        val entities = tick.indexOfFirst { it is MethodInsnNode && it.name == "tickEntities" }
        val tickEnd = tick.indexOfFirst { it is FieldInsnNode && it.owner.endsWith("ServerboundClientTickEndPacket") }
        assertTrue(keys >= 0 && entities > keys && tickEnd > entities)
        val player = node("net/minecraft/client/player/LocalPlayer")
        val localTick = player.methods.single { it.name == "tick" && it.desc == "()V" }.instructions.toArray()
        val physics = localTick.indexOfFirst { it is MethodInsnNode && it.name == "tick" && it.owner == player.superName }
        assertTrue(physics >= 0)
        val sending = if (EffectiveSources.nativeTarget == "26.2") localTick
            else player.methods.single { it.name == "sendChanges" && it.desc == "()V" }.instructions.toArray()
        if (EffectiveSources.nativeTarget == "26.3") {
            val sendChanges = tick.indexOfFirst { it is MethodInsnNode && it.name == "sendChanges" }
            assertTrue(sendChanges > entities && sendChanges < tickEnd)
            assertFalse(localTick.any { it is MethodInsnNode && it.name == "sendPosition" })
        }
        val input = sending.indexOfFirst { it is TypeInsnNode && it.desc.endsWith("ServerboundPlayerInputPacket") }
        val nativeSend = sending.indexOfFirst { it is MethodInsnNode && it.name == "send" }
        val cache = sending.indexOfFirst { it is FieldInsnNode && it.opcode == Opcodes.PUTFIELD && it.name == "lastSentInput" }
        val position = sending.indexOfFirst { it is MethodInsnNode && it.name == "sendPosition" }
        assertTrue(input >= 0 && nativeSend > input && cache > nativeSend && position > cache)
        if (EffectiveSources.nativeTarget == "26.2") assertTrue(input > physics)
        val sample = player.methods.single { it.name == "aiStep" }.instructions.toArray()
        val oldShift = sample.indexOfFirst { it is MethodInsnNode && it.name == "isShiftKeyDown" }
        val currentKeys = sample.indexOfFirst { it is MethodInsnNode && it.owner.endsWith("/ClientInput") && it.name == "tick" }
        assertTrue(oldShift >= 0 && currentKeys > oldShift, "Click scope must not overwrite old physics input")
    }

    @Test fun onlyFreshEarlyInputIsConstructedAndNormalNativeSamplingIsUnmoved() {
        val code = node("com/hawkslol/zpbw/NoSneakDelay")
        val constructors = code.methods.flatMap { it.instructions.toArray().toList() }.filterIsInstance<MethodInsnNode>()
            .filter { it.name == "<init>" && it.owner.endsWith("ServerboundPlayerInputPacket") }
        assertEquals(1, constructors.size, "No separately constructed deferred/repair input packet")
        for (method in code.methods) for (instruction in method.instructions.toArray()) {
            if (instruction is MethodInsnNode) {
                assertFalse(instruction.owner.endsWith("ServerboundMovePlayerPacket"))
                assertFalse(instruction.owner.endsWith("ServerboundClientTickEndPacket"))
                assertFalse(instruction.name == "tick" && instruction.owner.startsWith("net/minecraft/client/"))
            }
        }
        assertFalse(nsd.contains("commitDeferred"))
        assertFalse(nsd.contains("latch.deferred"))
        val hooks = Files.readString(EffectiveSources.path("src/main/java/com/hawkslol/zpbw/mixin/ZpbwSneakMixin.java"))
        assertContains(hooks, "@Inject(method=\"runTick\", at=@At(\"HEAD\"))")
        assertContains(hooks, "@Inject(method=\"tick\",at=@At(\"TAIL\"))")
        val sample = Files.readString(EffectiveSources.path("src/main/java/com/hawkslol/zpbw/mixin/ZpbwLatchedInputMixin.java"))
        assertContains(sample, "@Inject(method=\"tick\",at=@At(\"TAIL\"),order=2000)")
    }

    @Test fun focusGuiSessionAndOwnershipProtectionsRemainAtTheExistingBoundaries() {
        assertContains(nsd, "if (!enabled || !mc.isSameThread) return")
        assertContains(nsd, "if (ZpbwRuntime.externalPredictionActive()) { binding.reset(); return }")
        assertContains(nsd, "p !== player || mc.level !== level || connection !== stream")
        assertContains(nsd, "connection?.isConnected == true && it.connection.hasClientLoaded()")
        assertContains(nsd, "if (shift && (mc.gui.screen() != null || !mc.isWindowActive)) return")
        assertContains(nsd, "!ZpbwRuntime.canSendSneakEarly() || p.input !is KeyboardInput || !p.isAlive")
        assertContains(nsd, "p.isPassenger || p.isFallFlying || p.abilities.flying || p.isInWater || p.isInLava")
        assertContains(nsd, "packet is ServerboundPlayerInputPacket && latch.value == packet.input")
        assertContains(nsd, "ZpbwRuntime.deferredUseInput() ?: latch.value")
        assertFalse(nsd.contains("glfw"))
        assertFalse(nsd.contains("keyPresses = early"))
        val runtime = Files.readString(EffectiveSources.path("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
        assertContains(runtime, "inputCommitted = NoSneakDelay.hasCommitment()")
        assertContains(runtime.substringAfter("fun reset()"), "NoSneakDelay.reset()")
        val recovery = runtime.substringAfter("private fun recover(").substringBefore("private fun refreshRecoveryPick(")
        assertContains(recovery, "`zpbw\$setLastSentInput`(transmittedControls.input)")
        assertFalse(recovery.contains("NoSneakDelay.reset()"))
    }

    @Test fun allFiveNativeClickWrappersKeepFinallyCleanupIncludingExceptionalReturn() {
        for ((name, methods) in listOf(
            "ZpbwLatchedClickMixin" to listOf("zpbw\$attackInput", "zpbw\$useInput"),
            "ZpbwLatchedGameModeMixin" to listOf("zpbw\$interact", "zpbw\$use", "zpbw\$block"))) {
            val mixin = node("com/hawkslol/zpbw/mixin/$name")
            for (name in methods) {
                val method = mixin.methods.single { it.name == name }
                assertTrue(method.tryCatchBlocks.any { it.type == null }, "$name must have a finally handler")
                val calls = method.instructions.toArray().filterIsInstance<MethodInsnNode>()
                assertEquals(1, calls.count { it.name == "beginClick" }, name)
                assertEquals(2, calls.count { it.name == "endClick" }, "$name: cleanup on normal and exceptional paths")
            }
        }
    }
}
