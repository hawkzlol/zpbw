package com.hawkslol.zpbw

import com.hawkslol.zpbw.nativebridge.NativeResponseStep
import kotlin.test.*

class AutomaticRecoveryTest {
    private val contracts = listOf(TestResponseContracts.PAIR, TestResponseContracts.COMBINED)
    private fun authority(fence: RecoveryPredictionFence<Any>, stream: Any, steps: List<NativeResponseStep>, token: Any = Any()) {
        fence.beginAuthority(stream, token)
        steps.forEach { fence.nativeResponse(stream, token, it) }
        assertTrue(fence.handlerCompleted(stream, token))
    }
    @Test fun actualUsesInvalidateAuthorityButCompletedRepliesNeedNoQuietTimer() {
        for(contract in contracts) {
            val stream=Any();val f=RecoveryPredictionFence<Any>(contract);f.bindCurrent(stream)
            repeat(3) {
                f.suspend(stream,"position_mismatch")
                authority(f,stream,contract);assertTrue(f.awaitingFreshBoundary(stream))
                f.restart(stream) // An actually sent use invalidates that older authority.
                assertFalse(f.awaitingFreshBoundary(stream));f.inputSampled(stream)
                assertFalse(f.naturalTickEnd(stream,true))
                authority(f,stream,contract)
                assertFalse(f.naturalTickEnd(stream,true))
                f.inputSampled(stream);assertTrue(f.naturalTickEnd(stream,true))
            }
        }
    }

