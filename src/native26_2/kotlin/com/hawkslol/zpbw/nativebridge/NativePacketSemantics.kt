package com.hawkslol.zpbw.nativebridge

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundSwingPacket

object NativePacketSemantics {
    fun swing(packet: Packet<*>): Boolean = packet is ServerboundSwingPacket
}
