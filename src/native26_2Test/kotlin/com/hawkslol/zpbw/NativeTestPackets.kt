package com.hawkslol.zpbw

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.world.InteractionHand

object NativeTestPackets {
    fun swing(): Packet<*> = ServerboundSwingPacket(InteractionHand.MAIN_HAND)
    fun accept(id: Int): Packet<*> = ServerboundAcceptTeleportationPacket(id)
}
