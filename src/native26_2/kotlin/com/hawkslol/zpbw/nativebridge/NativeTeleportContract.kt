package com.hawkslol.zpbw.nativebridge

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.phys.Vec3

object NativeTeleportContract {
    const val responseIncludesMovementPosition = true
    val sequence = listOf(NativeResponseStep.ACCEPT_ID, NativeResponseStep.POSITION_ROTATION)
    fun observe(packet: Packet<*>): NativeResponse? = when (packet) {
        is ServerboundAcceptTeleportationPacket -> NativeResponse(NativeResponseStep.ACCEPT_ID, packet.id)
        is ServerboundMovePlayerPacket.PosRot -> NativeResponse(NativeResponseStep.POSITION_ROTATION,
            position = Vec3(packet.getX(0.0), packet.getY(0.0), packet.getZ(0.0)),
            yaw = packet.getYRot(0f), pitch = packet.getXRot(0f),
            wireCollision = packet.isOnGround to packet.horizontalCollision())
        else -> null
    }
    fun combinedAcknowledgement(packet: Packet<*>): NativeResponse? = null
}
