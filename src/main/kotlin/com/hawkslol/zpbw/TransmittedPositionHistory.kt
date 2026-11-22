package com.hawkslol.zpbw

/** Native sender history advances only for packets that actually left the holding journal.
 * Recovery uses it to restore vanilla's delta/heartbeat decision, never to force a packet.
 */
class TransmittedPositionHistory {
    data class Position(val x: Double, val y: Double, val z: Double)
    var position: Position? = null; private set
    var reminder = 0; private set
    var positionlessRequests = 0; private set
    var awaitingPosition = false; private set
    private var positionInTick = false
    fun position(x: Double, y: Double, z: Double, regular: Boolean = true) {
        position = Position(x, y, z)
        if (regular) { reminder = 0; positionInTick = true }
    }
    fun tickEnd() {
        if (!positionInTick) reminder = (reminder + 1).coerceAtMost(20)
        positionInTick = false
    }
    /** Uncancelled native send-delegate returns, not socket completion or server acceptance.
     * A combined teleport ACK is not a movement request and must not reset this history. */
    fun movementRequested(hasPosition: Boolean) {
        if (hasPosition) { positionlessRequests = 0; awaitingPosition = false }
        else positionlessRequests = (positionlessRequests + 1).coerceAtMost(20)
    }
    /** Reserve the next original source movement and one independent rotation reply.
     * Stop extending the journal before coalescing can discard a due native position.
     * Additional server-driven rotation bursts remain separately observable. */
    fun waitForPosition(coalescedTickEnds: Int = 0): Boolean {
        require(coalescedTickEnds in 0..512)
        // Coalescing drops obsolete Move objects but forwards their original TickEnds.
        // Include that known envelope BEFORE a new use can consume it. The first end
        // after an already requested position does not advance vanilla's reminder.
        val projected = reminder + (coalescedTickEnds - if (positionInTick) 1 else 0).coerceAtLeast(0)
        if (projected >= 19 || positionlessRequests >= 18) awaitingPosition = true
        return awaitingPosition
    }
    fun reset() {
        position = null; reminder = 0; positionInTick = false
        positionlessRequests = 0; awaitingPosition = false
    }
}
