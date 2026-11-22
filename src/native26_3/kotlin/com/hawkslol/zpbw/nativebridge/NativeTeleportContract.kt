package com.hawkslol.zpbw.nativebridge

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket
import net.minecraft.world.phys.Vec3

object NativeTeleportContract {
    const val responseIncludesMovementPosition = false
    val sequence = listOf(NativeResponseStep.COMBINED_ACCEPT_POSITION_ROTATION)
    fun observe(packet: Packet<*>): NativeResponse? = combinedAcknowledgement(packet)
    fun combinedAcknowledgement(packet: Packet<*>): NativeResponse? =
        (packet as? ServerboundAcceptTeleportationPacket)?.let {
            NativeResponse(NativeResponseStep.COMBINED_ACCEPT_POSITION_ROTATION, it.id(),
                Vec3(it.x(), it.y(), it.z()), it.yRot(), it.xRot())
        }
}
