package com.hawkslol.zpbw

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.*
import net.minecraft.server.Bootstrap
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Input
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FastActionPolicyTest {
    companion object { init { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() } }

    @Test fun pearlAndSlotStayInOrderWithMovementUntilBothNativeResponses() {
        val ledger = ChainLedger<String, Packet<*>>()
        ledger.add(1, "warp"); ledger.cut(1)
        val move = ServerboundMovePlayerPacket.PosRot(3.5, 100.05, 8.5, 45f, -10f, false, false)
        val slot = ServerboundSetCarriedItemPacket(3)
        val pearl = ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 81, 70f, -20f)
        val tickEnd = ServerboundClientTickEndPacket.INSTANCE
        for (packet in listOf(move, slot, pearl, tickEnd)) {
            ledger.retain(packet, action = FastActionPolicy.retains(packet))
        }
        assertTrue(FastActionPolicy.keepPendingOnVanillaUse(ledger.count))
        assertEquals(1, ledger.count); assertNull(ledger.head!!.realId)
        assertEquals(4, ledger.size); assertTrue(ledger.hasActions)
        assertFalse(ledger.canAdd)
        assertFailsWith<IllegalStateException> { ledger.coalesce() }
        assertEquals(81, pearl.sequence); assertEquals(70f, pearl.yRot); assertEquals(-20f, pearl.xRot)
        ledger.genuine(1, 13)
        assertFailsWith<IllegalStateException> { ledger.settle(1, 13) }
        ledger.response(13, true)
        assertFailsWith<IllegalStateException> { ledger.settle(1, 13) }
        ledger.response(13, false)
        val released = ledger.settle(1, 13)
        assertEquals(4, released.size)
        listOf(move, slot, pearl, tickEnd).forEachIndexed { i, packet -> assertSame(packet, released[i]) }
        assertFalse(ledger.hasActions); assertEquals(0, ledger.size)
    }

    @Test fun actionsDoNotCoalesceOrSettleAnyOverlappingPrediction() {
        val ledger = ChainLedger<String, Packet<*>>()
        for (id in 1L..3L) { ledger.add(id, "warp$id"); ledger.cut(id) }
        val movement = ServerboundMovePlayerPacket.PosRot(Vec3.ZERO, 0f, 0f, false, false)
        ledger.retain(movement)
        val action = ServerboundUseItemPacket(InteractionHand.OFF_HAND, 12, 0f, 0f)
        ledger.retain(action, action = true)
        assertEquals(3, ledger.count); assertEquals(2, ledger.size)
        for (id in 1L..3L) {
            ledger.genuine(id, id.toInt()); ledger.response(id.toInt(), true); ledger.response(id.toInt(), false)
            val released = ledger.settle(id, id.toInt())
            if (id < 3) { assertTrue(released.isEmpty()); assertTrue(ledger.hasActions) }
            else { assertSame(movement, released[0]); assertSame(action, released[1]); assertFalse(ledger.hasActions) }
        }
    }

    @Test fun attacksBlockUsesDiggingSwingAndInventoryCloseJoinJournal() {
        val hit = BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false)
        val packets = listOf<Packet<*>>(
            ServerboundAttackPacket(42),
            ServerboundInteractPacket(42, InteractionHand.MAIN_HAND, Vec3.ZERO, false),
            ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 12),
            ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, BlockPos.ZERO, Direction.UP, 13),
            ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.DROP_ITEM, BlockPos.ZERO, Direction.DOWN),
            ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN),
            ServerboundSwingPacket(InteractionHand.MAIN_HAND), ServerboundContainerClosePacket(1),
            ServerboundContainerButtonClickPacket(1, 0))
        packets.forEach { packet ->
            assertTrue(FastActionPolicy.retains(packet), packet.javaClass.simpleName)
        }
    }

    @Test fun movementInputTickEndAndTeleportResponsesRemainOutsideActionPolicy() {
        val packets = listOf<Packet<*>>(
            ServerboundMovePlayerPacket.PosRot(Vec3.ZERO, 0f, 0f, false, false),
            ServerboundPlayerInputPacket(Input.EMPTY),
            ServerboundClientTickEndPacket.INSTANCE,
            ServerboundAcceptTeleportationPacket(7))
        packets.forEach { assertFalse(FastActionPolicy.retains(it), it.javaClass.simpleName) }
    }

    @Test fun failedJournalClearsActionBarrierAndCannotLeakToNewChain() {
        val ledger = ChainLedger<String, String>(capacity = 2)
        ledger.add(1, "first"); ledger.cut(1)
        ledger.retain("slot", action = true); ledger.retain("use", action = true)
        assertFailsWith<IllegalStateException> { ledger.retain("overflow", action = true) }
        assertEquals(listOf("slot", "use"), ledger.clear())
        assertFalse(ledger.hasActions); assertTrue(ledger.canAdd)
        ledger.add(2, "new world"); ledger.cut(2)
        assertTrue(ledger.coalesce().isEmpty())
    }

    @Test fun failedPredictionCancelsWorldActionsButPreservesSlotAndInventorySynchronization() {
        assertTrue(FastActionPolicy.worldAction(ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 4, 0f, 0f)))
        assertTrue(FastActionPolicy.worldAction(ServerboundAttackPacket(4)))
        assertTrue(FastActionPolicy.worldAction(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, BlockPos.ZERO, Direction.UP, 5)))
        assertFalse(FastActionPolicy.worldAction(ServerboundSetCarriedItemPacket(5)))
        assertFalse(FastActionPolicy.worldAction(ServerboundContainerClosePacket(0)))
        assertFalse(FastActionPolicy.worldAction(ServerboundPlayerInputPacket(Input.EMPTY)))
        assertFalse(FastActionPolicy.worldAction(ServerboundClientTickEndPacket.INSTANCE))
        val runtime = Files.readString(Path.of("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
        assertContains(runtime, "forwardRecoveryEnvelope(p.stream, held)")
    }

    @Test fun deferredNativeActionDoesNotConsumeItemOrSequenceWhenPredictionIsCancelled() {
        val ledger = ChainLedger<String, Runnable>()
        var itemCount = 16; var sequence = 8
        val action = Runnable { itemCount--; sequence++ }
        ledger.add(1, "warp"); ledger.cut(1); ledger.retain(action, action = true)
        assertEquals(16, itemCount); assertEquals(8, sequence)
        ledger.clear() // Discard invocation; there is no packet or local item mutation to undo.
        assertEquals(16, itemCount); assertEquals(8, sequence)
        ledger.add(2, "next warp"); ledger.cut(2); ledger.retain(action, action = true)
        ledger.genuine(2, 42); ledger.response(42, true); ledger.response(42, false)
        ledger.settle(2, 42).forEach { it.run() }
        assertEquals(15, itemCount); assertEquals(9, sequence)
    }

    @Test fun nativeActionInvocationIsDeferredAheadOfVanillaPredictionAndCannotRecursivelyQueue() {
        val runtime = Files.readString(Path.of("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
        val mixin = Files.readString(Path.of("src/main/java/com/hawkslol/zpbw/mixin/ZpbwUseMixin.java"))
        assertContains(runtime, "if (bypass || dispatchingInput != null || !mc.isSameThread) return false")
        assertContains(runtime, "chain.retain(Held.Action")
        assertContains(runtime, "pose.restore(player); player.inventory.setSelectedSlot(slot)")
        assertContains(runtime, "advanced.restore(player); player.inventory.setSelectedSlot(currentSlot)")
        assertContains(runtime, "accessor.`zpbw\$syncCarriedItem`()")
        assertContains(mixin, "ZpbwRuntime.deferMining(fixed, false")
        assertContains(mixin, "ZpbwRuntime.deferMining(fixed, true")
        for (kind in listOf("block_use", "entity_use", "attack"))
            assertContains(mixin, "ZpbwRuntime.deferAction(\"$kind\"")
    }

    @Test fun runtimeHooksApplyPolicyBeforeRecoveryWithoutChangingNativeOwnership() {
        val runtime = Files.readString(Path.of("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
        val client = Files.readString(Path.of("src/main/kotlin/com/hawkslol/zpbw/ZpbwClient.kt"))
        assertTrue(runtime.indexOf("FastActionPolicy.keepPendingOnVanillaUse") < runtime.indexOf("recover(\"invalid_dependent_prediction\")"))
        assertTrue(runtime.indexOf("FastActionPolicy.retains(packet)") < runtime.indexOf("chain.retain(Held.Wire(packet"))
        assertFalse(runtime.contains("settings.fast"))
        assertFalse(client.contains("lit(\"fast\")"))
        assertFalse(client.contains("configureFast"))
        val reset = runtime.substringAfter("fun reset() {").substringBefore("fun status()")
        val attach = runtime.substringAfter("private fun attach(").substringBefore("private fun identity(")
        assertFalse(reset.contains("fast =")); assertFalse(attach.contains("fast ="))
        assertContains(runtime, "if (bypass || handling != null || connection !== connected) return false")
        assertTrue(runtime.indexOf("if (!identity(tail.value))") < runtime.indexOf("FastActionPolicy.retains(packet)"))
    }
}
