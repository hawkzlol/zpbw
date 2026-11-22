package com.hawkslol.zpbw

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import kotlin.test.*

/** Compiled boundary checks supplement the behavioral ownership tests and native runs. */
class NativeFallbackDispatchContractTest {
    private val methods = ClassNode().also { node ->
        javaClass.classLoader.getResourceAsStream("com/hawkslol/zpbw/ZpbwRuntime.class").use {
            assertNotNull(it); ClassReader(it).accept(node, 0)
        }
    }.methods

    @Test fun nativeQueueAccountingCannotSendRestoreOrRunBeforeTheOriginalHandler() {
        val calls=methods.single { it.name=="nativePositionHandled" }.instructions.toArray().filterIsInstance<MethodInsnNode>()
        val currentThread=calls.indexOfFirst { it.name=="isSameThread" }
        val processed=calls.indexOfFirst { it.owner.endsWith("/RecoveryPredictionFence") && it.name=="processed" }
        assertTrue(currentThread>=0 && processed>currentThread)
        assertFalse(calls.any { it.name in setOf("send","restore","tick","aiStep","genuine","settle") })
        val mixin=ClassNode().also { node ->
            javaClass.classLoader.getResourceAsStream("com/hawkslol/zpbw/mixin/ZpbwTeleportMixin.class").use {
                assertNotNull(it);ClassReader(it).accept(node,0)
            }
        }
        val scope=mixin.methods.single { it.name=="zpbw\$genuineScope" }.instructions.toArray().filterIsInstance<MethodInsnNode>()
        val original=scope.indexOfFirst { it.owner=="com/llamalad7/mixinextras/injector/wrapoperation/Operation" && it.name=="call" }
        val account=scope.indexOfFirst { it.name=="nativePositionHandled" }
        val complete=scope.indexOfFirst { it.name=="afterGenuine" }
        assertTrue(original>=0 && account>original && complete>account)
    }

    @Test fun releasingTheHoldPreservesLedgerOwnershipAndDoesNotConstructOrSendGameplay() {
        val code = methods.single { it.name == "releaseRecoveryHoldToNative" }.instructions.toArray()
        val calls = code.filterIsInstance<MethodInsnNode>()
        assertEquals(1, calls.count { it.owner.endsWith("/ChainLedger") && it.name == "beginNativeFallback" })
        assertFalse(calls.any { it.owner.endsWith("/ChainLedger") && it.name in setOf("clear", "genuine", "response", "settle") })
        assertFalse(calls.any { it.name in setOf("send", "tick", "aiStep", "sendPosition", "sendChanges", "zpbw\$setPositionReminder") })
        assertFalse(code.filterIsInstance<TypeInsnNode>().any { it.opcode == Opcodes.NEW && it.desc.startsWith("net/minecraft/network/protocol/") })
        val suspend = calls.indexOfFirst { it.name == "suspendRecoveryPrediction" }
        val sampleGate = calls.indexOfFirst { it.owner.endsWith("/RecoveryResumeGate") && it.name == "begin" }
        assertTrue(suspend >= 0 && sampleGate > suspend)
    }

    @Test fun retiringLateObservationsCannotRestoreStalePhysicsOrGrantSuccessfulDrain() {
        val calls = methods.single { it.name == "retireNativeFallback" }.instructions.toArray().filterIsInstance<MethodInsnNode>()
        assertEquals(1, calls.count { it.owner.endsWith("/ChainLedger") && it.name == "clear" })
        assertFalse(calls.any { it.name in setOf("restore", "rebaseSender", "send", "genuine", "response", "settle", "releaseExternalPrediction") })
        assertFalse(calls.any { it.owner.endsWith("/RecoveryPredictionFence") && it.name == "bindCurrent" })
        assertFalse(calls.any { it.owner.endsWith("/RecoveryResumeGate") && it.name == "begin" })
        assertFalse(calls.any { it.owner.contains("ZpbwPlayerAccessor") })
        val sample = methods.single { it.name == "recoveryInputSampled" }.instructions.toArray()
        assertFalse(sample.filterIsInstance<FieldInsnNode>().any { it.name == "recoveringUntil" && it.opcode in setOf(Opcodes.PUTFIELD, Opcodes.PUTSTATIC) })
    }
}
