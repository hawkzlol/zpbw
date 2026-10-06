package com.hawkslol.zpbw

/** Native sender history advances only for packets that actually left the holding journal.
 * Recovery uses it to restore vanilla's delta/heartbeat decision, never to force a packet.
 */
class TransmittedPositionHistory {
    data class Position(val x: Double, val y: Double, val z: Double)
    var position: Position? = null; private set
    var reminder = 0; private set
    private var positionInTick = false
    fun position(x: Double, y: Double, z: Double, regular: Boolean = true) {
        position = Position(x, y, z)
        if (regular) { reminder = 0; positionInTick = true }
    }
    fun tickEnd() {
        if (!positionInTick) reminder = (reminder + 1).coerceAtMost(20)
        positionInTick = false
    }
    fun reset() { position = null; reminder = 0; positionInTick = false }
}
