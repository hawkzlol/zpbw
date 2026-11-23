package com.hawkslol.zpbw

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.*

/** Cancelling speculative movement does not replay its simulation time.
 * Slot/container synchronization retains its original FIFO order. Intermediate coalescing and
 * genuine native teleport responses do not use this policy.
 */
object RecoveryPacketPolicy {
    fun sprintTransition(action: ServerboundPlayerCommandPacket.Action): Boolean =
        action == ServerboundPlayerCommandPacket.Action.START_SPRINTING ||
            action == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING

    fun discard(packet: Packet<*>): Boolean = when (packet) {
        is ServerboundMovePlayerPacket, is ServerboundClientTickEndPacket,
        is ServerboundPlayerInputPacket -> true
        is ServerboundPlayerCommandPacket -> sprintTransition(packet.action)
        else -> FastActionPolicy.worldAction(packet)
    }
}
