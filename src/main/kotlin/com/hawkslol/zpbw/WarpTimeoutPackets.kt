package com.hawkslol.zpbw

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket

/** Read-only packet classification shared by the Netty hook and offline packet-stream tests. */
object WarpTimeoutPackets {
    fun <S : Any> receive(clock: ServerTickTimeouts<S>, session: S, packet: Packet<*>): List<ServerTickTimeouts.Expiry> = when (packet) {
        is ClientboundPingPacket -> clock.ping(session, packet.id)
        is ClientboundPlayerPositionPacket -> { clock.teleport(session); emptyList() }
        is ClientboundRespawnPacket, is ClientboundLoginPacket, is ClientboundDisconnectPacket -> {
            clock.suspend(session); emptyList()
        }
        is ClientboundBundlePacket -> packet.subPackets().flatMap { receive(clock, session, it) }
        else -> emptyList()
    }
}
