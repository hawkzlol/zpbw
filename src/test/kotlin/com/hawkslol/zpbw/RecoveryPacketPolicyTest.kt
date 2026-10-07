package com.hawkslol.zpbw

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.*
import net.minecraft.server.Bootstrap
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Input
import kotlin.test.*

class RecoveryPacketPolicyTest {
    companion object { init { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() } }

    @Test fun abandonedStallHistoryCannotBurstSimulationTicksOrExecuteWorldActions() {
        val ledger = ChainLedger<String, Packet<*>>()
        val position = TransmittedPositionHistory()
        position.position(0.5, 70.0, 0.5); position.tickEnd()
        ledger.add(1, "warp"); ledger.cut(1)
        repeat(27) { tick ->
            ledger.retain(ServerboundPlayerInputPacket(Input(true, false, false, false, false, true, false)))
            ledger.retain(ServerboundMovePlayerPacket.Pos(tick + 1.5, 74.0, 5.5, true, false))
            ledger.retain(ServerboundClientTickEndPacket.INSTANCE)
        }
        ledger.retain(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
            BlockPos.ZERO, Direction.UP, 1), action = true)
        ledger.retain(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
        ledger.retain(ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 2, 0f, 0f), action = true)
        val surviving = ledger.clear().filterNot(RecoveryPacketPolicy::discard)
        assertTrue(surviving.isEmpty())
        assertEquals(0, ledger.count)
        assertTrue(ledger.canAdd)
        assertEquals(0, position.reminder) // No cancelled TickEnd was transmitted.
        assertEquals(TransmittedPositionHistory.Position(0.5, 70.0, 0.5), position.position)
    }

    @Test fun terminalCancellationPreservesSlotAndContainerSynchronizationInOriginalOrder() {
        val slot = ServerboundSetCarriedItemPacket(4)
        val button = ServerboundContainerButtonClickPacket(1, 0)
        val close = ServerboundContainerClosePacket(1)
        val nextSlot = ServerboundSetCarriedItemPacket(2)
        val journal = listOf<Packet<*>>(slot, ServerboundClientTickEndPacket.INSTANCE,
            ServerboundAttackPacket(42), button, ServerboundPlayerInputPacket(Input.EMPTY), close, nextSlot)
        val remaining = journal.filterNot(RecoveryPacketPolicy::discard)
        assertEquals(listOf(slot, button, close, nextSlot), remaining)
        remaining.zip(listOf(slot, button, close, nextSlot)).forEach { (a, b) -> assertSame(a, b) }
    }

    @Test fun onlySprintTransitionsAreObsoletePlayerCommands() {
        for (action in ServerboundPlayerCommandPacket.Action.entries) {
            assertEquals(action in setOf(ServerboundPlayerCommandPacket.Action.START_SPRINTING,
                ServerboundPlayerCommandPacket.Action.STOP_SPRINTING), RecoveryPacketPolicy.sprintTransition(action), action.name)
        }
        assertFalse(RecoveryPacketPolicy.discard(ServerboundAcceptTeleportationPacket(18)))
    }

    @Test fun successfulJournalStillReplaysEveryOriginalTickAndInputAfterBothGenuineResponses() {
        val ledger = ChainLedger<String, Packet<*>>()
        ledger.add(1, "warp"); ledger.cut(1)
        val packets = listOf<Packet<*>>(ServerboundPlayerInputPacket(Input.EMPTY),
            ServerboundMovePlayerPacket.Pos(5.5, 74.05, 5.5, false, false), ServerboundClientTickEndPacket.INSTANCE)
        packets.forEach { ledger.retain(it) }
        ledger.genuine(1, 12)
        ledger.response(12, true)
        assertFailsWith<IllegalStateException> { ledger.settle(1, 12) }
        ledger.response(12, false)
        val replay = ledger.settle(1, 12)
        assertEquals(packets, replay)
        replay.forEachIndexed { i, packet -> assertSame(packets[i], packet) }
    }
}
