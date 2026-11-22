package com.hawkslol.zpbw

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.*
import com.hawkslol.zpbw.nativebridge.NativePacketSemantics

/** Responsive local actions join the held FIFO; never bypass native teleport response ownership. */
object FastActionPolicy {
    fun keepPendingOnVanillaUse(pending: Int) = pending > 0

    fun worldAction(packet: Packet<*>): Boolean = when (packet) {
        is ServerboundUseItemPacket, is ServerboundUseItemOnPacket,
        is ServerboundAttackPacket, is ServerboundInteractPacket,
        is ServerboundPlayerActionPacket -> true
        else -> NativePacketSemantics.swing(packet)
    }

    fun retains(packet: Packet<*>): Boolean = when (packet) {
        is ServerboundSetCarriedItemPacket,
        is ServerboundUseItemPacket,
        is ServerboundUseItemOnPacket,
        is ServerboundAttackPacket,
        is ServerboundInteractPacket,
        is ServerboundPlayerActionPacket,
        is ServerboundContainerClickPacket,
        is ServerboundContainerClosePacket,
        is ServerboundContainerButtonClickPacket,
        is ServerboundContainerSlotStateChangedPacket,
        is ServerboundPlaceRecipePacket,
        is ServerboundSetCreativeModeSlotPacket,
        is ServerboundPickItemFromBlockPacket,
        is ServerboundPickItemFromEntityPacket,
        is ServerboundSelectBundleItemPacket,
        is ServerboundSelectTradePacket,
        is ServerboundRenameItemPacket,
        is ServerboundSetBeaconPacket,
        is ServerboundSignUpdatePacket,
        is ServerboundEditBookPacket -> true
        else -> NativePacketSemantics.swing(packet)
    }
}
