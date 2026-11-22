package com.hawkslol.zpbw

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.*
import kotlin.test.*

/** Compiled integration boundaries; actual queued guard behavior is exercised separately. */
class DisconnectResetDispatchContractTest {
    private val methods = ClassNode().also { node ->
        javaClass.classLoader.getResourceAsStream("com/hawkslol/zpbw/ZpbwRuntime.class").use {
            assertNotNull(it); ClassReader(it).accept(node, 0)
        }
    }.methods

    @Test fun nativeThreadCheckAndEnqueuePrecedeEveryLedgerReadOrCleanup() {
        val code = methods.single { it.name == "reset" }.instructions.toArray()
        val calls = code.filterIsInstance<MethodInsnNode>()
        val thread = calls.indexOfFirst { it.name == "isSameThread" }
        val enqueue = calls.indexOfFirst { it.name == "execute" }
        val ledger = calls.indexOfFirst { it.owner.endsWith("/ChainLedger") && it.name == "pendingSnapshot" }
        assertTrue(thread >= 0 && enqueue > thread && ledger > enqueue)
        val executeIndex = code.indexOfFirst { it is MethodInsnNode && it.name == "execute" }
        val firstLedgerIndex = code.indexOfFirst { it is MethodInsnNode && it.owner.endsWith("/ChainLedger") }
        assertTrue(code.slice(executeIndex + 1 until firstLedgerIndex).any { it.opcode == org.objectweb.asm.Opcodes.RETURN })
        assertEquals(1, calls.count { it.name == "execute" })
    }

    @Test fun queuedResetChecksBothCapturedConnectionIdentityAndEpoch() {
        val lambda = methods.single { method -> method.name.contains("reset\$lambda") &&
            method.instructions.toArray().filterIsInstance<MethodInsnNode>().any { it.name == "reset" } }
        val code = lambda.instructions.toArray()
        val fields = code.filterIsInstance<FieldInsnNode>().map { it.name }
        assertTrue("connected" in fields && "epoch" in fields)
        val dispatch = code.indexOfFirst { it is MethodInsnNode && it.name == "reset" }
        assertTrue(code.take(dispatch).filterIsInstance<JumpInsnNode>().size >= 2)
        assertFalse(code.filterIsInstance<MethodInsnNode>().any { it.name in setOf("send", "tick", "aiStep", "sendPosition", "sendChanges") })
    }
}
