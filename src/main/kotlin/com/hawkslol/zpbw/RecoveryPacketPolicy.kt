package com.hawkslol.zpbw

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.*

/** Terminal cancellation does not replay simulation time belonging to an abandoned prediction.
 * Slot/container synchronization retains its original FIFO order. Successful coalescing and
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
