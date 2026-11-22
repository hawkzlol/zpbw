package com.hawkslol.zpbw.nativebridge

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundPunchPacket

object NativePacketSemantics {
    // Classification only: singleton identity never grants action ownership.
    fun swing(packet: Packet<*>): Boolean = packet is ServerboundPunchPacket
}
