package com.hawkslol.zpbw

import com.hawkslol.zpbw.nativebridge.NativeIdentity
import com.hawkslol.zpbw.nativebridge.NativeResponseStep
import com.hawkslol.zpbw.nativebridge.NativeTeleportContract
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

/** Actual released bytecode/packet observations; not transformed-runtime/server acceptance. */
class NativeResponseContractTest {
    private fun node(name: String) = ClassNode().also { out ->
        javaClass.classLoader.getResourceAsStream("$name.class").use {
            assertNotNull(it, name); ClassReader(it).accept(out, 0)
        }
    }
    @Test fun actualReleasedPositionHandlerHasExactlyItsNativeResponseAndSuffix() {
        assertEquals(EffectiveSources.nativeTarget, NativeIdentity.TARGET)
        assertEquals(NativeIdentity.TARGET == "26.2", NativeTeleportContract.responseIncludesMovementPosition)
        val method = node("net/minecraft/client/multiplayer/ClientPacketListener").methods.single { it.name == "handleMovePlayer" }
        val calls = method.instructions.toArray().filterIsInstance<MethodInsnNode>()
        val sends = calls.filter { it.owner == "net/minecraft/network/Connection" && it.name == "send" }
        assertEquals(if (NativeIdentity.TARGET == "26.2") 2 else 1, sends.size)
        val accept = calls.single { it.owner.endsWith("ServerboundAcceptTeleportationPacket") && it.name == "<init>" }
        assertEquals(if (NativeIdentity.TARGET == "26.2") "(I)V" else "(IDDDFF)V", accept.desc)
        val suffix = calls.indexOfFirst { it.name == "onTeleport" }
        assertTrue(suffix > calls.indexOfLast { it in sends })
        assertEquals(if (NativeIdentity.TARGET == "26.2") 0 else 1, calls.count { it.name == "stopDestroyBlock" })
        assertEquals(if (NativeIdentity.TARGET == "26.2") TestResponseContracts.PAIR else TestResponseContracts.COMBINED,
            NativeTeleportContract.sequence)
    }
    @Test fun decodedOriginalAcceptKeepsNativePayloadAndDoesNotInventWireFlags() {
        val packet = NativeTestPackets.accept(71)
        val observation = NativeTeleportContract.observe(packet)!!
        assertEquals(71, observation.id)
        if (NativeIdentity.TARGET == "26.2") {
            assertEquals(NativeResponseStep.ACCEPT_ID, observation.step)
            assertNull(observation.position); assertNull(NativeTeleportContract.combinedAcknowledgement(packet))
        } else {
            assertEquals(NativeResponseStep.COMBINED_ACCEPT_POSITION_ROTATION, observation.step)
            assertNotNull(observation.position); assertEquals(0.0, observation.position.x)
            assertEquals(0f, observation.yaw); assertEquals(0f, observation.pitch)
        }
        assertNull(observation.wireCollision)
    }
    @Test fun separateNativeRotationRouteIsNotACompletePositionAcknowledgement() {
        val method = node("net/minecraft/client/multiplayer/ClientPacketListener").methods.single { it.name == "handleRotatePlayer" }
        val calls = method.instructions.toArray().filterIsInstance<MethodInsnNode>()
        assertEquals(1, calls.count { it.owner == "net/minecraft/network/Connection" && it.name == "send" })
        assertTrue(calls.any { it.owner.endsWith("ServerboundMovePlayerPacket\$Rot") && it.name == "<init>" })
        assertFalse(calls.any { it.owner.endsWith("ServerboundAcceptTeleportationPacket") || it.name == "onTeleport" })
    }
    @Test fun nativeDerivedAcknowledgementFlagsDoNotRewriteWireOrSimulationHistory() {
        val history = TransmittedCollisionHistory()
        history.sent(true, true, advancesSimulation = true)
        history.combinedAcknowledgement()
        assertEquals(true to true, history.wire); assertEquals(true to true, history.simulation)
        assertEquals(false to false, history.sourceFlags(true)); assertEquals(true to true, history.sourceFlags(false))
        history.sent(false, true, advancesSimulation = true)
        assertNull(history.nativeAcknowledgement); assertEquals(false to true, history.sourceFlags(true))
        history.reset(); assertNull(history.nativeAcknowledgement)
    }
    @Test fun released263ServerDerivesFalseFlagsWhenConsumingCombinedAcceptance() {
        if (NativeIdentity.TARGET != "26.3") return
        val method = node("net/minecraft/server/network/ServerGamePacketListenerImpl").methods.single { it.name == "handleAcceptTeleportPacket" }
        val code = method.instructions.toArray().filter { it.opcode >= 0 }
        val consumer = code.indexOfFirst { it is MethodInsnNode && it.name == "handlePlayerPositionChange" }
        assertTrue(consumer >= 2)
        assertEquals(Opcodes.ICONST_0, code[consumer - 1].opcode)
        assertEquals(Opcodes.ICONST_0, code[consumer - 2].opcode)
        assertEquals("(DDDFFZZ)V", (code[consumer] as MethodInsnNode).desc)
    }
    @Test fun settingTransactionPersistsBeforeAdmissionChangesAndNeverClearsOwnership() {
        val runtime = java.nio.file.Files.readString(EffectiveSources.path("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
        val setter = runtime.substringAfter("fun configureChainMaximum(").substringBefore("/** Netty receive")
        assertTrue(setter.indexOf("settingsStore.saveStrict") < setter.indexOf("chain.configureMaximum"))
        for (forbidden in listOf("chain.clear", "recover(", ".send(", "warpTimeouts.", "waitingInputs.clear", "dispatchWaitingInput"))
            assertFalse(setter.contains(forbidden), forbidden)
        assertEquals(5, ZpbwRuntime.chainMaximum(), "The pure query does not require Minecraft or load/write profile config")
    }
}
