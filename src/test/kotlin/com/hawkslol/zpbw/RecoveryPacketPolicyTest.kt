package com.hawkslol.zpbw

import com.hawkslol.zpbw.nativebridge.NativeResponseStep

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
        val ledger = ChainLedger<String, Packet<*>>(responseContract = TestResponseContracts.PAIR)
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
        ledger.retain(NativeTestPackets.swing())
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
        assertFalse(RecoveryPacketPolicy.discard(NativeTestPackets.accept(18)))
    }

    @Test fun successfulJournalStillReplaysEveryOriginalTickAndInputAfterBothGenuineResponses() {
        val ledger = ChainLedger<String, Packet<*>>(responseContract = TestResponseContracts.PAIR)
        ledger.add(1, "warp"); ledger.cut(1)
        val packets = listOf<Packet<*>>(ServerboundPlayerInputPacket(Input.EMPTY),
            ServerboundMovePlayerPacket.Pos(5.5, 74.05, 5.5, false, false), ServerboundClientTickEndPacket.INSTANCE)
        packets.forEach { ledger.retain(it) }
        ledger.genuine(1, 12)
        ledger.response(12, NativeResponseStep.ACCEPT_ID)
        assertFailsWith<IllegalStateException> { ledger.settle(1, 12) }
        ledger.response(12, NativeResponseStep.POSITION_ROTATION)
        val replay = ledger.settle(1, 12)
        assertEquals(packets, replay)
        replay.forEachIndexed { i, packet -> assertSame(packets[i], packet) }
    }

    @Test fun twoAndRollingThreeWarpsReleaseTheUnmodifiedFinalJournalForBothNativeContracts() {
        for (contract in listOf(TestResponseContracts.PAIR, TestResponseContracts.COMBINED)) {
            for (warps in 2..3) {
                val ledger = ChainLedger<String, Packet<*>>(responseContract = contract)
                ledger.configureMaximum(2)
                fun issue(generation: Long) { ledger.add(generation, "warp$generation"); ledger.cut(generation) }
                fun confirm(generation: Long, id: Int): List<Packet<*>> {
                    ledger.genuine(generation, id)
                    assertFailsWith<IllegalStateException> { ledger.settle(generation, id) }
                    for (step in contract) ledger.response(id, step)
                    return ledger.settle(generation, id)
                }
                issue(1); issue(2)
                assertTrue(confirm(1, 86).isEmpty())
                if (warps == 3) issue(3)
                val packets = listOf<Packet<*>>(ServerboundSetCarriedItemPacket(2),
                    ServerboundPlayerInputPacket(Input.EMPTY),
                    ServerboundMovePlayerPacket.Pos(0.5, 72.0, 0.75, true, false),
                    ServerboundClientTickEndPacket.INSTANCE, ServerboundContainerClosePacket(1))
                packets.forEach { ledger.retain(it) }
                if (warps == 3) {
                    assertTrue(confirm(2, 87).isEmpty())
                    assertEquals(packets.size, ledger.size)
                }
                val released = confirm(warps.toLong(), 85 + warps)
                assertEquals(0, ledger.count)
                assertEquals(packets.size, released.size)
                released.forEachIndexed { index, packet -> assertSame(packets[index], packet) }
                assertTrue(ledger.canAdd)
            }
        }
    }

}
