package com.hawkslol.zpbw

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import kotlin.test.*

class NativeRotationResponseScopeTest {
    private inline fun response(owner: Any, packet: Any, block: () -> Unit) {
        val scope = NativeRotationResponseScope.beginResponse(owner, Any(), packet)
        try { block() } finally { NativeRotationResponseScope.endResponse(scope) }
    }
    private inline fun send(owner: Any, packet: Any, block: () -> Unit) {
        val scope = NativeRotationResponseScope.beginSend(owner, packet)
        try { block() } finally { NativeRotationResponseScope.endSend(scope) }
    }

    @Test fun onlyOriginalInvocationPassesCaptureAndItCannotBeReplayed() {
        val owner = Any(); val packet = Any()
        assertFalse(NativeRotationResponseScope.isCurrentResponse(owner, packet))
        response(owner, packet) {
            assertFalse(NativeRotationResponseScope.isCurrentResponse(owner, packet))
            send(owner, packet) { assertTrue(NativeRotationResponseScope.isCurrentResponse(owner, packet)) }
            send(owner, packet) { assertFalse(NativeRotationResponseScope.isCurrentResponse(owner, packet)) }
        }
        send(owner, packet) { assertFalse(NativeRotationResponseScope.isCurrentResponse(owner, packet)) }
    }

    @Test fun nestedUnrelatedAndSameObjectSendsDoNotInheritTheOccurrence() {
        val owner = Any(); val packet = Any(); val unrelated = Any()
        response(owner, packet) {
            send(owner, packet) {
                assertTrue(NativeRotationResponseScope.isCurrentResponse(owner, packet))
                send(owner, unrelated) { assertFalse(NativeRotationResponseScope.isCurrentResponse(owner, unrelated)) }
                send(owner, packet) { assertFalse(NativeRotationResponseScope.isCurrentResponse(owner, packet)) }
                assertTrue(NativeRotationResponseScope.isCurrentResponse(owner, packet))
            }
        }
    }

    @Test fun differentConnectionOrPacketCannotClaimAndNestedPacketCannotClaimUnderIt() {
        val owner = Any(); val packet = Any()
        response(owner, packet) {
            send(Any(), packet) {
                assertFalse(NativeRotationResponseScope.isCurrentResponse(owner, packet))
                send(owner, packet) { assertFalse(NativeRotationResponseScope.isCurrentResponse(owner, packet)) }
            }
            send(owner, Any()) { assertFalse(NativeRotationResponseScope.isCurrentResponse(owner, packet)) }
            send(owner, packet) { assertTrue(NativeRotationResponseScope.isCurrentResponse(owner, packet)) }
        }
    }

    @Test fun exceptionFinallyCleanupDoesNotLeakIntoNextHandlerOrSend() {
        val owner = Any(); val packet = Any()
        assertFailsWith<IllegalArgumentException> {
            response(owner, packet) { send(owner, packet) { throw IllegalArgumentException("native failure") } }
        }
        assertFalse(NativeRotationResponseScope.isCurrentResponse(owner, packet))
        response(owner, packet) { send(owner, packet) { assertTrue(NativeRotationResponseScope.isCurrentResponse(owner, packet)) } }
    }

    @Test fun nativeOccurrenceIsThreadLocalAndCannotAuthorizeNettyOrAnotherSession() {
        val owner = Any(); val packet = Any(); var otherThreadClaimed = true
        response(owner, packet) {
            send(owner, packet) {
                Thread { send(owner, packet) { otherThreadClaimed = NativeRotationResponseScope.isCurrentResponse(owner, packet) } }.apply { start(); join() }
                assertFalse(otherThreadClaimed)
                assertFalse(NativeRotationResponseScope.isCurrentResponse(Any(), packet))
            }
        }
    }

    @Test fun responseIsNotRetainedCoalescedOrDroppedByRecoveryAndOrdinaryMovementStillIs() {
        val owner = Any(); val reply = ServerboundMovePlayerPacket.Rot(73f, -12f, false, false)
        val ordinary = ServerboundMovePlayerPacket.Rot(14f, 2f, true, true)
        val chain = ChainLedger<String, Any>(responseContract = TestResponseContracts.PAIR)
        chain.add(1, "issued"); chain.cut(1)
        fun capture(packet: ServerboundMovePlayerPacket, recovery: Boolean): Boolean {
            if (NativeRotationResponseScope.isCurrentResponse(owner, packet)) return false
            if (recovery && RecoveryPacketPolicy.discard(packet)) return true
            chain.retain(packet); return true
        }
        response(owner, reply) { send(owner, reply) {
            assertFalse(capture(reply, false)); assertFalse(capture(reply, true))
            send(owner, ordinary) { assertTrue(capture(ordinary, false)) }
        } }
        assertSame(ordinary, chain.coalesce().single())
        assertEquals(1, chain.count); assertNull(chain.head!!.realId)
        send(owner, reply) { assertTrue(capture(reply, true)) }
        assertEquals(0, chain.size); assertEquals(1, chain.count)
    }

    @Test fun originalReplyUpdatesWireLookFlagsWithoutSimulationOrTickAdvance() {
        val owner = Any(); val packet = ServerboundMovePlayerPacket.Rot(73f, -12f, false, false)
        val collisions = TransmittedCollisionHistory(); collisions.sent(true, true, true)
        val positions = TransmittedPositionHistory(); positions.position(1.0, 2.0, 3.0, true)
        val before = positions.reminder
        var sentLook: Pair<Float, Float>? = null
        response(owner, packet) { send(owner, packet) {
            positions.movementRequested(packet.hasPosition())
            sentLook = packet.getYRot(0f) to packet.getXRot(0f)
            collisions.sent(packet.isOnGround, packet.horizontalCollision(),
                !NativeRotationResponseScope.isCurrentResponse(owner, packet))
        } }
        assertEquals(73f to -12f, sentLook)
        assertEquals(false to false, collisions.wire); assertEquals(true to true, collisions.simulation)
        assertEquals(before, positions.reminder)
        assertEquals(1, positions.positionlessRequests)
    }

    @Test fun productionBindingProtectsBeforeCaptureAndReturnsBeforeSourceStateMutation() {
        val runtime = java.nio.file.Files.readString(EffectiveSources.path("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
        val capture = runtime.substringAfter("fun capture(").substringBefore("fun afterSend(")
        assertTrue(capture.indexOf("NativeRotationResponseScope.isCurrentResponse") < capture.indexOf("RecoveryPacketPolicy.discard"))
        val sent = runtime.substringAfter("fun afterSend(").substringBefore("fun sourceTick(")
        assertTrue(sent.indexOf("if (rotationResponse)") < sent.indexOf("SOURCE_STATE_REQUESTED"))
        assertTrue(sent.contains("handling == null && !rotationResponse"))
        val mixin = java.nio.file.Files.readString(EffectiveSources.path("src/main/java/com/hawkslol/zpbw/mixin/ZpbwRotationMixin.java"))
        assertTrue(mixin.contains("Connection;send(Lnet/minecraft/network/protocol/Packet;)V\"), require = 1"))
        assertTrue(mixin.contains("original.call(connection, response)"))
        assertTrue(mixin.contains("finally { if (scope != null) NativeRotationResponseScope.endResponse(scope); }"))
        assertFalse(mixin.contains("new Serverbound"))
    }
}
