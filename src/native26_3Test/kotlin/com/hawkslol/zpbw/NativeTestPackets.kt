package com.hawkslol.zpbw

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket
import net.minecraft.network.protocol.game.ServerboundPunchPacket

object NativeTestPackets {
    fun swing(): Packet<*> = ServerboundPunchPacket.INSTANCE
    fun accept(id: Int): Packet<*> = ServerboundAcceptTeleportationPacket(id, 0.0, 0.0, 0.0, 0f, 0f)
}
