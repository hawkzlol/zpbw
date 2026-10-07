package com.hawkslol.zpbw

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.world.entity.player.Input

/** Vanilla caches advance even when its send is captured. Retain the actual send-delegate
 * history independently so terminal recovery can resume native change detection.
 */
class TransmittedControlHistory {
    var input: Input = Input.EMPTY; private set
    var sprinting: Boolean = false; private set

    fun sent(packet: Packet<*>) {
        when (packet) {
            is ServerboundPlayerInputPacket -> input = packet.input
            is ServerboundPlayerCommandPacket -> sentCommand(packet.action)
        }
    }

    fun sentCommand(action: ServerboundPlayerCommandPacket.Action) {
        when (action) {
            ServerboundPlayerCommandPacket.Action.START_SPRINTING -> sprinting = true
            ServerboundPlayerCommandPacket.Action.STOP_SPRINTING -> sprinting = false
            else -> Unit
        }
    }

    fun reset() { input = Input.EMPTY; sprinting = false }
}