    @Test fun compiledNativeUseBoundaryRestartsOnlyTheRecoveryFenceNotWarpTimeoutsOrSimulation() {
        val node=org.objectweb.asm.tree.ClassNode()
        javaClass.classLoader.getResourceAsStream("com/hawkslol/zpbw/ZpbwRuntime.class").use {
            assertNotNull(it);org.objectweb.asm.ClassReader(it).accept(node,0)
        }
        val calls=node.methods.single { it.name=="afterSend" }.instructions.toArray().filterIsInstance<org.objectweb.asm.tree.MethodInsnNode>()
        assertEquals(1,calls.count { it.owner.endsWith("/RecoveryPredictionFence") && it.name=="restart" })
        assertFalse(calls.any { it.name in setOf("send","aiStep","sendChanges","sendPosition") })
        assertFalse(calls.any { it.owner.endsWith("/ServerTickTimeouts") && it.name in setOf("issued","cancel","bind") })
    }
    @Test fun oneGenuineReplyAndItsNextFreshBoundaryResumeBothNatives() {
        for(contract in contracts) {
            val stream=Any();val f=RecoveryPredictionFence<Any>(contract);f.bindCurrent(stream)
            f.suspend(stream,"position_mismatch");f.inputSampled(stream)
            authority(f,stream,contract)
            assertEquals("fresh_input",f.blocker(stream,true))
            assertFalse(f.naturalTickEnd(stream,true))
            f.inputSampled(stream);assertTrue(f.naturalTickEnd(stream,true))
            assertFalse(f.isSuspended(stream));assertNull(f.reason(stream))
        }
    }
    @Test fun continuingWarpIntentsWaitOneBoundaryWithoutCreatingMoreNativeDebt() {
        for(contract in contracts) {
            val stream=Any();val f=RecoveryPredictionFence<Any>(contract);f.bindCurrent(stream)
            f.suspend(stream,"position_mismatch");authority(f,stream,contract)
            var queued=0;var sends=0
            repeat(5) { if(f.awaitingFreshBoundary(stream))queued++ else sends++ }
            assertEquals(5,queued);assertEquals(0,sends)
            assertFalse(f.naturalTickEnd(stream,true));f.inputSampled(stream)
            assertTrue(f.naturalTickEnd(stream,true)) // Unissued intents are not server work.
            assertFalse(f.awaitingFreshBoundary(stream))
            queued--;sends++;assertEquals(1,sends);assertEquals(4,queued)
        }
    }
    @Test fun timeInputOrAnEmptyLedgerCannotReplaceARealNativeResponse() {
        val stream=Any(); val f=RecoveryPredictionFence<Any>();f.bindCurrent(stream);f.suspend(stream,"timeout")
        repeat(200) { f.nativeTick(stream);f.inputSampled(stream);assertFalse(f.naturalTickEnd(stream,true)) }
        assertTrue(f.isSuspended(stream))
    }
    @Test fun authorityNeedsTheCompleteMatchingHandlerContractNotJustItsIdOrFirstResponse() {
        val stream=Any();val f=RecoveryPredictionFence<Any>(TestResponseContracts.PAIR);val token=Any()
        f.bindCurrent(stream);f.suspend(stream,"failed");f.beginAuthority(stream,token)
        f.nativeResponse(stream,token,NativeResponseStep.ACCEPT_ID)
        assertFalse(f.handlerCompleted(stream,token))
        repeat(20) { f.nativeTick(stream) };f.inputSampled(stream);assertFalse(f.naturalTickEnd(stream,true))
        authority(f,stream,TestResponseContracts.PAIR);f.inputSampled(stream);assertTrue(f.naturalTickEnd(stream,true))
    }
    @Test fun duplicateWrongOrUnknownResponsesCannotProveAuthority() {
        for (steps in listOf(listOf(NativeResponseStep.POSITION_ROTATION),listOf(NativeResponseStep.ACCEPT_ID,NativeResponseStep.ACCEPT_ID),listOf(null))) {
            val stream=Any();val f=RecoveryPredictionFence<Any>(TestResponseContracts.PAIR);val token=Any()
            f.bindCurrent(stream);f.suspend(stream,"failed");f.beginAuthority(stream,token)
            steps.forEach { f.nativeResponse(stream,token,it) }
            assertFalse(f.handlerCompleted(stream,token));repeat(20) { f.nativeTick(stream) }
            f.inputSampled(stream);assertFalse(f.naturalTickEnd(stream,true))
        }
    }
    @Test fun aLateHandlerCannotStealTheNewHandlersResponseOwnership() {
        val stream=Any();val f=RecoveryPredictionFence<Any>(TestResponseContracts.COMBINED);val old=Any();val fresh=Any()
        f.bindCurrent(stream);f.suspend(stream,"late");f.beginAuthority(stream,old);f.beginAuthority(stream,fresh)
        TestResponseContracts.COMBINED.forEach { f.nativeResponse(stream,old,it) }
        assertFalse(f.handlerCompleted(stream,old))
        TestResponseContracts.COMBINED.forEach { f.nativeResponse(stream,fresh,it) };assertTrue(f.handlerCompleted(stream,fresh))
        repeat(20) { f.nativeTick(stream) };f.inputSampled(stream);assertTrue(f.naturalTickEnd(stream,true))
    }
    @Test fun samplingBeforeAuthorityDoesNotAuthorizeAFollowingTickEnd() {
        val stream=Any();val f=RecoveryPredictionFence<Any>();f.bindCurrent(stream);f.suspend(stream,"failed")
        repeat(20) { f.nativeTick(stream) };f.inputSampled(stream)
        authority(f,stream,com.hawkslol.zpbw.nativebridge.NativeTeleportContract.sequence)
        assertFalse(f.naturalTickEnd(stream,true));f.inputSampled(stream);assertTrue(f.naturalTickEnd(stream,true))
    }
    @Test fun heldWorkGuiOrExternalOwnerKeepsAdmissionClosedWithoutConsumingTheProof() {
        val stream=Any();val f=RecoveryPredictionFence<Any>();f.bindCurrent(stream);f.suspend(stream,"failed")
        authority(f,stream,com.hawkslol.zpbw.nativebridge.NativeTeleportContract.sequence)
        repeat(20) { f.nativeTick(stream) };f.inputSampled(stream)
        repeat(10) { assertFalse(f.naturalTickEnd(stream,false)) }
        assertTrue(f.naturalTickEnd(stream,true))
    }
    @Test fun anUntrackedNativeUseOrAbortedHandlerRequiresNewAuthorityAndNewInput() {
        val stream=Any();val f=RecoveryPredictionFence<Any>();f.bindCurrent(stream);f.suspend(stream,"failed")
        val contract=com.hawkslol.zpbw.nativebridge.NativeTeleportContract.sequence
        authority(f,stream,contract);repeat(20) { f.nativeTick(stream) };f.inputSampled(stream)
        f.invalidateAuthority(stream);assertFalse(f.naturalTickEnd(stream,true))
        authority(f,stream,contract);assertFalse(f.naturalTickEnd(stream,true))
        f.inputSampled(stream);assertTrue(f.naturalTickEnd(stream,true))
    }
    @Test fun retiringUncertainOwnershipCannotReuseAuthorityFromBeforeRetirement() {
        val stream=Any();val f=RecoveryPredictionFence<Any>();f.bindCurrent(stream);f.suspend(stream,"timeout")
        val contract=com.hawkslol.zpbw.nativebridge.NativeTeleportContract.sequence
        authority(f,stream,contract);repeat(20) { f.nativeTick(stream) };f.inputSampled(stream)
        f.restart(stream);assertFalse(f.naturalTickEnd(stream,true))
        authority(f,stream,contract);f.inputSampled(stream)
        assertTrue(f.naturalTickEnd(stream,true))
    }
    @Test fun staleConnectionsCannotAdvanceOrCompleteCurrentRecovery() {
        val old=Any();val current=Any();val f=RecoveryPredictionFence<Any>()
        f.bindCurrent(old);f.suspend(old,"old");f.bindCurrent(current);f.suspend(current,"current")
        val token=Any();f.beginAuthority(old,token)
        repeat(50) { f.nativeTick(old);f.inputSampled(old);assertFalse(f.naturalTickEnd(old,true)) }
        assertFalse(f.handlerCompleted(old,token));assertTrue(f.isSuspended(current));assertEquals("current",f.reason(current))
    }
    @Test fun anAlreadyArrivedButQueuedPositionKeepsAdmissionClosed() {
        val stream=Any();val f=RecoveryPredictionFence<Any>();f.bindCurrent(stream);f.suspend(stream,"failed")
        val contract=com.hawkslol.zpbw.nativebridge.NativeTeleportContract.sequence
        authority(f,stream,contract);repeat(20) { f.nativeTick(stream) };f.inputSampled(stream)
        f.received(stream);assertFalse(f.naturalTickEnd(stream,true))
        val token=Any();f.beginAuthority(stream,token);f.processed(stream)
        contract.forEach { f.nativeResponse(stream,token,it) };assertTrue(f.handlerCompleted(stream,token))
        assertFalse(f.naturalTickEnd(stream,true));f.inputSampled(stream);assertTrue(f.naturalTickEnd(stream,true))
    }
    @Test fun abortedQueuedHandlersCannotSupplyAuthorityOrLeaveAnIngressCountStuck() {
        val stream=Any();val f=RecoveryPredictionFence<Any>();f.bindCurrent(stream);f.suspend(stream,"failed")
        f.received(stream);f.beginAuthority(stream,Any());f.processed(stream);f.invalidateAuthority(stream)
        repeat(20) { f.nativeTick(stream) };f.inputSampled(stream);assertFalse(f.naturalTickEnd(stream,true))
        authority(f,stream,com.hawkslol.zpbw.nativebridge.NativeTeleportContract.sequence)
        f.inputSampled(stream);assertTrue(f.naturalTickEnd(stream,true))
    }
    @Test fun nettyIngressCannotBeMistakenForCompletedAuthorityAcrossAQueuedBatch() {
        val stream=Any();val f=RecoveryPredictionFence<Any>();f.bindCurrent(stream);f.suspend(stream,"failed")
        val thread=Thread { repeat(200) { f.received(stream) } };thread.start();thread.join(1_000)
        assertFalse(thread.isAlive)
        authority(f,stream,com.hawkslol.zpbw.nativebridge.NativeTeleportContract.sequence)
        repeat(20) { f.nativeTick(stream) };f.inputSampled(stream)
        repeat(199) { f.processed(stream) };assertFalse(f.naturalTickEnd(stream,true))
        f.processed(stream);assertTrue(f.naturalTickEnd(stream,true))
    }
}
